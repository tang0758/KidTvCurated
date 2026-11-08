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
| `kiosk` | 否 | 儿童锁：拦截返回键 / 系统功能键、被切走自动拉回；整段省略 = 关闭。见下方专节 |

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
- `id` 可省略，默认用路径做进度记录的 key；**改路径会让播放进度归零**。

### 封面缩略图 `cover`

**App 不联网刮削海报、也不会从视频里截帧**——它只会去 NAS 上找「现成的图片文件」。这是为了保证电视完全离线、只显示家长编排的内容。所以如果 NAS 目录里一张图片都没有，海报卡片就只会显示标题首字，属于正常现象，不是加载失败。

查找顺序（`cover` 留空时）：

| 条目类型 | 依次尝试 |
|---|---|
| `file` | 同名 `.jpg` → 同名 `.png` → `<名字>-poster.jpg` |
| `folder` | `folder.jpg` → `poster.jpg` → `cover.jpg` → `封面.jpg` |

`cover` 可以显式指定，两种写法：

```json
{ "title": "识字 第3课", "file": "学习/识字/03.mp4", "cover": "学习/识字/03.jpg" }
{ "title": "汪汪队", "folder": "动画/汪汪队", "cover": "data:curated/covers/abc123.jpg" }
```

第二种是**「共享名:路径」**：视频共享常被设成只读，没法往旁边塞同名 jpg，这时把封面统一放到可写的清单共享（`data`），用冒号前缀告诉 App 去哪个共享读。

#### 一键生成（推荐）

```bash
python -m pip install -r tools/requirements.txt   # pysmb
# 还需要本机有 ffmpeg / ffprobe，并加入 PATH

python tools/make_covers.py --dry-run              # 先看会挑哪些视频截帧
python tools/make_covers.py --apply                # 生成 + 上传 + 把 cover 写回 NAS 清单
python tools/make_covers.py --force --from-nas     # 重新截帧（换构图 / 换片头）
```

工具会：对每个 `file` 直接用该视频、对每个 `folder` 递归找第一个视频 → ffprobe 取时长 → 在 20% / 45% 两个时间点各截一帧，挑信息量大的 → 拼成 480×682 竖版海报（完整画面居中 + 上下模糊背景，不会被电视的竖版卡片裁掉主体）→ 上传到 `data/curated/covers/<hash>.jpg` → 把 `cover` 合并进 NAS 上的 `tv.json`（写前自动备份 `tv.backup.json`）。

已有 `cover` 的条目会被跳过（想重做加 `--force`）；文件名由路径 hash 决定，重复运行不会堆垃圾；`--plain` 可输出原始 16:9 截帧。网页控制台「片单编排」里每个条目都能看到封面预览并直接改路径。

> 改完清单后，在电视按「菜单 → 重新读取清单」，或直接重启 App，才会刷新封面缓存。

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

## 儿童锁 kiosk

只靠 App 是拦不住 Home 键的 —— Android 把 Home 键留给系统，应用连 `onKeyDown` 都收不到。
所以这个功能是「应用层能做的三件事 + 一步系统设置」的组合。整段省略或 `enabled: false` 时，行为和旧版完全一样。

| 层 | 挡住什么 | 怎么做 |
|---|---|---|
| 应用层 | 返回键、设置 / 输入源 / 最近任务等能收到的功能键 | `kiosk.enabled=true`；主界面吞返回键，子界面只吞系统键（返回仍可回上一级） |
| 桌面层 | Home 键 | 把本应用设为默认桌面，按 Home 就回到自己，见 [README](../README.md) 的「开机自启与儿童锁」 |
| 守卫层 | 被别的应用切到后台 | `reentryMs` 毫秒后自动把主界面拉回前台；屏幕熄灭、家长放行期间不打扰 |
| 硬件层 | 输入源 / 直播 / 电源等硬件快捷键 | 应用层无解：遮挡按键、换简易遥控器，或 Device Owner / 锁任务模式 |

```json
"kiosk": {
  "enabled": true,
  "exitComponent": "com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity",
  "exitMinutes": 30,
  "reentryMs": 1000,
  "blockBack": true,
  "blockSystemKeys": true
}
```

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | `false` | 总开关。改完在电视按菜单 → 重新读取清单生效 |
| `exitComponent` | 空 | 家长退出时启动的桌面组件；留空则在电视上列出所有桌面让家长选 |
| `exitMinutes` | `30` | 家长临时放行分钟数，期间不自动拉回，避免把家长也锁在外面 |
| `reentryMs` | `1000` | 离开应用多久后拉回（300–10000 毫秒） |
| `blockBack` | `true` | 主界面是否拦截返回键；剧集 / 播放 / 奖励页的返回键始终可回上一级 |
| `blockSystemKeys` | `true` | 是否拦截能收到的设置、输入源、最近任务、语音等功能键 |

家长出口：电视上按 **菜单 → 输入 PIN → 「退出到桌面」**。放行到期会自动恢复锁定，
也可以回到小剧场后再进一次家长菜单点「结束家长放行」提前恢复。

> 只开 `kiosk.enabled` 而不设默认桌面也能用：孩子按 Home 会先看到 Aerial / 系统桌面约 1 秒，
> 然后被守卫拉回来。想完全看不到这一下，必须做那一步系统设置。

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
| 海报卡片全灰、只有标题首字 | 正常现象：App 不刮削、不截帧，NAS 上没现成封面图。跑 `python tools/make_covers.py --apply`，或按上面「封面缩略图」一节手填 `cover`；改完在电视重读清单 |
| 控制台封面预览是破图 | `cover` 里的共享名 / 路径写错，或图片还没上传到 NAS；`data:xxx.jpg` 的冒号前必须是共享名 |
| 点文件夹没集数 | 目录层数是否超过 3 层；文件扩展名是否在支持列表内 |
| 改了没生效 | 电视上按菜单 → 重新读取清单；或检查是不是改到了另一份 tv.json |
| 锁屏右半边是空的 | `learning.enabled` 是否为 false；或 `useBuiltin: false` 但 `custom` 一张都没写 |
| 名画显示「加载中」 | `artFolder` 路径是否相对 `artShare` 根目录、共享名是否写对、图片是不是 jpg/png；不配就用内置图 |
| 学习卡片不自动换 | 按了播放 / 暂停键会暂停轮播，再按一次继续；或看 `intervalSeconds` 是否填得很大 |
| 密码泄露过 | 先改 NAS 密码，再更新 `tv.json` / `nas.local.json` / `LocalSecrets.kt`，重新编译安装 |
