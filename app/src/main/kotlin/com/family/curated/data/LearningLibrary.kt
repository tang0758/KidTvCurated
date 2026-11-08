package com.family.curated.data

import com.family.curated.data.CardType.ART
import com.family.curated.data.CardType.POEM
import com.family.curated.data.CardType.TEXT
import com.family.curated.data.CardType.WORD

/**
 * 内置离线题库：不联网、不依赖 NAS，装上 APK 锁屏就能看。
 * 家长可以在 tv.json 的 learning.custom 里追加自己的卡片，
 * 或把 useBuiltin 设成 false 只用自定义内容。
 *
 * 名画图片放在 assets/art/ 下（公共领域作品，来源见同目录 SOURCES.md）。
 */
object LearningLibrary {

    private fun poem(title: String, author: String, note: String, vararg lines: String) =
        LearningCard(POEM, title, author, lines.toList(), note)

    private fun word(w: String, ipa: String, meaning: String, en: String, zh: String) =
        LearningCard(WORD, w, ipa, listOf(en), "$meaning · $zh")

    private fun art(title: String, artist: String, note: String, asset: String) =
        LearningCard(ART, title, artist, emptyList(), note, "asset:$asset")

    private fun zi(ch: String, pinyin: String, words: String, meaning: String) =
        LearningCard(TEXT, ch, "识字 · $pinyin", words.split("、"), meaning)

    private fun idiom(word: String, pinyin: String, meaning: String) =
        LearningCard(TEXT, word, "成语 · $pinyin", listOf(meaning.split("：")[0]), meaning)

    private fun saying(text: String, from: String, meaning: String) =
        LearningCard(TEXT, text, from, emptyList(), meaning)

    val poems = listOf(
        poem("静夜思", "唐 · 李白", "看见地上的月光，就想起了远方的家。",
            "床前明月光，疑是地上霜。", "举头望明月，低头思故乡。"),
        poem("春晓", "唐 · 孟浩然", "春天睡得香，醒来听见满院子的鸟叫。",
            "春眠不觉晓，处处闻啼鸟。", "夜来风雨声，花落知多少。"),
        poem("咏鹅", "唐 · 骆宾王", "写这首诗的时候，作者才七岁。",
            "鹅，鹅，鹅，曲项向天歌。", "白毛浮绿水，红掌拨清波。"),
        poem("登鹳雀楼", "唐 · 王之涣", "想看得更远，就要再上一层楼。",
            "白日依山尽，黄河入海流。", "欲穷千里目，更上一层楼。"),
        poem("悯农", "唐 · 李绅", "碗里的每一粒米，都来得不容易。",
            "锄禾日当午，汗滴禾下土。", "谁知盘中餐，粒粒皆辛苦。"),
        poem("绝句", "唐 · 杜甫", "四句诗里藏着四种颜色、四样景物。",
            "两个黄鹂鸣翠柳，一行白鹭上青天。", "窗含西岭千秋雪，门泊东吴万里船。"),
        poem("江雪", "唐 · 柳宗元", "天寒地冻，只有一位老爷爷还在钓鱼。",
            "千山鸟飞绝，万径人踪灭。", "孤舟蓑笠翁，独钓寒江雪。"),
        poem("回乡偶书", "唐 · 贺知章", "离家太久，村里的小朋友都不认识他了。",
            "少小离家老大回，乡音无改鬓毛衰。", "儿童相见不相识，笑问客从何处来。"),
        poem("望庐山瀑布", "唐 · 李白", "把瀑布想成了从天上掉下来的银河。",
            "日照香炉生紫烟，遥看瀑布挂前川。", "飞流直下三千尺，疑是银河落九天。"),
        poem("早发白帝城", "唐 · 李白", "船开得飞快，心情也跟着轻快起来。",
            "朝辞白帝彩云间，千里江陵一日还。", "两岸猿声啼不住，轻舟已过万重山。"),
        poem("小池", "宋 · 杨万里", "刚冒尖的小荷叶上，已经停了一只蜻蜓。",
            "泉眼无声惜细流，树阴照水爱晴柔。", "小荷才露尖尖角，早有蜻蜓立上头。"),
        poem("村居", "清 · 高鼎", "放学早，正好趁着东风去放风筝。",
            "草长莺飞二月天，拂堤杨柳醉春烟。", "儿童散学归来早，忙趁东风放纸鸢。"),
        poem("赠汪伦", "唐 · 李白", "桃花潭水再深，也比不上朋友送别的情意。",
            "李白乘舟将欲行，忽闻岸上踏歌声。", "桃花潭水深千尺，不及汪伦送我情。"),
        poem("古朗月行 · 节选", "唐 · 李白", "小时候不认识月亮，把它叫成白玉盘子。",
            "小时不识月，呼作白玉盘。", "又疑瑶台镜，飞在青云端。"),
        poem("九月九日忆山东兄弟", "唐 · 王维", "过节的时候，格外想念家里的亲人。",
            "独在异乡为异客，每逢佳节倍思亲。", "遥知兄弟登高处，遍插茱萸少一人。")
    )

