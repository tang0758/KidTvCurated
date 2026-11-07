#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
改「每天可用时间 / 每日时长 / 家长密码」的小工具，直接改 NAS 上的 tv.json。

    python set_schedule.py --show
    python set_schedule.py --weekday 17:30-20:30 --weekend 09:00-11:30,16:00-21:30 --limit 60
    python set_schedule.py --limit 45
    python set_schedule.py --pin 2580
    python set_schedule.py --off          # 关掉时段限制（只保留统计）
    python set_schedule.py --on           # 重新打开

改完在电视上按「菜单」→ 重新读取清单，立刻生效。
账号密码读同目录的 nas.local.json（不进 Git）。
"""
import argparse
import io
import json
import pathlib
import sys

from smb.SMBConnection import SMBConnection

_CFG = pathlib.Path(__file__).with_name("nas.local.json")
if not _CFG.exists():
    _CFG = pathlib.Path(__file__).with_name("nas.example.json")
CFG = json.loads(_CFG.read_text(encoding="utf-8"))

CONFIG_SHARE = CFG.get("playlistShare", "data")
REMOTE_PATH = "/curated/tv.json"

DEFAULT_SCHEDULE = {
    "enabled": True,
    "weekday": [["17:30", "20:30"]],
    "weekend": [["09:00", "11:30"], ["16:00", "21:30"]],
    "dailyLimitMinutes": 60,
    "lockMessage": "电视休息时间，先去玩会儿别的吧",
}


def connect():
    c = SMBConnection(CFG["user"], CFG["password"], "workbuddy", "nas",
                      domain="", use_ntlm_v2=True, is_direct_tcp=True)
    c.connect(CFG["host"], 445, timeout=30)
    return c


def windows(text):
    out = []
    for part in text.split(","):
        part = part.strip()
        if not part:
            continue
        bits = part.replace("~", "-").replace("–", "-").split("-")
        if len(bits) != 2:
            raise ValueError(f"时段格式不对：{part}，应写成 17:30-20:30")
        out.append([bits[0].strip(), bits[1].strip()])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--show", action="store_true", help="只显示当前设置")
    ap.add_argument("--weekday", help="工作日时段，如 17:30-20:30，多个用逗号隔开")
    ap.add_argument("--weekend", help="周末时段，如 09:00-11:30,16:00-21:30")
    ap.add_argument("--limit", type=int, help="每天最多看多少分钟，0 = 不限制")
    ap.add_argument("--pin", help="家长密码（4 位数字）")
    ap.add_argument("--on", action="store_true", help="启用时段限制")
    ap.add_argument("--off", action="store_true", help="关闭时段限制")
    args = ap.parse_args()

    c = connect()
    buf = io.BytesIO()
    c.retrieveFile(CONFIG_SHARE, REMOTE_PATH, buf, timeout=30)
    data = json.loads(buf.getvalue().decode("utf-8"))

    sch = data.get("schedule") or dict(DEFAULT_SCHEDULE)
    if args.weekday is not None:
        sch["weekday"] = windows(args.weekday)
    if args.weekend is not None:
        sch["weekend"] = windows(args.weekend)
    if args.limit is not None:
        sch["dailyLimitMinutes"] = max(0, args.limit)
    if args.pin:
        sch["pin"] = args.pin
        data["pin"] = args.pin
    if args.on:
        sch["enabled"] = True
    if args.off:
        sch["enabled"] = False

    if not args.show:
        data["schedule"] = sch
        payload = json.dumps(data, ensure_ascii=False, indent=2).encode("utf-8")
        c.storeFile(CONFIG_SHARE, REMOTE_PATH, io.BytesIO(payload), timeout=40)
        print("已写回 NAS:", f"\\\\{CFG['host']}\\{CONFIG_SHARE}\\curated\\tv.json")

    print("\n当前设置：")
    print(json.dumps(data.get("schedule", {}), ensure_ascii=False, indent=2))
    print("家长密码：", data.get("pin", "1234"))
    c.close()


if __name__ == "__main__":
    main()
