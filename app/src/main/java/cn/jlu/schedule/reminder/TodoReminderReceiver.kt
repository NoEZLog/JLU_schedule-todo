package cn.jlu.schedule.reminder

import android.Manifest
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

/**
 * 待办提醒通知：到期或提前 N 天时提醒课程作业、考试与日常待办。
 */
class TodoReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TodoReminderScheduler.ACTION_TODO_REMINDER) return
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
                context.getString(R.string.notification_channel_todo),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        val todoId = intent.getLongExtra(EXTRA_ID, System.currentTimeMillis())
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            .ifBlank { context.getString(R.string.notification_todo_title) }
        val note = intent.getStringExtra(EXTRA_NOTE).orEmpty()
        val location = intent.getStringExtra(EXTRA_LOCATION).orEmpty()
        val advanceDays = intent.getIntExtra(EXTRA_ADVANCE_DAYS, 0)

        val body = buildString {
            when {
                advanceDays > 0 -> append("距离到期还有").append(advanceDays).append("天")
                else -> append("待办时间到了")
            }
            if (note.isNotBlank()) append(" · ").append(note)
            if (location.isNotBlank()) append("\n地点：").append(location)
        }

        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_settings_bell)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        NotificationManagerCompat.from(context).notify(
            TodoReminderScheduler.requestCode(todoId, advanceDays),
            notification
        )
    }

    companion object {
        const val CHANNEL_ID = "todo_reminder"
        const val EXTRA_ID = "todo_id"
        const val EXTRA_TITLE = "todo_title"
        const val EXTRA_NOTE = "todo_note"
        const val EXTRA_LOCATION = "todo_location"
        const val EXTRA_ADVANCE_DAYS = "todo_advance_days"
    }
}
