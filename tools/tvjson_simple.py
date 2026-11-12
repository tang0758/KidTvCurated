#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tv.json 极简 Web 编辑器 —— 单文件、零前端构建、只依赖 pysmb。

用法：
    python tools/tvjson_simple.py                 # 只监听 127.0.0.1:8787，自动开浏览器
    python tools/tvjson_simple.py --port 9000
    python tools/tvjson_simple.py --lan          # 同一 WiFi 的手机也能开（自动加访问码）
    python tools/tvjson_simple.py --no-browser

页面直接读 NAS 上的 data/curated/tv.json：可视化改分区/条目、改 JSON 原文、
浏览 SMB 目录挑路径，保存时校验 -> 备份 -> 写回 -> 回读校验。
NAS 密码不回显：页面上是占位符，保存时由后端自动补回原值。
"""
from __future__ import annotations

import argparse
import datetime as dt
import html
import io
import json
import re
import secrets
import shutil
import subprocess
import sys
import threading
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

try:
    from smb.SMBConnection import SMBConnection
except ImportError:
    print("缺少 pysmb，请先执行：python -m pip install pysmb", file=sys.stderr)
    sys.exit(1)

ROOT = Path(__file__).resolve().parent.parent
CONFIG_FILE = ROOT / "nas.local.json"
BACKUP_DIR = ROOT / ".tmp" / "tvjson-backups"
URL_FILE = ROOT / ".tmp" / "tvjson-editor-url.txt"
MASK = "__KEEP_NAS_PASSWORD__"
REFRESH_ACTION = "com.family.curated.PLAYLIST_CHANGED"
REFRESH_PACKAGE = "com.family.curated"
VIDEO_EXT = (".mp4", ".mkv", ".avi", ".ts", ".m2ts", ".mov", ".wmv",
             ".flv", ".iso", ".m4v", ".rmvb", ".mp3", ".m4a")

# 与电视端 AppCatalog 保持一致的排除名单：桌面 / 设置 / 安装器 / 商店 / 全量资源播放器
APP_BLOCKED_EXACT = {
    "com.android.settings", "com.android.vending", "com.android.packageinstaller",
    "com.google.android.packageinstaller", "com.android.permissioncontroller",
    "com.jamdeo.tv.vod", "app.orchard.tv", "com.dangbeimarket", "com.tcl.browser",
    "org.courville.nova", "com.mygithub0.tvbox0.osdX", "com.mdxgh.tv",
    "com.family.curated",
}
APP_BLOCKED_WORDS = ("settings", "launcher", "packageinstaller", "permissioncontroller",
                     "systemui", "setupwizard", "inputmethod", "systeminfo", "catlog", "logcat")
APP_BLOCKED_LOWER = {x.lower() for x in APP_BLOCKED_EXACT}

# adb 拿不到 APK 里的应用名（label 是资源字符串），这里给出常见包的中文名方便辨认。
# 没列到的包仍显示包名；网页里 entries 的 label 可以自己改。
APP_LABELS = {
    "com.cibn.tv": "优酷 / CIBN 酷喵",
    "com.gitvdemo.video": "银河奇异果（爱奇艺 TV）",
    "com.baidu.netdisk.tv": "百度网盘 TV",
    "com.hpplay.happyplay.aw": "乐播投屏",
    "com.ugreen.tvnas": "绿联私有云",
    "com.ugreen.nasprotv": "绿联 NAS TV",
    "com.xiaodianshi.tv.yst": "小电视",
    "com.ju.miguvideoTV.cmcc": "咪咕视频 TV",
    "com.audiocn.karaoke.micmanager": "K歌 / 麦克风",
    "com.dangbeimarket": "当贝市场",
    "com.tcl.browser": "TCL 浏览器",
    "org.courville.nova": "Nova 播放器",
    "com.mygithub0.tvbox0.osdX": "TVBox（全量播放器）",
    "com.mdxgh.tv": "TVBox 类播放器",
    "app.orchard.tv": "桌面",
    "com.family.curated": "小剧场（本应用）",
}

BLANK_CFG = {
    "host": "", "port": 445, "user": "", "password": "",
    "share": "儿童资源", "playlistShare": "data",
    "playlistPath": "curated/tv.json",
}

STATE: dict = {"cfg": dict(BLANK_CFG), "token": "", "lan": False}
LOCK = threading.Lock()


# --------------------------------------------------------------------------- 配置

def load_cfg() -> dict:
    cfg = dict(BLANK_CFG)
    if CONFIG_FILE.exists():
        try:
            raw = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
            for k in cfg:
                if k in raw and raw[k] not in (None, ""):
                    cfg[k] = raw[k]
        except Exception as e:
            print(f"[warn] 读取 {CONFIG_FILE.name} 失败：{e}")
    for key in ("user", "password"):
        if str(cfg.get(key, "")).startswith("YOUR_"):
            cfg[key] = ""
    cfg["port"] = int(cfg.get("port") or 445)
    return cfg


def current_cfg() -> dict:
    with LOCK:
        return dict(STATE["cfg"])


def public_cfg(cfg: dict) -> dict:
    d = {k: cfg.get(k, "") for k in BLANK_CFG if k != "password"}
    d["hasPassword"] = bool(cfg.get("password"))
    return d


def persist_cfg(cfg: dict) -> None:
    merged = {}
    if CONFIG_FILE.exists():
        try:
            merged = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
        except Exception:
            merged = {}
    merged.update({k: cfg.get(k, "") for k in BLANK_CFG})
    CONFIG_FILE.write_text(
        json.dumps(merged, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


# --------------------------------------------------------------------------- SMB

class SmbError(Exception):
    pass


def norm(path: str) -> str:
    p = (path or "").replace("\\", "/").strip()
    parts = [x for x in p.split("/") if x not in ("", ".", "..")]
    return "/" + "/".join(parts)


def connect(cfg: dict) -> SMBConnection:
    if not cfg.get("host") or not cfg.get("user") or not cfg.get("password"):
        raise SmbError("还没填 NAS 地址 / 账号 / 密码，请到「连接设置」里填")
    c = SMBConnection(cfg["user"], cfg["password"], "tvjson-editor", "nas",
                      domain="", use_ntlm_v2=True, is_direct_tcp=True)
    try:
        ok = c.connect(cfg["host"], int(cfg.get("port") or 445), timeout=15)
    except Exception as e:
        raise SmbError(f"连不上 {cfg['host']}:{cfg.get('port') or 445} —— {e}")
    if not ok:
        raise SmbError("SMB 认证失败，检查账号密码 / NAS 的 SMB 服务")
    return c


def remote_path(cfg: dict) -> str:
    return norm(cfg.get("playlistPath") or "curated/tv.json")


def read_remote(cfg: dict) -> bytes:
    c = connect(cfg)
    try:
        buf = io.BytesIO()
        c.retrieveFile(cfg["playlistShare"], remote_path(cfg), buf, timeout=30)
        return buf.getvalue()
    except Exception as e:
        raise SmbError(f"读不到 {cfg['playlistShare']}:{remote_path(cfg)} —— {e}")
    finally:
        c.close()


def read_share_file(cfg: dict, share: str, path: str, limit: int = 8 * 1024 * 1024) -> bytes:
    """读任意共享里的一个文件（编辑器里给封面做缩略图预览用）。"""
    share = share or cfg.get("share") or "儿童资源"
    path = norm(path)
    c = connect(cfg)
    try:
        buf = io.BytesIO()
        c.retrieveFile(share, path, buf, timeout=25)
        data = buf.getvalue()
    except Exception as e:
        raise SmbError(f"读不到 {share}:{path} —— {e}")
    finally:
        c.close()
    if len(data) > limit:
        raise SmbError(f"{path} 太大了（{len(data) // 1024} KB），不像封面图")
    return data


def remote_mtime(cfg: dict):
    path = remote_path(cfg)
    parent = path.rsplit("/", 1)[0] or "/"
    name = path.rsplit("/", 1)[-1]
    c = connect(cfg)
    try:
        for f in c.listPath(cfg["playlistShare"], parent, timeout=20):
            if f.filename == name:
                return dt.datetime.fromtimestamp(f.last_write_time).strftime("%Y-%m-%d %H:%M:%S")
    except Exception:
        pass
    finally:
        c.close()
    return ""


def masked_text(raw: bytes) -> tuple[str, dict]:
    obj = json.loads(raw.decode("utf-8-sig"))
    if not isinstance(obj, dict):
        raise SmbError("tv.json 顶层不是对象")
    nas = obj.get("nas")
    if isinstance(nas, dict) and nas.get("password"):
        nas["password"] = MASK
    return json.dumps(obj, ensure_ascii=False, indent=2), obj


def autofix_playlist(cfg: dict, obj: dict, warnings: list[str]) -> None:
    """保存前顺手修两类常见手误，避免整张片单在电视上全黑。

    1. 路径多写了共享名或反斜杠：`儿童资源\\儿童视频\\x.mp4` → `儿童视频/x.mp4`。
       清单里的路径是相对「视频共享根目录」的，共享名由 nas.share 指定，重复写会找不到文件。
    2. 新加的条目没填封面：如果同目录有同名图片（或 cover.jpg），自动接上，
       省得电视端对每条都去试 5 个候选图片，把 SMB 连接挤占掉。

    只做「确定能找到」的修正，找不到就原样留着，不猜。
    """
    nas = obj.get("nas") if isinstance(obj.get("nas"), dict) else {}
    share = (nas.get("share") or cfg.get("share") or "").strip("/")
    if not share:
        return

    fixes: list[str] = []
    dir_cache: dict[str, list[str]] = {}
    conn = None
    try:
        conn = connect(cfg)
    except Exception:
        conn = None

    def ls(d: str) -> list[str]:
        key = d.strip("/")
        if key in dir_cache:
            return dir_cache[key]
        names: list[str] = []
        if conn is not None:
            try:
                names = [f.filename for f in conn.listPath(share, "/" + key)
                         if f.filename not in (".", "..")]
            except Exception:
                names = []
        dir_cache[key] = names
        return names

    try:
        for sec in obj.get("sections") or []:
            if not isinstance(sec, dict):
                continue
            for it in sec.get("items") or []:
                if not isinstance(it, dict):
                    continue
                for k in ("file", "folder", "cover"):
                    p = it.get(k)
                    if not isinstance(p, str) or not p.strip():
                        continue
                    q = p.replace("\\", "/").strip().lstrip("/")
                    if q.lower().startswith(share.lower() + "/"):
                        q = q[len(share) + 1:]
                        it[k] = q
                        fixes.append(f"去掉多余共享名：{p} → {q}")
                    elif q != p:
                        it[k] = q
                if it.get("cover"):
                    continue
                # 单集条目找同名图；整季条目找 folder.jpg / poster.jpg / cover.jpg / 封面.jpg
                f = it.get("file")
                fd = it.get("folder")
                if isinstance(f, str) and f:
                    d, _, name = f.rpartition("/")
                    base = name.rsplit(".", 1)[0]
                    cands = (base + ".jpg", base + ".jpeg", base + ".png",
                             "cover.jpg", "folder.jpg", "封面.jpg")
                elif isinstance(fd, str) and fd:
                    d = fd.replace("\\", "/").strip("/")
                    cands = ("folder.jpg", "poster.jpg", "cover.jpg", "封面.jpg")
                else:
                    continue
                lower = {n.lower(): n for n in ls(d)}
                for cand in cands:
                    real = lower.get(cand.lower())
                    if real:
                        it["cover"] = (d + "/" if d else "") + real
                        fixes.append(f"自动补封面：{it['cover']}")
                        break
    finally:
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass

    if fixes:
        warnings.append(f"已自动修正 {len(fixes)} 处路径/封面问题（保存后即生效）")
        warnings.extend("  · " + x for x in fixes[:10])
        if len(fixes) > 10:
            warnings.append(f"  · 其余 {len(fixes) - 10} 处省略")


def is_placeholder_password(v) -> bool:
    """判断 nas.password 是不是占位符（空 / 本编辑器掩码 / 手打的星号）。

    保存时遇到占位符一律"沿用原有密码"，绝不把占位符本身写进 NAS ——
    否则电视端会拿一串星号去连 SMB，整个片单全部打不开。
    """
    s = str(v or "").strip()
    if not s or s == MASK:
        return True
    if len(s) >= 3 and set(s) <= {"*", "•", "·"}:
        return True
    return "KEEP_NAS_PASSWORD" in s.upper()


def prepare_payload(cfg: dict, text: str) -> tuple[bytes, list[str]]:
    """校验前端文本、补回 NAS 密码，返回待写入字节和警告列表。"""
    try:
        obj = json.loads(text)
    except json.JSONDecodeError as e:
        raise SmbError(f"JSON 格式错误：第 {e.lineno} 行第 {e.colno} 列 —— {e.msg}")
    if not isinstance(obj, dict):
        raise SmbError("JSON 顶层必须是一个对象 {...}")
    warnings: list[str] = []
    nas = obj.get("nas")
    if isinstance(nas, dict):
        if is_placeholder_password(nas.get("password")):
            real = cfg.get("password") or ""
            if not real:
                try:
                    old = json.loads(read_remote(cfg).decode("utf-8-sig"))
                    real = (old.get("nas") or {}).get("password", "")
                    if is_placeholder_password(real):
                        real = ""
                except Exception:
                    pass
            if real:
                nas["password"] = real
            else:
                warnings.append("没拿到原 NAS 密码，nas.password 保持占位符，电视端可能连不上")
        if not nas.get("host") or not nas.get("share"):
            warnings.append("nas.host / nas.share 为空，电视端可能连不上")
    else:
        warnings.append("缺少 nas 段，电视端可能连不上")

    sections = obj.get("sections")
    if not isinstance(sections, list):
        raise SmbError("sections 必须是数组")
    for si, sec in enumerate(sections):
        if not isinstance(sec, dict):
            raise SmbError(f"sections[{si}] 不是对象")
        if not sec.get("key"):
            warnings.append(f"第 {si + 1} 个分区没有 key")
        items = sec.get("items", [])
        if not isinstance(items, list):
            if "items" in sec:
                raise SmbError(f"分区「{sec.get('title', si)}」的 items 必须是数组")
        else:
            for ii, it in enumerate(items):
                if not isinstance(it, dict) or not (it.get("file") or it.get("folder") or it.get("app")):
                    warnings.append(f"分区「{sec.get('title', si)}」第 {ii + 1} 条缺 file/folder/app")

    # 保存前自动纠正路径前缀 / 补封面（详见函数注释），电视端就不会因为一个前缀全黑
    autofix_playlist(cfg, obj, warnings)

    if "apps" in obj:
        apps = obj["apps"]
        if not isinstance(apps, dict):
            raise SmbError("apps 必须是对象")
        entries = apps.get("entries", [])
        if not isinstance(entries, list):
            raise SmbError("apps.entries 必须是数组")
        for ai, en in enumerate(entries):
            spec = en.get("package") or en.get("pkg") if isinstance(en, dict) else en
            if not isinstance(spec, str) or not spec.strip():
                warnings.append(f"apps.entries[{ai}] 缺有效包名，电视端会忽略")
    if "autoRefresh" in obj:
        ar = obj["autoRefresh"]
        if not isinstance(ar, dict):
            raise SmbError("autoRefresh 必须是对象")
        n = ar.get("intervalSeconds", 15)
        if isinstance(n, bool) or not isinstance(n, int) or not (5 <= n <= 3600):
            warnings.append("autoRefresh.intervalSeconds 应为 5~3600 的整数，电视端会自动夹紧")
    payload = (json.dumps(obj, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    return payload, warnings


def write_remote(cfg: dict, payload: bytes, backup: bool = True) -> dict:
    share = cfg["playlistShare"]
    path = remote_path(cfg)
    parent = path.rsplit("/", 1)[0] or "/"
    c = connect(cfg)
    result = {"path": f"{share}:{path}", "bytes": len(payload), "backups": []}
    try:
        try:
            c.createDirectory(share, parent)
        except Exception:
            pass
        if backup:
            try:
                old = io.BytesIO()
                c.retrieveFile(share, path, old, timeout=20)
                old_bytes = old.getvalue()
                ts = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
                BACKUP_DIR.mkdir(parents=True, exist_ok=True)
                local = BACKUP_DIR / f"tv.{ts}.json"
                local.write_bytes(old_bytes)
                result["backups"].append(str(local))
                c.storeFile(share, parent + "/tv.backup.json",
                            io.BytesIO(old_bytes), timeout=30)
                result["backups"].append(f"{share}:{parent}/tv.backup.json")
            except Exception as e:
                result["backups"].append(f"备份失败（已继续写入）：{e}")
        # 先写隐藏临时文件再改名覆盖：电视端每 15 秒会读一次 tv.json，
        # 直接覆盖会让它读到半截 JSON；Samba 在改名时也不用让读端等锁。
        tmp_path = parent + "/.tv.json.tmp"
        c.storeFile(share, tmp_path, io.BytesIO(payload), timeout=40)
        renamed = False
        for _ in range(2):
            try:
                c.rename(share, tmp_path, path)   # POSIX rename 会原子替换
                renamed = True
                break
            except Exception:
                # 目标已存在且共享不允许覆盖改名：先删再改（App 侧有缓存与超时兜底）
                try:
                    c.deleteFiles(share, path, timeout=20)
                except Exception:
                    pass
        if not renamed:
            result["backups"].append("改名失败，改为直接写入")
            c.storeFile(share, path, io.BytesIO(payload), timeout=40)
            try:
                c.deleteFiles(share, tmp_path, timeout=10)
            except Exception:
                pass
        back = io.BytesIO()
        c.retrieveFile(share, path, back, timeout=30)
        got = back.getvalue()
        if len(got) != len(payload):
            raise SmbError(f"回读校验不一致：写入 {len(payload)} 字节，读回 {len(got)} 字节")
        try:
            json.loads(got.decode("utf-8-sig"))
        except Exception as e:
            raise SmbError(f"回读内容不是合法 JSON：{e}")
        return result
    except SmbError:
        raise
    except Exception as e:
        raise SmbError(f"写入失败：{e}")
    finally:
        c.close()


# --------------------------------------------------------------------------- 远程控制卡（OPT-06）
# 与 console.py 里那份是同一套约定：control.json / control.ack.json 和 tv.json 同目录同共享。
# 简版编辑器只提供 API，页面在「JSON / 导入导出」里可以直接写 control.json 原文。

def control_paths(cfg: dict) -> tuple:
    p = remote_path(cfg)
    parent = p.rsplit("/", 1)[0] or "/"
    return (cfg.get("playlistShare") or "data",
            parent + "/control.json", parent + "/control.ack.json")


def read_control(cfg: dict) -> dict:
    share, cpath, apath = control_paths(cfg)
    out = {"ok": True, "path": f"{share}:{cpath}", "control": None, "ack": None}
    c = connect(cfg)
    try:
        for key, p in (("control", cpath), ("ack", apath)):
            try:
                buf = io.BytesIO()
                c.retrieveFile(share, p, buf, timeout=20)
                out[key] = json.loads(buf.getvalue().decode("utf-8-sig"))
            except Exception:
                pass   # 还没建 / 写坏了都当「没有」
    finally:
        c.close()
    return out


def write_control(cfg: dict, incoming: dict) -> dict:
    share, cpath, _ = control_paths(cfg)
    clean = {
        "lockNow": bool(incoming.get("lockNow")),
        "addMinutes": max(0, min(240, int(incoming.get("addMinutes") or 0))),
        "pauseNow": bool(incoming.get("pauseNow")),
        # 每次发送都换新值（带毫秒，1 秒内连点两条也不撞），电视端按它幂等去重
        "updatedAt": dt.datetime.now().astimezone().isoformat(timespec="milliseconds"),
    }
    payload = (json.dumps(clean, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    c = connect(cfg)
    try:
        parent = cpath.rsplit("/", 1)[0] or "/"
        try:
            c.createDirectory(share, parent)
        except Exception:
            pass
        tmp = parent + "/.control.json.tmp"
        c.storeFile(share, tmp, io.BytesIO(payload), timeout=30)
        try:
            c.rename(share, tmp, cpath)
        except Exception:
            try:
                c.deleteFiles(share, cpath, timeout=15)
            except Exception:
                pass
            try:
                c.rename(share, tmp, cpath)
            except Exception:
                c.storeFile(share, cpath, io.BytesIO(payload), timeout=30)
    except Exception as e:
        raise SmbError(f"写 control.json 失败：{e}")
    finally:
        c.close()
    return {"ok": True, "control": clean,
            "message": f"已写入 {share}:{cpath}，等待电视回执（最长 15 秒）"}


def browse_dir(cfg: dict, share: str, path: str) -> dict:
    share = share or cfg.get("share") or "儿童资源"
    path = norm(path)
    c = connect(cfg)
    try:
        items = []
        for f in c.listPath(share, path, timeout=25):
            if f.filename in (".", ".."):
                continue
            items.append({
                "name": f.filename,
                "dir": bool(f.isDirectory),
                "size": int(getattr(f, "file_size", 0) or getattr(f, "filesize", 0) or 0),
                "video": f.filename.lower().endswith(VIDEO_EXT),
            })
        items.sort(key=lambda x: (not x["dir"], x["name"]))
        return {"share": share, "path": path,
                "parent": path.rsplit("/", 1)[0] or "/", "items": items}
    except Exception as e:
        raise SmbError(f"浏览 {share}:{path} 失败 —— {e}")
    finally:
        c.close()


def check_paths(cfg: dict, text: str) -> dict:
    """检查所有 file/folder 路径在视频共享里是否真实存在。"""
    obj = json.loads(text)
    paths: list[str] = []
    for sec in obj.get("sections", []):
        for it in sec.get("items", []) or []:
            if not isinstance(it, dict):
                continue
            p = it.get("file") or it.get("folder")  # app 卡片不用检查 SMB 路径
            if p:
                paths.append(p)
    share = cfg.get("share") or "儿童资源"
    c = connect(cfg)
    missing = []
    prefixed = []          # 多写了共享名的路径：save 时会自动去掉
    try:
        for p in paths:
            s = p.replace("\\", "/").strip().lstrip("/")
            if share and s.lower().startswith(share.lower() + "/"):
                prefixed.append(p)
                continue
            try:
                c.getAttributes(share, norm(p))
            except Exception:
                missing.append(p)
    finally:
        c.close()
    return {"checked": len(paths), "missing": missing, "prefixed": prefixed}


# --------------------------------------------------------------------- adb 应用清单

def find_adb() -> str:
    p = shutil.which("adb")
    if p:
        return p
    for cand in (r"D:\pcsoft\adb\platform-tools\adb.exe",
                 r"C:\platform-tools\adb.exe",
                 r"C:\Users\Stanley\AppData\Local\Android\Sdk\platform-tools\adb.exe"):
        if Path(cand).exists():
            return cand
    return ""


def _app_blocked(pkg: str) -> bool:
    p = pkg.lower()
    return p in APP_BLOCKED_LOWER or any(w in p for w in APP_BLOCKED_WORDS)


def installed_apps() -> dict:
    """通过 adb 列出电视上可启动的第三方应用，供网页勾选成 apps.entries。"""
    adb = find_adb()
    if not adb:
        raise SmbError("本机没找到 adb；把 platform-tools 目录加进 PATH，或在电视上手动勾选")

    def run(args, timeout=25):
        try:
            return subprocess.run([adb] + args, capture_output=True, text=True,
                                  timeout=timeout, encoding="utf-8", errors="replace").stdout
        except subprocess.TimeoutExpired:
            raise SmbError("adb 命令超时，检查电视的网络 ADB 连接")
        except Exception as e:
            raise SmbError(f"adb 执行失败：{e}")

    devices = [ln.split("\t")[0].strip() for ln in run(["devices"]).splitlines()
               if "\tdevice" in ln and not ln.startswith("List")]
    if not devices:
        raise SmbError("adb 没有已连接的设备；先在电视上开启网络 ADB，再 adb connect <电视IP>:5555")
    dev = devices[0]

    third = {ln.split(":", 1)[1].strip() for ln in
             run(["-s", dev, "shell", "pm", "list", "packages", "-3"]).splitlines()
             if ln.startswith("package:")}

    def launcher_pkgs(category: str) -> dict:
        out = run(["-s", dev, "shell", "cmd", "package", "query-activities", "--brief",
                   "-a", "android.intent.action.MAIN", "-c", category])
        found = {}
        for ln in out.splitlines():
            ln = ln.strip()
            if "/" in ln and " " not in ln and not ln.startswith("priority"):
                pkg = ln.split("/", 1)[0].strip()
                found.setdefault(pkg, ln)
        return found

    launchers = launcher_pkgs("android.intent.category.LAUNCHER")
    leanbacks = launcher_pkgs("android.intent.category.LEANBACK_LAUNCHER")
    homes = set(launcher_pkgs("android.intent.category.HOME"))

    apps = []
    for pkg in sorted(third):
        if _app_blocked(pkg) or pkg in homes:
            continue
        std = launchers.get(pkg)
        lean = leanbacks.get(pkg)
        if std:
            # 标准桌面入口：电视端 getLaunchIntentForPackage(纯包名) 就能拉起
            activity, spec, via = std, pkg, "launcher"
        elif lean:
            # 只有 Android TV 的 LEANBACK 入口：必须写「包名/Activity」，
            # 否则电视端 getLaunchIntentForPackage 拿不到 intent
            activity, spec, via = lean, lean, "leanback"
        else:
            continue  # 后台服务 / 系统组件，桌面上没有图标
        apps.append({"package": pkg, "activity": activity, "spec": spec,
                     "via": via, "label": APP_LABELS.get(pkg, "")})

    blocked = sum(1 for p in third if _app_blocked(p) or p in homes)
    return {"device": dev, "thirdParty": len(third), "apps": apps,
            "blocked": blocked, "noEntry": len(third) - blocked - len(apps)}


def notify_tv() -> dict:
    """保存成功后给电视发一条显式广播，让它立刻重读 NAS 清单。

    只是「加速通道」：电视端自己有轮询兜底，所以这里任何失败都不阻断保存。
    """
    adb = find_adb()
    if not adb:
        return {"sent": False, "detail": "本机没找到 adb（电视会在轮询间隔内自己发现）"}
    try:
        r = subprocess.run([adb, "devices"], capture_output=True, text=True,
                           timeout=8, encoding="utf-8", errors="replace")
        devices = [ln.split("\t")[0].strip() for ln in (r.stdout or "").splitlines()
                   if "\tdevice" in ln and not ln.startswith("List")]
        if not devices:
            return {"sent": False, "detail": "adb 没连上电视（不影响：电视会自己轮询）"}
        parts, ok = [], False
        for dev in devices:
            try:
                b = subprocess.run(
                    [adb, "-s", dev, "shell", "am", "broadcast",
                     "-a", REFRESH_ACTION, "-p", REFRESH_PACKAGE],
                    capture_output=True, text=True, timeout=8,
                    encoding="utf-8", errors="replace")
                out = ((b.stdout or "") + (b.stderr or "")).strip()
                good = b.returncode == 0 and "Error" not in out
                ok = ok or good
                parts.append(f"{dev}: {'已发出通知' if good else out[:90]}")
            except Exception as e:
                parts.append(f"{dev}: {type(e).__name__}")
        return {"sent": ok, "detail": "；".join(parts)}
    except Exception as e:
        return {"sent": False, "detail": f"通知失败（{type(e).__name__}），电视会自己轮询"}


def tv_status(cfg: dict) -> dict:
    """只读自检：电视上次同步时间、缓存版本、轮询配置与本地应用勾选。"""
    adb = find_adb()
    if not adb:
        raise SmbError("本机没找到 adb")

    def run(args, timeout=25):
        try:
            r = subprocess.run([adb] + args, capture_output=True, text=True,
                               timeout=timeout, encoding="utf-8", errors="replace")
            return r.stdout, r.stderr
        except subprocess.TimeoutExpired:
            raise SmbError("adb 命令超时，检查电视的网络 ADB 连接")

    out, _ = run(["devices"])
    devices = [ln.split("\t")[0].strip() for ln in out.splitlines()
               if "\tdevice" in ln and not ln.startswith("List")]
    if not devices:
        raise SmbError("adb 没有已连接的设备；先 adb connect <电视IP>:5555")
    dev = devices[0]

    def sh(cmd):
        o, e = run(["-s", dev, "shell", cmd])
        return o, (o + e).lower()

    pref, pref_err = sh("run-as com.family.curated cat shared_prefs/curated_prefs.xml")
    extras, _ = sh("run-as com.family.curated cat shared_prefs/kid_extras.xml")
    if not pref or "permission denied" in pref_err or "not debuggable" in pref_err:
        raise SmbError("读不到电视本地配置：需要 adb 已授权，且电视装的是 debuggable 版本")

    last = ""
    m = re.search(r'<long name="last_sync" value="(\d+)"', pref)
    if m:
        try:
            last = dt.datetime.fromtimestamp(int(m.group(1)) / 1000).strftime("%Y-%m-%d %H:%M:%S")
        except Exception:
            pass

    cached_entries, cached_items = [], 0
    cached_text = ""
    m = re.search(r'<string name="playlist_cache">(.*?)</string>', pref, re.S)
    if m:
        cached_text = html.unescape(m.group(1))
        try:
            cached = json.loads(cached_text)
            cached_entries = (cached.get("apps") or {}).get("entries") or []
            cached_items = sum(len(s.get("items") or []) for s in (cached.get("sections") or [])
                               if isinstance(s, dict))
        except Exception:
            pass

    sm = re.search(r'<set name="appsSelected">(.*?)</set>', extras, re.S)
    selected = re.findall(r"<string>([^<]*)</string>", sm.group(1)) if sm else []

    def pref_bool(name, default=False):
        m = re.search(r'<boolean name="' + name + r'" value="(\w+)"', pref)
        return (m.group(1) == "true") if m else default

    def pref_long(name):
        m = re.search(r'<long name="' + name + r'" value="(\d+)"', pref)
        if not m:
            return ""
        try:
            return dt.datetime.fromtimestamp(int(m.group(1)) / 1000).strftime("%H:%M:%S")
        except Exception:
            return ""

    interval_ms = re.search(r'<long name="auto_refresh_interval_ms" value="(\d+)"', pref)
    seconds = int(interval_ms.group(1)) // 1000 if interval_ms else 15
    # 新版 App 首次启动会写这个标记；没有就说明电视还装着旧版，轮询不会跑
    new_version = re.search(r'<boolean name="auto_refresh_migrated"', pref) is not None

    nas_mtime = ""
    nas_text = ""
    try:
        nas_mtime = remote_mtime(cfg) or ""
        nas_text = read_remote(cfg).decode("utf-8-sig")
    except Exception:
        pass
    # 内容一致才算同步到位。只看时间戳会误报：编辑器重写同样内容时
    # NAS mtime 会更新，但电视缓存其实已经是这一版。
    cache_match = None
    if cached_text and nas_text:
        try:
            cache_match = json.loads(cached_text) == json.loads(nas_text)
        except Exception:
            cache_match = cached_text.strip() == nas_text.strip()
    stale = (cache_match is False) or (
        cache_match is None and bool(last and nas_mtime and last < nas_mtime)
    )
    return {
        "device": dev, "lastSync": last or "未知", "nasMtime": nas_mtime or "未知",
        "stale": stale, "cacheMatch": cache_match,
        "cachedEntries": cached_entries, "cachedItems": cached_items,
        "appsSelected": selected, "override": sm is not None,
        "newVersion": new_version,
        "autoRefresh": pref_bool("auto_refresh_enabled", True),
        "autoSeconds": seconds,
        "autoApplied": pref_long("auto_refresh_last_applied"),
    }


# --------------------------------------------------------------------------- HTTP

HTML = r'''<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>tv.json 编辑器</title>
<style>
:root{--bg:#f4f6fa;--card:#fff;--bd:#e2e7ef;--txt:#1f2937;--muted:#6b7280;--pri:#2563eb;--prid:#1d4ed8;--ok:#16a34a;--warn:#b45309;--err:#dc2626;--soft:#f8fafc}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--txt);font:14px/1.55 system-ui,-apple-system,"Segoe UI","Microsoft YaHei",sans-serif}
button,input,select,textarea{font:inherit;color:inherit}
button{cursor:pointer;border:1px solid var(--bd);background:#fff;border-radius:7px;padding:6px 12px;transition:.12s}
button:hover{border-color:#b9c4d4;background:#f7f9fc}
button.primary{background:var(--pri);border-color:var(--pri);color:#fff;font-weight:600}
button.primary:hover{background:var(--prid)}
button.mini{padding:2px 7px;font-size:12px;border-radius:5px}
button.danger:hover{border-color:var(--err);color:var(--err)}
header{position:sticky;top:0;z-index:20;background:#fff;border-bottom:1px solid var(--bd);padding:10px 18px;display:flex;align-items:center;gap:14px;flex-wrap:wrap}
.brand{display:flex;align-items:center;gap:9px;min-width:0}
.brand h1{font-size:16px;margin:0;white-space:nowrap}
.dot{width:9px;height:9px;border-radius:50%;background:#cbd5e1;flex:none}
.dot.on{background:var(--ok)}.dot.off{background:var(--err)}
.sub{color:var(--muted);font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:52vw}
.acts{margin-left:auto;display:flex;gap:7px;flex-wrap:wrap}
.dirty{color:var(--warn);font-size:12px;font-weight:600}
main{max-width:1200px;margin:0 auto;padding:16px 18px 60px}
.msg{margin:12px 18px 0;padding:9px 13px;border-radius:8px;font-size:13px;display:none;white-space:pre-wrap}
.msg.show{display:block}.msg.ok{background:#ecfdf5;color:#065f46;border:1px solid #a7f3d0}
.msg.err{background:#fef2f2;color:#991b1b;border:1px solid #fecaca}
.msg.info{background:#eff6ff;color:#1e40af;border:1px solid #bfdbfe}
.tabs{display:flex;gap:4px;border-bottom:1px solid var(--bd);margin-bottom:16px}
.tabs button{border:0;border-radius:0;background:none;padding:9px 16px;color:var(--muted);border-bottom:2px solid transparent}
.tabs button.on{color:var(--pri);border-bottom-color:var(--pri);font-weight:600}
section.pane{display:none}section.pane.on{display:block}
.card{background:var(--card);border:1px solid var(--bd);border-radius:10px;padding:14px;margin-bottom:14px;box-shadow:0 1px 2px rgba(16,24,40,.03)}
.card h2{font-size:14px;margin:0 0 12px;color:var(--txt)}
.card h2 small{font-weight:400;color:var(--muted);margin-left:8px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:10px}
label.f{display:flex;flex-direction:column;gap:4px;font-size:12px;color:var(--muted)}
label.f input,label.f select,label.f textarea{border:1px solid var(--bd);border-radius:6px;padding:6px 8px;background:#fff;width:100%}
label.f input:focus,label.f textarea:focus{outline:2px solid #bfdbfe;border-color:var(--pri)}
label.f.chk{flex-direction:row;align-items:center;gap:6px;color:var(--txt)}
label.f.chk input{width:auto}
.sec-hd{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:10px}
.sec-hd .idx{color:var(--muted);font-size:12px}
.sec-hd input{border:1px solid var(--bd);border-radius:6px;padding:6px 8px}
.sec-hd .k{width:120px}.sec-hd .t{width:200px;font-weight:600}
.spacer{flex:1}
.opts{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:8px;margin-bottom:12px;padding-bottom:12px;border-bottom:1px dashed var(--bd)}
.scroll{overflow-x:auto}
table{width:100%;border-collapse:collapse;font-size:13px;min-width:780px}
th,td{padding:5px 6px;border-bottom:1px solid #eef1f6;text-align:left;vertical-align:middle}
th{color:var(--muted);font-size:12px;font-weight:500;background:var(--soft)}
td input,td select{width:100%;border:1px solid var(--bd);border-radius:5px;padding:5px 7px;background:#fff}
td .num{color:var(--muted);width:26px;text-align:center}
td.path{min-width:340px}
td.path .pw{display:flex;gap:4px;align-items:center}
td.path .pw input{flex:1;min-width:0;width:auto}
td.path .pw button{flex:none;white-space:nowrap}
td.cover{min-width:260px}
td.cover .cw{display:flex;gap:5px;align-items:center}
td.cover .cw input{flex:1;min-width:0;width:auto}
td.cover .cw button{flex:none;white-space:nowrap}
td.cover img.th{width:34px;height:48px;flex:none;object-fit:cover;border-radius:4px;border:1px solid var(--bd);background:var(--soft)}
td.cover img.th.empty{opacity:.45}
.modal .list img.thumb{width:40px;height:56px;object-fit:cover;border-radius:4px;border:1px solid var(--bd);background:var(--soft);flex:none}
td.ops{white-space:nowrap;width:105px}
.add{margin-top:10px}
textarea.raw{width:100%;min-height:64vh;border:1px solid var(--bd);border-radius:8px;padding:12px;font:12.5px/1.6 ui-monospace,Consolas,"Courier New",monospace;resize:vertical;background:#fff}
.hint{color:var(--muted);font-size:12px;margin:0 0 10px}
.modal{position:fixed;inset:0;background:rgba(15,23,42,.38);display:flex;align-items:center;justify-content:center;z-index:50;padding:16px}
.modal.hidden{display:none}
.modal .box{background:#fff;border-radius:12px;width:min(760px,100%);max-height:84vh;display:flex;flex-direction:column;overflow:hidden}
.modal .top{padding:12px 16px;border-bottom:1px solid var(--bd);display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.modal .top b{font-size:14px}
.crumb{font-size:12px;color:var(--muted);word-break:break-all}
.modal .list{overflow:auto;padding:8px 16px;flex:1}
.modal .list div{padding:6px 8px;border-radius:6px;cursor:pointer;display:flex;gap:8px;align-items:center}
.modal .list div:hover{background:#eff6ff}
.modal .list .sz{margin-left:auto;color:var(--muted);font-size:12px}
.modal .foot{padding:10px 16px;border-top:1px solid var(--bd);display:flex;gap:8px;flex-wrap:wrap;align-items:center}
.bad{background:#fef2f2 !important;border-color:var(--err) !important}
.modal .list label.ap{display:flex;gap:9px;align-items:center;padding:7px 8px;border-radius:6px;cursor:pointer;font-size:13px}
.modal .list label.ap:hover{background:#eff6ff}
.modal .list label.ap input{width:auto;flex:none}
.modal .list label.ap code{color:var(--muted);font-size:11px;word-break:break-all}
@media(max-width:640px){.sub{max-width:80vw}.acts{width:100%}.tabs button{padding:9px 10px}}
</style>
</head>
<body>
<header>
  <div class="brand"><span class="dot" id="dot"></span><h1>tv.json 编辑器</h1><span class="sub" id="conn">正在读取 NAS…</span></div>
  <span class="dirty" id="dirty"></span>
  <div class="acts">
    <button id="btnReload">重新读取</button>
    <button id="btnCheck">仅校验</button>
    <button id="btnPaths">检查路径</button>
    <button id="btnTv">电视状态</button>
    <button id="btnDownload">下载</button>
    <button id="btnSave" class="primary">保存到 NAS</button>
  </div>
</header>
<div class="msg" id="msg"></div>
<main>
  <div class="tabs">
    <button data-tab="form" class="on">可视化编辑</button>
    <button data-tab="json">JSON 原文</button>
    <button data-tab="cfg">连接设置</button>
  </div>

  <section class="pane on" id="pane-form">
    <div class="card">
      <h2>全局设置 <small>保存时 nas 段的密码自动保留，页面不回显</small></h2>
      <div class="grid" id="global"></div>
    </div>
    <div class="card">
      <h2>自动刷新 <small>改完 JSON，电视自己更新，不用遥控器</small></h2>
      <p class="hint">电视前台时按间隔读一次 NAS 上的 tv.json，<b>只有内容真的变了才重绘</b>；本页保存成功后还会通过 adb 发一条即时通知，通常几秒内生效。
      不写这段也生效（默认开启 / 15 秒 / 清单优先），前提是电视装了带自动同步的新版 APK。</p>
      <div class="grid" id="autorefresh">
        <label class="f chk"><input type="checkbox" data-path="autoRefresh.enabled" data-bool="1" id="ar-enabled"> 开启自动同步</label>
        <label class="f">轮询间隔（秒，5~3600）<input type="number" min="5" max="3600" step="1" data-path="autoRefresh.intervalSeconds" id="ar-interval"></label>
        <label class="f chk"><input type="checkbox" data-path="autoRefresh.followAppList" data-bool="1" id="ar-follow"> 清单应用列表优先</label>
      </div>
      <p class="hint">「清单应用列表优先」＝在网页里改了 apps 段时，自动清掉电视上家长菜单的本地勾选，让这里的 entries 直接生效；只改片单条目时不动本地勾选。</p>
    </div>
    <div class="card">
      <h2>应用入口 <small>apps 段 · 主界面最后一行的应用图标</small></h2>
      <p class="hint">开 <b>enabled</b> 才会显示这一行；<b>entries</b> 是初始列表。电视装了带自动同步的新版后，
      在这里改 apps 段并保存，电视会自动清掉旧的本地勾选并生效，不用再按遥控器。label 留空则用系统应用名。</p>
      <div class="grid" id="apps-global"></div>
      <div class="scroll" style="margin-top:12px"><table style="min-width:640px">
        <thead><tr><th>#</th><th>包名 / 入口（package）</th><th>显示名 label（选填）</th><th>操作</th></tr></thead>
        <tbody id="apps-rows"></tbody></table></div>
      <div style="margin-top:10px;display:flex;gap:8px;flex-wrap:wrap">
        <button data-act="app-add">＋ 手动添加</button>
        <button data-act="app-scan" class="primary">从电视读取并勾选</button>
        <span class="hint" id="apps-tip"></span>
      </div>
    </div>
    <div id="sections"></div>
    <button class="add" data-act="sec-add">＋ 新增分区</button>
    <div class="card" style="margin-top:16px">
      <h2>功能开关 <small>features 段 · 保存到 NAS 后 15 秒生效</small></h2>
      <p class="hint">出问题把对应开关改回 false 保存即可回滚，不用重装 APK。缺字段时电视用默认值。</p>
      <div class="grid">
        <label class="f chk"><input type="checkbox" data-path="features.kioskPullback" data-bool="1" id="ft-pullback"> 后台拉回</label>
        <label class="f chk"><input type="checkbox" data-path="features.extWatchMs" data-bool="1" id="ft-ext"> 外部应用计时</label>
        <label class="f chk"><input type="checkbox" data-path="features.remainingNotice" data-bool="1" id="ft-notice"> 剩余时间预告</label>
        <label class="f chk"><input type="checkbox" data-path="features.remoteControl" data-bool="1" id="ft-remote"> 远程控制</label>
        <label class="f chk"><input type="checkbox" data-path="features.autoNext" data-bool="1" id="ft-autonext"> 自动连播下一集</label>
      </div>
    </div>
    <div class="card" style="margin-top:16px">
      <h2>远程控制 <small>control.json · 最长 15 秒在电视上生效</small></h2>
      <p class="hint">先勾上「远程控制」并<b>保存 tv.json</b>，电视才会每 15 秒读同目录的 control.json。
      指令与片单是两份文件，改指令碰不坏片单；电视执行后写 control.ack.json 回执。</p>
      <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin:10px 0">
        <button data-act="ctl" data-cmd="lock">立即锁屏</button>
        <button data-act="ctl" data-cmd="add">按左边分钟数加时</button>
        <input type="number" id="ctl-min" min="1" max="240" value="15" style="width:100px">
        <button data-act="ctl" data-cmd="pause">暂停播放</button>
        <button data-act="ctl" data-cmd="unlock">解除锁定</button>
        <button data-act="ctl-refresh">刷新回执</button>
      </div>
      <p class="hint" id="ctl-ack">还没有读取回执</p>
      <p class="hint">「立即锁屏」会一直锁到家长解除（最长 8 小时）；点「解除锁定」、把 lockNow 改回 false，或在电视上临时放行都能解除。</p>
    </div>
    <div class="card" style="margin-top:16px">
      <h2>高级字段 <small>kiosk / 说明等，按 JSON 对象编辑</small></h2>
      <p class="hint">这里没列出的顶层字段都放在下面。改完点「应用到编辑器」；留空表示清空这些字段。</p>
      <textarea class="raw" id="extra" style="min-height:150px"></textarea>
      <div style="margin-top:8px"><button data-act="extra-apply">应用到编辑器</button></div>
    </div>
  </section>

  <section class="pane" id="pane-json">
    <p class="hint">直接改原文。切回「可视化编辑」时会先校验 JSON；这里的 nas.password 是占位符，保存时后端自动补回真实密码。</p>
    <textarea class="raw" id="raw" spellcheck="false"></textarea>
    <div style="margin-top:10px;display:flex;gap:8px;flex-wrap:wrap">
      <button data-act="fmt">格式化</button>
      <button data-act="apply-raw">应用到编辑器</button>
      <button data-act="copy-raw">复制</button>
    </div>
  </section>

  <section class="pane" id="pane-cfg">
    <div class="card">
      <h2>NAS 连接</h2>
      <p class="hint">默认读项目根目录的 nas.local.json。密码留空表示沿用已保存的密码。这里填的是「电脑连 NAS」的凭据，不会写进 tv.json 的 nas 段。</p>
      <div class="grid">
        <label class="f">地址<input id="c-host"></label>
        <label class="f">端口<input id="c-port" type="number"></label>
        <label class="f">账号<input id="c-user"></label>
        <label class="f">密码<input id="c-password" type="password" placeholder="留空 = 不修改"></label>
        <label class="f">视频共享<input id="c-share"></label>
        <label class="f">清单共享<input id="c-plshare"></label>
        <label class="f">清单路径<input id="c-plpath"></label>
      </div>
      <div style="margin-top:12px;display:flex;gap:8px;flex-wrap:wrap;align-items:center">
        <button class="primary" id="btnCfg">应用并读取</button>
        <button id="btnPersist">记住到 nas.local.json</button>
        <label class="f chk"><input type="checkbox" id="c-persist"> 应用时同时写入本地配置</label>
      </div>
    </div>
    <div class="card"><h2>说明</h2>
      <p class="hint">保存流程：JSON 校验 → 旧文件备份到本机 .tmp/tvjson-backups/ 和 NAS 同目录的 tv.backup.json → 写回 → 回读校验字节数和 JSON。<br>
      默认只监听 127.0.0.1，仅本机可访问；手机访问用 --lan 启动（会带访问码）。</p>
    </div>
  </section>
</main>

<div class="modal hidden" id="picker"><div class="box">
  <div class="top"><b>从 NAS 选路径</b><span class="crumb" id="pk-path"></span><span class="spacer"></span>
    <select id="pk-share"></select><button class="mini" data-act="pk-up">↑ 上级</button><button class="mini" data-act="pk-close">关闭</button></div>
  <div class="list" id="pk-list"></div>
  <div class="foot"><span class="hint" id="pk-target"></span><span class="spacer"></span>
    <button class="primary" data-act="pk-folder">把当前目录设为 folder</button></div>
</div></div>

<div class="modal hidden" id="appPicker"><div class="box">
  <div class="top"><b>电视上已安装的应用</b><span class="crumb" id="ap-device"></span><span class="spacer"></span>
    <button class="mini" data-act="ap-all">全选</button><button class="mini" data-act="ap-none">清空</button>
    <button class="mini" data-act="ap-close">关闭</button></div>
  <div class="hint" id="ap-note" style="padding:6px 12px">只列出电视桌面能打开的应用</div>
  <div class="list" id="ap-list"></div>
  <div class="foot"><span class="hint" id="ap-tip"></span><span class="spacer"></span>
    <button class="primary" data-act="ap-apply">加入选中应用</button></div>
</div></div>

<script>
"use strict";
const TOKEN = new URLSearchParams(location.search).get("k") || "";
const KNOWN = ["nas","playlistShare","passRatio","pin","sections","schedule","apps","autoRefresh","features"];
// OPT-14：分区图标只用内置名字，电视端映射到矢量资源
const ICON_OPTS = [["none","不显示图标"],["star","星星"],["play","播放"],["book","书本"],["heart","爱心"],["game","游戏"],["moon","月亮"]];
let obj = {sections:[]}, cfg = {}, activeTab = "form", dirty = false;
let pk = {share:"", path:"/", target:null, items:[]};
let ap = {mode:"apps", target:null, apps:[], checked:new Set()};

const $ = s => document.querySelector(s);
const esc = s => String(s == null ? "" : s).replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[c]));
function msg(text, kind){ const m=$("#msg"); m.className="msg show "+(kind||"info"); m.textContent=text; }
function setDirty(v){ dirty=v; $("#dirty").textContent=v?"● 有未保存修改":""; }
function api(path, opts){ return fetch(path+(path.includes("?")?"&":"?")+"k="+encodeURIComponent(TOKEN), Object.assign({headers:{"X-Token":TOKEN,"Content-Type":"application/json"}},opts)).then(async r=>{ const d=await r.json().catch(()=>({ok:false,error:"HTTP "+r.status})); if(!r.ok||d.ok===false) throw new Error(d.error||("HTTP "+r.status)); return d; }); }
function getPath(o,p){ let cur=o; for(const k of p.split(".")){ if(cur==null) return undefined; cur=cur[k]; } return cur; }
function setPath(o,p,v){ const a=p.split("."); let cur=o; for(let i=0;i<a.length-1;i++){ if(cur[a[i]]==null) cur[a[i]] = /^\d+$/.test(a[i+1])?[]:{}; cur=cur[a[i]]; } cur[a[a.length-1]]=v; }
function delPath(o,p){ const a=p.split("."); let cur=o; for(let i=0;i<a.length-1;i++){ if(cur==null) return; cur=cur[a[i]]; } if(cur&&typeof cur==="object") delete cur[a[a.length-1]]; }
function fmt(v){ return JSON.stringify(v, null, 2); }
function markDirty(){ setDirty(true); }

/* ---------- 视图切换 ---------- */
function switchTab(tab){
  if(tab===activeTab) return;
  if(activeTab==="json"){
    try{ obj=JSON.parse($("#raw").value); }catch(e){ msg("JSON 原文有错，修好再切走："+e.message,"err"); return; }
    renderForm();
  }
  if(tab==="json") $("#raw").value = fmt(obj);
  activeTab=tab;
  document.querySelectorAll(".tabs button").forEach(b=>b.classList.toggle("on", b.dataset.tab===tab));
  document.querySelectorAll("section.pane").forEach(p=>p.classList.toggle("on", p.id==="pane-"+tab));
  if(tab==="cfg") fillCfg();
}
document.querySelectorAll(".tabs button").forEach(b=>b.addEventListener("click",()=>switchTab(b.dataset.tab)));

/* ---------- 表单渲染 ---------- */
function f(label, path, opts){
  opts=opts||{};
  const v=getPath(obj,path);
  if(opts.type==="bool") return '<label class="f chk"><input type="checkbox" data-path="'+path+'" data-bool="1" '+(v?"checked":"")+'>'+esc(label)+'</label>';
  if(opts.json) return '<label class="f">'+esc(label)+'<input data-path="'+path+'" data-json="1" value="'+esc(v===undefined?"":JSON.stringify(v))+'"></label>';
  if(opts.type==="select"){
    const cur=String(v===undefined||v===null?"":v);
    return '<label class="f">'+esc(label)+'<select data-path="'+path+'">'+
      (opts.options||[]).map(o=>'<option value="'+esc(o[0])+'"'+(cur===o[0]?" selected":"")+'>'+esc(o[1])+'</option>').join("")+
      '</select></label>';
  }
  const t=opts.type==="number"?"number":"text";
  return '<label class="f">'+esc(label)+'<input type="'+t+'" data-path="'+path+'" value="'+esc(v===undefined?"":v)+'"></label>';
}
function renderGlobal(){
  $("#global").innerHTML =
    f("家长 PIN","pin") + f("看完比例 passRatio","passRatio",{type:"number"}) +
    f("清单共享 playlistShare","playlistShare") +
    f("时段开关 schedule.enabled","schedule.enabled",{type:"bool"}) +
    f("工作日时段 weekday（JSON 数组）","schedule.weekday",{json:true}) +
    f("周末时段 weekend（JSON 数组）","schedule.weekend",{json:true}) +
    f("每日时长 dailyLimitMinutes","schedule.dailyLimitMinutes",{type:"number"}) +
    f("做完任务奖励 rewardMinutes","schedule.rewardMinutes",{type:"number"}) +
    f("锁屏提示 lockMessage","schedule.lockMessage") +
    f("时段 PIN schedule.pin","schedule.pin");
}
function renderSections(){
  const secs=Array.isArray(obj.sections)?obj.sections:[];
  $("#sections").innerHTML = secs.map((s,si)=>{
    const items=Array.isArray(s.items)?s.items:[];
    const rows=items.map((it,ii)=>{
      const kind=it.file!==undefined?"file":(it.folder!==undefined?"folder":(it.app!==undefined?"app":"folder"));
      const p="sections."+si+".items."+ii;
      const val=it[kind]||"";
      return '<tr>'+
        '<td class="num">'+(ii+1)+'</td>'+
        '<td><input data-path="'+p+'.title" value="'+esc(it.title||"")+'" placeholder="标题"></td>'+
        '<td><select data-kind="1" data-s="'+si+'" data-i="'+ii+'"><option value="file" '+(kind==="file"?"selected":"")+'>单集 file</option><option value="folder" '+(kind==="folder"?"selected":"")+'>整季 folder</option><option value="app" '+(kind==="app"?"selected":"")+'>应用 app</option></select></td>'+
        '<td class="path"><div class="pw"><input data-path="'+p+'.'+kind+'" value="'+esc(val)+'" placeholder="'+(kind==="app"?"应用包名 或 包名/Activity":"相对视频共享根目录的路径")+'"><button class="mini" data-act="item-pick" data-s="'+si+'" data-i="'+ii+'">'+(kind==="app"?"选应用":"浏览")+'</button></div></td>'+
        '<td><input data-path="'+p+'.subtitle" value="'+esc(it.subtitle||"")+'" placeholder="副标题"></td>'+
        '<td class="cover"><div class="cw">'+
          '<img class="th" data-thumb="'+si+','+ii+'" alt="" title="封面预览">'+
          '<input data-path="'+p+'.cover" value="'+esc(it.cover||"")+'" placeholder="留空=自动找同名图">'+
          '<button class="mini" data-act="cover-pick" data-s="'+si+'" data-i="'+ii+'">选图</button>'+
        '</div></td>'+
        '<td class="ops"><button class="mini" data-act="item-up" data-s="'+si+'" data-i="'+ii+'">↑</button> '+
          '<button class="mini" data-act="item-down" data-s="'+si+'" data-i="'+ii+'">↓</button> '+
          '<button class="mini danger" data-act="item-del" data-s="'+si+'" data-i="'+ii+'">×</button></td>'+
      '</tr>';
    }).join("");
    return '<div class="card"><div class="sec-hd">'+
      '<span class="idx">分区 '+(si+1)+'</span>'+
      '<input class="k" data-path="sections.'+si+'.key" value="'+esc(s.key||"")+'" placeholder="key">'+
      '<input class="t" data-path="sections.'+si+'.title" value="'+esc(s.title||"")+'" placeholder="标题">'+
      '<span class="spacer"></span>'+
      '<button class="mini" data-act="sec-up" data-s="'+si+'">↑</button> '+
      '<button class="mini" data-act="sec-down" data-s="'+si+'">↓</button> '+
      '<button class="mini danger" data-act="sec-del" data-s="'+si+'">删除分区</button></div>'+
      '<div class="opts">'+
        f("每日必看 gate","sections."+si+".gate",{type:"bool"})+
        f("解锁依赖 requires","sections."+si+".requires")+
        f("列数 columns","sections."+si+".columns",{type:"number"})+
        f("分区图标 icon","sections."+si+".icon",{type:"select",options:ICON_OPTS})+
        f("说明 _说明","sections."+si+"._说明")+
        f("自动轮换 auto（JSON）","sections."+si+".auto",{json:true})+
      '</div>'+
      '<div class="scroll"><table><thead><tr><th>#</th><th>标题</th><th>类型</th><th>路径</th><th>副标题</th><th>封面</th><th>操作</th></tr></thead><tbody>'+
      (rows||'<tr><td colspan="7" class="hint">还没有条目</td></tr>')+'</tbody></table></div>'+
      '<button class="mini add" data-act="item-add" data-s="'+si+'">＋ 添加条目</button></div>';
  }).join("");
  syncThumbs();
}
function entrySpec(e){ return (e&&typeof e==="object")?(e.package||e.pkg||""):String(e==null?"":e); }
function appsCfg(create){
  if(!obj.apps || typeof obj.apps!=="object" || Array.isArray(obj.apps)){
    if(!create) return {enabled:false,title:"应用",showAfterDaily:true,maxMinutes:30,entries:[]};
    obj.apps={enabled:false,title:"应用",showAfterDaily:true,maxMinutes:30,entries:[]};
  }
  const a=obj.apps;
  if(create){
    if(a.enabled===undefined) a.enabled=false;
    if(a.title===undefined) a.title="应用";
    if(a.showAfterDaily===undefined) a.showAfterDaily=true;
    if(a.maxMinutes===undefined) a.maxMinutes=30;
    if(!Array.isArray(a.entries)) a.entries=[];
  }
  return a;
}
function renderApps(){
  const a=appsCfg(false), es=Array.isArray(a.entries)?a.entries:[];
  $("#apps-global").innerHTML =
    '<label class="f chk"><input type="checkbox" data-path="apps.enabled" data-bool="1" '+(a.enabled?"checked":"")+'>显示应用行 enabled</label>'+
    '<label class="f chk"><input type="checkbox" data-path="apps.showAfterDaily" data-bool="1" '+(a.showAfterDaily?"checked":"")+'>看完必看才解锁 showAfterDaily</label>'+
    '<label class="f">这一行标题 title<input data-path="apps.title" value="'+esc(a.title==null?"应用":a.title)+'"></label>'+
    '<label class="f">单次放行分钟 maxMinutes<input type="number" data-path="apps.maxMinutes" value="'+esc(a.maxMinutes==null?30:a.maxMinutes)+'"></label>';
  $("#apps-rows").innerHTML = es.length?es.map((e,i)=>
    '<tr><td class="num">'+(i+1)+'</td>'+
    '<td><input data-app="spec" data-i="'+i+'" value="'+esc(entrySpec(e))+'" placeholder="com.example.kidsgame"></td>'+
    '<td><input data-app="label" data-i="'+i+'" value="'+esc((e&&typeof e==="object")?(e.label||""):"")+'" placeholder="留空用系统应用名"></td>'+
    '<td class="ops"><button class="mini" data-act="app-up" data-i="'+i+'">↑</button> '+
      '<button class="mini" data-act="app-down" data-i="'+i+'">↓</button> '+
      '<button class="mini danger" data-act="app-del" data-i="'+i+'">×</button></td></tr>').join("")
    :'<tr><td colspan="4" class="hint">还没有应用入口，点「从电视读取并勾选」</td></tr>';
  $("#apps-tip").textContent=es.length+" 个入口 · "+(a.enabled?"已启用":"未启用");
}
function renderExtra(){
  const x={}; Object.keys(obj).forEach(k=>{ if(!KNOWN.includes(k)) x[k]=obj[k]; });
  $("#extra").value = Object.keys(x).length?fmt(x):"{}";
}
function renderAutoRefresh(){
  const a=(obj.autoRefresh&&typeof obj.autoRefresh==="object")?obj.autoRefresh:{};
  $("#ar-enabled").checked = a.enabled!==false;
  $("#ar-interval").value = (typeof a.intervalSeconds==="number")?a.intervalSeconds:15;
  $("#ar-follow").checked = a.followAppList!==false;
}
function renderFeatures(){
  const f=(obj.features&&typeof obj.features==="object")?obj.features:{};
  $("#ft-pullback").checked = f.kioskPullback!==false;
  $("#ft-ext").checked = f.extWatchMs!==false;
  $("#ft-notice").checked = f.remainingNotice!==false;
  $("#ft-remote").checked = f.remoteControl===true;
  $("#ft-autonext").checked = f.autoNext===true;
}
function renderForm(){ renderGlobal(); renderAutoRefresh(); renderFeatures(); renderApps(); renderSections(); renderExtra(); }

/* ---------- 远程控制卡 ---------- */
async function loadCtl(){
  try{
    const d=await api("/api/control");
    $("#ctl-ack").textContent = d.ack
      ? "已生效 · "+(d.ack.appliedAt||"")+" · "+((d.ack.actions||[]).join("、"))+" · "+(d.ack.message||"")
      : "电视还没回执（可能刚发送，或 features.remoteControl 没打开并保存）";
  }catch(e){ $("#ctl-ack").textContent="读取失败："+e.message; }
}
async function sendCtl(cmd){
  const n=Math.max(1,Math.min(240,Number($("#ctl-min").value)||15));
  const body={lockNow:false,addMinutes:0,pauseNow:false};
  if(cmd==="lock") body.lockNow=true;
  else if(cmd==="add") body.addMinutes=n;
  else if(cmd==="pause") body.pauseNow=true;
  // unlock：显式 lockNow=false + 新 updatedAt → 电视端解除远程锁定
  try{
    await api("/api/control",{method:"POST",body:JSON.stringify(body)});
    msg("指令已写入，等待电视回执…","ok");
    setTimeout(loadCtl,3000);
  }catch(e){ msg(e.message,"err"); }
}

/* ---------- 封面缩略图预览 ---------- */
const IMG_RE=/\.(jpe?g|png|webp|bmp|gif)$/i;
// cover 字段两种写法都要认：
//   "儿童视频/x/abc.jpg"        → 相对视频共享
//   "data:curated/covers/x.jpg" → 共享名:路径
function coverSrc(cov){
  cov=(cov||"").trim();
  if(!cov) return "";
  let share=(cfg&&cfg.share)||"儿童资源", path=cov;
  const i=cov.indexOf(":");
  if(i>1){
    const head=cov.slice(0,i);
    if(head.indexOf("/")<0 && head.indexOf("\\")<0){ share=head; path=cov.slice(i+1); }
  }
  path=path.replace(/\\/g,"/").replace(/^\/+/,"");
  if(!path) return "";
  return "/api/img?share="+encodeURIComponent(share)+"&path="+encodeURIComponent(path);
}
function syncThumbs(){
  document.querySelectorAll("#sections img[data-thumb]").forEach(img=>{
    const p=img.dataset.thumb.split(","), si=+p[0], ii=+p[1];
    const it=((obj.sections[si]||{}).items||[])[ii]||{};
    const src=coverSrc(it.cover);
    if(src){ if(img.getAttribute("src")!==src) img.src=src; img.classList.remove("empty"); img.title=it.cover; }
    else { img.removeAttribute("src"); img.classList.add("empty"); img.title="还没有封面"; }
  });
}

/* ---------- 表单事件（委托） ---------- */
document.addEventListener("input", e=>{
  const t=e.target;
  if(!t.dataset) return;
  if(t.dataset.app){
    const ac=appsCfg(true), i=+t.dataset.i, old=ac.entries[i];
    const isObj=old&&typeof old==="object"&&!Array.isArray(old);
    const spec=t.dataset.app==="spec"?t.value:(isObj?(old.package||old.pkg||""):String(old==null?"":old));
    const label=t.dataset.app==="label"?t.value:(isObj?(old.label||""):"");
    const en=Object.assign({},isObj?old:{},{package:spec}); delete en.pkg;
    if(label.trim()) en.label=label; else delete en.label;
    ac.entries[i]=en; markDirty(); return;
  }
  if(t.dataset.path && !t.dataset.bool && !t.dataset.json){
    let v=t.value;
    if(t.type==="number") v = v===""?null:Number(v);
    setPath(obj,t.dataset.path,v); markDirty();
    // 手改封面路径时预览图跟着变，省得保存后才发现写错
    if(/\.cover$/.test(t.dataset.path)) syncThumbs();
  }
});
document.addEventListener("change", e=>{
  const t=e.target;
  if(!t.dataset) return;
  if(t.dataset.pkg!==undefined){
    if(t.checked){
      if(ap.mode==="item"){
        ap.checked.clear();
        document.querySelectorAll("#ap-list input[data-pkg]").forEach(c=>{ if(c!==t) c.checked=false; });
      }
      ap.checked.add(t.dataset.pkg);
    } else ap.checked.delete(t.dataset.pkg);
    $("#ap-tip").textContent=ap.mode==="item"?"分区条目只能选 1 个应用":"已选 "+ap.checked.size+" 个";
    return;
  }
  // 普通下拉（分区图标）：input 事件已经写过一次，change 再兜一次，老浏览器也能生效
  if(t.tagName==="SELECT" && t.dataset.path){ setPath(obj,t.dataset.path,t.value); markDirty(); return; }
  if(t.dataset.bool){ setPath(obj,t.dataset.path,t.checked); markDirty(); return; }
  if(t.dataset.json){
    if(!t.value.trim()){ delPath(obj,t.dataset.path); t.classList.remove("bad"); markDirty(); return; }
    try{ setPath(obj,t.dataset.path,JSON.parse(t.value)); t.classList.remove("bad"); markDirty(); }
    catch(err){ t.classList.add("bad"); msg("这个字段不是合法 JSON："+err.message+"（已忽略本次修改）","err"); }
    return;
  }
  if(t.dataset.kind){
    const s=+t.dataset.s,i=+t.dataset.i,it=obj.sections[s].items[i];
    const val=it.file||it.folder||it.app||"";
    delete it.file; delete it.folder; delete it.app; it[t.value]=val;
    renderSections(); markDirty(); return;
  }
  if(t.id==="pk-share"){ pk.share=t.value; pk.path="/"; loadDir(); }
});
document.addEventListener("click", e=>{
  const b=e.target.closest("[data-act]"); if(!b) return;
  const a=b.dataset.act, s=+(b.dataset.s||0), i=+(b.dataset.i||0);
  const secs=obj.sections;
  if(a==="sec-add"){ secs.push({key:"new"+(secs.length+1),title:"新分区",items:[]}); renderSections(); markDirty(); }
  else if(a==="sec-del"){ if(confirm("删除分区「"+(secs[s].title||s)+"」？")){ secs.splice(s,1); renderSections(); markDirty(); } }
  else if(a==="sec-up"&&s>0){ [secs[s-1],secs[s]]=[secs[s],secs[s-1]]; renderSections(); markDirty(); }
  else if(a==="sec-down"&&s<secs.length-1){ [secs[s+1],secs[s]]=[secs[s],secs[s+1]]; renderSections(); markDirty(); }
  else if(a==="item-add"){ (secs[s].items=secs[s].items||[]).push({title:"",folder:""}); renderSections(); markDirty(); }
  else if(a==="item-del"){ secs[s].items.splice(i,1); renderSections(); markDirty(); }
  else if(a==="item-up"&&i>0){ const ar=secs[s].items; [ar[i-1],ar[i]]=[ar[i],ar[i-1]]; renderSections(); markDirty(); }
  else if(a==="item-down"&&i<secs[s].items.length-1){ const ar=secs[s].items; [ar[i+1],ar[i]]=[ar[i],ar[i+1]]; renderSections(); markDirty(); }
  else if(a==="item-pick"){
    const it=secs[s].items[i];
    if(it.app!==undefined && it.file===undefined && it.folder===undefined) openAppPicker("item",{s,i});
    else openPicker(s,i,"path");
  }
  else if(a==="cover-pick"){ openPicker(s,i,"cover"); }
  else if(a==="app-add"){ appsCfg(true).entries.push({package:""}); renderApps(); markDirty(); }
  else if(a==="app-del"){ appsCfg(true).entries.splice(i,1); renderApps(); markDirty(); }
  else if(a==="app-up"&&i>0){ const es=appsCfg(true).entries; [es[i-1],es[i]]=[es[i],es[i-1]]; renderApps(); markDirty(); }
  else if(a==="app-down"&&i<appsCfg(true).entries.length-1){ const es=appsCfg(true).entries; [es[i+1],es[i]]=[es[i],es[i+1]]; renderApps(); markDirty(); }
  else if(a==="app-scan"){ openAppPicker("apps",null); }
  else if(a==="ap-close"){ $("#appPicker").classList.add("hidden"); }
  else if(a==="ap-all"){ ap.apps.forEach(x=>ap.checked.add(x.package)); renderAppList(); }
  else if(a==="ap-none"){ ap.checked.clear(); renderAppList(); }
  else if(a==="ap-apply"){
    const picked=[...ap.checked];
    if(!picked.length){ msg("还没勾选任何应用","err"); return; }
    if(ap.mode==="apps"){
      const ac=appsCfg(true); ac.enabled=true;
      let added=0;
      picked.forEach(p=>{
        const x=ap.apps.find(a=>a.package===p||a.spec===p);
        const spec=x?x.spec:p;
        const found=ac.entries.find(e=>entrySpec(e)===spec);
        if(!found){
          const en={package:spec}; if(x&&x.label) en.label=x.label;
          ac.entries.push(en); added++;
        }else if(x&&x.label && (typeof found!=="object" || !found.label)){
          // 之前手填的包名没有显示名：顺手补上中文名
          ac.entries[ac.entries.indexOf(found)]={package:spec,label:x.label}; added++;
        }
      });
      msg(added? "已加入 "+added+" 个应用入口（已打开 apps.enabled），记得点「保存到 NAS」"
                : "选中的应用都已在清单里（显示名已同步），记得点「保存到 NAS」","ok");
    }else{
      const x=ap.apps.find(a=>a.package===picked[0]||a.spec===picked[0]);
      const spec=x?x.spec:picked[0];
      const it=secs[ap.target.s].items[ap.target.i];
      delete it.file; delete it.folder; it.app=spec;
      if(!it.title) it.title=(x&&x.label)||spec;
      msg("已把 "+spec+" 填进分区条目","ok");
    }
    $("#appPicker").classList.add("hidden"); renderForm(); markDirty();
  }
  else if(a==="extra-apply"){ applyExtra(); }
  else if(a==="fmt"||a==="apply-raw"){
    try{ obj=JSON.parse($("#raw").value); $("#raw").value=fmt(obj); renderForm(); markDirty(); msg("JSON 合法，已应用到编辑器","ok"); }
    catch(err){ msg("JSON 有错："+err.message,"err"); }
  }
  else if(a==="copy-raw"){ navigator.clipboard.writeText($("#raw").value).then(()=>msg("已复制","ok"),()=>msg("复制失败","err")); }
  else if(a==="ctl"){ sendCtl(b.dataset.cmd); }
  else if(a==="ctl-refresh"){ loadCtl(); }
  else if(a==="pk-close"){ $("#picker").classList.add("hidden"); }
  else if(a==="pk-up"){ pk.path=pk.path==="/"?"/":(pk.path.split("/").slice(0,-1).join("/")||"/"); loadDir(); }
  else if(a==="pk-folder"){ pick(pk.path.replace(/^\//,""),"folder"); }
});
function applyExtra(){
  try{
    const x=JSON.parse($("#extra").value||"{}");
    if(typeof x!=="object"||x===null||Array.isArray(x)) throw new Error("顶层必须是对象");
    Object.keys(obj).forEach(k=>{ if(!KNOWN.includes(k)) delete obj[k]; });
    Object.assign(obj,x); markDirty(); msg("高级字段已应用","ok");
  }catch(err){ msg("高级字段 JSON 有错："+err.message,"err"); }
}

/* ---------- NAS 路径选择器 ---------- */
// mode="path" 选视频/目录；mode="cover" 只为封面挑一张图片
function openPicker(s,i,mode){
  pk.target={s,i,mode:mode||"path"};
  const it=(obj.sections[s].items||[])[i]||{};
  pk.share=cfg.share||"儿童资源"; pk.path="/";
  if(pk.target.mode==="cover"){
    const cov=(it.cover||"").trim();
    const ci=cov.indexOf(":");
    let p=cov;
    if(ci>1 && cov.slice(0,ci).indexOf("/")<0){ pk.share=cov.slice(0,ci); p=cov.slice(ci+1); }
    p=p.replace(/\\/g,"/").replace(/^\/+/,"");
    const dir=p?p.split("/").slice(0,-1).join("/"):"";
    pk.path=dir?"/"+dir:"/";
  }
  const shares=[cfg.share||"儿童资源",cfg.playlistShare||"data"];
  if(pk.share && shares.indexOf(pk.share)<0) shares.push(pk.share);
  const sel=$("#pk-share");
  sel.innerHTML=shares.filter((v,k,a)=>a.indexOf(v)===k).map(v=>'<option>'+esc(v)+'</option>').join("");
  sel.value=pk.share;
  $("#pk-target").textContent = pk.target.mode==="cover"
    ? "给分区 "+s+" 第 "+(i+1)+" 条选封面：点图片即选中（成片效果是竖版 200×284）"
    : "目标：分区 "+s+" 第 "+(i+1)+" 条";
  $("#picker").classList.remove("hidden"); loadDir();
}
function loadDir(){
  $("#pk-path").textContent=pk.share+":"+pk.path;
  $("#pk-list").innerHTML='<div class="hint">读取中…</div>';
  const coverMode = pk.target && pk.target.mode==="cover";
    api("/api/browse?share="+encodeURIComponent(pk.share)+"&path="+encodeURIComponent(pk.path))
    .then(d=>{
      pk.items=d.items||[];
      const items = coverMode ? d.items.filter(x=>x.dir||IMG_RE.test(x.name)) : d.items;
      $("#pk-list").innerHTML=items.length?items.map(x=>{
        const base=(pk.path==="/"?"":pk.path);
        const rel=base+"/"+x.name;
        const isImg=!x.dir&&IMG_RE.test(x.name);
        const lead=isImg
          ? '<img class="thumb" loading="lazy" src="'+coverSrc(pk.share+":"+rel.replace(/^\//,""))+'" alt="">'
          : (x.dir?"📁":"🎬");
        return '<div data-name="'+esc(x.name)+'" data-dir="'+(x.dir?1:0)+'">'+lead+
        '<span>'+esc(x.name)+'</span>'+(x.dir?'':'<span class="sz">'+(x.size/1048576).toFixed(1)+' MB</span>')+'</div>';
      }).join(""):'<div class="hint">'+(coverMode?"这个目录里没有图片":"空目录")+'</div>';
    }).catch(err=>msg("浏览失败："+err.message,"err"));
}
$("#pk-list").addEventListener("click",e=>{
  const d=e.target.closest("[data-name]"); if(!d) return;
  const name=d.dataset.name, base=pk.path==="/"?"":pk.path;
  if(d.dataset.dir==="1"){ pk.path=base+"/"+name; loadDir(); }
  else pick((base+"/"+name).replace(/^\//,""),"file");
});
function pick(rel,kind){
  if(!pk.target) return;
  const it=obj.sections[pk.target.s].items[pk.target.i];
  if(pk.target.mode==="cover"){
    // 封面在别的共享里（比如只读视频共享 + 可写 data 共享）时，写成「共享名:路径」
    it.cover = (pk.share===cfg.share) ? rel : (pk.share+":"+rel);
    $("#picker").classList.add("hidden"); renderSections(); markDirty();
    msg("封面已设为 "+it.cover+"，记得点「保存到 NAS」","ok");
    return;
  }
  delete it.file; delete it.folder; delete it.app; it[kind]=rel;
  if(!it.title) it.title=rel.split("/").pop().replace(/\.[^.]+$/,"");
  // 同目录有同名图片 / cover.jpg 就顺手接上封面：没有封面的条目会让电视
  // 对每条都去试 5 个候选图，几百次 SMB 请求会把播放挤卡。
  if(kind==="file" && !it.cover){
    const parts=rel.split("/"), dir=parts.slice(0,-1).join("/");
    const base=parts[parts.length-1].replace(/\.[^.]+$/,"");
    const cands=[base+".jpg",base+".jpeg",base+".png","cover.jpg","folder.jpg","封面.jpg"].map(s=>s.toLowerCase());
    const hit=(pk.items||[]).find(x=>!x.dir && cands.indexOf(x.name.toLowerCase())>=0);
    if(hit) it.cover=(dir?dir+"/":"")+hit.name;
  }
  $("#picker").classList.add("hidden"); renderSections(); markDirty();
  msg("已填入 "+(kind==="file"?"单集":"整季")+"路径："+rel,"ok");
}

/* ---------- 电视应用选择器（adb） ---------- */
function openAppPicker(mode,target){
  ap.mode=mode; ap.target=target; ap.apps=[]; ap.checked=new Set();
  $("#appPicker").classList.remove("hidden");
  $("#ap-device").textContent="读取中…";
  $("#ap-note").textContent="正在通过 adb 读取电视上已安装的应用…";
  $("#ap-list").innerHTML='<div class="hint">正在通过 adb 读取电视上已安装的应用…</div>';
  $("#ap-tip").textContent=mode==="item"?"分区条目只能选 1 个应用":"";
  api("/api/installed-apps").then(d=>{
    ap.apps=d.apps;
    $("#ap-device").textContent="设备 "+d.device+" · 共装 "+d.thirdParty+" 个，可启动 "+d.apps.length+" 个";
    $("#ap-note").textContent="筛选："+(d.blocked||0)+" 个桌面/商店/全量播放器被排除，"
      +(d.noEntry||0)+" 个是后台服务或系统组件（桌面上没有图标）；"
      +"加粗的是应用名，后面是包名。找不到想要的？关掉后用「＋ 手动添加」填包名。";
    if(mode==="apps") appsCfg(false).entries.forEach(e=>{ const s=entrySpec(e); if(s) ap.checked.add(s); });
    else { const cur=target?obj.sections[target.s].items[target.i].app:""; if(cur) ap.checked.add(cur); }
    renderAppList();
  }).catch(err=>{
    $("#ap-note").textContent="读取失败；也可以关掉弹层，用「＋ 手动添加」自己填包名。";
    $("#ap-list").innerHTML='<div class="hint">读取失败：'+esc(err.message)+'</div>';
    msg("读取电视应用失败："+err.message+"（也可以在下方手动填包名）","err");
  });
}
function renderAppList(){
  $("#ap-tip").textContent=ap.mode==="item"?"分区条目只能选 1 个应用":"已选 "+ap.checked.size+" 个";
  $("#ap-list").innerHTML=ap.apps.length?ap.apps.map(x=>
    '<label class="ap"><input type="checkbox" data-pkg="'+esc(x.package)+'" '
      +((ap.checked.has(x.package)||ap.checked.has(x.spec))?"checked":"")+'>'+
    '<span>'+(x.label?'<b>'+esc(x.label)+'</b> · ':'')+esc(x.package)+'</span>'+
    (x.via==="leanback"?'<em style="font-style:normal;font-size:11px;color:#888;white-space:nowrap">TV入口</em>':'')+
    '<code>'+esc(x.activity)+'</code></label>').join("")
    :'<div class="hint">没找到可启动的第三方应用</div>';
}

/* ---------- 读写 ---------- */
function currentText(){ return activeTab==="json"?$("#raw").value:fmt(obj); }
function loadRemote(){
  msg("正在读取 NAS 上的 tv.json …","info");
  return api("/api/load").then(d=>{
    cfg=d.cfg; obj=JSON.parse(d.text);
    $("#conn").textContent=d.cfg.host+" · "+d.cfg.playlistShare+":"+d.path+" · "+d.bytes+" 字节"+(d.mtime?" · "+d.mtime:"")+" · "+d.sectionCount+" 分区 / "+d.itemCount+" 条目";
    $("#dot").className="dot on";
    renderForm(); $("#raw").value=d.text; setDirty(false); fillCfg(); loadCtl();
    msg("已读取 NAS 上的 tv.json（密码已隐藏，保存时自动保留）","ok");
  }).catch(err=>{ $("#dot").className="dot off"; $("#conn").textContent="连接失败"; msg("读取失败："+err.message,"err"); });
}
function fillCfg(){
  $("#c-host").value=cfg.host||""; $("#c-port").value=cfg.port||445;
  $("#c-user").value=cfg.user||""; $("#c-password").value="";
  $("#c-password").placeholder=cfg.hasPassword?"已保存，留空 = 不修改":"需要填写密码";
  $("#c-share").value=cfg.share||""; $("#c-plshare").value=cfg.playlistShare||"";
  $("#c-plpath").value=cfg.playlistPath||"";
}
function cfgFromForm(){
  return {host:$("#c-host").value.trim(),port:Number($("#c-port").value||445),user:$("#c-user").value.trim(),
    password:$("#c-password").value,share:$("#c-share").value.trim(),
    playlistShare:$("#c-plshare").value.trim(),playlistPath:$("#c-plpath").value.trim()};
}
function save(dryRun){
  const btn=$("#btnSave"); btn.disabled=true; btn.textContent="处理中…";
  msg(dryRun?"正在校验…":"正在校验并写入 NAS…","info");
  api("/api/save",{method:"POST",body:JSON.stringify({text:currentText(),dryRun:!!dryRun})})
    .then(d=>{
      if(d.text){ obj=JSON.parse(d.text); renderForm(); $("#raw").value=d.text; }
      let s=(dryRun?"校验通过，未写入。":"已保存到 NAS。")+" 共 "+d.bytes+" 字节";
      if(d.backups&&d.backups.length) s+="\n备份："+d.backups.join(" | ");
      if(d.warnings&&d.warnings.length) s+="\n提醒："+d.warnings.join("；");
      if(!dryRun){
        const sec=(obj.autoRefresh&&obj.autoRefresh.intervalSeconds)||15;
        s+=d.tvNotify&&d.tvNotify.sent
          ? "\n已通知电视立即刷新；即使通知没送达，电视也会每 "+sec+" 秒自动检查一次，不用按遥控器"
          : "\n电视会每 "+sec+" 秒自动检查一次（adb 即时通知："+(d.tvNotify?d.tvNotify.detail:"未发出")+"）";
      }
      msg(s,d.warnings&&d.warnings.length?"info":"ok");
      if(!dryRun){ setDirty(false); $("#dot").className="dot on"; }
    })
    .catch(err=>msg((dryRun?"校验":"保存")+"失败："+err.message,"err"))
    .finally(()=>{ btn.disabled=false; btn.textContent="保存到 NAS"; });
}
$("#btnReload").onclick=()=>{ if(dirty&&!confirm("有未保存的修改，确定丢弃并重新读取？")) return; loadRemote(); };
$("#btnSave").onclick=()=>save(false);
$("#btnCheck").onclick=()=>save(true);
$("#btnTv").onclick=()=>{
  msg("正在读取电视上的同步状态…","info");
  api("/api/tv-status").then(d=>{
    const lines=[
      "电视 "+d.device,
      "电视上次读取清单："+d.lastSync+"    |    NAS 文件修改："+d.nasMtime,
      d.cacheMatch===false ? "⚠ 电视还在用旧清单，等下一轮自动轮询即可，不用操作遥控器"
        : d.cacheMatch===true ? "✓ 电视画面与 NAS 清单完全一致"
        : (d.stale?"⚠ 电视可能还在用旧清单":"✓ 电视读到的清单不早于 NAS 文件"),
      "电视缓存：apps.entries "+d.cachedEntries.length+" 个 · 分区条目 "+d.cachedItems+" 条",
      d.newVersion
        ? "自动刷新："+(d.autoRefresh?"已开启，每 "+d.autoSeconds+" 秒检查一次":"已在 tv.json 里关闭")
          +(d.autoApplied?" · 最近一次自动应用 "+d.autoApplied:" · 本次运行还没检测到变化")
        : "⚠ 电视仍是旧版 APK，没有自动刷新能力，需要安装新版"
    ];
    if(d.override) lines.push("电视本地勾选："+(d.appsSelected.join("、")||"（空）")
      +" —— 在网页里改 apps 段并保存后会自动清掉");
    if(!d.newVersion) lines.push("旧版临时做法：菜单 → PIN →「重新读取清单」"
      +(d.override?"；再选一次「恢复清单里的应用列表」":""));
    msg(lines.join("\n"),(!d.newVersion||d.stale)?"info":"ok");
  }).catch(err=>msg("读取电视状态失败："+err.message,"err"));
};
$("#btnPaths").onclick=()=>{
  msg("正在检查所有 file/folder 路径…","info");
  api("/api/check",{method:"POST",body:JSON.stringify({text:currentText()})})
    .then(d=>{
      const lines=[];
      if(d.prefixed && d.prefixed.length) lines.push("发现 "+d.prefixed.length+" 条路径多写了共享名（如「儿童资源/儿童视频/…」）：\n"
        +d.prefixed.slice(0,5).join("\n")+(d.prefixed.length>5?"\n…":"")
        +"\n清单里的路径是相对视频共享根目录的，保存时会自动去掉这个前缀。");
      if(d.missing.length) lines.push("共检查 "+d.checked+" 个路径，找不到 "+d.missing.length+" 个：\n"+d.missing.join("\n"));
      else lines.push("共检查 "+d.checked+" 个路径，全部存在 ✓");
      msg(lines.join("\n\n"), d.missing.length?"err":(d.prefixed&&d.prefixed.length?"info":"ok"));
    })
    .catch(err=>msg("检查失败："+err.message,"err"));
};
$("#btnDownload").onclick=()=>{
  const blob=new Blob([currentText()],{type:"application/json;charset=utf-8"});
  const a=document.createElement("a"); a.href=URL.createObjectURL(blob);
  a.download="tv.json"; a.click(); URL.revokeObjectURL(a.href);
};
$("#btnCfg").onclick=()=>applyCfg($("#c-persist").checked);
$("#btnPersist").onclick=()=>applyCfg(true);
function applyCfg(persist){
  api("/api/cfg",{method:"POST",body:JSON.stringify({cfg:cfgFromForm(),persist:!!persist})})
    .then(()=>{ msg(persist?"连接设置已应用并写入 nas.local.json":"连接设置已应用（仅本次运行有效）","ok"); return loadRemote(); })
    .catch(err=>msg("设置失败："+err.message,"err"));
}
window.addEventListener("beforeunload",e=>{ if(dirty){ e.preventDefault(); e.returnValue=""; } });
loadRemote();
</script>
</body>
</html>
'''


class Handler(BaseHTTPRequestHandler):
    server_version = "tvjson-simple/1.0"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code: int, body: bytes, ctype: str):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _json(self, obj, code: int = 200):
        self._send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"),
                   "application/json; charset=utf-8")

    def _authorized(self, query: dict) -> bool:
        token = STATE["token"]
        if not token:
            return True
        got = self.headers.get("X-Token") or (query.get("k") or [""])[0]
        return got == token

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        try:
            return json.loads(self.rfile.read(n).decode("utf-8")) if n else {}
        except Exception as e:
            raise SmbError(f"请求体不是合法 JSON：{e}")

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        if u.path == "/":
            body = HTML.encode("utf-8")
            return self._send(200, body, "text/html; charset=utf-8")
        if not u.path.startswith("/api/"):
            return self._send(404, b"not found", "text/plain; charset=utf-8")
        if not self._authorized(q):
            return self._json({"ok": False, "error": "访问码不对，请用启动时打印的完整链接打开"}, 401)
        cfg = current_cfg()
        try:
            if u.path == "/api/load":
                raw = read_remote(cfg)
                text, o = masked_text(raw)
                items = sum(len(s.get("items") or []) for s in o.get("sections", [])
                            if isinstance(s, dict))
                return self._json({
                    "ok": True, "cfg": public_cfg(cfg), "path": remote_path(cfg),
                    "text": text, "bytes": len(raw), "mtime": remote_mtime(cfg),
                    "sectionCount": len(o.get("sections", [])), "itemCount": items,
                })
            if u.path == "/api/browse":
                return self._json({"ok": True, **browse_dir(
                    cfg, (q.get("share") or [""])[0], (q.get("path") or ["/"])[0])})
            if u.path == "/api/img":
                # 封面缩略图预览：share 可省略（默认视频共享），path 是共享内相对路径
                share = (q.get("share") or [""])[0] or (cfg.get("share") or "")
                raw_path = (q.get("path") or [""])[0]
                data = read_share_file(cfg, share, raw_path)
                ext = raw_path.rsplit(".", 1)[-1].lower() if "." in raw_path else ""
                ctype = {"png": "image/png", "webp": "image/webp", "gif": "image/gif",
                         "bmp": "image/bmp"}.get(ext, "image/jpeg")
                return self._send(200, data, ctype)
            if u.path == "/api/installed-apps":
                return self._json({"ok": True, **installed_apps()})
            if u.path == "/api/tv-status":
                return self._json({"ok": True, **tv_status(cfg)})
            if u.path == "/api/control":
                return self._json(read_control(cfg))
            return self._json({"ok": False, "error": "未知接口 " + u.path}, 404)
        except Exception as e:
            return self._json({"ok": False, "error": str(e)}, 400)

    def do_POST(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        if not self._authorized(q):
            return self._json({"ok": False, "error": "访问码不对，请用启动时打印的完整链接打开"}, 401)
        cfg = current_cfg()
        try:
            body = self._body()
            if u.path == "/api/cfg":
                incoming = body.get("cfg") or {}
                with LOCK:
                    new = dict(STATE["cfg"])
                    for k in BLANK_CFG:
                        if k in incoming:
                            v = incoming[k]
                            if k == "password" and not str(v or "").strip():
                                continue  # 留空 = 沿用
                            new[k] = v
                    new["port"] = int(new.get("port") or 445)
                    new["playlistShare"] = new.get("playlistShare") or "data"
                    new["playlistPath"] = new.get("playlistPath") or "curated/tv.json"
                    STATE["cfg"] = new
                if body.get("persist"):
                    persist_cfg(new)
                return self._json({"ok": True, "cfg": public_cfg(new)})

            if u.path == "/api/control":
                return self._json(write_control(cfg, body))

            text = body.get("text")
            if not isinstance(text, str):
                raise SmbError("缺少 text")
            if u.path == "/api/check":
                return self._json({"ok": True, **check_paths(cfg, text)})
            if u.path == "/api/save":
                payload, warnings = prepare_payload(cfg, text)
                dry = bool(body.get("dryRun"))
                result = {"ok": True, "dryRun": dry, "bytes": len(payload),
                          "warnings": warnings, "backups": []}
                if not dry:
                    result.update(write_remote(cfg, payload))
                    # 回给前端的仍是脱敏文本，避免密码出现在页面
                    result["text"] = masked_text(payload)[0]
                    result["message"] = f"已写入 {result['path']}"
                    result["tvNotify"] = notify_tv()
                return self._json(result)
            return self._json({"ok": False, "error": "未知接口 " + u.path}, 404)
        except Exception as e:
            return self._json({"ok": False, "error": str(e)}, 400)


def editor_running(port: int) -> bool:
    """端口上是不是已经在跑本编辑器（避免重复双击快捷方式时报端口占用）。"""
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/", timeout=2) as r:
            return "tv.json 编辑器" in r.read(4096).decode("utf-8", "replace")
    except Exception:
        return False


def main():
    ap = argparse.ArgumentParser(description="tv.json 极简 Web 编辑器")
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--lan", action="store_true", help="监听局域网（自动启用访问码）")
    ap.add_argument("--no-browser", action="store_true")
    ap.add_argument("--config", help="指定 nas.local.json 路径")
    args = ap.parse_args()

    global CONFIG_FILE
    if args.config:
        CONFIG_FILE = Path(args.config).resolve()
    STATE["cfg"] = load_cfg()
    STATE["lan"] = args.lan
    if args.lan:
        STATE["token"] = secrets.token_hex(3)

    host = "0.0.0.0" if args.lan else "127.0.0.1"
    url = f"http://127.0.0.1:{args.port}/"
    if STATE["token"]:
        url += "?k=" + STATE["token"]

    # 服务已经在跑：直接打开页面就好，不重复启动（支持 --lan 时带访问码的链接）
    if editor_running(args.port):
        saved = ""
        try:
            saved = URL_FILE.read_text(encoding="utf-8").strip()
        except Exception:
            pass
        target = saved if saved.startswith(f"http://127.0.0.1:{args.port}/") else url
        print("编辑器已经在运行，直接打开页面：" + target, flush=True)
        if not args.no_browser:
            webbrowser.open(target)
        return

    try:
        srv = ThreadingHTTPServer((host, args.port), Handler)
    except OSError as e:
        print(f"端口 {args.port} 被占用（{e}）。换一个端口试试：")
        print(f"  python tools/tvjson_simple.py --port {args.port + 1}")
        sys.exit(1)
    srv.daemon_threads = True
    try:
        URL_FILE.parent.mkdir(parents=True, exist_ok=True)
        URL_FILE.write_text(url, encoding="utf-8")
    except Exception:
        pass
    cfg = STATE["cfg"]
    print("=" * 62)
    print("tv.json 极简 Web 编辑器")
    print(f"  NAS      : {cfg['host']}:{cfg['port']}  账号 {cfg['user'] or '(未填)'}")
    print(f"  清单文件 : \\\\{cfg['host']}\\{cfg['playlistShare']}\\{cfg['playlistPath']}")
    print(f"  视频共享 : {cfg['share']}")
    print(f"  打开页面 : {url}")
    if args.lan:
        import socket
        try:
            ip = socket.gethostbyname(socket.gethostname())
        except Exception:
            ip = "<本机局域网 IP>"
        print(f"  手机访问 : http://{ip}:{args.port}/?k={STATE['token']}  (需同一 WiFi)")
    print("  停止服务 : Ctrl+C")
    print("=" * 62, flush=True)
    if not args.no_browser:
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")
    finally:
        srv.server_close()


if __name__ == "__main__":
    main()
