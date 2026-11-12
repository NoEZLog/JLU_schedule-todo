package cn.jlu.schedule.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import cn.jlu.schedule.data.TodoItem

/**
 * 待办提醒调度：到期时刻 + 提前 N 天各安排一次闹钟。
 * 优先使用精确闹钟保证提醒准时；未授予精确闹钟权限时降级为非精确闹钟。
 */
object TodoReminderScheduler {
    const val ACTION_TODO_REMINDER = "cn.jlu.schedule.action.TODO_REMINDER"

    /** 与 ClassCue 一致的可选提前提醒天数 */
    val SUPPORTED_ADVANCE_DAYS = listOf(1, 2, 3, 7, 14)

    private const val REQUEST_CODE_OFFSET_ADVANCE = 100

    /** 由待办 id 派生稳定的通知/闹钟请求码；提前提醒在基础码上偏移，避免互相覆盖 */
    fun requestCode(todoId: Long, advanceDays: Int): Int {
        val base = (todoId xor (todoId ushr 32)).toInt()
        return if (advanceDays > 0) base + REQUEST_CODE_OFFSET_ADVANCE + advanceDays else base
    }

    /** 为单个待办重排全部闹钟（先取消旧的，再按启用状态安排） */
    fun schedule(context: Context, item: TodoItem) {
        cancel(context, item)
        if (!item.reminderEnabled || item.completed || item.dueAt <= 0L) return
        scheduleOne(context, item, item.dueAt, 0)
        for (days in item.advanceReminderDays) {
            if (days > 0) scheduleOne(context, item, item.dueAt - days * 86_400_000L, days)
        }
    }

    fun cancel(context: Context, item: TodoItem) {
        val alarmManager = getSystemService(context) ?: return
        val allDays = intArrayOf(0) + SUPPORTED_ADVANCE_DAYS.toIntArray()
        for (days in allDays) {
            val pi = alarmPendingIntent(context, item.id, "", "", "", days)
            alarmManager.cancel(pi)
        }
    }

    /** 开机/升级/恢复备份后全量重排全部待办 */
    fun rescheduleAll(context: Context) {
        for (item in TodoSync.load(context)) schedule(context, item)
    }

    private fun scheduleOne(context: Context, item: TodoItem, triggerAt: Long, advanceDays: Int) {
        if (triggerAt <= System.currentTimeMillis()) return
        val alarmManager = getSystemService(context) ?: return
        val pendingIntent = alarmPendingIntent(
            context, item.id, item.title, item.note, item.location, advanceDays
        )
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun alarmPendingIntent(
        context: Context,
        todoId: Long,
        title: String,
        note: String,
        location: String,
        advanceDays: Int
    ): PendingIntent {
        val intent = Intent(context, TodoReminderReceiver::class.java).setAction(ACTION_TODO_REMINDER)
            .putExtra(TodoReminderReceiver.EXTRA_ID, todoId)
            .putExtra(TodoReminderReceiver.EXTRA_TITLE, title)
            .putExtra(TodoReminderReceiver.EXTRA_NOTE, note)
            .putExtra(TodoReminderReceiver.EXTRA_LOCATION, location)
            .putExtra(TodoReminderReceiver.EXTRA_ADVANCE_DAYS, advanceDays)
        return PendingIntent.getBroadcast(
            context,
            requestCode(todoId, advanceDays),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getSystemService(context: Context): AlarmManager? =
        androidx.core.content.ContextCompat.getSystemService(context, AlarmManager::class.java)
}
