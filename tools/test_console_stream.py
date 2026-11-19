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


class LearningApiTest(unittest.TestCase):
    """词库保存 / 图片上传 / 自动生成发音 mp3（纯 FakeSmb）。"""

    def test_save_learning_data_writes_json_and_mp3(self):
        written = {}

        class FakeSmbWrite(FakeSmb):
            def createDirectory(self, share, path):
                pass

            def storeFile(self, share, path, fh, timeout=20):
                written[(share, path)] = fh.read()

        orig_smb = console.smb
        orig_gen = console.generate_word_and_sentence_mp3
        console.smb = lambda cfg: FakeSmbWrite()
        console.generate_word_and_sentence_mp3 = lambda w, s="", n="": b"ID3fake-mp3-bytes-" + w.encode("utf-8")
        try:
            res = console.save_learning_data(
                {"host": "127.0.0.1", "playlistShare": "data"},
                {"version": 2, "dailyWordCount": 12, "cards": [
                    {"id": "word_cat", "type": "word", "title": "cat", "lines": ["The cat is cute."], "note": "猫"}
                ]}
            )
            self.assertEqual(res["count"], 1)
            self.assertEqual(res["generatedMp3"], 1)
            self.assertIn(("data", "curated/learning/audio/word_cat.mp3"), written)
            self.assertIn(("data", "curated/learning.json"), written)
        finally:
            console.smb = orig_smb
            console.generate_word_and_sentence_mp3 = orig_gen

    def test_silence_1s_like_produces_valid_mpeg2_frames(self):
        gap = console._silence_1s_like(bytes.fromhex("fff3e4c4"))
        # 24kHz / 160kbps -> 480B/帧, 24ms/帧 -> 42帧 = 20160B
        self.assertEqual(len(gap), 42 * 480)
        self.assertEqual(gap[:4], bytes.fromhex("fff3e4c4"))

    def test_save_learning_image_rejects_oversize(self):
        import base64
        big = base64.b64encode(b"x" * (2 * 1024 * 1024 + 10)).decode("ascii")
        with self.assertRaises(console.SmbError):
            console.save_learning_image({}, "word_cat", "cat.png", big)

    def test_validate_pin_fields_digits_only(self):
        good = {"pin": " 123456 ", "schedule": {"pin": "8888"}}
        console.validate_pin_fields(good)
        self.assertEqual(good["pin"], "123456")
        self.assertEqual(good["schedule"]["pin"], "8888")
        console.validate_pin_fields({"pin": "1234", "schedule": {"pin": ""}})
        for bad in [
            {"pin": "abcd"},
            {"pin": "123a"},
            {"pin": "123"},
            {"pin": "123456789"},
            {"pin": 1234},
            {"pin": ""},
            {"pin": "1234", "schedule": {"pin": "ab12"}},
        ]:
            with self.subTest(bad=bad):
                with self.assertRaises(console.SmbError):
                    console.validate_pin_fields(dict(bad))

    def test_conflict_detection_and_sync_status(self):
        h1 = console.text_sha256('{"sections":[]}')
        h2 = console.text_sha256('{"sections":[{"key":"daily"}]}')
        self.assertEqual(h1, console.text_sha256('\ufeff{"sections":[]}'))
        console.check_write_conflict('{"sections":[]}', h1, force=False)
        console.check_write_conflict(None, h1, force=False)
        with self.assertRaises(console.ConflictError):
            console.check_write_conflict('{"sections":[{"key":"daily"}]}', h1, force=False)
        console.check_write_conflict('{"sections":[{"key":"daily"}]}', h1, force=True)

        st_synced = console.compute_sync_status(h2, "learn1", {"tvHash": h2, "learningHash": "learn1", "appliedAt": "2026-11-19T10:50:00+08:00"})
        self.assertTrue(st_synced["synced"])
        st_stale_tv = console.compute_sync_status(h2, "learn1", {"tvHash": h1, "learningHash": "learn1"})
        self.assertFalse(st_stale_tv["synced"])
        self.assertFalse(st_stale_tv["tvSynced"])
        st_stale_learn = console.compute_sync_status(h2, "learn2", {"tvHash": h2, "learningHash": "learn1"})
        self.assertFalse(st_stale_learn["synced"])
        self.assertTrue(st_stale_learn["tvSynced"])
        self.assertFalse(st_stale_learn["learningSynced"])
        st_no_ack = console.compute_sync_status(h2, "", None)
        self.assertFalse(st_no_ack["synced"])


