package vtsen.hashnode.dev.newemptycomposeapp.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import vtsen.hashnode.dev.newemptycomposeapp.ui.AppTypography

// می‌تونی این‌ها رو با رنگ‌های دلخواه خودت سفارشی کنی
private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

/**
 * تم اصلی برنامه.
 * به صورت پیش‌فرض از تم سیستم (روشن/تاریک) پیروی می‌کند و در صورت پشتیبانی
 * دستگاه (اندروید ۱۲ به بالا) از رنگ‌های داینامیک (Material You) استفاده می‌کند.
 *
 * @param useSystemUIController وقتی true باشد، رنگ نوار وضعیت (status bar) و
 * روشن/تیره بودن آیکون‌هایش با تم برنامه هماهنگ می‌شود. در Preview باید false باشد
 * چون دسترسی به Activity در آن وجود ندارد.
 */
@Composable
fun NewEmptyComposeAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    useSystemUIController: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    if (useSystemUIController) {
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as Activity).window
                window.statusBarColor = colorScheme.primary.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}