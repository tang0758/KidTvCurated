# 小剧场 · KidTvCurated

给家里电视用的**家长编排型儿童点播应用**。电视上只出现你在 NAS 清单里写好的内容，
不扫全盘、不联网刮削、不被推荐算法带跑。

- 视频走 **SMB 直读**（smbj），海报墙 + 内置 ExoPlayer 播放，不依赖 Kodi / Jellyfin 服务端
- 片单只是一份 `tv.json`，放在 NAS 上，改完在电视上按菜单键刷新即可
- 适合 Android TV / 国产电视（海信 55E3F-MAX，Android 9，2.5GB RAM 实测通过）

---

## 功能

| 功能 | 说明 |
|---|---|
| 编排片单 | 只显示 `tv.json` 里写的条目；`file` 单集、`folder` 整季（递归 3 层子目录） |
| 每日必看门禁 | `gate: true` 分区全部看完，才解锁 `requires: "daily"` 的分区 |
| 每日自动轮换 | `auto: {"folder": "...", "count": 2}` 每天自动换一批 |
| **打卡墙** | 当天必看全部完成自动盖章；月历可翻页，连续天数实时统计 |
| **勋章墙** | 10 枚成就勋章，按累计打卡 / 连击 / 观看时长 / 视频数解锁，新勋章有小红点 |
| **每日播放时长** | 真实播放时长累计（暂停不计时），主界面与奖励中心显示；可设每日上限 |
| **可用时段 + 大倒计时** | 工作日 / 周末分别设时间窗；不可用或时长用尽时锁屏，左 42% 显示大倒计时 |
| **等待时间学习角** | 锁屏右 58% 自动轮播：15 首唐诗、24 个 A–Z 英语单词、10 幅内置名画、识字 / 成语 / 短句；可自定义卡片、换成 NAS 上的图片，遥控器翻页与暂停 |
| **家长临时放行** | 锁屏按菜单键（或直接按遥控器数字键）输 PIN，放行 15 分钟 |
| **防跳看** | 必看视频记录「真正看到过」的最远位置，只许回看已看部分，快进越界自动拉回并提示 |
| **手机 / PC 编排** | 网页控制台浏览 SMB 目录、可视化编辑片单与时段，一键写回 NAS |
| 家长选项 | 菜单键 + PIN：重读清单 / 今天重来 / 跳过必看 / 清进度 / 奖励中心 / 临时放行 |
| 续播 | 记住每个文件看到哪儿；进度过 92%（可在清单改）自动标记完成 |
| 断网兜底 | 读不到 NAS 时用上次缓存的清单照常显示 |
| NAS 换 IP 自愈 | 445 连不上时扫本网段找 SMB 主机，找到自动切过去 |

### 锁屏长什么样（电视实拍）

不可用时段锁屏按 **左 42% / 右 58%** 分栏：左边是需要隔几米也看得清的大倒计时，
右边留给名画和诗句这类需要画面宽度的学习内容，每 20 秒自动换一张。

![锁屏：唐诗](docs/preview/lock-learning-poem.png)

![锁屏：名画](docs/preview/lock-learning-art.png)

### 遥控器

| 按键 | 作用 |
|---|---|
| 上 / 下 | 选分区 |
| 左 / 右 | 选片；播放时快退 / 快进 15 秒 |
| OK | 播放 / 暂停、进入 |
| 返回 | 回上一级 |
| 菜单 | 家长选项（要 PIN） |
| 信息 / 指南 | 打卡墙 · 勋章墙 |
| 数字键（锁屏时） | 直接输 PIN 临时放行 |
| ◀ ▶ / ▲▼ / OK（锁屏时） | 手动翻一张学习卡片 |
| 播放 / 暂停（锁屏时） | 暂停 / 继续自动轮播 |

---

## 部署

### 1. 准备

- JDK 17、Android SDK 34（platform-34 + build-tools 34.0.0）
- 一台开了 SMB 文件服务的 NAS
- 电视打开网络 ADB：`adb connect <电视IP>:5555`

### 2. 填本机私有配置（这两个文件不会进 Git）

