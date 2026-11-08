#!/usr/bin/env python3
"""内置题库自检：数量、A-Z 覆盖、名画图片引用、重复标题。

    python tools/check_library.py

加完诗 / 单词 / 名画 / 识字卡后跑一次，避免拼错 asset 名或漏字母。
只读源码，不联网、不改文件。
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "family" / "curated" / "data" / "builtin"
ART = ROOT / "app" / "src" / "main" / "assets" / "art"

def first_args(text: str, fn: str) -> list[list[str]]:
    """抓出 fn("a", "b", ...) 的字符串实参，支持跨行调用，忽略 // 注释。"""
    text = "\n".join(line.split("//", 1)[0] for line in text.splitlines())
    out: list[list[str]] = []
    i = 0
    while True:
        m = re.search(r"\b" + fn + r"\s*\(", text[i:])
        if not m:
            break
        start = i + m.end()
        j, depth = start, 1
        while j < len(text) and depth > 0:
            ch = text[j]
            if ch == '"':                       # 跳过字符串内部
                j += 1
                while j < len(text) and text[j] != '"':
                    if text[j] == "\\":
                        j += 1
                    j += 1
            elif ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            j += 1
        body = text[start:j - 1]
        args = re.findall(r'"([^"\\]*(?:\\.[^"\\]*)*)"', body)
        if args:
            out.append(args)
        i = j
    return out


def main() -> int:
    problems: list[str] = []
    counts: dict[str, int] = {}

    poems = first_args((SRC / "Poems.kt").read_text(encoding="utf-8"), "poem")
    words = first_args((SRC / "Words.kt").read_text(encoding="utf-8"), "word")
    arts = first_args((SRC / "Arts.kt").read_text(encoding="utf-8"), "art")
    texts = (SRC / "Texts.kt").read_text(encoding="utf-8")
    zis = first_args(texts, "zi")
    idioms = first_args(texts, "idiom")
    sayings = first_args(texts, "saying")

    counts = {"唐诗": len(poems), "英语单词": len(words), "名画": len(arts),
              "识字卡": len(zis) + len(idioms) + len(sayings)}
    for label, value, minimum in [("唐诗", len(poems), 50), ("英语单词", len(words), 78),
                                  ("名画", len(arts), 50), ("识字卡", counts["识字卡"], 70)]:
        print(("OK  " if value >= minimum else "FAIL"), "%s: %d（要求 ≥ %d）" % (label, value, minimum))
        if value < minimum:
            problems.append("%s 只有 %d，期望 ≥ %d" % (label, value, minimum))

    # A-Z 每个字母恰好 3 个
    letters: dict[str, list[str]] = {}
    for w in words:
        letters.setdefault(w[0][:1].upper(), []).append(w[0])
    wrong = [(c, letters.get(c, [])) for c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
             if len(letters.get(c, [])) != 3]
    if wrong:
        for c, ws in wrong:
            problems.append("字母 %s 有 %d 个单词：%s" % (c, len(ws), ", ".join(ws)))
        print("FAIL 字母覆盖:", ", ".join("%s=%d" % (c, len(ws)) for c, ws in wrong))
    else:
        print("OK   A-Z 26 × 3 = 78 个单词，无缺字母")

    # 名画 asset 必须与真实文件一一对应
    assets = [a[-1] for a in arts]
    for name in assets:
        if not (ART / name).exists():
            problems.append("名画图片缺失: " + name)
    unused = sorted(set(p.name for p in ART.iterdir()) - set(assets))
    print(("OK  " if not unused else "WARN"), "50 幅名画图片引用:",
          "%d 个 asset，%d 个本地文件" % (len(assets), len(list(ART.iterdir()))),
          ("；未引用: " + ", ".join(unused)) if unused else "")

    # 重复标题
    for label, rows in [("诗", [p[0] for p in poems]), ("单词", [w[0] for w in words]),
                        ("名画", [a[0] for a in arts]),
                        ("识字", [z[0] for z in zis] + [i[0] for i in idioms] + [s[0] for s in sayings])]:
        dup = sorted({t for t in rows if rows.count(t) > 1})
        if dup:
            problems.append("%s标题重复: %s" % (label, ", ".join(dup)))
            print("FAIL %s标题重复: %s" % (label, ", ".join(dup)))
    if not any("重复" in p for p in problems):
        print("OK   无重复标题")

    print("\n合计 %d 张内置卡片" % sum(counts.values()))
    if problems:
        print("\n".join("  - " + p for p in problems))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
