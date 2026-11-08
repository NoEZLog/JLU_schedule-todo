package cn.jlu.schedule.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import cn.jlu.schedule.MainActivity
import cn.jlu.schedule.R
import cn.jlu.schedule.data.ImportedScheduleStorage
import cn.jlu.schedule.domain.SectionTimes
import cn.jlu.schedule.domain.WeekScheduleCalculator
import cn.jlu.schedule.model.Weekday
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 今日课程桌面小组件：显示当前周次与今天要上的课。
 * 每 30 分钟系统刷新一次；课表数据变化时由 ScheduleRepository 主动触发更新。
 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val data = readSnapshot(context)
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildRemoteViews(context, data))
        }
    }

    companion object {
        fun updateAll(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val manager = AppWidgetManager.getInstance(context)
                    val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
                    if (ids.isEmpty()) return@runCatching
                    val data = readSnapshot(context)
                    ids.forEach { id ->
                        manager.updateAppWidget(id, buildRemoteViews(context, data))
                    }
                }
            }
        }

        private fun buildRemoteViews(context: Context, data: Snapshot): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_today)
            views.setTextViewText(R.id.widgetTitle, context.getString(R.string.widget_today_title))
            views.setTextViewText(R.id.widgetSubtitle, data.subtitle)
            views.removeAllViews(R.id.widgetCourseList)
            if (data.items.isEmpty()) {
                views.setViewVisibility(R.id.widgetEmpty, android.view.View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widgetEmpty, android.view.View.GONE)
                data.items.forEach { item ->
                    val row = RemoteViews(context.packageName, R.layout.widget_course_item)
                    row.setTextViewText(R.id.widgetItemName, item.name)
                    row.setTextViewText(R.id.widgetItemDetail, item.detail)
                    views.addView(R.id.widgetCourseList, row)
                }
            }
            val openIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, openIntent)
            return views
        }

        private fun readSnapshot(context: Context): Snapshot {
            return runCatching {
                val files = context.filesDir
                val courses = ImportedScheduleStorage.loadActiveCourses(files)
                val semesterStart = ImportedScheduleStorage.getActiveSemesterStartDate(files)
                val today = LocalDate.now()
                val totalWeeks = WeekScheduleCalculator.totalWeeks(courses)
                val week = WeekScheduleCalculator.activeWeekOrNull(semesterStart, today, totalWeeks)
                val weekdayLabel = when (today.dayOfWeek.value) {
                    1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; else -> "日"
                }
                val subtitle = "${today.format(DateTimeFormatter.ofPattern("M月d日"))} 周$weekdayLabel · " +
                    (week?.let { "第${it}周" } ?: "学期外")
                val items = week?.let { WeekScheduleCalculator.meetingsForWeek(courses, it) }.orEmpty()
                    .filter { it.meeting.weekday == weekdayFrom(today) }
                    .distinctBy {
                        listOf(
                            it.course.courseName,
                            it.meeting.startSection,
                            it.meeting.endSection,
                            it.meeting.location
                        )
                    }
                    .sortedBy { it.meeting.startSection }
                    .take(6)
                    .map { ref ->
                        val start = ref.meeting.startSection.coerceIn(1, SectionTimes.DEFAULT_RANGES.size)
                        val end = ref.meeting.endSection.coerceIn(start, SectionTimes.DEFAULT_RANGES.size)
                        val time = SectionTimes.DEFAULT_RANGES[start - 1].substringBefore('-') + " " +
                            SectionTimes.DEFAULT_RANGES[end - 1].substringAfter('-')
                        WidgetItem(
                            name = ref.course.courseName,
                            detail = "第${start}-${end}节 $time" +
                                if (ref.meeting.location.isBlank()) "" else " · ${ref.meeting.location}"
                        )
                    }
                Snapshot(subtitle, items)
            }.getOrDefault(Snapshot("", emptyList()))
        }

        private fun weekdayFrom(date: LocalDate): Weekday = when (date.dayOfWeek.value) {
            1 -> Weekday.MONDAY
            2 -> Weekday.TUESDAY
            3 -> Weekday.WEDNESDAY
            4 -> Weekday.THURSDAY
            5 -> Weekday.FRIDAY
            6 -> Weekday.SATURDAY
            else -> Weekday.SUNDAY
        }

        private data class WidgetItem(val name: String, val detail: String)

        private data class Snapshot(val subtitle: String, val items: List<WidgetItem>)
    }
}
