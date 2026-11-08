package cn.jlu.schedule.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机或应用升级后重新注册全部待办提醒闹钟 */
class TodoBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> TodoReminderScheduler.rescheduleAll(context)
        }
    }
}
