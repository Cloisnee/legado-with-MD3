package io.legado.app.help.readaloud.audio

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigStore

/**
 * 云端词网的统一取值入口：默认值与回退口径只在这里定义，UI/提交链共用。
 *
 * 缺省启用、仓库缺省 [DEFAULT_REPO]；令牌不做默认（由用户自填）。
 */
object CloudWordnetPrefs {

    const val DEFAULT_REPO = "Cloisnee/sfx"

    fun enabled(): Boolean = AppConfigStore.getBoolean(PreferKey.cloudWordEnabled) != false

    fun repo(): String =
        AppConfigStore.getString(PreferKey.cloudWordRepo).orEmpty().ifBlank { DEFAULT_REPO }

    fun token(): String = AppConfigStore.getString(PreferKey.cloudWordToken).orEmpty()
}
