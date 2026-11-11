#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""小剧场 · 统一启动页（OPT-19：一个启动页 + 三个模式，功能不合并）。

双击仓库根的「小剧场.bat」，或在命令行：

    python tools/launcher.py                 # 127.0.0.1:8700，自动开浏览器
    python tools/launcher.py --port 8800
    python tools/launcher.py --no-browser
    python tools/launcher.py --self-test     # 不起窗口，自检各接口后退出

**它做什么**：起一个只监听 127.0.0.1 的小网页，列出三个既有工具的卡片，
显示各自「运行中 / 未启动」，点一下就拉起对应服务并跳转到它的页面。

**它不做什么**（「功能不合并」的边界）：
- 不读写 NAS、不碰 tv.json、不知道访问码以外的任何业务逻辑；
- 不改动 `tvjson_simple.py` / `console.py` / `desktop.py` 一行；
- 不合并三个工具的界面，不常驻代理它们的流量 —— 拉起之后浏览器直达原服务端口；
- 启动页本身关掉不影响已经启动的工具（子进程用独立进程组）。

三个模式里 console 与 desktop 共用 8765 上的同一个后端，所以两者只会起一个服务；
另一个点「打开」时直接复用（这正是过去最容易撞端口的地方）。
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import launcher_math as L  # noqa: E402

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = pathlib.Path(__file__).resolve().parent.parent
TOOLS = ROOT / "tools"
PAGE = TOOLS / "static" / "launcher.html"
TOKEN_FILE = ROOT / ".console_token"          # console.py / desktop.py 共用
SIMPLE_URL_FILE = ROOT / ".tmp" / "tvjson-editor-url.txt"
LOG_DIR = ROOT / ".tmp" / "launcher-logs"     # .tmp 已在 .gitignore 里
EXE = ROOT / L.mode("desktop")["exe"]
DEFAULT_PORT = 8700

# 拉起过的子进程，仅用于 --self-test 与退出时的展示；不跟踪、不杀别人的进程
CHILDREN: list[subprocess.Popen] = []
LOGS: dict[str, pathlib.Path] = {}
LOCK = threading.Lock()
BOOT: dict = {"port": DEFAULT_PORT, "token": "", "started": time.time()}


def log_path(key: str) -> pathlib.Path:
    return LOG_DIR / ("%s.log" % key)


def tail_log(key: str, lines: int = 12) -> str:
    """启动失败时把子进程日志尾巴带给页面，比黑窗口一闪而过好查。"""
    try:
        text = log_path(key).read_text(encoding="utf-8", errors="replace").splitlines()
        return "\n".join(text[-lines:])
    except Exception:
        return ""


def say(*args) -> None:
    print(" ".join(str(a) for a in args), flush=True)


# --------------------------------------------------------------------------- 状态

def read_text(p: pathlib.Path) -> str:
    try:
        return p.read_text(encoding="utf-8").strip()
    except Exception:
        return ""


def console_token() -> str:
    """读 console 写下的访问码；读不到就返回空串（页面会显示登录门）。"""
    return read_text(TOKEN_FILE)


def probe(key: str, timeout: float = 1.5) -> bool:
    """真的去敲一下那个服务的免鉴权接口。任何异常都算「没在跑」。

    端口必须和 launch() 拉起时用的一致 —— 否则换端口后会一直打在默认端口上，
    把已经起来的服务误判成「没起来」，然后重复拉起撞端口。
    """
    try:
        url = L.probe_url(key, port_of(key))
        with urllib.request.urlopen(url, timeout=timeout) as r:
            # 必须读完整个响应：极简编辑器的 GET / 是整页 HTML（约 90 KB），
            # 只读前几 KB 就断开会让对方往已关闭的 socket 写，刷一屏
            # ConnectionAbortedError 堆栈，家长看了以为出事了。
            body = r.read().decode("utf-8", "replace")
        return L.probe_matches(key, body)
    except Exception:
        return False


