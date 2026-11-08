package cn.jlu.schedule.ui.theme

import android.view.View
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** Keep page content clear of system bars and cutouts without accumulating padding. */
fun View.applySystemBarPadding() {
    val initialLeft = paddingLeft
    val initialTop = paddingTop
    val initialRight = paddingRight
    val initialBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val safeArea = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.updatePadding(
            left = initialLeft + safeArea.left,
            top = initialTop + safeArea.top,
            right = initialRight + safeArea.right,
            bottom = initialBottom + safeArea.bottom
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

fun Window.applySystemBarIcons(isDark: Boolean) {
    WindowCompat.getInsetsController(this, decorView).apply {
        isAppearanceLightStatusBars = !isDark
        isAppearanceLightNavigationBars = !isDark
    }
}
