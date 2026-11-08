package cn.jlu.schedule.ui.today

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cn.jlu.schedule.R
import cn.jlu.schedule.data.AccountDataContext
import cn.jlu.schedule.data.AppPreferences
import cn.jlu.schedule.data.ScheduleRepository
import cn.jlu.schedule.data.TaskKind
import cn.jlu.schedule.data.TodoStore
import cn.jlu.schedule.reminder.TodoSync
import cn.jlu.schedule.ui.todo.TodoEditDialog
import cn.jlu.schedule.ui.todo.TodoListActivity
import cn.jlu.schedule.domain.SectionTimes
import cn.jlu.schedule.domain.WeekScheduleCalculator
import cn.jlu.schedule.model.Weekday
import cn.jlu.schedule.ui.theme.ThemePalette
import cn.jlu.schedule.ui.theme.ThemePaletteProvider
import cn.jlu.schedule.ui.theme.SurfaceStyle
import cn.jlu.schedule.ui.theme.UiFeedback
import cn.jlu.schedule.ui.timetable.CourseCardColors
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class TodayScheduleFragment : Fragment() {

    private var lastData: ScheduleRepository.TimetableUiData? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_today_schedule, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ScheduleRepository.timetable.collect { data ->
                    if (data != null) {
                        renderToday(data)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 跨午夜或数据变更后回到本页时刷新，避免显示过期日程
        ScheduleRepository.refresh(requireContext())
    }

    @SuppressLint("SetTextI18n")
    private fun renderToday(data: ScheduleRepository.TimetableUiData) {
        lastData = data
        val view = this.view ?: return
        val title = view.findViewById<TextView>(R.id.todayTitle)
        val subTitle = view.findViewById<TextView>(R.id.todaySubTitle)
        val countChip = view.findViewById<TextView>(R.id.todayCountChip)
        val firstClass = view.findViewById<TextView>(R.id.todayFirstClass)
        val lastClass = view.findViewById<TextView>(R.id.todayLastClass)
        val list = view.findViewById<LinearLayout>(R.id.todayCourseList)
        val root = view.findViewById<LinearLayout>(R.id.todayContainer)

        val periodTimeRanges = SectionTimes.DEFAULT_RANGES
        val palette = ThemePaletteProvider.fromContext(requireContext())
        val themeColors = ThemeColors(
            card = palette.panelBackground,
            text = palette.textPrimary,
            subText = palette.textSecondary,
            emptyCard = palette.panelAltBackground
        )
        val hasCustomBackground = !AppPreferences.getCustomBackgroundUri(requireContext()).isNullOrBlank()
        root.setBackgroundColor(
            if (hasCustomBackground) Color.TRANSPARENT else palette.pageBackground
        )
        SurfaceStyle.apply(view.findViewById(R.id.todaySummaryCard), palette, SurfaceStyle.Variant.Panel, 16f)
        countChip.background = SurfaceStyle.drawable(requireContext(), palette, SurfaceStyle.Variant.Control, 12f)
        firstClass.background = SurfaceStyle.drawable(requireContext(), palette, SurfaceStyle.Variant.Panel, 12f)
        lastClass.background = SurfaceStyle.drawable(requireContext(), palette, SurfaceStyle.Variant.Panel, 12f)
        title.setTextColor(themeColors.text)
        subTitle.setTextColor(themeColors.subText)
        countChip.setTextColor(themeColors.text)
        firstClass.setTextColor(themeColors.subText)
        lastClass.setTextColor(themeColors.subText)

        val today = LocalDate.now()
        val todayWeekday = weekdayFromJava(today.dayOfWeek.value)
        title.text = "今日日程"
        subTitle.text = String.format(
            Locale.getDefault(),
            "%s 周%s",
            today.format(DateTimeFormatter.ofPattern("yyyy/M/d")),
            label(todayWeekday)
        )

        val ctx = requireContext()
        val courses = data.courses
        val week = WeekScheduleCalculator.activeWeekOrNull(data.semesterStart, today, data.totalWeeks)
        val todayMeetings = week?.let { WeekScheduleCalculator.meetingsForWeek(courses, it) }.orEmpty()
            .filter { it.meeting.weekday == todayWeekday }
            .distinctBy {
                listOf(
                    it.course.courseName,
                    it.course.teacher,
                    it.meeting.startSection.toString(),
                    it.meeting.endSection.toString(),
                    it.meeting.location
                ).joinToString("|")
            }
            .sortedBy { it.meeting.startSection }

        list.removeAllViews()
        list.addView(buildTodoEntryRow(ctx, palette, themeColors))
        if (todayMeetings.isEmpty()) {
            countChip.text = "0 门课"
            firstClass.text = "最早：无"
            lastClass.text = "最晚：无"
            list.addView(TextView(ctx).apply {
                text = "今天没有课程安排"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColors.text)
                setPadding(18, 24, 18, 24)
                background = roundedBackground(themeColors.emptyCard)
            })
            renderTodayTodos(ctx, list, palette, themeColors, hasCustomBackground)
            return
        }

        countChip.text = String.format(Locale.getDefault(), "%d 门课", todayMeetings.size)
        val firstStart = todayMeetings.minOf { it.meeting.startSection }.coerceIn(1, periodTimeRanges.size)
        val lastEnd = todayMeetings.maxOf { it.meeting.endSection }.coerceIn(1, periodTimeRanges.size)
        firstClass.text = String.format(
            Locale.getDefault(),
            "最早：%s",
            periodTimeRanges[firstStart - 1].substringBefore('-')
        )
        lastClass.text = String.format(
            Locale.getDefault(),
            "最晚：%s",
            periodTimeRanges[lastEnd - 1].substringAfter('-')
        )

        val activeSection = currentSection()
        todayMeetings.forEachIndexed { index, item ->
            val start = item.meeting.startSection.coerceIn(1, periodTimeRanges.size)
            val end = item.meeting.endSection.coerceIn(start, periodTimeRanges.size)
            val isActive = activeSection != null && start <= activeSection && activeSection <= end

            // 时间列：开始-结束时刻
            val timeColumn = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                }
                if (hasCustomBackground) {
                    background = roundedBackground(palette.panelAltBackground)
                    setPadding(0, dp(6), 0, dp(6))
                }
                addView(TextView(ctx).apply {
                    text = periodTimeRanges[start - 1].substringBefore('-')
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(themeColors.text)
                })
                addView(TextView(ctx).apply {
                    text = periodTimeRanges[end - 1].substringAfter('-')
                    textSize = 10f
                    setTextColor(themeColors.subText)
                    alpha = if (hasCustomBackground) 1f else 0.8f
                })
            }

            // 课程卡：左侧色条与课表格子同色，便于跨页面对应
            val accent = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = dp(10)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 2f
                    setColor(CourseCardColors.forCourse(item.courseIndex, palette.isDark))
                }
            }

            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val nameRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(ctx).apply {
                        text = item.course.courseName
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(themeColors.text)
                    })
                    if (isActive) {
                        addView(TextView(ctx).apply {
                            text = "进行中"
                            textSize = 10f
                            setTextColor(Color.WHITE)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = dp(9).toFloat()
                                setColor(themeColors.subText)
                            }
                            setPadding(dp(7), dp(2), dp(7), dp(2))
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                marginStart = dp(8)
                            }
                        })
                    }
                }
                addView(nameRow)
                addView(TextView(ctx).apply {
                    text = "第${start}-${end}节  ${periodTimeRanges[start - 1].substringBefore('-')}-" +
                        periodTimeRanges[end - 1].substringAfter('-')
                    textSize = 13f
                    setTextColor(themeColors.subText)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(3)
                    }
                })
                addView(TextView(ctx).apply {
                    text = item.meeting.location.ifBlank { "教室待定" }
                    textSize = 13f
                    setTextColor(themeColors.subText)
                    alpha = if (hasCustomBackground) 1f else 0.9f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(2)
                    }
                })
            }

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(if (isActive) themeColors.emptyCard else themeColors.card)
                }
                SurfaceStyle.addDepth(this, palette, if (isActive) 3f else 2f)
                addView(accent)
                addView(content)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = if (index == 0) 0 else dp(10)
                }
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                addView(timeColumn)
                addView(card)
            }
            list.addView(row)
        }

        renderTodayTodos(ctx, list, palette, themeColors, hasCustomBackground)
    }

    /** 顶部常显的「待办管理」入口卡片：无论当天是否有到期待办都展示 */
    private fun buildTodoEntryRow(
        ctx: android.content.Context,
        palette: ThemePalette,
        themeColors: ThemeColors
    ): View {
        val todos = TodoSync.load(ctx)
        val now = System.currentTimeMillis()
        val dayEnd = LocalDate.now().plusDays(1)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val dueToday = todos.count { !it.completed && it.dueAt > 0 && it.dueAt < dayEnd }
        val overdue = todos.count { !it.completed && it.dueAt in 1 until now }
        val open = todos.count { !it.completed }

        val statusText = when {
            open == 0 -> "暂无待办 ›"
            overdue > 0 && dueToday > 0 -> "逾期 $overdue · 今日 $dueToday ›"
            overdue > 0 -> "逾期 $overdue 项 ›"
            dueToday > 0 -> "今日到期 $dueToday 项 ›"
            else -> "未完成 $open 项 ›"
        }
        val statusColor = if (overdue > 0) {
            if (palette.isDark) 0xFFEF9A9A.toInt() else 0xFFC62828.toInt()
        } else {
            themeColors.subText
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(themeColors.emptyCard)
            }
            SurfaceStyle.addDepth(this, palette, 2f)
            UiFeedback.addPressFeedback(this, palette.iconTint, 16f)
            setOnClickListener {
                startActivity(android.content.Intent(ctx, TodoListActivity::class.java))
            }

            addView(TextView(ctx).apply {
                text = "🗒 待办管理"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColors.text)
            })
            addView(TextView(ctx).apply {
                text = statusText
                textSize = 13f
                setTextColor(statusColor)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(12) }
            })
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(TextView(ctx).apply {
                text = "＋ 新建"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.iconTint)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(palette.iconTint, 28))
                    setStroke(
                        1,
                        androidx.core.graphics.ColorUtils.blendARGB(palette.panelBackground, palette.iconTint, 0.35f)
                    )
                }
                setOnClickListener { showQuickNewTodo(ctx, palette) }
            })
        }
        return row
    }

    /** 日程页快捷新建待办：课程建议来自当前课表，保存后立即刷新本页 */
    private fun showQuickNewTodo(ctx: android.content.Context, palette: ThemePalette) {
        val activity = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                activity, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_TODO_NOTIFICATION
            )
        }
        val courseNames = runCatching {
            cn.jlu.schedule.data.ImportedScheduleStorage.loadActiveCourses(ctx.filesDir)
                .map { it.courseName }
                .filter { it.isNotBlank() }
                .distinct()
        }.getOrDefault(emptyList())
        TodoEditDialog(activity, palette, existing = null, courseNames = courseNames) { saved ->
            cn.jlu.schedule.reminder.TodoSync.upsert(activity, saved)
            android.widget.Toast.makeText(
                ctx, ctx.getString(cn.jlu.schedule.R.string.todo_created_toast, saved.title),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            lastData?.let { renderToday(it) }
        }.show()
    }

    /** 「今日待办」分区：展示今天到期与已逾期的待办事项，点击进入待办管理 */
    private fun renderTodayTodos(
        ctx: android.content.Context,
        list: LinearLayout,
        palette: ThemePalette,
        themeColors: ThemeColors,
        hasCustomBackground: Boolean
    ) {
        val todos = AccountDataContext.get(ctx.filesDir).capture().use { dir ->
            TodoStore.load(dir)
        }.orEmpty()
        val now = System.currentTimeMillis()
        val dayStart = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val dayEnd = dayStart + 86_400_000L
        val relevant = todos.filter { !it.completed && it.dueAt > 0 && it.dueAt < dayEnd }
            .sortedBy { it.dueAt }
        if (relevant.isEmpty()) return

        val header = TextView(ctx).apply {
            text = String.format(Locale.getDefault(), "今日待办 · %d", relevant.size)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColors.text)
            setPadding(0, dp(18), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        header.setOnClickListener {
            startActivity(android.content.Intent(ctx, TodoListActivity::class.java))
        }
        list.addView(header)

        relevant.forEachIndexed { index, item ->
            val overdue = item.dueAt < now
            val timeColumn = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                }
                if (hasCustomBackground) {
                    background = roundedBackground(palette.panelAltBackground)
                    setPadding(0, dp(6), 0, dp(6))
                }
                addView(TextView(ctx).apply {
                    text = java.time.Instant.ofEpochMilli(item.dueAt)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (overdue && !hasCustomBackground) 0xFFC62828.toInt() else themeColors.text)
                })
                addView(TextView(ctx).apply {
                    text = if (overdue) "逾期" else "今天"
                    textSize = 10f
                    setTextColor(themeColors.subText)
                })
            }

            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = item.title
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(themeColors.text)
                })
                addView(TextView(ctx).apply {
                    text = buildString {
                        append(item.taskKind.displayName)
                        if (item.courseName.isNotBlank()) append(" · ").append(item.courseName)
                        if (item.location.isNotBlank()) append(" · ").append(item.location)
                    }
                    textSize = 13f
                    setTextColor(themeColors.subText)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(3) }
                })
            }

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(themeColors.card)
                }
                SurfaceStyle.addDepth(this, palette, 2f)
                addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        marginEnd = dp(10)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 2f
                        setColor(palette.iconTint)
                    }
                })
                addView(content)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else dp(10) }
            }
            card.setOnClickListener {
                startActivity(android.content.Intent(ctx, TodoListActivity::class.java))
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                addView(timeColumn)
                addView(card)
            }
            list.addView(row)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun currentSection(): Int? {
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        val now = java.time.LocalTime.now().format(formatter)
        for ((index, range) in SectionTimes.DEFAULT_RANGES.withIndex()) {
            val parts = range.split("-")
            if (parts.size == 2 && parts[0] <= now && now < parts[1]) {
                return index + 1
            }
        }
        return null
    }

    private fun weekdayFromJava(dayValue: Int): Weekday {
        return when (dayValue) {
            1 -> Weekday.MONDAY
            2 -> Weekday.TUESDAY
            3 -> Weekday.WEDNESDAY
            4 -> Weekday.THURSDAY
            5 -> Weekday.FRIDAY
            6 -> Weekday.SATURDAY
            else -> Weekday.SUNDAY
        }
    }

    private fun label(weekday: Weekday): String {
        return when (weekday) {
            Weekday.MONDAY -> "一"
            Weekday.TUESDAY -> "二"
            Weekday.WEDNESDAY -> "三"
            Weekday.THURSDAY -> "四"
            Weekday.FRIDAY -> "五"
            Weekday.SATURDAY -> "六"
            Weekday.SUNDAY -> "日"
        }
    }

    private fun roundedBackground(fill: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f
            setColor(fill)
        }
    }

    private fun withAlpha(color: Int, alphaFactor: Float): Int {
        val alpha = (((color ushr 24) and 0xFF) * alphaFactor).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private data class ThemeColors(
        val card: Int,
        val text: Int,
        val subText: Int,
        val emptyCard: Int
    )

    private companion object {
        const val REQ_TODO_NOTIFICATION = 7001
    }
}