def backend_running() -> dict[str, bool]:
    """三个端口并行探活。

    串行的最坏耗时是 3 × 1.5 秒 = 4.5 秒，启动页每 15 秒刷一次状态就会被拖住；
    并行后最坏只有一个探活超时（1.5 秒）。
    """
    out: dict[str, bool] = {k: False for k in L.MODE_KEYS}
    threads = [threading.Thread(target=lambda k=k: out.__setitem__(k, probe(k)),
                                daemon=True) for k in L.MODE_KEYS]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=2.0)
    return out


def port_of(key: str) -> int:
    """允许环境变量覆盖端口（与 console.py / tvjson_simple.py 的约定对齐）。"""
    m = L.mode(key)
    if not m:
        return 0
    if key == "simple" and os.environ.get("KIDTV_SIMPLE_PORT"):
        try:
            return int(os.environ["KIDTV_SIMPLE_PORT"])
        except ValueError:
            pass
    if key in ("console", "desktop") and os.environ.get("KIDTV_PORT"):
        try:
            return int(os.environ["KIDTV_PORT"])
        except ValueError:
            pass
    return int(m["port"])


def urls() -> dict[str, str]:
    token = console_token()
    return {
        k: L.open_url(k, port=port_of(k), token=token,
                      saved_url=read_text(SIMPLE_URL_FILE) if k == "simple" else "")
        for k in L.MODE_KEYS
    }


def status() -> dict:
    """给启动页的数据：每个模式是否在跑、该显示什么按钮、点开去哪。"""
    running = backend_running()
    u = urls()
    items = []
    for m in L.MODES:
        key = m["key"]
        # console / desktop 共用一个后端：只要那个端口在跑，两个都算「已就绪」
        alive = running[key]
        already = ""
        if not alive:
            for other in L.MODE_KEYS:
                if other != key and running[other] and L.shared_backend(key, other):
                    alive = False  # 仍标未启动，但按钮改为「打开」复用现成的服务
                    already = L.mode(other)["title"]
                    break
        st = L.status_of(key, alive, already=already)
        items.append({
            "key": key, "title": m["title"], "tagline": m["tagline"],
            "desc": m["desc"], "forwhom": m["forwhom"], "mark": m["mark"],
            "accent": m["accent"], "kind": m["kind"], "port": port_of(key),
            "url": u[key], "running": alive, "reuse": bool(already),
            "reuseOf": already, "label": st["label"], "action": st["action"],
            "hint": st["hint"],
        })
    return {
        "ok": True, "app": "kidtv-launcher", "root": str(ROOT),
        "port": BOOT["port"], "hasExe": EXE.exists(),
        "logDir": str(LOG_DIR),
        "consoleShared": L.shared_backend("console", "desktop"),
        "modes": items,
    }


# --------------------------------------------------------------------------- 拉起

