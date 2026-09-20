package io.legado.app.ui.ttssrv

import androidx.compose.runtime.Composable
import io.legado.app.ui.widget.components.tabRow.AppTabRow

/**
 * 角色/剧本 双栏公共 Tab 行（B10.2·U3/U4 共用）：
 * 位于顶栏下（bottomContent），只点按切换、无左右滑动。
 */
@Composable
fun RoleScriptTabRow(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
) {
    AppTabRow(
        tabTitles = listOf("角色管理", "剧本"),
        selectedTabIndex = selectedTabIndex,
        onTabSelected = onTabSelected,
        isScrollable = false,
    )
}
