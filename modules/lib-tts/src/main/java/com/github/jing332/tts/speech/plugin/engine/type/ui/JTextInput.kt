@file:Suppress("unused")

package com.github.jing332.tts.speech.plugin.engine.type.ui

import android.annotation.SuppressLint
import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp

/**
 * 插件 UI bean（补丁版 API 同形，Compose + Material3 渲染）：
 * JS: `let e = JTextInput(ctx, "自定义声音代号"); e.text.set("x"); e.text.toString();
 *      e.setOnTextChangedListener(function(text){...}); e.addTextChangedListener(function(text){...});
 *      e.setSingleLine(true); e.maxLines=1;`
 *
 * 注意：`text` 必须是带 set()/append()/clear() 的包装对象（千问等插件按补丁版契约调用
 * `input.text.set(...)`），而不是裸字符串；`setOnTextChangedListener` 为无 try/catch 保护
 * 的主流用法（讯飞/千问都直接调用），缺失会导致整个插件特色界面中断。
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
@SuppressLint("ViewConstructor")
class JTextInput(context: Context, val hint: CharSequence? = null) : FrameLayout(context) {

    interface OnTextChangedListener {
        fun onChanged(text: CharSequence)
    }

    private val listeners = mutableSetOf<OnTextChangedListener>()

    fun addTextChangedListener(listener: OnTextChangedListener) {
        listeners.add(listener)
    }

    fun removeTextChangedListener(listener: OnTextChangedListener) {
        listeners.remove(listener)
    }

    fun setOnTextChangedListener(listener: OnTextChangedListener) {
        listeners.add(listener)
    }

    private var mText by mutableStateOf("")

    private var mMaxLines by mutableIntStateOf(Int.MAX_VALUE)
    var maxLines: Int
        get() = mMaxLines
        set(value) {
            mMaxLines = value
        }

    fun setSingleLine(singleLine: Boolean) {
        mMaxLines = if (singleLine) 1 else Int.MAX_VALUE
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

    @Composable
    fun Content() {
        OutlinedTextField(
            value = mText,
            onValueChange = {
                mText = it
                for (listener in listeners) {
                    runCatching { listener.onChanged(it) }.onFailure { it.printStackTrace() }
                }
            },
            label = {
                if (!hint.isNullOrBlank()) {
                    Text(text = hint.toString(), maxLines = 1)
                }
            },
            maxLines = mMaxLines,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }

    val text: MyEditable by lazy { MyEditable() }

    inner class MyEditable {
        val length: Int get() = mText.length

        fun get(index: Int): Char = mText[index]

        fun set(text: CharSequence) {
            mText = text.toString()
        }

        fun get(): String = mText

        fun append(text: CharSequence) {
            mText += text
        }

        fun append(text: Char) {
            mText += text
        }

        fun clear() {
            mText = ""
        }

        override fun toString(): String = mText
    }
}
