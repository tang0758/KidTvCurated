#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""统一启动页的纯逻辑单测。

跑法（仓库根）：
    python -m unittest discover -s tools -p "test_*.py" -v

CI 里也在 Ubuntu 上跑同一条命令：这里不连 NAS、不起服务、不开浏览器、
不 import 任何平台专有模块，只验证「按白名单拼命令 / 拼 URL / 解析探活」这些
最容易悄悄写错、又最难在电视上发现的部分。
"""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import launcher_math as L  # noqa: E402


class ModesTest(unittest.TestCase):
    def test_三个模式且顺序固定(self):
        self.assertEqual(L.MODE_KEYS, ("simple", "console", "desktop"))
        self.assertEqual(len(L.MODES), 3)

    def test_端口与展示信息齐全(self):
        expect = {"simple": 8787, "console": 8765, "desktop": 8765}
        for m in L.MODES:
            self.assertEqual(m["port"], expect[m["key"]])
            for field in ("title", "tagline", "desc", "forwhom", "mark", "accent", "script"):
                self.assertTrue(m.get(field), "%s 缺 %s" % (m["key"], field))
            self.assertIn(m["kind"], ("web", "window"))

    def test_白名单只认三个模式(self):
        for k in L.MODE_KEYS:
            self.assertTrue(L.is_known(k))
        for k in ("", "unknown", "../console.py", "console;rm -rf", "SIMPLE", None):
            self.assertFalse(L.is_known(k))
            self.assertIsNone(L.mode(k))

    def test_console_与_desktop_共用后端_其余不共用(self):
        self.assertTrue(L.shared_backend("console", "desktop"))
        self.assertTrue(L.shared_backend("desktop", "console"))
        self.assertFalse(L.shared_backend("simple", "console"))
        self.assertFalse(L.shared_backend("console", "console"))  # 自己不算
        self.assertFalse(L.shared_backend("nope", "console"))     # 未知 key 不算

    def test_desktop_有独立_exe_入口(self):
        self.assertEqual(L.mode("desktop")["exe"], "dist/KidTvConfig-PC.exe")


class ProbeTest(unittest.TestCase):
    def test_探活_URL_都走免鉴权接口(self):
        self.assertEqual(L.probe_url("simple"), "http://127.0.0.1:8787/")
        self.assertEqual(L.probe_url("console"), "http://127.0.0.1:8765/api/desktop/ping")
        self.assertEqual(L.probe_url("desktop"), "http://127.0.0.1:8765/api/desktop/ping")
        self.assertEqual(L.probe_url("simple", 9000), "http://127.0.0.1:9000/")
        self.assertNotIn("k=", L.probe_url("console"))  # 绝不带访问码

    def test_未知模式_探活_URL_直接拒绝(self):
        with self.assertRaises(ValueError):
            L.probe_url("nope")

    def test_simple_靠页面标题串识别(self):
        self.assertTrue(L.probe_matches("simple", "<title>tv.json 编辑器</title>"))
        self.assertFalse(L.probe_matches("simple", "<title>别的服务</title>"))

    def test_console_desktop_靠_ping_的_app_字段识别(self):
        body = json.dumps({"ok": True, "app": "kidtv-console", "port": 8765})
        self.assertTrue(L.probe_matches("console", body))
        self.assertTrue(L.probe_matches("desktop", body))

    def test_探活坏响应一律不当成在跑(self):
        for body in ("", "not json", "{}", "[]", "null",
                     json.dumps({"app": "other"}),
                     json.dumps({"ok": True}),           # 缺 app
                     json.dumps({"app": "kidtv-console-extra"}),
                     "<html>404</html>", None):
            self.assertFalse(L.probe_matches("console", body))
            self.assertFalse(L.probe_matches("simple", body))

    def test_未知模式探活不匹配(self):
        self.assertFalse(L.probe_matches("nope", json.dumps({"app": "kidtv-console"})))


class CommandTest(unittest.TestCase):
    PY = "C:/Python311/python.exe"

    def test_simple_与_console_加_no_browser(self):
        self.assertEqual(
            L.build_command("simple", self.PY),
            [self.PY, "tools/tvjson_simple.py", "--port", "8787", "--no-browser"])
        self.assertEqual(
            L.build_command("console", self.PY),
            [self.PY, "tools/console.py", "--port", "8765", "--no-browser"])

    def test_desktop_源码模式不加_no_browser_因为它自己要开窗(self):
        self.assertEqual(
            L.build_command("desktop", self.PY),
            [self.PY, "tools/desktop.py", "--port", "8765"])

    def test_desktop_优先用打包好的_exe(self):
        self.assertEqual(
            L.build_command("desktop", self.PY, use_exe=True,
                            exe_path="dist/KidTvConfig-PC.exe"),
            ["dist/KidTvConfig-PC.exe", "--port", "8765"])

    def test_exe_不存在时回退源码(self):
        self.assertEqual(
            L.build_command("desktop", self.PY, use_exe=False,
                            exe_path="dist/KidTvConfig-PC.exe"),
            [self.PY, "tools/desktop.py", "--port", "8765"])

    def test_端口可覆盖(self):
        self.assertEqual(L.build_command("simple", self.PY, port=9000),
                         [self.PY, "tools/tvjson_simple.py", "--port", "9000", "--no-browser"])

    def test_未知模式拒绝拼命令_防注入(self):
        for k in ("console;rm -rf", "../evil", "", None):
            with self.assertRaises(ValueError):
                L.build_command(k, self.PY)

    def test_命令里没有外部输入拼接的痕迹(self):
        for k in L.MODE_KEYS:
            cmd = L.build_command(k, self.PY)
            self.assertNotIn(";", " ".join(cmd))
            self.assertNotIn("&", " ".join(cmd))


class UrlTest(unittest.TestCase):
    def test_simple_默认地址(self):
        self.assertEqual(L.open_url("simple"), "http://127.0.0.1:8787/")

    def test_simple_优先用记下的带访问码链接(self):
        self.assertEqual(
            L.open_url("simple", saved_url="http://127.0.0.1:8787/?k=abc123"),
            "http://127.0.0.1:8787/?k=abc123")

    def test_simple_不认端口不符或外部地址的存档(self):
        for bad in ("http://127.0.0.1:9000/?k=x", "http://evil.example/?k=x",
                    "file:///etc/passwd", "javascript:alert(1)"):
            self.assertEqual(L.open_url("simple", saved_url=bad),
                             "http://127.0.0.1:8787/")

    def test_console_desktop_拼访问码(self):
        self.assertEqual(L.open_url("console", token="a b&c"),
                         "http://127.0.0.1:8765/?k=a%20b%26c")
        self.assertEqual(L.open_url("desktop", token="t0k"),
                         "http://127.0.0.1:8765/static/desktop.html?k=t0k")

    def test_空访问码时不带参数_页面会显示登录门(self):
        self.assertEqual(L.open_url("console"), "http://127.0.0.1:8765/")
        self.assertEqual(L.open_url("desktop"),
                         "http://127.0.0.1:8765/static/desktop.html")

    def test_端口可覆盖(self):
        self.assertEqual(L.open_url("console", port=9000, token="x"),
                         "http://127.0.0.1:9000/?k=x")

    def test_未知模式拒绝拼_URL(self):
        with self.assertRaises(ValueError):
            L.open_url("nope")


class StatusTest(unittest.TestCase):
    def test_已运行只提示打开(self):
        s = L.status_of("console", True)
        self.assertTrue(s["running"])
        self.assertEqual(s["action"], "打开")

    def test_未启动提示启动并打开(self):
        s = L.status_of("simple", False)
        self.assertFalse(s["running"])
        self.assertEqual(s["action"], "启动并打开")

    def test_共用后端已在跑时不重复拉起(self):
        s = L.status_of("desktop", False, already="网页控制台")
        self.assertFalse(s["running"])
        self.assertEqual(s["action"], "打开")   # 关键：不是「启动并打开」
        self.assertIn("网页控制台", s["hint"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
