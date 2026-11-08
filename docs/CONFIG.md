# 片单配置手册（tv.json）

电视端只读一份文件：NAS 上 **`<playlistShare>/curated/tv.json`**（默认即 `\\<NAS>\data\curated\tv.json`）。
改完在电视上按遥控器 **菜单 → 重新读取清单**，立刻生效，不用重装 App。

## 最快上手

1. 复制 [`examples/tv.json`](../examples/tv.json)，改名 `tv.json`。
2. 改 `nas` 段的地址、账号、密码、共享名（**这个文件含明文密码，不要提交到 Git**）。
3. 用网页控制台「资源浏览」勾选 NAS 上的文件夹，生成 `sections`；或照着模板手写。
4. 上传到 NAS 的 `data/curated/tv.json`（控制台里点「保存到 NAS」即可，会自动备份旧文件）。

> 用 VS Code 打开时，`examples/tv.json` 顶部的 `$schema` 会带出字段补全和校验（离线可用 `docs/tv.schema.json`）。

## 顶层字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `nas` | 建议 | `{host, port, user, password, share}`。省略时 App 用编译时 `LocalSecrets.kt` 里的账号，换 NAS / 换密码就得重编译 |
| `playlistShare` | 否 | `tv.json` 所在共享名，默认 `data`。`儿童资源` 常是只读的，所以清单单独放可写共享 |
| `sections` | 是 | 一行行海报分区，数组顺序即电视上的上下顺序 |
| `pin` | 否 | 家长密码 4 位数字，默认 `1234`。**装好请改掉** |
| `passRatio` | 否 | 播放进度达到多少算「看完」，默认 `0.92` |
| `schedule` | 否 | 可用时段 + 每日时长，整段省略 = 不限制 |
| `learning` | 否 | 锁屏右半边的「等待时间学习角」；整段省略 = 启用内置题库 |

`file` / `folder` / `cover` 都相对 `nas.share` 的根目录，用 `/` 分隔，中文直接写，**不要百分号编码**。
以 `_` 开头的字段只是注释，App 会忽略。

## 分区 section

| 字段 | 说明 |
|---|---|
| `key` | 唯一标识，`requires` 就是指向它。约定 `daily` 为每日必看分区 |
| `title` | 电视上显示的行标题 |
| `gate: true` | 标记「每日必看」。本分区所有条目看完，才解锁 `requires` 指向它的分区 |
| `requires` | 被哪个 key 的 gate 分区锁住；不写 = 随时可看 |
| `columns` | 每行海报数，默认 5 |
| `items` | 条目数组 |
| `auto` | `{folder, count}`：每天从文件夹自动取 N 集并按天轮换，与 `items` 二选一 |

## 条目 item

`file` 和 `folder` 二选一：

```json
{ "title": "识字 第3课", "file": "学习/识字/03.mp4" }
{ "title": "汪汪队立大功", "subtitle": "全 8 季", "folder": "动画/汪汪队立大功" }
```

- `folder` 会递归 3 层子目录，所以「剧名 / 第N季 / 单集.mp4」这种结构能正常读到。
- `cover` 可指定封面图相对路径；不写会自动找视频同名 `.jpg/.png`、文件夹里的 `folder.jpg` / `poster.jpg` / `cover.jpg` / `封面.jpg`。
- `id` 可省略，默认用路径做进度记录的 key；**改路径会让播放进度归零**。

## 时段 schedule

```json
"schedule": {
  "enabled": true,
  "weekday": [["17:30", "20:30"]],
  "weekend": [["09:00", "11:30"], ["16:00", "21:30"]],
  "dailyLimitMinutes": 60,
  "lockMessage": "电视休息时间，先去玩会儿别的吧"
}
```

- 时间窗支持三种写法：`"17:30-20:30"`、`["17:30","20:30"]`、`{"start":"17:30","end":"20:30"}`。
- 结束时间小于开始时间 = 跨零点，如 `"22:00-01:00"`。
- 不写 `weekend` → 周末沿用工作日；写 `[]` → 周末全天可用。
- `dailyLimitMinutes: 0` = 不限总时长；统计的是**真实播放时长**，暂停不计时。
- `enabled: false` 或整段删掉 = 完全不限制。
- 不可用 / 时长用尽时锁屏：**左 42%** 继续显示可用时段和大倒计时，**右 58%** 是学习角（见下节）；家长按菜单键输 PIN 可临时放行 15 分钟。

旧版简写（顶层 `allowed` / `allowedHours` / `dailyLimitMinutes` / `lockMessage`）仍兼容，但有 `schedule` 段时以 `schedule` 为准。

## 等待时间学习角 learning

等待开门的十几分钟别干瞪着倒计时：右半边会自动轮播唐诗、英语单词、名画、识字与成语。

**不写这一段也会生效**——APK 里已经内置 **57 首唐诗**、**78 个 A–Z 英语单词**（每个字母 3 个）、**50 幅公共领域名画**、**70 张识字 / 成语 / 短句卡**，共 **255 张**，断网可用。四类按 `order` 交错出现，不会连着来十几个单词。按 20 秒一张算，完整轮一遍约 85 分钟。

