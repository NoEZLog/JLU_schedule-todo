package cn.jlu.schedule.ui.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QuickImportCaptureTest {
    @Test
    fun responsesWithSamePrefixButDifferentCoursesAreNotDeduplicated() {
        val url = "https://iedu.jlu.edu.cn/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do"
        val commonPrefix = "x".repeat(256)
        val first = commonPrefix + "高等数学"
        val second = commonPrefix + "大学物理"

        assertNotEquals(QuickImportActivity.captureKey(url, first), QuickImportActivity.captureKey(url, second))
        assertEquals(QuickImportActivity.captureKey(url, first), QuickImportActivity.captureKey(url, first))
    }
}
