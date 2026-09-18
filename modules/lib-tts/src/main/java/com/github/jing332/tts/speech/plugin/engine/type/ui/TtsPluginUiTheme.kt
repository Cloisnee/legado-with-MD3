package com.github.jing332.tts.speech.plugin.engine.type.ui

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * 插件特色界面主题：
 * 跟随宿主（阅读 MD3）的深浅色与 Material You 动态取色，
 * 使插件 onLoadUI 里的 JSpinner/JSeekBar/JTextInput 与 App 观感一致。
 */
@Composable
internal fun TtsPluginUiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme() ||
            (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val context = LocalContext.current
    val scheme = when {
        dark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicDarkColorScheme(context)
        !dark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
