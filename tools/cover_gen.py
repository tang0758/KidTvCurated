#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""服务端片单封面复用与截帧生成（供 console.py 调用）。

规则与 tools/make_covers.py / App Repo.coverCandidates() 保持完全一致：
1. 若 item.cover 已非空且目标文件存在，保留不覆盖；
2. 若 NAS 可写共享 curated/covers/<md5(norm(key))[:14]>.jpg 已存在，直接绑定复用（不调 ffmpeg）；
3. 若视频原目录已有同名图片或 folder/poster/cover.jpg，直接复用（不调 ffmpeg）；
4. 仅对真正缺少封面的视频，走本机 HTTP /stream/<share>/<path> + ffmpeg 截帧，拼成 10:14 竖版海报上传。
"""
from __future__ import annotations

import hashlib
import io
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.parse

VIDEO_EXT = {
    ".mp4", ".mkv", ".avi", ".ts", ".m2ts", ".mov",
    ".wmv", ".flv", ".webm", ".mpg", ".mpeg", ".m4v",
}


def norm_rel(path: str) -> str:
    """规范为无首尾斜杠的正斜杠相对路径，供 md5 命名与 SMB 一致。"""
    p = str(path or "").replace("\\", "/").strip("/")
    parts = [x for x in p.split("/") if x not in ("", ".", "..")]
    return "/".join(parts)


def natural_key(s: str):
    return [t.zfill(24) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", str(s))]


def cover_tag(key: str) -> str:
    return hashlib.md5(norm_rel(key).encode("utf-8")).hexdigest()[:14]


def _safe_seg(seg: str) -> str:
    cleaned = re.sub(r'[:*?"<>|]', "_", seg).strip(" .")
    return cleaned or "_"


def shadow_cover_rel(cover_dir: str, item: dict) -> str:
    """方案 A：按片源原目录层级与文件名镜像生成可读路径（不改视频源共享）。"""
    c_dir = norm_rel(cover_dir)
    if item.get("file"):
        parts = [p for p in norm_rel(item["file"]).split("/") if p]
        if not parts:
            return f"{c_dir}/{cover_tag('')}.jpg"
        last = parts[-1]
        stem = last.rsplit(".", 1)[0] if "." in last else last
        safe_parts = [_safe_seg(x) for x in parts[:-1]] + [_safe_seg(stem) + ".jpg"]
        return f"{c_dir}/" + "/".join(safe_parts)
    fld = norm_rel(item.get("folder") or "")
    safe_parts = [_safe_seg(x) for x in fld.split("/") if x]
    if not safe_parts:
        return f"{c_dir}/{cover_tag(fld)}.jpg"
    return f"{c_dir}/" + "/".join(safe_parts) + "/folder.jpg"


def legacy_cover_rel(cover_dir: str, key: str) -> str:
    return f"{norm_rel(cover_dir)}/{cover_tag(key)}.jpg"


def cover_dir_of(cfg: dict) -> str:
    pl = norm_rel(cfg.get("playlistPath") or "curated/tv.json")
    parent = pl.rsplit("/", 1)[0] if "/" in pl else ""
    return f"{parent}/covers" if parent else "covers"


def parse_cover_ref(ref: str, default_share: str) -> tuple[str, str]:
    s = str(ref or "").strip()
    idx = s.find(":")
    if idx > 1 and "/" not in s[:idx] and "\\" not in s[:idx]:
        return s[:idx], norm_rel(s[idx + 1:])
    return default_share, norm_rel(s)


def remote_exists(c, share: str, rel_path: str) -> bool:
    p = norm_rel(rel_path)
    if not share or not p:
        return False
    try:
        c.getAttributes(share, "/" + p)
        return True
    except Exception:
        return False


def ensure_dirs(c, share: str, folder: str) -> None:
    cur = ""
    for part in norm_rel(folder).split("/"):
        if not part:
            continue
        cur = f"{cur}/{part}" if cur else part
        try:
            c.createDirectory(share, "/" + cur)
        except Exception:
            pass


def list_dir(c, share: str, folder: str) -> list:
    try:
        p = "/" + norm_rel(folder) if norm_rel(folder) else "/"
        return [e for e in c.listPath(share, p) if e.filename not in (".", "..")]
    except Exception:
        return []


def first_video(c, share: str, folder: str, depth: int = 0) -> str | None:
    if depth > 3:
        return None
    entries = list_dir(c, share, folder)
    vids = sorted(
        (
            e.filename for e in entries
            if not e.isDirectory
            and pathlib.PurePosixPath(e.filename).suffix.lower() in VIDEO_EXT
            and not e.filename.startswith(".")
        ),
        key=natural_key,
    )
    base = norm_rel(folder)
    if vids:
        return f"{base}/{vids[0]}" if base else vids[0]
    for e in sorted((x for x in entries if x.isDirectory and not x.filename.startswith(".")),
                    key=lambda x: natural_key(x.filename)):
        sub = f"{base}/{e.filename}" if base else e.filename
        found = first_video(c, share, sub, depth + 1)
        if found:
            return found
    return None


def find_adjacent_cover(c, video_share: str, item: dict, default_video_share: str) -> str | None:
    """检查视频目录是否已有与 App coverCandidates 一致的现成封面图。"""
    if not video_share:
        return None
    cands: list[str] = []
    if item.get("file"):
        f = norm_rel(item["file"])
        stem = f.rsplit(".", 1)[0] if "." in f.rsplit("/", 1)[-1] else f
        parent = f.rsplit("/", 1)[0] if "/" in f else ""
        name_no_ext = stem.rsplit("/", 1)[-1]
        cands.extend([f"{stem}.jpg", f"{stem}.png"])
        if parent:
            cands.append(f"{parent}/{name_no_ext}-poster.jpg")
    elif item.get("folder"):
        fld = norm_rel(item["folder"])
        if fld:
            cands.extend([
                f"{fld}/folder.jpg",
                f"{fld}/poster.jpg",
                f"{fld}/cover.jpg",
                f"{fld}/封面.jpg",
            ])
    for rel_img in cands:
        if remote_exists(c, video_share, rel_img):
            if video_share == default_video_share:
                return rel_img
            return f"{video_share}:{rel_img}"
    return None


def _pydeps_dir() -> pathlib.Path:
    cfg_env = os.environ.get("KIDTV_CONFIG", "")
    if cfg_env:
        return pathlib.Path(cfg_env).resolve().parent / "pydeps"
    return pathlib.Path(__file__).resolve().parent / ".pydeps"


def find_ffmpeg(auto_install: bool = True) -> tuple[str, str]:
    """查找系统 ffmpeg 或 imageio-ffmpeg；必要时按需后台拉取到可写目录，绝不阻塞控制台启动。"""
    exe = shutil.which("ffmpeg")
    if exe:
        return exe, ""
    dep_dir = _pydeps_dir()
    if dep_dir.is_dir() and str(dep_dir) not in sys.path:
        sys.path.insert(0, str(dep_dir))
    for _attempt in range(2 if auto_install else 1):
        try:
            import imageio_ffmpeg
            cand = imageio_ffmpeg.get_ffmpeg_exe()
            if cand and pathlib.Path(cand).exists():
                return cand, ""
        except Exception:
            pass
        if not auto_install or _attempt > 0:
            break
        try:
            dep_dir.mkdir(parents=True, exist_ok=True)
            last_err = ""
            for extra in (["-i", "https://mirrors.aliyun.com/pypi/simple/"], []):
                cmd = [
                    sys.executable, "-m", "pip", "install", "--no-cache-dir",
                    "--target", str(dep_dir), *extra, "imageio-ffmpeg",
                ]
                r = subprocess.run(cmd, capture_output=True, text=True, timeout=120, encoding="utf-8", errors="replace")
                if r.returncode == 0:
                    if str(dep_dir) not in sys.path:
                        sys.path.insert(0, str(dep_dir))
                    break
                last_err = (r.stderr or r.stdout or "").strip()[-160:]
            else:
                return "", f"按需安装 imageio-ffmpeg 失败：{last_err or '网络或镜像不可用'}"
        except Exception as e:
            return "", f"按需安装 imageio-ffmpeg 异常：{e}"
    return "", "运行环境未安装 ffmpeg（且按需拉取 imageio-ffmpeg 失败），请检查容器网络或安装 ffmpeg"


def probe_duration_ffmpeg(ffmpeg_bin: str, input_url: str) -> float:
    """通过 ffmpeg -i 读取 stderr Duration，不依赖额外 ffprobe。"""
    try:
        r = subprocess.run(
            [ffmpeg_bin, "-hide_banner", "-i", input_url],
            capture_output=True, text=True, timeout=25, encoding="utf-8", errors="replace",
        )
        m = re.search(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", r.stderr or "")
        if m:
            return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3))
    except Exception:
        pass
    return 0.0


def grab_portrait_frame(
    ffmpeg_bin: str, input_url: str, out_path: pathlib.Path, at_sec: float, width: int = 480
) -> bool:
    h = round(width * 284 / 200 / 2) * 2
    flt = (
        f"[0:v]split=2[bg][fg];"
        f"[bg]scale={width}:{h}:force_original_aspect_ratio=increase,"
        f"crop={width}:{h},boxblur=18:2[bg2];"
        f"[fg]scale={width}:-2[fg2];"
        f"[bg2][fg2]overlay=(W-w)/2:(H-h)/2[v]"
    )
    cmd = [
        ffmpeg_bin, "-hide_banner", "-loglevel", "error", "-y",
        "-ss", f"{at_sec:.2f}", "-i", input_url, "-frames:v", "1",
        "-filter_complex", flt, "-map", "[v]", "-q:v", "4", str(out_path),
    ]
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=60)
        return r.returncode == 0 and out_path.exists() and out_path.stat().st_size > 3000
    except Exception:
        return False


def best_portrait_frame(
    ffmpeg_bin: str, input_url: str, tmpdir: pathlib.Path, width: int = 480, tries: int = 2
) -> bytes | None:
    dur = probe_duration_ffmpeg(ffmpeg_bin, input_url)
    points = [max(2.0, dur * f) for f in (0.2, 0.45, 0.7)][:tries] if dur > 8 else [5.0]
    best, best_size = None, 0
    for idx, t in enumerate(points):
        out = tmpdir / f"frame_{idx}.jpg"
        if grab_portrait_frame(ffmpeg_bin, input_url, out, t, width=width):
            data = out.read_bytes()
            if len(data) > best_size:
                best, best_size = data, len(data)
    return best


def stream_url(base_url: str, share: str, rel_path: str) -> str:
    encoded_path = "/".join(urllib.parse.quote(seg) for seg in norm_rel(rel_path).split("/"))
    return f"{base_url.rstrip('/')}/stream/{urllib.parse.quote(share)}/{encoded_path}"


def _find_reusable_cover(
    c, c_share: str, c_dir: str, v_share: str, default_v_share: str, item: dict, key: str
) -> tuple[str, str]:
    existing = str(item.get("cover") or "").strip()
    if existing:
        ex_share, ex_rel = parse_cover_ref(existing, default_v_share)
        if remote_exists(c, ex_share, ex_rel):
            return existing, "existing"

    shadow_rel = shadow_cover_rel(c_dir, item)
    if remote_exists(c, c_share, shadow_rel):
        return f"{c_share}:{shadow_rel}", "reused"

    legacy_rel = legacy_cover_rel(c_dir, key)
    if remote_exists(c, c_share, legacy_rel):
        return f"{c_share}:{legacy_rel}", "reused"

    adj = find_adjacent_cover(c, v_share, item, default_v_share)
    if adj:
        return adj, "adjacent"

    return "", "missing"


def resolve_existing_covers(
    c, cfg: dict, items: list, video_share: str = "", cover_share: str = ""
) -> list[dict]:
    """仅查已有封面（影子目录同名图、历史 MD5 图或原目录自带图），不执行 ffmpeg。"""
    v_share = video_share or cfg.get("share") or ""
    default_v_share = cfg.get("share") or v_share
    c_share = cover_share or cfg.get("playlistShare") or "data"
    c_dir = cover_dir_of(cfg)
    results = []
    for idx, it in enumerate(items):
        if not isinstance(it, dict) or not (it.get("file") or it.get("folder")):
            results.append({"index": idx, "key": "", "cover": (it.get("cover") if isinstance(it, dict) else "") or "", "status": "skipped"})
            continue
        key = norm_rel(it.get("file") or it.get("folder"))
        cover, status = _find_reusable_cover(c, c_share, c_dir, v_share, default_v_share, it, key)
        results.append({"index": idx, "key": key, "cover": cover, "status": status})
    return results


def generate_missing_covers(
    c,
    cfg: dict,
    items: list,
    stream_base_url: str,
    video_share: str = "",
    cover_share: str = "",
    force: bool = False,
    grabber=None,
) -> dict:
    """优先复用已有封面，仅对缺失项通过 /stream/ + ffmpeg 生成并上传至影子目录同名路径。"""
    v_share = video_share or cfg.get("share") or ""
    c_share = cover_share or cfg.get("playlistShare") or "data"
    c_dir = cover_dir_of(cfg)
    default_v_share = cfg.get("share") or v_share

    summary = {"total": len(items), "reused": 0, "adjacent": 0, "generated": 0, "skipped": 0, "failed": 0}
    results = []
    warnings = []
    ffmpeg_bin = ""
    checked_ffmpeg = False

    with tempfile.TemporaryDirectory(prefix="kidtv-web-covers-") as td:
        tmpdir = pathlib.Path(td)
        for idx, it in enumerate(items):
            if not isinstance(it, dict) or not (it.get("file") or it.get("folder")):
                summary["skipped"] += 1
                results.append({"index": idx, "key": "", "cover": (it.get("cover") if isinstance(it, dict) else "") or "", "status": "skipped"})
                continue

            key = norm_rel(it.get("file") or it.get("folder"))
            existing = str(it.get("cover") or "").strip()

            if not force:
                reused_cover, status = _find_reusable_cover(
                    c, c_share, c_dir, v_share, default_v_share, it, key
                )
                if status in ("existing", "reused"):
                    summary["reused"] += 1
                    results.append({"index": idx, "key": key, "cover": reused_cover, "status": "reused"})
                    continue
                if status == "adjacent":
                    summary["adjacent"] += 1
                    results.append({"index": idx, "key": key, "cover": reused_cover, "status": "adjacent"})
                    continue

            video_rel = key if it.get("file") else first_video(c, v_share, key)
            if not video_rel:
                summary["failed"] += 1
                results.append({"index": idx, "key": key, "cover": existing, "status": "failed", "reason": "目录里没找到视频"})
                continue

            if grabber is None and not checked_ffmpeg:
                checked_ffmpeg = True
                ffmpeg_bin, ff_err = find_ffmpeg(auto_install=True)
                if not ffmpeg_bin and ff_err:
                    warnings.append(ff_err)

            if grabber is None and not ffmpeg_bin:
                summary["failed"] += 1
                results.append({"index": idx, "key": key, "cover": existing, "status": "failed", "reason": "缺少 ffmpeg"})
                continue

            src_url = stream_url(stream_base_url, v_share, video_rel)
            if grabber is not None:
                img_bytes = grabber(src_url, it, video_rel)
            else:
                img_bytes = best_portrait_frame(ffmpeg_bin, src_url, tmpdir, width=480, tries=2)

            if not img_bytes:
                summary["failed"] += 1
                results.append({"index": idx, "key": key, "cover": existing, "status": "failed", "reason": "视频截帧失败"})
                continue

            remote = shadow_cover_rel(c_dir, it)
            ref = f"{c_share}:{remote}"
            parent_dir = remote.rsplit("/", 1)[0] if "/" in remote else c_dir
            ensure_dirs(c, c_share, parent_dir)
            c.storeFile(c_share, "/" + remote, io.BytesIO(img_bytes), timeout=40)
            summary["generated"] += 1
            results.append({"index": idx, "key": key, "cover": ref, "status": "generated", "bytes": len(img_bytes)})

    return {"ok": True, "summary": summary, "results": results, "warnings": warnings}
