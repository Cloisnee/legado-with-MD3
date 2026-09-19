package io.legado.app.ui.ttssrv

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.ReadAloudDataRepository
import io.legado.app.help.readaloud.analysis.AnalysisSchedulerV3
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * 剧本审查页（B3.5 重构）——从听书播放器「剧本审查」按钮进入的全屏页：
 *  - 左右滑动双页：页1=当前朗读书的角色管理（复用角色管理页 embedded）；
 *                  页2=当前朗读章节的剧本（复用书籍管理页 embedded，含「重新分析」）；
 *  - 进入时把正在朗读的书切换为「当前书」，保证两页上下文一致；
 *  - 分析调度完成事件 → 两页自动刷新（新角色/新剧本自动出现）。
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(app) { ReadAloudDataRepository(app) }
    var ready by remember(bookName) { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    // 进入即把当前朗读书切换为「当前书」
    LaunchedEffect(bookName) {
        if (bookName.isNotBlank()) {
            runCatching { repo.switchBook(bookName) }
        }
        ready = true
    }

    // 分析调度完成 → 刷新两页
    LaunchedEffect(Unit) {
        runCatching {
            val scheduler: AnalysisSchedulerV3 = GlobalContext.get().get()
            scheduler.events.collect { ev ->
                if (ev.bookUrl == bookUrl && ev.chapterIndex == chapterIndex) refreshKey++
            }
        }.onFailure { AppLog.put("剧本审查页·调度事件监听失败: ${it.localizedMessage}", it) }
    }

    if (!ready) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AppText(text = "正在准备当前书…")
        }
        return
    }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        when (page) {
            0 -> CharacterManageScreen(
                app = app,
                onBack = onBack,
                embedded = true,
                refreshKey = refreshKey,
            )
            else -> BookManageScreen(
                app = app,
                onBack = onBack,
                embedded = true,
                initialChapter = chapterIndex,
                refreshKey = refreshKey,
                onReanalyze = {
                    scope.launch {
                        runCatching {
                            val scheduler: AnalysisSchedulerV3 = GlobalContext.get().get()
                            scheduler.enqueueChapter(bookUrl, chapterIndex, force = true)
                        }.onFailure { AppLog.put("重析入队失败: ${it.localizedMessage}", it) }
                        context.toastOnUi("已提交重新分析（第${chapterIndex + 1}章）")
                    }
                },
            )
        }
    }
}
