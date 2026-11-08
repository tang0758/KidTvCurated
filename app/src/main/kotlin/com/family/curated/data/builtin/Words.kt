package com.family.curated.data

/**
 * A—Z 每个字母 3 个单词，共 78 个，全部是小朋友身边能看到的东西。
 * 顺序按字母分组，配合 Learning.deck() 的穿插算法不会连着蹦一串同字母的词。
 */
object BuiltinWords {
    val all = listOf(
        // A
        word("apple", "/ˈæpl/  n.", "苹果", "I eat an apple.", "我吃一个苹果。"),
        word("ant", "/ænt/  n.", "蚂蚁", "An ant is very small.", "蚂蚁非常小。"),
        word("arm", "/ɑːm/  n.", "手臂", "Wave your arm.", "挥挥你的手臂。"),
        // B
        word("ball", "/bɔːl/  n.", "球", "Throw the ball to me.", "把球扔给我。"),
        word("bear", "/beə/  n.", "熊", "The bear is brown.", "这只熊是棕色的。"),
        word("book", "/bʊk/  n.", "书", "This book is fun.", "这本书很有趣。"),
        // C
        word("cat", "/kæt/  n.", "猫", "The cat is sleeping.", "猫在睡觉。"),
        word("cake", "/keɪk/  n.", "蛋糕", "Happy birthday, here is a cake.", "生日快乐，这是蛋糕。"),
        word("car", "/kɑː/  n.", "小汽车", "The car is red.", "这辆车是红色的。"),
        // D
        word("dog", "/dɒɡ/  n.", "狗", "My dog can run fast.", "我的狗跑得很快。"),
        word("duck", "/dʌk/  n.", "鸭子", "A duck can swim.", "鸭子会游泳。"),
        word("door", "/dɔː/  n.", "门", "Please open the door.", "请把门打开。"),
        // E
        word("egg", "/eɡ/  n.", "鸡蛋", "I have an egg for breakfast.", "我早餐吃一个鸡蛋。"),
        word("elephant", "/ˈelɪfənt/  n.", "大象", "An elephant has a long nose.", "大象有长鼻子。"),
        word("eye", "/aɪ/  n.", "眼睛", "Close your eyes.", "闭上眼睛。"),
        // F
        word("fish", "/fɪʃ/  n.", "鱼", "Fish swim in the water.", "鱼在水里游。"),
        word("flower", "/ˈflaʊə/  n.", "花", "The flower smells good.", "这朵花很香。"),
        word("foot", "/fʊt/  n.", "脚", "My foot is small.", "我的脚小小的。"),
        // G
        word("green", "/ɡriːn/  adj.", "绿色", "The leaves are green.", "树叶是绿色的。"),
        word("grape", "/ɡreɪp/  n.", "葡萄", "Grapes are sweet.", "葡萄很甜。"),
        word("giraffe", "/dʒəˈrɑːf/  n.", "长颈鹿", "A giraffe has a long neck.", "长颈鹿脖子很长。"),
        // H
        word("happy", "/ˈhæpi/  adj.", "开心", "I am happy today.", "我今天很开心。"),
        word("hat", "/hæt/  n.", "帽子", "Put on your hat.", "戴上你的帽子。"),
        word("hand", "/hænd/  n.", "手", "Wash your hands.", "洗洗你的手。"),
        // I
        word("ice", "/aɪs/  n.", "冰", "Ice is very cold.", "冰很凉。"),
        word("insect", "/ˈɪnsekt/  n.", "昆虫", "A bee is an insect.", "蜜蜂是一种昆虫。"),
        word("ink", "/ɪŋk/  n.", "墨水", "The ink is blue.", "墨水是蓝色的。"),
        // J
        word("jump", "/dʒʌmp/  v.", "跳", "Can you jump high?", "你能跳得高吗？"),
        word("juice", "/dʒuːs/  n.", "果汁", "I like apple juice.", "我喜欢苹果汁。"),
        word("jacket", "/ˈdʒækɪt/  n.", "外套", "It is cold, wear a jacket.", "天冷，穿件外套。"),
        // K
        word("kite", "/kaɪt/  n.", "风筝", "Let's fly a kite.", "我们去放风筝吧。"),
        word("key", "/kiː/  n.", "钥匙", "Here is the key.", "钥匙在这儿。"),
        word("king", "/kɪŋ/  n.", "国王", "The king has a crown.", "国王戴着王冠。"),
        // L
        word("lion", "/ˈlaɪən/  n.", "狮子", "The lion is big and strong.", "狮子又大又壮。"),
        word("leaf", "/liːf/  n.", "叶子", "A leaf falls down.", "一片叶子落下来。"),
        word("lamp", "/læmp/  n.", "台灯", "Turn on the lamp.", "把台灯打开。"),
        // M
        word("moon", "/muːn/  n.", "月亮", "Look at the bright moon.", "看那轮明亮的月亮。"),
        word("milk", "/mɪlk/  n.", "牛奶", "Drink a cup of milk.", "喝一杯牛奶。"),
        word("monkey", "/ˈmʌŋki/  n.", "猴子", "The monkey climbs a tree.", "猴子在爬树。"),
        // N
        word("nest", "/nest/  n.", "鸟窝", "A bird is in the nest.", "小鸟在窝里。"),
        word("nose", "/nəʊz/  n.", "鼻子", "Touch your nose.", "摸摸你的鼻子。"),
        word("night", "/naɪt/  n.", "夜晚", "Good night, sleep well.", "晚安，好好睡。"),
        // O
        word("orange", "/ˈɒrɪndʒ/  n.", "橙子", "This orange is sweet.", "这个橙子很甜。"),
        word("owl", "/aʊl/  n.", "猫头鹰", "An owl stays up at night.", "猫头鹰晚上不睡觉。"),
        word("ocean", "/ˈəʊʃn/  n.", "大海", "The ocean is very big.", "大海非常大。"),
        // P
        word("panda", "/ˈpændə/  n.", "熊猫", "The panda eats bamboo.", "熊猫吃竹子。"),
        word("pen", "/pen/  n.", "笔", "I write with a pen.", "我用笔写字。"),
        word("pig", "/pɪɡ/  n.", "小猪", "The pig is pink.", "小猪是粉色的。"),
        // Q
        word("queen", "/kwiːn/  n.", "女王", "The queen has a crown.", "女王戴着王冠。"),
        word("quilt", "/kwɪlt/  n.", "被子", "The quilt is warm.", "被子很暖和。"),
        word("question", "/ˈkwestʃən/  n.", "问题", "May I ask a question?", "我可以问个问题吗？"),
        // R
        word("rabbit", "/ˈræbɪt/  n.", "兔子", "The rabbit hops away.", "兔子蹦蹦跳跳地跑了。"),
        word("rain", "/reɪn/  n.", "雨", "The rain is falling.", "下雨了。"),
        word("robot", "/ˈrəʊbɒt/  n.", "机器人", "My robot can walk.", "我的机器人会走路。"),
        // S
        word("sun", "/sʌn/  n.", "太阳", "The sun is shining.", "太阳出来了。"),
        word("star", "/stɑː/  n.", "星星", "I see a little star.", "我看见一颗小星星。"),
        word("shoe", "/ʃuː/  n.", "鞋子", "Where is my other shoe?", "我另一只鞋在哪儿？"),
        // T
        word("tiger", "/ˈtaɪɡə/  n.", "老虎", "A tiger has stripes.", "老虎身上有条纹。"),
        word("tree", "/triː/  n.", "树", "The tree is tall.", "这棵树很高。"),
        word("train", "/treɪn/  n.", "火车", "The train goes fast.", "火车开得很快。"),
        // U
        word("umbrella", "/ʌmˈbrelə/  n.", "雨伞", "Take your umbrella.", "带上你的雨伞。"),
        word("under", "/ˈʌndə/  prep.", "在……下面", "The cat is under the table.", "猫在桌子下面。"),
        word("uncle", "/ˈʌŋkl/  n.", "叔叔", "My uncle is tall.", "我叔叔个子很高。"),
        // V
        word("van", "/væn/  n.", "面包车", "A van can carry many things.", "面包车能装很多东西。"),
        word("violin", "/ˌvaɪəˈlɪn/  n.", "小提琴", "She plays the violin.", "她拉小提琴。"),
        word("vegetable", "/ˈvedʒtəbl/  n.", "蔬菜", "Eat more vegetables.", "多吃点蔬菜。"),
        // W
        word("water", "/ˈwɔːtə/  n.", "水", "Drink some water.", "喝点水吧。"),
        word("wind", "/wɪnd/  n.", "风", "The wind is strong today.", "今天风很大。"),
        word("window", "/ˈwɪndəʊ/  n.", "窗户", "Look out of the window.", "看看窗外。"),
        // X（X 开头的常用词很少，第三个是圣诞节的常见简写）
        word("xylophone", "/ˈzaɪləfəʊn/  n.", "木琴", "I play the xylophone.", "我敲木琴。"),
        word("x-ray", "/ˈeks reɪ/  n.", "X 光片", "The doctor looks at an x-ray.", "医生在看 X 光片。"),
        word("Xmas", "/ˈkrɪsməs/  n.", "圣诞节（简写）", "Xmas is short for Christmas.", "Xmas 是圣诞节的简写。"),
        // Y
        word("yellow", "/ˈjeləʊ/  adj.", "黄色", "The duck is yellow.", "小鸭子是黄色的。"),
        word("yoyo", "/ˈjəʊjəʊ/  n.", "溜溜球", "My yoyo goes up and down.", "我的溜溜球上上下下。"),
        word("yogurt", "/ˈjɒɡət/  n.", "酸奶", "Yogurt is cool and sweet.", "酸奶又凉又甜。"),
        // Z
        word("zebra", "/ˈzebrə/  n.", "斑马", "A zebra is black and white.", "斑马是黑白色的。"),
        word("zoo", "/zuː/  n.", "动物园", "We see many animals at the zoo.", "我们在动物园看到很多动物。"),
        word("zipper", "/ˈzɪpə/  n.", "拉链", "Pull up your zipper.", "把拉链拉上。")
    )
}
