package cn.jlu.schedule.data

import kotlinx.serialization.Serializable

@Serializable
data class ExamItem(
    val id: String,
    val courseName: String,
    val courseCode: String = "",
    val examTimeText: String,
    val location: String = "",
    val seatNumber: String = "",
    val examType: String = "期末考试",
    val timestamp: Long = 0L,
    val isCustom: Boolean = false
)
