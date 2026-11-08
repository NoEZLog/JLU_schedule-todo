package cn.jlu.schedule.parser

import cn.jlu.schedule.data.ExamItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/**
 * 金智 jwapp 考试安排载荷解析（studentWdksapApp 的 datas.*.rows）。
 */
object ExamScheduleParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun isLikelyExamPayload(text: String): Boolean {
        val hasRows = text.contains("\"rows\"") && text.contains("\"datas\"")
        val hasKeywords = text.contains("KCM") || text.contains("KSSJ") || text.contains("xskscx") ||
            text.contains("wdksap") || text.contains("cxxsksap")
        return hasRows && hasKeywords
    }

    fun parse(payload: String): List<ExamItem> {
        return runCatching {
            val root = json.parseToJsonElement(payload).jsonObject
            val datas = root["datas"]?.jsonObject ?: return emptyList()

            // 寻找包含 rows 的子对象（如 xskscx, wdksap, kscx 等）
            val rows = datas.values.firstNotNullOfOrNull { value ->
                value.jsonObject["rows"]?.jsonArray
            } ?: return emptyList()

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

            rows.mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val courseName = row.primitive("KCM")
                    ?: row.primitive("KCMC")
                    ?: row.primitive("courseName")
                    ?: return@mapNotNull null
                if (courseName.isBlank()) return@mapNotNull null

                val courseCode = row.primitive("KCH") ?: row.primitive("courseCode") ?: ""
                val timeText = row.primitive("KSSJMS")
                    ?: row.primitive("KSSJ")
                    ?: row.primitive("examTime")
                    ?: ""
                val location = row.primitive("JASMC")
                    ?: row.primitive("KSCDMC")
                    ?: row.primitive("KSDD")
                    ?: row.primitive("location")
                    ?: ""
                val seatNumber = row.primitive("ZWH") ?: row.primitive("seatNumber") ?: ""
                val examType = row.primitive("KSMC")
                    ?: row.primitive("KSLX")
                    ?: row.primitive("examType")
                    ?: "期末考试"

                // 尝试提取时间戳用于排序倒计时
                val timestamp = parseTimestamp(timeText, dateFormat)

                ExamItem(
                    id = UUID.randomUUID().toString(),
                    courseName = courseName.trim(),
                    courseCode = courseCode.trim(),
                    examTimeText = timeText.trim(),
                    location = location.trim(),
                    seatNumber = if (seatNumber.isNotBlank()) "${seatNumber.trim()}号" else "",
                    examType = examType.trim(),
                    timestamp = timestamp,
                    isCustom = false
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun parseTimestamp(timeText: String, format: SimpleDateFormat): Long {
        if (timeText.isBlank()) return 0L
        val match = Regex("""\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}""").find(timeText) ?: return 0L
        return runCatching { format.parse(match.value)?.time ?: 0L }.getOrDefault(0L)
    }

    private fun JsonObject.primitive(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        return value.content
    }
}
