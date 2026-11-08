package com.family.curated.data

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 读取清单：优先 NAS 上的 json，失败时退回本地缓存。
 */
object Repo {

    private const val TAG = "Repo"

    data class LoadResult(val playlist: Playlist, val fromCache: Boolean, val error: String?)

    /** 首次启动用的默认 NAS；清单里的 nas 段会覆盖它 */
    private val DEFAULT_NAS = Store.DEFAULT_NAS

    fun load(cfgOverride: NasConfig? = null): LoadResult {
        val cfg = cfgOverride ?: Store.nas() ?: DEFAULT_NAS
        if (cfg.host.isBlank() || cfg.share.isBlank()) {
            return LoadResult(Playlist(nas = null, sections = emptyList()), true, "还没有配置 NAS 和清单")
        }
        return tryLoad(cfg, allowDiscover = true)
    }

    private fun tryLoad(cfg: NasConfig, allowDiscover: Boolean): LoadResult {
        var usedShare = Store.playlistShare ?: Store.DEFAULT_PLAYLIST_SHARE
        try {
            val (bytes, foundShare) = readPlaylist(cfg)
            usedShare = foundShare
            val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF')
            val pl = PlaylistParser.parse(text)
            Store.cachedPlaylist = text
            Store.lastSync = System.currentTimeMillis()
            Store.saveNas(cfg)
            if (pl.nas != null && pl.nas.host.isNotBlank()) Store.saveNas(pl.nas)
            return LoadResult(expandAuto(pl), false, null)
        } catch (e: Exception) {
            val where = "${cfg.host}:${cfg.port}/$usedShare/${Store.playlistPath}"
            Log.w(TAG, "从 NAS 读取清单失败 ($where): ${e.message}")

            // NAS 换 IP 很常见（DHCP 重新分配）：扫一遍本网段找 SMB 服务，找到就自动切过去
            if (allowDiscover) {
                val found = discoverSmb(cfg)
                if (found != null && found.host != cfg.host) {
                    Log.i(TAG, "发现 NAS 换了地址：${cfg.host} -> ${found.host}")
                    Store.saveNas(found)
                    val retry = tryLoad(found, allowDiscover = false)
                    if (retry.error == null || Store.cachedPlaylist != null) return retry
                }
            }

            val detail = e.message ?: e.javaClass.simpleName
            val cached = Store.cachedPlaylist
            if (cached != null) {
                return LoadResult(
                    expandAuto(PlaylistParser.parse(cached)), true,
                    "连不上 NAS，正在显示上次的清单\n$where\n$detail"
                )
            }
            return LoadResult(
                Playlist(nas = cfg, sections = emptyList()),
                true,
                "连不上 NAS，也没有缓存清单\n\n目标：$where\n原因：$detail\n\n如果是 445 端口拒绝连接，去 NAS 后台把 SMB 文件服务打开"
            )
        }
    }

