package cn.jlu.schedule.data

import cn.jlu.schedule.domain.GpaCourse
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 绩点计算器手动录入课程的本地持久化（工具数据，独立于课表存储） */
object GpaCourseStore {
    private const val STORAGE_DIR = "tools"
    private const val FILE_NAME = "gpa_courses.json"
    private const val TEMP_FILE_SUFFIX = ".tmp"

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(filesDir: File): List<GpaCourse> = synchronized(lock) {
        val file = coursesFile(filesDir)
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(GpaCourse.serializer()), file.readText(Charsets.UTF_8))
        }.getOrElse { emptyList() }
    }

    fun save(filesDir: File, courses: List<GpaCourse>): Unit = synchronized(lock) {
        val file = coursesFile(filesDir)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + TEMP_FILE_SUFFIX)
        tmp.writeText(json.encodeToString(ListSerializer(GpaCourse.serializer()), courses), Charsets.UTF_8)
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

    /** 读改写在同一把锁内完成，避免与计算器页面的保存交错。 */
    fun update(filesDir: File, transform: (List<GpaCourse>) -> List<GpaCourse>): Unit = synchronized(lock) {
        save(filesDir, transform(load(filesDir)))
    }

    fun clear(filesDir: File): Unit = synchronized(lock) {
        val file = coursesFile(filesDir)
        if (file.exists() && !file.delete()) {
            throw java.io.IOException("无法删除绩点课程文件")
        }
    }

    private fun coursesFile(filesDir: File): File = File(File(filesDir, STORAGE_DIR), FILE_NAME)
}
