#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
小剧场 · PC 配置助手（Windows / macOS / Linux 都能跑）

把网页控制台包装成一个桌面应用：双击启动 → 独立窗口 → 连 NAS 编排片单。
复用 tools/console.py 的全部后端和 tools/static/index.html 的全部编辑能力，
不引入 Web 框架；窗口优先用 pywebview（装了才用），否则用 Edge / Chrome 的
--app 无地址栏窗口，最后回退到默认浏览器。

    python tools/desktop.py                      # 启动桌面窗口
    python tools/desktop.py --lan                # 同时允许手机访问
    python tools/desktop.py --window browser     # 用默认浏览器打开
    python tools/desktop.py --no-window          # 只跑服务，不开窗口
    python tools/desktop.py --self-test          # 不开窗口，自检所有接口后退出

打包成独立 exe：见 tools/build_pc_exe.bat
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import shutil
import subprocess
import sys
import threading
import urllib.parse
import urllib.request
import webbrowser

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import console  # noqa: E402

STOP = threading.Event()
LOCK = threading.Lock()
SERVER: dict = {"obj": None, "port": 8765}


# --------------------------------------------------------------------------- 单实例

def ping(port: int) -> dict | None:
    try:
        with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/api/desktop/ping", timeout=1.2) as r:
            data = json.loads(r.read().decode("utf-8"))
            return data if data.get("app") == "kidtv-console" else None
    except Exception:
        return None


def stored_token() -> str:
    try:
        if console.TOKEN_FILE.exists():
            return console.TOKEN_FILE.read_text(encoding="utf-8").strip()
    except Exception:
        pass
    return ""


def open_existing(port: int, info: dict, token: str, mode: str) -> int:
    console.STATE.update({"port": port, "token": token, "lan": bool(info.get("lan"))})
    url = console.desktop_urls()["desktop"]
    console.say(f"已有一个配置助手在运行（端口 {port}），直接打开它的窗口。")
    if not token:
        console.say("提示：读不到访问码，若页面提示 401，请从最先启动的那个终端 / 日志里找到访问码，")
        console.say("      或关掉旧的服务后重新启动本助手。")
    open_window(url, mode)
    return 0


# --------------------------------------------------------------------------- 窗口

def find_browser() -> str | None:
    candidates = [os.environ.get("KIDTV_BROWSER")]
    pf = os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")
    p64 = os.environ.get("ProgramFiles", r"C:\Program Files")
    candidates += [
        os.path.join(pf, "Microsoft", "Edge", "Application", "msedge.exe"),
        os.path.join(p64, "Microsoft", "Edge", "Application", "msedge.exe"),
        os.path.join(p64, "Google", "Chrome", "Application", "chrome.exe"),
        os.path.join(pf, "Google", "Chrome", "Application", "chrome.exe"),
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
    ]
    for p in candidates:
        if p and pathlib.Path(p).exists():
            return p
    for name in ("msedge", "chrome", "google-chrome", "chromium"):
        p = shutil.which(name)
        if p:
            return p
    return None


def open_window(url: str, mode: str) -> str:
    """返回 webview / app / browser / none。webview 会阻塞到窗口关闭。"""
    if mode in ("auto", "webview"):
        try:
            import webview  # 可选依赖：pip install pywebview
            win = webview.create_window("小剧场 · 配置助手", url,
                                        width=1280, height=860, min_size=(900, 600))
            try:
                win.events.closed += lambda: STOP.set()
            except Exception:
                pass
            webview.start()
            return "webview"
        except ImportError:
            if mode == "webview":
                console.say("没装 pywebview，回退到浏览器应用窗口（pip install pywebview 可启用原生窗口）")
        except Exception as e:
            console.say(f"pywebview 启动失败（{e}），回退到浏览器应用窗口")

    if mode in ("auto", "app"):
        exe = find_browser()
        if exe:
            profile = console.DATA_DIR / ".pc_profile"
            try:
                profile.mkdir(parents=True, exist_ok=True)
            except Exception:
                pass
            cmd = [exe, f"--app={url}", "--window-size=1280,860",
                   "--no-first-run", "--no-default-browser-check",
                   f"--user-data-dir={profile}"]
            try:
                kw = {"stdout": subprocess.DEVNULL, "stderr": subprocess.DEVNULL}
                if sys.platform == "win32":
                    kw["creationflags"] = 0x00000008  # DETACHED_PROCESS
                subprocess.Popen(cmd, **kw)
                return "app"
            except Exception as e:
                console.say(f"启动应用窗口失败（{e}），改用默认浏览器")

    if mode == "none":
        return "none"
    try:
        webbrowser.open(url)
        return "browser"
    except Exception as e:
        console.say(f"打开浏览器失败：{e}")
        return "none"


