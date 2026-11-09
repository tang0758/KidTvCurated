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
 */
object PlaylistWriter {

    private const val TAG = "PlaylistWriter"

    data class Result(val ok: Boolean, val message: String = "")

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
            return Result(false, "读不到 NAS 上的清单：${e.message ?: e.javaClass.simpleName}")
        }

        val root = try {
            JSONObject(before.toString(Charsets.UTF_8).trimStart('\uFEFF'))
        } catch (e: Exception) {
            Log.w(TAG, "远端清单解析失败，拒绝改写: ${e.message}")
            return Result(false, "NAS 上的 tv.json 不是合法 JSON，已放弃这次修改")
        }

        try {
            apply(root)
        } catch (e: Exception) {
            Log.w(TAG, "生成新设置失败: ${e.message}")
            return Result(false, "生成新设置失败：${e.message}")
        }

        val out = (root.toString(2) + "\n").toByteArray(Charsets.UTF_8)

        // 先备份再覆盖。SMB2 的 rename 在目标已存在时不保证能替换，
        // 所以不用「临时文件 + 改名」，改成「备份 + 直接覆盖」：
        // 万一写坏，NAS 上还留着上一份 tv.backup.json。
        val backup = backupPath(path)
        try {
            Smb.writeIsolated(cfg, share, backup, before, 20_000)
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

        // 让自动同步立刻跑一次：把新设置应用到本地（时段、密码、比例…）并刷新界面
        Store.lastLocalPlaylistWrite = System.currentTimeMillis()
        PlaylistSync.checkNow("settings")
        Log.i(TAG, "清单已更新（家长控制），${out.size} 字节，备份 $backup")
        return Result(true)
    }

    private fun backupPath(path: String): String {
        val dir = path.substringBeforeLast('/', "")
        return if (dir.isEmpty()) "tv.backup.json" else "$dir/tv.backup.json"
    }
}
