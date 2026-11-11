package com.family.curated.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 打卡与播放进度的 NAS 备份（OPT-09）。
 *
 * 为什么需要：卸载重装、恢复出厂、`pm clear` 都会把 `stamps` / `pos_` / `watch_` 清零。
 * 对家长来说「记录没了」只是无所谓，对孩子来说连续 30 天的打卡墙一夜消失是另一回事；
 * 更实际的是**今日用量归零等于白送一段额度**，这是 EX-13 那条绕过路径。
 *
 * 存哪：`data:curated/state.json`（和 tv.json 同目录、同共享）。独立文件，
 * 家长手改片单碰不坏记录，反过来也一样。
 *
 * 三个触发点（见 ROADMAP OPT-09）：退到后台、每 5 分钟、每日首次打卡。
 * 全部走 `PlaylistSync.submit()` 的同一条后台线程 + `Smb.writeIsolated()` 的独立短连接，
 * 不新增线程、不抢正在供货的那条 SMB 连接，播放中也不会把孩子卡住。
 *
 * 三条硬规矩：
 *  1. **本地一条记录都没有时绝不回写** —— 否则重装后自动备份会拿空数据冲掉 NAS 上的好备份；
 *  2. **用量自动合回、记录要问家长** —— 用量合回只会更严格；记录可能是家长刚故意清空的；
 *  3. **只升不降**（[StateMerge.merge]）—— 导入幂等，重复导、导一半断电再导都不会弄小。
 */
object StateBackup {

    private const val TAG = "StateBackup"

    /** 定时备份周期 */
    const val INTERVAL_MS = 5 * 60_000L

    /** 两次备份之间的最短间隔：防止「退到后台 + 打卡」在同一分钟内连写两次 */
    private const val MIN_GAP_MS = 45_000L

    private const val READ_TIMEOUT_MS = 10_000L
    private const val WRITE_TIMEOUT_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var busy = false
    @Volatile private var pending = false
    @Volatile private var pendingReason = ""

    /**
     * 上次真正发起备份尝试的时刻（成功、失败都算）。
     *
     * 不能拿 [Store.stateSavedAt] 当节流依据：那个只在**成功**时更新，
     * 失败后 5 分钟阈值会一直成立，tick 每秒都能进来 —— 实测 NAS 一断线就是每秒重连一次。
     */
    @Volatile private var lastTryAt = 0L

    /** 连续失败次数；成功一次清零。退避间隔见 [BackoffMath] */
    @Volatile private var failStreak = 0

    data class Result(val ok: Boolean, val message: String = "", val raised: Int = 0)

    // ---------- 路径 ----------

    private fun dirOfPlaylist(): String = Store.playlistPath.substringBeforeLast('/', "")

    fun statePath(): String =
        dirOfPlaylist().let { if (it.isEmpty()) "state.json" else "$it/state.json" }

    private fun share(): String = Store.playlistShare ?: Store.DEFAULT_PLAYLIST_SHARE

    /**
     * 用哪套 NAS 配置。
     *
     * 关键：`pm clear` / 卸载重装会把 `nas_*` 一键清空，`Store.nas()` 返回 null。
     * 而这时候正是最需要读备份的时刻 —— 所以必须和 `Repo.load()` / `PlaylistSync`
     * 一样退回 [Store.DEFAULT_NAS]（内置的地址与账号），否则「重装能恢复」就是句空话。
     */
    private fun nasCfg(): NasConfig = Store.nas() ?: Store.DEFAULT_NAS

    // ---------- 触发 ----------

