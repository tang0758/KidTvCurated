package com.family.curated.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一天里允许使用的时间窗。分钟以 00:00 为 0。
 * endMin <= startMin 表示跨零点（例如 20:00 - 01:00）。
 */
data class TimeWindow(val startMin: Int, val endMin: Int) {
    fun contains(min: Int): Boolean =
        if (endMin <= startMin) (min >= startMin || min < endMin)
        else (min >= startMin && min < endMin)
}

/**
 * 可用时段的配置，来自 tv.json 的 "schedule" 段。
 * 空的时间窗列表 = 那天全天可用。
 */
data class Schedule(
    val enabled: Boolean = false,
    val weekday: List<TimeWindow> = emptyList(),
    val weekend: List<TimeWindow> = emptyList(),
    val dailyLimitMinutes: Int = 0,
    val lockMessage: String = "到点啦，先去玩会儿别的吧",
    val pin: String = "1234"
) {
    /** 周末不写 weekend 时沿用工作日；显式写 [] 表示周末全天开放 */
    fun windowsAt(cal: java.util.Calendar): List<TimeWindow> {
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val isWeekend = dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY
        return if (isWeekend) weekend else weekday
    }

    fun toJson(): String = JSONObject().apply {
        put("enabled", enabled)
        put("weekday", JSONArray().also { a -> weekday.forEach { a.put("${hm(it.startMin)}-${hm(it.endMin)}") } })
        put("weekend", JSONArray().also { a -> weekend.forEach { a.put("${hm(it.startMin)}-${hm(it.endMin)}") } })
        put("dailyLimitMinutes", dailyLimitMinutes)
        put("lockMessage", lockMessage)
        put("pin", pin)
    }.toString()

    companion object {
        fun fromJson(s: String?): Schedule? {
            if (s.isNullOrBlank()) return null
            return try { parse(JSONObject(s)) } catch (e: Exception) { null }
        }

        fun parse(o: JSONObject?): Schedule? {
            if (o == null) return null
            val wd = windows(o.optJSONArray("weekday"))
            val we = if (o.has("weekend")) windows(o.optJSONArray("weekend")) else wd
            return Schedule(
                enabled = o.optBoolean("enabled", true),
                weekday = wd,
                weekend = we,
                dailyLimitMinutes = o.optInt("dailyLimitMinutes", 0),
                lockMessage = o.optString("lockMessage", "到点啦，先去玩会儿别的吧").ifBlank { "到点啦" },
                pin = o.optString("pin", "1234").ifBlank { "1234" }
            )
        }

        /** 支持三种写法："18:00-20:00"、["18:00","20:00"]、{"start":"18:00","end":"20:00"} */
        private fun windows(arr: JSONArray?): List<TimeWindow> {
            if (arr == null) return emptyList()
            val out = ArrayList<TimeWindow>()
            for (i in 0 until arr.length()) {
                try {
                    when (val v = arr.get(i)) {
                        is String -> {
                            val parts = v.split("-", "–", "~", "至")
                            if (parts.size >= 2) out.add(TimeWindow(minOfDay(parts[0]), minOfDay(parts[1])))
                        }
                        is JSONObject -> out.add(
                            TimeWindow(minOfDay(v.optString("start")), minOfDay(v.optString("end")))
                        )
                        is JSONArray -> if (v.length() >= 2)
                            out.add(TimeWindow(minOfDay(v.getString(0)), minOfDay(v.getString(1))))
                    }
                } catch (e: Exception) { /* 忽略写错的时段 */ }
            }
            return out
        }

        private fun minOfDay(s: String?): Int {
            val t = (s ?: "").trim()
            val p = t.split(":")
            val h = p.getOrNull(0)?.toIntOrNull() ?: 0
            val m = p.getOrNull(1)?.toIntOrNull() ?: 0
            return (h.coerceIn(0, 23) * 60 + m.coerceIn(0, 59))
        }

        private fun hm(min: Int): String = String.format("%02d:%02d", min / 60, min % 60)
    }
}
