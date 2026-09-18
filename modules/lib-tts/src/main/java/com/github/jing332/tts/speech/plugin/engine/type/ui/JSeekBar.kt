package com.github.jing332.tts.speech.plugin.engine.type.ui

import android.annotation.SuppressLint
import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/**
 * 插件 UI bean（原生版，API 与补丁版 Compose 版同形）：
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
            syncUi()
        }

    private var mValue = 0f

    private val label: TextView = TextView(context).apply { text = hint }
    private val seekBar: SeekBar = SeekBar(context)

    init {
        val root = LinearLayout(context).apply { orientation = VERTICAL }
        addView(
            root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            seekBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                mValue = progress.toFloat()
                label.text = hint.toString() + scaleText(value)
                mListener?.onProgressChanged(this@JSeekBar, value.toInt(), fromUser)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                mListener?.onStartTrackingTouch(this@JSeekBar)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                mListener?.onStopTrackingTouch(this@JSeekBar)
            }
        })
    }

    private fun syncUi() {
        seekBar.max = if (max > 0) (max * x).toInt() else 100
        seekBar.progress = mValue.toInt().coerceIn(0, seekBar.max)
        label.text = hint.toString() + scaleText(value)
    }

    private fun scaleText(v: Float): String =
        if (n <= 0) v.toInt().toString()
        else String.format(Locale.US, "%.${n}f", v)
}