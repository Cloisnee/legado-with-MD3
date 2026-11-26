package io.legado.app.feature.readaloud.settings

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.help.readaloud.analysis.ReadAloudBuildCoordinator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Stable
data class AiAnalysisBuildUiState(
    val bookUrl: String = "",
    val label: String = "未朗读",
    val builtChapters: Int = 0,
    val cachedPending: Int = 0,
    val budgetLeft: Int = 0,
)

/** 现有设置页新增的只读会话状态；跟随页面生命周期订阅，不在 Compose 镜像服务状态。 */
class AiAnalysisStatusViewModel(coordinator: ReadAloudBuildCoordinator) : ViewModel() {
    val state = coordinator.status.map { status ->
        AiAnalysisBuildUiState(
            bookUrl = status.bookUrl,
            label = when (status.state) {
                ReadAloudBuildCoordinator.BackgroundState.RUNNING -> "建库中"
                ReadAloudBuildCoordinator.BackgroundState.WAITING_CACHE -> "等待缓存"
                ReadAloudBuildCoordinator.BackgroundState.BUDGET_REACHED -> "额度用尽"
                ReadAloudBuildCoordinator.BackgroundState.PAUSED -> "已暂停"
                ReadAloudBuildCoordinator.BackgroundState.STOPPED -> "未朗读"
            },
            builtChapters = status.builtChapters,
            cachedPending = status.cachedPending,
            budgetLeft = status.budgetLeft,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiAnalysisBuildUiState())
}
