package io.legado.app.ui.widget.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.util.fastFirstOrNull

@Composable
fun <T, ID> DraggableSelectionHandler(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    items: List<T>,
    selectedIds: Set<ID>,
    onSelectionChange: (Set<ID>) -> Unit,
    idProvider: (T) -> ID,
    /** 行 key → 选择 ID 集合（多 ID = 整块切换，如“组行→组内全部条目”；返回空集 = 该行不参与选择）。
     *  为 null 时行为与旧版一致：直接以行 key 作为单个 ID。 */
    resolveIds: ((Any) -> Set<ID>)? = null,
    haptic: HapticFeedback = LocalHapticFeedback.current,
) {
    val latestSelectedIds by rememberUpdatedState(selectedIds)
    val latestOnSelectionChange by rememberUpdatedState(onSelectionChange)
    val latestIdProvider by rememberUpdatedState(idProvider)
    val latestItems by rememberUpdatedState(items)

    var isAddingMode by remember { mutableStateOf(true) }
    var lastProcessedIndex by remember { mutableIntStateOf(-1) }

    fun findItemAtOffset(offsetY: Float): Pair<Int, Set<ID>>? {
        val layoutInfo = listState.layoutInfo
        val itemsInfo = layoutInfo.visibleItemsInfo
        if (itemsInfo.isEmpty()) return null

        // 核心修复：校准坐标系。
        // offsetY 是相对于此 Box 顶部的。
        // item.offset 是相对于视口起始位置（通常包含 contentPadding）的。
        // 当开启模糊时，LazyColumn 占据全屏但有 topContentPadding。
        // item.offset 在 Compose 的 LazyListLayoutInfo 中是相对于“内容区域”开始计算的。
        val adjustedY = offsetY - layoutInfo.beforeContentPadding

        val itemInfo = itemsInfo.fastFirstOrNull { item ->
            adjustedY >= item.offset && adjustedY <= (item.offset + item.size)
        } ?: return null

        val ids: Set<ID> = if (resolveIds != null) {
            resolveIds.invoke(itemInfo.key)
        } else {
            @Suppress("UNCHECKED_CAST")
            val id = try {
                itemInfo.key as ID
            } catch (e: Exception) {
                latestItems.getOrNull(itemInfo.index)?.let { latestIdProvider(it) } ?: return null
            }
            setOf(id)
        }
        if (ids.isEmpty()) return null
        return itemInfo.index to ids
    }

    fun applySelection(ids: Set<ID>, add: Boolean) {
        if (ids.isEmpty()) return
        val current = latestSelectedIds
        val next = if (add) current + ids else current - ids
        if (next != current) latestOnSelectionChange(next)
    }

    Box(
        modifier = modifier
            .pointerInput(listState) {
                detectTapGestures(
                    onTap = { offset ->
                        findItemAtOffset(offset.y)?.let { (_, ids) ->
                            val allSelected = ids.all { it in latestSelectedIds }
                            applySelection(ids, !allSelected)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                )
            }
            .pointerInput(listState) {
                detectDragGestures(
                    onDragStart = { offset ->
                        findItemAtOffset(offset.y)?.let { (index, ids) ->
                            lastProcessedIndex = index
                            isAddingMode = !ids.all { it in latestSelectedIds }
                            applySelection(ids, isAddingMode)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onDrag = { change, _ ->
                        findItemAtOffset(change.position.y)?.let { (index, ids) ->
                            if (index != lastProcessedIndex) {
                                lastProcessedIndex = index
                                applySelection(ids, isAddingMode)
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                        if (change.pressed) change.consume()
                    },
                    onDragEnd = { lastProcessedIndex = -1 },
                    onDragCancel = { lastProcessedIndex = -1 }
                )
            }
    )
}
