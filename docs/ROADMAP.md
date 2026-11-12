# 优化计划 ROADMAP

| 项目 | 内容 |
|---|---|
| 产品 | 小剧场 · KidTvCurated |
| 文档 | 优化计划 V2.3 |
| 日期 | 2026-11-10（最后更新 2026-11-11 夜 · OPT-19） |
| 基线 | `main` @ `3e7193b` |
| 输入 | [PRD](PRD.md) 的风险与待办、[用户使用说明书](用户使用说明书.md) 的常见问题、一次代码评审结论、[外部应用计时方案对比](外部应用计时方案对比.md) 与 [ADR-01](ADR-外部应用计时.md) |
| 目标 | 从「能用」到「可信 + 有反馈」：规则绕不过、数据丢不了、家长看得见 |

**V1.1 变更**：孩子体验由 Wave 3 提前到 Wave 2；周报确定只做电视端。

**V1.2 变更**：采纳 A+C 分层架构，外部应用计时（OPT-04）**由「待定」转为正式排期**，落在 Wave 2 之后、Wave 3 之前；架构定稿见 [ADR-01](ADR-外部应用计时.md)；规则闭环目标由「3 条绕过路径降到 1 条」改为**本期清零**。Wave 顺序不变，孩子体验不因此后移。

**V1.4 变更（2026-11-10 晚）**：用户重开电视后实机确认「从应用入口跳到第三方应用就一直能看」，
`dumpsys usagestats` 显示优酷当天 2 小时 1 分 / 启动 8 次，而 `watch_<day>` 只涨了 6 分钟 ——
每日上限**从未触发**。这不是「以后再优化」的体验问题，而是核心承诺已经失效，
因此 **OPT-04 从 Wave 2 之后提前到 Wave 0 之后立刻实施**，发 `versionCode 3 / v1.2`。
V1–V5 验证门同批跑完（见第 14 节），Wave 1 顺延。

**V1.5 变更（2026-11-10 深夜）**：Wave 1 的 OPT-05 / OPT-06 / OPT-07 全部落地并实机验收通过，
发 `versionCode 4 / versionName 1.3`（见第 14 节）。同时修正第 10 节异常用例的编号：
PRD 15.4 已把 EX-11～EX-20 用在 Wave 0 与 OPT-04 上，原提案里 Wave 1 的 EX-14 / EX-15
与 PRD 冲突，统一改为 **EX-21 / EX-22**，以 PRD 为准。

**V1.6 变更（2026-11-11 凌晨）**：Wave 2 孩子体验四项（OPT-11 / OPT-12 / OPT-13 / OPT-14）全部落地并实机验收，
发 `versionCode 5 / versionName 1.4`（见第 14 节）。与计划的偏差：**连播队列由剧集页整串传给播放页**，
播完不再回 SMB 列目录（避免刚播完时连接半死、也避免播放期间抢主连接）；
另新增 `features.continueWatching` 作为「继续看」的远程回滚闸门（该功能已于 2026-11-12 整体移除，见 14 节）。下一步改为 Wave 3。

**V1.8 变更（2026-11-11 傍晚）**：Wave 3 第二批落地 —— **OPT-08 电视端周报**与 **OPT-10 完成必看自动奖励加时**，
发 `versionCode 7 / versionName 1.6`（见第 14 节）。两处与计划的偏差：① 奖励加时**没有**用 `Extras.grantTemp`，
改成记在当天额度 `bonus_<day>` 上（理由见第 6 节，`grantTemp` 是从此刻开始倒计时的放行窗口，孩子吃完饭回来就过期了）；
② 周报「反复看」需要日期维度，新增 `wplay_<day>` 集合（同一天同一部只记一次，只留 14 天），不新增任何 NAS 读写。

**V1.9 变更（2026-11-11 晚）**：**OPT-17 异常落盘**落地，发 `versionCode 8 / versionName 1.7`（见第 14 节）。
原先 `KidApp.tick` 的 `catch` 是空的（「守卫不能把应用搞崩」），代价是电视上出任何问题都没有线索。
现在改成 `Diag`：**先写本地 `filesDir/diag`，再在后台合并上传** NAS 的 `data:curated/logs/tv-yyyyMMdd.txt`
（单文件 200 KB 滚动、保留 7 天、同一处异常 60 秒只记一条），并接管未捕获异常。
另有 `Smb.withIsolatedShare()`：一条独立短连接里做完「建目录 / 列目录 / 删 / 读 / 写」，不碰主连接。

**V2.0 变更（2026-11-11 夜）**：**OPT-16 文案收口**落地（`versionCode 9 / 1.8`）：
设置页、锁屏页、家长工具三处共 **236 条**中文硬编码搬进 `res/values/strings.xml`（现 223 条资源），
**文案一字未改**（有脚本做过逐字符比对）。同时加 `tools/check_strings.py` 守卫：
Kotlin 里再写中文界面文字会直接报错，剩下的 271 处按文件记了基线，只许降不许升。
两处与计划的偏差见第 14 节。

**V2.1 变更（2026-11-11 晚）**：**OPT-18 设置页拆分**落地，发 `versionCode 10 / versionName 1.9`（见第 14 节）。
`SettingsActivity` 从 1094 行降到 **256 行**，只留外壳（布局 / 列表 / 写回 / 焦点保持）；
每一行设置是什么、点了弹什么框，按分组搬进 `settings/`：概览、时间与时长、规则、维护、系统五个 `SettingsGroup`。
**行为与文案一字未改**：`R.string.set_*` 的 201 处使用点逐条对齐（脚本比对出现次数与顺序），单测仍 112 条全绿。
另外把「关于」框里 `开 / 关` 两个字收进资源（223 → 225 条），设置页至此**零 UI 硬编码**（只剩一条时段时间正则）。
另：**周一到周五白天不做实机安装测试**，实机项统一登记在[待实机验证清单](待实机验证.md)。

**V2.2 变更（2026-11-11 夜）**：**OPT-15 CI + 时段解析单测**落地。
新增 `.github/workflows/android.yml`：每次 push / PR 跑两个 job —— **文案守卫**（`tools/check_strings.py`，半分钟）
与 **单测 + 打包**（`testDebugUnitTest assembleDebug`，有缓存约 1 分半）；单测从 112 条涨到 **130 条**。
同时把电视端「可看时段」的解析从 `TimeGroup` 搬进 `data/WindowMath.kt` 并补 18 条单测（跨零点、全角符号、
`18-20` 这种缺分钟的写法必须返回 `null` 而不是悄悄变成「全天」）。顺带修掉两个只在 Linux 上暴露的问题：
`gradlew` 在 Git 里是 `100644`（Windows 勾不出可执行位），runner 上 `./gradlew` 直接 `Permission denied`，
已改 `100755` 并在 workflow 里 `chmod +x` 兜底；新增 `.gitattributes` 把 `*.yml` / `*.sh` / `*.py` / `gradlew`
钉死为 LF，否则 workflow 的 bash 会报 `$'\r': command not found`。
**本次零运行时改动、不新增版本号**，APK 仍是 `v1.9(10)`。

**V2.3 变更（2026-11-11 夜）**：**OPT-19 家长工具收敛**落地，Wave 4 收口（见第 14 节）。
新增 `tools/launcher.py` + `tools/static/launcher.html` + 根目录 `小剧场.bat`：**一个启动页 + 三个模式**
（极简编辑器 8787 / 网页控制台 8765 / PC 桌面助手 8765 独立窗口）。启动页只负责
「探测在不在跑 → 按白名单拉起 → 跳转」，**三个工具一行都没改**，功能不合并。
关键收敛点：`console.py` 与 `desktop.py` **共用 8765 同一后端**，过去最容易撞端口；
启动页发现另一个已在跑就只「打开」不重复拉起。配套 `launcher_math.py` 纯逻辑 + 28 条 Python 单测，
CI 新增第三个 job。本次**零 APK 改动、不新增版本号**（PC 侧工具，APK 仍是 `v1.9(10)`）。

**V1.3 进度（2026-11-10）**：**Wave 0（OPT-00 / OPT-01 / OPT-02）+ OPT-03 已落地，`versionCode 2`**。
落地范围与偏差见第 14 节「落地记录」。可绕过路径：**3 → 2**（「改时钟」已闭合；「外部应用不计时」待 OPT-04、「重装清零」待 OPT-09）。

---

## 1. 一页结论

现在的问题不是功能少，是**核心承诺有缺口**：白名单和限额可以被绕过（改电视时间、从应用入口看外部 App、卸载重装清空记录），家长掏心经营了一套规则却看不到效果。

所以计划只追三条主线，不铺功能：

| 主线 | 一句话 | 判定标准 |
|---|---|---|
| **A. 规则闭环** | 规则在任何一条路径上都成立 | 可绕过路径 3 → 0（OPT-04 已定架构，Wave 3 前落地） |
| **B. 家长可感** | 家长能远程干预、能看到结果 | 远程动作 0 → 3，周报 0 → 1 |
| **C. 工程底座** | 以后敢改、坏了自己能查 | 单测 0 → ≥30 条，CI 常绿，异常可回溯 |

三条主线之外的一切（多孩、分级、语音、自更新）都排队，理由见第 12 节「明确不做」。

---

## 2. 成功指标（验收口径）

| 指标 | 现状 | 目标 | 怎么量 |
|---|---|---|---|
| 可绕过限额的路径 | 3 条（改时钟 / 外部应用不计时 / 重装清零） | **本期 0 条** | 手工用例 EX-11、EX-12、EX-13 全通过 |
| 外部应用观看时长归集 | 不计时（0） | 10 分钟误差 ≤ 60 秒；无授权自动降级预扣，绝不漏记 | ADR-01 的 A-01、EX-12、EX-17 |
| 家长远程可控动作 | 0 | 3（立即锁屏 / 加时 / 暂停） | `control.json` 三条指令实测 15 秒内生效 |
| 家长可见数据 | 0 页 | 1 个周报页（电视端） | 能答出「这周看了几小时、哪集反复看」 |
| 孩子看完整季的按键次数 | 12+ 次（每集点一次） | 1 次（首集点开） | 自动连播实测 |
| 自动化回归 | 0 | ≥30 条单测 + CI | `gradlew test` 全绿 |
| 崩溃可定位 | 无（异常被吞） | NAS `logs/` 可查最近 7 天 | 人为造一次崩溃能查到栈 |

---

## 3. Wave 0 · 基座（3 个改动，先做，约 2 人日）

后续每一项都依赖这三块地基，顺序不能乱。

### OPT-00 远程功能开关（灰度与回滚闸门）✅

- **问题**：现在改一个行为只能重装 APK；新逻辑出问题，家长只能跑 `adb`。
- **做法**：在 `tv.json` 增加 `features` 段（`extWatchMs`、`autoNext`、`remoteControl`、`report` 等布尔开关），由 `PlaylistSync.applyToLocal()` 写进 `Extras`；每处新逻辑用 `if (Extras.feature("xxx"))` 包一层，默认关闭。
- **价值**：**任何新功能出问题，家长在编辑器改一行、15 秒后生效**，不用卸载重装。这是整份计划里性价比最高的一个改动。
- **验收**：`features.autoNext=false` 时连播不触发；改回 true 15 秒内生效。
- **估时**：0.5 人日。

### OPT-01 观看时长统一账本 `WatchClock` ✅

- **问题**：`Extras.addWatchMs()` 只被 `PlayerActivity`（275、353 行）调用，口径单点，外部应用完全不计时。
- **做法**：新增 `data/WatchClock.kt`，单例 + 单一 owner（同一时刻只允许一个来源计时，避免重复计数）：`start(owner)` / `stop(owner)` / `tick()` / `add()`，另加 `preCharge()` / `refund()` 供外部应用兜底预扣使用；每 10 秒落盘一次；`KidApp` 的 1 秒 tick 驱动。`PlayerActivity` 改为 start/stop，删除自身累加逻辑。总账仍写 `watch_<day>`，另记 `ext_<day>` 明细。
- **风险**：播放页与前台守卫同时计时 → 用 owner 令牌 + 断言，重进页面必须 stop 旧的。
- **为谁铺路**：[ADR-01](ADR-外部应用计时.md) 的 `ExtWatchTracker` 直接复用这本账；`GateKeeper.status()` 读同一个 `watch_<day>`，OPT-04 落地时**门禁一行都不用改**。这一版就要把 `preCharge/refund` 预留好，避免返工。
- **验收**：播放 3 分钟，`watch_今天` 增加 180±5 秒；暂停 1 分钟不增加；切后台不增加。
- **估时**：1 人日。

### OPT-02 时钟回退守卫 `ClockGuard` ✅

- **问题**：`Extras.day()` 用 `System.currentTimeMillis()` 生成日期键，`tempUntil`/`kioskExitUntil` 也挂在墙上时间上。改电视日期 → 限额归零；自动对时回拨 → 误判。
- **做法**：新增 `data/ClockGuard.kt`：持久化 `lastWall`、`dayMaxUsedMs`；`now()` 若发现墙上时间倒退超过 2 分钟，返回「单调递增」的校正时间并累加 `anomalyCount`；当日已用量取 `max(实际, dayMaxUsedMs)` 只增不减。放行类倒计时改用 `SystemClock.elapsedRealtime()` 差值而不是墙上截止时间。
- **验收**：EX-11：看满限额后把系统时间改到明天，仍处于锁定；改回后当日用量不回退。
- **估时**：1 人日。

---

## 4. Wave 1 · 规则闭环（约 3.5 人日）

| 编号 | 项 | 依赖 | 估时 | 优先级 |
|---|---|---|---|---|
| OPT-03 | 版本可见 ✅ | — | 0.2 | P0 |
| OPT-05 | 剩余时间预告 ✅ | OPT-01 | 1 | P1 |
| OPT-06 | 远程控制卡 `control.json` ✅ | OPT-00 | 1.5 | P0 |
| OPT-07 | 设置写回失败阻断 + 未同步标记 ✅ | — | 0.8 | P1 |

> OPT-04（外部应用计时）不放在本波，也不插到 Wave 2 之前——避免把已经提前的「孩子体验」再往后推 1.5 人日。它的位置见第 8 节。

### OPT-03 版本可见 ✅

- **做法**：`versionCode` 1 → 2；`SettingsActivity` 页脚显示 `versionName + 构建时间`（`BuildConfig.BUILD_TIME` 或 `build.gradle` 注入字段）。
- **理由**：排障第一句永远是「电视上装的是哪版」。现在答不上来。
- **验收**：设置页底部可见；APK 属性里版本一致。