    /** 由 KidApp 的 1 秒 tick 调用；内部自己判断该不该写 */
    fun tick() {
        val now = System.currentTimeMillis()
        val sinceLast = now - Store.stateSavedAt
        val due = pending || sinceLast > INTERVAL_MS
        if (!due || busy) return
        // 播放中只做「被明确请求」的那次（退到后台 / 打卡），定时备份等播完再说：
        // 小文件虽然走独立短连接，也没必要在孩子看得正顺的时候去动 NAS
        if (SmbPlaybackContext.playing && !pending) return
        // 失败退避：NAS 不可达时不要每秒重连一次。家长明确点的「立即备份」照做（pending），
        // 定时任务才受限 —— 反正这段时间里试也是白试
        val backoff = BackoffMath.retryDelayMs(failStreak)
        if (!pending && backoff > 0 && now - lastTryAt < backoff) return
        val reason = pendingReason.ifEmpty { "timer" }
        pending = false
        pendingReason = ""
        busy = true
        lastTryAt = now
        PlaylistSync.submit {
            try {
                exportNow(reason)
            } catch (e: Exception) {
                Log.w(TAG, "自动备份异常（已忽略）: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    /**
     * 插一个「尽快备份」的请求。退到后台、每日首次打卡、家长点了「立即备份」都会用。
     * 不直接写盘：写 SMB 会阻塞调用线程，而这个函数常在 onPause / 渲染路径里被调。
     */
    fun nudge(reason: String) {
        if (System.currentTimeMillis() - Store.stateSavedAt < MIN_GAP_MS) return
        pending = true
        pendingReason = reason
    }

    // ---------- 导出 ----------

    /** 立即备份一次（后台线程） */
    fun exportNow(reason: String): Result {
        val cfg = nasCfg()
        val snap = StateMerge.Snapshot(
            stamps = Extras.backupStamps(),
            longs = Extras.backupLongs(),
            savedAt = System.currentTimeMillis()
        )
        if (snap.isEmpty) {
            // 关键保护：空记录绝不覆盖 NAS 上的备份（重装后、恢复前就是这种状态）
            Log.i(TAG, "本地没有任何记录，跳过备份（不覆盖 NAS 上的备份，$reason）")
            return Result(false, "本地还没有记录，先不备份")
        }

        val json = encode(snap)
        val bytes = json.toByteArray(Charsets.UTF_8)
        return try {
            Smb.writeIsolated(cfg, share(), statePath(), bytes, WRITE_TIMEOUT_MS)
            failStreak = 0
            Store.stateSavedAt = System.currentTimeMillis()
            Store.stateSavedReason = reason
            Store.stateSavedBytes = bytes.size
            Log.i(TAG, "记录已备份到 NAS（$reason）：${StateMerge.summary(snap)}，${bytes.size} 字节")
            Result(true, "已备份", 0)
        } catch (e: Exception) {
            failStreak += 1
            // 日志里带上退避时间，下次查「为什么半小时才备份一次」时不用再翻代码
            val waitMin = BackoffMath.retryDelayMs(failStreak) / 60_000
            Log.w(TAG, "备份失败（$reason，连续 $failStreak 次，$waitMin 分钟后再试）: ${e.javaClass.simpleName}: ${e.message}")
            Result(false, "备份失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------- 导入 ----------

    /** 冷启动静默补回「用量」。返回补回来的键数量（0 = 没什么可补） */
    fun autoMergeUsage(): Int {
        // 今天的记录还在就说明不是「重装清零」，不必多读一次 NAS。
        // 判据必须是 `watch_<day>` 原值：`watchMsToday()` 带 daymax 下限，
        // 清掉记录之后仍然 > 0，会把这整段补回逻辑永久挡在门外（TC-46 实机踩到）
        if (!StateMerge.shouldMergeUsage(Extras.watchRecordedToday())) return 0
        val remote = readRemote() ?: return 0
        val usage = StateMerge.onlyUsage(remote)
        if (usage.isEmpty) return 0
        // 这里比的也必须是记录原值：用 watchMsToday() 的话，daymax 顶着原值，
        // 「补回来了」和「什么都没发生」算出来一模一样，日志会静默（同 TC-46）
        val before = Extras.watchRecordedToday()
        Extras.applyState(emptySet(), usage.longs)
        val after = Extras.watchRecordedToday()
        val n = usage.longs.size
        if (after > before) {
            Log.i(TAG, "冷启动已补回今日用量：$before → $after ms（$n 个键）")
        }
        return n
    }

    /** 家长确认后恢复打卡与进度（后台线程） */
    fun importNow(): Result {
        val remote = readRemote() ?: return Result(false, "NAS 上没有找到备份（state.json）")
        val local = StateMerge.Snapshot(Extras.backupStamps(), Extras.backupLongs())
        val merged = StateMerge.merge(local, remote)
        val raised = StateMerge.raised(local, merged)
        if (raised <= 0) return Result(true, "本地记录已经是最新的，不用恢复", 0)
        Extras.applyState(merged.stamps, merged.longs)
        Log.i(TAG, "已从 NAS 恢复记录：${StateMerge.summary(merged)}（$raised 项）")
        return Result(true, "已恢复 ${StateMerge.summary(merged)}", raised)
    }

    /** 问家长之前先看看 NAS 上到底有没有东西；读不到就静默不打扰 */
    fun probe(onDone: (StateMerge.Snapshot?) -> Unit) {
        PlaylistSync.submit {
            val snap = try {
                readRemote()
            } catch (e: Exception) {
                Log.w(TAG, "探测备份失败（忽略）: ${e.javaClass.simpleName}")
                null
            }
            main.post { onDone(snap) }
        }
    }

    /** 一键备份 + 恢复都走后台，界面只处理回调 */
    fun exportAsync(reason: String, onDone: (Result) -> Unit) {
        PlaylistSync.submit {
            val r = try {
                exportNow(reason)
            } catch (e: Exception) {
                Result(false, e.message ?: e.javaClass.simpleName)
            }
            main.post { onDone(r) }
        }
    }

    fun importAsync(onDone: (Result) -> Unit) {
        PlaylistSync.submit {
            val r = try {
                importNow()
            } catch (e: Exception) {
                Result(false, e.message ?: e.javaClass.simpleName)
            }
            main.post { onDone(r) }
        }
    }

    // ---------- 远端读写 ----------

    private fun readRemote(): StateMerge.Snapshot? {
        val cfg = nasCfg()
        val text = try {
            Smb.readAllIsolated(cfg, share(), statePath(), READ_TIMEOUT_MS)
                .toString(Charsets.UTF_8).trimStart('\uFEFF')
        } catch (e: Exception) {
            // 绝大多数时候是「还没备份过 / 家长删了」，不是错误
            Log.i(TAG, "没有读到 state.json（${e.javaClass.simpleName}）")
            return null
        }
        return decode(text)
    }

    // ---------- JSON ----------
    //
    // 只在这里用 org.json（在设备上可用、在纯 JVM 单测里是桩），
    // 所以所有取舍/校验逻辑都放在 StateMerge，单测只测那一层。

    private fun encode(s: StateMerge.Snapshot): String {
        val o = JSONObject()
        o.put("version", StateMerge.VERSION)
        o.put("savedAt", s.savedAt)
        o.put("savedAtText", android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", s.savedAt))
        o.put("note", "电视自动备份的打卡与播放进度，重装后可恢复；删掉也没关系，会重新生成")
        o.put("stamps", JSONArray(s.stamps.toList().sorted()))
        val longs = JSONObject()
        s.longs.toSortedMap().forEach { (k, v) -> longs.put(k, v) }
        o.put("longs", longs)
        return o.toString(2) + "\n"
    }

    private fun decode(text: String): StateMerge.Snapshot? {
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "state.json 不是合法 JSON，忽略本次（不影响其它功能）")
            return null
        }
        val ver = o.optInt("version", StateMerge.VERSION)
        if (ver > StateMerge.VERSION) {
            Log.w(TAG, "state.json 版本 $ver 比本版 App 新，忽略（避免误读新字段）")
            return null
        }
        // 缺字段、类型写错都当空处理：备份文件坏了顶多恢复不出来，不能让 App 崩
        val stamps = ArrayList<String>()
        val arr = o.optJSONArray("stamps")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isNotBlank()) stamps.add(v)
            }
        }
        val longs = LinkedHashMap<String, Long>()
        val obj = o.optJSONObject("longs")
        if (obj != null) {
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = obj.optLong(k, Long.MIN_VALUE)
                if (v != Long.MIN_VALUE) longs[k] = v
            }
        }
        return StateMerge.Snapshot(
            stamps = StateMerge.sanitizeStamps(stamps),
            longs = StateMerge.sanitizeLongs(longs),
            savedAt = o.optLong("savedAt", 0L)
        )
    }

    /** 设置页显示用：上次备份多久以前 */
    fun lastSavedText(): String {
        val at = Store.stateSavedAt
        if (at <= 0L) return "还没备份过"
        val min = (System.currentTimeMillis() - at) / 60_000L
        return when {
            min < 1 -> "刚刚备份"
            min < 60 -> "${min} 分钟前备份"
            min < 60 * 24 -> "${min / 60} 小时前备份"
            else -> "${min / (60 * 24)} 天前备份"
        }
    }
}
