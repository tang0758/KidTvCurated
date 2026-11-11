package com.family.curated.data

import android.util.Log
import org.json.JSONObject

/**
 * 从电视上改清单（家长控制里的设置项）。
 *
 * tv.json 是唯一事实来源：网页编辑器、电视端设置改的是同一份文件，
 * 这样两边永远一致，也不会出现「电视上改完、下次同步又被清单覆盖」的怪事。
 *
 * 写之前一定先读远端最新内容再改字段 —— 不能用本地缓存的文本覆盖，
 * 否则家长刚在电脑上加的条目会被电视这边抹掉。
 *
 * OPT-07：写失败时不能只弹一个 Toast 就算了。返回 [Result.pendingText]
 * （当时本想写入的完整 tv.json）与 [Result.baseHash]（失败前读到的远端指纹），
 * 设置页据此挂红色错误条 + 标 `Store.hasUnsyncedEdit`，家长可以稍后原样重放。
 */
object PlaylistWriter {

    private const val TAG = "PlaylistWriter"

    /** 计算远端清单内容的 SHA-256 指纹，避免 32 位多项式 hashCode 碰撞导致误判未修改 */
    fun contentHash(bytes: ByteArray): String = RemoteCommand.sha256Hex(bytes)

    data class Result(
        val ok: Boolean,
        val message: String = "",
        /** 失败时本想写入的完整 tv.json；连 NAS 都没读到时为空 */
        val pendingText: String? = null,
        /** 失败前读到的远端内容指纹，覆盖前比对，防止抹掉家长的其它改动 */
        val baseHash: String = ""
    )

    /**
     * 读远端 tv.json → 交给 [apply] 修改 → 备份 → 覆盖写 → 回读校验 → 触发一次同步。
     * 失败时返回 ok=false，调用方不要假装改成功了。
     */
    fun patch(apply: (JSONObject) -> Unit): Result {
        val cfg = Store.nas() ?: return Result(false, "还没有配置 NAS")
        val share = Store.playlistShare ?: Store.DEFAULT_PLAYLIST_SHARE
        val path = Store.playlistPath

        val before = try {
            Smb.readAllIsolated(cfg, share, path, 15_000)
        } catch (e: Exception) {
            // 连读都没读到，没有可重放的完整内容；只标记「没同步」，不提供覆盖按钮
            return Result(false, "读不到 NAS 上的清单：${e.message ?: e.javaClass.simpleName}")
        }
        val baseHash = contentHash(before)

        val root = try {
            JSONObject(before.toString(Charsets.UTF_8).trimStart('\uFEFF'))
        } catch (e: Exception) {
            Log.w(TAG, "远端清单解析失败，拒绝改写: ${e.message}")
            return Result(false, "NAS 上的 tv.json 不是合法 JSON，已放弃这次修改", baseHash = baseHash)
        }

        try {
            apply(root)
        } catch (e: Exception) {
            Log.w(TAG, "生成新设置失败: ${e.message}")
            return Result(false, "生成新设置失败：${e.message}", baseHash = baseHash)
        }

        val out = (root.toString(2) + "\n").toByteArray(Charsets.UTF_8)
        val pending = out.toString(Charsets.UTF_8)
        val r = writeVerified(cfg, share, path, before, out)
        if (!r.ok) return r.copy(pendingText = pending, baseHash = baseHash)

        // 让自动同步立刻跑一次：把新设置应用到本地（时段、密码、比例…）并刷新界面
        Store.lastLocalPlaylistWrite = System.currentTimeMillis()
        PlaylistSync.checkNow("settings")
        Log.i(TAG, "清单已更新（家长控制），${out.size} 字节，备份 ${backupPath(path)}")
        return Result(true)
    }

    /**
     * 重放一次没保存成功的修改（设置页「用电视上的设置覆盖 NAS」）。
     *
     * 覆盖前重新读一次 NAS，和失败时读到的内容逐字节比对：这期间家长要是
     * 在网页上动过片单，宁可让家长重新改一次，也不能把那些改动抹掉。
     */
    fun restorePending(): Result {
        val pending = Store.pendingPlaylistJson
            ?: return Result(false, "这次修改当时连 NAS 都没读到，没有可重放的内容，请重新设置一次")
        val cfg = Store.nas() ?: return Result(false, "还没有配置 NAS")
        val share = Store.playlistShare ?: Store.DEFAULT_PLAYLIST_SHARE
        val path = Store.playlistPath

        val before = try {
            Smb.readAllIsolated(cfg, share, path, 15_000)
        } catch (e: Exception) {
            return Result(false, "现在还是读不到 NAS 上的清单：${e.message ?: e.javaClass.simpleName}")
        }
        val currentHash = contentHash(before)
        // 兼容升级前存下的旧 hashCode 格式
        val matchesBase = currentHash == Store.pendingBaseHash ||
            before.contentHashCode().toString() == Store.pendingBaseHash
        if (!matchesBase) {
            return Result(
                false,
                "NAS 上的清单在这期间被改过（可能你在网页上动过片单）。" +
                    "为避免覆盖，已放弃这次重放，请重新设置一次。"
            )
        }

        val out = pending.toByteArray(Charsets.UTF_8)
        val r = writeVerified(cfg, share, path, before, out)
        if (!r.ok) return r
        Store.clearUnsynced()
        Store.lastLocalPlaylistWrite = System.currentTimeMillis()
        PlaylistSync.checkNow("settings")
        Log.i(TAG, "未同步的修改已重新写入 NAS（${out.size} 字节）")
        return Result(true)
    }

    /** 家长选择「放弃这次修改」 */
    fun discardPending() {
        Store.clearUnsynced()
        PlaylistSync.checkNow("settings-discard")
    }

    /** 备份 → 覆盖写 → 回读校验。[patch] 与 [restorePending] 共用，避免两份逻辑跑偏 */
    private fun writeVerified(
        cfg: NasConfig,
        share: String,
        path: String,
        before: ByteArray,
        out: ByteArray
    ): Result {
        // 先备份再覆盖。SMB2 的 rename 在目标已存在时不保证能替换，
        // 所以不用「临时文件 + 改名」，改成「备份 + 直接覆盖」：
        // 万一写坏，NAS 上还留着上一份 tv.backup.json。
        try {
            Smb.writeIsolated(cfg, share, backupPath(path), before, 20_000)
        } catch (e: Exception) {
            // 备份失败不阻塞（例如共享只给了这一个文件的写权限），但要知道
            Log.w(TAG, "备份失败（继续写入）: ${e.message}")
        }

        try {
            Smb.writeIsolated(cfg, share, path, out, 25_000)
        } catch (e: Exception) {
            Log.w(TAG, "写入清单失败: ${e.message}")
            return Result(false, "写入失败：${e.message ?: e.javaClass.simpleName}")
        }

        // 回读校验：确认真的落盘、而且仍然是合法 JSON
        try {
            val check = Smb.readAllIsolated(cfg, share, path, 15_000)
            val text = check.toString(Charsets.UTF_8).trimStart('\uFEFF')
            JSONObject(text)
            if (check.size != out.size) {
                Log.w(TAG, "回读字节数不一致：写 ${out.size} / 读 ${check.size}")
            }
        } catch (e: Exception) {
            return Result(false, "写入了，但回读校验失败：${e.message}")
        }
        return Result(true)
    }

    private fun backupPath(path: String): String {
        val dir = path.substringBeforeLast('/', "")
        return if (dir.isEmpty()) "tv.backup.json" else "$dir/tv.backup.json"
    }
}