### OPT-05 剩余时间预告 ✅

- **做法**：播放页右上角小字「还剩 12 分钟」；剩 5 分钟一次 Toast；剩 1 分钟再提示；到点走现有 `stopForLock()`。外部应用在放行结束前 2 分钟同样提示（`Toast` + 守卫倒计时）。
- **理由**：低龄产品的体验差异不在「能不能锁」，在「锁得有没有预期」。
- **验收**：限额 30 分钟，三个时点各提示一次，不重复刷屏。

### OPT-06 远程控制卡 `control.json` ✅

- **做法**：新增 `data/RemoteControl.kt`，用现成的 `Smb.readAllIsolated()` 每 15 秒读 `data:curated/control.json`（与 `PlaylistSync` 共用同一次轮询周期，不额外开连接）：
  ```json
  { "lockNow": false, "addMinutes": 0, "pauseNow": false, "updatedAt": "2026-11-10T20:15:00+08:00" }
  ```
  - `lockNow` → `Extras.grantTemp(0)` + 跳 `LockActivity`；
  - `addMinutes` → `Extras.grantTemp(n)`（家长主动加时，即使不在时段内）；
  - `pauseNow` → 播放器 `pause()`。
  - 幂等：按 `updatedAt` 去重，执行后 `Smb.writeIsolated()` 写 `control.ack.json` 回执，网页端能显示「已生效」。
- **验收**：在电脑上改文件，电视 15 秒内响应；重复轮询不重复执行。
- **注意**：`control.json` 与 `tv.json` 分开文件，避免家长手改控制指令时碰坏片单。

### OPT-07 设置写回失败阻断 ✅

- **做法**：`PlaylistWriter.patch()` 返回失败时，设置页停留在原页 + 明显错误条，并置 `hasUnsyncedEdit=true`；下次 `applyToLocal()` 前若发现未同步，弹「NAS 上仍是你上次保存的内容，是否用电视上的覆盖」。
- **理由**：现在「以为改好了、下次同步被回滚」是最伤信任的失败方式。

---

## 5. Wave 2 · 孩子体验（约 7 人日，已提前）

孩子每天真实接触的就是这一层。放在规则闭环之后、家长报表之前，是因为它不需要任何新权限，纯客户端改动，性价比高。

> **与 OPT-04 的取舍**：外部应用计时需要一次 `appops` 授权且有待验证的 ROM 行为，把它插到本波之前会让孩子体验整体后移。所以 OPT-04 排在本波之后（第 8 节），两者不互相阻塞。

| 编号 | 项 | 依赖 | 估时 |
|---|---|---|---|
| OPT-11 | 自动连播下一集 ✅ | OPT-00、OPT-01 | 2 |
| OPT-12 | 首页「继续看」 ⛔ 已移除 | — | 1.5 |
| OPT-13 | 占位封面（缺图不再灰块） ✅ | — | 1.5 |
| OPT-14 | 分区图标 + 今日任务进度条 ✅ | — | 2 |

### OPT-11 自动连播

- **做法**：`PlayerActivity` 监听 `Player.STATE_ENDED`（现在完全没有处理）→ 5 秒倒计时浮层「下一集：xx（取消）」→ 取同目录下一集（`Repo` 已能列目录）→ 复用现有 `EXTRA_*` 启动自身。受三件事约束：门禁未解锁 / 当日限额用尽 / `features.autoNext` 关闭 → 不连播，回到剧集列表。
- **验收**：一季 10 集，从第 1 集点开能连到第 10 集；第 5 集时限额到点则停止并锁屏。
- **风险**：连播会加快时长消耗，家长可能觉得「怎么比以前看得多」——上线时在说明书写一句。
- **落地（V1.6）**：新增 `data/AutoNext.kt`。**与计划的偏差**：队列由 `EpisodesActivity` 一次编码好
  （≤200 集，URL 编码的纯文本协议，不用 `org.json` 才能在纯 JVM 单测里跑），播放页 `STATE_ENDED`
  只做数组取下一个，**不再回 SMB 列目录** —— 一集刚播完时连接最容易半死，播放期间主连接也本该全留给视频读。
  倒计时 5 秒浮层可取消（返回）/ 立即播（确认）；`features.autoNext`（默认关）、`GateKeeper` 门禁、
  家长远程暂停三道闸门；启动时 `finish()` 自身，连播 10 集也只占一层 Activity。
- **实机**：ABC Book 26 集目录，第 1 集播完 → `自动连播倒计时：lv01-002…（5s）` → 自动进第 2 集；
  第 2 集倒计时按返回 → `自动连播已取消` 回剧集页；关开关后 `不连播：autoNext=false`；
  把限额压到已用量以下，清单同步后 1 秒内前台变 `LockActivity`。

### OPT-12 首页「继续看」（⛔ 2026-11-12 已移除，以下为历史留档）

- **做法**：动态分区 key 固定为 `p:continue`（守住「id 必须路径派生」的原则，不能用下标），取 `Store.progress` 最近 5 条，封面复用现有缓存；无进度则不显示。
- **落地（V1.6）**：新增 `data/ContinueMath.kt`；`Store.notePlayed()` 单独记「点开过」的时间，
  不拿进度落盘时间当排序依据（暂停 / 自动重连都会写进度，会把老片刷到最前）。
  分区内的条目一律沿用原路径派生 key；剧集页动态列出的单集按路径现造，但必须仍落在当前片单的
  `file` / `folder` 范围内，片单删了内容首页就不会留下打不开的卡。新增 `features.continueWatching` 默认开，可远程关掉。
- **实机**：播 20 秒返回首页出现「继续看 · 1 个」；把片单前两项对调后仍指向同一张卡；
  快速滚动后诊断日志 `继续看: recent=3 eligible=1 items=1`（分区始终存在，
  UI dump 偶发看不到只是 RecyclerView 把上方分区滚出屏幕后不再创建 ViewHolder）。

### OPT-13 占位封面

- **做法**：`Covers` 在 `markNoCover()` 分支不再返回 null，而用 `Canvas` 画一张「渐变色 + 标题大字」的 Bitmap（按条目 key 取稳定色相），进同一个 LRU。零 SMB 请求、零额外内存峰值。
- **落地（V1.6）**：新增 `data/PlaceholderMath.kt`（色相 / 折行纯函数）；`Covers.placeholder()`
  按卡片实际尺寸画 `RGB_565` 渐变 + 同心圆 + 标题，`cachedOrPlaceholder()` 收进同一个 24MB LRU，
  `load()` 找不到图时也不再回调 null。播放中 8 次探测仍腾不出 SMB 连接时同样先给占位图，不让孩子看灰块。
- **实机**：强制缺图卡片下 `ivCover` 全部 `V.ED`（可见）、`tvFallback` 全部 `G.ED`（隐藏）；
  海报墙连续左右移动 30 次无 ANR / OOM / 崩溃，进程存活、首页继续响应。

### OPT-14 分区图标与任务进度条

- **做法**：`Section` 增加可选 `icon` 字段（内置几套矢量图标，家长在编辑器选）；首页顶部一条「今天任务 2/5」进度条，替代现在只靠文案提示的 `gateRemaining()`。
- **落地（V1.6）**：`Section.icon` + 6 个内置矢量（`star / play / book / heart / game / moon`），
  只传名字不传图片路径，不认识的名字当「不显示」；两个编辑器都加了下拉、schema 加了枚举。
  首页 `taskBar` 进度条用 `gateTotal() / gateRemaining()`，没有必修分区就整行隐藏。
- **实机**：`ivSectionIcon` 出现在 view hierarchy 且 `V.ED`，`tvTask` 显示「今天任务 0/3」、`pbTask` 可见。

---

## 6. Wave 3 · 家长可感（约 5 人日）

| 编号 | 项 | 依赖 | 估时 |
|---|---|---|---|
| OPT-08 | 周报页 `ReportActivity`（**电视端**）✅ | OPT-01 | 2 |
| OPT-09 | 打卡与进度备份 `state.json` 🟡 代码完成，实机待验 | OPT-02 | 1.5 |
| OPT-10 | 完成必看自动奖励加时 ✅ | OPT-06 | 1 |

### OPT-08 周报页（电视端）✅

- **范围**：只做电视端，不做网页端。理由是数据在电视本地、家长坐在沙发上就能看，多写一个网页端要多一套 NAS 读写与鉴权。
- **做法**：新增 `ReportActivity`（入口放设置页顶部，与「我的奖励」并列）。数据全部来自本地，无需新埋点：`watch_*`（近 14 天）、`vids_*`、`stamps`、`front_*`。
  展示三块：① 近 7 天每天时长条形图（自绘 View，不引图表库，省内存）；② 连续打卡与最长连击；③「这周反复看的 3 部」与「总看到一半就退出的 2 部」——后者用 `front_/duration` 比值判断。
- **验收**：数字与 `shared_prefs` 实际值一致；无数据时显示空状态而不是 0。
- **本期即包含**：OPT-04 排在本波之前（第 8 节），条形图多一条「外部应用」分段，读 `ext_<day>`；若 OPT-04 降级为估算模式，该分段标注「估算」。
- **落地**：`data/ReportMath.kt`（日期算术、7 天序列、反复看、看到一半，17 条单测）+
  `ui/WeekBarView.kt`（自绘两段柱子 + 每日上限虚线，零图表库）+
  `ReportActivity` / `activity_report.xml`；入口在家长控制页顶部「概览 → 本周报告」，
  受 `features.report` 控制，**默认开**（只读页，不改任何行为；出问题关开关即收起入口）。
  「反复看」缺日期维度，新增 `wplay_<day>`（每天一个 key 集合），播放页点开时记一次，只留 14 天。

### OPT-09 打卡与进度备份

- **做法**：`Extras` 增加 `exportState()/importState()`，序列化 `stamps`、`front_*`、`pos_*`、`watch_*` 到 `data:curated/state.json`
  （落地时落在 `StateBackup` + 纯逻辑 `StateMerge`，`Extras` 只提供快照读写，见第 14 节）；触发时机：应用退到后台、每 5 分钟、每日首次打卡。冷启动若本地 `stamps` 为空且 NAS 有备份 → 提示家长「恢复上次记录」而不是静默导入。
- **理由**：卸载重装/恢复出厂会把 30 天连续记录清零——数据上无所谓，对孩子的意义不一样。
- **验收**：`pm clear` 后重启，能把打卡墙恢复。

### OPT-10 自动奖励加时 ✅

- **做法**：`schedule.rewardMinutes`（默认 0 = 关闭，上限 120）。当日必看全部完成 → 今天的上限加 N 分钟，
  提示「今天全部完成，奖励 10 分钟」。
- **落地偏差**：没有用 `Extras.grantTemp`。`grantTemp` 是「从此刻开始倒计时」的临时放行窗口，
  家长下一次手动放行会覆盖它，孩子做完任务先去吃饭、晚上回来再看时那 10 分钟已经过期 ——
  那不叫奖励。改成写 `bonus_<day>`（当日额度），`GateKeeper` 的 `limit` 直接加上它，
  与手动放行（`tempUntil`）**分开计数**，正合本节「避免互相覆盖」的初衷。
  只加「额度」，不改时段：不在允许时段里，奖励再多也进不去。纯逻辑在 `RewardMath`（8 条单测）。

---

## 7. Wave 4 · 工程底座（穿插进行，约 6 人日）

| 编号 | 项 | 估时 | 说明 |
|---|---|---|---|
| OPT-15 | 单测 + CI ✅ | 2 | 加 `testImplementation("junit:junit:4.13.2")` 与 `src/test/kotlin`；先覆盖纯函数：`Schedule` 时段解析与跨零点、`GateKeeper.nextOpening`、`Badges` 判定、`PlaylistParser` 路径解码（`+` 号事故回归）、`Kiosk` 解析。已落地：`junit` 早在 OPT-02 就引入，`src/test/kotlin` 现有 **130 条**（本轮新增 `WindowMathTest` 18 条：时段解析 / 跨零点 / 全角符号 / 缺分钟返回 `null`）；`.github/workflows/android.yml` 跑 `check_strings.py` + `testDebugUnitTest assembleDebug`，现为**三个 job 并行**（OPT-19 增 `python-tools`：PC 单测 + 启动器自检）。`Schedule.parse` 的 JSON 分支上不了纯 JVM 单测（`org.json` 是桩），解析与判定改由 `WindowMath` + `TimeWindow.contains` 覆盖。见第 14 节 |
| OPT-16 | 文案收口 `strings.xml` ✅ | 1.5 | 已落地（`v1.8`）：设置页 / 锁屏页 / 家长工具 236 条搬进资源，文案未改；新增 `tools/check_strings.py` 守住回潮。见第 14 节 |
| OPT-17 | 异常落盘 ✅ | 1 | 已落地（`v1.7`）：`DiagMath` 纯逻辑 + `Diag`；本地先落盘、后台合并上传 `data:curated/logs/tv-yyyyMMdd.txt`（200 KB 滚动、留 7 天、60 秒去重）；接管崩溃兜底。见第 14 节 |
| OPT-18 | 设置页拆分 ✅ | 1.5 | 已落地（`v1.9`）：`SettingsActivity` 1094 → 256 行，只留外壳；按「概览 / 时间与时长 / 规则 / 维护 / 系统」拆成 5 个 `SettingsGroup` + 适配器 + 弹窗工具箱，行为与文案零变化（201 处文案使用点逐条比对）。见第 14 节 |
| OPT-19 | 家长工具收敛 ✅ | 视情况 | 已落地：`tools/launcher.py` + `tools/static/launcher.html` + 根目录 `小剧场.bat`，「一个启动页 + 三个模式」（极简编辑器 / 网页控制台 / PC 桌面助手）。启动页只做探测·拉起·跳转，**三个工具一行未改**；`console` 与 `desktop` 共用 8765，发现已运行就复用不重复拉起。配 28 条纯逻辑单测 + CI 第三个 job。零 APK 改动、不新增版本号。见第 14 节 |

---

## 8. OPT-04 · 外部应用计时（架构已定，Wave 2 后 / Wave 3 前）

**状态**：**已排期，架构定稿**。方案选型见[方案对比](外部应用计时方案对比.md)，完整设计见 [ADR-01 · 外部应用计时](ADR-外部应用计时.md)。本节只写排期、验收与开工门控。

