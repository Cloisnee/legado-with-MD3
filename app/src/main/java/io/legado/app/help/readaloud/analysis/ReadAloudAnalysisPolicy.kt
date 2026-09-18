package io.legado.app.help.readaloud.analysis

import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.model.readaloud.ContentSplitPolicies
import io.legado.app.domain.model.readaloud.ContentSplitPolicy
import io.legado.app.domain.model.settings.ReadAloudContentSplitMode
import org.koin.core.context.GlobalContext

/**
 * 分析侧「内容划分」口径：与朗读服务（`resolveContentSplitMode`/`contentSplitPolicy`）
 * 同一套解析——「默认」随多角色开关落到具体粒度，标点集合取存储值。
 *
 * 分析段落构建（调度器）与分析身份（解析器版本）都从这里取，保证：
 *  1) 分析段落与播放侧同粒度（同一 resolve 口径）；
 *  2) 切换划分方式 → 解析器版本变化 → 旧分析缓存自动失效重析
 *     （对齐上游 `ruleResolverVersion(policy)` 语义）。
 */
object ReadAloudAnalysisPolicy {

    /** 当前设置对应的划分策略；设置不可用时退回「默认」口径。 */
    fun current(): ContentSplitPolicy {
        val settings = runCatching {
            GlobalContext.get().get<ReadAloudSettingsGateway>().currentSettings
        }.getOrNull()
        val mode = ContentSplitPolicies.resolve(
            mode = ReadAloudContentSplitMode.fromStorage(
                settings?.contentSplitMode ?: ReadAloudContentSplitMode.Default.storageValue
            ),
            useMultiSpeaker = settings?.useMultiSpeaker ?: true,
        )
        return ContentSplitPolicies.forMode(mode, settings?.contentSplitSymbols.orEmpty())
    }

    /** 当前设置下的分析解析器版本，形如 `v3-script-1:<划分策略标识>`。 */
    fun currentResolverVersion(): String = resolverVersionOf(current())

    /** 把划分策略并入分析身份（切划分方式即换版本，旧缓存不命中）。 */
    fun resolverVersionOf(policy: ContentSplitPolicy): String =
        "${SpeechAnalysisPipelineV3.RESOLVER_VERSION}:${policy.identifier}"
}
