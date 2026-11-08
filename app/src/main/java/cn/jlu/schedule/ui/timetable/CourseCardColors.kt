package cn.jlu.schedule.ui.timetable

/**
 * 课程卡片色板（三端一致的 8 色循环），课表网格与课程详情横幅共用。
 *
 * 浅色模式为柔和粉彩底 + 深色文字；深色模式为同色相压暗降饱和的深底，
 * 文字改用对应粉彩原色（带色相的浅色），避免深色界面上出现大面积高亮色块。
 */
object CourseCardColors {
    val colors: IntArray = intArrayOf(
        0xFFFAD8C0.toInt(),
        0xFFC9E7FF.toInt(),
        0xFFD8F4D2.toInt(),
        0xFFFFE6A8.toInt(),
        0xFFE6D7FF.toInt(),
        0xFFFFD7E0.toInt(),
        0xFFD8F0EE.toInt(),
        0xFFFFE1C4.toInt()
    )

    private val darkColors: IntArray = intArrayOf(
        0xFF4A3221.toInt(),
        0xFF21384A.toInt(),
        0xFF2B4625.toInt(),
        0xFF4A3E21.toInt(),
        0xFF30214A.toInt(),
        0xFF4A212A.toInt(),
        0xFF2A413F.toInt(),
        0xFF4A3521.toInt()
    )

    private const val LIGHT_TEXT_COLOR = 0xFF37312A.toInt()

    fun forCourse(courseIndex: Int, isDark: Boolean = false): Int {
        return (if (isDark) darkColors else colors)[courseIndex % colors.size]
    }

    fun textColorFor(courseIndex: Int, isDark: Boolean): Int {
        return if (isDark) colors[courseIndex % colors.size] else LIGHT_TEXT_COLOR
    }
}