**位置**：Wave 2 结束之后、Wave 3 开始之前，约 1.5 人日（A+C）或 0.5 人日（仅 C）。依赖 OPT-00 / OPT-01 / OPT-02；**不阻塞 Wave 0–2**，且是 OPT-08 周报「外部应用分段」的数据来源。

**决策**：UsageStats 精确计时（A）+ 零权限预扣（C）分层。有授权走 A，无授权自动降级 C，绝不出现「少扣」。两条硬约束：复用 `KidApp.tick` 的 1 秒节拍（不新增线程、服务、定时器）；账写进现有 `watch_<day>`（**门禁不改**），另记 `ext_<day>` 明细。

**三个决定架构的约束**（详见 ADR 第 4 节）：

| # | 约束 | 后果 |
|---|---|---|
| 1 | 计时不能挂 `enforceKiosk()`：它第 67 行在儿童锁未启用时直接 return | 挂进去等于不工作，必须在 `KidApp.tick` 独立调用 |
| 2 | 插入点只能是 `KidApp.tick` 第 34 行 | 第 35 行 `clearAppLaunch()` 会先清放行标志，放后面会丢掉整段时长 |
| 3 | `UNKNOWN` 不等于 `LEFT` | 优酷静置 5 分钟无事件时保持会话，不能误判孩子已退出 |

**实现要点**：`AppGuard` 扩事件窗口、补 `MOVE_TO_BACKGROUND`、输出三态；新增 `ExtUsageProbe`（事件流 + `totalTimeInForeground` 双读）与 `ExtWatchTracker`（状态机、5 秒节流、幂等结算）；实时用 `elapsedRealtime`，退出时按系统累计值校准（偏差 > 20% 以系统为准）；全程 `ClockGuard.day()` 做日期键。落点：`KidApp.kt`（34 / 122 行）、`MainActivity.kt`（313 行）、`data/Apps.kt`、3 个新增类、`data/Extras.kt`；`GateKeeper.kt` 不改。

**验收**：EX-12、EX-16～EX-20 与 ADR 第 10 节 A-01～A-08。核心两条 —— 应用入口看 10 分钟，`watch_<day>` 增加 600±60 秒；`features.extWatchMs=false` 时行为与现状完全一致。

**开工门控（仍是硬前置）**：电视开机后跑[方案对比文档](外部应用计时方案对比.md)第 5 节 V1–V5。2026-11-10 16:20 探测电视离线，尚未实测；本架构按 API 语义与现有代码推断。

| 验证结论 | 执行范围 | 估时 |
|---|---|---|
| V1–V5 全通过 | 完整 A + C（ADR 全部内容） | 1.5 人日 |
| V2 通过但拿不到事件流 | 只做 C（预扣 + 退还 + 估算标注），`ExtUsageProbe` 不实现 | 0.5 人日 |
| V1 授权失败 | 只做 C + 编辑器推荐「关闭应用入口」 | 0.5 人日 |

**无论哪条分支都实现 C** —— 它是永不失效的底线，也是 A 不可用时唯一的闭合手段。`features.extWatchMs` 默认 `false`，出问题改回 `false` 等 15 秒即可，不重装 APK。

---

## 9. 排期建议

```
Wave 0（基座）        ██░░░░░░░░░  2.0 人日
Wave 1（规则闭环）    ░░███░░░░░░  3.5 人日
Wave 2（孩子体验）    ░░░░░█████░  7.0 人日   ← V1.1 提前
OPT-04（外部计时）    ░░░░░░░░░█  1.5 人日   ← V1.2 定稿，Wave 3 前
Wave 3（家长可感）    ░░░░░░░░░██  5.0 人日
Wave 4（工程底座）    穿插           6.0 人日
```

- **建议节奏**：Wave 0 + OPT-03 先发一版（约 2.2 人日），验证「改 JSON 关功能」这条链路，再往下走。
- **验证门尽量提前**：Wave 0/1 期间只要电视开过机，就顺手跑 V1–V5（10 分钟），不必等到 Wave 2 结束。结论只决定 OPT-04 是 1.5 还是 0.5 人日，不再决定「要不要做」。
- **每个 Wave 结束发一个版本**（`versionCode` 递增），出问题用 `features` 关掉，不靠回滚 APK。
- **每个 Wave 结束同步更新**：PRD 的对应 FR 与验收、说明书的常见问题、`README` 的功能清单。

### 依赖关系

```mermaid
graph LR
  O00[OPT-00 功能开关] --> O06[OPT-06 远程控制]
  O00 --> O11[OPT-11 自动连播]
  O01[OPT-01 时长账本] --> O05[OPT-05 剩余预告]
  O01 --> O11
  O01 --> O08[OPT-08 周报]
  O02[OPT-02 时钟守卫] --> O09[OPT-09 状态备份]
  O06 --> O10[OPT-10 自动奖励]
  O00 --> O04[OPT-04 外部应用计时]
  O01 --> O04
  O02 --> O04
  O04 --> O08
```

---

## 10. 测试策略（跟着 Wave 走）

> **排期约定（2026-11-11 起）**：周一到周五 09:00–18:00 **不做实机安装测试**（装包会重启应用、
> 改动片单与设置，打断家里人正常观看，出问题也来不及还原）；只读排查与 NAS 侧准备随时可以做。
> 实机验收集中在工作日晚上或周末，一轮跑完清单里的全部待验证项。
> 「代码写完但没上电视」的用例一律登记在 [待实机验证清单](待实机验证.md)，不再散落在对话里。

| Wave | 必测 | 手段 |
|---|---|---|
| 0 | 功能开关远程生效/失效；3 分钟计时准确；时间回拨不重置 | 实机 + 手工改 `tv.json` |
| 1 | control 三条指令幂等；写回失败提示；剩余预告三个时点 | 实机 + EX-21、EX-22（已全部通过，见第 14 节） |
| 2 | 连播受限额/门禁约束；占位封面不卡顿；继续看不错位 | 实机 + 51 条单测（2026-11-11 全部通过，见第 14 节） |
| OPT-04 | 10 分钟误差 ≤ 60 秒；无授权降级 C；时段到点不丢时长；静置不误判退出 | 实机 + ADR 第 10 节 A-01～A-08、PRD EX-14～EX-20 |
| 3 | `pm clear` 后恢复；周报数字与 prefs 一致；外部应用分段 | 实机 + `run-as` 读 prefs 对账 |
| 4 | 纯函数单测；CI 在 PR 上跑 | GitHub Actions |

异常用例（**以 [PRD 15.4](PRD.md) 的编号为唯一事实来源**）：

> 本节最初的提案占用了 EX-11～EX-20，但 OPT-04 提前落地时 PRD 已用这组编号登记了它的用例，
> 造成 EX-14 / EX-15 一号两义。V1.5 统一纠正：PRD EX-11～EX-20 保持不动，
> Wave 1 新增的两条顺延为 **EX-21 / EX-22**。

- EX-21 `control.json` 写坏 JSON → 忽略，不崩、不影响片单同步（Wave 1，原提案 EX-14）
- EX-22 NAS 写保护时在电视上改时段 → 应阻断并提示，不静默回滚（Wave 1，原提案 EX-15）
- Wave 3 的 `pm clear` 后重开 → 打卡可恢复（EX-24）

> **编号澄清（2026-11-11 第二轮验收时修正）**：早期第 14 节落地记录把「`pm clear` 恢复」写成 EX-13，但 PRD 15.4 的 `EX-13` 是「`features` 段写坏」，恢复记录的正确编号是 **EX-24**。第 10 节已声明「用例编号以 PRD 15.4 为唯一事实来源」，历史段落保留原文并加注，活文档（第 13 节、待实机验证清单）一律用 EX-24。

---

## 11. 风险与回滚

| 风险 | 影响 | 对策 |
|---|---|---|
| `WatchClock` 双重计时 | 孩子被冤枉扣时长 | owner 令牌 + 单测覆盖；上线首日家长对账一次 |
| `feature` 开关本身写坏 | 全部功能回到默认 | `Extras.feature()` 缺字段读默认值（关闭新功能），不抛异常 |
| 连播让时长消耗变快 | 家长误以为孩子看多了 | 说明书写明；限额到点仍会锁 |
| `state.json` 备份写频繁 | 增加 SMB 负载、诱发老问题 | 走 `writeIsolated`，10 分钟一次上限，失败静默退避 |
| 新逻辑把 SMB 主连接搞挂（历史事故） | 主界面全卡 | 所有新读取一律 `readAllIsolated`，禁止在 `PlaylistSync` 之外复用主连接 |
| `KidApp.tick` 插入点错位 | 时段到点时整段外部时长蒸发 | 状态机固定在第 34 行「先结算、再清放行、再锁屏」；EX-19 专项回归 |
| usage 事件流被 ROM 裁剪 | A 不可用 | 自动降级 C；开工前 V1–V5 门控，不在 A 上耗时间 |
| 系统累计值跳变 | 结算时长忽大忽小 | 双读校准 + 20% 阈值，系统值只做退出后纠偏；偏差记日志 |
| 预扣多扣、孩子被冤枉 | 家长不信任数字 | 结算自动退还 + 结算卡片 + 家长手动退还 + `ext_<day>` 明细 |

**回滚方式统一为**：`tv.json` 里把对应 `features.*` 改回 `false`，等 15 秒。不需要重装 APK。

---

## 12. 明确不做（本期）

| 项 | 为什么不做 |
|---|---|
| 多孩 profile | 数据模型要全局改 key（`pos_`/`done_`/`stamps` 都带 profile 前缀），是破坏性变更，等真有需求再一次性做 |
| 内容分级标签 | 需要给 1970 个文件打标，人工成本远大于收益；先在编辑器里用分区替代 |
| per-day 时段（周二四不同） | 表达力提升有限，家长用 `weekday/weekend` 够用 |
| 语音交互 / TTS 朗读 | 电视无麦克风，TTS 收益不确定，等孩子识字多一点再说；**单向「念诗」另记于 [需求池](需求池.md) RQ-001，尚未评估** |
| 自更新（OTA） | 家里只有一台电视，换 APK 的频率远低于改片单；`features` 开关已经解决 80% 的「回滚」需求 |
| 周报网页端 | 已定只做电视端，网页端等真有远程看数据的需求再说 |
| 重构 `MainActivity` | 485 行尚可维护，没有正在咬人的问题；优先级低于规则闭环 |

---

## 13. 下一步

1. ~~落 Wave 0 三项 + OPT-03（约 2.2 人日），发 `versionCode 2`~~ ✅ 已完成，见第 14 节。
2. ~~电视开机后跑 V1–V5~~ ✅ 已完成（V1/V2 授权通过、V3 事件流完整、V5 累计值可读但实时不刷新），见第 14 节。
3. ~~按 [ADR-01](ADR-外部应用计时.md) 实施 OPT-04~~ ✅ **提前完成**（实机缺陷驱动，发 `versionCode 3 / v1.2`），见第 14 节。
4. ~~Wave 1 的 OPT-05 / OPT-06 / OPT-07~~ ✅ 已完成并实机验收，发 `versionCode 4 / v1.3`，见第 14 节。
5. ~~Wave 2（孩子体验）~~ ✅ 已完成并实机验收，发 `versionCode 5 / v1.4`，见第 14 节。
6. ~~Wave 3（OPT-09 / OPT-08 / OPT-10）~~ ✅ **代码全部完成**，`versionCode 6 / v1.5` 与 `7 / v1.6` 两版；
   **OPT-09 的实机 EX-24 未跑**（旧稿误记 EX-13；2026-11-11 16:00 电视离线），
   与 OPT-08 / OPT-10 的新用例一起登记在 [待实机验证清单](待实机验证.md)。
   **周一到周五白天不做实机安装测试**，这部分只能在晚上或周末集中跑。
7. ~~Wave 4 的 OPT-17 / OPT-16 / OPT-18 / OPT-15 / OPT-19~~ ✅ **全部完成**，Wave 4 收口（`v1.7` / `v1.8` / `v1.9`，
   OPT-15 与 OPT-19 均为零 APK 改动）。代码侧四个 Wave 到此告一段落。
   接下来优先级：**① 把待实机验证清单跑完（含 v1.5~v1.9 的存量，这是当前最大的欠账）**，
   ② Wave 5 的体验项，③ 若启动页用得多，再考虑把三个工具的功能真正合并（本轮明确不做）。
   文案还剩 271 处：`MainActivity` 55、`Badges` 30（勋章名）、`PlayerActivity` 16、`ReportActivity` 16 是主要头寸；
   数据层（`Diag` / `StateBackup` / `ReportMath`）那些是**回给 UI 显示的消息**，要动就得改成 `@StringRes + args`，单独排一项。
8. Wave 3 周报读 `ext_<day>` 分段时，注意这台 ROM 的 `totalTimeInForeground` 实时增量为 0，
   结算实际走的是 `elapsedRealtime` 估算（ADR 第 13 节未决事项 1 已有实测结论）。
9. 待补（非阻塞）：换一个长视频补验「主动按 OK 暂停」不计时；
   A-02 / A-06「已在第三方 App 内被拉回盖锁屏」在允许时段内补跑；
   连播「恰好在第 5 集播完那一秒限额到点」未做端到端构造（5 秒倒计时窗口短于 15 秒同步周期，
   已由 `AutoNext.allowed` 单测 + 实机「限额到点 1 秒内切锁屏」覆盖）。
10. **新需求已收集，尚未排期**：详见 [需求池](需求池.md)（RQ-001 学习区配图 + 念诗、
    RQ-002 学习资源在线拉取、RQ-003 视频图标缩小约 30%）。RQ-001/002 关联，先确认音频与图片来源、
    在线通道（NAS 还是公网）、断网兜底；RQ-003 先确认缩的是首页海报卡还是分区小图标。
    本轮只登记，不改代码、不发版。

---

## 14. 落地记录

### 2026-11-10 · Wave 0 + OPT-03（`versionCode 2` / `versionName 1.1`）

