package cn.jlu.schedule.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 待办事项本地持久化（手动添加的课程作业、考试与日常待办） */
object TodoStore {
    private const val STORAGE_DIR = "tools"
    private const val FILE_NAME = "todos.json"
    private const val TEMP_FILE_SUFFIX = ".tmp"

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(filesDir: File): List<TodoItem> = synchronized(lock) {
        val file = todosFile(filesDir)
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(TodoItem.serializer()), file.readText(Charsets.UTF_8))
        }.getOrElse { emptyList() }
    }

    fun save(filesDir: File, todos: List<TodoItem>): Unit = synchronized(lock) {
        val file = todosFile(filesDir)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + TEMP_FILE_SUFFIX)
        tmp.writeText(json.encodeToString(ListSerializer(TodoItem.serializer()), todos), Charsets.UTF_8)
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun clear(filesDir: File): Unit = synchronized(lock) {
        val file = todosFile(filesDir)
        if (file.exists() && !file.delete()) {
            throw java.io.IOException("无法删除待办事项文件")
        }
    }

    private fun todosFile(filesDir: File): File = File(File(filesDir, STORAGE_DIR), FILE_NAME)
}