    /** 清单固定放在 data 共享；找不到时再兜底试其它共享 */
    private fun readPlaylist(cfg: NasConfig): Pair<ByteArray, String> {
        val path = Store.playlistPath
        val candidates = listOfNotNull(
            Store.playlistShare,
            Store.DEFAULT_PLAYLIST_SHARE,
            cfg.share,
            "普通数据",
            "迅雷下载"
        ).distinct()

        var last: Exception? = null
        for (s in candidates) {
            try {
                val bytes = Smb.readAll(cfg, path, 4 * 1024 * 1024, s)
                Store.playlistShare = s
                Log.i(TAG, "清单读取自共享 $s / $path (${bytes.size} 字节)")
                return bytes to s
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: Exception("找不到 $path")
    }

    /** 扫本网段 445 端口，再用账号试认证，找到真正能用的 NAS */
    private fun discoverSmb(cfg: NasConfig): NasConfig? {
        val lastDot = cfg.host.lastIndexOf('.')
        if (lastDot < 0) return null
        val prefix = cfg.host.substring(0, lastDot + 1)

        val open = Collections.synchronizedList(ArrayList<String>())
        val pool = Executors.newFixedThreadPool(48)
        try {
            val tasks = (1..254).map { i ->
                pool.submit {
                    val ip = prefix + i
                    if (probePort(ip, 445)) open.add(ip)
                }
            }
            tasks.forEach {
                try { it.get(3, TimeUnit.SECONDS) } catch (e: Exception) { }
            }
        } finally {
            pool.shutdownNow()
        }

        val candidates = open.distinct().sortedBy { it == cfg.host }
        Log.i(TAG, "网段内 SMB 主机: $candidates")
        for (ip in candidates) {
            val test = cfg.copy(host = ip)
            try {
                Smb.reset()
                Smb.ensureConnected(test).list("\\")
                return test
            } catch (e: Exception) {
                // 别的机器也开了 445（比如 Windows 共享），跳过
            }
        }
        return null
    }

    private fun probePort(host: String, port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 350)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 把带 auto 的分区展开成今天的实际条目：
     * 从文件夹里按天轮换，每天取 count 个，不会重复（一轮放完再从头开始）。
     */
    private fun expandAuto(pl: Playlist): Playlist {
        if (pl.sections.none { it.autoFolder != null }) return pl
        val cfg = Store.nas() ?: pl.nas ?: return pl
        val start = Store.rotationStart()
        val day = Store.daysSince(start)

        val expanded = pl.sections.map { sec ->
            val folder = sec.autoFolder ?: return@map sec
            val count = sec.autoCount.coerceAtLeast(1)
            try {
                val all = listVideos(cfg, folder)
                if (all.isEmpty()) {
                    sec.copy(items = emptyList())
                } else {
                    val n = minOf(count, all.size)
                    val from = ((day.toLong() * count) % all.size).toInt()
                    val picked = (0 until n).map { all[(from + it) % all.size] }
                    sec.copy(items = picked)
                }
            } catch (e: Exception) {
                sec
            }
        }
        return pl.copy(sections = expanded)
    }

    /** 清单里每个条目对应的封面候选（按优先级） */
    fun coverCandidates(item: Item): List<CoverRef> {
        item.cover?.let { c ->
            // "data:curated/covers/x.jpg" = 显式指定共享（视频共享只读时很有用）
            val i = c.indexOf(':')
            if (i > 1 && c.substring(0, i).none { it == '/' || it == '\\' }) {
                return listOf(CoverRef(c.substring(i + 1).trimStart('/'), c.substring(0, i)))
            }
            return listOf(CoverRef(c))
        }
        val out = ArrayList<CoverRef>(5)
        if (item.file != null) {
            val base = item.file.substringBeforeLast('.', item.file)
            out.add(CoverRef("$base.jpg"))
            out.add(CoverRef("$base.png"))
            val dir = item.file.substringBeforeLast('/', "")
            val name = item.file.substringAfterLast('/')
            if (dir.isNotEmpty()) {
                out.add(CoverRef("$dir/${name.substringBeforeLast('.', name)}-poster.jpg"))
            }
        }
        if (item.folder != null) {
            val f = item.folder.trimEnd('/')
            out.add(CoverRef("$f/folder.jpg"))
            out.add(CoverRef("$f/poster.jpg"))
            out.add(CoverRef("$f/cover.jpg"))
            out.add(CoverRef("$f/封面.jpg"))
        }
        return out
    }

    private val VIDEO_EXT = setOf(
        "mp4", "mkv", "avi", "ts", "m2ts", "mov", "wmv", "flv", "webm", "mpg", "mpeg", "rmvb", "iso", "m4v"
    )

    fun isVideo(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXT && !name.startsWith(".")
    }

    /** 把乱糟糟的文件名收拾成电视上能一眼看懂的短标题 */
    fun prettyName(name: String): String {
        var n = name.substringBeforeLast('.', name)
        n = n.replace(Regex("【[^】]*】"), "")           // 站点水印
        n = n.replace(Regex("\\([0-9]+\\)$"), "")        // (1) 副本后缀
        n = n.replace(
            Regex("(?i)\\b(1080p|720p|2160p|4k|web-?dl|webrip|bluray|hdr|h\\.?264|h\\.?265|x264|aac2?\\.?0?|dts|lazy|nick|at008)\\b"),
            " "
        )
        n = n.replace(Regex("[._]+"), " ").replace(Regex("\\s{2,}"), " ").trim()

        val ep = Regex("(?i)S\\d+E\\d+(?:E\\d+)?").find(n)?.value
        if (ep != null) {
            val rest = n.replace(ep, "").replace(Regex("^[\\s\\-·:]+"), "").trim()
            return if (rest.isBlank()) ep else "$ep · ${rest.take(46)}"
        }
        return n.take(64).ifBlank { name }
    }

    /**
     * 列出文件夹里的所有视频。
     * 绿联 NAS 上很多剧集是「剧名 / 第N季 / 单集.mp4」的多层结构，
     * 所以这里往下钻几层，否则像汪汪队这种会一个都看不到。
     */
    fun listVideos(cfg: NasConfig, folder: String, maxDepth: Int = 3): List<Item> {
        val root = folder.trimEnd('/')
        val out = ArrayList<Item>()

        fun walk(dir: String, depth: Int) {
            if (depth > maxDepth || out.size >= 800) return
            val entries = try {
                Smb.list(cfg, dir)
            } catch (e: Exception) {
                Log.w(TAG, "listVideos 列目录失败: $dir", e)
                return
            }

            val vids = entries
                .filter { !it.second && isVideo(it.first) && !it.first.endsWith(".bak") }
                .map { it.first }
                .sortedWith(NaturalOrder)

            // 文件名里已带 S06E01 这类集号就不用再加季目录前缀了
            val shortDir = dir.removePrefix(root).trimStart('/').replace('/', ' ')
            val epCode = Regex("(?i)S\\d+E\\d+")

            for (name in vids) {
                val path = "$dir/$name"
                val clean = prettyName(name)
                val label = if (dir == root || epCode.containsMatchIn(name)) ""
                else (shortDir.take(16) + " / ")
                out.add(Item(id = path, title = label + clean, file = path))
            }

            entries
                .filter { it.second && !it.first.startsWith(".") && it.first != "@tmp" }
                .sortedBy { it.first }
                .forEach { walk("$dir/${it.first}", depth + 1) }
        }

        walk(root, 0)
        return out
    }
}

/** 自然排序：Maisy 2 在 Maisy 10 之前 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var si = i
                var sj = j
                while (si < a.length && a[si].isDigit()) si++
                while (sj < b.length && b[sj].isDigit()) sj++
                val na = a.substring(i, si).trimStart('0')
                val nb = b.substring(j, sj).trimStart('0')
                val c = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
                if (c != 0) return c
                i = si; j = sj
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return a.length - b.length
    }
}