| 项 | 落地内容 | 新增 / 改动 |
|---|---|---|
| OPT-00 | `features` 段解析 + `Extras.feature()` + `applyToLocal()` 下发；编辑器「规则」页与电视设置页都能改；**当前真正被消费的开关是 `kioskPullback`** | `Model.kt`（`Features`）、`Extras.kt`、`PlaylistSync.kt`、`KidApp.kt`、`SettingsActivity.kt`、`tools/static/index.html`、`docs/tv.schema.json` |
| OPT-01 | `WatchClock`：单一 owner、1 秒 tick、10 秒落盘、`watch_<day>` + `ext_<day>`、跨零点分账、`preCharge/refund` 预留 | 新增 `data/WatchClock.kt`；`PlayerActivity` 改为 `onIsPlayingChanged` 起停，删掉自带累加 |
| OPT-02 | `ClockGuard`：日期键统一、时间跳变冻结、改回自动解除、`daymax_<day>` 只增不减；放行倒计时改单调时间 | 新增 `data/ClockGuard.kt`、`data/ClockMath.kt`（纯计算）；`Extras`、`GateKeeper`、`SettingsActivity` |
| OPT-03 | `versionCode 2`、`versionName 1.1`、`BuildConfig.BUILD_TIME`；设置页页脚常显 | `app/build.gradle.kts`、`SettingsActivity.kt` |

**与计划的偏差（都是有意的）**

1. **OPT-00 多了一个 `kioskPullback` 开关**。计划用 `features.autoNext` 做验收，但 autoNext 要到 Wave 2 才有逻辑，
   Wave 0 阶段无从验证「15 秒生效」。所以把已有的「被切走后拉回」提升为可远程开关的项（默认 `true`，行为不变），
   让这条链路从第一天起就是活的。其余四个开关按计划先占位。
2. **ClockGuard 的数学部分拆成 `ClockMath` 并补了 6 条单测**。单测整体排在 Wave 4，但这段逻辑错一次就是
   「孩子被冤枉」或「规则被绕开」，先单独盖住。`gradlew test` 已可跑。
3. **放行倒计时存两份（墙上 + `elapsedRealtime`），取剩余小者**。计划只写「改用 elapsedRealtime」，
   但只存单调时间会在重启后失效（`elapsedRealtime` 归零 → 截止点看起来远在未来 → 放行永不结束）。
4. **已用量读取改为 `max(watch_<day>, daymax_<day>)` 且 `GateKeeper` 计入未落盘零头**，否则「到点」最晚迟 10 秒。

**已知限制**

- 时钟跳变只在一次运行期间比对（`elapsedRealtime` 重启归零）。「改日期 + 重启电视」这一组合识别不了，
  但那需要进系统设置再断电，不在本期威胁模型内（README 与 PRD 都已写明）。
- `features` 里的 `autoNext` / `remoteControl` / `report` 目前只是占位，改了不影响行为。
  `extWatchMs` 已随 OPT-04 提前落地（见下）。
  > 以上是 **Wave 0 当时**的状态：Wave 1 之后 `remoteControl` / `remainingNotice` 已生效，
  > Wave 2 之后 `autoNext` / `continueWatching` 也已生效，仍占位的只剩 `report`（Wave 3），见第 14 节。

---

### 2026-11-10 晚 · OPT-04 提前 + 四个绕过漏洞修复（`versionCode 3` / `v1.2`）

**触发**：用户重开电视后反馈「跳到第三方应用就一直能看」。实机取证：
`dumpsys usagestats` 的 `com.cibn.tv totalTime="2:01:27" appLaunchCount=8`，
19:10:30→19:40:32、19:40:34→20:10:32 每 30 分钟一轮；同期 `watch_2026-11-10 = 381280`（6.4 分钟），
`dailyLimitMinutes: 50` 从未触发。电视上还装的是 `versionCode 1`（v1.0）。

| 根因 | 修法 | 落点 |
|---|---|---|
| 外部应用时长完全不入账 | `ExtWatchTracker` 每 5 秒写 `watch_<day>` + `ext_<day>`；`GateKeeper` 不改 | 新增 `data/ExtWatchTracker.kt`、`data/ExtUsageProbe.kt`、`data/ExtClockMath.kt` |
| 点应用入口不查门禁 | `launchAllowedApp()` 开头查 `GateKeeper.status()`，不可用时直接拒绝 | `MainActivity.kt` |
| `current == null` 时锁屏盖不到第三方 App | `KidApp.pullbackForGate()` 主动拉回主界面盖锁屏，**与 `kioskPullback` 无关** | `KidApp.kt` |
| 升级 / 重装后本地缺 `kiosk` 键（`enabled` 默认 false → 拉回全程没工作） | `PlaylistSync.init()` 冷启动用缓存清单幂等重放 `applyToLocal()` | `PlaylistSync.kt` |

**A + C 分层按 ADR-01 落地**：有 usage 授权走精确计时（事件流三态 + 系统累计值纠偏）；无授权降级 C
（进入即预扣 `maxMinutes`、结算退还）。为让退还真的生效，给 `ClockGuard` 加了**唯一**的合法下调入口
`lowerUsageFloor()`，只由 `Extras.addWatchMs(refund=true)` 调用 —— 否则 `daymax_<day>` 只增不减会让
30 分钟预扣永远退不掉，反而冤枉孩子。

**V1–V5 验证门结论**：V1/V2 `GET_USAGE_STATS: allow` 通过；V3 事件流完整（含
`MOVE_TO_FOREGROUND` / `MOVE_TO_BACKGROUND`）；V4 应用内探针可用；V5 `totalTimeInForeground`
能读到（`dumpsys` 里 `totalTime=2:01:27`），但**会话期间实时增量恒为 0**（这台 ROM 退到后台才 flush），
`calibrate()` 的 `sys <= 0 → est` 分支自动生效。即：这台机器上实际是「精确计时 = elapsedRealtime 估算 +
事件流判离开」，系统值只作为结算时的兜底，不构成功能缺口。ADR 第 13 节未决事项 1 已有实测结论。

**实机验收**（21:15–21:22，PIN 1234 临时放行）：

| 用例 | 结果 |
|---|---|
| 进优酷 5 秒 | `watch_` 381280→386760，`ext_` +5480，日志「精确计时，系统基线 7287s」 |
| 静置 125 秒无事件 | 两者同步 +156744 ms（156.7 s），探针不误判退出 |
| 回小剧场结算 | 总增量 192760 ms ≈ 192 s 会话时长，日志「结算 192s（估算 192s / 系统 0s）」，无重复入账，`appLaunch*` 清空 |
| 撤销 `appops` 后进入 | 预扣 1800000 ms（30 分钟），日志「无授权，预扣 30 分钟」 |
| 25 秒后退出 | `watch_` / `ext_` / `daymax_` 都回落到 598915，顶栏「今天 9/50 分钟」，**没有冤枉孩子** |
| 冷启动补下发 | `kid_extras.xml` 从「无 `kiosk` / 无 `features`」变为 `kiosk.enabled=true`、`extWatchMs=true` |

**与 ADR-01 的偏差（有意的）**

1. **`features.extWatchMs` 默认由 `false` 改为 `true`**：ADR 按「新功能灰度」定默认关，但这次是
   **已实机确认的缺陷修复**，装上不生效等于没修。开关保留，改回 `false` 15 秒回滚；编辑器 normalize
   与 `tv.schema.json` 同步改成 `true`，否则家长在网页保存一次就会把修复写关掉。
2. **状态机不用 `WatchClock.start/stop`，改用 `WatchClock.add()` 每 5 秒直接入账**：`WatchClock` 的
   owner 机制是给播放页设计的，外部会话有独立的「放行窗口 + 三态探针」结束信号；走 `add()` 更直接，
   也避免两套 owner 相互抢占。`WatchClock.stopCurrent()` 在会话开始时清掉播放页残留 owner，仍保证互斥。
3. **`settle()` 的精确模式只补正差额、不退负差额**：`dayMax` 只增不减是防改日期绕过的，
   为几秒校准误差开一个下调口子不划算，按「宁可多算几秒」处理。
4. **C 模式退款必须同步下调 `dayMax`**：ADR 只写了「退还」，没写 `ClockGuard` 会挡住它。
   这是本轮最容易漏、也最容易冤枉孩子的一处。

**仍未端到端实测**：限额用尽 / 时段到点时「已在第三方 App 内被拉回盖锁屏」——
当前处于时段外且靠家长临时放行才进得去应用入口，而 TEMP 优先级高于限额判定（R-09），
造不出「门禁不可用但能点入口」的组合。逻辑已覆盖（`KidApp.pullbackForGate()`），
下次在允许时段内补跑 A-02 / A-06。其余 A-01 / A-04 / A-05 / A-07 已实测通过。

---

### 2026-11-10 深夜 · Wave 1（`versionCode 4` / `versionName 1.3`）

| 项 | 落地内容 | 新增 / 改动 |
|---|---|---|
| OPT-05 | `TimeNoticeMath` 纯计算取「最早会锁住孩子」的约束（限额 / 时段结束 / 跨零点 / 临时放行 / 远程锁定）；播放页右上角常显「今天还剩 12 分钟」；剩 5 分钟、1 分钟各一次 Toast，用 `notice_<day>_<tag>` 当天去重；外部应用放行结束前 2 分钟提醒 | 新增 `data/TimeNoticeMath.kt`、`data/TimeNotice.kt`、`TimeNoticeMathTest.kt`（13 条）；`PlayerActivity.kt`、`activity_player.xml`、`KidApp.kt` |
| OPT-06 | `control.json` / `control.ack.json` 分文件；15 秒轮询、独立短连接、播放期间也生效；`updatedAt` 幂等；`lockNow` 走 `GateKeeper.Reason.REMOTE`（否则下一秒守卫会 finish 掉锁屏）；`lockNow=false` 解锁；`addMinutes` 加时；`pauseNow` 用单调版本号让播放页暂停；坏 JSON 只打一次日志且不阻断片单同步 | 新增 `data/RemoteControl.kt`、`data/RemoteCommand.kt`、`RemoteCommandTest.kt`（6 条）；`PlaylistSync.kt`、`Store.kt`、`Extras.kt`、`GateKeeper.kt`、`PlayerActivity.kt`、`SettingsActivity.kt` |
| OPT-07 | `PlaylistWriter.Result` 带 `pendingText` / `baseHash`；失败时留在原页、挂红色错误条、`Store.unsynced_*` 六个键；弹窗三选「覆盖 NAS / 放弃 / 稍后」；重放前重读 NAS 比对基线，被改过就拒绝覆盖；`PlaylistSync` 在有未同步修改时跳过设置下发（片单照常刷新） | `PlaylistWriter.kt`、`Store.kt`、`PlaylistSync.kt`、`ParentTools.kt`、`SettingsActivity.kt`、`MainActivity.kt`、`activity_settings.xml` |
| 编排侧 | 网页控制台与极简编辑器都加「远程控制」页与 `/api/control`；`updatedAt` 带毫秒，保证连点两次也不是同一条指令 | `tools/static/index.html`、`tools/console.py`、`tools/tvjson_simple.py`、`docs/tv.schema.json` |

`features` 新增 `remainingNotice`（默认 **开**，装上即生效）与 `remoteControl`（默认 **关**，
家长需在编辑器打开）；`tv.schema.json` / 编辑器 normalize / 电视端默认值三处保持一致。

**构建**：`gradlew assembleDebug testDebugUnitTest --offline` 通过，
`ClockMath 6 + ExtClockMath 5 + RemoteCommand 6 + TimeNoticeMath 13 = 30` 条，
`failures=0 errors=0 skipped=0`；两处内嵌 JS `node --check`、两个 Python 文件 `py_compile` 均通过。
APK 26542672 字节，已装电视（`dumpsys package` → `versionCode=4 versionName=1.3`），
工作区 `小剧场.apk` 同步更新。

**实机验收**（22:55–23:36，沿用「备份 NAS 原字节 → 临时测试配置 → 原字节还原」策略）：

| 用例 | 结果 |
|---|---|
| 剩余预告三个时点 | limit 28 → 「今天还剩 12 分钟」；21 → 「还剩 5 分钟」+ Toast；17 → 「还剩 1 分钟」+ Toast；临时放行 → 「临时放行还剩 10 分钟」 |
| 阈值不刷屏 | 重复进入同一阈值只有预告日志，`grep -c "阈值提示"` = 0；`notice_2026-11-10_5min / _1min` 各写一次 |
| `lockNow=true` | ack `["lockNow"]`；前台变 `LockActivity`；`kid_extras.xml` 出现 `remoteLockUntil/remoteLockRt`；20 秒后重复轮询仍只有 1 条执行日志 |
| `lockNow=false` | ack `["unlock"]`；前台回 `MainActivity`；`remoteLock*` 无残留 |
| `addMinutes=10` | ack `["addMinutes:10"]`；`tempUntil*` 写入、无 `remoteLockUntil`；播放页显示「临时放行还剩 10 分钟」 |
| `pauseNow=true` | ack `["pauseNow"]`；播放页 `远程暂停指令已执行（version=1）` |
| **EX-21 坏 JSON** | 写 `{ broken json oops`，同时把 tv.json limit 改 27；20 秒后坏 JSON 警告只有 1 条，`dailyLimitMinutes:27` 正常下发、App 存活、片单同步不受影响 |
| **EX-22 写保护** | 用 SMB 只读属性造「读得到、写不回」（不是改密码、不是删文件）：`STATUS_ACCESS_DENIED (0xc0000022)`；设置页停留原页 + 红色错误条 + 「重试保存到 NAS」入口；`unsynced_*` 六键齐全；`Extras.schedule` 与 NAS 都仍是旧值，**没有静默回滚** |
| 同步不抹平 | 未同步期间从 PC 改 `lockMessage`，日志 `清单已自动更新（poll）…（电视有未同步修改，本次跳过设置下发）`，本地 `Extras.schedule` 保持旧值、红色错误条仍在 |
| 基线防覆盖 | NAS 期间被改过后点「覆盖 NAS」：重放被拒，`enabled` 仍为 true、`unsynced_edit` 仍为 true，不会抹掉家长在网页上的改动 |
| 放弃修改 | 点「放弃这次修改」→ `unsynced_*` 清空、错误条与重试行消失 |
| 重放成功 | 写保护解除后重新生成一份待重放记录，点「覆盖 NAS」→ `未同步的修改已重新写入 NAS` → `清单已自动更新（settings）` → NAS 与本地同时变为新值、错误条消失 |

**与计划的偏差（有意的）**

1. **`lockNow` 做成 `GateKeeper` 的一个 reason，而不是只跳 `LockActivity`**：全局守卫每秒都在按
   `status()` 决定要不要 finish 锁屏，不加 `REMOTE` 的话远程锁屏会在下一秒被自己拆掉。
