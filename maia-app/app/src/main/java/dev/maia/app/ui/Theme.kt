package dev.maia.app.ui

import android.app.Activity
import android.view.View
import android.view.Window
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

private val Dark = darkColorScheme()
private val Light = lightColorScheme()

@Composable
fun MaiaTheme(window: Window? = null, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    SystemBarIcons(light = !dark, window)
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        content = content,
    )
}

/**
 * Dark status and navigation bar icons on the light ground. The bars are
 * transparent, so without this the white icons vanish on `#F4F5F7`.
 */
@Composable
private fun SystemBarIcons(light: Boolean, given: Window?) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = given ?: view.window() ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}

/** The activity's window, or a dialog's. The assistant session passes its own. */
private fun View.window(): Window? =
    (parent as? DialogWindowProvider)?.window ?: (context as? Activity)?.window
