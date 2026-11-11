#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""小剧场 · 统一启动页的纯逻辑（不碰 socket / subprocess / 平台 API，可直接单测）。

`launcher.py` 只负责「起一个本地网页、探测三个模式在不在跑、按白名单拉起、跳转」，
所有能离线判定的部分放这里，好让 CI 不用 NAS、不用浏览器也能跑单测。

三个模式对应仓库里三个既有入口，**启动器不改动它们任何一行**：

    simple   -> tools/tvjson_simple.py   极简编辑器      127.0.0.1:8787  网页
    console  -> tools/console.py         网页控制台      127.0.0.1:8765  网页
    desktop  -> tools/desktop.py (或 exe) PC 桌面助手    127.0.0.1:8765  独立窗口

注意：console 与 desktop **共用同一个后端和同一个端口**，一次只能起一个服务；
      另一个要「打开」而不是「再拉一个」（否则 8765 端口冲突）。
"""
from __future__ import annotations

import json
from urllib.parse import quote

# 探活响应里用来确认「是本项目的服务，而不是碰巧占了端口的别的东西」
PROBE_MARK_SIMPLE = "tv.json 编辑器"
PROBE_APP_CONSOLE = "kidtv-console"

# 顺序即启动页上的展示顺序。key 同时是 /api/launch 的白名单。
MODES = (
    {
        "key": "simple",
        "title": "极简编辑器",
        "port": 8787,
        "kind": "web",
        "script": "tools/tvjson_simple.py",
        "tagline": "只想改片单 / 时段",
        "desc": "一页搞定分区、条目、时段和 JSON 原文，最轻。只依赖 pysmb。",
        "forwhom": "日常改片单",
        "mark": "简",
        "accent": "#0d9488",
    },
    {
        "key": "console",
        "title": "网页控制台",
        "port": 8765,
        "kind": "web",
        "script": "tools/console.py",
        "tagline": "完整编排 / 手机也能开",
        "desc": "浏览 NAS 目录、批量加集、封面、远程控制、二维码——功能最全的一个。",
        "forwhom": "大改片单 / 手机访问",
        "mark": "控",
        "accent": "#4f6bed",
    },
    {
        "key": "desktop",
        "title": "PC 桌面助手",
        "port": 8765,
        "kind": "window",
        "script": "tools/desktop.py",
        "exe": "dist/KidTvConfig-PC.exe",
        "tagline": "双击即用 / 独立窗口",
        "desc": "不用记命令和端口，独立窗口里编排片单、看日志、开手机二维码、一键退出。",
        "forwhom": "不想敲命令",
        "mark": "PC",
        "accent": "#7c3aed",
    },
)

MODE_KEYS = tuple(m["key"] for m in MODES)


def mode(key: str) -> dict | None:
    """按 key 取模式定义；未知 key 返回 None（这是白名单的唯一入口）。"""
    for m in MODES:
        if m["key"] == key:
            return m
    return None


def is_known(key: str) -> bool:
    return mode(key) is not None


def shared_backend(a: str, b: str) -> bool:
    """console 与 desktop 共用 8765，同时只能起一个服务。"""
    ma, mb = mode(a), mode(b)
    return bool(ma and mb and ma["port"] == mb["port"] and a != b)


def probe_url(key: str, port: int | None = None) -> str:
    """探活 URL。两个后端都留了免访问码的接口，启动页不必知道 token。"""
    m = mode(key)
    if not m:
        raise ValueError("未知模式: %s" % key)
    p = int(port if port is not None else m["port"])
    if key == "simple":
        # tvjson_simple 的 GET / 直接返回页面 HTML，含标题串
        return "http://127.0.0.1:%d/" % p
    # console.py 给桌面启动器留的免鉴权 ping，desktop 复用同一后端
    return "http://127.0.0.1:%d/api/desktop/ping" % p


def probe_matches(key: str, body: str) -> bool:
    """判断探活响应是不是「本项目对应的服务」。坏响应一律 False，绝不误判。"""
    if not is_known(key) or not body:
        return False
    if key == "simple":
        return PROBE_MARK_SIMPLE in body
    try:
        data = json.loads(body)
    except (ValueError, TypeError):
        return False
    return isinstance(data, dict) and data.get("app") == PROBE_APP_CONSOLE


def build_command(key: str, python_exe: str, *, use_exe: bool = False,
                  exe_path: str = "", port: int | None = None) -> list[str]:
    """构造 Popen 的 argv。脚本一律相对路径（配 cwd=仓库根），跨平台。

    只按白名单拼固定参数，**绝不把外部输入拼进命令**（防注入）。
    web 类加 --no-browser：跳转由启动页负责，避免弹两个标签页。
    """
    m = mode(key)
    if not m:
        raise ValueError("未知模式: %s" % key)
    p = int(port if port is not None else m["port"])
    if key == "desktop" and use_exe and exe_path:
        # exe 自己会开独立窗口；只把端口传过去，不加 --no-window
        return [exe_path, "--port", str(p)]
    cmd = [python_exe, m["script"], "--port", str(p)]
    if m["kind"] == "web":
        cmd.append("--no-browser")
    return cmd


def open_url(key: str, *, port: int | None = None, token: str = "",
             saved_url: str = "", host: str = "127.0.0.1") -> str:
    """点「打开」时浏览器该去的地址。

    simple 优先用它自己写下的 .tmp/tvjson-editor-url.txt（--lan 时带 k=）；
    console / desktop 用 .console_token 拼 ?k=，空 token 时不带参数（页面会显示登录门）。
    """
    m = mode(key)
    if not m:
        raise ValueError("未知模式: %s" % key)
    p = int(port if port is not None else m["port"])
    if key == "simple":
        if saved_url.startswith("http://%s:%d/" % (host, p)):
            return saved_url
        return "http://%s:%d/" % (host, p)
    base = "http://%s:%d/" % (host, p)
    if key == "desktop":
        base += "static/desktop.html"
    if token:
        base += "?k=" + quote(token, safe="")
    return base


def status_of(key: str, running: bool, *, already: str = "") -> dict:
    """启动页状态条文案。already 是「共用后端的另一个模式」的名称。"""
    if running:
        return {"running": True, "label": "运行中", "action": "打开",
                "hint": "服务已经起来了，直接打开即可。"}
    if already:
        return {"running": False, "label": "可复用", "action": "打开",
                "hint": "「%s」已在运行，两者共用同一个服务，直接打开，不必再起一个。" % already}
    return {"running": False, "label": "未启动", "action": "启动并打开",
            "hint": "点一下会自动拉起服务并打开页面。"}