```bash
cp nas.example.json nas.local.json       # 改 NAS 地址 / 账号 / 密码 / 共享名
cp docs/LocalSecrets.example.kt.txt \
   app/src/main/kotlin/com/family/curated/data/LocalSecrets.kt   # 去掉说明，填同一组账号密码
```

`local.properties` 里写 `sdk.dir=...`（Gradle 会自动生成本地路径，已忽略）。

### 3. 编译安装

```bash
./gradlew :app:assembleDebug            # Windows: gradlew.bat
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4. 生成片单 / 编排内容

**推荐用网页控制台**（下一节）：手机、电脑浏览器直接浏览 NAS 目录、勾文件夹、改时段、一键写回。
命令行方式：

```bash
python -m pip install pysmb
python make_playlist.py --upload        # 扫 NAS 生成 data/curated/tv.json 并上传
python set_schedule.py --show           # 查看当前时段设置
```

想手编就从 **[`examples/tv.json`](examples/tv.json)** 复制一份改名 `tv.json` —— 这是提交的**公开模板**，
里面全是 `YOUR_NAS_*` 占位符和虚构目录。字段手册见 [docs/CONFIG.md](docs/CONFIG.md)，
编辑器补全 / 校验见 [docs/tv.schema.json](docs/tv.schema.json)。

---

## 网页控制台：手机 / 电脑上编排片单

不用敲命令行、不用记 JSON 字段。浏览器里直接浏览 NAS 目录、勾文件夹、改时段、编排锁屏学习卡片，一键写回 NAS。

![控制台界面（电脑）](docs/preview/console-pc.png)

手机窄屏会自动改成整行排布，[看手机截图](docs/preview/console-mobile.png)。

```bash
python -m pip install -r tools/requirements.txt
python tools/console.py                 # 本机：自动打开 http://127.0.0.1:8765/?k=访问码
python tools/console.py --lan           # 同一 WiFi 的手机也能打开，终端会打印手机链接和二维码
python tools/console.py --lan --port 9000 --token 我的访问码
```

五个页签：

| 页签 | 能做什么 |
|---|---|
| **资源浏览** | 选共享、逐层进目录，看视频 / 图片和大小；「集数」递归统计（和电视端一致，含多层季目录）；单条或整层「加入」到指定分区；文件可直接下载到手机 |
| **片单编排** | 增删改分区与顺序、改标题 / key / gate / requires / 列数，条目上下移，配置 `auto` 每日轮换，设「每日必看」 |
| **时段与密码** | PIN、看完比例、工作日 / 周末时间窗、每日时长、锁屏提示语，带常用预设 |
| **等待学习角** | 学习角开关、每张停留秒数、类别顺序、内置题库开关、名画目录与共享、自定义卡片（唐诗 / 单词 / 名画 / 识字）增删改排序 |
| **JSON / 导入导出** | 看原文、手改后应用到编辑器、下载 tv.json、导入 JSON、载入公开模板、写到本地 tv.json |

链接后可以加 `&tab=sections` / `&tab=rules` / `&tab=learning` / `&tab=json` 直接进某一页，手机上可以分别存书签。

常用流程：资源浏览 → 选好「添加到」哪个分区 → 点「＋ 加入」或「把本层子目录全部做成卡片」
→ 片单编排里调顺序 / 标必看 → 底部「保存到 NAS」→ 电视上按菜单「重新读取清单」。

保存时会：自动把 `nas` 段同步成控制台正在用的真实账号密码（前端从不回显密码）、
把旧清单备份为 NAS 同目录的 `tv.backup.json`、回读校验字节数。

**手机访问**：电脑跑 `--lan`，手机连同一个 WiFi，扫终端二维码或输入终端打印的 `http://192.168.x.x:8765/?k=...`。
访问码首次自动生成并存在 `.console_token`，手机存书签后不用每次输。
想不开电脑就随时改，把服务常驻到 NAS：

```bash
docker compose up -d --build            # 在仓库根目录执行
docker compose logs -f kidtv-console    # 看访问码和手机链接
```

容器把凭据和访问码放在命名卷 `kidtv-config` 里，首次打开页面点「连接设置」填一次即可，重建容器不丢。
绿联 UGOS Pro 可在「容器 / Docker」里导入本仓库的 `docker-compose.yml`；端口是 8765。

