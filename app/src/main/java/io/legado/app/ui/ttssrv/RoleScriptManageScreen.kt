package io.legado.app.ui.ttssrv

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.legado.app.data.repository.ReadAloudDataRepository
import io.legado.app.ui.widget.components.text.AppText
import kotlinx.coroutines.CancellationException

/**
 * 「角色与剧本」二合一面（B10.2·U4，我的 → 朗读 →「角色与剧本」）：
 *  - 与剧本审查同布局：顶栏下 AppTabRow 双栏（角色管理 / 书籍管理），点按切换、无左右滑动；
 *  - 顶栏只保留搜索：类型切换/章节操作由下方卡片承担，不做重复入口；
 *  - 角色栏（角色管理）= 书籍卡 + 类型卡；书籍管理栏 = 书籍卡 + 章节卡。
 *  - Q4：进入即把「当前书」切到最近朗读的书（cunfang 同步；与剧本审查进入口径一致）。
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
    val repo = remember(app) { ReadAloudDataRepository(app) }
    var tab by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(false) }

    // Q4：进入先自动匹配「最近朗读的书」，完成后再进双栏（避免先渲染旧书再跳变）
    LaunchedEffect(Unit) {
        runCatching { repo.syncBookToRecentRead() }.onFailure {
            if (it is CancellationException) throw it
        }
        ready = true
    }
    if (!ready) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AppText(text = "正在准备当前书…")
        }
        return
    }

    when (tab) {
        0 -> CharacterManageScreen(
            app = app,
            onBack = onBack,
            hostTab = tab,
            onHostTabSelected = { tab = it },
            bookTabLabel = "书籍管理",
        )
        else -> BookManageScreen(
            app = app,
            onBack = onBack,
            hostTab = tab,
            onHostTabSelected = { tab = it },
            bookTabLabel = "书籍管理",
        )
    }
}