def watch_window() -> None:
    """Edge/Chrome 应用窗口被关掉后自动停服务（仅 Windows，纯 ctypes 无依赖）。"""
    if sys.platform != "win32":
        return
    try:
        import ctypes
        from ctypes import wintypes
        user32 = ctypes.windll.user32
        cb_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
        found: list[int] = []

        def cb(hwnd, _):
            n = user32.GetWindowTextLengthW(hwnd)
            if n:
                buf = ctypes.create_unicode_buffer(n + 1)
                user32.GetWindowTextW(hwnd, buf, n + 1)
                if "小剧场" in buf.value and "配置助手" in buf.value:
                    found.append(1)
            return True

        seen = False
        while not STOP.is_set():
            found.clear()
            user32.EnumWindows(cb_type(cb), 0)
            if found:
                seen = True
            elif seen:
                console.say("检测到配置助手窗口已关闭，服务停止。")
                STOP.set()
                break
            STOP.wait(2)
    except Exception as e:
        console.say(f"窗口监视不可用：{e}")


# --------------------------------------------------------------------------- 服务管理

def restart_bind(enabled: bool) -> None:
    """桌面页「允许手机访问」开关：重新绑定 127.0.0.1 ↔ 0.0.0.0。"""
    with LOCK:
        old = SERVER["obj"]
        port = SERVER["port"]
        bind = "0.0.0.0" if enabled else "127.0.0.1"
        try:
            if old:
                old.shutdown()
                old.server_close()
            new = console.make_server(bind, port)
        except Exception as e:
            console.say(f"切换监听失败，保持原状：{e}")
            return
        console.STATE["lan"] = bool(enabled)
        SERVER["obj"] = new
        threading.Thread(target=new.serve_forever, daemon=True).start()
        console.say(f"手机访问已{'开启' if enabled else '关闭'}（监听 {bind}:{port}）")


