package cn.jlu.schedule.reminder

import android.content.Context
import cn.jlu.schedule.data.AccountDataContext
import cn.jlu.schedule.data.TodoItem
import cn.jlu.schedule.data.TodoStore

/** 跨界面共享的待办读写入口：始终作用于当前账号作用域，并在写盘后同步提醒闹钟 */
object TodoSync {

    /** 读取当前作用域的全部待办 */
    fun load(context: Context): List<TodoItem> =
        AccountDataContext.get(context.filesDir).capture().use { dir ->
            TodoStore.load(dir)
        }.orEmpty()

    /** 新增或更新单条待办并重排其提醒 */
    fun upsert(context: Context, item: TodoItem) {
        val todos = load(context).filterNot { it.id == item.id } + item
        AccountDataContext.get(context.filesDir).capture().use { dir ->
            TodoStore.save(dir, todos)
        }
        TodoReminderScheduler.schedule(context, item)
    }

    /** 删除待办并取消其提醒 */
    fun delete(context: Context, item: TodoItem) {
        val todos = load(context).filterNot { it.id == item.id }
        AccountDataContext.get(context.filesDir).capture().use { dir ->
            TodoStore.save(dir, todos)
        }
        TodoReminderScheduler.cancel(context, item)
    }
}