2. **`lockNow=false` 且字段存在 = 解锁**：只靠 `addMinutes` 无法表达「撤销刚才的锁屏」，
   但必须区分「字段缺失」与「显式 false」，否则每份不含 `lockNow` 的旧指令都会误解锁。
3. **`pauseNow` 用单调版本号而不是广播**：播放器可能不在前台，直接发 Intent 会丢；
   `RemoteControl.pauseVersion` 由播放页 ticker 比对，回到播放页也能补执行。
4. **预告取「最早锁住」的约束，不是只看每日限额**：时段结束、临时放行结束、远程锁定都可能比
   限额更早到点，只说「还剩 X 分钟」会骗孩子；跨零点窗口在 `TimeNoticeMath` 里单独算。
5. **写回失败时存「完整 tv.json + 远端指纹」**：只存改动字段，重放时 NAS 已被别人改过就无从判断；
   指纹不一致时宁可让家长重设一次，也不能覆盖。
6. **补了 `SettingsActivity` 的同步监听**：重放成功后 `PlaylistWriter` 是异步触发同步的，
   原先弹窗 `onResolved()` 先渲染，家长会短暂看到旧开关值；现在只对 `reason=settings*` 补一次渲染。

**已知限制**

- `remoteControl` 默认关，需在编辑器「规则」页勾选后 15 秒生效；控制文件不存在 / 坏 JSON 都是静默忽略
  （坏 JSON 只记一条日志），不会在电视上提示家长。
- `addMinutes` 上限 240 分钟；`lockNow` 的远程锁定固定 8 小时后自动失效，也可显式发 `lockNow:false`
  或在电视上用 PIN 临时放行解除。
- `control.ack.json` 是电视单向写的回执，网页端只轮询读取；控制卡依赖 NAS 时钟与 `updatedAt` 字符串，
  同一毫秒内连点两次会被当成同一条指令（编辑器已带毫秒时间戳）。
- EX-22 的失败点选在「写 tv.json」；若连读都读不到，`pendingText` 为空，弹窗只提供「放弃 / 稍后」，
  需要家长重新设置一次。

**现场还原**：NAS `tv.json` 逐字节还原到 `e4cb4d445f903d6e…`（8125 字节，
`weekday 17:30-20:30`、`weekend 09:00-11:30 / 16:00-23:30`、50 分钟），`tv.backup.json` 同步写成正式内容，
`tv.json` 只读属性已清除，`control.json` / `control.ack.json` / `.control.json.tmp` 已删除；
电视 `curated_prefs.xml` / `kid_extras.xml` 还原为装 v1.3 之前的备份，重启后无崩溃、无测试残留键，
前台按正式时段规则停在 `LockActivity`（当天周二 23:36，属时段外，属预期行为）。

---

### 2026-11-11 凌晨 · Wave 2 孩子体验（`versionCode 5` / `versionName 1.4`）

| 项 | 落地内容 | 新增 / 改动 |
|---|---|---|
| OPT-11 | 自动连播：剧集页整季队列 → 播放页 `STATE_ENDED` → 5 秒可取消倒计时 → 复用 `EXTRA_*` 启动自身；`autoNext` / 门禁 / 远程暂停三道闸门 | 新增 `data/AutoNext.kt`；`EpisodesActivity.kt`、`PlayerActivity.kt`、`activity_player.xml`、`next_overlay_bg.xml` |
| OPT-12 | ~~首页「继续看」动态分区~~ ⛔ 2026-11-12 移除：`ContinueMath.kt` 删除，`MainActivity` 去掉动态分区，`Store.recentPlayed` 删除（`notePlayed` 保留给周报排序） | `data/ContinueMath.kt`（已删）、`MainActivity.kt`、`Model.kt` |
| OPT-13 | 缺图不再灰块：稳定色相渐变 + 标题大字占位图进同一 LRU | 新增 `data/PlaceholderMath.kt`；`Covers.kt`、`ui/PosterAdapter.kt` |
| OPT-14 | 分区图标（6 套内置矢量）+ 首页「今天任务 n/m」进度条 | 新增 `ui/SectionIcons.kt` 与 6 个 `ic_sec_*.xml`；`Model.kt`（`Section.icon`）、`ui/SectionsAdapter.kt`、`item_section.xml`、`activity_main.xml` |
| 配置层 | schema 加 `sections[].icon` 枚举与两个新开关；两个编辑器加分区图标下拉与功能开关；设置页加「首页继续看 / 自动连播」 | `docs/tv.schema.json`、`tools/static/index.html`、`tools/tvjson_simple.py`、`SettingsActivity.kt`、`Model.kt` |
| 测试 | 51 条单测全绿（新增 AutoNext 9 / ContinueMath 8 / PlaceholderMath 4） | `app/src/test/kotlin` 三个新测试类 |

**与计划的偏差（都是有意的）**

1. **连播队列不回 SMB 列目录**：由 `EpisodesActivity` 用纯文本协议编码整季（≤200 集）交给播放页。
   一集刚播完时 SMB 连接最容易半死，而且播放期间主连接要留给视频读。纯文本而非 `org.json`
   是为了让这段闸门逻辑能在纯 JVM 单测里真跑（`org.json` 在单测里是 Android 桩类，一调用就抛异常）。
2. **新增 `features.continueWatching`**：项目约定「新功能要有远程回滚闸门」，OPT-12 原计划没写开关，
   这里按约定补上，默认开（纯展示、不改变计时）；`autoNext` 仍按计划默认关。
3. **`Store.notePlayed()` 独立记「点开过」**：不复用 `setProgress()` 的落盘时间，
   否则暂停 / 自动重连会把刚翻过的老片刷到继续看最前面。

**实机验证（沿用「备份 NAS → 测试片单 → 还原」策略）**

- 连播：ABC Book 26 集，第 1 集播完 → `自动连播倒计时：lv01-002…（5s）` → 自动进第 2 集；
  第 2 集倒计时按返回 → `自动连播已取消` 回剧集页；队列下标正确递增到第 3 集。
- 约束：`autoNext=false` 时 `不连播：autoNext=false 有下一集=true`；限额压到已用量以下，
  清单同步后 1 秒内前台变 `LockActivity`；`AutoNext.allowed(gateOpen=false)` 单测覆盖。
- 继续看：~~播 20 秒返回首页出现「继续看 · 1 个」~~ ⛔ 功能移除，验收改看「首页第一个分区就是片单第一个分区」（2026-11-12）。
- 占位封面：强制缺图卡下 `ivCover` 全 `V.ED`、`tvFallback` 全 `G.ED`；
  海报墙连续左右 30 次无 ANR / OOM / 崩溃。
- 图标与进度条：`ivSectionIcon` 可见，首页显示「今天任务 0/3」，`pbTask` 可见。
- **已知限制**：`uiautomator dump` 在 RecyclerView 滚到下方后会只输出当前创建的 ViewHolder，
  曾误判「继续看消失」；诊断日志 `继续看: recent=3 eligible=1 items=1` 与重启后的首页确认分区始终存在，
  不是功能 bug。连播「恰好在第 5 集播完那一秒到点」未端到端构造，见第 13 节第 8 条。

**现场还原**：NAS `tv.json` 逐字节还原到 `e4cb4d445f903d6e…`（8125 字节），`tv.backup.json` 同步写成正式内容，
只读属性清除，`curated/` 只剩 `tv.json` / `tv.backup.json` / `covers`；电视三份 prefs
（`curated_prefs` / `kid_extras` / `kid_clock`）还原为装 v1.4 之前的备份，`played_*` 与
`continueWatching` 无残留，重启无崩溃，前台按正式时段规则停在 `LockActivity`（周二 00:30，时段外，预期）。

### 2026-11-11 下午 · Wave 3 第一批：OPT-09 打卡与进度备份（`versionCode 6` / `v1.5`）

**实现了什么**：`StateMerge`（纯逻辑）+ `StateBackup`（读写 NAS）+ `Extras` 快照读写 +
`Store` 的备份状态键；`data:curated/state.json` 存打卡与进度，和片单分文件。

**三个触发点**（按第 6 节设计）：退到后台（`KidApp.onActivityPaused` 里 `nudge`）、
每 5 分钟（`KidApp.tick` 驱动）、每日首次打卡（`MainActivity` 打卡成功处 `nudge`）。
全部复用 `PlaylistSync.submit()` 的同一条后台线程与 `Smb.writeIsolated()` 的独立短连接，
不新增线程 / 定时器，也不抢正在供视频的那条主连接。

**四条实现期定下的硬规矩**

1. **本地记录为空时绝不回写**：重装后 5 分钟自动备份会拿空数据把 NAS 上的好备份冲掉。
   `exportNow()` 在 `snapshot.isEmpty` 时直接返回，日志留一行「跳过备份」。
2. **用量自动合回、打卡与进度要问家长**：`watch_` / `ext_` / `vids_` 决定今天还剩多少额度，
   重装后归零等于白送一段（EX-24；旧稿误记 EX-13），必须静默补；`stamps` / `pos_` / `front_` 可能是家长
   刚用「清空播放进度」故意清掉的，静默导入会把他的操作悄悄撤销，所以走确认弹窗。
3. **`pm clear` 后仍要读得到备份**：清数据会把 `nas_*` 一起清空，`Store.nas()` 返回 null。
   所以 `StateBackup.nasCfg()` 和 `Repo.load()` / `PlaylistSync` 一样退回 `Store.DEFAULT_NAS`
   （内置地址与账号）——否则「重装能恢复」就是一句空话。
4. **清空进度后立刻把空的写回 NAS**：`SettingsActivity` 的「清空播放进度」加了一次
   `StateBackup.exportAsync("reset")`，否则下次恢复会把旧进度合回来，家长的操作等于没做。

**合并策略**：只升不降 —— 同键取大值、打卡取并集（`StateMerge.merge`），
于是导入天然幂等，重复导、导一半断电再导都不会把孩子的记录弄小；
也意味着这套机制**不能**用来回退数据，回退只有设置页那条显式路径。

**单测**：`StateMergeTest` 15 条（白名单前缀、日期校验、打卡去重与 400 天截断、
负数/超长键过滤、只升不降、并集、幂等、`raised` 计数、`onlyUsage` / `onlyRecords`、
何时该问家长、摘要文案）。累计 **66 条全绿**。

**实机验证：待跑（阻塞在电视开机）**。2026-11-11 16:00 电视 `192.168.31.147` 离线
（ARP 表里已消失，NAS `192.168.31.72` 正常），`adb connect` 超时。开机后要补：

- EX-24（旧稿误记 EX-13）：`pm clear com.family.curated` → 重启 App → 今日 `watch_<day>` 自动回补、
  弹出「发现上次的观看记录」→ 点「恢复」后 `stamps` 与 `pos_*` 回来。
- 空记录保护：清数据后先等一轮自动备份，确认日志是「跳过备份」而不是覆盖。
- 家长入口：设置页「维护 → 记录备份」的「立即备份 / 从 NAS 恢复」。
- 还原现场：`state.json` 删除、三份 prefs 还原、`appops` 的 `GET_USAGE_STATS` 重新授权。

### 2026-11-11 傍晚 · Wave 3 第二批：OPT-08 周报 + OPT-10 奖励加时（`versionCode 7` / `v1.6`）

**实现了什么**

| 项 | 落地内容 | 新增 / 改动 |
|---|---|---|
| OPT-08 | 电视端周报：7 天两段条形图 + 每日上限虚线 + 打卡 + 反复看 + 看到一半 | `data/ReportMath.kt`、`ui/WeekBarView.kt`、`ReportActivity.kt`、`activity_report.xml`、`res/values/colors.xml`（`ext_blue`）、`AndroidManifest`、`SettingsActivity`（顶部入口 + 开关） |
| OPT-08 埋点 | 「反复看」需要日期维度：每天一个 key 集合 `wplay_<day>`，点开记一次，只留 14 天 | `Extras.notePlayedVideo / playedDaysOn`、`PlayerActivity`、`Store.allProgress()` |
| OPT-10 | `schedule.rewardMinutes`（0 = 关闭，上限 120）：必看全完成 → 今天上限 +N 分钟，一天一次 | `data/RewardMath.kt`、`Schedule`、`Extras.bonusMinutes / grantBonus`、`GateKeeper`、`MainActivity`、`SettingsActivity` |
| 配置 | schema + 两个编辑器同步（`rewardMinutes` 输入框、`report` 默认开） | `docs/tv.schema.json`、`tools/static/index.html`、`tools/tvjson_simple.py` |

**两处与计划的偏差（都是有意为之）**

1. **奖励加时没用 `grantTemp`**。`grantTemp` 是「从此刻开始倒计时」的放行窗口，家长再点一次放行就覆盖它；
   孩子做完任务先去吃饭、晚上回来再看，那 10 分钟已经过期 —— 那叫抽奖不叫奖励。
   改成 `bonus_<day>`（当日额度），`GateKeeper.limit = dailyLimitMinutes + bonus`，
   与手动放行分开计数；只加额度不改时段，时段外奖励再多也进不去。
   `RewardMath.limitWith` 特意处理了「基础上限 0 = 不限制」：0 + 10 若当成 10 分钟，
   反而比不配置更严格。
2. **周报没有引图表库**。7 根柱子的需求用一个 200 行的 `WeekBarView` 就够了，
   MPAndroidChart 进来的方法数与内存对这台 2GB 电视不划算。

**几条实现期定下的规矩**

- **没有数据就显示空状态，不显示一排 0**：0 会被读成「孩子真的一分钟没看」，
  而实际可能只是今天刚装好。`ReportMath.isEmpty` 决定是传 7 行还是空列表给 View。
- **平均只按「有记录的天」算**：周一打开周报不该显示「平均 5 分钟」，那是被后面 6 个空白天拉出来的。
- **`ext_<day>` 夹到不超过 `watch_<day>`**：两个键分开存，被手改坏时条形图会画出负数段。
- **日期算术不用 `Calendar` 也不用 `java.time`**：前者要时区，后者在 minSdk 24 上要 desugaring。
  用 epoch day 的整数算法，跨月/跨年/闰年都能单测（周一 = 0 这个约定也一起测了）。
- **奖励只发一次、只升不降**：`reward_<day>` 标记 + `bonusAfter` 取大值，重绘不会 10→20→30 滚下去；
  `gateTotal == 0` 时不发 —— 否则「没有任务」等于「一秒完成」，等于天天白送时间。

