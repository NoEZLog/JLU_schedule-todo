package cn.jlu.schedule.data

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 培养方案与学业完成进度本地持久化 */
object AcademicProgressStore {
    private const val STORAGE_DIR = "tools"
    private const val FILE_NAME = "academic_plan.json"
    private const val TEMP_FILE_SUFFIX = ".tmp"

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(filesDir: File): AcademicProgressPlan = synchronized(lock) {
        val file = planFile(filesDir)
        if (!file.exists()) return AcademicProgressPlan()
        return runCatching {
            json.decodeFromString(AcademicProgressPlan.serializer(), file.readText(Charsets.UTF_8))
        }.getOrElse { AcademicProgressPlan() }
    }

    fun save(filesDir: File, plan: AcademicProgressPlan): Unit = synchronized(lock) {
        val file = planFile(filesDir)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + TEMP_FILE_SUFFIX)
        tmp.writeText(json.encodeToString(AcademicProgressPlan.serializer(), plan), Charsets.UTF_8)
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
        val file = planFile(filesDir)
        if (file.exists() && !file.delete()) {
            throw java.io.IOException("无法删除学业进度文件")
        }
    }

    private fun planFile(filesDir: File): File = File(File(filesDir, STORAGE_DIR), FILE_NAME)
}
