package cn.jlu.schedule.reminder

import android.content.Context
import cn.jlu.schedule.data.TodoItem
import cn.jlu.schedule.data.TodoStore

/** 跨界面共享的待办读写入口：全局作用域存储，写盘后同步提醒闹钟 */
object TodoSync {

    /** 读取全部待办 */
    fun load(context: Context): List<TodoItem> = TodoStore.load(context.filesDir)

    /** 整表保存（不重排闹钟，调用方自行处理） */
    fun saveAll(context: Context, todos: List<TodoItem>) {
        TodoStore.save(context.filesDir, todos)
    }

    /** 新增或更新单条待办并重排其提醒 */
    fun upsert(context: Context, item: TodoItem) {
        val todos = load(context).filterNot { it.id == item.id } + item
        saveAll(context, todos)
        TodoReminderScheduler.schedule(context, item)
    }

    /** 删除待办并取消其提醒 */
    fun delete(context: Context, item: TodoItem) {
        val todos = load(context).filterNot { it.id == item.id }
        saveAll(context, todos)
        TodoReminderScheduler.cancel(context, item)
    }
}