class CoverGenerationTest(unittest.TestCase):
    """每日必看封面：已有封面零截帧复用 + 目录图复用 + 缺失截帧上传。"""

    def test_reuses_existing_and_generates_only_missing(self):
        import io
        import cover_gen

        reused_key = "动画/旧片.mp4"
        reused_tag = cover_gen.cover_tag(reused_key)
        adj_folder = "动画/带图剧集"
        new_folder = "动画/新剧集"

        class _Entry:
            def __init__(self, filename, is_dir=False):
                self.filename = filename
                self.isDirectory = is_dir

        class FakeCoverSmb:
            def __init__(self):
                self.files = {
                    ("data", f"/curated/covers/{reused_tag}.jpg"): b"OLD-JPG",
                    ("儿童资源", f"/{adj_folder}/folder.jpg"): b"ADJ-JPG",
                    ("儿童资源", "/动画/手工.jpg"): b"MANUAL-JPG",
                }
                self.dirs = {
                    ("儿童资源", f"/{new_folder}"): [_Entry("Season 1", True)],
                    ("儿童资源", f"/{new_folder}/Season 1"): [
                        _Entry("ep10.mp4", False),
                        _Entry("ep2.mp4", False),
                    ],
                }

            def getAttributes(self, share, path):
                if (share, path) not in self.files:
                    raise OperationFailure("not found", [])
                return _Attrs(len(self.files[(share, path)]))

            def listPath(self, share, path):
                return self.dirs.get((share, path), [])

            def createDirectory(self, share, path):
                pass

            def storeFile(self, share, path, fh, timeout=40):
                self.files[(share, path)] = fh.read()

        smb = FakeCoverSmb()
        cfg = {"share": "儿童资源", "playlistShare": "data", "playlistPath": "curated/tv.json"}
        items = [
            {"title": "手工封面", "file": "动画/其他.mp4", "cover": "儿童资源:动画/手工.jpg"},
            {"title": "旧片重选", "file": reused_key},
            {"title": "带图剧集", "folder": adj_folder},
            {"title": "新剧集", "folder": new_folder},
        ]

        resolved = cover_gen.resolve_existing_covers(smb, cfg, items)
        self.assertEqual([r["status"] for r in resolved], ["existing", "reused", "adjacent", "missing"])
        self.assertEqual(resolved[1]["cover"], f"data:curated/covers/{reused_tag}.jpg")
        self.assertEqual(resolved[2]["cover"], f"{adj_folder}/folder.jpg")

        grabbed_urls = []

        def fake_grabber(url, item, video_rel):
            grabbed_urls.append((url, video_rel))
            return b"NEW-JPG-BYTES"

        out = cover_gen.generate_missing_covers(
            smb, cfg, items, stream_base_url="http://127.0.0.1:8765", grabber=fake_grabber
        )
        self.assertEqual(out["summary"], {
            "total": 4, "reused": 2, "adjacent": 1, "generated": 1, "skipped": 0, "failed": 0
        })
        self.assertEqual(len(grabbed_urls), 1)
        self.assertEqual(grabbed_urls[0][1], f"{new_folder}/Season 1/ep2.mp4")
        self.assertTrue(grabbed_urls[0][0].startswith("http://127.0.0.1:8765/stream/"))
        expected_rel = f"curated/covers/{new_folder}/folder.jpg"
        self.assertEqual(out["results"][3]["cover"], f"data:{expected_rel}")
        self.assertEqual(smb.files[("data", "/" + expected_rel)], b"NEW-JPG-BYTES")

        # 验证单集与影子目录复用：新单集生成为同名 .jpg，再次解析直接走影子目录命中
        single_items = [{"title": "单集", "file": "动画/小猪佩奇/S01E01.mp4"}]
        out_single = cover_gen.generate_missing_covers(
            smb, cfg, single_items, stream_base_url="http://127.0.0.1:8765", grabber=fake_grabber
        )
        self.assertEqual(out_single["results"][0]["cover"], "data:curated/covers/动画/小猪佩奇/S01E01.jpg")
        resolved_again = cover_gen.resolve_existing_covers(smb, cfg, single_items)
        self.assertEqual(resolved_again[0]["status"], "reused")
        self.assertEqual(resolved_again[0]["cover"], "data:curated/covers/动画/小猪佩奇/S01E01.jpg")


class ConfigVersionTest(unittest.TestCase):
    """configVersion 格式与同步回执版本比较。"""

    def test_make_config_version_format(self):
        import re
        v = console.make_config_version()
        self.assertRegex(v, r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$")

    def test_compute_sync_status_reports_config_versions(self):
        nas_text = '{"configVersion":"2026-11-19 22:15:08","sections":[]}'
        nas_hash = console.text_sha256(nas_text)
        ack = {
            "tvHash": nas_hash,
            "learningHash": "",
            "appliedAt": "2026-11-19T22:15:20+08:00",
            "configVersion": "2026-11-19 22:15:08",
            "versionCode": 18,
            "versionName": "2.1",
            "sectionsCount": 0,
        }
        st = console.compute_sync_status(
            nas_hash,
            "",
            ack,
            nas_config_version="2026-11-19 22:15:08",
        )
        self.assertTrue(st["synced"])
        self.assertEqual(st["nasConfigVersion"], "2026-11-19 22:15:08")
        self.assertEqual(st["tvConfigVersion"], "2026-11-19 22:15:08")


if __name__ == "__main__":
    unittest.main(verbosity=2)
