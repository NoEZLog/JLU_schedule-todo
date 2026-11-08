package cn.jlu.schedule.ui.todo

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import cn.jlu.schedule.R
import cn.jlu.schedule.data.TaskKind
import cn.jlu.schedule.data.TodoItem
import cn.jlu.schedule.reminder.TodoReminderScheduler
import cn.jlu.schedule.ui.theme.SurfaceStyle
import cn.jlu.schedule.ui.theme.ThemePalette
import cn.jlu.schedule.ui.theme.UiFeedback
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 待办编辑弹窗：标题、类型、课程、截止时间、地点、备注、提醒与提前天数、图片附件。
 */
class TodoEditDialog(
    private val activity: AppCompatActivity,
    private val palette: ThemePalette,
    private val existing: TodoItem?,
    private val courseNames: List<String>,
    private val presetCourseName: String = "",
    private val onSaved: (TodoItem) -> Unit
) {
    private var kind: TaskKind = existing?.taskKind ?: TaskKind.GENERAL
    private var dueAtMillis: Long = existing?.dueAt ?: defaultDueAt()
    private val selectedAdvance = linkedSetOf<Int>()
    private val imageUris = mutableListOf<String>()
    private var reminderEnabled: Boolean = existing?.reminderEnabled ?: true

    private lateinit var kindChips: Map<TaskKind, TextView>
    private lateinit var advanceChips: Map<Int, TextView>
    private lateinit var dueInput: EditText
    private lateinit var reminderSwitch: SwitchCompat
    private lateinit var advanceRow: LinearLayout
    private lateinit var advanceLabel: TextView
    private lateinit var imageStrip: LinearLayout
    private lateinit var dialogView: View
    private var dialog: AlertDialog? = null

    fun show() {
        selectedAdvance.clear()
        selectedAdvance.addAll(existing?.advanceReminderDays?.filter { it > 0 }.orEmpty())
        imageUris.clear()
        imageUris.addAll(existing?.imageUris.orEmpty())

        dialogView = activity.layoutInflater.inflate(R.layout.dialog_todo_edit, null)
        val titleInput = dialogView.findViewById<EditText>(R.id.inputTodoTitle)
        val courseInput = dialogView.findViewById<AutoCompleteTextView>(R.id.inputTodoCourse)
        dueInput = dialogView.findViewById(R.id.inputTodoDue)
        val locationInput = dialogView.findViewById<EditText>(R.id.inputTodoLocation)
        val noteInput = dialogView.findViewById<EditText>(R.id.inputTodoNote)
        reminderSwitch = dialogView.findViewById(R.id.switchTodoReminder)
        advanceRow = dialogView.findViewById(R.id.advanceTodoRow)
        advanceLabel = dialogView.findViewById(R.id.labelTodoAdvance)
        imageStrip = dialogView.findViewById(R.id.todoImageStrip)
        val addImageBtn = dialogView.findViewById<TextView>(R.id.todoAddImageBtn)

        UiFeedback.styleInput(titleInput, palette)
        UiFeedback.styleInput(courseInput, palette)
        UiFeedback.styleInput(dueInput, palette)
        UiFeedback.styleInput(locationInput, palette)
        UiFeedback.styleInput(noteInput, palette)
        titleInput.setText(existing?.title.orEmpty())
        courseInput.setText(
            existing?.courseName?.takeIf { it.isNotBlank() } ?: presetCourseName
        )
        locationInput.setText(existing?.location.orEmpty())
        noteInput.setText(existing?.note.orEmpty())
        if (courseNames.isNotEmpty()) {
            courseInput.setAdapter(
                ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, courseNames)
            )
        }
        advanceLabel.setTextColor(palette.textSecondary)
        addImageBtn.setTextColor(palette.iconTint)

        // 截止时间输入框：点击弹出日期/时间选择器
        dueInput.isClickable = true
        dueInput.setOnClickListener { pickDueDate() }

        reminderSwitch.isChecked = reminderEnabled
        reminderSwitch.setOnCheckedChangeListener { _, checked ->
            reminderEnabled = checked
            restyleAdvanceRow()
        }

        buildKindRow(dialogView.findViewById(R.id.kindTodoRow))
        buildAdvanceRow()
        refreshDueText()
        refreshImageStrip()

        addImageBtn.setOnClickListener { pickImages() }

        // 底部 iOS 风格胶囊按钮
        val cancelBtn = dialogView.findViewById<TextView>(R.id.btnTodoCancel)
        val saveBtn = dialogView.findViewById<TextView>(R.id.btnTodoSave)
        styleCapsuleButton(cancelBtn, palette, primary = false)
        styleCapsuleButton(saveBtn, palette, primary = true)
        cancelBtn.setOnClickListener { dialog?.dismiss() }
        saveBtn.setOnClickListener { attemptSave(titleInput) }

        val alertDialog = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) "新建待办" else "编辑待办")
            .setView(dialogView)
            .create()
        dialog = alertDialog
        UiFeedback.styleDialogSurface(alertDialog, palette)
        alertDialog.show()
    }

    private fun styleCapsuleButton(button: TextView, palette: ThemePalette, primary: Boolean) {
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = button.resources.displayMetrics.density * 18
            if (primary) {
                setColor(palette.iconTint)
                setStroke(1, ColorUtils.blendARGB(palette.iconTint, palette.panelBackground, 0.25f))
            } else {
                setColor(palette.panelBackground)
                setStroke(1, ColorUtils.blendARGB(palette.panelBackground, palette.textSecondary, 0.35f))
            }
        }
        button.setTextColor(if (primary) Color.WHITE else palette.textSecondary)
        UiFeedback.addPressFeedback(button, palette.iconTint, 18f)
    }

    private fun attemptSave(titleInput: EditText) {
        val title = titleInput.text.toString().trim()
        if (title.isBlank()) {
            UiFeedback.showMessage(dialogView, "请填写待办标题", palette)
            return
        }
        val item = TodoItem(
            id = existing?.id ?: System.currentTimeMillis(),
            title = title,
            note = (dialogView.findViewById<EditText>(R.id.inputTodoNote)).text.toString().trim(),
            location = (dialogView.findViewById<EditText>(R.id.inputTodoLocation)).text.toString().trim(),
            dueAt = dueAtMillis,
            completed = existing?.completed ?: false,
            reminderEnabled = reminderEnabled,
            advanceReminderDays = selectedAdvance.filter { it > 0 }.sorted(),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            courseName = (dialogView.findViewById<AutoCompleteTextView>(R.id.inputTodoCourse)).text.toString().trim(),
            taskKind = kind,
            imageUris = imageUris.toList()
        )
        dialog?.dismiss()
        onSaved(item)
    }

    private fun buildKindRow(row: LinearLayout) {
        val chips = linkedMapOf<TaskKind, TextView>()
        for (taskKind in TaskKind.entries) {
            chips[taskKind] = TextView(activity).apply {
                text = taskKind.displayName
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(6), dp(14), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    kind = taskKind
                    restyleKindChips()
                }
            }
            row.addView(chips.getValue(taskKind))
        }
        kindChips = chips
        restyleKindChips()
    }

    private fun restyleKindChips() {
        for ((taskKind, chip) in kindChips) {
            val selected = taskKind == kind
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                if (selected) {
                    setColor(palette.iconTint)
                } else {
                    setColor(ColorUtils.setAlphaComponent(palette.panelAltBackground, 0xFF))
                    setStroke(1, ColorUtils.setAlphaComponent(palette.textPrimary, 40))
                }
            }
            chip.setTextColor(if (selected) Color.WHITE else palette.textSecondary)
            chip.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun buildAdvanceRow() {
        val chips = linkedMapOf<Int, TextView>()
        advanceRow.removeAllViews()
        for (days in TodoReminderScheduler.SUPPORTED_ADVANCE_DAYS) {
            chips[days] = TextView(activity).apply {
                text = "${days}天"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    if (selectedAdvance.contains(days)) selectedAdvance.remove(days) else selectedAdvance.add(days)
                    restyleAdvanceRow()
                }
            }
            advanceRow.addView(chips.getValue(days))
        }
        advanceChips = chips
        restyleAdvanceRow()
    }

    private fun restyleAdvanceRow() {
        val enabled = reminderEnabled
        advanceRow.alpha = if (enabled) 1f else 0.4f
        advanceLabel.alpha = if (enabled) 1f else 0.4f
        for ((days, chip) in advanceChips) {
            val selected = enabled && selectedAdvance.contains(days)
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                if (selected) {
                    setColor(ColorUtils.setAlphaComponent(palette.iconTint, 70))
                    setStroke(1, palette.iconTint)
                } else {
                    setColor(ColorUtils.setAlphaComponent(palette.panelAltBackground, 0xFF))
                    setStroke(1, ColorUtils.setAlphaComponent(palette.textPrimary, 40))
                }
            }
            chip.setTextColor(if (selected) palette.iconTint else palette.textSecondary)
            chip.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun refreshDueText() {
        dueInput.setText(
            SimpleDateFormat("yyyy/M/d HH:mm", Locale.CHINA).format(Date(dueAtMillis))
        )
    }

    private fun pickDueDate() {
        val calendar = Calendar.getInstance().apply { timeInMillis = dueAtMillis }
        DatePickerDialog(
            activity,
            { _, year, month, day ->
                val time = Calendar.getInstance().apply { timeInMillis = dueAtMillis }
                pickDueTime(year, month, day, time.get(Calendar.HOUR_OF_DAY), time.get(Calendar.MINUTE))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun pickDueTime(year: Int, month: Int, day: Int, hour: Int, minute: Int) {
        TimePickerDialog(
            activity,
            { _, h, m ->
                dueAtMillis = Calendar.getInstance().apply {
                    set(year, month, day, h, m, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                refreshDueText()
            },
            hour,
            minute,
            true
        ).show()
    }

    private fun refreshImageStrip() {
        imageStrip.removeAllViews()
        for (uri in imageUris) {
            val thumb = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(8) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(ColorUtils.setAlphaComponent(palette.iconTint, 24))
                }
                contentDescription = "长按移除附件"
            }
            activity.lifecycleScope.launch {
                val bitmap = loadTodoThumbnail(activity, uri)
                if (bitmap != null) thumb.setImageBitmap(bitmap)
            }
            thumb.setOnLongClickListener {
                imageUris.remove(uri)
                refreshImageStrip()
                true
            }
            imageStrip.addView(thumb)
        }
        if (imageUris.isEmpty()) {
            imageStrip.addView(TextView(activity).apply {
                text = activity.getString(R.string.todo_images_empty)
                textSize = 12f
                setTextColor(palette.textSecondary)
                setPadding(0, dp(10), 0, dp(4))
            })
        }
    }

    private fun pickImages() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        activity.startActivityForResult(
            Intent.createChooser(intent, activity.getString(R.string.todo_add_image)),
            REQUEST_PICK_IMAGES
        )
    }

    /** 由宿主 Activity 转发图片选择结果；返回 true 表示已消费 */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_PICK_IMAGES || resultCode != android.app.Activity.RESULT_OK) return false
        val collected = mutableListOf<String>()
        val clip = data?.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                collected.add(clip.getItemAt(i).uri.toString())
            }
        } else if (data?.data != null) {
            collected.add(data.data.toString())
        }
        for (raw in collected) {
            if (imageUris.contains(raw)) continue
            runCatching {
                activity.contentResolver.takePersistableUriPermission(
                    Uri.parse(raw),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            imageUris.add(raw)
        }
        refreshImageStrip()
        return true
    }

    private fun defaultDueAt(): Long {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PICK_IMAGES = 5001
    }
}
