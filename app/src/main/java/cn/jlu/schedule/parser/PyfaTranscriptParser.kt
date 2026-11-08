package cn.jlu.schedule.parser

import cn.jlu.schedule.data.AcademicProgressPlan
import cn.jlu.schedule.data.AcademicRequirement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.round

/**
 * 培养方案与学业完成情况载荷解析器。
 * 支持解析金智 eMAP 学业完成查询数据与 WebView DOM 提取的结构化数据。
 */
object PyfaTranscriptParser {

    private val json = Json { ignoreUnknownKeys = true }

    private val NAME_KEYS = listOf("FAJDMC", "JDMC", "KCLBMC", "KCXZMC", "MKMC", "PYFAMC", "MC", "NAME", "TITLE", "name", "categoryName")
    private val REQ_KEYS = listOf("YQJDXF", "ZSYQXF", "YQXF", "ZXF", "REQUIRED_CREDITS", "REQUIRED", "requiredCredits", "required", "yqxf", "zxf")
    private val EARNED_KEYS = listOf("YHDXF", "YWCXF", "WCXF", "HDXF", "YXXF", "PASSED_CREDITS", "EARNED", "earnedCredits", "earned", "yhdxf", "hdxf", "yxxf")

    fun isLikelyPyfaPayload(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.contains("\"pyfa_dom_extract\"") || text.contains("\"pyfa\"") || text.contains("\"pyfaTree\"")) return true
        if (text.contains("\"FAJDMC\"") || text.contains("\"YQJDXF\"") || text.contains("\"YHDXF\"")) return true
        if (text.contains("\"xsgzywcqk\"") || text.contains("\"xywcqk\"") || text.contains("\"wdpyfa\"") ||
            text.contains("\"grpyfacx\"") || text.contains("\"cxxsscfa\"")) return true
        return text.contains("\"datas\"") && (
            text.contains("\"YQXF\"") || text.contains("\"ZSYQXF\"") || text.contains("\"YWCXF\"") ||
            text.contains("\"WCXF\"") || text.contains("\"HDXF\"") || text.contains("\"YXXF\"") ||
            text.contains("\"requiredCredits\"") || text.contains("\"earnedCredits\"")
        )
    }

    fun parse(payload: String): AcademicProgressPlan? {
        if (!isLikelyPyfaPayload(payload)) return null
        return runCatching {
            val root = json.parseToJsonElement(payload)
            if (root !is JsonObject) return null

            // 1. 尝试 DOM/脚本提取专用格式
            if (root["type"]?.primitiveText == "pyfa_dom_extract" || root["modules"] is JsonArray) {
                return parseDomExtractFormat(root)
            }

            // 2. 尝试 eMAP datas 结构
            val datas = root["datas"]?.jsonObject ?: return null
            parseEmapDatas(datas)
        }.getOrNull()
    }

    private fun parseDomExtractFormat(obj: JsonObject): AcademicProgressPlan? {
        val modulesArray = obj["modules"]?.jsonArray ?: return null
        val reqs = mutableListOf<AcademicRequirement>()
        var sumReq = 0.0
        var sumEarned = 0.0

        for (item in modulesArray) {
            val itemObj = item as? JsonObject ?: continue
            val name = itemObj.findString(NAME_KEYS)?.trim().orEmpty()
            if (name.isBlank() || isRootSummaryTitle(name)) continue
            val reqCredit = itemObj.findDouble(REQ_KEYS) ?: 0.0
            val earnedCredit = itemObj.findDouble(EARNED_KEYS) ?: 0.0
            if (reqCredit <= 0.0 && earnedCredit <= 0.0) continue

            reqs.add(
                AcademicRequirement(
                    categoryName = name,
                    requiredCredits = roundOneDecimal(reqCredit),
                    earnedCredits = roundOneDecimal(earnedCredit)
                )
            )
            sumReq += reqCredit
            sumEarned += earnedCredit
        }

        if (reqs.isEmpty()) return null

        val explicitTotalReq = obj.findDouble(listOf("totalRequired", "totalRequiredCredits", "totalReq"))
        val explicitTotalEarned = obj.findDouble(listOf("totalEarned", "totalEarnedCredits", "totalEarned"))

        val finalTotalReq = if (explicitTotalReq != null && explicitTotalReq > 0.0) explicitTotalReq else sumReq
        val finalTotalEarned = if (explicitTotalEarned != null && explicitTotalEarned >= 0.0) explicitTotalEarned else sumEarned

        return AcademicProgressPlan(
            totalRequiredCredits = roundOneDecimal(finalTotalReq),
            totalEarnedCredits = roundOneDecimal(finalTotalEarned),
            requirements = reqs,
            lastUpdated = System.currentTimeMillis(),
            source = AcademicProgressPlan.SOURCE_WEB
        )
    }

    private fun parseEmapDatas(datas: JsonObject): AcademicProgressPlan? {
        val allNodes = mutableListOf<JsonObject>()

        // 收集所有 rows 或 树形节点
        fun collectNodes(elem: JsonElement) {
            when (elem) {
                is JsonObject -> {
                    val name = elem.findString(NAME_KEYS)
                    val req = elem.findDouble(REQ_KEYS)
                    if (!name.isNullOrBlank() && req != null) {
                        allNodes.add(elem)
                    }
                    for ((_, v) in elem) {
                        collectNodes(v)
                    }
                }
                is JsonArray -> {
                    for (item in elem) {
                        collectNodes(item)
                    }
                }
                else -> Unit
            }
        }

        collectNodes(datas)

        if (allNodes.isEmpty()) return null

        var rootNode: JsonObject? = null
        val categoryNodes = mutableListOf<JsonObject>()

        for (node in allNodes) {
            val name = node.findString(NAME_KEYS)?.trim().orEmpty()
            if (isRootSummaryTitle(name)) {
                if (rootNode == null) rootNode = node
            } else {
                categoryNodes.add(node)
            }
        }

        // 去重：按模块名称去重，保留信息更全的节点
        val distinctCategories = LinkedHashMap<String, AcademicRequirement>()
        for (node in categoryNodes) {
            val name = node.findString(NAME_KEYS)?.trim().orEmpty()
            if (name.isBlank() || isRootSummaryTitle(name)) continue
            val req = node.findDouble(REQ_KEYS) ?: 0.0
            val earned = node.findDouble(EARNED_KEYS) ?: 0.0
            if (req <= 0.0 && earned <= 0.0) continue

            val existing = distinctCategories[name]
            if (existing == null || (earned > existing.earnedCredits || req > existing.requiredCredits)) {
                distinctCategories[name] = AcademicRequirement(
                    categoryName = name,
                    requiredCredits = roundOneDecimal(req),
                    earnedCredits = roundOneDecimal(earned)
                )
            }
        }

        val requirements = distinctCategories.values.toList()
        if (requirements.isEmpty()) return null

        val sumReq = requirements.sumOf { it.requiredCredits }
        val sumEarned = requirements.sumOf { it.earnedCredits }

        val rootReq = rootNode?.findDouble(REQ_KEYS)
        val rootEarned = rootNode?.findDouble(EARNED_KEYS)

        val totalReq = if (rootReq != null && rootReq > 0.0) rootReq else sumReq
        val totalEarned = if (rootEarned != null && rootEarned >= 0.0) rootEarned else sumEarned

        return AcademicProgressPlan(
            totalRequiredCredits = roundOneDecimal(totalReq),
            totalEarnedCredits = roundOneDecimal(totalEarned),
            requirements = requirements,
            lastUpdated = System.currentTimeMillis(),
            source = AcademicProgressPlan.SOURCE_WEB
        )
    }

    private fun isRootSummaryTitle(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed in listOf("毕业要求", "总要求", "毕业总学分", "总学分", "方案要求", "毕业审核") ||
                trimmed.startsWith("毕业总要求") ||
                trimmed.startsWith("总计")
    }

    private fun JsonObject.findString(keys: List<String>): String? {
        for (k in keys) {
            val v = this[k]
            if (v != null && v !is JsonNull) {
                val s = v.primitiveText?.trim()
                if (!s.isNullOrBlank()) return s
            }
        }
        return null
    }

    private fun JsonObject.findDouble(keys: List<String>): Double? {
        for (k in keys) {
            val v = this[k] ?: continue
            if (v is JsonNull) continue
            val num = runCatching { v.jsonPrimitive.doubleOrNull }.getOrNull()
                ?: v.primitiveText?.toDoubleOrNull()
            if (num != null && num.isFinite()) return num
        }
        return null
    }

    private val JsonElement.primitiveText: String?
        get() = (this as? JsonPrimitive)?.content

    private fun roundOneDecimal(value: Double): Double =
        round(value * 10.0) / 10.0
}
