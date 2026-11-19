package com.family.curated.data

/**
 * 清单同步回执 `tv.ack.json` 的纯计算辅助（RQ-005）。
 * 不依赖 Android / org.json，便于纯 JVM 单测。
 */
object TvAck {

    fun textSha256(text: String): String =
        RemoteCommand.sha256Hex(text.trimStart('\uFEFF').toByteArray(Charsets.UTF_8))

    fun ackKey(tvText: String, learningText: String?): String {
        val tvHash = textSha256(tvText)
        val learningHash = learningText?.takeIf { it.isNotBlank() }?.let { textSha256(it) } ?: ""
        return "$tvHash|$learningHash"
    }

    fun ackPath(playlistPath: String): String {
        val dir = playlistPath.substringBeforeLast('/', "")
        return if (dir.isEmpty()) "tv.ack.json" else "$dir/tv.ack.json"
    }

    fun formatAckJson(
        tvHash: String,
        learningHash: String,
        appliedAt: String,
        versionCode: Int,
        versionName: String,
        sectionsCount: Int,
        configVersion: String = ""
    ): String {
        val verName = versionName.replace("\\", "\\\\").replace("\"", "\\\"")
        val ts = appliedAt.replace("\\", "\\\\").replace("\"", "\\\"")
        val cfgVer = configVersion.replace("\\", "\\\\").replace("\"", "\\\"")
        return buildString {
            append("{\n")
            append("  \"tvHash\": \"").append(tvHash).append("\",\n")
            append("  \"learningHash\": \"").append(learningHash).append("\",\n")
            append("  \"appliedAt\": \"").append(ts).append("\",\n")
            append("  \"configVersion\": \"").append(cfgVer).append("\",\n")
            append("  \"versionCode\": ").append(versionCode).append(",\n")
            append("  \"versionName\": \"").append(verName).append("\",\n")
            append("  \"sectionsCount\": ").append(sectionsCount).append("\n")
            append("}\n")
        }
    }
}
