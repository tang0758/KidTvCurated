#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
扫描绿联 NAS（UGOS Pro），生成并上传 tv.json 片单。

    python make_playlist.py            # 只生成本地 tv.json
    python make_playlist.py --upload   # 生成后上传到 NAS 的 data/curated/tv.json

账号密码放在同目录的 nas.local.json（不进 Git，可参考 nas.example.json）。
重新生成时会自动保留 NAS 上已有的 schedule / pin / passRatio 设置，
所以「可用时段」「家长密码」不会因为重扫而丢失。

想改「每日必看」：直接改 NAS 上 tv.json 里 key=daily 的 items；
改完在电视上按菜单键 →「重新读取清单」。
"""
import io
import json
import os
import pathlib
import sys
from smb.SMBConnection import SMBConnection

_CFG_FILE = pathlib.Path(__file__).with_name("nas.local.json")
if not _CFG_FILE.exists():
    _CFG_FILE = pathlib.Path(__file__).with_name("nas.example.json")
CFG = json.loads(_CFG_FILE.read_text(encoding="utf-8"))

HOST = CFG["host"]
USER = CFG["user"]
PASSWORD = CFG["password"]
SHARE = CFG["share"]
CONFIG_SHARE = "data"       # 配置文件放在这个共享（可写）
REMOTE_PATH = "/curated/tv.json"
OUT = "tv.json"

KIDS = "/儿童视频/99.娱乐动画"
LEARN = "/儿童视频/01.学习类视频"
DOCS = "/儿童视频/02.纪录片"
DAILY_SAMPLE_FOLDER = LEARN + "/ABC Book MP4"
DAILY_COUNT = 2

VIDEO_EXT = {".mp4", ".mkv", ".avi", ".ts", ".m2ts", ".mov", ".wmv",
             ".flv", ".webm", ".mpg", ".mpeg", ".rmvb", ".iso", ".m4v"}

# 这些是重复副本或资源包，不适合直接当剧集卡片
SKIP = {"Numberblocks 数字积木1-8季（动画+音频+台词本+PPT课件）"}


def connect():
    c = SMBConnection(USER, PASSWORD, "workbuddy", "nas",
                      domain="", use_ntlm_v2=True, is_direct_tcp=True)
    c.connect(HOST, 445, timeout=30)
    return c


def ls(c, path):
    try:
        return [f for f in c.listPath(SHARE, path, timeout=25)
                if f.filename not in (".", "..")]
    except Exception as e:
        print(f"   跳过 {path}: {str(e)[:80]}")
        return []


def is_video(name):
    return os.path.splitext(name)[1].lower() in VIDEO_EXT and not name.startswith(".")


def count_all(c, path, depth=0):
    """递归数视频，多层季目录也要算到"""
    if depth > 3:
        return 0
    n = 0
    for f in ls(c, path):
        if f.isDirectory and not f.filename.startswith("."):
            n += count_all(c, path + "/" + f.filename, depth + 1)
        elif is_video(f.filename):
            n += 1
    return n


def pretty(name):
    return name.split(" 20")[0].strip()


def folder_sections(c, base, title, key):
    items = []
    for f in sorted(ls(c, base), key=lambda x: x.filename):
        if not f.isDirectory or f.filename.startswith(".") or f.filename in SKIP:
            continue
        p = base + "/" + f.filename
        n = count_all(c, p)
        if n == 0:
            continue
        items.append({
            "title": pretty(f.filename),
            "folder": p.lstrip("/"),
            "subtitle": f"{n} 集"
        })
        print(f"   {f.filename}: {n} 集")
    return {"key": key, "title": title, "requires": "daily", "items": items}


def existing_config(c):
    """读回 NAS 上现有的清单，只取出需要保留的设置项"""
    try:
        buf = io.BytesIO()
        c.retrieveFile(CONFIG_SHARE, REMOTE_PATH, buf, timeout=30)
        return json.loads(buf.getvalue().decode("utf-8"))
    except Exception:
        return {}


def build(c, old=None):
    old = old or {}
    sections = []

    # 每日必看：给两条真实路径做样板，之后你自己改
    daily = []
    samples = [f.filename for f in sorted(ls(c, DAILY_SAMPLE_FOLDER),
                                          key=lambda x: x.filename)
               if is_video(f.filename)][:DAILY_COUNT]
    for name in samples:
        daily.append({
            "title": os.path.splitext(name)[0].replace("_", " "),
            "file": (DAILY_SAMPLE_FOLDER + "/" + name).lstrip("/")
        })
    print(f"每日必看样板: {len(daily)} 条")
    sections.append({
        "key": "daily",
        "title": "今天要看的",
        "gate": True,
        "_说明": "想要每天固定看哪几集，就在 items 里写 file 路径；也可删掉 items 改成 auto 自动轮换",
        "items": daily
    })

    print("动画片:")
    sections.append(folder_sections(c, KIDS, "动画片", "cartoons"))
    print("纪录片:")
    sections.append(folder_sections(c, DOCS, "纪录片", "docs"))
    print("学习:")
    sections.append(folder_sections(c, LEARN, "学习", "learning"))

    # 重建 sections 时，把家长后来配的封面按路径继承下来，别让缩略图一夜回到灰卡
    covers = {}
    for sec in old.get("sections", []):
        for it in sec.get("items", []):
            key = it.get("file") or it.get("folder")
            if key and it.get("cover"):
                covers[key] = it["cover"]
    inherited = 0
    for sec in sections:
        for it in sec.get("items", []):
            key = it.get("file") or it.get("folder")
            if key in covers:
                it["cover"] = covers[key]
                inherited += 1
    if inherited:
        print(f"沿用已有封面: {inherited} 张")

    data = {
        "nas": {"host": HOST, "user": USER, "password": PASSWORD, "share": SHARE},
        "playlistShare": CONFIG_SHARE,
        "passRatio": old.get("passRatio", 0.92),
        "pin": old.get("pin", "1234"),
        "_说明": "file/folder 路径相对「儿童资源」共享根目录。gate=true 的分区全部看完，才解锁 requires=daily 的分区。",
        "sections": sections
    }
    if "schedule" in old:
        data["schedule"] = old["schedule"]
    if "learning" in old:
        data["learning"] = old["learning"]
    return data


def main():
    c = connect()
    print("已连接 NAS\n")
    data = build(c, existing_config(c))
    text = json.dumps(data, ensure_ascii=False, indent=2)
    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write(text)
    total = sum(len(s["items"]) for s in data["sections"])
    print(f"\n已生成 {OUT}：{len(data['sections'])} 个分区，{total} 个条目，{len(text)} 字节")

    if "--upload" in sys.argv:
        try:
            c.createDirectory(CONFIG_SHARE, "/curated")
        except Exception:
            pass  # 已存在
        with open(OUT, "rb") as fh:
            c.storeFile(CONFIG_SHARE, REMOTE_PATH, fh, timeout=40)
        print(f"已上传到 NAS: \\\\{HOST}\\{CONFIG_SHARE}\\curated\\tv.json")
        import io
        buf = io.BytesIO()
        c.retrieveFile(CONFIG_SHARE, REMOTE_PATH, buf, timeout=40)
        got = buf.getvalue().decode("utf-8")
        print(f"回读校验: {len(got)} 字符, 分区 {len(json.loads(got)['sections'])} 个")
    c.close()


if __name__ == "__main__":
    main()
