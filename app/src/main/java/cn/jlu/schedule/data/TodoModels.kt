package cn.jlu.schedule.data

import kotlinx.serialization.Serializable

/** 待办事项类型：日常 / 作业 / 测验 / 考试 */
@Serializable
enum class TaskKind(val displayName: String) {
    GENERAL("日常"),
    HOMEWORK("作业"),
    QUIZ("测验"),
    EXAM("考试")
}

/**
 * 待办事项。JSON 字段名与 ClassCue 的本地存储保持一致，便于日后手工迁移数据。
 */
@Serializable
data class TodoItem(
    val id: Long,
    val title: String,
    val note: String = "",
    val location: String = "",
    val dueAt: Long = 0L,
    val completed: Boolean = false,
    val reminderEnabled: Boolean = true,
    val advanceReminderDays: List<Int> = emptyList(),
    val createdAt: Long = 0L,
    val courseName: String = "",
    val taskKind: TaskKind = TaskKind.GENERAL,
    val imageUris: List<String> = emptyList()
)