**单测**：`ReportMathTest` 16 条 + `RewardMathTest` 8 条，累计 **90 条全绿**。

**实机验证：未跑（按约定，周一到周五白天不做实机安装测试）**。用例已登记在
[待实机验证清单](待实机验证.md)：周报空状态与数字对账、外部应用分段、反复看/看到一半、
奖励加时到账与 `1.6` 的版本号显示；连同 OPT-09 的 EX-24 一起，等晚上或周末一轮跑完。

### 2026-11-11 晚 · Wave 4 首项：OPT-17 异常落盘（`versionCode 8` / `v1.7`）

**实现了什么**

| 项 | 落地内容 | 新增 / 改动 |
|---|---|---|
| 纯逻辑 | `DiagMath`：文件名（`2026-11-11` → `tv-20261111.txt`）、按日期过期、**200 KB 整行滚动**、格式化（时间/级别/标签/栈帧）、去重指纹 | 新增 `data/DiagMath.kt` + `DiagMathTest` 22 条 |
| 落盘 | `Diag`：`record()` 追加到 `filesDir/diag/tv-yyyyMMdd.txt`（毫秒级、同步完成）；`tick()` 每 60 秒在后台合并上传 NAS；失败退避 5 分钟；播放中不传 | 新增 `data/Diag.kt` |
| SMB | `Smb.withIsolatedShare()`：一条独立短连接里做完「建目录 / 列目录 / 删 / 读 / 写」，超时只掐自己 | `data/Smb.kt` |
| 接线 | `KidApp.tick` 的空白 catch、`pullbackForGate`、`enforceKiosk`、`BootReceiver` 全部改为记一笔；`Thread.setDefaultUncaughtExceptionHandler` 接管崩溃（落盘后原样交回系统，不吞） | `KidApp.kt`、`BootReceiver.kt` |
| 入口 | 设置页「维护 → 诊断日志」：待上传条数 / 上次上传时间 / 最近几条 / 立即上传 / 清空 | `SettingsActivity.kt`、`Store.diagUploadedAt` |

**为什么不是「catch 里直接写 NAS」**（这条最重要）

心跳是 1 秒一次，而写 NAS 是一次网络往返：直接在 catch 里写，等于每秒在 UI 线程上等网络。
而且 NAS 不通、断网、会话过期这些情况，恰恰是最容易产生异常的时候 —— 那时候写不上去，
线索就没了。所以拆成两段：**本地先落盘（一定成功），后台再传（失败了就攒着）**。

**几条实现期定下的规矩**

- **日志代码自己绝不抛异常**：`record()` 整个套在 try 里，内部再出错也只 `Log.w`。
  查日志的代码把应用搞崩，是这类模块最典型的死法。
- **同一个坑 60 秒只记一条**：心跳一秒钟抛一次，不去重能把 200 KB 在十分钟内填满，
  真正的第一条反而被滚动丢掉。指纹取「标签 + 异常类 + 第一个栈帧」。
- **滚动必须整行丢**：截半个 UTF-8 中文字会留下读不懂的残句；丢过内容时在开头插一句
  `# 更早的日志已滚动丢弃`，让人知道这里断过。
- **字节上限按 UTF-8 算，不按字符数**：中文 3 字节/字，按 `length` 算会超三倍。
- **读失败 ≠ 文件不存在**：只有 `STATUS_OBJECT_NAME_NOT_FOUND / PATH_NOT_FOUND` 才当空文件，
  其它错误直接让这次上传失败、下次重试。否则网络抖一下就把 NAS 上攒了几天的日志整份覆盖掉。
- **只删自己起的文件名**：`logs` 目录里家长自己放的东西，日期解析不出来的一律不动。
- **崩溃兜底不吞异常**：写完日志原样调用前一个 handler —— 带着记错的账继续跑比崩掉更糟。

**单测**：`DiagMathTest` 22 条，累计 **112 条全绿**。

**实机验证：未跑（按约定，周一到周五白天不做实机安装测试）**。用例已登记在
[待实机验证清单](待实机验证.md)：造一次 `tick` 异常看本地文件与 NAS 归档、断网时退避与不丢、
`logs` 目录自动创建、7 天清理、设置页「诊断日志」对话框。

### 2026-11-11 夜 · Wave 4 第二项：OPT-16 文案收口（`versionCode 9` / `v1.8`）

**实现了什么**

| 项 | 落地内容 | 规模 |
|---|---|---|
| 资源 | `res/values/strings.xml` 从 1 条 → **223 条**，按「通用 / 概览 / 时间与时长 / 规则 / 维护 / 系统 / 关于 / 对话框 / 锁屏页 / 家长工具」分组，带分隔注释 | 资源 223 条 |
| 设置页 | `SettingsActivity` 全部界面文字改走 `getString`，含时段预设、上限/奖励对话框、关于框、记录备份、诊断日志 | 193 处 |
| 锁屏页 | `LockActivity`：今日已看、卡片提示、名画缺图、临时放行 PIN 框 | 17 处 |
| 家长工具 | `ParentTools`：PIN 框、选应用、退出桌面、未同步冲突框（object 无 Context，用 `activity.getString`） | 26 处 |
| 守卫 | 新增 `tools/check_strings.py` + `tools/string_budget.json`：按文件记基线，新增中文硬编码就返回 1 | 271 处基线 |

**文案一个字都没改**（这是这次唯一的验收底线）。做法是「对照表驱动 + 逐条断言命中」：
迁移脚本里每条都写明原文与期望命中次数，跑完必须全是「命中」，最后再用另一个脚本做双向比对 ——
把旧文件的字面量（`${...}` 归一成 `%`）与新资源值（`%1$d` 归一成 `%`）互相比，
未匹配项只能落在「跨行拼接被合并成一条」这类已知情形上，逐条核对过。

**两处与计划的偏差**

1. **`footer` 改成 `by lazy`**。它原来是个类级 `val`，直接写 `getString` 会在构造期取 Context ——
   那时 Activity 还没 `attach()`，必崩。`by lazy` 让它第一次用到（onCreate）时才求值。
2. **两个默认参数改成可空**。`save(label: String = "设置")` 与 `askPin(title: String = "家长选项")`
   的默认值不能用 `getString`（默认参数在调用方求值，那里没有 Context）。改成 `String? = null`，
   在使用处兜底 `?: getString(...)`。

**几条实现期定下的规矩**

- **教学数据不进资源**：`data/builtin/`（唐诗 / 课文 / 单词 / 名画）是**数据**，塞进 `strings.xml`
  等于把资源文件变成第二个数据源，改内容还得重新编译。`check_strings.py` 整目录跳过。
- **日志不进资源**：`Log.*` 的实参保持中文，它不上界面，进资源只会让排障时多绕一层。
- **`全天 / 全天不限` 既是显示文字又是解析取值**：原来 `parseWindows` 拿字面量比对，
  文案一旦改进资源就会失配。这次把比较也改成 `getString(...)`，两处口径合成一处。
- **`关闭` 拆两个键**：`common_off`（开关状态值）与 `common_close`（对话框按钮）文字相同、语义不同，
  分开放，以后要改按钮措辞不会连带把开关值改掉。
- **不给文案加开关**：文案是基础设施，不是 feature flag，出错时不能在 `features` 里关掉它。

**守卫怎么用**

```bash
python tools/check_strings.py            # 检查（超基线返回 1，可直接挂 CI）
python tools/check_strings.py --list     # 列每一处的文件与行号
python tools/check_strings.py --update   # 认账：把当前值写成新基线
```

**剩下的 271 处**（已写入基线，只许降不许升）：`MainActivity` 55、`Badges` 30（勋章名，纯 UI）、
`PlayerActivity` 16、`ReportActivity` 16、`EpisodesActivity` 5 是界面侧主要头寸；
`Diag` / `StateBackup` / `ReportMath` / `PlaylistWriter` 那些是**回给 UI 显示的消息**，
要收就得把返回值改成 `@StringRes + args`（数据层不能拿 Context），单独排一项更合适。

**验证**：单测 **112 条全绿**；`assembleDebug` 通过；`aapt2 dump resources` 核对过编译后的
`%1$s / %2$d` 与换行都没走样；`check_strings.py` 自测（故意加一句硬编码 → 返回 1，删掉 → 通过）。

**实机验证：未跑（按约定，周一到周五白天不做实机安装测试）**。用例已登记在
[待实机验证清单](待实机验证.md)：设置页 / 锁屏页 / 家长工具逐屏过一遍（重点看有没有丢字、
折行、占位符没填），页脚版本号显示 `v1.8(9)`。

### 2026-11-11 晚 · Wave 4 第三项：OPT-18 设置页拆分（`versionCode 10` / `v1.9`）

**实现了什么**

| 项 | 落地内容 | 规模 |
|---|---|---|
| 外壳 | `SettingsActivity` 只留布局、列表装配、写回、焦点保持；新增 `SettingsHost` 定下分组能用到的四件事（`save` / `refresh` / 底部占位 / 文案） | 1094 → **256 行** |
| 分组 | `OverviewGroup`（周报 / 未同步重试）、`TimeGroup`（上限 / 时段 / 开关 / 奖励）、`RulesGroup`（PIN / 通过率 / 应用）、`MaintGroup`（放行 / 清记录 / 备份 / 日志 / 重载 / 退出）、`SystemGroup`（7 个开关 / 时钟 / 版本） | 5 个文件，872 行 |
| 列表 | `Entry`（分组 / 行）+ `SettingsGroup` 接口 + `SettingsAdapter` 独立成文件 | 145 行 |
| 弹窗 | `darkDialog()` / `inputField()` / `confirmDialog()` / `pickDialog()`：原先手抄四五份的输入框样式只剩一份 | 65 行 |

**三层分工（这是这次拆分唯一要守的东西）**

- **分组只产出 `Entry`**：某一行叫什么、点了弹什么框、改完写哪个字段。它不知道列表怎么渲染。
- **外壳只管落盘与显示**：`save()` 的「写成功才更新本机」、失败挂红色错误条、`busy` 占位都在一处，
  任何分组都绕不开 OPT-07 定下的这条规矩。
- **弹窗工具箱只有样式**：深色主题、20sp、留白，不含任何业务判断。

**行为和文案零变化怎么保证**

1. 先做 `R.string.set_*` 的**使用点对齐**：把旧文件与新文件里所有 `R.string.xxx` 抓出来按次数比对，
   201 处逐条相等。这一步最便宜也最有效 —— 「漏搬一行」「同一行搬了两遍」都能立刻看出来。
2. 行的**顺序**按分组物理顺序对回原来的 30 行：概览 → 未同步重试 → 时间 → 规则 → 维护 → 系统。
3. 单测 112 条不变、全绿；`assembleDebug` 通过；`aapt2` 核对 `versionCode 10 / versionName 1.9`。

**两处顺带的改动（都不改显示文字）**

- 「关于」框里的 `开 / 关` 两个短词收进资源（`word_on` / `word_off`，资源 223 → **225 条**）。
  原先它写在字符串模板里（`"${if (...) "开" else "关"}"`），守卫的正则抓不到这种嵌套引号，
  所以一直是「看不见的硬编码」。搬完设置页只剩一条「时段时间正则」被计为硬编码。
- `tools/string_budget.json` 重新认账：总数仍 **271**，只是那 1 处正则从 `SettingsActivity.kt`
  记到了 `settings/TimeGroup.kt`。

**没做的事（有意为之）**

- **没有顺手把 `parseWindows` 抽成纯函数**。它确实该进 `data/*Math` 并配单测（OPT-15 的清单里就有
  「时段解析与跨零点」），但那样要连「全天」这种显示文字与解析取值一起搬 —— 属于行为边界改动，
  不该混进「零变化」的重构。留给 OPT-15。
- **没有把分组做成可配置顺序**。电视上的顺序是产品决策，不是配置项。

**遗留**：`TimeGroup` 仍 312 行（4 个对话框 + `schedule` 段写回都在里面）。如果后面还往时段上加能力
（例如按星期几不同），再按「上限 / 时段 / 奖励」拆二级文件。

**验证**：单测 **112 条全绿**；`assembleDebug` 通过；APK 26.6 MB / `v1.9(10)`；
`python tools/check_strings.py` 通过（271 处基线，设置页 0 处界面硬编码）。

**实机验证：未跑（按约定，周一到周五白天不做实机安装测试）**。用例已登记在
[待实机验证清单](待实机验证.md)：五个分组逐项点一遍、改动是否真的写回 `tv.json`、
改完焦点是否还停在原行、页脚显示 `v1.9(10)`。

---

### 2026-11-11 夜 · Wave 4 第四项：OPT-15 CI + 时段解析单测（零版本号变更，APK 仍 `v1.9(10)`）

| 项 | 落地内容 | 新增 / 改动 |
|---|---|---|
| CI | `.github/workflows/android.yml`：`strings` job（`check_strings.py`，半分钟）与 `unit-test` job（`testDebugUnitTest assembleDebug` + 用例数汇总 + 失败上传测试报告）；`concurrency` 取消同分支的旧任务 | 新增 `.github/workflows/android.yml`、`.gitattributes` |
| 时段解析 | `WindowMath`：解析 / 显示 / 写回 / 紧凑四种转换，全部纯 JVM；`TimeGroup` 只剩两行包装 | 新增 `data/WindowMath.kt`、`WindowMathTest.kt`；`settings/TimeGroup.kt` 净减约 30 行 |
| 权限 | `gradlew` 在 Git 里是 `100644`（Windows 勾不出可执行位），Linux runner 上 `./gradlew` 报 `Permission denied` | `git update-index --chmod=+x gradlew` → `100755` |

**为什么这次不发新版本号**：代码侧唯一的运行时改动是「全角减号 `－` 也算时段分隔符」，
用户可见行为与 `v1.9(10)` 一致；再发一版只会让待实机清单里那条「页脚应该是 `vX`」又飘一次。

**两个只在 Linux 上暴露的坑**
1. **`gradlew` 权限位**：Windows 的文件系统不记录可执行位，本机永远跑得通，到 runner 上必挂。
   修两处 —— 仓库改 `100755`，workflow 里再加一句 `chmod +x` 兜底（历史克隆的旧工作区不会自动拿到新权限位）。
