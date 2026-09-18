@file:Suppress("unused")

package com.github.jing332.tts.speech.plugin.engine.type.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.max

/**
 * 插件 UI bean（原生版，API 与补丁版 Compose 版同形）：
 * JS: `let sp = JSpinner(ctx,"音效模式"); sp.items=[Item("关闭","0"),...]; sp.selectedPosition=2;
 *      sp.setOnItemSelected(function(spinner,pos,item){...});`
 */
@Suppress("MemberVisibilityCanBePrivate")
@SuppressLint("ViewConstructor")
class JSpinner(context: Context, val hint: CharSequence) : FrameLayout(context) {
    companion object {
        const val TAG = "JSpinner"
    }

    private var mItems: List<Item> = emptyList()
    var items: List<Item>
        get() = mItems
        set(value) {
            mItems = value
            rebuild()
        }

    private var mSelectedPosition = 0
    var selectedPosition: Int
        get() = mSelectedPosition
        set(value) {
            val pos = value.coerceIn(0, max(0, mItems.size - 1))
            mSelectedPosition = pos
            suppress = true
            spinner.setSelection(pos)
            suppress = false
        }

    var value: Any?
        get() = mItems.getOrNull(mSelectedPosition)?.value
        set(value) {
            selectedPosition = max(0, mItems.indexOfFirst { it.value == value })
        }

    interface OnItemSelectedListener {
        fun onItemSelected(spinner: JSpinner, position: Int, item: Item)
    }

    private var mListener: OnItemSelectedListener? = null

    fun setOnItemSelected(listener: OnItemSelectedListener?) {
        mListener = listener
    }

    private val label: TextView = TextView(context).apply {
        text = hint
        visibility = if (hint.isBlank()) View.GONE else View.VISIBLE
    }
    private val spinner: Spinner = Spinner(context)
    private var suppress = false

    init {
        orientation = VERTICAL
        addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            spinner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (suppress) return
                mSelectedPosition = position
                val item = mItems.getOrNull(position) ?: return
                mListener?.onItemSelected(this@JSpinner, position, item)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun rebuild() {
        val names = mItems.map { it.name.toString() }
        spinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            names,
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        if (mSelectedPosition >= names.size) mSelectedPosition = max(0, names.size - 1)
        suppress = true
        spinner.setSelection(mSelectedPosition)
        suppress = false
    }
}