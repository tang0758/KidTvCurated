#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
导出学习角卡片库到 learning.json 与准备多媒体资源（OPT-28A）。
支持将内置的 255 张卡片（唐诗、单词、名画、识字）导出为 JSON 格式。
"""
from __future__ import annotations

import json
import os
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
BUILTIN_DIR = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "family" / "curated" / "data" / "builtin"
OUT_DIR = ROOT / "dist" / "learning"

def parse_cards():
    cards = []

    # Poems.kt: poem(title, author, note, lines...)
    poems_file = BUILTIN_DIR / "Poems.kt"
    if poems_file.exists():
        content = poems_file.read_text(encoding="utf-8")
        matches = re.findall(r'poem\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*([^)]+)\)', content)
        for title, author, note, lines_str in matches:
            lines = [s.strip().strip('"') for s in lines_str.split(',') if s.strip().strip('"')]
            clean_title = re.sub(r'\s+', '_', title.strip().lower())
            cid = f"poem_{clean_title}"
            cards.append({
                "id": cid,
                "type": "poem",
                "title": title,
                "sub": author,
                "lines": lines,
                "note": note,
                "image": f"learning/images/{cid}.jpg",
                "audio": f"learning/audio/{cid}.mp3",
                "ttsText": f"{title}。{author}。{'。'.join(lines)}"
            })

    # Words.kt: word(w, ipa, meaning, en, zh)
    words_file = BUILTIN_DIR / "Words.kt"
    if words_file.exists():
        content = words_file.read_text(encoding="utf-8")
        matches = re.findall(r'word\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)', content)
        for w, ipa, meaning, en, zh in matches:
            clean_w = re.sub(r'\s+', '_', w.strip().lower())
            cid = f"word_{clean_w}"
            cards.append({
                "id": cid,
                "type": "word",
                "title": w,
                "sub": ipa,
                "lines": [en],
                "note": f"{meaning} · {zh}",
                "image": f"learning/images/{cid}.jpg",
                "audio": f"learning/audio/{cid}.mp3",
                "ttsText": f"{w}. {w}. {en}"
            })

    # Arts.kt: art(title, artist, note, asset)
    arts_file = BUILTIN_DIR / "Arts.kt"
    if arts_file.exists():
        content = arts_file.read_text(encoding="utf-8")
        matches = re.findall(r'art\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)', content)
        for title, artist, note, asset in matches:
            clean_t = re.sub(r'\s+', '_', title.strip().lower())
            cid = f"art_{clean_t}"
            cards.append({
                "id": cid,
                "type": "art",
                "title": title,
                "sub": artist,
                "lines": [],
                "note": note,
                "image": f"art/{asset}",
                "audio": f"learning/audio/{cid}.mp3",
                "ttsText": f"{title}。{artist}。{note}"
            })

    # Texts.kt: zi, idiom, saying
    texts_file = BUILTIN_DIR / "Texts.kt"
    if texts_file.exists():
        content = texts_file.read_text(encoding="utf-8")
        for ch, pinyin, words, meaning in re.findall(r'zi\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)', content):
            cid = f"text_{ch}"
            cards.append({
                "id": cid, "type": "text", "title": ch, "sub": f"识字 · {pinyin}",
                "lines": words.split("、"), "note": meaning,
                "image": f"learning/images/{cid}.jpg", "audio": f"learning/audio/{cid}.mp3",
                "ttsText": f"{ch}。{pinyin}。{words}。{meaning}"
            })
        for w, pinyin, story, moral in re.findall(r'idiom\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)', content):
            cid = f"text_{w}"
            cards.append({
                "id": cid, "type": "text", "title": w, "sub": f"成语 · {pinyin}",
                "lines": [story], "note": moral,
                "image": f"learning/images/{cid}.jpg", "audio": f"learning/audio/{cid}.mp3",
                "ttsText": f"{w}。{pinyin}。{story}。{moral}"
            })
        for text, from_s, meaning in re.findall(r'saying\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)', content):
            cid = f"text_{text[:6]}"
            cards.append({
                "id": cid, "type": "text", "title": text, "sub": from_s,
                "lines": [], "note": meaning,
                "image": f"learning/images/{cid}.jpg", "audio": f"learning/audio/{cid}.mp3",
                "ttsText": f"{text}。{from_s}。{meaning}"
            })

    return cards

def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    cards = parse_cards()
    data = {
        "version": 2,
        "intervalSeconds": 20,
        "order": ["poem", "word", "art", "text"],
        "stage": 0,
        "stageSize": 0,
        "cards": cards
    }
    out_file = OUT_DIR / "learning.json"
    out_file.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"成功导出 {len(cards)} 张学习卡片到 {out_file}")

if __name__ == "__main__":
    main()
