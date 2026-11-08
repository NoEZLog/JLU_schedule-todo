package cn.jlu.schedule.data

import cn.jlu.schedule.domain.GpaCalculator
import cn.jlu.schedule.domain.GpaCourse
import cn.jlu.schedule.domain.GpaGradeType
import cn.jlu.schedule.domain.ImportedGrade
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 成绩查询本地持久化（存储教务同步、手动录入或与绩点计算器共享的成绩列表） */
object GradeStore {
    private const val STORAGE_DIR = "tools"
    private const val FILE_NAME = "grades.json"
    private const val TEMP_FILE_SUFFIX = ".tmp"

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(filesDir: File): List<ImportedGrade> = synchronized(lock) {
        val file = gradesFile(filesDir)
        if (file.exists()) {
            return runCatching {
                json.decodeFromString(ListSerializer(ImportedGrade.serializer()), file.readText(Charsets.UTF_8))
            }.getOrElse { emptyList() }
        }

        // 仅首次没有 grades.json 时从绩点数据迁移；已保存的 [] 表示用户明确清空。
        val gpaCourses = runCatching { GpaCourseStore.load(filesDir) }.getOrDefault(emptyList())
        if (gpaCourses.isNotEmpty()) {
            val converted = convertFromGpaCourses(gpaCourses)
            if (converted.isNotEmpty()) {
                runCatching { save(filesDir, converted) }
                return converted
            }
        }
        return emptyList()
    }

    fun save(filesDir: File, grades: List<ImportedGrade>): Unit = synchronized(lock) {
        val file = gradesFile(filesDir)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + TEMP_FILE_SUFFIX)
        tmp.writeText(json.encodeToString(ListSerializer(ImportedGrade.serializer()), grades), Charsets.UTF_8)
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
        save(filesDir, emptyList())
    }

    /** 将 GpaCourse 转换为 ImportedGrade，提取嵌入在 id 中的课程代码与学期 */
    fun convertFromGpaCourses(courses: List<GpaCourse>): List<ImportedGrade> {
        return courses.map { c ->
            var code = ""
            var sem = ""
            if (c.id.startsWith("jw-")) {
                val parts = c.id.removePrefix("jw-").split("-")
                if (parts.size >= 4) {
                    code = parts[0]
                    sem = "${parts[1]}-${parts[2]}-${parts[3]}"
                } else if (parts.size >= 2) {
                    code = parts[0]
                    sem = parts.subList(1, parts.size).joinToString("-")
                }
            }
            val scoreText = if (c.gradeType == GpaGradeType.LEVEL5 && c.level.isNotBlank()) {
                c.level
            } else if (c.score % 1.0 == 0.0) {
                c.score.toInt().toString()
            } else {
                c.score.toString()
            }
            ImportedGrade(
                courseCode = code,
                name = c.name,
                credit = c.credit,
                scoreText = scoreText,
                semesterCode = sem,
                isCustom = !c.id.startsWith("jw-")
            )
        }
    }

    /** 教务结果可能只覆盖部分学期；保留未匹配旧记录，并优先保留用户修改。 */
    fun mergeSyncedGrades(existing: List<ImportedGrade>, fetched: List<ImportedGrade>): List<ImportedGrade> {
        val kept = existing.filter { old ->
            old.isCustom || fetched.none { remote -> sameCourse(old, remote) }
        }
        val remote = fetched.filterNot { grade ->
            kept.any { it.isCustom && sameCourse(it, grade) }
        }
        return kept + remote
    }

    private fun sameCourse(a: ImportedGrade, b: ImportedGrade): Boolean {
        if (!a.semesterCode.trim().equals(b.semesterCode.trim(), ignoreCase = true)) return false
        if (a.courseCode.isNotBlank() && b.courseCode.isNotBlank()) {
            return a.courseCode.trim().equals(b.courseCode.trim(), ignoreCase = true)
        }
        return a.name.isNotBlank() && a.name.trim().equals(b.name.trim(), ignoreCase = true)
    }

    /** 将 ImportedGrade 同步推入 GpaCourseStore，供绩点计算器共享使用 */
    fun syncToGpaCourses(filesDir: File, grades: List<ImportedGrade>) {
        val imported = GpaCalculator.mergeImported(grades)
        GpaCourseStore.update(filesDir) { existing ->
            val previousImported = existing.filter { it.id.startsWith("jw-") }
            val manual = existing.filterNot { it.id.startsWith("jw-") }
            manual + imported.map { course ->
                val previous = previousImported.firstOrNull { it.id == course.id }
                    ?: previousImported.firstOrNull { importIdentity(it.id) == importIdentity(course.id) }
                course.copy(included = previous?.included ?: true)
            }
        }
    }

    private fun importIdentity(id: String): String =
        id.replace(Regex("-\\d{4}-\\d{4}-[12]$"), "")

    private fun gradesFile(filesDir: File): File = File(File(filesDir, STORAGE_DIR), FILE_NAME)
}
