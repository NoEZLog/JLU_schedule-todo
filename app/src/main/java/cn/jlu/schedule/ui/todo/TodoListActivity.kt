package cn.jlu.schedule.ui.todo

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import cn.jlu.schedule.R
import cn.jlu.schedule.data.ImportedScheduleStorage
import cn.jlu.schedule.data.TaskKind
import cn.jlu.schedule.data.TodoItem
import cn.jlu.schedule.data.TodoStore
import cn.jlu.schedule.reminder.TodoReminderScheduler
import cn.jlu.schedule.ui.tools.AccountScopedActivity
import cn.jlu.schedule.ui.theme.ThemePalette
import cn.jlu.schedule.ui.theme.ThemePaletteProvider
import cn.jlu.schedule.ui.theme.UiFeedback
import cn.jlu.schedule.ui.theme.applySystemBarIcons
import cn.jlu.schedule.ui.theme.applySystemBarPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 待办管理：课程作业、考试、测验与日常事项，支持到期提醒、提前天数、图片附件与课程归组。
 */
class TodoListActivity : AccountScopedActivity() {

    private lateinit var palette: ThemePalette
    private lateinit var activeList: LinearLayout
    private lateinit var doneList: LinearLayout
    private lateinit var doneHeader: TextView
    private lateinit var emptyHint: TextView
    private lateinit var addBtn: android.widget.Button

