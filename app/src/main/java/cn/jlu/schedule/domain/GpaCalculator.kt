package cn.jlu.schedule.domain

import kotlinx.serialization.Serializable

/**
 * 吉林大学绩点计算（原生改造自开源项目：
 * https://github.com/DailyPotato/JLU-GPA-Calculator 与
 * https://github.com/Coldymemos/JLU-GPA-Calculator-for-Windows-Desktop ，已获原作者同意）
 *
 * 规则（两个项目一致的常用预设，非官方规则，各学院可能不同）：
 * - 百分制 → 绩点：4.0 制，90 分及以上为 4.0，每 3 分左右递减一档，60 分以下为 0
 * - 五级制折算：优秀 95、良好 85、中等 75、及格 65、不及格 0
 * - 保研绩点 GPA = Σ(课程绩点 × 学分) / Σ学分
 * - 加权平均分 = Σ(分数 × 学分) / Σ学分
 * - 算术平均分 = Σ分数 / 课程门数
 */
enum class GpaGradeType {
    /** 百分制分数（0-100） */
    PERCENT,

    /** 五级制等级（优秀/良好/中等/及格/不及格） */
    LEVEL5
}

@Serializable
data class GpaCourse(
    val id: String,
    val name: String = "",
    val gradeType: GpaGradeType = GpaGradeType.PERCENT,
    /** 百分制分数，gradeType 为 PERCENT 时有效 */
    val score: Double = 0.0,
    /** 五级制等级原文，gradeType 为 LEVEL5 时有效 */
    val level: String = "",
    val credit: Double,
    /** 是否参与计算（用户可逐门排除） */
    val included: Boolean = true
) {
    val displayName: String get() = name.ifBlank { "未命名课程" }
}

/** 教务系统导入的一条成绩记录（金智 xscjcx.do 的常用字段） */
@Serializable
data class ImportedGrade(
    /** 课程号（KCH），重修去重的主键；缺失时退化为 课程名+学期 */
    val courseCode: String = "",
    val name: String = "",
    val credit: Double,
    /** 原始成绩文本：百分制数字或五级制等级（优秀/良好/中等/及格/不及格） */
    val scoreText: String = "",
    val semesterCode: String = "",
    /** 用户手动录入或修改的记录，同步时保留。 */
    val isCustom: Boolean = false
)

object GpaCalculator {

    /** 百分制 → 绩点映射档位：[minInclusive, 下一个档位 min) → gradePoint */
    private val GRADE_POINT_BANDS = listOf(
        90.0 to 4.0,
        87.0 to 3.7,
        84.0 to 3.3,
        80.0 to 3.0,
        77.0 to 2.7,
        74.0 to 2.3,
        70.0 to 2.0,
        67.0 to 1.7,
        64.0 to 1.3,
        60.0 to 1.0
    )

    /** 五级制 → 百分制折算分数 */
    val LEVEL_SCORES: Map<String, Double> = linkedMapOf(
        "优秀" to 95.0,
        "良好" to 85.0,
        "中等" to 75.0,
        "及格" to 65.0,
        "不及格" to 0.0
    )

    const val MAX_SCORE = 100.0
    const val MIN_SCORE = 0.0

    /** 五级制等级的展示顺序 */
    val LEVEL_ORDER = listOf("优秀", "良好", "中等", "及格", "不及格")

    fun gradePointFor(score: Double): Double {
        if (score < 60.0) return 0.0
        return GRADE_POINT_BANDS.firstOrNull { score >= it.first }?.second ?: 4.0
    }

    fun effectiveScore(gradeType: GpaGradeType, score: Double, level: String): Double = when (gradeType) {
        GpaGradeType.LEVEL5 -> LEVEL_SCORES[level] ?: 0.0
        GpaGradeType.PERCENT -> score
    }

