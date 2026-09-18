package com.github.jing332.tts.speech.plugin.engine.type.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * 插件 UI bean（原生版，API 与补丁版 Compose 版同形）：
 * JS: `let e = JTextInput(ctx, "自定义声音代号"); e.addTextChangedListener(function(text){...}); e.text.toString()`
 */
@Suppress("unused")
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

    private val editText = EditText(context).apply {
        hint = this@JTextInput.hint
        maxLines = Int.MAX_VALUE
    }

    init {
        addView(
            editText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s ?: ""
                listeners.forEach { runCatching { it.onChanged(text) } }
            }
        })
    }

    var text: CharSequence
        get() = editText.text?.toString().orEmpty()
        set(value) {
            editText.setText(value)
        }

    var maxLines: Int
        get() = editText.maxLines
        set(value) {
            editText.maxLines = value
        }
}