> 安全：默认只监听 `127.0.0.1`；`--lan` / Docker 会监听局域网，所有 API 都要求访问码。
> 页面不回显 NAS 密码，但局域网是明文 HTTP，只建议在家里 WiFi 用，别做公网端口映射。

---

## 片单格式

生效文件固定为 **`data/curated/tv.json`**（`儿童资源` 共享通常只读，所以清单单独放可写的 `data`）。
`file` / `folder` 路径都相对「儿童资源」共享根目录，中文直接写，不要百分号编码。

```json
{
  "nas": { "host": "192.168.31.72", "user": "...", "password": "...", "share": "儿童资源" },
  "playlistShare": "data",
  "pin": "1234",
  "passRatio": 0.92,
  "schedule": {
    "enabled": true,
    "weekday": [["17:30", "20:30"]],
    "weekend": [["09:00", "11:30"], ["16:00", "21:30"]],
    "dailyLimitMinutes": 60,
    "lockMessage": "电视休息时间，先去玩会儿别的吧"
  },
  "learning": {
    "enabled": true,
    "intervalSeconds": 20,
    "order": ["poem", "word", "art", "text"],
    "useBuiltin": true
  },
  "sections": [
    {
      "key": "daily", "title": "今天要看的", "gate": true,
      "items": [
        { "title": "识字 第3课", "file": "儿童视频/01.学习类视频/识字/03.mp4" }
      ]
    },
    {
      "key": "fun", "title": "动画片", "requires": "daily",
      "items": [
        { "title": "汪汪队", "folder": "儿童视频/99.娱乐动画/汪汪队立大功 PAW Patrol" }
      ]
    }
  ]
}
```

| 字段 | 含义 |
|---|---|
| `gate` | 标记该分区为「每日必看」 |
| `requires` | 被哪个 `key` 分区锁住；那个分区全看完才解锁 |
| `auto` | `{ "folder": "...", "count": 2 }`，每天自动从文件夹取 N 个并按天轮换 |
| `passRatio` | 播放进度达到多少算「看完」，默认 0.92 |
| `pin` | 家长密码，4 位数字 |
| `schedule.enabled` | `false` 或整段删掉 = 不限制时段 |
| `schedule.weekday/weekend` | 时间窗，支持 `["18:00","20:00"]`、`"18:00-20:00"`、`{"start":"18:00","end":"20:00"}`；跨零点写 `23:00-01:00` 也可以 |
| `schedule.dailyLimitMinutes` | 每天最多播放分钟数，`0` = 不限 |
| 不写 `weekend` | 周末沿用工作日；写 `[]` = 周末全天可用 |
| `learning.enabled` | 锁屏右半边学习角；整段省略也会启用内置题库，`false` = 只留大倒计时 |
| `learning.intervalSeconds` | 学习卡片每张停留秒数，默认 20 |
| `learning.custom` | 自定义卡片数组，字段见 [docs/CONFIG.md](docs/CONFIG.md) |

改完按遥控器**菜单 → 重新读取清单**生效，不用重装 App。
`python set_schedule.py --weekday 18:00-20:30 --weekend 09:00-11:30,16:00-20:30 --limit 45` 可以命令行改时段。

---

## 内置名画与离线题库

锁屏右半边的内容全部离线：唐诗、单词、识字卡片是代码里的常量，
10 幅名画是 `assets/art/` 里的缩略图，均为作者逝世逾百年的公共领域作品，出处见 [docs/ART_SOURCES.md](docs/ART_SOURCES.md)。
换成自己 NAS 上的图或孩子画作，只需在 `learning.artFolder` 填目录，不用重编译；
想换内置图，改 `tools/fetch_art.py` 里的清单，跑 `python tools/fetch_art.py --force` 后重编译即可。

---

## 后续玩法规划（还没做，按性价比排序）

「家长远程改单」已由上面的网页控制台实现，下面这些还没做：