    val words = listOf(
        word("apple", "/ˈæpl/  n.", "苹果", "I eat an apple.", "我吃一个苹果。"),
        word("ball", "/bɔːl/  n.", "球", "Throw the ball to me.", "把球扔给我。"),
        word("cat", "/kæt/  n.", "猫", "The cat is sleeping.", "猫在睡觉。"),
        word("dog", "/dɒɡ/  n.", "狗", "My dog can run fast.", "我的狗跑得很快。"),
        word("elephant", "/ˈelɪfənt/  n.", "大象", "An elephant has a long nose.", "大象有长鼻子。"),
        word("fish", "/fɪʃ/  n.", "鱼", "Fish swim in the water.", "鱼在水里游。"),
        word("green", "/ɡriːn/  adj.", "绿色", "The leaves are green.", "树叶是绿色的。"),
        word("happy", "/ˈhæpi/  adj.", "开心", "I am happy today.", "我今天很开心。"),
        word("ice", "/aɪs/  n.", "冰", "Ice is very cold.", "冰很凉。"),
        word("jump", "/dʒʌmp/  v.", "跳", "Can you jump high?", "你能跳得高吗？"),
        word("kite", "/kaɪt/  n.", "风筝", "Let's fly a kite.", "我们去放风筝吧。"),
        word("lion", "/ˈlaɪən/  n.", "狮子", "The lion is big and strong.", "狮子又大又壮。"),
        word("moon", "/muːn/  n.", "月亮", "Look at the bright moon.", "看那轮明亮的月亮。"),
        word("nest", "/nest/  n.", "鸟窝", "A bird is in the nest.", "小鸟在窝里。"),
        word("orange", "/ˈɒrɪndʒ/  n.", "橙子", "This orange is sweet.", "这个橙子很甜。"),
        word("panda", "/ˈpændə/  n.", "熊猫", "The panda eats bamboo.", "熊猫吃竹子。"),
        word("queen", "/kwiːn/  n.", "女王", "The queen has a crown.", "女王戴着王冠。"),
        word("rabbit", "/ˈræbɪt/  n.", "兔子", "The rabbit hops away.", "兔子蹦蹦跳跳地跑了。"),
        word("sun", "/sʌn/  n.", "太阳", "The sun is shining.", "太阳出来了。"),
        word("tiger", "/ˈtaɪɡə/  n.", "老虎", "A tiger has stripes.", "老虎身上有条纹。"),
        word("umbrella", "/ʌmˈbrelə/  n.", "雨伞", "Take your umbrella.", "带上你的雨伞。"),
        word("water", "/ˈwɔːtə/  n.", "水", "Drink some water.", "喝点水吧。"),
        word("yellow", "/ˈjeləʊ/  adj.", "黄色", "The duck is yellow.", "小鸭子是黄色的。"),
        word("zebra", "/ˈzebrə/  n.", "斑马", "A zebra is black and white.", "斑马是黑白色的。")
    )

