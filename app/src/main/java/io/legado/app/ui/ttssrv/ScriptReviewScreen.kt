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
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.ReadAloudDataRepository
import io.legado.app.help.readaloud.analysis.AnalysisSchedulerV3
import io.legado.app.ui.widget.components.text.AppText
import org.koin.core.context.GlobalContext

/**
 * 剧本审查页（B10.2·U3 改版）——从听书播放器「剧本审查」按钮进入的全屏页：
 *  - 顶栏下 AppTabRow 双栏（角色管理 / 剧本），点按切换、无左右滑动；
 *  - 进入时把正在朗读的书切换为「当前书」，保证两栏上下文一致；
 *  - 顶栏 FindReplace 图标：角色栏=切换类型、剧本栏=重析本章；
 *  - 分析调度完成事件 → 自动刷新（新角色/新剧本自动出现）。
 */
@Composable
fun ScriptReviewRouteScreen(
    bookName: String,
    bookUrl: String,
    chapterIndex: Int,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    ScriptReviewScreen(
        app = context.applicationContext as Application,
        bookName = bookName,
        bookUrl = bookUrl,
        chapterIndex = chapterIndex,
        onBack = onBackClick,
    )
}

@Composable
fun ScriptReviewScreen(
    app: Application,
    bookName: String,
    bookUrl: String,
    chapterIndex: Int,
    onBack: () -> Unit,
) {
    val repo = remember(app) { ReadAloudDataRepository(app) }
    var ready by remember(bookName) { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(0) }

    // 进入即把当前朗读书切换为「当前书」
    LaunchedEffect(bookName) {
        if (bookName.isNotBlank()) {
            runCatching { repo.switchBook(bookName) }
        }
        ready = true
    }

    // 分析调度完成 → 刷新当前栏
    LaunchedEffect(Unit) {
        val scheduler = runCatching { GlobalContext.get().get<AnalysisSchedulerV3>() }.getOrNull()
        if (scheduler == null) {
            AppLog.put("剧本审查页·分析调度器不可用")
            return@LaunchedEffect
        }
        scheduler.events.collect { ev ->
            if (ev.bookUrl == bookUrl && ev.chapterIndex == chapterIndex) refreshKey++
        }
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
            embedded = true,
            refreshKey = refreshKey,
            hostTab = tab,
            onHostTabSelected = { tab = it },
        )
        else -> BookManageScreen(
            app = app,
            onBack = onBack,
            embedded = true,
            initialChapter = chapterIndex,
            externalBookUrl = bookUrl,
            refreshKey = refreshKey,
            hostTab = tab,
            onHostTabSelected = { tab = it },
        )
    }
}
