package io.legado.app.ui.book.readaloud.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.ui.theme.ProvideThemeOverride
import org.koin.compose.koinInject

/**
 * 听书播放界面（Navigation 3 目的地）。
 *
 * 它是一层普通全屏目的地：整页纵向进出动画完全由 nav3 的
 * `NavDisplay.TransitionKey` / `PopTransitionKey` / `PredictivePopTransitionKey` 提供
 * （见 `MainNavGraph.readAloudPlayerEntryMetadata`）。因此这里不需要 `ModalBottomSheet`
 * 这类窗口级 sheet 容器，也不自己驱动位移，没有「弹层里再开弹层」的
 * shape / 宽度 / 返回键特判；代价是没有下拉关闭手势。
 *
 * 朗读设置统一归口「我的 → 朗读设置」；播放页不设设置入口——原齿轮位自 W1 起固定为
 * 「朗读日志」，不要把上游播放页的设置入口随批加回。
 */
@Composable
fun ReadAloudPlayerRouteScreen(
    onBack: () -> Unit,
    /**
     * 「经典控制」按钮：交给宿主决定回到已有阅读界面还是新开阅读界面。
     * 参数是当前朗读的书籍 url。
     */
    onSwitchToClassic: (bookUrl: String) -> Unit,
    /** 顶栏「朗读日志」：由宿主导航到日志/缓存页。 */
    onOpenReadAloudLogs: () -> Unit,
    /** 底部「剧本审查」：由宿主导航到剧本审查页。 */
    onOpenScriptReview: (bookName: String, bookUrl: String, chapterIndex: Int) -> Unit,
) {
    val playerViewModel: ReadAloudPlayerViewModel = koinInject()
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val playerTheme = rememberPlayerThemeOverride(playerState)

    LaunchedEffect(playerViewModel) {
        playerViewModel.effects.collect { effect ->
            when (effect) {
                ReadAloudPlayerEffect.ReturnToClassic ->
                    onSwitchToClassic(playerViewModel.uiState.value.bookUrl)

                ReadAloudPlayerEffect.ReturnToReaderSettings -> Unit

                ReadAloudPlayerEffect.OpenReadAloudLogs -> onOpenReadAloudLogs()
            }
        }
    }

    ProvideThemeOverride(playerTheme) {
        ReadAloudPlayerScreenContent(
            state = playerState,
            onIntent = playerViewModel::onIntent,
            onBack = onBack,
            onOpenScriptReview = onOpenScriptReview,
        )
    }
}
