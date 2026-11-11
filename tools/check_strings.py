#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""界面文案守卫：统计 Kotlin 里还剩多少「中文硬编码」，超过基线就报错。

为什么需要它：OPT-16 把设置页 / 锁屏页 / 家长工具的文案搬进了 `res/values/strings.xml`，
但剩下几屏还没搬。没有守卫的话，下一个功能随手写一句中文 Toast，就又长回去了 ——
这类回潮不会有任何报错，只会让「文案集中」慢慢失效。

用法：
    python tools/check_strings.py            # 检查，超预算返回 1
    python tools/check_strings.py --update   # 认账：把当前值写成新基线
    python tools/check_strings.py --list     # 列出每一处的文件名与行号

计数口径：
- 只数 Kotlin 的字符串字面量里含中文的，**不算注释**；
- `Log.*` 的实参不算 —— 日志本来就该是中文，它不上界面；
- `data/builtin/` 整目录不算 —— 唐诗 / 课文 / 单词 / 名画是**教学数据**，不是界面文案，
  塞进 strings.xml 会让资源文件变成第二个数据源。
"""
import argparse
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'app', 'src', 'main', 'kotlin', 'com', 'family', 'curated')
BASELINE = os.path.join(ROOT, 'tools', 'string_budget.json')
XML = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'values', 'strings.xml')

CJK = re.compile(r'[\u4e00-\u9fff]')
LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
LOG_CALL = re.compile(r'\bLog\.[vdiew]\s*\(')

# 整目录豁免：教学数据
SKIP_DIRS = ('builtin',)


def strip_block_comments(text):
    """把块注释替换成等量空白，保持行号不变（行号要用来报告）"""
    def repl(m):
        return ''.join(ch if ch == '\n' else ' ' for ch in m.group(0))
    return re.sub(r'/\*.*?\*/', repl, text, flags=re.S)


def scan(path):
    """返回 [(行号, 字面量)]"""
    text = strip_block_comments(io.open(path, encoding='utf-8').read())
    hits = []
    for no, line in enumerate(text.split('\n'), 1):
        code = line.split('//')[0]
        log_at = LOG_CALL.search(code)
        for m in LITERAL.finditer(code):
            if not CJK.search(m.group(1)):
                continue
            if log_at and m.start() > log_at.end():
                continue  # Log.w(TAG, "……") 允许中文
            hits.append((no, m.group(1)))
    return hits


def collect():
    result = {}
    for dirpath, dirnames, filenames in os.walk(SRC):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in sorted(filenames):
            if not name.endswith('.kt'):
                continue
            path = os.path.join(dirpath, name)
            hits = scan(path)
            if hits:
                rel = os.path.relpath(path, ROOT).replace('\\', '/')
                result[rel] = hits
    return result


def count_xml_strings():
    if not os.path.exists(XML):
        return 0
    return len(re.findall(r'<string name=', io.open(XML, encoding='utf-8').read()))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--update', action='store_true', help='把当前统计写成新基线')
    ap.add_argument('--list', action='store_true', help='列出每一处的位置')
    args = ap.parse_args()

    found = collect()
    now = {k: len(v) for k, v in found.items()}

    if args.list:
        for rel in sorted(found, key=lambda k: -len(found[k])):
            print('%4d  %s' % (len(found[rel]), rel))
            for no, text in found[rel]:
                print('        %s:%d  %s' % (rel.split('/')[-1], no, text[:90]))
        return 0

    total = sum(now.values())
    print('界面文案：strings.xml 有 %d 条，Kotlin 里还剩 %d 处中文硬编码（%d 个文件）'
          % (count_xml_strings(), total, len(now)))

    if args.update:
        payload = {
            '_comment': '界面文案预算：每个 Kotlin 文件允许残留的中文硬编码条数。只许降，不许升。'
                        'tools/check_strings.py --update 可认账。',
            'total': total,
            'files': dict(sorted(now.items())),
        }
        io.open(BASELINE, 'w', encoding='utf-8').write(
            json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False) + '\n')
        print('已写入基线 %s' % os.path.relpath(BASELINE, ROOT))
        return 0

    if not os.path.exists(BASELINE):
        print('缺少基线文件 %s，先跑 --update' % os.path.relpath(BASELINE, ROOT))
        return 1

    base = json.loads(io.open(BASELINE, encoding='utf-8').read())
    budget = base.get('files', {})
    bad = []
    for rel, n in sorted(now.items()):
        allow = budget.get(rel, 0)
        if n > allow:
            bad.append('  %-58s %d > 基线 %d' % (rel, n, allow))
    stale = [k for k in budget if k not in now]

    if stale:
        print('基线里有已不存在的文件（可以 --update 清理）：')
        for k in stale:
            print('  ' + k)

    if bad:
        print('\n新增了中文硬编码（界面文字请放进 res/values/strings.xml，用 getString）：')
        print('\n'.join(bad))
        print('\n如果确实是日志 / 数据 / 正则，改写代码或跑 --update 认账。')
        return 1

    saved = base.get('total', 0) - total
    if saved > 0:
        print('比基线少了 %d 处，记得 --update 把水位降下来。' % saved)
    print('通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())