```json
"learning": {
  "enabled": true,
  "intervalSeconds": 20,
  "order": ["poem", "word", "art", "text"],
  "useBuiltin": true,
  "stage": 0,
  "stageSize": 0,
  "custom": [
    { "type": "text", "title": "日", "sub": "识字 · rì",
      "lines": ["日出", "生日", "日记"], "note": "太阳；一天也叫一日" },
    { "type": "poem", "title": "咏鹅", "sub": "唐 · 骆宾王",
      "lines": ["鹅，鹅，鹅，曲项向天歌。", "白毛浮绿水，红掌拨清波。"],
      "note": "写这首诗的时候，作者才七岁。" }
  ]
}
```

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | `false` = 隐藏右半边，锁屏回到原来的纯倒计时全屏 |
| `intervalSeconds` | `20` | 每张卡停留秒数（限制在 6–600），顶部有进度条 |
| `order` | 四类全开 | `poem` / `word` / `art` / `text` 的任意子集：**写几类就只出这几类**，并决定交错顺序；`custom` 里出现的类型会自动补进来，不会被吞掉 |
| `useBuiltin` | `true` | 关掉后只显示 `custom` 里的卡片 |
| `artFolder` | 空 | NAS 上的图片目录，按文件名顺序覆盖内置名画 |
| `artShare` | 空 | 图片所在共享；不写就用 `nas.share` |
| `stage` / `stageSize` | `0` / `0` | 分阶段学习，见下；两个都为 0 = 255 张全部轮播 |
| `custom` | `[]` | 追加在内置题库后面的自定义卡片（不受阶段限制，始终出现） |

**分阶段**：写 `"stage": 1, "stageSize": 50` 就是「第一阶段」——每类只取内置库的前 50 张（唐诗刚好 50 首、名画 50 张、单词前 50 个、识字前 50 张），一轮约 200 张。孩子熟悉之后把 `stage` 改成 `2` 进入下一批，或者把两项都改回 `0` 全部放出来。阶段号写超了会自动停在该类最后一组，不会出现空白。家长自己写的 `custom` 卡片始终参与轮播。

自定义卡片 `type` 决定字段怎么显示：

| type | title | sub | lines | note |
|---|---|---|---|---|
| `poem` | 诗名 | 朝代 · 作者 | 逐行诗句 | 诗意 |
| `word` | 单词 | 音标 / 词性 | 英文例句 | 中文释义 |
| `art` | 画名 | 画家 · 年代 | 可省略 | 看点；`image` 填 `asset:xxx.jpg` 或 NAS 路径 |
| `text` | 要认的大字 | 拼音 / 类别 | 组词 | 释义 |

**名画图片**：内置 50 幅公共领域作品（梵高《星月夜》、达·芬奇《蒙娜丽莎》、葛饰北斋《神奈川冲浪里》与《红富士》、郭熙《早春图》、王希孟《千里江山图》局部等，题材以风景 / 动物 / 孩子为主），来源见 [docs/ART_SOURCES.md](ART_SOURCES.md)，想重下或换图用 `python tools/fetch_art.py`。把自己的图片放进 NAS 目录再写 `"artFolder": "curated/art", "artShare": "data"` 即可换成家庭相册 / 孩子画作。

**加内容**：内置题库按类别放在 `app/src/main/kotlin/com/family/curated/data/builtin/`（`Poems.kt` / `Words.kt` / `Arts.kt` / `Texts.kt`），照着现有格式加一行即可。改完跑 `python tools/check_library.py` 自检数量、A–Z 覆盖、图片引用和重复标题。

**遥控器**：`◀ ▶`（或 `▲ ▼` / OK）手动翻页，播放 / 暂停键暂停自动轮播，菜单键 + PIN 仍是家长临时放行，返回键照旧被拦住，孩子翻不出去。

## 常见配方

**只让孩子先看完两集才能看动画片**：`daily` 分区 `gate: true` 放两集，动画片分区写 `requires: "daily"`。

**每天自动换一课**：

```json
{ "key": "lesson", "title": "每日一课", "requires": "daily",
  "auto": { "folder": "学习/ABC Book", "count": 2 } }
```

**完全不设门禁**：删掉所有 `gate` / `requires`，或只留一个不带 `requires` 的分区。

**周末全天开放**：`weekend: []`；只放宽到某个点则写 `"weekend": [["09:00", "23:30"]]`。

## 哪些文件不能进 Git

| 文件 | 原因 |
|---|---|
| `tv.json`（本地生成的） | 含 **NAS 明文账号密码 + PIN**，以及你家真实片库目录结构；且是运行时产物，每台 NAS 都不同 |
| `nas.local.json` | 存 NAS 地址 / 账号 / 密码，供 Python 脚本使用 |
| `data/LocalSecrets.kt` | 编译进 APK 的兜底凭据，含明文密码 |
| `local.properties` | 本机 Android SDK 绝对路径 |

模板 [`examples/tv.json`](../examples/tv.json) 里全是 `YOUR_NAS_*` 占位符和虚构目录，可以安全公开。
`.gitignore` 已挡住上述四个文件；提交前可用 `git diff --cached | grep -i password` 再扫一眼。

## 排错

| 现象 | 检查 |
|---|---|
| 电视显示「连不上 NAS」 | 445 端口、账号密码、`nas.share` 名、清单是否真在 `data/curated/` |
| 海报墙空 | `file` / `folder` 路径是否相对**视频共享**根目录；中文路径不要 URL 编码 |
| 点文件夹没集数 | 目录层数是否超过 3 层；文件扩展名是否在支持列表内 |
| 改了没生效 | 电视上按菜单 → 重新读取清单；或检查是不是改到了另一份 tv.json |
| 锁屏右半边是空的 | `learning.enabled` 是否为 false；或 `useBuiltin: false` 但 `custom` 一张都没写 |
| 名画显示「加载中」 | `artFolder` 路径是否相对 `artShare` 根目录、共享名是否写对、图片是不是 jpg/png；不配就用内置图 |
| 学习卡片不自动换 | 按了播放 / 暂停键会暂停轮播，再按一次继续；或看 `intervalSeconds` 是否填得很大 |
| 密码泄露过 | 先改 NAS 密码，再更新 `tv.json` / `nas.local.json` / `LocalSecrets.kt`，重新编译安装 |
