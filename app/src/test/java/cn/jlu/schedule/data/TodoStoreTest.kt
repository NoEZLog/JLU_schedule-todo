package cn.jlu.schedule.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TodoStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private val sample = TodoItem(
        id = 1699900000000L,
        title = "高等数学作业三",
        note = "第三章习题 3.1-3.8",
        location = "经信教学楼 C203",
        dueAt = 1699987200000L,
        completed = false,
        reminderEnabled = true,
        advanceReminderDays = listOf(1, 3),
        createdAt = 1699900000000L,
        courseName = "高等数学",
        taskKind = TaskKind.HOMEWORK,
        imageUris = listOf("content://test/attachment/1")
    )

    @Test
    fun saveAndLoadRoundTripsAllFields() {
        TodoStore.save(temp.root, listOf(sample))
        val loaded = TodoStore.load(temp.root)
        assertEquals(listOf(sample), loaded)
    }

    @Test
    fun loadToleratesCorruptFile() {
        val file = java.io.File(temp.root, "tools/todos.json")
        file.parentFile!!.mkdirs()
        file.writeText("{invalid json")
        assertTrue(TodoStore.load(temp.root).isEmpty())
        assertEquals("{invalid json", file.readText())
    }

    /** ClassCue 的本地 JSON 字段名与本模型一致，老数据可直接解析 */
    @Test
    fun decodesClassCueJsonFormat() {
        val classCueJson = """
            [{
              "id": 1699900000000,
              "title": "数据结构实验报告",
              "note": "周四交",
              "location": "计算机楼 A305",
              "dueAt": 1699987200000,
              "completed": false,
              "reminderEnabled": true,
              "advanceReminderDays": [1, 2, 7],
              "createdAt": 1699900000000,
              "courseName": "数据结构",
              "taskKind": "EXAM",
              "imageUris": ["content://test/1"]
            }]
        """.trimIndent()
        val file = java.io.File(temp.root, "tools/todos.json")
        file.parentFile!!.mkdirs()
        file.writeText(classCueJson)

        val loaded = TodoStore.load(temp.root)
        assertEquals(1, loaded.size)
        val item = loaded.first()
        assertEquals("数据结构实验报告", item.title)
        assertEquals(TaskKind.EXAM, item.taskKind)
        assertEquals(listOf(1, 2, 7), item.advanceReminderDays)
        assertEquals("数据结构", item.courseName)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val json = Json { ignoreUnknownKeys = true }.decodeFromString(
            TodoItem.serializer(),
            """{"id":1,"title":"测试","legacyField":"x","dueAt":100}"""
        )
        assertEquals("测试", json.title)
        assertEquals(100L, json.dueAt)
    }

    @Test
    fun backupCodecKeepsTodosAlongsideProfiles() {
        val backupTodo = with(ScheduleBackupCodec) { sample.toBackupTodo() }
        val encoded = ScheduleBackupCodec.encode(
            exportedAt = "2026-11-08T18:00:00",
            profiles = listOf(
                ScheduleBackupCodec.BackupProfile(
                    name = "默认课表",
                    courses = emptyList()
                )
            ),
            todos = listOf(backupTodo)
        )

        val file = ScheduleBackupCodec.decodeFile(encoded)
        assertTrue(file != null)
        assertEquals(1, file!!.profiles.size)
        assertEquals(listOf(sample), file.todos.map { with(ScheduleBackupCodec) { it.toTodoItem() } })

        // 旧版 decode 只关心课表，不受 todos 影响
        assertEquals(1, ScheduleBackupCodec.decode(encoded)!!.size)
    }

    @Test
    fun backupCodecToleratesUnknownTaskKindAndOldFormat() {
        val restored = with(ScheduleBackupCodec) {
            ScheduleBackupCodec.BackupTodo(
                id = 1L, title = "旧数据", taskKind = "UNKNOWN_KIND"
            ).toTodoItem()
        }
        assertEquals(TaskKind.GENERAL, restored.taskKind)

        // 没有 todos 字段的旧备份也能解码，todos 为空
        val legacy = """{"version":1,"exportedAt":"2025-01-01T00:00:00","profiles":[]}"""
        val file = ScheduleBackupCodec.decodeFile(legacy)
        assertTrue(file != null && file!!.todos.isEmpty())
    }
}
