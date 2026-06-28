package dev.local.smsforwarder

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Tasteful indigo fallback for pre-Android-12 devices; on the Pixel (API 31+)
// we use Material You dynamic color drawn from the wallpaper.
private val FallbackLight = lightColorScheme(
    primary = Color(0xFF4355B9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE0FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondaryContainer = Color(0xFFDCE1FF),
    onSecondaryContainer = Color(0xFF141B2C),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFBBC3FF),
    onPrimary = Color(0xFF0D1A6B),
    primaryContainer = Color(0xFF2A3A9F),
    onPrimaryContainer = Color(0xFFDFE0FF),
)

@Composable
fun SmsForwarderTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