2. **workflow 的行尾**：`run:` 是 bash 脚本，行尾带 `\r` 会报 `$'\r': command not found`，
   而且报错位置毫无提示性（看起来像是某条命令本身有问题）。加 `.gitattributes` 把 `*.yml` / `*.sh` /
   `*.py` / `gradlew` 钉死为 LF；**故意没写全局 `* text=auto`** —— 那会让所有历史文件在下次检出时变行尾，
   一次提交冒出几千行「只是换行符」的假改动，diff 就没法看了。

**为什么 `Schedule.parse` 没上单测**：它读 `org.json`，纯 JVM 单测里 `org.json` 是桩（`RuntimeException: Stub!`）。
可拆，但要动 `Schedule` 的公开 API，收益不抵风险。现在的覆盖是两头：
`WindowMath.parse`（家长输入框那条路）与 `TimeWindow.contains`（判断「现在能不能看」那条路）；
中间只剩「JSON 数组 → 时间窗」这一小段没测（三个分支：字符串 / 对象 / 数组），已在 PRD 第 15 节记为未覆盖。

**顺带钉住的四条边界**（都是真实会遇到的输入）
- `18-20`（只写小时不写分钟）→ **`null`**，提示格式不对、**不写回**。若误判成「全天」，家长以为设了 18:00–20:00，
  实际孩子全天可看 —— 这是本节最贵的一条单测。
- `22:00-01:00` 真的跨到凌晨（`00:30` 可看、`01:00` 整不可看）。
- 起止相同（`22:00-22:00`）按全天算，不是「一分钟都不许看」。
- `18：00－20：00`（中文输入法的全角冒号与全角减号）也认。

**验证**：单测 **130 条全绿**（新增 18 条）；`assembleDebug` 通过；APK 26.7 MB / `v1.9(10)` / 构建时间 11-11 18:30；
`python tools/check_strings.py` 通过（271 处基线，唯一变化是那条时段正则从 `TimeGroup.kt` 记到了 `WindowMath.kt`）。
workflow 先做静态校验：YAML 能被解析、5 段 `run:` 脚本逐个 `bash -n` 通过、用例汇总脚本喂真数据与空数据各跑一次。
推送后第一次真跑（run `43004642083`）：`文案守卫` **34 秒绿**，`单测与打包` 在**编译阶段**失败 ——
原因不是 workflow 写错，而是**仓库本身不能独立构建**，见下一小节。
修完再推（run `43006461940`，commit `9945233`）：**两个 job 全绿**，
`单测与打包` 完整走过 checkout → JDK 17 → Gradle 缓存 → SDK 34 → `gradlew` → 用例数汇总 → 上传报告。
顺手补验了守卫的另一半：临时放一个含中文的 `TmpCiProbe.kt`，`check_strings.py` 返回 1 并点名该文件；
删掉后返回 0 —— 说明「CI 绿」不是因为守卫瞎了。

**第一次跑 CI 就红了，但红得值**

- 现象：`unit-test` job 在「跑单测并构建调试包」这步失败，`文案守卫` 正常。
- 原因：`data/LocalSecrets.kt` 被 `.gitignore` 排除（里面是明文 NAS 账号密码），
  而 `Store.kt` 直接引用 `LocalSecrets.USER / PASS` 当首次启动的默认账号。
  本机有这份文件所以永远编得过，**任何干净检出都必然 `Unresolved reference: LocalSecrets`**。
- 讽刺的是仓库里一直有一份 `docs/LocalSecrets.example.kt.txt`，要求「复制 → 去掉说明 → 填密码」，
  三步都能忘，而且忘了只在别人机器上炸。

改法（`versionCode` 不变，仍是 `v1.9(10)`）：

| 之前 | 现在 |
|---|---|
| gitignore 掉的 `LocalSecrets.kt` 里写 `const val USER / PASS` | `LocalSecrets.kt` **进 Git**，只读 `BuildConfig.NAS_USER / NAS_PASS` |
| — | `app/build.gradle.kts` 从 `local.properties` 的 `nas.user` / `nas.pass`（或环境变量 `NAS_USER` / `NAS_PASS`）生成 `buildConfigField` |
| 缺文件就编不过 | 两边都没有时是空串，照样编译；`LocalSecrets.isConfigured` 可判断有没有配 |

顺带删掉过时的 `docs/LocalSecrets.example.kt.txt`，并把 README / `CONFIG.md` / `tv.schema.json` /
`examples/tv.json` 里「用编译时 `LocalSecrets.kt` 里的账号」这类说法统一改成
「`BuildConfig.NAS_USER / NAS_PASS`」。

**怎么验证的**（不必真推两遍）：把 `local.properties` 换成只剩 `sdk.dir`，
`./gradlew :app:compileDebugKotlin --rerun-tasks` 编过且 `BuildConfig.NAS_USER` 是空串；
还原后又跑一遍 `testDebugUnitTest assembleDebug`，130 条全绿、`NAS_USER` 回到真值。

**留下的规矩**：**「本机能编过」≠「仓库能编过」**。任何被 `.gitignore` 排除却被源码 `import` 的文件，
都会在第一次 CI 上现形。以后的本机私有配置一律走 `local.properties` / 环境变量 → `BuildConfig`，
源码里不留对 gitignore 文件的引用。

---

### 2026-11-11 夜 · Wave 4 第五项：OPT-19 家长工具收敛（零 APK 改动，APK 仍 `v1.9(10)`）

| 项 | 落地内容 | 文件 |
|---|---|---|
| 统一入口 | 双击根目录 `小剧场.bat` → 打开 `http://127.0.0.1:8700/` 启动页 | 新增 `小剧场.bat` |
| 启动页 | 三张模式卡片：名称 / 适合谁 / 端口 / 状态灯 / 按钮，每 15 秒自动刷新 | 新增 `tools/static/launcher.html` |
| 服务 | 只监听 127.0.0.1；`GET /api/status`（并行探活三端口）、`POST /api/launch {mode}`（白名单拉起）、`GET /api/ping`，另有命令行 `--self-test` 自检 | 新增 `tools/launcher.py` |
| 纯逻辑 | 模式表 / 探活 URL 与响应解析 / 命令构造 / URL 拼接 / 状态文案，全部不碰 socket、subprocess、平台 API | 新增 `tools/launcher_math.py` |
| 单测 | 28 条，覆盖白名单与防注入、共用后端、探活坏响应、命令参数、访问码转义、状态机 | 新增 `tools/test_launcher.py` |
| CI | 第三个 job `python-tools`：unittest + `--self-test` + py_compile，Linux runner 上不连 NAS、不开浏览器 | `.github/workflows/android.yml` |

**为什么是「一个启动页 + 三个模式」而不是合并成一个工具**：ROADMAP 这一项写的是「先做」。
家长真正的痛点不是功能少，而是**桌面上三个 bat、三个端口、不知道该双击哪个**，
以及 `console.py` 与 `desktop.py` 一起开会撞 8765。所以这一轮只收敛入口：
启动页负责「探测在不在跑 → 按白名单拉起 → 跳转」，拉起之后浏览器**直达原服务端口**，
启动页不代理流量、不重写界面、不碰 `tv.json`。**三个既有工具一行未改**，
三个原 bat 也全部保留，行为不变（用惯了的人不受影响）。

**三个模式其实是两套后端**：`console.py` 和 `desktop.py` 共用 8765 上的同一套后端与
`static/index.html`，PC 助手只是加了 `desktop.html` 导航壳 + 独立窗口；`tvjson_simple.py` 是独立的 8787。
启动页把这个关系直接写在页面顶部，并在 `launch()` 里判断：共用后端的另一个已在跑时
**只「打开」不重复拉起**（`launcher_math.shared_backend`），否则第二个进程必然 `OSError: 端口被占用`。

**开发中发现并修掉的两个真 bug**（都不是靠单测拍脑袋想到的，是实跑出来的）：
1. `probe()` 用了 `probe_url()` 的默认端口，而 `launch()` 拉起时用的是 `port_of()` 覆盖后的端口 ——
   一旦 `--port` / 环境变量换端口，探活永远打在旧端口上，把已经起来的服务误判成「没起来」，
   然后重复拉起撞端口。已统一走 `port_of()`。
2. `/api/status` 原本串行敲三个端口，最坏 3 × 1.5 秒 = 4.5 秒，启动页每 15 秒刷新会被拖住，
   `--self-test` 直接超时报 FAIL。改成三线程并行，最坏 1.5 秒。

**一处刻意的边界**：探活 `tvjson_simple.py` 的 `GET /` 会拿整页 HTML（约 90 KB）。
客户端若只读前几 KB 就断开，既有工具的 `_send` 只捕获 `BrokenPipeError / ConnectionResetError`，
Windows 下会抛 `ConnectionAbortedError` 刷一屏红字。按「三个工具一行不改」的约束，
**没有去改它**，而是在启动器侧：① 探活 `r.read()` 读完整个响应；② 子进程 stdout/stderr
收进 `.tmp/launcher-logs/<mode>.log`（同时设 `PYTHONIOENCODING=utf-8`，中文横幅不乱码），
启动失败时把日志尾巴回显到页面；③ 冷启动第一次探活超时放宽到 3 秒。
副作用是横幅不再出现在黑窗口里 —— 已在启动页底部写明，且页面链接自带访问码，不用抄。

**验证**：28 条 Python 单测全绿；`--self-test` 6 项全过；真实端到端（把极简编辑器拉到空闲的 8799，
`--no-browser`、不写 NAS）跑通「未启动 → 拉起 → 运行中 → 再点复用 → 非法模式拒绝」，
日志落盘且中文正常、stderr 零红字、测试子进程已全部停止、8799 已释放。
`tools/check_strings.py` 仍通过（Kotlin 一行未动，271 处基线不变）。
推送后 CI 实跑（run `43014539613`，commit `6a91681`）：**三个 job 全绿** ——
`文案守卫`、`单测与打包`（含 130 条 Android 单测与 `assembleDebug`）、新增的 `PC 工具单测`（28 条单测 / `--self-test` / `py_compile` 在 Ubuntu runner 上均通过）。
这一条也顺带验证了「启动器纯逻辑不依赖 Windows」：Linux 上不连 NAS、不开浏览器照样能测。

**第三个坑（真实双击才暴露）**：`小剧场.bat` 第一版按 README 惯例写成 UTF-8 中文 + LF，
结果 Windows cmd 用本地 GBK 代码页解析批处理时，中文 `echo` 行被当成命令，直接
`'先安装' 不是内部或外部命令` 退出 —— **文件能提交、自检脚本能过，但家长双击用不了**。
已改成**纯 ASCII + CRLF**（中文说明全在 HTML 启动页里，黑窗口只需要英文提示），
并用 `subprocess.run(['小剧场.bat', '--self-test'])` 真实跑了一遍 cmd 链路确认 exit 0。
**规矩：Windows 的 `.bat` 一律纯 ASCII + CRLF，别信「UTF-8 + chcp 65001 就行」** ——
`chcp` 是批处理执行到那一行才生效，前面的中文已经按启动时的代码页解析完了。

**明确没做（留给以后）**：三个工具的功能合并、启动页打包进 exe、把启动页做成常驻服务、
手机从局域网直接打开启动页（它只监听 127.0.0.1，是有意的安全边界）。

### 2026-11-11 夜 · Wave 4 后首轮实机（部分完成）+ 整机解码链路故障

按 `docs/待实机验证.md` 第 0 节约定，20:40 后首次上电视：备份 NAS 原字节 + 三份 prefs →
覆盖安装 `v1.9(10)` → 验锁屏页/主界面/设置页首屏/PIN/页脚/时段对话框 → 原字节还原（两轮，逐字节比对 OK）。
**通过的项与截图证据写在 `docs/待实机验证.md` 第 4 节**，这里只记三条对后续有影响的结论：

1. **首轮只是「部分完成」**：周报、奖励加时、诊断日志上传、写保护回退、`pm clear` 重用例、
   TC-58 实际输入、TC-59 真实桌面三张卡都还没跑，工作区 `小剧场.apk` 仍保持 `v1.4(5)`，
   不要把「代码完成」写成「验收完成」。
2. **页脚构建时间是 `v1.9(10) 11-11 18:45`**（`BuildConfig.BUILD_TIME`，随构建变），
   说明书/清单里旧的 `18:30` 已更正；以后别把构建时间写死进验收标准。
3. **11-09 的「所有视频黑屏」在无 scrcpy 的情况下复发**：本轮没跑 scrcpy、没调分辨率，
   优酷与小剧场仍同时黑屏，解码日志同样是 `setPortMode ... failed -1010` / `BadPortIndex`；
   `adb reboot` 后同一部片正常出画、时长正常入账。**不是 v1.9 回归**，是 MTK9652 整机共享视频链路，
   只有断电/重启能恢复（`persist.adb.tcp.port=5555` 保证重启后 ADB 自动回来）。
   说明书 11.1 已补「不碰 scrcpy 也可能复发」；排查时 `watch_<day>` 入账 + 音频活跃轨道比
   `pos_` 可靠（`pos_` 只在结束/退后台保存）。



### 2026-11-11 夜 · 第二轮实机（周报 / 五组设置 / TC-58）+ 两个对话框失败项

21:43~22:01 又跑了一轮，电视一直开着，首轮装的 `v1.9(10)` 未动、没再装包。
备份在 `.workbuddy/tmp/backup-20261111-2145/`，**通过项、截图证据、还原细节全部写在
`docs/待实机验证.md` 第 5 节**，这里只记三条对后续有影响的结论：

1. **周报与设置页实机通过**：TC-48 数字对账 86 分与 prefs 一致（口径是
   `watchMsOn = max(watch_, daymax_)`，11/10 的 daymax 16.79 > watch 15.16）；7 柱视觉、未来轨道、
   今天描边、空态文案全部正常。设置页五组 24 行 + 「关于」对话框逐屏看过，无裁切/串行。
   TC-58 的 ASCII 正反两项实机通过：`18-20` 被拒且 NAS 逐字节不变、`1800-2000` 正确写回
   `18:00-20:00` 后已按备份还原并确认本地缓存无残留。中文/全角输入因 `input text` 注入不了，仍靠 18 条单测。
