package cn.jlu.schedule.parser

import cn.jlu.schedule.domain.ImportedGrade
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 金智 jwapp 成绩查询载荷解析（sys/cjcx/modules/cjcx/xscjcx.do → datas.xscjcx.rows）。
 * 字段类型在数据里可能是字符串或数字，均做容忍处理。
 */
object GradeTranscriptParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun isLikelyGradePayload(text: String): Boolean =
        text.contains("\"xscjcx\"") && text.contains("\"rows\"")

    fun parse(payload: String): List<ImportedGrade> {
        return runCatching {
            val rows = json.parseToJsonElement(payload)
                .jsonObject["datas"]?.jsonObject?.get("xscjcx")
                ?.jsonObject?.get("rows")?.jsonArray
                ?: return emptyList()
            rows.mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val credit = row.primitive("XF")?.toDoubleOrNull() ?: return@mapNotNull null
                if (credit <= 0.0) return@mapNotNull null
                val score = row.primitive("ZCJ")?.trim()?.ifEmpty { null }
                    ?: row.primitive("XSZCJMC")?.trim()?.ifEmpty { null }
                    ?: row.primitive("DJCJMC")?.trim()?.ifEmpty { null }
                    ?: return@mapNotNull null
                ImportedGrade(
                    courseCode = row.primitive("KCH").orEmpty().trim(),
                    name = row.primitive("KCM").orEmpty().trim(),
                    credit = credit,
                    scoreText = score,
                    semesterCode = row.primitive("XNXQDM").orEmpty().trim()
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun JsonObject.primitive(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        return value.content
    }
}
