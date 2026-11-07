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
| **可用时段** | 工作日 / 周末分别设时间窗；不可用或时长用尽时全屏大倒计时锁屏 |
| **家长临时放行** | 锁屏按菜单键（或直接按遥控器数字键）输 PIN，放行 15 分钟 |
| **防跳看** | 必看视频记录「真正看到过」的最远位置，只许回看已看部分，快进越界自动拉回并提示 |
| 家长选项 | 菜单键 + PIN：重读清单 / 今天重来 / 跳过必看 / 清进度 / 奖励中心 / 临时放行 |
| 续播 | 记住每个文件看到哪儿；进度过 92%（可在清单改）自动标记完成 |
| 断网兜底 | 读不到 NAS 时用上次缓存的清单照常显示 |
| NAS 换 IP 自愈 | 445 连不上时扫本网段找 SMB 主机，找到自动切过去 |

### 遥控器

| 按键 | 作用 |
|---|---|
| 上 / 下 | 选分区 |
| 左 / 右 | 选片；播放时快退 / 快进 15 秒 |
| OK | 播放 / 暂停、进入 |
| 返回 | 回上一级 |
| 菜单 | 家长选项（要 PIN） |
| 信息 / 指南 | 打卡墙 · 勋章墙 |
| 数字键 1-4（锁屏时） | 直接输 PIN 临时放行 |

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

### 4. 生成片单

```bash
python -m pip install pysmb
python make_playlist.py --upload        # 扫 NAS 生成 data/curated/tv.json 并上传
python set_schedule.py --show           # 查看当前时段设置
```

也可以直接手编 NAS 上的 `\\<NAS>\data\curated\tv.json`，字段见 `tv.example.json`。

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

改完按遥控器**菜单 → 重新读取清单**生效，不用重装 App。
`python set_schedule.py --weekday 18:00-20:30 --weekend 09:00-11:30,16:00-20:30 --limit 45` 可以命令行改时段。

---

## 后续玩法规划（还没做，按性价比排序）

1. **每周任务卡**：一周给 3~5 个小目标（看一集纪录片 / 学一首字母歌），完成翻卡，比每日打卡更有惊喜感
2. **片尾小问答**：必看视频播完弹一道 2 选 1 的题，答对才算真正完成，防止挂着不看
3. **奖励兑换**：攒够星星可以找家长换「多看 10 分钟」「周末挑一部电影」，把时长变成可协商的奖励
4. **成长报告**：每周日生成一张图（本周看了什么、坚持几天、新解锁勋章），可推到 NAS 或打印
5. **多娃档案**：进应用先选头像，各自有独立片单、打卡墙和勋章
6. **成就动画 / 音效**：解锁勋章时全屏礼花 + 一句定制化夸奖
7. **家长远程改单**：手机浏览器打开一个极简页面编辑 `tv.json`，不用开电脑
8. **生日 / 节日彩蛋**：特定日期自动换主题色、放出一部「特别放映」
9. **观影笔记**：看完让孩子用遥控器给这集打分（★~★★★），慢慢形成口味档案
10. **软关机提醒**：还剩 5 分钟时画面边缘渐变暖色，提前预告，减少硬切断带来的哭闹

---

## 目录

```
app/src/main/kotlin/com/family/curated/
  MainActivity.kt        海报墙 + 家长菜单 + 门禁
  PlayerActivity.kt      ExoPlayer + 防跳看 + 时长累计
  EpisodesActivity.kt    剧集列表
  RewardsActivity.kt     打卡墙 + 勋章墙
  LockActivity.kt        时段大倒计时锁屏
  KidApp.kt              全局时间守卫
  data/  Model / Store / Repo / Smb / SmbDataSource / Covers
         Schedule / GateKeeper / Extras / Badges
  ui/    SectionsAdapter / PosterAdapter
make_playlist.py         扫 NAS 生成片单
set_schedule.py          命令行改时段 / 时长 / PIN
tv.example.json          带注释的清单样例
docs/                    私有配置模板
```

## 安全说明

- `nas.local.json`、`LocalSecrets.kt`、`tv.json`、`local.properties` 都在 `.gitignore` 里，**不会上传**
- NAS 账号密码只存在于电视本机和这两个本地文件；仓库里只有 `YOUR_NAS_USER` 占位符
- 家长 PIN 明文写在 NAS 的 `tv.json` 里，仅用于防止孩子随手解锁，不是安全加密
