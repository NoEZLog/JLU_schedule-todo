package cn.jlu.schedule.data

import cn.jlu.schedule.model.CourseSchedule
import cn.jlu.schedule.model.MeetingTime
import cn.jlu.schedule.model.WeekParity
import cn.jlu.schedule.model.WeekRule
import cn.jlu.schedule.model.Weekday
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 课表备份文件的 JSON 编解码。
 * 独立于存储层的内部持久化模型，保证备份格式稳定、可跨版本迁移。
 */
object ScheduleBackupCodec {
    private const val BACKUP_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
    }

    @Serializable
    data class BackupFile(
        val version: Int = BACKUP_VERSION,
        val exportedAt: String = "",
        val profiles: List<BackupProfile> = emptyList(),
        val todos: List<BackupTodo> = emptyList()
    )

    @Serializable
    data class BackupProfile(
        val name: String,
        val semesterStartDate: String? = null,
        val courses: List<BackupCourse> = emptyList()
    )

    @Serializable
    data class BackupCourse(
        val courseName: String,
        val teacher: String = "",
        val semester: String = "",
        val credit: Double? = null,
        val rawWeekText: String = "",
        val meetings: List<BackupMeeting> = emptyList()
    )

    @Serializable
    data class BackupMeeting(
        val weekday: String,
        val startSection: Int,
        val endSection: Int,
        val weekRules: List<BackupWeekRule> = emptyList(),
        val location: String = ""
    )

    @Serializable
    data class BackupWeekRule(
        val startWeek: Int,
        val endWeek: Int,
        val parity: String = "ALL"
    )

    /** 待办事项备份模型：taskKind 以字符串保存，容忍未知类型 */
    @Serializable
    data class BackupTodo(
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
        val taskKind: String = "GENERAL",
        val imageUris: List<String> = emptyList()
    )

    fun encode(
        exportedAt: String,
        profiles: List<BackupProfile>,
        todos: List<BackupTodo> = emptyList()
    ): String {
        return json.encodeToString(
            BackupFile(version = BACKUP_VERSION, exportedAt = exportedAt, profiles = profiles, todos = todos)
        )
    }

    /** 解码备份文件内容，格式不合法时返回 null */
    fun decode(content: String): List<BackupProfile>? {
        return decodeFile(content)?.profiles
    }

    /** 解码完整备份文件（含待办），格式不合法时返回 null */
    fun decodeFile(content: String): BackupFile? {
        return runCatching {
            val file = json.decodeFromString<BackupFile>(content)
            if (file.version !in 1..BACKUP_VERSION) return null
            file
        }.getOrNull()
    }

    fun BackupTodo.toTodoItem(): TodoItem {
        return TodoItem(
            id = id,
            title = title,
            note = note,
            location = location,
            dueAt = dueAt,
            completed = completed,
            reminderEnabled = reminderEnabled,
            advanceReminderDays = advanceReminderDays,
            createdAt = createdAt,
            courseName = courseName,
            taskKind = runCatching { TaskKind.valueOf(taskKind.trim().uppercase()) }
                .getOrDefault(TaskKind.GENERAL),
            imageUris = imageUris
        )
    }

    fun TodoItem.toBackupTodo(): BackupTodo {
        return BackupTodo(
            id = id,
            title = title,
            note = note,
            location = location,
            dueAt = dueAt,
            completed = completed,
            reminderEnabled = reminderEnabled,
            advanceReminderDays = advanceReminderDays,
            createdAt = createdAt,
            courseName = courseName,
            taskKind = taskKind.name,
            imageUris = imageUris
        )
    }

    fun BackupCourse.toCourseSchedule(): CourseSchedule {
        return CourseSchedule(
            courseName = courseName,
            teacher = teacher,
            semester = semester,
            credit = credit,
            rawWeekText = rawWeekText,
            meetings = meetings.map { it.toMeetingTime() }
        )
    }

    fun CourseSchedule.toBackupCourse(): BackupCourse {
        return BackupCourse(
            courseName = courseName,
            teacher = teacher,
            semester = semester,
            credit = credit,
            rawWeekText = rawWeekText,
            meetings = meetings.map { it.toBackupMeeting() }
        )
    }

    private fun BackupMeeting.toMeetingTime(): MeetingTime {
        return MeetingTime(
            weekday = parseWeekday(weekday),
            startSection = startSection,
            endSection = endSection,
            weekRules = weekRules.map { it.toWeekRule() },
            location = location
        )
    }

    private fun parseWeekday(raw: String): Weekday {
        val intVal = raw.trim().toIntOrNull()
        if (intVal != null && intVal in 1..Weekday.entries.size) {
            return Weekday.entries[intVal - 1]
        }
        return runCatching { Weekday.valueOf(raw.trim().uppercase()) }.getOrDefault(Weekday.MONDAY)
    }

    private fun MeetingTime.toBackupMeeting(): BackupMeeting {
        return BackupMeeting(
            weekday = weekday.name,
            startSection = startSection,
            endSection = endSection,
            weekRules = weekRules.map { it.toBackupWeekRule() },
            location = location
        )
    }

    private fun BackupWeekRule.toWeekRule(): WeekRule {
        return WeekRule(
            startWeek = startWeek,
            endWeek = endWeek,
            parity = parseWeekParity(parity)
        )
    }

    private fun parseWeekParity(raw: String): WeekParity {
        return runCatching { WeekParity.valueOf(raw.trim().uppercase()) }.getOrDefault(WeekParity.ALL)
    }

    private fun WeekRule.toBackupWeekRule(): BackupWeekRule {
        return BackupWeekRule(startWeek = startWeek, endWeek = endWeek, parity = parity.name)
    }
}
