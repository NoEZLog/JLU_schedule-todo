package cn.jlu.schedule.remote

import android.content.Context
import cn.jlu.schedule.data.ImportedScheduleStorage
import cn.jlu.schedule.data.ScheduleRepository
import cn.jlu.schedule.parser.ScheduleImportCacheParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一键导入编排：解析已捕获的课表响应 → 复用与网页导入一致的学期筛选逻辑 → 入库。
 * 捕获环节由 QuickImportActivity 的隐藏 WebView 完成（复用内置浏览器登录 Cookie）。
 */
object AutoImportCoordinator {

    /** 结果阶段 */
    sealed class Phase {
        data class Done(val courseCount: Int, val newProfileName: String?) : Phase()
        data class Failed(val message: String) : Phase()
    }

    /** 解析捕获的接口响应并入库；entries 为捕获到的一个或多个课表 JSON 落盘 */
    suspend fun importCaptured(
        context: Context,
        entries: List<ScheduleImportCacheParser.CacheEntry>,
        mode: ImportedScheduleStorage.ImportMode,
        newProfileName: String?
    ): Phase = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext Phase.Failed("未捕获到课表数据")
        val parseResult = try {
            ScheduleImportCacheParser.parse(entries)
        } catch (error: Exception) {
            return@withContext Phase.Failed(error.message ?: "解析课表失败")
        }
        if (parseResult.courses.isEmpty()) {
            return@withContext Phase.Failed("教务返回了空课表")
        }
        ScheduleRepository.importParsedCourses(
            context,
            parseResult.courses,
            mode,
            newProfileName,
            parseResult.inferredSemesterStartDate
        ).fold(
            onSuccess = { Phase.Done(it.courseCount, newProfileName) },
            onFailure = { Phase.Failed(it.message ?: "导入失败") }
        )
    }
}