2. **新发现两个验收失败项（不是文档笔误）**：`MaintGroup.kt:138 showStateBackup()` 与
   `:194 showDiag()` 都用 `setMessage + setItems`，在这台电视的 `THEME_DEVICE_DEFAULT_DARK` 上
   items 的 ListView 被 message 的 ScrollView 挤掉，实机只有正文 + 「关闭」——
   「立即备份到 NAS / 从 NAS 恢复记录」「立即上传 / 清空本地日志」四个操作项点不到，家长入口失效。
   同轮只有 `setItems` 的列表框（时段预设、临时放行）全部正常，根因明确。
   修法建议改成三个按钮或自定义 View，改完需重新构建装包回归。**修好之前 OPT-09 / OPT-17 的
   家长手动入口不能算验收通过**，清单里两行已标「失败」。
3. **还原边界写清了**：`state.json` 是 App 自动维护的正常产物，收尾按原字节写回即可、
   **不要删**（首轮删过又自动生成）；`curated_prefs.xml` 的四个自动时间戳会随 App 重启推进，
   不必追求逐字节一致，只要没有测试键/测试值。本轮 NAS 三文件原字节一致、
   `tempUntil` 回到 `1794353631858`、前台回到 `LockActivity`；工作区 `小剧场.apk` 仍 v1.4(5)。

### 2026-11-11 夜 · 第三轮实机：两个失败项修复 + 家长选项菜单可达性

22:12~23:05 又跑一轮（>18:00，符合 `docs/待实机验证.md` 第 0 节）。
用户要求「bug 和测试都今晚一起搞完」，本轮**改代码、构建、装包、实机回归、还原、文档一次做完**。
细节、截图编号、配置与还原证据全部在 `docs/待实机验证.md` 第 6 节，这里只记结论：

1. **两个验收失败项清零**：`SettingsDialogs.kt` 新增 `actionDialog()`（自定义 View：
   说明在固定高度 ScrollView、动作是竖排可聚焦 Button），`MaintGroup` 的「记录备份」「诊断日志」
   改用它。实机三个按钮可见、可聚焦、能干活：立即备份写入 `state.json`、恢复提示「本地记录已经是最新的」、
   立即上传/清空本地日志 Toast 正确。**纯 `setMessage` 的确认框正常，根因确认是 message 与 items 同用。**
2. **顺手发现并修掉第三个同款问题**：主界面「家长选项」11 项用 `setItems`，遥控器只能到第 8 项，
   「临时放行」「退出到桌面」滑动才看得见。改成 `actionDialog` 的 `scrollActions`（按钮区也进 ScrollView），
   重构建装包后方向键 10 步走到末项并高亮、「关闭」常驻。**全项目排查过，没有第四处同款组合。**
3. **版本**：`v1.9.1(11)`，`BUILD_TIME` 22:19 / 22:49 两次构建；Android 单测 130、PC 单测 28、
   文案守卫 270 处基线（比原来少 1，只降不升）。工作区 `小剧场.apk` 仍是 `v1.4(5)`，**没发版**。
4. **顺带跑通的用例**：TC-50/TC-51 奖励（首页 `50/60 分钟（含奖励 10 分钟）`、`bonus_=10`、
   上限 0 不产生限额）、EX-25 不重复发、TC-52/TC-53 上传链路（`logs/` 首次自动创建、
   NAS 与本地探针逐字节一致）、TC-55 守卫拦截、家长工具菜单文案。
   **EX-25 清单原写「额度变 +20」是错的**：实现是一天只发一次，改配置后额度保持 +10，已更正通过标准。
5. **还原**：NAS 三文件按 manifest 原字节写回并回读一致；`logs/` 探针产物删除；
   三份 prefs 无测试键值、`tempUntil` 与 `watch_` 回到基线、`kid_extras.xml` 逐字节一致；
   前台回 `LockActivity`。清单目前 30 条：**通过 10 / 部分通过 11 / 失败 0 / 待验证 9**。
   剩下的多是 `pm clear`、周报空态、断网这类破坏性或要构造环境的用例，按约定留周末。

### 2026-11-11 深夜 · PR #1（`v1.4` 时代旧分支）评估与真缺口移植（`v1.9.2(12)`，**未上电视**）

`fix/smb-watchdog-sha256-ui-async` 基于 tag `v1.4`（`d85607e`）的 1 个提交，main 已前进 14 个提交，
直接 merge 会把 Wave 3/4 的工作整片退回去，冲突面覆盖 12 个文件。逐条比对「现在还需不需要」后按下面处理：

| PR #1 里的改动 | 现在还需要吗 | 处理 |
|---|---|---|
| `PlaylistWriter` 用 SHA-256 取代 `contentHashCode()` | **需要**：main 仍拿 32 位哈希当「能不能覆盖 `tv.json`」的判据，撞一次就是抹掉家长的改动 | 移植：`RemoteCommand.sha256Hex()` + `PlaylistWriter.contentHash()`，`restorePending()` 兼容旧指纹 |
| `idempotencyKey` 兜底改内容 SHA-256 | 需要（同一处根因） | 移植 |
| 握手期 `pendingClient` / `pendingConnection` | **需要**：`client`/`connection` 只在握手全成功后才赋值，卡在 `connect()` 的那一次 `closeQuietly()` 手上没句柄，超时形同虚设 | 移植；同时去掉 `ensureConnected` / `shareNamed` 的对象监视器（卡在握手里会连监视器一起占着，看门狗根本走不到关 socket） |
| 隔离读写超时只关 `SMBClient` | 需要：不关 `Connection` 时阻塞中的读不保证抛得出来 | 三条隔离路径统一 `connHolder`（读 / 写 / `withIsolatedShare`） |
| 状态字段 `@Volatile` + 共享 map 换 `ConcurrentHashMap` | 需要（看门狗在另一线程读写） | 移植 |
| `ArtImages` daemon 线程 + 并发 `missing` | 需要：`HashSet` 只做部分加锁，`clear()` 与加载线程有竞态 | 移植 |
| `UiAsync`（协程调度 + 销毁检查） | 不需要 | **不采纳**：6 个 Activity 全是等价的 `Thread` + `runOnUiThread` 机械替换，现有回调已逐处判 `isFinishing`/`isDestroyed`；收益只是样式统一，却要动 6 个界面文件，而这轮没法上电视回归 |
| 线程池上限 16 + 拒绝策略 | 暂不需要 | **不采纳**：拒绝时要给家长一句中文提示，Data 层没有 `Context`，只能硬编码，撞 `check_strings.py` 的「只降不升」；超时真能掐断 socket 后挂死线程会自行退出，风险已大幅下降 |

**验证**：Android 单测 138（新增 8 条：SHA-256 标准向量、`"Aa"`/`"BB"` 同 `hashCode` 但指纹不同、
幂等指纹即内容 SHA-256，以及 `PlaylistWriter.baseMatches()` 的新旧指纹与空指纹五条）、PC 单测 28、文案守卫 270 处（与基线持平）、`assembleDebug` 通过
（`BUILD_TIME 11-11 23:20`）。**本轮没装包**：用户不在电脑/电视旁，`v1.9.2(12)` 只是构建产出，
电视上仍是 `v1.9.1(11)`；下次上电视装 12，并把 `待实机验证.md` 第 7.2 节三条待验跑掉。

**PR #1 处置**：本机没有 `gh` 也没有 token，无法代为评论或关闭。分支保留作历史参考，
建议在网页上直接 close —— 有价值的部分已按上表吸收，剩下的两条是上面写明的不采纳项。

### 2026-11-12 晨 · `v1.9.2(12)` 上电视（7.2 三条全通过）+ 修掉「备份失败每秒重连」（`v1.9.3(13)`）

第 7.2 节三条一次性跑完，结论与证据在 [`待实机验证.md` 第 8 节](待实机验证.md)：

| 项 | 结果 |
|---|---|
| SMB 超时能收回现场 | **通过**：黑洞地址下冷启动 5 秒失败并用缓存片单；设置页改一项 7 秒内出警告条、本机不改值；界面全程可操作；还原地址后不重启即自动重连 |
| 旧指纹兼容的一次重放 | **通过**：`v1.9.1(11)` 写保护造失败 → `unsynced_base=1124651156`（32 位）→ 升 12 → 点「覆盖 NAS」允许重放、NAS 值正确 |
| 连续同步不抖 | **通过**：10 分 38 秒、4 轮进出页面，ANR 0、备份成功 6 次；1 次 `DiskShare has already been closed` 由 `openRetrying` 0.4 秒内自愈，用户无感 |

**新发现的缺陷（不在原计划里）**：NAS 不可达时 `StateBackup` 退化成**每秒**重连一次 ——
`tick()` 拿「上次成功时间」当节流依据，失败时那个时间不动，5 分钟阈值恒成立。
修法是新增 `data/BackoffMath.retryDelayMs(streak)`（5 分钟起、每次 +5 分钟、30 分钟封顶），
`StateBackup` 另记 `lastTryAt` / `failStreak`，明确请求（`pending`）不受退避限制。
实机复测：黑洞下 3 分钟 2 条（修复前 30 秒 3 条），日志带「连续 N 次，X 分钟后再试」。

**验证**：Android 单测 147（138 + `BackoffMathTest` 9 条）、PC 单测 28、文案守卫 270 处（持平）、
`assembleDebug` 通过。NAS 三文件 prefs 三份按原字节还原，无测试残留。

### 2026-11-12 晨（续）· EX-27 验掉 + 修「远端日志从头部读」（`v1.9.4(14)`）

EX-27 的构造是往 NAS 塞 1.4 MB 的 `logs/tv-<今天>.txt`。第一次跑（`v1.9.3(13)`）不合格：
文件滚到 200 KB、标记载对了、`keepme.txt` 也没被动，但里面留了一行半行残句 `… W/probe �`，
而且保留下来的是中段旧内容 —— 较新的那几千条被永久丢掉。

根因在读取侧：`Diag.readText()` 上限 400 KB，**从文件头部读**、超限直接 break。截断点落在任意字节上，
于是末尾多出半行；而 `DiagMath.append()` 的「从最新往回装」装的是这段被截断内容的尾巴。

修法：文件大于上限时只读尾部（`size - limit` 起），并用新增的 `DiagMath.trimLeadingPartial()`
丢掉因此不完整的首行。复测（`v1.9.4(14)`）：204,760 字节 ≤ 200 KB、标记 1 次、无 `�`、
最新一条留住、保留的是第 10363~11999 条（最后一段）而不是中段。

**验证**：Android 单测 150（+3）、PC 单测 28、文案守卫 270 持平、`assembleDebug` 通过；
`logs/` 与其中的测试文件测完全部删除，NAS 三文件仍按原字节。

### 2026-11-12 晨（再续）· TC-46 验掉 + 修「冷启动补用量判据用错层」（`v1.9.5(15)`）

TC-46（只清本地 `watch_<day>`，看能否从 NAS 补回）本来排在「破坏性、留周末」，但它只动本地一个键、
随时可补回，电视在线就能跑。第一次跑**不通过**：NAS 上明明有 `watch_2026-11-12 = 18522`，
清掉本地该键后冷启动，本地不长键、首页仍「今天 0/50 分钟」、`StateBackup` 一条日志都没有。

根因是判据用错层：`autoMergeUsage()` 的第一道门 `if (Extras.watchMsToday() > 0L) return 0`，
而 `watchMsToday()` 取的是 `max(watch_<day>, daymax_<day>)`。`daymax_<day>` 是当日用量的防篡改下限，
`watch_` 被清掉之后它照样顶着原值（18522），于是这道门恒成立、函数在第一个 `if` 就静默返回。
首页「0/50」则是因为 18522 ms 不足 1 分钟被截断 —— 两个假象叠起来，很像「功能根本没写」。

修法：新增 `Extras.watchRecordedToday()`（只读 `watch_<day>` 原值）与
`StateMerge.shouldMergeUsage(recordedTodayMs)`（纯函数、可单测），日志里的 `before/after`
也换成记录原值 —— 原来用带下限的值，即使真补回来了也打不出那条日志。

复跑通过：先让 App 自己把 18522 备到 NAS（启动 → 按 HOME 触发 `nudge("background")`），
再清本地键冷启动，日志 `冷启动已补回今日用量：0 → 18522 ms（13 个键）`，
prefs 出现 `watch_2026-11-12 = 18522`；再冷启动一次无任何日志（记录已在就不再读 NAS）。

**验证**：Android 单测 **151**（+1）、PC 单测 28、文案守卫 270 持平、`assembleDebug` 通过。
`tv.json` / `tv.backup.json` 本轮字节未动（回读 sha 与 06:30 备份一致）；`state.json`
只多了 `watch_2026-11-12=18522` 与 `savedAt` 前进，`stamps` 一致，保留。
「已超限开机直接锁屏」没造数（会同步抬 `daymax_`，属破坏性），留你在场。

### 2026-11-12 上午 · 按家长要求移除首页「继续看」（`v1.9.6(16)`）

家长明确要求去掉这个功能，不做成「开关默认关」——**代码一起删**，避免留一条永远不用的分支。

- **删代码**：`data/ContinueMath.kt` 与 `ContinueMathTest.kt` 整文件删除；`MainActivity` 去掉
  `buildContinueSection()` 与 render 里的插入逻辑，分区顺序恢复成「清单原样 + 应用分区最后」；
  `Store.recentPlayed()` 删除；`Model.Features` 去掉 `continueWatching` 字段与默认值。
- **删配置**：`docs/tv.schema.json` 去掉 `features.continueWatching`；两个编辑器
  （`tools/tvjson_simple.py`、`tools/static/index.html`）去掉复选框与默认值；设置页系统分组
  少一行（6 行 + 版本行）。当前 NAS 的 `tv.json` 本来就没有 `features` 段，**无需改配置**。
- **保留记录**：`Store.notePlayed()` 与 `played_<key>` 照旧写。周报的「看到一半」
  按 `playedAt` 排序（`ReportMath`），记录备份也把它当记录类键同步，删了会让周报少一列依据。
- **文案**：`strings.xml` 少 3 条（222 条）；`string_budget.json` 基线 270 → **269**（ContinueMath 那处中文没了）。
- **验证**：Android 单测 **143**（151 − ContinueMathTest 8）、PC 单测 28、`assembleDebug` 通过。
  实机 `v1.9.6(16)`：首页第一个分区就是片单第一个分区「今天要看的」；设置页系统分组
  后台拉回 / 外部应用计时 / 剩余时间预告 / 远程控制 / 自动连播 / 本周报告 六行 + 版本行。
  截图 `backup-20261112-0920/shots/96-home.png`、`96-system.png`。
