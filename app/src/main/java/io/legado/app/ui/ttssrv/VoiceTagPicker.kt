package io.legado.app.ui.ttssrv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.data.repository.ReadAloudDataRepository
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem

/**
 * 声线标签选择（共享组件）：
 *  - 标签池 = 仅在「引擎与音色 → 配置列表」中被选中的声线池（多选）内提取
 *  - 按 类型×性别×年龄 期望前缀筛选 + 搜索；未选池给明确引导
 */
data class VoiceTagPickRequest(
    val roletype: String,
    val gender: String,
    val age: String,
    val title: String,
    val onPicked: (String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceTagPickerSheet(
    show: Boolean,
    request: VoiceTagPickRequest?,
    repo: ReadAloudDataRepository,
    onDismiss: () -> Unit,
) {
    var pool by remember { mutableStateOf<List<String>?>(null) }
    var query by remember { mutableStateOf("") }
    var emptyHint by remember { mutableStateOf("") }

    LaunchedEffect(show, request) {
        if (show && request != null) {
            pool = null
            query = ""
            emptyHint = ""
            val list = repo.loadVoiceTagPool()
            pool = list
            if (list.isEmpty()) {
                emptyHint = if (repo.loadActiveVoiceBanks().isEmpty()) {
                    "未选择声线池：请到 我的→朗读→引擎与音色→配置列表 选中声线池后再来"
                } else {
                    "已选声线池内没有可用标签"
                }
            }
        }
    }

    AppModalBottomSheet(
        animateContentSize = false,
        show = show,
        onDismissRequest = onDismiss,
        title = request?.title ?: "选择声线",
    ) {
        val req = request
        if (req != null) {
            val expected = repo.expectedVoicePrefix(req.roletype, req.gender, req.age)
            Column(modifier = Modifier.fillMaxWidth()) {
                SearchBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "搜索声线标签（期望前缀：$expected）",
                    shape = RoundedCornerShape(12.dp),
                    autoFocus = false,
                )
                val current = pool
                when {
                    current == null -> TinyClickableSettingItem(title = "加载声线库…", onClick = {})
                    current.isEmpty() -> TinyClickableSettingItem(
                        title = "无法选择声线",
                        description = emptyHint,
                        onClick = {},
                    )

                    else -> {
                        val matched = current.filter {
                            it.startsWith(expected) &&
                                (query.isBlank() || it.contains(query, ignoreCase = true))
                        }
                        val fallbackAll = matched.isEmpty()
                        val showList = if (fallbackAll) {
                            current.filter {
                                query.isBlank() || it.contains(query, ignoreCase = true)
                            }
                        } else {
                            matched
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(360.dp),
                        ) {
                            if (fallbackAll) {
                                item(key = "fb") {
                                    TinyClickableSettingItem(
                                        title = "未匹配到前缀「$expected」的声线，已显示全部",
                                        onClick = {},
                                    )
                                }
                            }
                            items(showList, key = { "vp_$it" }) { tag ->
                                TinyClickableSettingItem(
                                    title = tag,
                                    onClick = {
                                        req.onPicked(tag)
                                        onDismiss()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}