@file:Suppress("unused")

package com.github.jing332.tts.speech.plugin.engine.type.ui

import android.annotation.SuppressLint
import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 插件 UI bean（补丁版 API 同形，Compose + Material3 渲染）：
 * JS: `let sp = JSpinner(ctx,"音效模式"); sp.items=[Item("关闭","0"),...]; sp.selectedPosition=2;
 *      sp.value="0"; sp.setOnItemSelected(function(spinner,pos,item){...});`
 */
@Suppress("MemberVisibilityCanBePrivate")
@SuppressLint("ViewConstructor")
class JSpinner(context: Context, val hint: CharSequence) : FrameLayout(context) {
    companion object {
        const val TAG = "JSpinner"
    }

    private val mItems = mutableStateListOf<Item>()
    var items: List<Item>
        get() = mItems
        set(value) {
            mItems.clear()
            mItems += value
        }

    private var mSelectedPosition by mutableIntStateOf(0)

    var selectedPosition: Int
        get() = mSelectedPosition
        set(value) {
            mSelectedPosition = value.coerceIn(0, max(0, mItems.size - 1))
        }

    var value: Any?
        get() = mItems.getOrNull(mSelectedPosition) ?: Item("null", Unit)
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
        val item = mItems.getOrElse(mSelectedPosition) { mItems.getOrNull(0) }
        var expanded by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth()) {
            if (hint.isNotBlank()) {
                Text(
                    text = hint.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
            Box {
                Surface(
                    onClick = { if (mItems.isNotEmpty()) expanded = true },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = item?.name?.toString() ?: "未选择",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    mItems.forEachIndexed { index, it ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = it.name.toString(),
                                    color = if (index == mSelectedPosition) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            },
                            onClick = {
                                expanded = false
                                mSelectedPosition = index
                                mListener?.onItemSelected(this@JSpinner, index, it)
                            },
                        )
                    }
                }
            }
        }
    }
}
