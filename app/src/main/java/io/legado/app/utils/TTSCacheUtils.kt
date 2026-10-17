package io.legado.app.utils

import io.legado.app.data.appDb
import io.legado.app.data.repository.ReadAloudAudioCacheRepository
import io.legado.app.help.coroutine.Coroutine
import org.koin.core.context.GlobalContext
import splitties.init.appCtx
import java.io.File

object TTSCacheUtils {

    /** 清除全部朗读音频缓存（持久化 `<数据根>/data/audio` + 历史 httpTTS 目录） */
    fun clearTtsCache() {
        val repo = GlobalContext.get().get<ReadAloudAudioCacheRepository>()
        repo.rootDir().takeIf { it.exists() }?.deleteRecursively()
        val base = appCtx.externalCacheDir ?: appCtx.cacheDir
        FileUtils.delete(File(base, "httpTTS").absolutePath)
        FileUtils.delete(File(base, "httpTTS_cache").absolutePath)
        // 「已下载」标记同步清空（音频本体就在缓存目录里，已随上一步删除）
        Coroutine.async {
            runCatching { appDb.readAloudAudioDownloadDao.deleteAll() }
        }
    }
}