def http(url: str, token: str, method: str = "GET", body: dict | None = None,
         timeout: int = 40) -> tuple[int, str, bytes]:
    data = json.dumps(body or {}).encode("utf-8") if method == "POST" else None
    req = urllib.request.Request(
        url, data=data, method=method,
        headers={"X-Console-Token": token, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.headers.get_content_type(), r.read()


def self_test(port: int, token: str) -> bool:
    base = f"http://127.0.0.1:{port}"
    k = urllib.parse.quote(token)
    cases = [
        ("桌面 ping", f"{base}/api/desktop/ping", "GET", None, b"kidtv-console"),
        ("桌面首页", f"{base}/static/desktop.html", "GET", None, b"\xe9\x85\x8d\xe7\xbd\xae\xe5\x8a\xa9\xe6\x89\x8b"),
        ("编辑器页面", f"{base}/static/index.html?k={k}", "GET", None, b"\xe7\x89\x87\xe5\x8d\x95\xe6\x8e\xa7\xe5\x88\xb6\xe5\x8f\xb0"),
        ("桌面状态", f"{base}/api/desktop/state?k={k}", "GET", None, b'"running"'),
        ("运行日志", f"{base}/api/desktop/logs?k={k}", "GET", None, b'"lines"'),
        ("控制台状态", f"{base}/api/state?k={k}", "GET", None, b'"settings"'),
        ("读取 NAS 清单", f"{base}/api/config?k={k}", "GET", None, b'"sections"'),
        ("检测 NAS 连接", f"{base}/api/desktop/check?k={k}", "POST", {}, b'"connected"'),
    ]
    ok = True
    for name, url, method, body, expect in cases:
        try:
            status, _, raw = http(url, token, method, body)
            hit = expect in raw
            console.say(f"  [{'PASS' if hit else 'FAIL'}] {name}  HTTP {status}, {len(raw)} 字节")
            ok = ok and hit and status == 200
        except Exception as e:
            console.say(f"  [FAIL] {name}  {e}")
            ok = False
    if console._qr_available():
        try:
            status, ctype, raw = http(f"{base}/api/desktop/qr?k={k}&text={urllib.parse.quote('http://test')}",
                                      token, timeout=10)
            hit = b"<svg" in raw
            console.say(f"  [{'PASS' if hit else 'FAIL'}] 手机二维码  {ctype}, {len(raw)} 字节")
            ok = ok and hit
        except Exception as e:
            console.say(f"  [FAIL] 手机二维码  {e}")
            ok = False
    else:
        console.say("  [SKIP] 手机二维码（pip install qrcode 后可用）")
    console.say(f"自检结果：{'全部通过' if ok else '有失败项，看上面日志'}")
    return ok


# --------------------------------------------------------------------------- 入口

def main() -> int:
    ap = argparse.ArgumentParser(description="小剧场 · PC 配置助手")
    ap.add_argument("--port", type=int, default=None, help="本机端口，默认记住上次的，首次 8765")
    ap.add_argument("--token", help="访问码，默认自动生成并记在 .console_token")
    ap.add_argument("--config", help="指定 nas.local.json 路径")
    ap.add_argument("--lan", action="store_true", help="允许同一 WiFi 的手机访问")
    ap.add_argument("--no-lan", action="store_true", help="强制只允许本机访问")
    ap.add_argument("--window", choices=["auto", "webview", "app", "browser", "none"],
                    default="auto", help="窗口模式，默认 auto（pywebview → Edge/Chrome 应用窗口 → 浏览器）")
    ap.add_argument("--no-window", action="store_true", help="只跑服务，不开窗口")
    ap.add_argument("--self-test", action="store_true", help="自检所有接口后退出，不开窗口")
    args = ap.parse_args()

    prefs = console.desktop_prefs()
    port = args.port or int(os.environ.get("KIDTV_PORT") or prefs.get("port") or 8765)
    lan = True if args.lan else (False if args.no_lan else bool(prefs.get("lan")))
    mode = "none" if (args.no_window or args.self_test) else args.window

    existing = ping(port)
    if existing:
        return open_existing(port, existing, args.token or stored_token(), mode)

    token = console.init_state(args.config, args.token, lan, port)
    try:
        server = console.make_server("0.0.0.0" if lan else "127.0.0.1", port)
    except OSError as e:
        console.say(f"端口 {port} 被占用且不是本助手：{e}")
        console.say("换一个端口再试：python tools/desktop.py --port 8766")
        return 1
    SERVER.update({"obj": server, "port": port})
    console.DESKTOP["restart"] = restart_bind
    console.DESKTOP["quit"] = STOP.set
    threading.Thread(target=server.serve_forever, daemon=True).start()

    u = console.desktop_urls()
    console.say("\n小剧场 · PC 配置助手")
    console.say("=" * 60)
    console.say(f"  数据目录 : {console.DATA_DIR}")
    console.say(f"  NAS      : {console.STATE['cfg']['host'] or '（未配置，进窗口点连接设置）'}")
    console.say(f"  桌面窗口 : {u['desktop']}")
    console.say(f"  手机访问 : {'已开启 ' + u['phone'] if lan else '未开启（窗口里可一键打开）'}")
    console.say("=" * 60)

    result = 0
    if args.self_test:
        result = 0 if self_test(port, token) else 1
        STOP.set()
    else:
        kind = open_window(u["desktop"], mode)
        if kind == "webview":
            STOP.set()          # pywebview.start() 返回 = 窗口已关闭
        elif kind == "app" and sys.platform == "win32" and not args.no_window:
            threading.Thread(target=watch_window, daemon=True).start()
        try:
            while not STOP.wait(0.5):
                pass
        except KeyboardInterrupt:
            console.say("收到 Ctrl+C，正在停止…")

    with LOCK:
        srv = SERVER["obj"]
    try:
        srv.shutdown()
        srv.server_close()
    except Exception:
        pass
    console.say("配置助手已退出。")
    return result


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(0)
