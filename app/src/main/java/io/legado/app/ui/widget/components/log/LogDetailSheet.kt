package io.legado.app.ui.widget.components.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.text.AppText

/**
 * 诊断/日志详情：固定高度对话框，正文独立滚动，长日志不会被外层弹窗抢走手势。
 * 配置列表与插件登录页共用同一条组件。
 */
@Composable
fun LogDetailSheet(
    show: Boolean,
    title: String = "Log",
    content: String,
    onDismissRequest: () -> Unit,
    onCopy: (() -> Unit)? = null,
) {
    if (!show) return
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f),
                shape = RoundedCornerShape(24.dp),
                color = LegadoTheme.colorScheme.surfaceContainer,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                ) {
                    AppText(
                        text = title,
                        style = LegadoTheme.typography.titleMediumEmphasized,
                    )
                    Spacer(Modifier.height(12.dp))
                    SelectionContainer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            AppText(
                                text = content,
                                style = LegadoTheme.typography.bodySmall,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onCopy != null) {
                            MediumTonalButton(
                                onClick = onCopy,
                                text = "复制",
                                contentDescription = "复制诊断",
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        MediumTonalButton(
                            onClick = onDismissRequest,
                            text = if (onCopy != null) "关闭" else "确定",
                            contentDescription = "关闭",
                        )
                    }
                }
            }
        }
    }
}
