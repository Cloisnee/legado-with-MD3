package io.legado.app.ui.book.read.sheet

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue.Expanded
import androidx.compose.material3.SheetValue.Hidden
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.domain.model.settings.ReadAloudTimerMode
import io.legado.app.ui.book.read.ReadBookIntent
import io.legado.app.ui.book.read.ReadBookUiState
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerIntent
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerScreenContent
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerUiState
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.ProvideAppDensity
import io.legado.app.ui.theme.ProvideThemeOverride
import io.legado.app.ui.theme.ThemeOverrideState
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.TinySliderSettingItem
import io.legado.app.ui.widget.components.settingItem.TinySwitchSettingItem

enum class ReadAloudPage {
    Config,
    Player,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadAloudScreen(
    page: ReadAloudPage?,
    state: ReadBookUiState,
    playerState: ReadAloudPlayerUiState,
    playerTheme: ThemeOverrideState?,
    onIntent: (ReadBookIntent) -> Unit,
    onPlayerIntent: (ReadAloudPlayerIntent) -> Unit,
    onDismissRequest: () -> Unit,
    onOpenScriptReview: (bookName: String, bookUrl: String, chapterIndex: Int) -> Unit = { _, _, _ -> },
) {
    val sheetState = rememberBottomSheetState(
        initialValue = Hidden,
        enabledValues = setOf(Hidden, Expanded),
    )
    val isPlayer = page == ReadAloudPage.Player
    var backProgress by remember { mutableFloatStateOf(0f) }
    val predictiveBackOffset = with(LocalDensity.current) { 120.dp.toPx() }
    var configParentIsPlayer by remember { mutableStateOf(false) }
    LaunchedEffect(page) {
        when (page) {
            ReadAloudPage.Player -> configParentIsPlayer = true
            null -> configParentIsPlayer = false
            ReadAloudPage.Config -> Unit
        }
    }
    val returnFromConfig = {
        if (configParentIsPlayer) {
            onIntent(
                ReadBookIntent.ShowSheet(
                    io.legado.app.ui.book.read.ReadBookSheet.ReadAloudPlayer
                )
            )
        } else {
            onIntent(ReadBookIntent.OpenClassicReadAloudControls)
        }
    }
    if (page != null) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = sheetState,
            modifier = (if (isPlayer) Modifier.fillMaxSize() else Modifier)
                .graphicsLayer { translationY = backProgress * predictiveBackOffset },
            shape = if (isPlayer) RectangleShape else MaterialTheme.shapes.extraLarge,
            sheetMaxWidth = if (isPlayer) Dp.Unspecified else 640.dp,
            containerColor = if (isPlayer) {
                Color.Transparent
            } else {
                LegadoTheme.colorScheme.surfaceContainer
            },
            contentColor = LegadoTheme.colorScheme.onSurface,
            contentWindowInsets = {
                WindowInsets(0, 0, 0, 0)
            },
            dragHandle = null,
            properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        ) {
            ProvideAppDensity {
                BackHandler(enabled = page == ReadAloudPage.Config, onBack = returnFromConfig)
                PredictiveBackHandler(enabled = page != ReadAloudPage.Config) { progress ->
                    try {
                        progress.collect { event ->
                            backProgress = event.progress
                        }
                        sheetState.hide()
                        onDismissRequest()
                    } finally {
                        backProgress = 0f
                    }
                }
                AnimatedContent(
                    targetState = page,
                    label = "ReadAloudPage",
                ) { targetPage ->
                    when (targetPage) {
                        ReadAloudPage.Config -> Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.statusBars)
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MediumTonalButton(
                                    onClick = {
                                        returnFromConfig()
                                    },
                                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.aloud_config),
                                    style = LegadoTheme.typography.titleLarge,
                                )
                            }
                            Spacer(modifier = Modifier.padding(vertical = 8.dp))
                            ReadAloudConfigContent(
                                state = state,
                                playerState = playerState,
                                onIntent = onIntent,
                                onPlayerIntent = onPlayerIntent,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        ReadAloudPage.Player -> ProvideThemeOverride(playerTheme) {
                            ReadAloudPlayerScreenContent(
                                state = playerState,
                                onIntent = onPlayerIntent,
                                onBack = onDismissRequest,
                                onOpenScriptReview = onOpenScriptReview,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReadAloudContent(
    state: ReadBookUiState,
    onIntent: (ReadBookIntent) -> Unit,
    onDismissRequest: () -> Unit,
    onOpenChapterList: () -> Unit,
    onGoToBackground: () -> Unit,
    onOpenMainMenu: () -> Unit,
    onShowReadAloudConfig: () -> Unit,
    onShowTimerSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ttsSpeechRate = state.readAloudTtsSpeechRate

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        // Media controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediumTonalButton(
                onClick = { onIntent(ReadBookIntent.ReadAloudPrevParagraph) },
                icon = Icons.Default.SkipPrevious,
                contentDescription = stringResource(R.string.prev_sentence),
            )
            Spacer(Modifier.width(6.dp))
            MediumTonalButton(
                onClick = { onIntent(ReadBookIntent.ReadAloudTogglePause) },
                icon = if (state.isReadAloudPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = stringResource(
                    if (state.isReadAloudPaused) R.string.audio_play else R.string.pause
                ),
            )
            Spacer(Modifier.width(6.dp))
            MediumTonalButton(
                onClick = {
                    onIntent(ReadBookIntent.ReadAloudStop)
                    onDismissRequest()
                },
                icon = Icons.Default.Stop,
                contentDescription = stringResource(R.string.stop),
            )
            Spacer(Modifier.width(6.dp))
            MediumTonalButton(
                onClick = { onIntent(ReadBookIntent.ReadAloudNextParagraph) },
                icon = Icons.Default.SkipNext,
                text = stringResource(R.string.next_sentence),
            )
        }

        Spacer(Modifier.height(12.dp))

        TinyClickableSettingItem(
            title = stringResource(R.string.set_timer),
            description = readAloudTimerSummary(state),
            onClick = onShowTimerSettings,
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MediumTonalButton(
                onClick = { onIntent(ReadBookIntent.ReadAloudPrevChapter) },
                text = stringResource(R.string.previous_chapter),
                modifier = Modifier.weight(1f),
            )
            MediumTonalButton(
                onClick = { onIntent(ReadBookIntent.ReadAloudNextChapter) },
                text = stringResource(R.string.next_chapter),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))

        TinySwitchSettingItem(
            title = stringResource(R.string.flow_sys),
            checked = state.readAloudTtsFollowSys,
            onCheckedChange = {
                onIntent(ReadBookIntent.SetReadAloudTtsFollowSys(it))
            },
        )

        TinySliderSettingItem(
            title = stringResource(R.string.read_aloud_speed),
            description = stringResource(R.string.read_aloud_speed_summary),
            value = ttsSpeechRate.toFloat(),
            valueRange = 0f..80f,
            steps = 79,
            enabled = !state.readAloudTtsFollowSys,
            onValueChange = {
                onIntent(ReadBookIntent.SetReadAloudTtsSpeechRate(it.toInt()))
            },
        )

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ActionButton(
                icon = Icons.Default.Menu,
                label = stringResource(R.string.main_menu),
                onClick = onOpenMainMenu,
            )
            ActionButton(
                icon = Icons.AutoMirrored.Filled.List,
                label = stringResource(R.string.chapter_list),
                onClick = onOpenChapterList,
            )
            ActionButton(
                icon = Icons.Default.VisibilityOff,
                label = stringResource(R.string.to_backstage),
                onClick = onGoToBackground,
            )
            ActionButton(
                icon = Icons.Default.Headphones,
                label = stringResource(R.string.switch_to_read_aloud_player),
                onClick = { onIntent(ReadBookIntent.OpenReadAloudPlayer) },
            )
        }
    }
}

/**
 * 定时入口的摘要：按当前模式显示剩余分钟或剩余章数，未开启显示「关闭」。
 */
@Composable
private fun readAloudTimerSummary(state: ReadBookUiState): String = when {
    state.readAloudTimerMode == ReadAloudTimerMode.Chapter.storageValue &&
            state.readAloudTimerChapters > 0 ->
        stringResource(R.string.timer_chapters, state.readAloudTimerChapters)

    state.readAloudTimerMode == ReadAloudTimerMode.Minute.storageValue &&
            state.readAloudTtsTimer > 0 ->
        stringResource(R.string.timer_m, state.readAloudTtsTimer)

    else -> stringResource(R.string.close)
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MediumTonalButton(
            onClick = onClick,
            icon = icon,
            contentDescription = label,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
