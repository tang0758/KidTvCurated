package com.family.curated.data

import com.family.curated.data.CardType.ART
import com.family.curated.data.CardType.POEM
import com.family.curated.data.CardType.TEXT
import com.family.curated.data.CardType.WORD

/** 内置卡片的构造小工具，四类内容共用 LearningCard。 */
internal fun poem(title: String, author: String, note: String, vararg lines: String) =
    LearningCard(POEM, title, author, lines.toList(), note)

internal fun word(w: String, ipa: String, meaning: String, en: String, zh: String) =
    LearningCard(WORD, w, ipa, listOf(en), "$meaning · $zh")

internal fun art(title: String, artist: String, note: String, asset: String) =
    LearningCard(ART, title, artist, emptyList(), note, "asset:$asset")

internal fun zi(ch: String, pinyin: String, words: String, meaning: String) =
    LearningCard(TEXT, ch, "识字 · $pinyin", words.split("、"), meaning)

internal fun idiom(w: String, pinyin: String, story: String, moral: String) =
    LearningCard(TEXT, w, "成语 · $pinyin", listOf(story), moral)

internal fun saying(text: String, from: String, meaning: String) =
    LearningCard(TEXT, text, from, emptyList(), meaning)