    val arts = listOf(
        art("星月夜", "梵高 · 1889 · 荷兰", "看点：天空被画成了一圈圈打转的星星，好像风在吹。", "starry_night.jpg"),
        art("蒙娜丽莎", "达·芬奇 · 约1503 · 意大利", "看点：不管站在哪边看，她的眼睛好像都望着你。", "mona_lisa.jpg"),
        art("神奈川冲浪里", "葛饰北斋 · 约1831 · 日本", "看点：大浪像爪子一样伸出来，后面的富士山却很安静。", "great_wave.jpg"),
        art("戴珍珠耳环的少女", "维米尔 · 约1665 · 荷兰", "看点：深黑的背景里，只有一颗珍珠在发光。", "pearl_earring.jpg"),
        art("向日葵", "梵高 · 1888 · 荷兰", "看点：同一束花里，画出了好多种不一样的黄。", "sunflowers.jpg"),
        art("睡莲", "莫奈 · 1906 · 法国", "看点：不画天空，只画水面上倒映的天空。", "water_lilies.jpg"),
        art("呐喊", "蒙克 · 1893 · 挪威", "看点：连天空和桥都跟着一起扭动，画出了「声音」。", "the_scream.jpg"),
        art("大碗岛的星期天下午", "修拉 · 1884 · 法国", "看点：整幅画是无数个小色点拼出来的，凑近看更有趣。", "grande_jatte.jpg"),
        art("维纳斯的诞生", "波提切利 · 约1485 · 意大利", "看点：花瓣随风飘，人物站在一枚大贝壳上。", "birth_of_venus.jpg"),
        art("清明上河图 · 局部", "张择端 · 北宋 · 中国", "看点：几百个人、几十条船，像一张古代城市的大照片。", "qingming.jpg")
    )

    val texts = listOf(
        zi("日", "rì", "日出、生日、日记", "太阳；一天也叫一日"),
        zi("月", "yuè", "月亮、月饼、六月", "天上的月亮；一年有十二个月"),
        zi("水", "shuǐ", "喝水、水花、水果", "流动的水，三点水旁的字多和水有关"),
        zi("火", "huǒ", "火车、火花、大火", "火苗是向上跳的"),
        zi("山", "shān", "高山、山羊、爬山", "三个山峰连起来就是「山」"),
        zi("人", "rén", "大人、人们、好人", "一撇一捺，像人迈开的两条腿"),
        zi("木", "mù", "树木、木头、木马", "一棵树；两个木是林，三个木是森"),
        zi("明", "míng", "明天、明亮、明白", "日 + 月 = 明，又亮又清楚"),
        zi("大", "dà", "大象、长大、大家", "张开手臂，表示很大"),
        zi("小", "xiǎo", "小狗、大小、小心", "中间一竖，两边两个小点"),
        zi("上", "shàng", "上面、上学、早上", "一横往上加一笔"),
        zi("下", "xià", "下面、下雨、楼下", "一横往下加一笔"),
        zi("中", "zhōng", "中间、中午、中国", "一根线穿过方框的正中间"),
        zi("田", "tián", "田地、水田、农田", "方方的田块，中间是田埂"),
        zi("好", "hǎo", "你好、好吃、好看", "女 + 子 = 好"),
        zi("雨", "yǔ", "下雨、雨伞、雨点", "四点是落下来的雨滴"),
        idiom("守株待兔", "shǒu zhū dài tù", "成语：守着树桩等兔子，比喻不肯努力只想碰运气"),
        idiom("画蛇添足", "huà shé tiān zú", "成语：蛇本来没有脚，多画了反而弄糟了"),
        idiom("亡羊补牢", "wáng yáng bǔ láo", "成语：丢了羊赶紧修羊圈，出问题后马上补救还不晚"),
        idiom("井底之蛙", "jǐng dǐ zhī wā", "成语：井里的青蛙只看见一小块天，要多出去看看"),
        idiom("掩耳盗铃", "yǎn ěr dào líng", "成语：捂住自己耳朵偷铃铛，骗不了别人"),
        idiom("拔苗助长", "bá miáo zhù zhǎng", "成语：把苗拔高反而枯了，做事不能太着急"),
        saying("人之初，性本善", "《三字经》", "每个人刚出生的时候，心地都是善良的"),
        saying("玉不琢，不成器", "《三字经》", "玉石不雕琢成不了器物，小朋友也要慢慢学习"),
        saying("一寸光阴一寸金", "古谚语", "时间像金子一样宝贵，用完就回不来了"),
        saying("读书破万卷", "唐 · 杜甫", "书读得多了，写起来就顺了")
    )

    val all: List<LearningCard> = poems + words + arts + texts

    fun countByType(): Map<CardType, Int> = CardType.values().associateWith { t -> all.count { it.type == t } }
}
