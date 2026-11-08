package cn.jlu.schedule.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.jlu.schedule.MainActivity
import cn.jlu.schedule.R
import cn.jlu.schedule.data.AppPreferences
import cn.jlu.schedule.data.ImportedScheduleStorage
import cn.jlu.schedule.domain.SectionTimes
import cn.jlu.schedule.domain.WeekScheduleCalculator
import java.time.LocalDate
import java.time.ZoneId

/**
 * 每日课程提醒：每天在用户设定的时间发送今日课程概览通知。
 * 使用 setAndAllowWhileIdle 非精确闹钟，无需精确闹钟权限；开机后自动重排。
 */
class DailyReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DAILY_REMINDER -> {
                if (AppPreferences.isReminderEnabled(context)) {
                    showReminderNotification(context)
                }
                ReminderScheduler.reschedule(context)
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                ReminderScheduler.reschedule(context)
            }
        }
    }

    private fun showReminderNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_reminder),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        val text = buildReminderText(context)
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle(context.getString(R.string.notification_reminder_title))
            .setContentText(text.first)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.first + text.second))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun buildReminderText(context: Context): Pair<String, String> {
        return runCatching {
            val files = context.filesDir
            val courses = ImportedScheduleStorage.loadActiveCourses(files)
            val semesterStart = ImportedScheduleStorage.getActiveSemesterStartDate(files)
            val today = LocalDate.now()
            val week = WeekScheduleCalculator.activeWeekOrNull(
                semesterStart,
                today,
                WeekScheduleCalculator.totalWeeks(courses)
            )
            val weekday = when (today.dayOfWeek.value) {
                1 -> java.time.DayOfWeek.MONDAY
                2 -> java.time.DayOfWeek.TUESDAY
                3 -> java.time.DayOfWeek.WEDNESDAY
                4 -> java.time.DayOfWeek.THURSDAY
                5 -> java.time.DayOfWeek.FRIDAY
                6 -> java.time.DayOfWeek.SATURDAY
                else -> java.time.DayOfWeek.SUNDAY
            }
            val meetings = week?.let { WeekScheduleCalculator.meetingsForWeek(courses, it) }.orEmpty()
                .filter { it.meeting.weekday.name == weekday.name }
                .distinctBy {
                    listOf(
                        it.course.courseName,
                        it.meeting.startSection,
                        it.meeting.endSection,
                        it.meeting.location
                    )
                }
                .sortedBy { it.meeting.startSection }
            if (meetings.isEmpty()) {
                Pair(context.getString(R.string.notification_reminder_empty), "")
            } else {
                val first = meetings.first()
                val startTime = SectionTimes.DEFAULT_RANGES.getOrNull(first.meeting.startSection - 1)
                    ?.substringBefore('-') ?: "--:--"
                val headline = context.getString(R.string.notification_reminder_title) +
                    " · ${meetings.size} 门，最早 $startTime ${first.course.courseName}"
                val detail = meetings.take(5).joinToString("\n") { ref ->
                    "第${ref.meeting.startSection}-${ref.meeting.endSection}节 ${ref.course.courseName}" +
                        if (ref.meeting.location.isBlank()) "" else " ${ref.meeting.location}"
                }
                Pair(headline, "\n$detail")
            }
        }.getOrDefault(Pair(context.getString(R.string.notification_reminder_empty), ""))
    }

    companion object {
        const val CHANNEL_ID = "daily_reminder"
        const val NOTIFICATION_ID = 1001
        const val ACTION_DAILY_REMINDER = "cn.jlu.schedule.action.DAILY_REMINDER"
    }
}

object ReminderScheduler {
    private const val REQUEST_CODE = 2001

    fun reschedule(context: Context) {
        val alarmManager = ContextCompat.getSystemService(context, AlarmManager::class.java) ?: return
        val pendingIntent = alarmPendingIntent(context)
        if (!AppPreferences.isReminderEnabled(context)) {
            alarmManager.cancel(pendingIntent)
            return
        }
        val triggerAt = nextTriggerMillis(AppPreferences.getReminderMinute(context))
        // 非精确闹钟即可满足"每天早上提醒"，避免申请精确闹钟权限
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    }

    private fun nextTriggerMillis(minuteOfDay: Int): Long {
        val now = System.currentTimeMillis()
        val todayTarget = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() +
            minuteOfDay * 60_000L
        var trigger = todayTarget
        while (trigger <= now) {
            trigger += 24 * 60 * 60_000L
        }
        return trigger
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DailyReminderReceiver::class.java).setAction(
            DailyReminderReceiver.ACTION_DAILY_REMINDER
        )
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
