package io.legado.app.ui.book.readaloud

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import io.legado.app.core.ui.player.PlayerMorphAppearance
import io.legado.app.core.ui.player.PlayerMorphHost
import io.legado.app.ui.book.readaloud.morph.ReadAloudMorphState
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerEffect
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerScreenContent
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerUiState
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerViewModel
import io.legado.app.ui.book.readaloud.player.rememberPlayerThemeOverride

/** 朗读播放页的特有内容；几何、封面、返回手势与形变由共用宿主处理。 */
@Composable
fun ReadAloudPlayerMorphHost(
    playerViewModel: ReadAloudPlayerViewModel,
    playerState: ReadAloudPlayerUiState,
    morph: ReadAloudMorphState,
    visible: Boolean,
    awaitCapsuleAnchor: Boolean = false,
    predictiveBackEnabled: Boolean = true,
    onDismiss: () -> Unit,
    onSwitchToClassic: (bookUrl: String) -> Unit,
    onOpenReadAloudLogs: () -> Unit = {},
    onOpenScriptReview: (bookName: String, bookUrl: String, chapterIndex: Int) -> Unit =
        { _, _, _ -> },
) {
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentSwitch by rememberUpdatedState(onSwitchToClassic)
    val currentOpenLogs by rememberUpdatedState(onOpenReadAloudLogs)
    LaunchedEffect(playerViewModel) {
        playerViewModel.effects.collect { effect ->
            when (effect) {
                ReadAloudPlayerEffect.ReturnToClassic -> {
                    morph.animateTo(0f)
                    currentDismiss()
                    currentSwitch(playerViewModel.uiState.value.bookUrl)
                }

                ReadAloudPlayerEffect.OpenReadAloudLogs -> {
                    morph.animateTo(0f)
                    currentDismiss()
                    currentOpenLogs()
                }
            }
        }
    }
    PlayerMorphHost(
        appearance = PlayerMorphAppearance(
            playerState.bookName, playerState.author, playerState.coverPath,
            playerState.sourceOrigin, playerState.bgMode,
        ),
        playerTheme = rememberPlayerThemeOverride(playerState),
        morph = morph,
        visible = visible,
        awaitCapsuleAnchor = awaitCapsuleAnchor,
        predictiveBackEnabled = predictiveBackEnabled,
        backEnabled = playerState.activeSheet == null,
        onDismiss = onDismiss,
    ) { onCollapse ->
        ReadAloudPlayerScreenContent(
            state = playerState,
            onIntent = playerViewModel::onIntent,
            onBack = onCollapse,
            onOpenScriptReview = onOpenScriptReview,
        )
    }
}
