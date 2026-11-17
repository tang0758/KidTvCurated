#!/usr/bin/env python3
"""console.py 流媒体网关（/stream/）回归测试：不连 NAS，用假 SMB 连接 + 本地临时 HTTP 服务。

覆盖 2026-11-17 实机测试发现的三个缺陷，以及相关边界：
  1. Range 起点越界必须 416（旧实现回 206 + 非法 Content-Range）
  2. 文件 / 共享不存在回 404，NAS 连不上回 502（旧实现一律 500）
  3. 支持 HEAD（旧实现 501）
  附带：后缀范围 bytes=-N 取最后 N 字节（旧实现被当成 0-N）

运行：python tools/test_console_stream.py
"""
from __future__ import annotations

import http.client
import pathlib
import sys
import threading
import unittest
import urllib.parse
from http.server import ThreadingHTTPServer

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import console  # noqa: E402
from smb.smb_structs import OperationFailure  # noqa: E402

DATA = bytes(range(256)) * 4          # 1024 字节，内容可逐字节核对
TOTAL = len(DATA)


class _Attrs:
    def __init__(self, n):
        self.file_size = n


class FakeSmb:
    """只实现 handle_stream 用到的四个方法。"""
    files = {("data", "/curated/tv.json"): DATA,
             ("儿童资源", "/视频/a b.mp4"): DATA,
             ("data", "/empty.bin"): b""}

    def getAttributes(self, share, path):
        if (share, path) not in self.files:
            raise OperationFailure("not found", [])
        return _Attrs(len(self.files[(share, path)]))

    def retrieveFileFromOffset(self, share, path, fh, offset=0, max_length=-1, timeout=60):
        fh.write(self.files[(share, path)][offset:offset + max_length])

    def retrieveFile(self, share, path, fh, timeout=60):
        fh.write(self.files[(share, path)])

    def close(self):
        pass


class StreamServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._orig = (console.smb, console.current_cfg)
        console.smb = lambda cfg: FakeSmb()
        console.current_cfg = lambda: {}
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), console.Handler)
        cls.port = cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()
        console.smb, console.current_cfg = cls._orig

    def req(self, method, path, rng=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        headers = {"Range": rng} if rng else {}
        conn.request(method, urllib.parse.quote(path), headers=headers)
        r = conn.getresponse()
        body = r.read()
        hdr = {k.lower(): v for k, v in r.getheaders()}
        conn.close()
        return r.status, hdr, body

    URL = "/stream/data/curated/tv.json"

    # ---- 原有行为不能退化 ----
    def test_无Range_200整文件(self):
        st, h, b = self.req("GET", self.URL)
        self.assertEqual((st, b), (200, DATA))
        self.assertEqual(h["content-length"], str(TOTAL))
        self.assertEqual(h["accept-ranges"], "bytes")

    def test_区间_206(self):
        st, h, b = self.req("GET", self.URL, "bytes=0-99")
        self.assertEqual(st, 206)
        self.assertEqual(h["content-range"], f"bytes 0-99/{TOTAL}")
        self.assertEqual(b, DATA[0:100])

    def test_开区间_206(self):
        st, h, b = self.req("GET", self.URL, "bytes=1000-")
        self.assertEqual((st, b), (206, DATA[1000:]))
        self.assertEqual(h["content-range"], f"bytes 1000-{TOTAL - 1}/{TOTAL}")

    def test_终点越界被截到文件末尾(self):
        st, h, b = self.req("GET", self.URL, "bytes=1000-99999")
        self.assertEqual((st, b), (206, DATA[1000:]))

    def test_中文共享名和带空格路径(self):
        st, _, b = self.req("GET", "/stream/儿童资源/视频/a b.mp4", "bytes=10-19")
        self.assertEqual((st, b), (206, DATA[10:20]))

    # ---- 缺陷 1：越界 Range ----
    def test_起点越界_416(self):
        st, h, b = self.req("GET", self.URL, "bytes=99999999-")
        self.assertEqual(st, 416)
        self.assertEqual(h["content-range"], f"bytes */{TOTAL}")
        self.assertEqual(b, b"")

    def test_起点等于文件大小_416(self):
        self.assertEqual(self.req("GET", self.URL, f"bytes={TOTAL}-")[0], 416)

    def test_空文件任何Range_416(self):
        st, h, _ = self.req("GET", "/stream/data/empty.bin", "bytes=0-")
        self.assertEqual((st, h["content-range"]), (416, "bytes */0"))

    def test_空文件无Range_200(self):
        st, h, b = self.req("GET", "/stream/data/empty.bin")
        self.assertEqual((st, h["content-length"], b), (200, "0", b""))

    def test_后缀范围取最后N字节(self):
        st, h, b = self.req("GET", self.URL, "bytes=-100")
        self.assertEqual((st, b), (206, DATA[-100:]))
        self.assertEqual(h["content-range"], f"bytes {TOTAL - 100}-{TOTAL - 1}/{TOTAL}")

    def test_后缀范围大于文件取整个文件(self):
        st, _, b = self.req("GET", self.URL, "bytes=-99999")
        self.assertEqual((st, b), (206, DATA))

    def test_后缀范围0_416(self):
        self.assertEqual(self.req("GET", self.URL, "bytes=-0")[0], 416)

    def test_看不懂的Range按标准忽略回200(self):
        for bad in ("bytes=abc-def", "bytes=5-2", "items=0-9", "bytes=0-9,20-29", "bytes="):
            with self.subTest(bad=bad):
                st, _, b = self.req("GET", self.URL, bad)
                self.assertEqual((st, b), (200, DATA))

    # ---- 缺陷 2：错误码 ----
    def test_文件不存在_404(self):
        self.assertEqual(self.req("GET", "/stream/data/curated/nope.bin")[0], 404)

    def test_共享不存在_404(self):
        self.assertEqual(self.req("GET", "/stream/nosuchshare/a")[0], 404)

    def test_路径穿越不出共享根且回404(self):
        # norm() 会丢掉 .. 段：解析成 data:/etc/passwd，不存在 → 404，绝不会读到别处的文件
        self.assertEqual(self.req("GET", "/stream/data/../../etc/passwd")[0], 404)

    def test_NAS连不上_502(self):
        orig = console.smb

        def boom(cfg):
            raise console.SmbError("连不上 NAS")
        console.smb = boom
        try:
            self.assertEqual(self.req("GET", self.URL)[0], 502)
        finally:
            console.smb = orig

    def test_未预期异常_500(self):
        orig = FakeSmb.getAttributes
        FakeSmb.getAttributes = lambda *a: (_ for _ in ()).throw(RuntimeError("bug"))
        try:
            self.assertEqual(self.req("GET", self.URL)[0], 500)
        finally:
            FakeSmb.getAttributes = orig

    def test_路径不完整_400(self):
        self.assertEqual(self.req("GET", "/stream/onlyshare")[0], 400)

    # ---- 缺陷 3：HEAD ----
    def test_HEAD_有头无体(self):
        st, h, b = self.req("HEAD", self.URL)
        self.assertEqual((st, b), (200, b""))
        self.assertEqual(h["content-length"], str(TOTAL))
        self.assertEqual(h["accept-ranges"], "bytes")

    def test_HEAD带Range_206只有头(self):
        st, h, b = self.req("HEAD", self.URL, "bytes=0-9")
        self.assertEqual((st, b), (206, b""))
        self.assertEqual(h["content-range"], f"bytes 0-9/{TOTAL}")
        self.assertEqual(h["content-length"], "10")

    def test_HEAD文件不存在_404(self):
        self.assertEqual(self.req("HEAD", "/stream/data/nope")[0], 404)

    def test_HEAD非stream路径_404(self):
        self.assertEqual(self.req("HEAD", "/api/config")[0], 404)


class ParseRangeTest(unittest.TestCase):
    """parse_range 纯函数：边界值逐个钉死。"""

    def test_table(self):
        pr = console.parse_range
        cases = [
            (None, 100, ("full", 0, 99)),
            ("bytes=0-0", 100, ("range", 0, 0)),
            ("bytes=0-99", 100, ("range", 0, 99)),
            ("bytes=99-99", 100, ("range", 99, 99)),
            ("bytes=99-", 100, ("range", 99, 99)),
            ("bytes=100-", 100, ("416", 0, 0)),
            ("bytes=0-", 0, ("416", 0, 0)),
            ("bytes=-1", 100, ("range", 99, 99)),
            ("bytes=-100", 100, ("range", 0, 99)),
            ("bytes=-101", 100, ("range", 0, 99)),
            ("bytes=-0", 100, ("416", 0, 0)),
            ("bytes=10-5", 100, ("full", 0, 99)),
            ("bytes=a-b", 100, ("full", 0, 99)),
            ("bytes=1-2,5-6", 100, ("full", 0, 99)),
            ("bytes=-", 100, ("full", 0, 99)),
        ]
        for header, total, want in cases:
            with self.subTest(header=header, total=total):
                self.assertEqual(pr(header, total), want)


if __name__ == "__main__":
    unittest.main(verbosity=2)