def launch(key: str) -> dict:
    """按白名单拉起一个模式，等它就绪后把该打开的 URL 交给前端。"""
    if not L.is_known(key):
        return {"ok": False, "error": "未知模式：%s" % key}

    # 共用后端：另一个模式已经起了服务，就别再拉一个（会撞 8765 端口）
    for other in L.MODE_KEYS:
        if other != key and L.shared_backend(key, other) and probe(other):
            say("「%s」已在运行，复用它的服务，不重复启动。" % L.mode(other)["title"])
            return {"ok": True, "reused": True, "url": urls()[key],
                    "window": False, "wait": False}

    if probe(key):
        return {"ok": True, "reused": True, "url": urls()[key],
                "window": False, "wait": False}

    m = L.mode(key)
    use_exe = key == "desktop" and EXE.exists()
    cmd = L.build_command(key, sys.executable, use_exe=use_exe,
                          exe_path=str(EXE), port=port_of(key))
    say("启动「%s」：%s" % (m["title"], " ".join(cmd)))
    try:
        # 子进程输出收进 .tmp/launcher-logs/<mode>.log：
        # ① 三个工具都会在终端打印横幅 / 访问码，三份混在一个黑窗口里看不清；
        # ② 极简编辑器在客户端提前断连时会打一屏 ConnectionAbortedError 堆栈，
        #    家长不该看到这种「其实没坏」的红字。失败时页面会把日志尾巴显示出来。
        LOG_DIR.mkdir(parents=True, exist_ok=True)
        lf = open(log_path(key), "a", encoding="utf-8", errors="replace")
        lf.write("\n===== %s 启动 %s =====\n" % (
            time.strftime("%Y-%m-%d %H:%M:%S"), m["title"]))
        lf.flush()
        env = dict(os.environ)
        env["PYTHONIOENCODING"] = "utf-8"   # 否则 Windows 用 GBK 写中文横幅会乱码
        env["PYTHONUNBUFFERED"] = "1"       # 启动失败时日志里能看到最后一句
        kw: dict = {"cwd": str(ROOT), "env": env, "stdout": lf,
                    "stderr": subprocess.STDOUT}
        if sys.platform == "win32":
            # CREATE_NEW_PROCESS_GROUP：启动页关掉 / Ctrl+C 不会顺带带走子进程
            kw["creationflags"] = 0x00000200
        else:
            kw["start_new_session"] = True
        proc = subprocess.Popen(cmd, **kw)
        lf.close()  # 子进程已继承句柄，父进程这边不必一直开着
        with LOCK:
            CHILDREN.append(proc)
            LOGS[key] = log_path(key)
    except Exception as e:
        return {"ok": False, "error": "启动失败：%s" % e}

    # 冷启动第一次探活放宽到 3 秒：服务刚 bind、页面还没生成时 1.5 秒容易超时，
    # 客户端一断开，既有工具写整页就会抛 ConnectionAbortedError。
    deadline = time.time() + 15
    first = True
    while time.time() < deadline:
        if probe(key, timeout=3.0 if first else 1.5):
            break
        first = False
        if proc.poll() is not None:
            return {"ok": False, "error": "服务进程已退出（退出码 %s）。日志：\n%s"
                    % (proc.returncode, tail_log(key))}
        time.sleep(0.4)
    else:
        return {"ok": False, "error": "15 秒内没等到服务就绪（端口被占？pysmb 没装？）。日志：\n%s"
                % tail_log(key)}

    # 服务刚起来，重读一次它写下的访问码 / URL 存档，别用旧的
    return {"ok": True, "reused": False, "url": urls()[key],
            "window": m["kind"] == "window", "wait": False}


# --------------------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    server_version = "KidTvLauncher/1.0"

    def log_message(self, fmt, *args):
        pass  # 启动页别刷访问日志，安静一点

    def _send(self, code: int, body: bytes, ctype: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _json(self, obj: dict, code: int = 200) -> None:
        self._send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"),
                   "application/json; charset=utf-8")

    def do_GET(self) -> None:
        path = self.path.split("?", 1)[0]
        if path in ("/", "/index.html", "/launcher.html"):
            if not PAGE.exists():
                return self._send(404, b"missing launcher.html", "text/plain; charset=utf-8")
            return self._send(200, PAGE.read_bytes(), "text/html; charset=utf-8")
        if path == "/api/status":
            return self._json(status())
        if path == "/api/ping":
            return self._json({"ok": True, "app": "kidtv-launcher", "port": BOOT["port"]})
        return self._send(404, b"not found", "text/plain; charset=utf-8")

    def do_POST(self) -> None:
        path = self.path.split("?", 1)[0]
        if path != "/api/launch":
            return self._json({"ok": False, "error": "not found"}, 404)
        try:
            n = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(n) if n else b"{}"
            key = str(json.loads(raw.decode("utf-8", "replace")).get("mode") or "")
        except Exception as e:
            return self._json({"ok": False, "error": "请求体不是合法 JSON：%s" % e}, 400)
        return self._json(launch(key))


def make_server(bind: str, port: int) -> ThreadingHTTPServer:
    srv = ThreadingHTTPServer((bind, port), Handler)
    srv.daemon_threads = True
    return srv


# --------------------------------------------------------------------------- 自检