    /** 成绩、学分是否可参与计算（不合法条目一律排除，避免污染结果） */
    fun isValidCourse(course: GpaCourse): Boolean {
        if (course.credit <= 0.0 || !course.credit.isFinite()) return false
        return when (course.gradeType) {
            GpaGradeType.PERCENT ->
                course.score.isFinite() && course.score >= MIN_SCORE && course.score <= MAX_SCORE
            GpaGradeType.LEVEL5 -> LEVEL_SCORES.containsKey(course.level)
        }
    }

    data class CourseBreakdown(
        val course: GpaCourse,
        /** 归一化后的百分制分数（五级制为折算分） */
        val effectiveScore: Double,
        /** 该课程绩点 */
        val gradePoint: Double,
        /** 最终是否纳入（用户排除或数据不合法都会排除） */
        val included: Boolean
    )

    data class Summary(
        /** 保研绩点 GPA；没有纳入课程时为 null */
        val recommendationGpa: Double?,
        /** 加权平均分；没有纳入课程时为 null */
        val weightedAverage: Double?,
        /** 算术平均分；没有纳入课程时为 null */
        val arithmeticAverage: Double?,
        val includedCount: Int,
        val includedCredits: Double,
        val breakdowns: List<CourseBreakdown>
    )

    fun calculate(courses: List<GpaCourse>): Summary {
        val breakdowns = courses.map { course ->
            val valid = isValidCourse(course)
            val included = course.included && valid
            val effective = if (valid) {
                effectiveScore(course.gradeType, course.score, course.level)
            } else {
                0.0
            }
            CourseBreakdown(course, effective, gradePointFor(effective), included)
        }
        val included = breakdowns.filter { it.included }
        if (included.isEmpty()) {
            return Summary(null, null, null, 0, 0.0, breakdowns)
        }
        val creditSum = included.sumOf { it.course.credit }
        val gpa = included.sumOf { it.gradePoint * it.course.credit } / creditSum
        val weighted = included.sumOf { it.effectiveScore * it.course.credit } / creditSum
        val arithmetic = included.sumOf { it.effectiveScore } / included.size
        return Summary(gpa, weighted, arithmetic, included.size, creditSum, breakdowns)
    }

    /**
     * 导入成绩 → 计算器课程：同一课程号只保留最高有效成绩（重修取高，与两个参考实现一致），
     * 同名不同课程号不合并；无法识别的成绩（缓考/旷考/空值等）丢弃。
     */
    fun mergeImported(grades: List<ImportedGrade>): List<GpaCourse> {
        val best = LinkedHashMap<String, ImportedGrade>()
        for (grade in grades) {
            val key = grade.courseCode.ifBlank { "${grade.name}|${grade.semesterCode}" }
            val existing = best[key]
            if (existing == null || importedEffectiveScore(grade) > importedEffectiveScore(existing)) {
                best[key] = grade
            }
        }
        return best.values.mapNotNull { it.toGpaCourse() }
    }

    private fun importedEffectiveScore(grade: ImportedGrade): Double {
        val asNumber = grade.scoreText.toDoubleOrNull()
        return when {
            asNumber != null && asNumber.isFinite() && asNumber >= MIN_SCORE && asNumber <= MAX_SCORE -> asNumber
            else -> LEVEL_SCORES[grade.scoreText] ?: -1.0
        }
    }

    private fun ImportedGrade.toGpaCourse(): GpaCourse? {
        val asNumber = scoreText.toDoubleOrNull()
        val id = if (courseCode.isNotBlank()) {
            "jw-$courseCode-$semesterCode"
        } else {
            "jw-${name.hashCode()}-$semesterCode"
        }
        return when {
            asNumber != null && asNumber.isFinite() && asNumber >= MIN_SCORE && asNumber <= MAX_SCORE -> GpaCourse(
                id = id,
                name = name,
                gradeType = GpaGradeType.PERCENT,
                score = asNumber,
                credit = credit
            )
            LEVEL_SCORES.containsKey(scoreText) -> GpaCourse(
                id = id,
                name = name,
                gradeType = GpaGradeType.LEVEL5,
                level = scoreText,
                credit = credit
            )
            else -> null
        }
    }
}
