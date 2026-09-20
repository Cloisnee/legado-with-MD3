package io.legado.app.ui.ttssrv

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 「角色与剧本」二合一面（B10.2·U4，我的 → 朗读 →「角色与剧本」）：
 *  - 与剧本审查同布局：顶栏下 AppTabRow 双栏（角色管理 / 剧本），点按切换、无左右滑动；
 *  - 角色栏（角色管理）= 书籍卡 + 类型卡；剧本栏 = 书籍卡 + 章节卡；
 *  - 顶栏 FindReplace 图标：角色栏=切换类型、剧本栏=重析当前章。
 */
@Composable
fun RoleScriptManageRouteScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    RoleScriptManageScreen(
        app = context.applicationContext as Application,
        onBack = onBackClick,
    )
}

@Composable
fun RoleScriptManageScreen(app: Application, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    when (tab) {
        0 -> CharacterManageScreen(
            app = app,
            onBack = onBack,
            hostTab = tab,
            onHostTabSelected = { tab = it },
        )
        else -> BookManageScreen(
            app = app,
            onBack = onBack,
            hostTab = tab,
            onHostTabSelected = { tab = it },
        )
    }
}