def self_test(port: int) -> bool:
    """起一个临时服务，逐个打接口；不拉起任何真实工具、不开浏览器。"""
    srv = make_server("127.0.0.1", port)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    base = "http://127.0.0.1:%d" % port
    ok = True

    def check(name: str, fn) -> None:
        nonlocal ok
        try:
            hit = fn()
            say("  [%s] %s" % ("PASS" if hit else "FAIL", name))
            ok = ok and bool(hit)
        except Exception as e:
            say("  [FAIL] %s  %s" % (name, e))
            ok = False

    def get(path: str):
        # /api/status 要并行敲三个真实端口，本机有服务在跑时可能各等 1.5 秒，
        # 3 秒太紧；给 8 秒，别让自检自己超时误报。
        with urllib.request.urlopen(base + path, timeout=8) as r:
            return r.status, r.read().decode("utf-8", "replace")

    def post(path: str, obj: dict):
        req = urllib.request.Request(
            base + path, data=json.dumps(obj).encode("utf-8"), method="POST",
            headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=3) as r:
                return r.status, json.loads(r.read().decode("utf-8", "replace"))
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8", "replace"))

    check("启动页 HTML 有标题", lambda: "小剧场" in get("/")[1])
    check("/api/ping 返回本应用标识",
          lambda: json.loads(get("/api/ping")[1]).get("app") == "kidtv-launcher")
    check("/api/status 列出三个模式", lambda: len(
        json.loads(get("/api/status")[1])["modes"]) == 3)
    check("/api/status 标记 console/desktop 共用后端", lambda:
          json.loads(get("/api/status")[1])["consoleShared"] is True)
    def rejects(obj: dict) -> bool:
        code, payload = post("/api/launch", obj)
        return code == 200 and payload.get("ok") is False

    check("非法模式被拒绝", lambda: rejects({"mode": "rm -rf /"}))
    check("空请求体不崩", lambda: rejects({}))

    srv.shutdown()
    srv.server_close()
    say("自检结果：%s" % ("全部通过" if ok else "有失败项，看上面"))
    return ok


# --------------------------------------------------------------------------- 入口

def main() -> int:
    ap = argparse.ArgumentParser(description="小剧场 · 统一启动页")
    ap.add_argument("--port", type=int, default=int(os.environ.get("KIDTV_LAUNCHER_PORT", DEFAULT_PORT)))
    ap.add_argument("--no-browser", action="store_true", help="不自动打开浏览器")
    ap.add_argument("--self-test", action="store_true", help="自检接口后退出，不开窗口")
    args = ap.parse_args()

    if args.self_test:
        return 0 if self_test(args.port + 1) else 1

    # 已经有一个启动页在跑：直接打开它，不重复起服务（跟三个工具的单实例习惯一致）
    try:
        with urllib.request.urlopen("http://127.0.0.1:%d/api/ping" % args.port, timeout=1) as r:
            if json.loads(r.read().decode("utf-8", "replace")).get("app") == "kidtv-launcher":
                say("启动页已经在运行，直接打开：http://127.0.0.1:%d/" % args.port)
                if not args.no_browser:
                    webbrowser.open("http://127.0.0.1:%d/" % args.port)
                return 0
    except Exception:
        pass

    BOOT["port"] = args.port
    try:
        srv = make_server("127.0.0.1", args.port)
    except OSError as e:
        say("端口 %d 被占用（%s），换一个：python tools/launcher.py --port %d"
            % (args.port, e, args.port + 1))
        return 1

    url = "http://127.0.0.1:%d/" % args.port
    say("")
    say("小剧场 · 统一启动页")
    say("=" * 58)
    say("  启动页   : %s" % url)
    say("  仓库     : %s" % ROOT)
    say("  三个模式 : 极简编辑器 / 网页控制台 / PC 桌面助手")
    say("  说明     : 只监听 127.0.0.1，关掉这个窗口不影响已启动的工具")
    say("=" * 58)
    say("Ctrl+C 停止启动页\n")
    if not args.no_browser:
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        say("\n启动页已停止（已经打开的工具继续运行）。")
    finally:
        srv.server_close()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(0)