1. **每周任务卡**：一周给 3~5 个小目标（看一集纪录片 / 学一首字母歌），完成翻卡，比每日打卡更有惊喜感
2. **片尾小问答**：必看视频播完弹一道 2 选 1 的题，答对才算真正完成，防止挂着不看
3. **奖励兑换**：攒够星星可以找家长换「多看 10 分钟」「周末挑一部电影」，把时长变成可协商的奖励
4. **成长报告**：每周日生成一张图（本周看了什么、坚持几天、新解锁勋章），可推到 NAS 或打印
5. **多娃档案**：进应用先选头像，各自有独立片单、打卡墙和勋章
6. **成就动画 / 音效**：解锁勋章时全屏礼花 + 一句定制化夸奖
7. **生日 / 节日彩蛋**：特定日期自动换主题色、放出一部「特别放映」
8. **观影笔记**：看完让孩子用遥控器给这集打分，慢慢形成口味档案
9. **软关机提醒**：还剩 5 分钟时画面边缘渐变暖色，提前预告，减少硬切断带来的哭闹
10. **配置文件版本管理**：控制台里一键对比 / 回滚历史版本，改错了不用去 NAS 翻备份

---

## 目录

```
app/src/main/kotlin/com/family/curated/
  MainActivity.kt        海报墙 + 家长菜单 + 门禁
  PlayerActivity.kt      ExoPlayer + 防跳看 + 时长累计
  EpisodesActivity.kt    剧集列表
  RewardsActivity.kt     打卡墙 + 勋章墙
  LockActivity.kt        锁屏：左 42% 大倒计时 / 右 58% 学习角轮播
  KidApp.kt              全局时间守卫
  data/  Model / Store / Repo / Smb / SmbDataSource / Covers
         Schedule / GateKeeper / Extras / Badges
         Learning / LearningLibrary / ArtImages
  ui/    SectionsAdapter / PosterAdapter
app/src/main/assets/art/  10 幅公共领域名画缩略图，离线内置
make_playlist.py         扫 NAS 生成片单（命令行）
set_schedule.py          命令行改时段 / 时长 / PIN
tools/console.py         网页控制台：浏览 SMB + 编排片单（手机 / PC）
tools/static/index.html  控制台前端（无外部依赖，离线可用）
tools/fetch_art.py       重新下载 / 更换内置名画图片
tools/requirements.txt   控制台依赖（pysmb / qrcode）
tools/Dockerfile + docker-compose.yml   常驻 NAS，手机随时可改
examples/tv.json         公开的配置模板，复制改名即可用
docs/CONFIG.md           字段手册 / 常见配方 / 排错
docs/ART_SOURCES.md      内置名画来源与许可
docs/tv.schema.json      JSON Schema（编辑器补全与校验）
docs/LocalSecrets.example.kt.txt   私有凭据模板
```

## 安全说明 · 为什么这几个文件不进 Git

| 文件 | 里面有什么 | 不上传的原因 |
|---|---|---|
| `nas.local.json` | NAS 地址、账号、**明文密码**，供 Python 脚本连接 | 纯凭据文件，每台机器不同 |
| `app/.../data/LocalSecrets.kt` | 同一组账号密码，会被**编译进 APK** | 同上；仓库里只有占位符版 `docs/LocalSecrets.example.kt.txt` |
| `tv.json`（根目录 / NAS 上那份） | `nas` 段的**明文账号密码**、家长 PIN，以及你家真实的片库目录和片名 | 既是凭据也是隐私，还是「扫描 NAS 自动生成」的运行时产物，每台 NAS 都不一样 |
| `local.properties` | 本机 Android SDK 绝对路径 | 只对当前电脑有效 |

仓库里公开的模板是 [`examples/tv.json`](examples/tv.json)：所有地址、账号、路径都换成了示例值，
别人复制一份填上自己的即可。`.gitignore` 只忽略**根目录**的 `tv.json`，不会误伤 `examples/tv.json`。

- NAS 账号密码只存在于电视本机（APK + NAS 上的 tv.json）和上述本地文件；Git 历史里只有 `YOUR_NAS_USER`
- 家长 PIN 明文写在 NAS 的 `tv.json` 里，仅用于防止孩子随手解锁，不是安全加密；装好请改掉默认 `1234`
- 网页控制台默认只监听 `127.0.0.1`，开 `--lan` 后会强制带访问码；仍建议只在家庭 WiFi 内使用
- 提交前可以扫一眼：`git diff --cached | grep -i password`