    private val todos = mutableListOf<TodoItem>()
    private var pendingDialog: TodoEditDialog? = null
    private var selectionMode = false
    private val selectedIds = mutableSetOf<Long>()

    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCount: TextView
    private lateinit var selectAllBtn: TextView
    private lateinit var completeBtn: TextView
    private lateinit var deleteBtn: TextView
    private lateinit var exitSelectBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePaletteProvider.applyNightMode(this)
        setTheme(ThemePaletteProvider.themeStyleFor(cn.jlu.schedule.data.AppPreferences.getThemeColor(this)))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_todo_list)
        findViewById<View>(R.id.todoRoot).applySystemBarPadding()

        palette = ThemePaletteProvider.fromContext(this)
        window.applySystemBarIcons(palette.isDark)
        initViews()
        loadTodos()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        loadTodos()
    }

    private fun initViews() {
        findViewById<View>(R.id.todoRoot).setBackgroundColor(palette.pageBackground)
        findViewById<View>(R.id.todoHeader).setBackgroundColor(palette.panelAltBackground)

        val backBtn = findViewById<ImageView>(R.id.todoBackBtn)
        backBtn.imageTintList = ColorStateList.valueOf(palette.iconTint)
        backBtn.setOnClickListener { finish() }

        activeList = findViewById(R.id.todoActiveList)
        doneList = findViewById(R.id.todoDoneList)
        doneHeader = findViewById(R.id.todoDoneHeader)
        emptyHint = findViewById(R.id.todoEmptyHint)
        addBtn = findViewById(R.id.todoAddBtn)
        UiFeedback.stylePrimaryButton(addBtn, palette)
        addBtn.setOnClickListener { showEditDialog(null) }

        selectionBar = findViewById(R.id.todoSelectionBar)
        selectionCount = findViewById(R.id.todoSelectionCount)
        selectAllBtn = findViewById(R.id.todoSelectAllBtn)
        completeBtn = findViewById(R.id.todoCompleteBtn)
        deleteBtn = findViewById(R.id.todoDeleteBtn)
        exitSelectBtn = findViewById(R.id.todoExitSelectBtn)
        for (btn in listOf(selectAllBtn, completeBtn, deleteBtn, exitSelectBtn)) {
            btn.setTextColor(palette.iconTint)
        }
        deleteBtn.setTextColor(if (palette.isDark) 0xFFFF9B91.toInt() else 0xFFC62828.toInt())
        selectAllBtn.setOnClickListener { selectAll() }
        completeBtn.setOnClickListener { batchComplete() }
        deleteBtn.setOnClickListener { batchDelete() }
        exitSelectBtn.setOnClickListener { exitSelection() }
    }

    private fun loadTodos() {
        todos.clear()
        todos.addAll(withAccountData { TodoStore.load(it) }.orEmpty())
        renderTodos()
    }

    /** 全部待办写盘并对每一条重排闹钟 */
    private fun persistTodos() {
        withAccountData { TodoStore.save(it, todos) }
        for (item in todos) {
            TodoReminderScheduler.schedule(this, item)
        }
        renderTodos()
    }

    private fun renderTodos() {
        val now = System.currentTimeMillis()
        val active = todos.filter { !it.completed }.sortedBy { if (it.dueAt > 0) it.dueAt else Long.MAX_VALUE }
        val done = todos.filter { it.completed }.sortedByDescending { if (it.dueAt > 0) it.dueAt else Long.MIN_VALUE }

        selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        addBtn.visibility = if (selectionMode) View.GONE else View.VISIBLE
        selectionCount.text = "已选 ${selectedIds.size} 项"

        emptyHint.visibility = if (active.isEmpty()) View.VISIBLE else View.GONE
        activeList.visibility = if (active.isEmpty()) View.GONE else View.VISIBLE
        doneHeader.visibility = if (done.isEmpty()) View.GONE else View.VISIBLE
        doneList.visibility = if (done.isEmpty()) View.GONE else View.VISIBLE

        activeList.removeAllViews()
        for (item in active) {
            activeList.addView(buildTodoCard(item, now, isFirst = item === active.firstOrNull()))
        }
        doneList.removeAllViews()
        for (item in done) {
            doneList.addView(buildTodoCard(item, now, isFirst = item === done.firstOrNull()))
        }
    }

    private fun buildTodoCard(item: TodoItem, now: Long, isFirst: Boolean): FrameLayout {
        val isOverdue = item.dueAt in 1 until now
        val card = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = if (isFirst) 0 else dp(10) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(palette.panelAltBackground)
                setStroke(1, ColorUtils.blendARGB(palette.panelAltBackground, palette.iconTint, 0.12f))
            }
            if (item.completed) alpha = 0.55f
            UiFeedback.addPressFeedback(this, palette.iconTint, 14f)
        }

        // 内容列：标题行 + 信息行 + 附件（右侧为操作块预留 66dp）
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(66), dp(12))
        }

        // 顶行：标题 + 类型徽标
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(this).apply {
            text = item.title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.textPrimary)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(TextView(this).apply {
            text = item.taskKind.displayName
            textSize = 11f
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(7).toFloat()
                setColor(ColorUtils.setAlphaComponent(palette.iconTint, 35))
            }
            setTextColor(palette.iconTint)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        })
        content.addView(topRow)

        // 截止时间行
        if (item.dueAt > 0) {
            content.addView(infoLine(
                "⏰ " + SimpleDateFormat("M/d HH:mm", Locale.CHINA).format(Date(item.dueAt)) +
                    " · " + relativeDueText(item.dueAt, now),
                highlight = isOverdue && !item.completed
            ))
        } else {
            content.addView(infoLine("⏰ 未设置截止时间"))
        }

        if (item.courseName.isNotBlank()) {
            content.addView(infoLine("📘 ${item.courseName}"))
        }
        if (item.location.isNotBlank()) {
            content.addView(infoLine("📍 ${item.location}"))
        }
        if (item.note.isNotBlank()) {
            content.addView(infoLine("📝 ${item.note}").apply { maxLines = 2; ellipsize = null })
        }
        if (item.reminderEnabled && !item.completed && item.dueAt > now) {
            val advance = item.advanceReminderDays.filter { it > 0 }.sorted().joinToString("、") { "提前${it}天" }
            content.addView(infoLine(if (advance.isBlank()) "🔔 到期时提醒" else "🔔 $advance · 到期时提醒"))
        }
        if (item.imageUris.isNotEmpty()) {
            content.addView(buildImageStrip(item.imageUris.take(4), editable = false))
        }

        // 右侧主题深色操作块：Gravity.END 贴满卡片右缘（上下到边、圆角随卡片）
        val control = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                dp(60),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END
            )
        }
        val blockCorners = floatArrayOf(
            0f, 0f,
            dp(14).toFloat(), dp(14).toFloat(),
            dp(14).toFloat(), dp(14).toFloat(),
            0f, 0f
        )
        val darkColor = ColorUtils.blendARGB(palette.iconTint, Color.BLACK, 0.22f)
        val controlView = if (selectionMode) {
            TextView(this).apply {
                text = if (item.id in selectedIds) "✓" else ""
                textSize = 16f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                background = GradientDrawable().apply {
                    cornerRadii = blockCorners
                    setColor(if (item.id in selectedIds) darkColor else ColorUtils.setAlphaComponent(palette.textSecondary, 38))
                }
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            }
        } else {
            TextView(this).apply {
                text = if (item.completed) "撤销" else "完成"
                textSize = 13f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                background = GradientDrawable().apply {
                    cornerRadii = blockCorners
                    setColor(darkColor)
                }
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                setOnClickListener { toggleCompleted(item) }
            }
        }
        control.addView(controlView)

        card.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        card.addView(control)

        if (selectionMode) {
            card.setOnClickListener { view ->
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                toggleSelect(item.id)
            }
            card.setOnLongClickListener { view ->
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                toggleSelect(item.id); true
            }
        } else {
            card.setOnClickListener { showEditDialog(item) }
            card.setOnLongClickListener { view ->
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                enterSelection(item.id); true
            }
        }
        return card
    }

    private fun infoLine(text: String, highlight: Boolean = false): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(
                if (highlight) {
                    if (palette.isDark) 0xFFEF9A9A.toInt() else 0xFFC62828.toInt()
                } else {
                    ColorUtils.blendARGB(palette.textPrimary, palette.pageBackground, 0.35f)
                }
            )
            setPadding(0, dp(8), 0, 0)
        }
    }

    private fun buildImageStrip(uris: List<String>, editable: Boolean): LinearLayout {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        for (uri in uris) {
            val thumb = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(8) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(ColorUtils.setAlphaComponent(palette.iconTint, 24))
                }
                setImageDrawable(null)
            }
            lifecycleScope.launch {
                val bitmap = loadTodoThumbnail(this@TodoListActivity, uri)
                if (bitmap != null) thumb.setImageBitmap(bitmap)
            }
            if (editable) {
                thumb.setOnLongClickListener { onThumbnailRemove(uri); true }
            }
            strip.addView(thumb)
        }
        return strip
    }

    private fun enterSelection(todoId: Long) {
        selectionMode = true
        selectedIds.clear()
        selectedIds.add(todoId)
        renderTodos()
    }

    private fun exitSelection() {
        selectionMode = false
        selectedIds.clear()
        renderTodos()
    }

    private fun toggleSelect(todoId: Long) {
        if (selectedIds.contains(todoId)) selectedIds.remove(todoId) else selectedIds.add(todoId)
        if (selectedIds.isEmpty()) {
            exitSelection()
        } else {
            renderTodos()
        }
    }

    private fun selectAll() {
        selectedIds.clear()
        todos.forEach { selectedIds.add(it.id) }
        renderTodos()
    }

    private fun batchComplete() {
        if (selectedIds.isEmpty()) return
        var changed = 0
        for (index in todos.indices) {
            if (todos[index].id in selectedIds && !todos[index].completed) {
                todos[index] = todos[index].copy(completed = true)
                changed++
            }
        }
        selectionMode = false
        selectedIds.clear()
        if (changed > 0) {
            persistTodos()
            UiFeedback.showMessage(activeList, "已完成 $changed 项待办", palette)
        } else {
            renderTodos()
        }
    }

    private fun batchDelete() {
        if (selectedIds.isEmpty()) return
        val dialog = AlertDialog.Builder(this)
            .setTitle("批量删除")
            .setMessage("确定删除选中的 ${selectedIds.size} 项待办吗？其提醒也会一并取消。")
            .setPositiveButton("删除") { _, _ ->
                val removed = todos.filter { it.id in selectedIds }
                todos.removeAll { it.id in selectedIds }
                selectionMode = false
                selectedIds.clear()
                withAccountData { TodoStore.save(it, todos) }
                for (item in removed) {
                    TodoReminderScheduler.cancel(this, item)
                }
                renderTodos()
                UiFeedback.showMessage(activeList, "已删除 ${removed.size} 项待办", palette)
            }
            .setNegativeButton(cn.jlu.schedule.R.string.action_cancel, null)
            .create()
        UiFeedback.styleDialogSurface(dialog, palette)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { UiFeedback.styleDangerButton(it, palette) }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.let { UiFeedback.styleSecondaryButton(it, palette) }
    }

    private fun toggleCompleted(item: TodoItem) {
        val index = todos.indexOfFirst { it.id == item.id }
        if (index < 0) return
        todos[index] = item.copy(completed = !item.completed)
        persistTodos()
    }

    private fun showEditDialog(item: TodoItem?) {
        val courseNames = runCatching {
            ImportedScheduleStorage.loadActiveCourses(applicationContext.filesDir)
                .map { it.courseName }
                .filter { it.isNotBlank() }
                .distinct()
        }.getOrDefault(emptyList())
        val dialog = TodoEditDialog(this, palette, item, courseNames) { saved ->
            val index = todos.indexOfFirst { it.id == saved.id }
            if (index >= 0) todos[index] = saved else todos.add(saved)
            persistTodos()
        }
        pendingDialog = dialog
        dialog.show()
    }

    private fun showDeleteDialog(item: TodoItem) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("删除待办")
            .setMessage("确定删除「${item.title}」吗？其提醒也会一并取消。")
            .setPositiveButton("删除") { _, _ ->
                todos.removeAll { it.id == item.id }
                withAccountData { TodoStore.save(it, todos) }
                TodoReminderScheduler.cancel(this, item)
                renderTodos()
            }
            .setNegativeButton(cn.jlu.schedule.R.string.action_cancel, null)
            .create()
        UiFeedback.styleDialogSurface(dialog, palette)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { UiFeedback.styleDangerButton(it, palette) }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.let { UiFeedback.styleSecondaryButton(it, palette) }
    }

    private fun onThumbnailRemove(uri: String) {
        UiFeedback.showMessage(activeList, "编辑待办时才能移除图片附件", palette)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION_PERMISSION
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        pendingDialog?.handleActivityResult(requestCode, resultCode, data)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_NOTIFICATION_PERMISSION = 4001
    }
}

/** 截止时间的相对描述：还有X天X小时 / 已逾期X天 */
internal fun relativeDueText(dueAt: Long, now: Long): String {
    val diff = dueAt - now
    val abs = kotlin.math.abs(diff)
    val days = abs / 86_400_000
    val hours = (abs % 86_400_000) / 3_600_000
    val mins = (abs % 3_600_000) / 60_000
    val span = when {
        days > 0 -> "${days}天${hours}小时"
        hours > 0 -> "${hours}小时${mins}分钟"
        else -> "${mins}分钟"
    }
    return if (diff >= 0) "还有$span" else "已逾期$span"
}

/** IO 线程解码图片缩略图，按目标边长取采样率 */
internal suspend fun loadTodoThumbnail(context: android.content.Context, uriString: String): Bitmap? {
    return withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val target = 128
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()
    }
}
