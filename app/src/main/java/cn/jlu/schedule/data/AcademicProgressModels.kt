package cn.jlu.schedule.data

import kotlinx.serialization.Serializable

@Serializable
data class AcademicRequirement(
    val categoryName: String,
    val requiredCredits: Double,
    val earnedCredits: Double = 0.0
)

@Serializable
data class AcademicProgressPlan(
    val totalRequiredCredits: Double = 160.0,
    val totalEarnedCredits: Double = 0.0,
    val requirements: List<AcademicRequirement> = listOf(
        AcademicRequirement("学科基础与专业必修", 75.0, 0.0),
        AcademicRequirement("专业选修课", 35.0, 0.0),
        AcademicRequirement("通识教育选修/核心课", 16.0, 0.0),
        AcademicRequirement("实践教学与毕业设计", 34.0, 0.0)
    ),
    val lastUpdated: Long = 0L,
    val source: String = SOURCE_MANUAL
) {
    companion object {
        const val SOURCE_MANUAL = "MANUAL"
        const val SOURCE_WEB = "WEB_SYNC"
        const val SOURCE_ESTIMATED = "ESTIMATED"
    }
}
