#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
小剧场 · 片单控制台（手机 / PC 浏览器都能用）

一个不依赖 Web 框架的小服务：浏览 NAS 上的 SMB 目录、统计集数、下载文件、
可视化编排 tv.json（分区 / 必看 / 自动轮换 / 时段 / PIN），一键写回 NAS，写前自动备份。

    python tools/console.py                  # 只在本机开: http://127.0.0.1:8765/?k=访问码
    python tools/console.py --lan            # 同一 WiFi 下手机也能开
    python tools/console.py --lan --port 9000
    python tools/console.py --token mycode --no-browser

依赖：python -m pip install pysmb
配置：默认读仓库根的 nas.local.json（不进 Git），也可用 --config 或环境变量 KIDTV_CONFIG 指定。
安全：默认只监听 127.0.0.1；--lan 会监听 0.0.0.0，所有 API 都必须带访问码（.console_token）。
      访问码首次运行自动生成并记在 .console_token，手机可存书签长期用。
"""
from __future__ import annotations

import argparse
import collections
import io
import json
import mimetypes
import os
import pathlib
import re
import secrets
import socket
import sys
import threading
import time
import traceback
import urllib.parse
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    from smb.SMBConnection import SMBConnection
except ImportError:
    print("缺少依赖，请先执行：python -m pip install pysmb")
    sys.exit(1)

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# 源码运行：资源和数据都在仓库；PyInstaller 打包后：资源在临时解包目录，
# 配置和访问码必须放在 exe 同目录，否则每次启动都会丢。
FROZEN = bool(getattr(sys, "frozen", False))
BUNDLE = pathlib.Path(getattr(sys, "_MEIPASS", "")) if FROZEN else pathlib.Path(__file__).resolve().parent.parent
DATA_DIR = pathlib.Path(sys.executable).resolve().parent if FROZEN else BUNDLE
TOOLS = BUNDLE / "tools"
STATIC = TOOLS / "static"
EXAMPLE_FILE = BUNDLE / "nas.example.json"
TEMPLATE_FILE = pathlib.Path(os.environ.get("KIDTV_TEMPLATE") or (BUNDLE / "examples" / "tv.json"))


def _default_config() -> pathlib.Path:
    """打包 exe 放在 dist/ 时，自动继承项目根已有的 nas.local.json。"""
    if os.environ.get("KIDTV_CONFIG"):
        return pathlib.Path(os.environ["KIDTV_CONFIG"])
    mine = DATA_DIR / "nas.local.json"
    if mine.exists() or not FROZEN:
        return mine
    parent = DATA_DIR.parent / "nas.local.json"
    return parent if parent.exists() else mine


CONFIG_FILE = _default_config()
LOCAL_TV_FILE = CONFIG_FILE.parent / "tv.json"
# 访问码和凭据放一起：Docker 里把配置目录做成卷，重建容器后手机书签仍有效
TOKEN_FILE = CONFIG_FILE.parent / ".console_token"
DESKTOP_FILE = CONFIG_FILE.parent / ".console_desktop.json"

VIDEO_EXT = {
    ".mp4", ".mkv", ".avi", ".ts", ".m2ts", ".mov", ".wmv", ".flv",
    ".webm", ".mpg", ".mpeg", ".rmvb", ".iso", ".m4v",
}
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif"}

STATE = {"cfg": {}, "token": "", "lan": False, "port": 8765}
LOCK = threading.Lock()
CACHE: dict[tuple, tuple[float, object]] = {}
CACHE_TTL = 300.0
# 桌面版控制面板要显示最近日志；windowed 打包时 stdout 可能为 None，所有输出走 say()
LOG_LINES: collections.deque[str] = collections.deque(maxlen=200)
# 桌面启动器注入：切换监听地址、退出程序、打开编辑器窗口
DESKTOP = {"restart": None, "quit": None, "open": None}

# 控制台一旦放到外网，访问码就是唯一一道门：连续输错就临时锁定，挡住暴力猜码。
# 经反向代理（UGREENlink / Cloudflare Tunnel）时用 X-Forwarded-For 区分真实客户端，
# 需要显式设置 KIDTV_TRUST_PROXY=1 才信任该头，避免被伪造绕过。
AUTH_FAILS: dict[str, list] = {}
AUTH_MAX_FAILS = 5
AUTH_LOCK_SEC = 300


def client_key(handler) -> str:
    if os.environ.get("KIDTV_TRUST_PROXY", "").lower() in ("1", "true", "yes"):
        xff = handler.headers.get("X-Forwarded-For", "")
        if xff:
            return xff.split(",")[0].strip()
    return handler.client_address[0]


def auth_blocked(key: str) -> bool:
    rec = AUTH_FAILS.get(key)
    if not rec:
        return False
    if rec[1] > time.time():
        return True
    # rec[1] == 0 表示「还没锁，正在累计失败次数」，必须保留计数；
    # 只有锁真的存在过且已过期，才清空重新开始。
    if rec[1]:
        AUTH_FAILS.pop(key, None)
    return False


def auth_failed(key: str) -> None:
    rec = AUTH_FAILS.setdefault(key, [0, 0.0])
    rec[0] += 1
    if rec[0] >= AUTH_MAX_FAILS:
        rec[1] = time.time() + AUTH_LOCK_SEC
        say(f"访问码连续输错 {rec[0]} 次，{key} 临时锁定 5 分钟")


def say(*args) -> None:
    text = " ".join(str(a) for a in args)
    LOG_LINES.append(time.strftime("%H:%M:%S ") + text)
    try:
        print(text, flush=True)
    except Exception:
        pass


class SmbError(Exception):
    pass


# --------------------------------------------------------------------------- 配置

def blank_cfg() -> dict:
    return {
        "host": "", "port": 445, "user": "", "password": "",
        "share": "", "playlistShare": "data", "playlistPath": "curated/tv.json",
    }


def load_cfg() -> dict:
    src = CONFIG_FILE if CONFIG_FILE.exists() else EXAMPLE_FILE
    cfg = blank_cfg()
    try:
        raw = json.loads(src.read_text(encoding="utf-8"))
        for k in cfg:
            if k in raw and raw[k] not in (None, ""):
                cfg[k] = raw[k]
    except Exception as e:
        say(f"读取配置 {src.name} 失败：{e}")
    # 示例文件里的占位符不能当成真密码
    if str(cfg["password"]).startswith("YOUR_"):
        cfg["password"] = ""
    if str(cfg["user"]).startswith("YOUR_"):
        cfg["user"] = ""
    cfg["port"] = int(cfg.get("port") or 445)
    return cfg


def save_cfg(cfg: dict) -> None:
    payload = dict(cfg)
    CONFIG_FILE.parent.mkdir(parents=True, exist_ok=True)
    CONFIG_FILE.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def current_cfg() -> dict:
    with LOCK:
        return dict(STATE["cfg"])


def load_template() -> dict:
    try:
        return json.loads(TEMPLATE_FILE.read_text(encoding="utf-8"))
    except Exception:
        return {"sections": []}


# --------------------------------------------------------------------------- SMB

def smb(cfg: dict) -> SMBConnection:
    if not cfg.get("host") or not cfg.get("user") or not cfg.get("password"):
        raise SmbError("还没填 NAS 地址 / 账号 / 密码，请点右上角「连接设置」")
    c = SMBConnection(
        cfg["user"], cfg["password"], "kidtv-console", "nas",
        domain="", use_ntlm_v2=True, is_direct_tcp=True,
    )
    try:
        ok = c.connect(cfg["host"], int(cfg.get("port") or 445), timeout=15)
    except Exception as e:
        raise SmbError(f"连不上 {cfg['host']}:445 —— {e}")
    if not ok:
        raise SmbError(f"{cfg['host']}:445 认证失败，检查账号密码 / SMB 服务是否开启")
    return c


def norm(path: str) -> str:
    """SMB 路径：以 / 开头、无尾斜杠；前端传相对路径也能用。"""
    p = (path or "").replace("\\", "/").strip()
    parts = [x for x in p.split("/") if x not in ("", ".", "..")]
    return "/" + "/".join(parts)


def rel(path: str) -> str:
    return norm(path).lstrip("/")


def is_video(name: str) -> bool:
    return pathlib.Path(name).suffix.lower() in VIDEO_EXT and not name.startswith(".")


def natural_key(s: str):
    # 全部转成字符串再比较，避免 "1集" / "A集" 混排时 int 与 str 不可比较
    return [t.zfill(24) if t.isdigit() else t.lower()
            for t in re.split(r"(\d+)", s)]


def clean_title(name: str) -> str:
    n = re.sub(r"【[^】]*】", "", name)
    n = n.split(" 20")[0].strip()
    return n or name


def list_path(cfg: dict, share: str, path: str, timeout: int = 25) -> list:
    c = smb(cfg)
    try:
        entries = c.listPath(share, norm(path), timeout=timeout)
        return [e for e in entries if e.filename not in (".", "..")
                and not e.filename.startswith(".") and e.filename != "@tmp"]
    except Exception as e:
        raise SmbError(f"列目录失败 {share}:{norm(path)} —— {e}")
    finally:
        c.close()


def cached(key: tuple, producer):
    now = time.time()
    hit = CACHE.get(key)
    if hit and now - hit[0] < CACHE_TTL:
        return hit[1]
    val = producer()
    CACHE[key] = (now, val)
    return val


def list_shares(cfg: dict) -> list[str]:
    def produce():
        c = smb(cfg)
        try:
            return [s.name for s in c.listShares(timeout=20)
                    if not s.name.endswith("$") and s.type == 0]
        except Exception as e:
            raise SmbError(f"列共享失败：{e}")
        finally:
            c.close()
    return cached(("shares", cfg["host"], cfg["user"]), produce)


def count_videos(cfg: dict, share: str, path: str, depth: int = 0) -> int:
    """递归 3 层数视频，与 App 的 listVideos 行为一致。"""
    if depth > 3:
        return 0
    n = 0
    for e in list_path(cfg, share, path):
        if e.isDirectory:
            n += count_videos(cfg, share, rel(norm(path) + "/" + e.filename), depth + 1)
        elif is_video(e.filename):
            n += 1
    return n


def read_remote(cfg: dict) -> str:
    c = smb(cfg)
    try:
        buf = io.BytesIO()
        c.retrieveFile(cfg["playlistShare"], norm(cfg["playlistPath"]), buf, timeout=30)
        return buf.getvalue().decode("utf-8-sig")
    except Exception as e:
        raise SmbError(f"读不到 {cfg['playlistShare']}:{norm(cfg['playlistPath'])} —— {e}")
    finally:
        c.close()


def write_remote(cfg: dict, text: str) -> str:
    share = cfg["playlistShare"]
    path = norm(cfg["playlistPath"])
    parent = path.rsplit("/", 1)[0] or "/"
    c = smb(cfg)
    try:
        try:
            c.createDirectory(share, parent)
        except Exception:
            pass  # 已存在
        # 写前备份，改坏了可以在 NAS 上找 tv.backup.json
        try:
            old = io.BytesIO()
            c.retrieveFile(share, path, old, timeout=20)
            c.storeFile(share, parent + "/tv.backup.json",
                        io.BytesIO(old.getvalue()), timeout=30)
        except Exception:
            pass
        payload = text.encode("utf-8")
        c.storeFile(share, path, io.BytesIO(payload), timeout=40)
        back = io.BytesIO()
        c.retrieveFile(share, path, back, timeout=30)
        if len(back.getvalue()) != len(payload):
            raise SmbError("回读校验不一致，请检查 NAS 空间 / 权限")
        return f"已写入 {share}:{path}（{len(payload)} 字节，旧文件已备份为 tv.backup.json）"
    except SmbError:
        raise
    except Exception as e:
        raise SmbError(f"写入失败：{e}")
    finally:
        c.close()


# --------------------------------------------------------------------------- 远程控制卡（OPT-06）
# control.json 与 tv.json 同目录同共享，但**是独立文件**：家长改指令碰不坏片单。
# 电视端每 15 秒读一次，执行后写 control.ack.json 回执，网页据此显示「已生效」。

def control_paths(cfg: dict) -> tuple[str, str, str]:
    p = norm(cfg.get("playlistPath") or "curated/tv.json")
    parent = p.rsplit("/", 1)[0] or "/"
    return (cfg.get("playlistShare") or "data",
            parent + "/control.json", parent + "/control.ack.json")


def _now_iso() -> str:
    t = time.localtime()
    off = time.strftime("%z", t) or "+0000"
    # 带毫秒：家长 1 秒内连点「锁屏」「解除」，两条 updatedAt 也必须不同
    ms = f".{int(time.time() * 1000) % 1000:03d}"
    return time.strftime("%Y-%m-%dT%H:%M:%S", t) + ms + off[:3] + ":" + off[3:]


def read_control(cfg: dict) -> dict:
    share, cpath, apath = control_paths(cfg)
    out = {"ok": True, "path": f"{share}:{cpath}", "control": None, "ack": None}
    c = smb(cfg)
    try:
        for key, p in (("control", cpath), ("ack", apath)):
            try:
                buf = io.BytesIO()
                c.retrieveFile(share, p, buf, timeout=20)
                out[key] = json.loads(buf.getvalue().decode("utf-8-sig"))
            except Exception:
                pass   # 文件还没建、或写坏了，都当「没有」，不阻断页面
    finally:
        c.close()
    return out


def write_control(cfg: dict, incoming: dict) -> dict:
    share, cpath, _ = control_paths(cfg)
    clean = {
        "lockNow": bool(incoming.get("lockNow")),
        "addMinutes": max(0, min(240, int(incoming.get("addMinutes") or 0))),
        "pauseNow": bool(incoming.get("pauseNow")),
        # 每次发送都换新值：电视端按 updatedAt 幂等去重，不改就永远不会再执行一次
        "updatedAt": _now_iso(),
    }
    payload = (json.dumps(clean, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    c = smb(cfg)
    try:
        parent = cpath.rsplit("/", 1)[0] or "/"
        try:
            c.createDirectory(share, parent)
        except Exception:
            pass
        # 临时文件 + 原子改名：电视可能正好在轮询，别让它读到半截 JSON
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


# --------------------------------------------------------------------------- 业务

def browse(cfg: dict, share: str, path: str) -> dict:
    path = norm(path)
    entries = list_path(cfg, share, path)
    dirs, files = [], []
    for e in entries:
        full = rel(path + "/" + e.filename)
        if e.isDirectory:
            dirs.append({"name": e.filename, "path": full})
        elif is_video(e.filename) or pathlib.Path(e.filename).suffix.lower() in IMAGE_EXT:
            files.append({
                "name": e.filename, "path": full,
                "size": int(getattr(e, "file_size", 0) or 0),
                "video": is_video(e.filename),
            })
    dirs.sort(key=lambda x: natural_key(x["name"]))
    files.sort(key=lambda x: natural_key(x["name"]))
    return {
        "ok": True, "share": share, "path": rel(path),
        "parent": rel(path.rsplit("/", 1)[0]) if path != "/" else "",
        "dirs": dirs, "files": files,
    }


def scan(cfg: dict, share: str, path: str) -> dict:
    """把当前目录下每个子文件夹做成一张卡片；没有子文件夹时把当前层的视频逐集列出。"""
    path = norm(path)
    entries = list_path(cfg, share, path)
    subdirs = [e for e in entries if e.isDirectory]
    items = []
    if subdirs:
        for d in sorted(subdirs, key=lambda x: natural_key(x.filename)):
            items.append({
                "title": clean_title(d.filename),
                "folder": rel(path + "/" + d.filename),
                "subtitle": "",
            })
    else:
        for f in sorted([e for e in entries if is_video(e.filename)],
                        key=lambda x: natural_key(x.filename)):
            items.append({
                "title": clean_title(f.filename.rsplit(".", 1)[0]),
                "file": rel(path + "/" + f.filename),
            })
    return {"ok": True, "items": items, "mode": "folders" if subdirs else "files"}


def state_payload() -> dict:
    cfg = current_cfg()
    return {
        "ok": True,
        "settings": {
            "host": cfg.get("host", ""),
            "port": cfg.get("port", 445),
            "user": cfg.get("user", ""),
            "share": cfg.get("share", ""),
            "playlistShare": cfg.get("playlistShare", "data"),
            "playlistPath": cfg.get("playlistPath", "curated/tv.json"),
            "hasPassword": bool(cfg.get("password")),
            "configFile": str(CONFIG_FILE),
        },
        "lan": STATE["lan"],
    }


def lan_ips() -> list[str]:
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("223.5.5.5", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127."):
                ips.add(ip)
    except Exception:
        pass
    return sorted(ips)


# --------------------------------------------------------------------------- 桌面版
# PC 桌面助手通过下列接口管理本地服务；网页控制台（CLI / Docker）不受影响。

def desktop_prefs() -> dict:
    prefs = {"lan": False, "port": 8765}
    try:
        if DESKTOP_FILE.exists():
            prefs.update(json.loads(DESKTOP_FILE.read_text(encoding="utf-8")))
    except Exception:
        pass
    return prefs


def save_desktop_prefs(patch: dict) -> dict:
    prefs = desktop_prefs()
    for k in ("lan", "port"):
        if k in patch:
            prefs[k] = bool(patch[k]) if k == "lan" else int(patch[k])
    try:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        DESKTOP_FILE.write_text(json.dumps(prefs, ensure_ascii=False, indent=2), encoding="utf-8")
    except Exception as e:
        raise SmbError(f"保存桌面偏好失败：{e}")
    return prefs


def desktop_urls() -> dict:
    port = int(STATE["port"])
    token = STATE["token"]
    k = urllib.parse.quote(token)
    ips = lan_ips()
    # 优先和 NAS 同一个网段，其次家用路由器网段；别把虚拟网卡地址当成手机访问地址
    nas = str(current_cfg().get("host") or "")
    prefix = ".".join(nas.split(".")[:3]) + "." if nas.count(".") == 3 else ""
    host = next((ip for ip in ips if prefix and ip.startswith(prefix)), None) \
        or next((ip for ip in ips if ip.startswith("192.168.")), None) \
        or (ips[0] if ips else "127.0.0.1")
    return {
        "local": f"http://127.0.0.1:{port}/?k={k}",
        "desktop": f"http://127.0.0.1:{port}/static/desktop.html?k={k}",
        "editor": f"http://127.0.0.1:{port}/static/index.html?k={k}",
        "phone": f"http://{host}:{port}/?k={k}",
        "host": host, "token": token, "port": port,
    }


def desktop_state() -> dict:
    cfg = current_cfg()
    hit = CACHE.get(("shares", cfg.get("host"), cfg.get("user")))
    return {
        "ok": True,
        "running": True,
        "frozen": FROZEN,
        "lan": bool(STATE["lan"]),
        "urls": desktop_urls(),
        "ips": lan_ips(),
        "dataDir": str(DATA_DIR),
        "configFile": str(CONFIG_FILE),
        "localTvFile": str(LOCAL_TV_FILE),
        "hasLocalTv": LOCAL_TV_FILE.exists(),
        "settings": {
            "host": cfg.get("host", ""), "port": cfg.get("port", 445),
            "user": cfg.get("user", ""), "share": cfg.get("share", ""),
            "playlistShare": cfg.get("playlistShare", "data"),
            "playlistPath": cfg.get("playlistPath", "curated/tv.json"),
            "hasPassword": bool(cfg.get("password")),
        },
        "connection": "ok" if hit else "unknown",
        "qrAvailable": _qr_available(),
        "prefs": desktop_prefs(),
        "canManage": DESKTOP["restart"] is not None and DESKTOP["quit"] is not None,
    }


def _qr_available() -> bool:
    try:
        import qrcode.image.svg  # noqa: F401
        return True
    except Exception:
        return False


def qr_svg(text: str) -> bytes:
    import qrcode
    import qrcode.image.svg
    img = qrcode.make(text, image_factory=qrcode.image.svg.SvgPathImage,
                      box_size=7, border=2)
    buf = io.BytesIO()
    img.save(buf)
    return buf.getvalue()


def desktop_check() -> dict:
    """首页「检测连接」：真连一次 SMB，顺便看清单是从哪读的。"""
    cfg = current_cfg()
    shares = list_shares(cfg)
    source, detail = "none", ""
    try:
        read_remote(cfg)
        source = "nas"
        detail = f"\\\\{cfg['host']}\\{cfg['playlistShare']}\\{cfg['playlistPath']}"
    except Exception as e:
        if LOCAL_TV_FILE.exists():
            source, detail = "local", str(LOCAL_TV_FILE)
        else:
            source, detail = "template", str(e)
    return {"ok": True, "connected": True, "shares": shares,
            "playlist": {"source": source, "detail": detail}}


# --------------------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    server_version = "KidTvConsole/1.0"

    def log_message(self, fmt, *args):
        msg = fmt % args
        if "/api/" in msg or msg.startswith("code "):
            say(f"  {msg}")

    # ---- helpers ----
    def json_out(self, obj, code: int = 200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            pass

    def html_out(self, body: bytes, ctype: str = "text/html; charset=utf-8"):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            pass

    def query(self) -> dict:
        return urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)

    def authorized(self) -> bool:
        k = self.headers.get("X-Console-Token") or self.query().get("k", [""])[0]
        if not k:
            # 没带访问码 = 正常的「首次打开显示登录门」，不计失败；
            # 否则登录页一次并发几个 API 就会把自己锁 5 分钟。
            return False
        ok = secrets.compare_digest(str(k), STATE["token"])
        key = client_key(self)
        if ok:
            AUTH_FAILS.pop(key, None)
        else:
            auth_failed(key)
        return ok

    def deny_locked(self) -> bool:
        if not auth_blocked(client_key(self)):
            return False
        self.json_out({"ok": False,
                       "error": "访问码连续输错，已临时锁定 5 分钟，请稍后再试"}, 429)
        return True

    def body(self) -> dict:
        n = int(self.headers.get("Content-Length", 0) or 0)
        if not n:
            return {}
        try:
            return json.loads(self.rfile.read(n).decode("utf-8"))
        except Exception as e:
            raise SmbError(f"请求体不是合法 JSON：{e}")

    @staticmethod
    def one(q: dict, key: str, default: str = "") -> str:
        return q.get(key, [default])[0]

    # ---- routes ----
    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        try:
            # 无需访问码：桌面启动器用它判断「是不是已经有一个助手在跑」
            if path == "/api/desktop/ping":
                return self.json_out({"ok": True, "app": "kidtv-console",
                                      "port": int(STATE["port"]),
                                      "lan": bool(STATE["lan"])})
            if path == "/desktop":
                f = STATIC / "desktop.html"
                return self.html_out(f.read_bytes() if f.exists()
                                     else b"<h1>missing desktop.html</h1>")
            if path in ("/", "/index.html", "/static/index.html"):
                f = STATIC / "index.html"
                if not f.exists():
                    return self.html_out("<h1>缺少 tools/static/index.html</h1>".encode("utf-8"))
                return self.html_out(f.read_bytes())
            if path.startswith("/static/"):
                f = (STATIC / path[len("/static/"):]).resolve()
                if STATIC not in f.parents and f != STATIC:
                    return self.json_out({"ok": False, "error": "非法路径"}, 403)
                if f.exists() and f.is_file():
                    ctype = mimetypes.guess_type(str(f))[0] or "application/octet-stream"
                    if ctype.startswith("text/") or "javascript" in ctype:
                        ctype += "; charset=utf-8"
                    return self.html_out(f.read_bytes(), ctype)
                return self.json_out({"ok": False, "error": "找不到文件"}, 404)
            if path == "/favicon.ico":
                self.send_response(204)
                self.end_headers()
                return
            if not path.startswith("/api/"):
                return self.json_out({"ok": False, "error": "未知路径"}, 404)
            if self.deny_locked():
                return
            if not self.authorized():
                return self.json_out({"ok": False, "error": "访问码不对，请用启动时的完整链接打开"}, 401)

            cfg = current_cfg()
            q = self.query()

            if path == "/api/desktop/state":
                return self.json_out(desktop_state())
            if path == "/api/desktop/logs":
                return self.json_out({"ok": True, "lines": list(LOG_LINES)[-120:]})
            if path == "/api/desktop/qr":
                text = self.one(q, "text")
                if not text:
                    raise SmbError("二维码内容为空")
                return self.html_out(qr_svg(text), "image/svg+xml; charset=utf-8")
            if path == "/api/state":
                out = state_payload()
                try:
                    out["shares"] = list_shares(cfg)
                except Exception as e:
                    out["shares"] = []
                    out["warning"] = str(e)
                return self.json_out(out)

            if path == "/api/shares":
                return self.json_out({"ok": True, "shares": list_shares(cfg)})

            if path == "/api/config":
                try:
                    text = read_remote(cfg)
                    return self.json_out({
                        "ok": True, "data": json.loads(text), "source": "nas",
                        "where": f"\\\\{cfg['host']}\\{cfg['playlistShare']}\\{cfg['playlistPath']}",
                    })
                except Exception as e:
                    if LOCAL_TV_FILE.exists():
                        try:
                            data = json.loads(LOCAL_TV_FILE.read_text(encoding="utf-8-sig"))
                            return self.json_out({"ok": True, "data": data, "source": "local",
                                                  "warning": f"NAS 读取失败（{e}），已改用本地 tv.json"})
                        except Exception:
                            pass
                    return self.json_out({
                        "ok": True, "data": load_template(), "source": "template",
                        "warning": f"NAS 上还没有清单（{e}），已载入公开模板，改完点「保存到 NAS」即可",
                    })

            if path == "/api/control":
                return self.json_out(read_control(cfg))

            if path == "/api/template":
                return self.json_out({"ok": True, "data": load_template(), "source": "template"})

            if path == "/api/browse":
                share = self.one(q, "share") or cfg["share"]
                if not share:
                    raise SmbError("先在「连接设置」里填视频共享名，或在上方选一个共享")
                return self.json_out(browse(cfg, share, self.one(q, "path")))

            if path == "/api/count":
                share = self.one(q, "share") or cfg["share"]
                target = self.one(q, "path")
                n = cached(("count", cfg["host"], share, target),
                           lambda: count_videos(cfg, share, target))
                return self.json_out({"ok": True, "path": target, "videos": n})

            if path == "/api/scan":
                share = self.one(q, "share") or cfg["share"]
                return self.json_out(scan(cfg, share, self.one(q, "path")))

            if path == "/api/download":
                share = self.one(q, "share") or cfg["share"]
                target = norm(self.one(q, "path"))
                name = target.rsplit("/", 1)[-1]
                size = int(self.one(q, "size", "0") or 0)
                c = smb(cfg)
                try:
                    self.send_response(200)
                    self.send_header("Content-Type", mimetypes.guess_type(name)[0]
                                     or "application/octet-stream")
                    if size:
                        self.send_header("Content-Length", str(size))
                    self.send_header("Content-Disposition",
                                     "attachment; filename*=UTF-8''" + urllib.parse.quote(name))
                    self.end_headers()
                    c.retrieveFile(share, target, self.wfile, timeout=120)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                except Exception as e:
                    # 响应头已发出，不能再回 JSON；直接断连让浏览器提示下载失败
                    say(f"  下载失败：{e}")
                    self.close_connection = True
                finally:
                    c.close()
                return

            return self.json_out({"ok": False, "error": "未知 API"}, 404)
        except SmbError as e:
            return self.json_out({"ok": False, "error": str(e)}, 400)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            return
        except Exception as e:
            say(traceback.format_exc())
            return self.json_out({"ok": False, "error": str(e)}, 500)

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        try:
            if self.deny_locked():
                return
            if not self.authorized():
                return self.json_out({"ok": False, "error": "访问码不对，请用启动时的完整链接打开"}, 401)
            data = self.body()

            if path == "/api/desktop/check":
                return self.json_out(desktop_check())
            if path == "/api/desktop/prefs":
                return self.json_out({"ok": True, "prefs": save_desktop_prefs(data)})
            if path == "/api/desktop/lan":
                enabled = bool(data.get("enabled"))
                cb = DESKTOP["restart"]
                if not cb:
                    raise SmbError("当前是命令行 / Docker 模式，请用桌面版启动才能一键切换")
                save_desktop_prefs({"lan": enabled})
                threading.Timer(0.4, _safe, args=(cb, enabled)).start()
                return self.json_out({"ok": True,
                                      "message": ("已开启" if enabled else "已关闭") +
                                                 "手机访问，正在重新绑定服务…"})
            if path == "/api/desktop/quit":
                if self.client_address[0] not in ("127.0.0.1", "::1"):
                    return self.json_out({"ok": False, "error": "只允许在电脑本机退出"}, 403)
                cb = DESKTOP["quit"]
                if not cb:
                    raise SmbError("当前是命令行 / Docker 模式，请关掉终端窗口退出")
                threading.Timer(0.4, _safe, args=(cb,)).start()
                return self.json_out({"ok": True, "message": "正在退出"})

            if path == "/api/settings":
                cfg = current_cfg()
                for k in ("host", "port", "user", "share", "playlistShare", "playlistPath"):
                    if k in data and str(data[k]).strip():
                        cfg[k] = str(data[k]).strip()
                if data.get("password"):       # 留空 = 沿用已保存的密码
                    cfg["password"] = str(data["password"])
                cfg["port"] = int(cfg.get("port") or 445)
                shares = list_shares(cfg)      # 顺带验证账号可用
                if data.get("save", True):
                    save_cfg(cfg)
                with LOCK:
                    STATE["cfg"] = cfg
                CACHE.clear()
                return self.json_out({"ok": True, "shares": shares,
                                      "message": f"已连接 {cfg['host']}，找到 {len(shares)} 个共享"})

            if path == "/api/browse" or path == "/api/scan":
                cfg = current_cfg()
                share = data.get("share") or cfg["share"]
                if path.endswith("scan"):
                    return self.json_out(scan(cfg, share, data.get("path", "")))
                return self.json_out(browse(cfg, share, data.get("path", "")))

            if path == "/api/control":
                return self.json_out(write_control(current_cfg(), data))

            if path == "/api/config":
                payload = data.get("data")
                if not isinstance(payload, dict) or "sections" not in payload:
                    raise SmbError("清单格式不对：必须是含 sections 的 JSON 对象")
                # 电视靠 tv.json 里的 nas 段连 SMB。控制台知道真实密码（前端不回显），
                # 保存时自动同步成当前连接设置，避免用户载入模板后忘改占位符账号。
                cfg = current_cfg()
                if data.get("syncNas", True) and cfg.get("password"):
                    payload["nas"] = {
                        "host": cfg["host"],
                        "port": int(cfg.get("port") or 445),
                        "user": cfg["user"],
                        "password": cfg["password"],
                        "share": cfg["share"],
                    }
                    payload["playlistShare"] = cfg.get("playlistShare") or "data"
                text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
                target = data.get("target", "nas")
                if target == "local":
                    LOCAL_TV_FILE.write_text(text, encoding="utf-8")
                    msg = f"已写入本地 {LOCAL_TV_FILE}（{len(text.encode('utf-8'))} 字节）"
                else:
                    msg = write_remote(current_cfg(), text)
                return self.json_out({"ok": True, "message": msg,
                                      "bytes": len(text.encode("utf-8"))})

            return self.json_out({"ok": False, "error": "未知 API"}, 404)
        except SmbError as e:
            return self.json_out({"ok": False, "error": str(e)}, 400)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            return
        except Exception as e:
            say(traceback.format_exc())
            return self.json_out({"ok": False, "error": str(e)}, 500)


def get_token(arg_token: str | None) -> str:
    if arg_token:
        return arg_token
    if os.environ.get("KIDTV_TOKEN"):
        return os.environ["KIDTV_TOKEN"]
    try:
        if TOKEN_FILE.exists():
            t = TOKEN_FILE.read_text(encoding="utf-8").strip()
            if t:
                return t
        # 16 位十六进制：够强，又能手输 / 复制（旧的 8 位访问码继续沿用，不破坏书签）
        t = secrets.token_hex(8)
        TOKEN_FILE.write_text(t, encoding="utf-8")
        return t
    except Exception:
        return secrets.token_hex(8)


def _safe(cb, *args) -> None:
    try:
        cb(*args)
    except Exception as e:
        say(f"桌面操作失败：{e}")


def init_state(config: str | None = None, token: str | None = None,
               lan: bool = False, port: int = 8765) -> str:
    """命令行和 PC 桌面启动器共用；返回访问码。"""
    global CONFIG_FILE, TOKEN_FILE, DESKTOP_FILE
    if config:
        CONFIG_FILE = pathlib.Path(config)
        TOKEN_FILE = CONFIG_FILE.parent / ".console_token"
        DESKTOP_FILE = CONFIG_FILE.parent / ".console_desktop.json"
    STATE["cfg"] = load_cfg()
    STATE["token"] = get_token(token)
    STATE["lan"] = bool(lan)
    STATE["port"] = int(port)
    return STATE["token"]


def make_server(bind: str, port: int) -> ThreadingHTTPServer:
    srv = ThreadingHTTPServer((bind, int(port)), Handler)
    srv.daemon_threads = True
    return srv


def print_banner(lan: bool, port: int) -> str:
    u = desktop_urls()
    say("\n小剧场 · 片单控制台")
    say("=" * 56)
    say(f"  配置文件 : {CONFIG_FILE}")
    say(f"  NAS      : {STATE['cfg']['host'] or '（未配置，进页面点连接设置）'}")
    say(f"  访问码   : {STATE['token']}  （API 必须携带，链接里已带 k=）")
    say(f"  本机打开 : {u['local']}")
    if lan:
        for ip in lan_ips():
            say(f"  手机打开 : http://{ip}:{port}/?k={STATE['token']}")
        say("  手机需和这台机器在同一个 WiFi / 局域网")
        if len(STATE["token"]) < 12:
            say("  ! 当前访问码偏短；要放到外网，请先用 --token / KIDTV_TOKEN 换成 16 位以上强码")
    else:
        say("  想让手机也能打开：加 --lan 重新启动")
    say("=" * 56)
    try:
        import qrcode  # 可选：终端打印二维码，手机扫一下就进
        qr = qrcode.QRCode(border=1)
        qr.add_data(u["phone"])
        qr.make()
        qr.print_ascii(invert=True)
    except Exception:
        pass
    say("Ctrl+C 停止\n")
    return u["local"]


def main():
    ap = argparse.ArgumentParser(description="小剧场片单控制台")
    ap.add_argument("--lan", action="store_true", help="监听 0.0.0.0，允许手机等局域网设备访问")
    ap.add_argument("--port", type=int, default=int(os.environ.get("KIDTV_PORT", "8765")))
    ap.add_argument("--token", help="自定义访问码（默认自动生成并记在 .console_token）")
    ap.add_argument("--config", help="指定 nas.local.json 路径")
    ap.add_argument("--no-browser", action="store_true", help="不自动打开浏览器")
    args = ap.parse_args()

    init_state(args.config, args.token, args.lan, args.port)
    url_local = print_banner(args.lan, args.port)
    server = make_server("0.0.0.0" if args.lan else "127.0.0.1", args.port)
    if not args.no_browser:
        try:
            webbrowser.open(url_local)
        except Exception:
            pass
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        say("\n已停止")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
