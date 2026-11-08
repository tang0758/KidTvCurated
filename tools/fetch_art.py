#!/usr/bin/env python3
"""下载内置名画缩略图（公共领域作品）到 app/src/main/assets/art/。

图片来自 Wikimedia Commons，通过官方 API 取 960px 缩略图，
只用于电视锁屏上做名画欣赏，全部是作者逝世逾百年的公共领域作品。
源文件清单与出处写到 docs/ART_SOURCES.md。

用法：
    python tools/fetch_art.py                    # 只补缺的文件
    python tools/fetch_art.py --proxy 127.0.0.1:7890
    python tools/fetch_art.py --force           # 重新下载全部

国内网络可能需要代理；不写 --proxy 时会读 HTTPS_PROXY / HTTP_PROXY 环境变量。
仓库里已经带了下载好的图片，正常开发/编译不需要跑这个脚本。
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import sys
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "assets" / "art"
SOURCES = ROOT / "docs" / "ART_SOURCES.md"
API = "https://commons.wikimedia.org/w/api.php"

# (本地文件名, Commons 文件名, 作者 / 说明)
ART = [
    ("starry_night.jpg", "Van Gogh - Starry Night - Google Art Project.jpg", "文森特·梵高《星月夜》1889"),
    ("mona_lisa.jpg", "Mona Lisa.jpg", "列奥纳多·达·芬奇《蒙娜丽莎》约 1503"),
    ("great_wave.jpg", "The Great Wave off Kanagawa.jpg", "葛饰北斋《神奈川冲浪里》约 1831"),
    ("pearl_earring.jpg", "1665 Girl with a Pearl Earring.jpg", "约翰内斯·维米尔《戴珍珠耳环的少女》约 1665"),
    ("sunflowers.jpg", "Vincent van Gogh - Sunflowers - VGM F458.jpg", "文森特·梵高《向日葵》1888"),
    ("water_lilies.jpg", "Claude Monet - Water Lilies - 1906, Ryerson.jpg", "克劳德·莫奈《睡莲》1906"),
    ("the_scream.jpg", "Edvard Munch, 1893, The Scream, oil, tempera and pastel on cardboard, 91 x 73 cm, National Gallery of Norway.jpg", "爱德华·蒙克《呐喊》1893"),
    ("grande_jatte.jpg", "A Sunday on La Grande Jatte, Georges Seurat, 1884.jpg", "乔治·修拉《大碗岛的星期天下午》1884"),
    ("birth_of_venus.jpg", "Sandro Botticelli - La nascita di Venere - Google Art Project - edited.jpg", "桑德罗·波提切利《维纳斯的诞生》约 1485"),
    ("qingming.jpg", "Along the River During the Qingming Festival (detail of original).jpg", "张择端《清明上河图》局部，北宋"),
]


def opener_for(proxy: str | None):
    proxies = {}
    p = proxy or os.environ.get("HTTPS_PROXY") or os.environ.get("HTTP_PROXY")
    if p:
        if not p.startswith("http"):
            p = "http://" + p
        proxies = {"http": p, "https": p}
        print("使用代理:", p)
    return urllib.request.build_opener(urllib.request.ProxyHandler(proxies))


def get_json(op, params: dict) -> dict:
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": "KidTvCurated/1.0 (art assets fetcher)"})
    with op.open(req, timeout=40) as r:
        return json.load(r)


def thumb_urls(op) -> dict[str, tuple[str, str]]:
    titles = ["File:" + t for _, t, _ in ART]
    data = get_json(op, {
        "action": "query", "prop": "imageinfo", "iiprop": "url", "iiurlwidth": "960",
        "format": "json", "titles": "|".join(titles),
    })
    out: dict[str, tuple[str, str]] = {}
    for page in data.get("query", {}).get("pages", {}).values():
        ii = (page.get("imageinfo") or [{}])[0]
        if ii.get("thumburl"):
            out[page["title"].removeprefix("File:")] = (ii["thumburl"], ii.get("descriptionurl", ""))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--proxy", help="例如 127.0.0.1:7890")
    ap.add_argument("--force", action="store_true", help="已存在也重新下载")
    args = ap.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    op = opener_for(args.proxy)
    try:
        urls = thumb_urls(op)
    except Exception as e:
        print("取图片地址失败:", e, file=sys.stderr)
        return 1

    lines = [
        "# 内置名画图片来源",
        "",
        "全部来自 Wikimedia Commons，作者逝世均已超过著作权保护期，属公共领域作品。",
        "图片由 `tools/fetch_art.py` 通过 Commons API 下载 960px 缩略图，",
        "编译进 APK 的 `assets/art/`，电视不联网也能显示。",
        "",
        "| 本地文件 | 作品 | Commons 页面 |",
        "|---|---|---|",
    ]
    ok = 0
    for local, title, desc in ART:
        dst = OUT / local
        info = urls.get(title)
        page = info[1] if info else "https://commons.wikimedia.org/wiki/File:" + urllib.parse.quote(title.replace(" ", "_"))
        lines.append("| `%s` | %s | [链接](%s) |" % (local, desc, page))
        if dst.exists() and not args.force:
            print("跳过（已存在）:", local)
            ok += 1
            continue
        if not info:
            print("没找到缩略图:", title, file=sys.stderr)
            continue
        try:
            req = urllib.request.Request(info[0], headers={"User-Agent": "KidTvCurated/1.0 (art assets fetcher)"})
            with op.open(req, timeout=60) as r:
                blob = r.read()
            if not blob.startswith(b"\xff\xd8") or len(blob) < 5000:
                raise ValueError("不是有效 JPEG 或过小：%d 字节" % len(blob))
            dst.write_bytes(blob)
            print("下载:", local, "%.0f KB" % (len(blob) / 1024))
            ok += 1
        except Exception as e:
            print("下载失败:", local, e, file=sys.stderr)

    SOURCES.parent.mkdir(parents=True, exist_ok=True)
    SOURCES.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("完成：%d/%d 张，来源清单 %s" % (ok, len(ART), SOURCES.relative_to(ROOT)))
    return 0 if ok == len(ART) else 2


if __name__ == "__main__":
    raise SystemExit(main())
