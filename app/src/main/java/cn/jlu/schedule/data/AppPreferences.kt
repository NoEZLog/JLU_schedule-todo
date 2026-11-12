package cn.jlu.schedule.data

import android.content.Context
import cn.jlu.schedule.model.Weekday

object AppPreferences {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_PINNED_COURSES = "pinned_course"
    private const val KEY_DEFAULT_OPEN_PAGE = "default_open_page"
    private const val KEY_CUSTOM_BACKGROUND_URI = "custom_background_uri"
    private const val KEY_SEMESTER_START_DATE = "semester_start_date"
    private const val KEY_THEME_COLOR = "theme_color"
    private const val KEY_TIMETABLE_FONT_SCALE = "timetable_font_scale"
    private const val KEY_SHOW_NON_CURRENT_COURSES = "show_non_current_courses"
    private const val KEY_VPN_PROXY_MODE = "vpn_proxy_mode"
    private const val KEY_VPN_CRYPT_KEY = "vpn_crypt_key"
    private const val KEY_VPN_CRYPT_IV = "vpn_crypt_iv"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_REMINDER_ENABLED = "daily_reminder_enabled"
    private const val KEY_REMINDER_MINUTE = "daily_reminder_minute"
    private const val KEY_LEARNED_SCHEDULE_ENDPOINT = "learned_schedule_endpoint"

    const val PAGE_TIMETABLE = "timetable"
    const val PAGE_TODAY = "today"
    const val THEME_WARM = "warm"
    const val THEME_OCEAN = "ocean"
    const val THEME_MINT = "mint"
    const val DARK_SYSTEM = "system"
    const val DARK_LIGHT = "light"
    const val DARK_DARK = "dark"

    fun getDarkMode(context: Context): String {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DARK_MODE, DARK_SYSTEM)
        return when (raw) {
            DARK_LIGHT, DARK_DARK -> raw ?: DARK_SYSTEM
            else -> DARK_SYSTEM
        }
    }

    fun setDarkMode(context: Context, mode: String) {
        val safe = when (mode) {
            DARK_LIGHT, DARK_DARK -> mode
            else -> DARK_SYSTEM
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DARK_MODE, safe)
            .apply()
    }

    fun getDefaultOpenPage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEFAULT_OPEN_PAGE, PAGE_TIMETABLE) ?: PAGE_TIMETABLE
    }

    fun setDefaultOpenPage(context: Context, page: String) {
        val safeValue = when (page) {
            PAGE_TODAY -> PAGE_TODAY
            else -> PAGE_TIMETABLE
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEFAULT_OPEN_PAGE, safeValue)
            .apply()
    }

    fun getCustomBackgroundUri(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_BACKGROUND_URI, null)
    }

    fun setCustomBackgroundUri(context: Context, uri: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_BACKGROUND_URI, uri)
            .apply()
    }

    fun getThemeColor(context: Context): String {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_COLOR, THEME_WARM)
        return when (raw) {
            THEME_OCEAN, THEME_MINT -> raw
            else -> THEME_WARM
        }
    }

    fun setThemeColor(context: Context, theme: String) {
        val safe = when (theme) {
            THEME_OCEAN, THEME_MINT -> theme
            else -> THEME_WARM
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_COLOR, safe)
            .apply()
    }

    fun getTimetableFontScale(context: Context): Float {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_TIMETABLE_FONT_SCALE, 0.95f)
        return when {
            saved < 0.98f -> 0.95f
            saved > 1.08f -> 1.15f
            else -> 1.0f
        }
    }

    fun setTimetableFontScale(context: Context, scale: Float) {
        val safe = when {
            scale < 0.98f -> 0.95f
            scale > 1.08f -> 1.15f
            else -> 1.0f
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_TIMETABLE_FONT_SCALE, safe)
            .apply()
    }

    fun isShowNonCurrentCourses(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_NON_CURRENT_COURSES, true)
    }

    fun isVpnProxyMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VPN_PROXY_MODE, false)
    }

    fun setVpnProxyMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VPN_PROXY_MODE, enabled).apply()
    }

    fun getVpnCryptKey(context: Context): String {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VPN_CRYPT_KEY, null) ?: return cn.jlu.schedule.remote.WebVpnConverter.KEY_JLU
        // 迁移：历史上随旧版本 App 默认持久化过的密钥不再是现役密钥，重置为当前默认
        if (stored == cn.jlu.schedule.remote.WebVpnConverter.KEY_DIDIDA ||
            stored == cn.jlu.schedule.remote.WebVpnConverter.KEY_WRD
        ) {
            setVpnCryptKey(context, cn.jlu.schedule.remote.WebVpnConverter.KEY_JLU)
            return cn.jlu.schedule.remote.WebVpnConverter.KEY_JLU
        }
        return stored
    }

    fun setVpnCryptKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_VPN_CRYPT_KEY, key).apply()
    }

    fun getVpnCryptIv(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VPN_CRYPT_IV, null)
    }

    fun setVpnCryptIv(context: Context, iv: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_VPN_CRYPT_IV, iv).apply()
    }

    fun setShowNonCurrentCourses(context: Context, show: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_NON_CURRENT_COURSES, show)
            .apply()
    }

    fun isReminderEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REMINDER_ENABLED, false)
    }

    fun setReminderEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REMINDER_ENABLED, enabled)
            .apply()
    }

    /** 提醒时间，格式为当天分钟数（0-1439），默认 7:30 */
    fun getReminderMinute(context: Context): Int {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_REMINDER_MINUTE, DEFAULT_REMINDER_MINUTE)
        return value.coerceIn(0, 24 * 60 - 1)
    }

    fun setReminderMinute(context: Context, minuteOfDay: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_REMINDER_MINUTE, minuteOfDay.coerceIn(0, 24 * 60 - 1))
            .apply()
    }

    /** 网页导入时自学习到的课表接口完整 URL（适配校内直连 / WebVPN 两种入口） */
    fun getLearnedScheduleEndpoint(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LEARNED_SCHEDULE_ENDPOINT, null)
    }

    fun setLearnedScheduleEndpoint(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LEARNED_SCHEDULE_ENDPOINT, url)
            .apply()
    }

    /**
     * 获取指定星期和节次的置顶封面课程名称
     */
    fun getPinnedCourse(context: Context, weekday: Weekday, section: Int): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("${KEY_PINNED_COURSES}_${weekday.name}_$section", null)
    }

    /**
     * 将某门课程设为指定时段的课表封面（或传入 null 清除置顶）
     */
    fun setPinnedCourseForSlot(context: Context, weekday: Weekday, sections: IntRange, courseName: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (sec in sections) {
            val key = "${KEY_PINNED_COURSES}_${weekday.name}_$sec"
            if (courseName == null) {
                editor.remove(key)
            } else {
                editor.putString(key, courseName)
            }
        }
        editor.apply()
    }

    private const val KEY_LAST_UPDATE_CHECK_TIME = "last_update_check_time"
    private const val KEY_IGNORED_UPDATE_VERSION = "ignored_update_version"

    fun getLastUpdateCheckTime(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_UPDATE_CHECK_TIME, 0L)
    }

    fun setLastUpdateCheckTime(context: Context, timestamp: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_UPDATE_CHECK_TIME, timestamp)
            .apply()
    }

    fun getIgnoredUpdateVersion(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_IGNORED_UPDATE_VERSION, -1)
    }

    fun setIgnoredUpdateVersion(context: Context, versionCode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_IGNORED_UPDATE_VERSION, versionCode)
            .apply()
    }

    const val DEFAULT_REMINDER_MINUTE = 7 * 60 + 30
}
