package io.legado.app.ui.ttssrv

import androidx.compose.runtime.Composable
import io.legado.app.ui.widget.components.tabRow.AppTabRow

/**
 * 角色/剧本 双栏公共 Tab 行（B10.2·U3/U4 共用）：
 * 位于顶栏下（bottomContent），只点按切换、无左右滑动。
 * [bookTabLabel]：第二栏名称——剧本审查（播放器入口）=「剧本」；二合一面（我的入口）=「书籍管理」。
 */
@Composable
fun RoleScriptTabRow(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    bookTabLabel: String = "剧本",
) {
    AppTabRow(
        tabTitles = listOf("角色管理", bookTabLabel),
        selectedTabIndex = selectedTabIndex,
        onTabSelected = onTabSelected,
        isScrollable = false,
    )
}
