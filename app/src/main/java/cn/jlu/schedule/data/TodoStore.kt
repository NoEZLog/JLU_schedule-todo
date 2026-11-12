package cn.jlu.schedule.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 待办事项本地持久化。
 *
 * 待办属于设备个人数据（与登录状态无关），存储在应用根 filesDir/tools/todos.json，
 * 不随校园账号作用域切换而变化。首次加载时自动迁移历史版本遗留在
 * tool_accounts/<scope>/tools/ 下的账号作用域数据（按 id 去重合并）。
 */
object TodoStore {
    private const val STORAGE_DIR = "tools"
    private const val FILE_NAME = "todos.json"
    private const val TEMP_FILE_SUFFIX = ".tmp"
    private const val LEGACY_ACCOUNT_DIR = "tool_accounts"

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** filesDir 为应用根目录；文件位于 <filesDir>/tools/todos.json */
    fun load(filesDir: File): List<TodoItem> = synchronized(lock) {
        migrateLegacyScopes(filesDir)
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

    /** 历史版本把待办存在账号作用域目录下；合并到全局文件并把旧文件改名作废 */
    private fun migrateLegacyScopes(filesDir: File) {
        val global = todosFile(filesDir)
        if (global.exists()) return // 已迁移（全局文件存在即视为完成）
        val accountRoot = File(filesDir, LEGACY_ACCOUNT_DIR)
        val legacyFiles = mutableListOf<File>()
        // 旧版本一：账号作用域根下直接放 todos.json
        accountRoot.listFiles { file -> file.isFile && file.name == FILE_NAME }
            .orEmpty().forEach { legacyFiles.add(it) }
        // 旧版本二：各账号目录 <scope>/tools/todos.json
        accountRoot.listFiles { dir -> dir.isDirectory }.orEmpty().forEach { dir ->
            val f = File(dir, "$STORAGE_DIR/$FILE_NAME")
            if (f.isFile) legacyFiles.add(f)
        }
        if (legacyFiles.isEmpty()) return

        val merged = LinkedHashMap<Long, TodoItem>()
        for (source in legacyFiles) {
            runCatching {
                val items = json.decodeFromString(
                    ListSerializer(TodoItem.serializer()),
                    source.readText(Charsets.UTF_8)
                )
                for (item in items) {
                    val existing = merged[item.id]
                    if (existing == null || item.createdAt >= existing.createdAt) merged[item.id] = item
                }
            }
        }
        if (merged.isNotEmpty()) {
            runCatching { save(filesDir, merged.values.toList()) }
        }
        legacyFiles.forEach { runCatching { it.renameTo(File(it.parentFile, "$FILE_NAME.bak")) } }
    }
}
