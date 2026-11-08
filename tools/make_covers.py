# -*- coding: utf-8 -*-
"""从 NAS 视频截帧，批量生成片单封面缩略图。

为什么需要它：电视端 App 不会自己截帧、也不会联网刮削海报，
它只会按固定命名在 NAS 上「找现成图片」。资源目录里没有图片时，
海报卡片就只剩标题首字占位。这个脚本负责把缩略图补出来。

流程：
  1. 读 tv.json（本地或 --from-nas）
  2. 每条 item 找到对应视频（file 直接用；folder 递归找第一个视频）
  3. ffmpeg 从 UNC 路径截 1~3 帧，挑信息量最大的一张，缩到 480px
  4. 上传到可写共享的 curated/covers/<hash>.jpg
  5. 给 item 写 "cover": "<共享名>:curated/covers/<hash>.jpg"
  6. 生成本地 tv.covers.json；加 --apply 才写回 NAS（写前自动备份）

用法：
  python tools/make_covers.py --dry-run          # 只看会处理哪些条目
  python tools/make_covers.py                    # 生成封面 + 上传，产出 tv.covers.json
  python tools/make_covers.py --apply            # 额外把 cover 字段写回 NAS 生效清单
  python tools/make_covers.py --force            # 已有封面也重新截帧
  python tools/make_covers.py --limit 3          # 只处理前 3 条，先试水
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
VIDEO_EXT = {".mp4", ".mkv", ".avi", ".ts", ".m2ts", ".mov", ".wmv",
             ".flv", ".webm", ".mpg", ".mpeg", ".rmvb", ".m4v", ".iso"}
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}

try:
    from smb.SMBConnection import SMBConnection
except ImportError:
    sys.exit("缺少 pysmb，请先运行：python -m pip install -r tools/requirements.txt")


# --------------------------------------------------------------------------- 配置

def load_nas(path: pathlib.Path) -> dict:
    if not path.exists():
        sys.exit(f"找不到 {path}，请从 nas.example.json 复制一份 nas.local.json 并填好账号")
    cfg = json.loads(path.read_text(encoding="utf-8"))
    for k in ("host", "user", "password", "share"):
        if not cfg.get(k):
            sys.exit(f"{path} 缺少 {k}")
    cfg.setdefault("playlistShare", cfg["share"])
    cfg.setdefault("playlistPath", "curated/tv.json")
    return cfg


def connect(cfg: dict) -> SMBConnection:
    c = SMBConnection(cfg["user"], cfg["password"], "kidtv-covers", "nas",
                      domain="", use_ntlm_v2=True, is_direct_tcp=True)
    if not c.connect(cfg["host"], int(cfg.get("port", 445)), timeout=15):
        raise RuntimeError(f"连不上 NAS {cfg['host']}")
    return c


def norm(p: str) -> str:
    return p.replace("\\", "/").strip("/")


def read_config(cfg: dict, args) -> tuple[dict, str]:
    """返回 (清单对象, 来源说明)。本地文件优先；--from-nas 读 NAS 生效清单。"""
    if args.from_nas:
        c = connect(cfg)
        try:
            buf = io.BytesIO()
            c.retrieveFile(cfg["playlistShare"], norm(cfg["playlistPath"]), buf, timeout=30)
            return json.loads(buf.getvalue().decode("utf-8-sig")), "NAS"
        finally:
            c.close()
    p = pathlib.Path(args.config)
    if not p.is_absolute():
        p = ROOT / p
    if not p.exists():
        sys.exit(f"找不到清单 {p}（可用 --from-nas 直接读 NAS 上的 tv.json）")
    return json.loads(p.read_text(encoding="utf-8-sig")), str(p)


def write_back(cfg: dict, data: dict) -> str:
    """把清单写回 NAS，写前备份 tv.backup.json，写完回读校验。"""
    share, path = cfg["playlistShare"], norm(cfg["playlistPath"])
    parent = path.rsplit("/", 1)[0]
    payload = (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    c = connect(cfg)
    try:
        if parent:
            for d in (parent, parent + "/covers"):
                try:
                    c.createDirectory(share, d)
                except Exception:
                    pass
        try:
            old = io.BytesIO()
            c.retrieveFile(share, path, old, timeout=20)
            c.storeFile(share, parent + "/tv.backup.json",
                        io.BytesIO(old.getvalue()), timeout=30)
        except Exception as e:
            print(f"  ! 备份失败（继续写）：{e}")
        c.storeFile(share, path, io.BytesIO(payload), timeout=45)
        back = io.BytesIO()
        c.retrieveFile(share, path, back, timeout=30)
        if len(back.getvalue()) != len(payload):
            raise RuntimeError("回读校验不一致，请检查 NAS 空间 / 权限")
        return f"已写回 {share}:{path}（{len(payload)} 字节，旧版备份为 tv.backup.json）"
    finally:
        c.close()


# --------------------------------------------------------------------------- SMB 辅助

def list_dir(c: SMBConnection, share: str, path: str):
    try:
        return [e for e in c.listPath(share, path.replace("/", "\\"))
                if e.filename not in (".", "..")]
    except Exception:
        return []


def first_video(c: SMBConnection, share: str, folder: str, depth: int = 0) -> str | None:
    """folder 条目：递归 3 层找一个视频，优先本层、名字自然排序。"""
    if depth > 3:
        return None
    entries = list_dir(c, share, folder)
    vids = sorted(
        (e.filename for e in entries if not e.isDirectory
         and pathlib.PurePosixPath(e.filename).suffix.lower() in VIDEO_EXT
         and not e.filename.startswith(".")),
        key=lambda s: [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)],
    )
    if vids:
        return norm(folder + "/" + vids[0])
    for e in sorted((e for e in entries if e.isDirectory), key=lambda x: x.filename):
        found = first_video(c, share, norm(folder + "/" + e.filename), depth + 1)
        if found:
            return found
    return None


def remote_exists(c: SMBConnection, share: str, path: str) -> bool:
    try:
        c.getAttributes(share, path.replace("/", "\\"))
        return True
    except Exception:
        return False


def upload(c: SMBConnection, share: str, folder: str, name: str, data: bytes):
    parts = folder.split("/")
    cur = ""
    for part in parts:
        cur = cur + "/" + part if cur else part
        try:
            c.createDirectory(share, cur.replace("/", "\\"))
        except Exception:
            pass
    target = (folder + "/" + name).replace("/", "\\")
    c.storeFile(share, target, io.BytesIO(data), timeout=40)


# --------------------------------------------------------------------------- 截帧

def unc(cfg: dict, share: str, rel: str) -> str:
    return "\\\\" + cfg["host"] + "\\" + share + "\\" + rel.replace("/", "\\")


def probe_duration(path: str) -> float:
    try:
        r = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=nw=1:nk=1", path],
            capture_output=True, text=True, timeout=40,
        )
        return max(0.0, float(r.stdout.strip()))
    except Exception:
        return 0.0


def grab(path: str, out: pathlib.Path, at: float, width: int, portrait: bool) -> bool:
    if portrait:
        # 电视海报卡是 200x284 竖版，直接把 16:9 画面 centerCrop 会切掉主体。
        # 这里用「放大模糊的同帧做背景 + 完整画面居中」拼一张 10:14 海报，不裁内容。
        h = round(width * 284 / 200 / 2) * 2
        flt = (
            f"[0:v]split=2[bg][fg];"
            f"[bg]scale={width}:{h}:force_original_aspect_ratio=increase,"
            f"crop={width}:{h},boxblur=18:2[bg2];"
            f"[fg]scale={width}:-2[fg2];"
            f"[bg2][fg2]overlay=(W-w)/2:(H-h)/2[v]"
        )
        tail = ["-filter_complex", flt, "-map", "[v]"]
    else:
        tail = ["-vf", f"scale={width}:-2"]
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
           "-ss", f"{at:.2f}", "-i", path, "-frames:v", "1"] + tail + \
          ["-q:v", "4", str(out)]
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=90)
        return r.returncode == 0 and out.exists() and out.stat().st_size > 3000
    except Exception:
        return False


def best_frame(path: str, tmpdir: pathlib.Path, width: int, tries: int,
               portrait: bool) -> bytes | None:
    """片头常是黑屏/台标，按比例试几个时间点，挑文件最大的一张（信息量最多）。"""
    dur = probe_duration(path)
    points = [max(1.0, dur * f) for f in (0.2, 0.45, 0.7)][:tries] if dur > 8 else [5.0]
    best, best_size = None, 0
    for i, t in enumerate(points):
        out = tmpdir / f"frame{i}.jpg"
        if grab(path, out, t, width, portrait):
            data = out.read_bytes()
            if len(data) > best_size:
                best, best_size = data, len(data)
    return best


# --------------------------------------------------------------------------- 主流程

def collect_items(data: dict):
    for sec in data.get("sections", []):
        for it in sec.get("items", []):
            if isinstance(it, dict) and (it.get("file") or it.get("folder")):
                yield it


def main():
    ap = argparse.ArgumentParser(description="从 NAS 视频截帧生成片单封面")
    ap.add_argument("--config", default="tv.json", help="本地清单路径（默认 tv.json）")
    ap.add_argument("--from-nas", action="store_true", help="直接读 NAS 上的生效清单")
    ap.add_argument("--nas-config", default="nas.local.json", help="NAS 账号文件")
    ap.add_argument("--video-share", default="", help="视频所在共享，默认用清单里的 nas.share")
    ap.add_argument("--cover-share", default="", help="封面存放共享，默认 playlistShare")
    ap.add_argument("--cover-dir", default="curated/covers", help="封面存放目录")
    ap.add_argument("--out", default="tv.covers.json", help="生成的清单文件名")
    ap.add_argument("--width", type=int, default=480, help="封面宽度像素")
    ap.add_argument("--tries", type=int, default=2, help="每个视频尝试几个时间点")
    ap.add_argument("--plain", action="store_true", help="输出原始 16:9 截帧，不拼成竖版海报")
    ap.add_argument("--limit", type=int, default=0, help="只处理前 N 条")
    ap.add_argument("--force", action="store_true", help="已有封面也重新截帧")
    ap.add_argument("--apply", action="store_true", help="把 cover 字段写回 NAS 生效清单")
    ap.add_argument("--dry-run", action="store_true", help="只打印计划，不截帧不写入")
    args = ap.parse_args()

    cfg = load_nas(ROOT / args.nas_config if not pathlib.Path(args.nas_config).is_absolute()
                   else pathlib.Path(args.nas_config))
    data, source = read_config(cfg, args)

    video_share = args.video_share or (data.get("nas") or {}).get("share") or cfg["share"]
    cover_share = args.cover_share or cfg["playlistShare"]
    cover_dir = norm(args.cover_dir)
    items = list(collect_items(data))
    if args.limit:
        items = items[: args.limit]

    print(f"清单来源 : {source}")
    print(f"视频共享 : {video_share}    封面共享 : {cover_share}    封面目录 : {cover_dir}")
    print(f"待处理   : {len(items)} 条" + ("   [DRY RUN]" if args.dry_run else ""))
    print("-" * 68)

    if args.dry_run:
        c = connect(cfg)
        try:
            for it in items:
                rel = norm(it.get("file") or it.get("folder"))
                target = rel if it.get("file") else (first_video(c, video_share, rel) or "<没找到视频>")
                print(f"  {it.get('title', '')[:28]:<30} -> {target}")
        finally:
            c.close()
        return

    c = connect(cfg)
    done, skipped, failed = 0, 0, []
    try:
        with tempfile.TemporaryDirectory(prefix="kidtv-covers-") as td:
            tmpdir = pathlib.Path(td)
            for idx, it in enumerate(items, 1):
                key = it.get("file") or it.get("folder")
                tag = hashlib.md5(norm(key).encode("utf-8")).hexdigest()[:14]
                name = tag + ".jpg"
                remote = cover_dir + "/" + name
                ref = f"{cover_share}:{remote}"

                if it.get("cover") and not args.force:
                    print(f"[{idx}/{len(items)}] 跳过（已手工指定封面）{it.get('title','')[:24]}")
                    skipped += 1
                    continue
                if not args.force and remote_exists(c, cover_share, remote):
                    it["cover"] = ref
                    print(f"[{idx}/{len(items)}] 复用已有封面 {it.get('title','')[:24]}")
                    done += 1
                    continue

                rel = norm(key)
                video = rel if it.get("file") else first_video(c, video_share, rel)
                if not video:
                    print(f"[{idx}/{len(items)}] 没找到视频，跳过 {it.get('title','')[:24]}")
                    failed.append((it.get("title", ""), "目录里没有视频"))
                    continue

                path = unc(cfg, video_share, video)
                img = best_frame(path, tmpdir, args.width, args.tries, not args.plain)
                if not img:
                    print(f"[{idx}/{len(items)}] 截帧失败 {it.get('title','')[:24]}  <- {video}")
                    failed.append((it.get("title", ""), "ffmpeg 截帧失败"))
                    continue
                upload(c, cover_share, cover_dir, name, img)
                it["cover"] = ref
                done += 1
                print(f"[{idx}/{len(items)}] OK  {it.get('title','')[:26]:<28} {len(img)//1024}KB  <- {video[:52]}")
    finally:
        c.close()

    out = pathlib.Path(args.out)
    if not out.is_absolute():
        out = ROOT / out
    out.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("-" * 68)
    print(f"完成 {done} 张，跳过 {skipped}，失败 {len(failed)}；清单已存到 {out}")
    for title, why in failed:
        print(f"  失败: {title[:30]} —— {why}")

    if args.apply:
        if not done:
            print("没有新封面，跳过写回 NAS")
            return
        # 以 NAS 上的最新清单为底，只把 cover 合并进去，避免覆盖家长在控制台的其它改动
        fresh, _ = read_config(cfg, argparse.Namespace(from_nas=True, config=""))
        mapping = {(it.get("file") or it.get("folder")): it.get("cover")
                   for it in collect_items(data) if it.get("cover")}
        n = 0
        for it in collect_items(fresh):
            ref = mapping.get(it.get("file") or it.get("folder"))
            if ref:
                it["cover"] = ref
                n += 1
        print(write_back(cfg, fresh))
        print(f"已合并 {n} 个 cover 字段；电视上按「菜单 → 重新读取清单」即可看到")
    else:
        print("未写回 NAS。确认无误后加 --apply，或在控制台导入这份 JSON。")


if __name__ == "__main__":
    main()
