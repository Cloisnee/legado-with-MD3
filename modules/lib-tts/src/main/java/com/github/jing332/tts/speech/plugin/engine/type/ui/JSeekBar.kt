@file:Suppress("unused")

package com.github.jing332.tts.speech.plugin.engine.type.ui

import android.annotation.SuppressLint
import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * 插件 UI bean（补丁版 API 同形，Compose + Material3 渲染）：
 * JS: `let sb = JSeekBar(ctx,"情感强度："); sb.max=40; sb.value=new java.lang.Float(20);
 *      sb.setOnChangeListener({ onStartTrackingTouch:.., onProgressChanged:.., onStopTrackingTouch:.. });`
 */
@Suppress("unused")
@SuppressLint("ViewConstructor")
class JSeekBar(context: Context, val hint: CharSequence) : FrameLayout(context) {

    interface OnSeekBarChangeListener {
        fun onStartTrackingTouch(seekBar: JSeekBar)
        fun onProgressChanged(seekBar: JSeekBar, progress: Int, fromUser: Boolean)
        fun onStopTrackingTouch(seekBar: JSeekBar)
    }

    private var mListener: OnSeekBarChangeListener? = null

    fun setOnChangeListener(listener: OnSeekBarChangeListener?) {
        mListener = listener
    }

    init {
        val composeView = ComposeView(context)
        addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        composeView.setContent {
            TtsPluginUiTheme { Content() }
        }
    }

    @JvmField
    var max = 0

    private var n = 0
    private var x = 1f

    fun setFloatType(n: Int) {
        this.n = n
        x = 1f
        for (i in 1..n) {
            x *= 10f
        }
    }

    var value: Float
        get() = mValue / x
        set(value) {
            mValue = value * x
            mListener?.onProgressChanged(this@JSeekBar, value.toInt(), false)
        }

    private var mValue by mutableFloatStateOf(0f)

    @Composable
    fun Content() {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = hint.toString() + scaleText(value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            Slider(
                value = mValue,
                onValueChange = {
                    mValue = it
                    mListener?.onProgressChanged(this@JSeekBar, value.toInt(), true)
                },
                onValueChangeFinished = {
                    mListener?.onStopTrackingTouch(this@JSeekBar)
                },
                valueRange = 0f..(max.toFloat() * x).coerceAtLeast(0.0001f),
            )
        }
    }

    private fun scaleText(v: Float): String =
        if (n <= 0) v.toInt().toString()
        else String.format(Locale.US, "%.${n}f", v)
}
