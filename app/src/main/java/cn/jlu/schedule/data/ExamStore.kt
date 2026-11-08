package cn.jlu.schedule.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 考试日程本地持久化（存储教务同步或手动添加的考试安排） */
object ExamStore {
    private const val STORAGE_DIR = "tools"
    private const val FILE_NAME = "exams.json"
    private const val TEMP_FILE_SUFFIX = ".tmp"

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(filesDir: File): List<ExamItem> = synchronized(lock) {
        val file = examsFile(filesDir)
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ExamItem.serializer()), file.readText(Charsets.UTF_8))
        }.getOrElse { emptyList() }
    }

    fun save(filesDir: File, exams: List<ExamItem>): Unit = synchronized(lock) {
        val file = examsFile(filesDir)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + TEMP_FILE_SUFFIX)
        tmp.writeText(json.encodeToString(ListSerializer(ExamItem.serializer()), exams), Charsets.UTF_8)
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
        val file = examsFile(filesDir)
        if (file.exists() && !file.delete()) {
            throw java.io.IOException("无法删除考试日程文件")
        }
    }

    private fun examsFile(filesDir: File): File = File(File(filesDir, STORAGE_DIR), FILE_NAME)
}
