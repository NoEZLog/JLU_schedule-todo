package cn.jlu.schedule.data

import android.content.Context
import cn.jlu.schedule.domain.WeekScheduleCalculator
import cn.jlu.schedule.model.CourseSchedule
import cn.jlu.schedule.widget.TodayWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 课表数据仓库：UI 通过 [timetable] 订阅数据变化，写操作统一走本层，
 * 变更成功后自动重新加载并更新桌面小组件，替代旧版 recreate() 刷新模式。
 */
object ScheduleRepository {
    data class TimetableUiData(
        val activeProfileId: String,
        val profiles: List<ImportedScheduleStorage.TimetableProfile>,
        val courses: List<CourseSchedule>,
        val semesterStart: LocalDate,
        val totalWeeks: Int,
        val currentWeek: Int
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _timetable = MutableStateFlow<TimetableUiData?>(null)
    val timetable: StateFlow<TimetableUiData?> = _timetable.asStateFlow()

    fun refresh(context: Context) {
        scope.launch { reload(context) }
    }

    suspend fun load(context: Context): TimetableUiData = withContext(Dispatchers.IO) {
        val files = context.filesDir
        val courses = runCatching { ImportedScheduleStorage.loadActiveCourses(files) }.getOrDefault(emptyList())
        val semesterStart = ImportedScheduleStorage.getActiveSemesterStartDate(files)
        val profiles = ImportedScheduleStorage.listProfiles(files)
        val totalWeeks = WeekScheduleCalculator.totalWeeks(courses)
        val currentWeek = WeekScheduleCalculator.guessCurrentWeek(semesterStart, LocalDate.now(), totalWeeks)
        TimetableUiData(
            activeProfileId = profiles.firstOrNull { it.isActive }?.id.orEmpty(),
            profiles = profiles,
            courses = courses,
            semesterStart = semesterStart,
            totalWeeks = totalWeeks,
            currentWeek = currentWeek
        )
    }

    suspend fun addManualCourse(
        context: Context,
        input: ImportedScheduleStorage.ManualCourseInput
    ): Result<Unit> = mutate(context) {
        ImportedScheduleStorage.addManualCourseToActive(context.filesDir, input)
    }

    suspend fun deleteCourse(
        context: Context,
        courseIndex: Int,
        expected: CourseSchedule
    ): Result<Boolean> = mutate(context) {
        val deleted = ImportedScheduleStorage.deleteCourseFromActive(context.filesDir, courseIndex, expected)
        if (deleted) {
            expected.meetings.forEach { meeting ->
                for (section in meeting.startSection..meeting.endSection) {
                    if (AppPreferences.getPinnedCourse(context, meeting.weekday, section) == expected.courseName) {
                        AppPreferences.setPinnedCourseForSlot(context, meeting.weekday, section..section, null)
                    }
                }
            }
        }
        deleted
    }

    suspend fun importParsedCourses(
        context: Context,
        courses: List<CourseSchedule>,
        mode: ImportedScheduleStorage.ImportMode,
        newProfileName: String? = null,
        semesterStartDate: LocalDate? = null
    ): Result<ImportedScheduleStorage.ImportResult> = mutate(context) {
        ImportedScheduleStorage.importParsedCourses(
            context.filesDir,
            courses,
            mode,
            newProfileName,
            semesterStartDate
        )
    }

    suspend fun createEmptyProfile(context: Context, name: String): Result<ImportedScheduleStorage.TimetableProfile> =
        mutate(context) {
            ImportedScheduleStorage.createEmptyProfile(context.filesDir, name)
        }

    suspend fun deleteProfile(context: Context, profileId: String): Result<Boolean> = mutate(context) {
        ImportedScheduleStorage.deleteProfile(context.filesDir, profileId)
    }

    suspend fun renameProfile(context: Context, profileId: String, newName: String): Result<Boolean> =
        mutate(context) {
            ImportedScheduleStorage.renameProfile(context.filesDir, profileId, newName)
        }

    suspend fun setActiveProfile(context: Context, profileId: String): Result<Boolean> = mutate(context) {
        ImportedScheduleStorage.setActiveProfile(context.filesDir, profileId)
    }

    suspend fun setActiveSemesterStartDate(context: Context, date: LocalDate): Result<Unit> = mutate(context) {
        ImportedScheduleStorage.setActiveSemesterStartDate(context.filesDir, date)
    }

    suspend fun updateCourse(
        context: Context,
        courseIndex: Int,
        expected: CourseSchedule,
        updated: CourseSchedule
    ): Result<Boolean> = mutate(context) {
        ImportedScheduleStorage.updateCourseInActive(context.filesDir, courseIndex, expected, updated)
    }

    suspend fun restoreBackup(context: Context, content: String): Result<Int> = mutate(context) {
        val count = ImportedScheduleStorage.importBackup(context.filesDir, content)
        cn.jlu.schedule.reminder.TodoReminderScheduler.rescheduleAll(context)
        count
    }

    private suspend fun <T> mutate(context: Context, block: suspend () -> T): Result<T> {
        return withContext(Dispatchers.IO) {
            runCatching { block() }
        }.onSuccess {
            reload(context)
        }
    }

    private suspend fun reload(context: Context) {
        runCatching { load(context) }
            .onSuccess { data ->
                _timetable.value = data
                TodayWidgetProvider.updateAll(context)
            }
    }
}
