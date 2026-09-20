package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.HttpTTS
import io.legado.app.domain.gateway.CloudTtsEngineGateway
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.domain.model.readaloud.ReadAloudPlaybackCursor
import io.legado.app.domain.model.readaloud.ReadAloudPlaybackQueue
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechEngineRoute
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.SpeechVoiceRouter
import io.legado.app.domain.model.readaloud.SystemTtsVoiceConfig
import io.legado.app.domain.model.settings.OtherSettings
import io.legado.app.domain.model.settings.ReadAloudSettings
import io.legado.app.domain.model.settings.ReadSettings
import io.legado.app.exception.NoStackTraceException
import io.legado.app.feature.reader.core.readaloud.ReaderReadAloudChapter
import io.legado.app.feature.reader.core.source.ReaderChapterSourceParser
import io.legado.app.feature.reader.platform.AndroidReaderHtmlSemanticTextResolver
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.data.repository.ReadAloudAudioCacheRepository
import io.legado.app.help.readaloud.playback.CharacterPerformanceInstructionBuilder
import io.legado.app.help.readaloud.playback.CloudTtsAudioSynthesizer
import io.legado.app.help.readaloud.playback.CloudTtsEmotionMapper
import io.legado.app.help.readaloud.playback.CloudTtsRoleInstructionMapper
import io.legado.app.help.readaloud.playback.ReadAloudAudioCacheKeys
import io.legado.app.help.readaloud.playback.SystemTtsFileSynthesizer
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Response
import org.koin.core.context.GlobalContext
import org.koin.java.KoinJavaComponent.get
import org.mozilla.javascript.WrappedException
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * 在线朗读
 */
@SuppressLint("UnsafeOptInUsageError")
class HttpReadAloudService : BaseReadAloudService(),
    Player.Listener {
    override val useSpeechPlaybackQueue: Boolean = true

    protected override val currentSpeechRate: Float
        get() = synthesisSpeed(ReadAloud.httpTTS) * globalPlaybackSpeed

    private val readAloudSettingsGateway = GlobalContext.get().get<ReadAloudSettingsGateway>()
    private val readSettingsGateway = GlobalContext.get().get<ReadSettingsGateway>()
    private val otherSettingsGateway = GlobalContext.get().get<OtherSettingsGateway>()
    private var readAloudSettings: ReadAloudSettings = readAloudSettingsGateway.currentSettings
    private var readSettings: ReadSettings = readSettingsGateway.currentSettings
    private var otherSettings: OtherSettings = otherSettingsGateway.currentSettings

    private val speechRatePlay: Int
        get() = if (readAloudSettings.ttsFollowSys) 5 else readAloudSettings.ttsSpeechRate

    /**
     * 全局语速倍率, 由设置里的朗读语速换算而来, 在播放端 (ExoPlayer) 变速,
     * 对所有来源的音频 (http/系统/云端合成) 统一生效。
     */
    private val globalPlaybackSpeed: Float
        get() = (speechRatePlay + 5) / 10f

    /**
     * 源级合成语速倍率, 仅当源接口支持语速参数 ({{speakSpeed}}) 时影响返回的音频。
     * 全局语速不再参与合成, 避免与播放端变速叠加。
     */
    private fun synthesisSpeed(httpTts: HttpTTS?): Float =
        ((httpTts?.speed ?: DEFAULT_TTS_SPEED) + 5) / 10f

    private data class PreDownloadChapter(
        val book: String,
        val chapterIndex: Int,
        val chapterTitle: String,
        val queue: ReadAloudPlaybackQueue,
        val contentList: List<String>,
    )

    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build()
    }

    private val cache by lazy {
        val baseDir = externalCacheDir ?: cacheDir
        SimpleCache(
            File(baseDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(128 * 1024 * 1024),
            StandaloneDatabaseProvider(appCtx)
        )
    }
    private val cacheDataSinkFactory by lazy {
        CacheDataSink.Factory()
            .setCache(cache)
    }
    private val loadErrorHandlingPolicy by lazy {
        CustomLoadErrorHandlingPolicy()
    }
    private var speechRate: Int = speechRatePlay + 5
    private var downloadTask: Coroutine<*>? = null
    private var preDownloadJob: Job? = null
    private var playIndexJob: Job? = null
    private var downloadErrorNo: Int = 0
    private var playErrorNo = 0
    private val downloadTaskActiveLock = Mutex()
    private val systemTtsFileSynthesizer by lazy { SystemTtsFileSynthesizer(this) }
    private val cloudTtsAudioSynthesizer by lazy {
        CloudTtsAudioSynthesizer(get(CloudTtsEngineGateway::class.java))
    }
    // [TTS-Server 移植] 内嵌引擎合成器（engineType = tts_server）
    private val ttsServerSynthesizer by lazy {
        com.github.jing332.tts.readaloud.TtsServerSynthesizer(this)
    }
    // [B8] 朗读音频缓存：持久化于 <数据根>/data/audio/<书名>/<章>/<条目>_<hash>.mp3（不再写索引文件）
    private val audioCache by lazy { GlobalContext.get().get<ReadAloudAudioCacheRepository>() }
    // 合成失败时的临时静音占位（不进缓存目录，避免「失败」被当成「已合成」）
    private val silentFile by lazy {
        File(cacheDir, "httpTTS_silent/silent.mp3").apply {
            parentFile?.mkdirs()
            if (!exists() || length() == 0L) {
                writeBytes(resources.openRawResource(R.raw.silent_sound).readBytes())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
        exoPlayer.setPlaybackSpeed(globalPlaybackSpeed)
        lifecycleScope.launch {
            readAloudSettingsGateway.settings.collectLatest {
                readAloudSettings = it
                // 全局语速为播放端变速, 设置变化即时生效, 无需重新合成
                exoPlayer.setPlaybackSpeed(globalPlaybackSpeed)
            }
        }
        lifecycleScope.launch {
            readSettingsGateway.settings.collectLatest { readSettings = it }
        }
        lifecycleScope.launch {
            otherSettingsGateway.settings.collectLatest { otherSettings = it }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadTask?.cancel()
        preDownloadJob?.cancel()
        exoPlayer.release()
        cache.release()
        val book = ReadBook.book?.name.orEmpty()
        val chapterIndex = readerReadAloudChapter?.chapterIndex ?: -1
        Coroutine.async {
            systemTtsFileSynthesizer.close()
            // 过期清理（audioCacheCleanTime<=0 表示持久保留，交由音频管理页删除）
            if (book.isNotEmpty() && chapterIndex >= 0) {
                runCatching {
                    audioCache.cleanupExpired(
                        book = book,
                        keepChapterIndex = chapterIndex,
                        keepMinutes = readAloudSettings.audioCacheCleanTime,
                    )
                }
            }
        }
    }

    override fun play() {
        pageChanged = false
        exoPlayer.stop()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            super.play()
            if (readAloudSettings.streamReadAloudAudio && !hasFileSynthesisCue()) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    override fun onPlaybackStateReplaced() {
        super.onPlaybackStateReplaced()
        val chapter = readerReadAloudChapter ?: return
        AppLog.putAudio(
            "【音频缓存】第${chapter.chapterIndex + 1}章「${chapter.title}」" +
                "播放队列就绪：${contentList.size}条"
        )
    }

    override fun playStop() {
        exoPlayer.stop()
        playIndexJob?.cancel()
        preDownloadJob?.cancel()
    }

    private fun updateNextPos(naturalCompletion: Boolean = false) {
        if (!playbackQueue.isEmpty) {
            val current = playbackCursor ?: ReadAloudPlaybackCursor(nowSpeak, paragraphStartPos)
            playbackQueue.next(current)?.let(::moveToPlaybackCursor) ?: if (naturalCompletion) {
                completeCurrentChapter()
            } else {
                nextChapter()
            }
            return
        }
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        // 页内切段不引入换行符，累加会漂移，用段落绝对位置重算
        readAloudNumber = paragraphChapterPositionAt(nowSpeak + 1) ?: 0
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
        } else {
            if (naturalCompletion) completeCurrentChapter() else nextChapter()
        }
    }

    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        preDownloadJob?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")

                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    var text = content
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    val routedVoice = voiceForCue(playbackQueue, index, httpTts)
                    val cue = playbackQueue.cues.getOrNull(index)
                    val cueEmotion = cue?.emotion.orEmpty()
                    val characterPerformance = cue?.characterPerformance
                    val cueRoleType = cue?.roleType ?: SpeechRoleType.Unknown
                    val itemHttpTts = routedVoice.engineId.toLongOrNull()
                        ?.let(appDb.httpTTSDao::get) ?: httpTts
                    val voiceKey = ReadAloudAudioCacheKeys.voiceKey(
                        voice = routedVoice,
                        emotion = cueEmotion,
                        performance = characterPerformance,
                        roleType = cueRoleType,
                        httpTts = itemHttpTts,
                    )
                    // 章节标题 cue 序号 = -1（leadingTitleCueCount 已把标题计入 cue 序号）
                    val segIndex = index - playbackQueue.leadingTitleCueCount
                    val cacheFile = cueCacheFile(segIndex, text, voiceKey)
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    val cueLabel = cueLabel(index, routedVoice)
                    if (speakText.isEmpty()) {
                        AppLog.putAudio("【音频缓存】空文本→静音占位 $cueLabel | ${snippet(text)}")
                    } else if (!cacheFile.isValidAudio()) {
                        val t0 = System.currentTimeMillis()
                        val failReason = runCatching {
                            when (routedVoice.engineType) {
                                ReadAloudVoice.ENGINE_SYSTEM -> synthesizeWithRetry(cueLabel, speakText) {
                                    val config = runCatching {
                                        GSON.fromJson(
                                            routedVoice.traitsJson,
                                            SystemTtsVoiceConfig::class.java,
                                        )
                                    }.getOrNull() ?: SystemTtsVoiceConfig()
                                    // 全局语速已改为播放端变速, 系统合成只使用音色自带语速, 避免叠加
                                    if (systemTtsFileSynthesizer.synthesize(
                                            routedVoice.engineId,
                                            routedVoice.speakerId,
                                            speakText,
                                            cacheFile,
                                            config.speechRate ?: 1f,
                                            config.pitch ?: 1f,
                                        )
                                    ) null else "系统TTS合成失败"
                                }

                                ReadAloudVoice.ENGINE_TTS_SERVER -> synthesizeWithRetry(cueLabel, speakText) {
                                    val outcome = ttsServerSynthesizer.synthesize(
                                        routedVoice.engineId,
                                        routedVoice.speakerId,
                                        speakText,
                                        cacheFile,
                                    )
                                    if (outcome.ok) null else (outcome.reason ?: "未知原因")
                                }

                                ReadAloudVoice.ENGINE_CLOUD -> synthesizeWithRetry(cueLabel, speakText) {
                                    if (cloudTtsAudioSynthesizer.synthesize(
                                            routedVoice,
                                            speakText,
                                            cacheFile,
                                            styleOverride = cueEmotion,
                                            characterPerformance = characterPerformance,
                                            roleType = cueRoleType,
                                        )
                                    ) null else "云端合成失败"
                                }

                                else -> {
                                    val inputStream = getSpeakStream(itemHttpTts, speakText)
                                    if (inputStream != null) {
                                        cacheFile.parentFile?.mkdirs()
                                        cacheFile.outputStream().use { out ->
                                            inputStream.use { it.copyTo(out) }
                                        }
                                        null
                                    } else {
                                        "TTS下载失败"
                                    }
                                }
                            }
                        }.onFailure {
                            when (it) {
                                is CancellationException -> Unit
                                else -> {
                                    AppLog.putAudio("【音频缓存】合成异常，已暂停朗读: ${it.localizedMessage}", it)
                                    pauseReadAloud()
                                }
                            }
                            return@execute
                        }.getOrNull()
                        if (failReason == null) {
                            AppLog.putAudio(
                                "【音频缓存】已缓存 $cueLabel " +
                                    "${cacheFile.length() / 1024}KB " +
                                    "${System.currentTimeMillis() - t0}ms | ${snippet(speakText)}"
                            )
                        } else {
                            // 失败不落缓存（避免「失败」被当成「已合成」）；播放时用临时静音占位
                            runCatching { cacheFile.delete() }
                            AppLog.putAudio(
                                "【音频缓存】合成失败→静音占位 $cueLabel: $failReason | ${snippet(speakText)}"
                            )
                        }
                    }
                    val mediaItem = MediaItem.fromUri(
                        Uri.fromFile(if (cacheFile.isValidAudio()) cacheFile else silentFile)
                    )
                    launch(Main) {
                        if (readAloudSettings.ttsParagraphInterval > 0) {
                            if (index == nowSpeak && exoPlayer.mediaItemCount == 0) {
                                exoPlayer.setMediaItem(mediaItem)
                                if (!pause) {
                                    exoPlayer.prepare()
                                }
                                // 当前章开始播放后，立即异步启动后续章节预合成
                                launchPreDownload(httpTts)
                            }
                        } else {
                            if (exoPlayer.mediaItemCount == 0) {
                                exoPlayer.setMediaItem(mediaItem)
                                if (!pause) {
                                    exoPlayer.prepare()
                                }
                                // 当前章开始播放后，立即异步启动后续章节预合成
                                launchPreDownload(httpTts)
                            } else {
                                exoPlayer.addMediaItem(mediaItem)
                            }
                        }
                    }
                }
            }
        }.onError {
            AppLog.putAudio("朗读下载出错\n${it.localizedMessage}", it, toast = true)
        }
    }

    /**
     * 异步启动后续章节的预合成，与当前章节播放并行。
     * 不持有 downloadTaskActiveLock，不阻塞当前章节的合成和播放。
     */
    private fun launchPreDownload(httpTts: HttpTTS) {
        preDownloadJob?.cancel()
        preDownloadJob = lifecycleScope.launch {
            preDownloadAudios(httpTts)
        }
    }

    private suspend fun getPreDownloadChapter(
        book: Book,
        chapter: BookChapter,
    ): PreDownloadChapter? {
        val content = BookHelp.getContent(book, chapter) ?: return null
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val displayTitle = chapter.getDisplayTitle(
            contentProcessor.getTitleReplaceRules(),
            book.getUseReplaceRule(otherSettings.replaceEnableDefault),
            chineseConverterType = readSettings.chineseConverterType,
        )
        val processedContent = contentProcessor.getContent(
            book,
            chapter,
            content,
            includeTitle = false,
        )
        val source = ReaderChapterSourceParser.parse(
            chapterIndex = chapter.index,
            title = displayTitle,
            paragraphs = processedContent.textList,
            includeTitle = false,
            adaptSpecialStyle = readSettings.adaptSpecialStyle,
            htmlSemanticTextResolver = AndroidReaderHtmlSemanticTextResolver,
        )
        val readAloudChapter = ReaderReadAloudChapter.create(
            chapterIndex = chapter.index,
            title = displayTitle,
            semanticContent = source.semanticContent,
            pageStarts = ReadBook.readerPagination(chapter.index)?.pageStarts.orEmpty(),
        )
        val plan = buildSpeechPlan(
            bookUrl = book.bookUrl,
            chapterIndex = chapter.index,
            paragraphs = readAloudChapter.canonicalSpeechParagraphs(),
        )
        val queue = runCatching {
            ReadAloudPlaybackQueue.from(plan)
                .withChapterTitle(displayTitle, ReadAloudPlaybackQueue.narratorVoiceOf(plan))
        }.getOrDefault(ReadAloudPlaybackQueue.Empty)
        val contentList = if (!queue.isEmpty) {
            queue.cues.map { it.text }
        } else {
            listOf(displayTitle.trim()).filter { it.isNotEmpty() } +
                    readAloudChapter.paragraphs(readAloudSettings.readAloudByPage)
                        .map { it.text.replace(Regex("[袮祢꧁\uFFFC]"), " ") }
        }
        return PreDownloadChapter(book.name, chapter.index, displayTitle, queue, contentList)
    }

    private suspend fun preDownloadAudios(httpTts: HttpTTS) {
        val book = ReadBook.book ?: return
        val currentIdx = ReadBook.durChapterIndex
        val limit = readAloudSettings.audioPreDownloadNum
        val concurrency = readAloudSettings.ttsPreSynthesisConcurrency.coerceIn(1, 8)
        var consecutiveFailures = 0

        try {
            for (i in 1..limit) {
                currentCoroutineContext().ensureActive()
                if (consecutiveFailures >= 3) {
                    AppLog.putAudio("TTS预合成连续失败${consecutiveFailures}章，已停止预合成")
                    break
                }
                val targetIndex = currentIdx + i
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, targetIndex) ?: break
                val prepared = getPreDownloadChapter(book, chapter)
                if (prepared == null) {
                    AppLog.putAudio("【音频缓存】跳过预合成 第${targetIndex + 1}章（章节内容未缓存）")
                    continue
                }
                AppLog.putAudio(
                    "【音频缓存】预合成 第${targetIndex + 1}章「${prepared.chapterTitle}」" +
                        "${prepared.contentList.size}条"
                )
                val chapterFailed = synthesizeChapterCues(prepared, httpTts, concurrency)
                consecutiveFailures = if (chapterFailed) consecutiveFailures + 1 else 0
            }
        } catch (e: Exception) {
            AppLog.putAudio("听书预下载异常: ${e.localizedMessage}", e)
        }
    }

    /**
     * 并行合成一个章节的所有 cue，通过 Semaphore 控制并发。
     * 返回 true 表示该章节合成失败（超过半数 cue 失败）。
     */
    private suspend fun synthesizeChapterCues(
        prepared: PreDownloadChapter,
        httpTts: HttpTTS,
        concurrency: Int,
    ): Boolean = coroutineScope {
        val semaphore = kotlinx.coroutines.sync.Semaphore(concurrency)
        var failedCount = 0
        val totalCues = prepared.contentList.size

        prepared.contentList.mapIndexed { index, content ->
            async {
                semaphore.acquire()
                try {
                    val routedVoice = voiceForCue(prepared.queue, index, httpTts)
                    if (routedVoice.engineType == ReadAloudVoice.ENGINE_SYSTEM) {
                        return@async
                    }
                    val cue = prepared.queue.cues.getOrNull(index)
                    val segIndex = index - prepared.queue.leadingTitleCueCount
                    if (synthesizeSingleCueWithRetry(
                            routedVoice, cue, content, prepared, segIndex, httpTts,
                        )
                    ) return@async
                    failedCount++
                } finally {
                    semaphore.release()
                }
            }
        }.awaitAll()

        if (failedCount > 0) {
            AppLog.putAudio(
                "【音频缓存】预合成「${prepared.chapterTitle}」失败 $failedCount/$totalCues 条"
            )
        }
        failedCount > totalCues / 2
    }

    /**
     * 单个 cue 合成 + 重试 1 次（500ms 延迟）
     */
    private suspend fun synthesizeSingleCueWithRetry(
        routedVoice: ReadAloudVoice,
        cue: io.legado.app.domain.model.readaloud.ReadAloudPlaybackCue?,
        content: String,
        prepared: PreDownloadChapter,
        segIndex: Int,
        httpTts: HttpTTS,
    ): Boolean {
        if (synthesizeSingleCue(routedVoice, cue, content, prepared, segIndex, httpTts)) {
            return true
        }
        delay(500)
        return synthesizeSingleCue(routedVoice, cue, content, prepared, segIndex, httpTts)
    }

    /**
     * 单个 cue 合成核心方法
     */
    private suspend fun synthesizeSingleCue(
        routedVoice: ReadAloudVoice,
        cue: io.legado.app.domain.model.readaloud.ReadAloudPlaybackCue?,
        content: String,
        prepared: PreDownloadChapter,
        segIndex: Int,
        httpTts: HttpTTS,
    ): Boolean {
        val itemHttpTts = routedVoice.engineId.toLongOrNull()
            ?.let(appDb.httpTTSDao::get) ?: httpTts
        val voiceKey = ReadAloudAudioCacheKeys.voiceKey(
            voice = routedVoice,
            emotion = cue?.emotion.orEmpty(),
            performance = cue?.characterPerformance,
            roleType = cue?.roleType ?: SpeechRoleType.Unknown,
            httpTts = itemHttpTts,
        )
        val cacheFile = cacheFileFor(
            book = prepared.book,
            chapterIndex = prepared.chapterIndex,
            segIndex = segIndex,
            text = content,
            voiceKey = voiceKey,
        )
        val speakText = content.replace(AppPattern.notReadAloudRegex, "")
        if (speakText.isEmpty()) {
            // 空文本不落缓存（播放侧用静音占位）
            return true
        }
        if (cacheFile.isValidAudio()) return true
        val failReason = runCatching {
            when (routedVoice.engineType) {
                ReadAloudVoice.ENGINE_TTS_SERVER -> {
                    val outcome = ttsServerSynthesizer.synthesize(
                        routedVoice.engineId,
                        routedVoice.speakerId,
                        speakText,
                        cacheFile,
                    )
                    if (outcome.ok) null else (outcome.reason ?: "未知原因")
                }

                ReadAloudVoice.ENGINE_CLOUD -> {
                    if (cloudTtsAudioSynthesizer.synthesize(
                            routedVoice, speakText, cacheFile,
                            styleOverride = cue?.emotion.orEmpty(),
                            characterPerformance = cue?.characterPerformance,
                            roleType = cue?.roleType ?: SpeechRoleType.Unknown,
                        )
                    ) null else "云端合成失败"
                }

                ReadAloudVoice.ENGINE_HTTP -> {
                    val inputStream = getSpeakStream(itemHttpTts, speakText)
                    if (inputStream != null) {
                        cacheFile.parentFile?.mkdirs()
                        cacheFile.outputStream().use { out -> inputStream.use { it.copyTo(out) } }
                        null
                    } else {
                        "TTS下载失败"
                    }
                }

                else -> "不支持的引擎类型: ${routedVoice.engineType}"
            }
        }.getOrElse {
            when (it) {
                is CancellationException -> throw it
                else -> "合成异常: ${it.localizedMessage}"
            }
        }
        if (failReason != null) {
            runCatching { cacheFile.delete() }
            AppLog.putAudio(
                "【音频缓存】预合成失败 ${routedVoice.speakerId.ifBlank { routedVoice.displayName }}" +
                    " | ${prepared.chapterTitle} | ${snippet(speakText)} | $failReason"
            )
            return false
        }
        return true
    }

    private fun downloadAndPlayAudiosStream() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        preDownloadJob?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val downloaderChannel = Channel<Downloader>()
                launch {
                    for (downloader in downloaderChannel) {
                        downloader.download(null)
                    }
                }
                var preDownloadLaunched = false
                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    var text = content
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isEmpty()) {
                        AppLog.putAudio("【音频缓存】空文本→静音占位 | ${snippet(speakText)}")
                    }
                    val itemHttpTts = httpTtsForCue(index, httpTts)
                    val fileName = streamCacheKey(text, itemHttpTts)
                    val dataSourceFactory = createDataSourceFactory(itemHttpTts, speakText)
                    val downloader = createDownloader(dataSourceFactory, fileName)
                    downloaderChannel.send(downloader)
                    val mediaSource = createMediaSource(dataSourceFactory, fileName)
                    launch(Main) {
                        if (readAloudSettings.ttsParagraphInterval > 0) {
                            if (index == nowSpeak && exoPlayer.mediaItemCount == 0) {
                                exoPlayer.setMediaSource(mediaSource)
                                if (!pause) {
                                    exoPlayer.prepare()
                                }
                                if (!preDownloadLaunched) {
                                    preDownloadLaunched = true
                                    launchPreDownloadStream(httpTts, downloaderChannel)
                                }
                            }
                        } else {
                            if (exoPlayer.mediaItemCount == 0) {
                                exoPlayer.setMediaSource(mediaSource)
                                if (!pause) {
                                    exoPlayer.prepare()
                                }
                                if (!preDownloadLaunched) {
                                    preDownloadLaunched = true
                                    launchPreDownloadStream(httpTts, downloaderChannel)
                                }
                            } else {
                                exoPlayer.addMediaSource(mediaSource)
                            }
                        }
                    }
                }
            }
        }.onError {
            AppLog.putAudio("朗读下载出错\n${it.localizedMessage}", it, toast = true)
        }
    }

    private fun launchPreDownloadStream(httpTts: HttpTTS, downloaderChannel: Channel<Downloader>) {
        preDownloadJob?.cancel()
        preDownloadJob = lifecycleScope.launch {
            preDownloadAudiosStream(httpTts, downloaderChannel)
        }
    }

    private suspend fun preDownloadAudiosStream(
        httpTts: HttpTTS,
        downloaderChannel: Channel<Downloader>
    ) {
        val book = ReadBook.book ?: return
        val currentIdx = ReadBook.durChapterIndex
        val limit = readAloudSettings.audioPreDownloadNum
        val concurrency = readAloudSettings.ttsPreSynthesisConcurrency.coerceIn(1, 8)
        var consecutiveFailures = 0

        try {
            for (i in 1..limit) {
                currentCoroutineContext().ensureActive()
                if (consecutiveFailures >= 3) {
                    AppLog.putAudio("TTS流式预合成连续失败${consecutiveFailures}章，已停止")
                    break
                }
                val targetIndex = currentIdx + i
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, targetIndex) ?: break
                val prepared = getPreDownloadChapter(book, chapter) ?: continue
                val chapterFailed = synthesizeChapterCuesStream(
                    prepared, httpTts, concurrency, downloaderChannel,
                )
                consecutiveFailures = if (chapterFailed) consecutiveFailures + 1 else 0
            }
        } catch (e: Exception) {
            AppLog.putAudio("听书流式预下载异常: ${e.localizedMessage}", e)
        }
    }

    /**
     * 流式模式下并行预合成一个章节的 cue
     */
    private suspend fun synthesizeChapterCuesStream(
        prepared: PreDownloadChapter,
        httpTts: HttpTTS,
        concurrency: Int,
        downloaderChannel: Channel<Downloader>,
    ): Boolean = coroutineScope {
        val semaphore = kotlinx.coroutines.sync.Semaphore(concurrency)
        var failedCount = 0
        val totalCues = prepared.contentList.size

        prepared.contentList.mapIndexed { index, content ->
            async {
                semaphore.acquire()
                try {
                    val routedVoice = voiceForCue(prepared.queue, index, httpTts)
                    if (routedVoice.engineType == ReadAloudVoice.ENGINE_SYSTEM) {
                        return@async
                    }
                    val cue = prepared.queue.cues.getOrNull(index)
                    if (routedVoice.engineType == ReadAloudVoice.ENGINE_CLOUD ||
                        routedVoice.engineType == ReadAloudVoice.ENGINE_TTS_SERVER) {
                        val segIndex = index - prepared.queue.leadingTitleCueCount
                        if (synthesizeSingleCueWithRetry(
                                routedVoice, cue, content, prepared, segIndex, httpTts,
                            )
                        ) return@async
                        failedCount++
                    } else {
                        val speakText = content.replace(AppPattern.notReadAloudRegex, "")
                        val fileName = streamCacheKey(content, httpTts)
                        val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
                        val downloader = createDownloader(dataSourceFactory, fileName)
                        downloaderChannel.send(downloader)
                    }
                } finally {
                    semaphore.release()
                }
            }
        }.awaitAll()

        failedCount > totalCues / 2
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS,
        speakText: String
    ): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (speakText.isEmpty()) {
                    null
                } else {
                    kotlin.runCatching {
                        runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                            getSpeakStream(httpTts, speakText)
                        }
                    }.onFailure {
                        when (it) {
                            is InterruptedException,
                            is CancellationException -> Unit

                            else -> pauseReadAloud()
                        }
                    }.getOrThrow()
                } ?: resources.openRawResource(R.raw.silent_sound)
            }
        }
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
        return factory
    }

    private fun createDownloader(factory: CacheDataSource.Factory, fileName: String): Downloader {
        val uri = fileName.toUri()
        val request = DownloadRequest.Builder(fileName, uri).build()
        return DefaultDownloaderFactory(factory, okHttpClient.dispatcher.executorService)
            .createDownloader(request)
    }

    private fun createMediaSource(factory: DataSource.Factory, fileName: String): MediaSource {
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(MediaItem.fromUri(fileName))
    }

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String
    ): InputStream? {
        while (true) {
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    speakText = speakText,
                    speakSpeed = (httpTts.speed ?: DEFAULT_TTS_SPEED) + 5,
                    source = httpTts,
                    readTimeout = 300 * 1000L,
                    coroutineContext = currentCoroutineContext()
                )
                var response = analyzeUrl.getResponseAwait()
                currentCoroutineContext().ensureActive()
                val checkJs = httpTts.loginCheckJs
                if (checkJs?.isNotBlank() == true) {
                    response = analyzeUrl.evalJS(checkJs, response) as Response
                }
                response.headers["Content-Type"]?.let { contentType ->
                    val contentType = contentType.substringBefore(";")
                    val ct = httpTts.contentType
                    if (contentType == "application/json" || contentType.startsWith("text/")) {
                        throw NoStackTraceException(response.body.string())
                    } else if (ct?.isNotBlank() == true) {
                        if (!contentType.matches(ct.toRegex())) {
                            throw NoStackTraceException(
                                "TTS服务器返回错误：" + response.body.string()
                            )
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                response.body.byteStream().let { stream ->
                    downloadErrorNo = 0
                    return stream
                }
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        AppLog.putAudio("js错误\n${e.localizedMessage}", e, toast = true)
                        e.printOnDebug()
                        throw e
                    }

                    is SocketTimeoutException, is ConnectException -> {
                        downloadErrorNo++
                        if (downloadErrorNo > 5) {
                            val msg = "tts超时或连接错误超过5次\n${e.localizedMessage}"
                            AppLog.putAudio(msg, e, toast = true)
                            throw e
                        }
                    }

                    else -> {
                        downloadErrorNo++
                        val msg = "tts下载错误\n${e.localizedMessage}"
                        AppLog.putAudio(msg, e)
                        e.printOnDebug()
                        if (downloadErrorNo > 5) {
                            val msg1 = "TTS服务器连续5次错误，已暂停阅读。"
                            AppLog.putAudio(msg1, e, toast = true)
                            throw e
                        } else {
                            AppLog.putAudio("TTS下载音频出错，使用无声音频代替。 | ${snippet(speakText)}")
                            break
                        }
                    }
                }
            }
        }
        return null
    }

    /** 流式播放（ExoPlayer SimpleCache）缓存键：与音频文件缓存无关 */
    private fun streamCacheKey(content: String, httpTts: HttpTTS?): String {
        return MD5Utils.md5Encode16(readerReadAloudChapter?.title.orEmpty()) + "_" +
                MD5Utils.md5Encode16("${httpTts?.url.orEmpty()}-|-$speechRate-|-$content")
    }

    // ---------------- 音频缓存文件（B8） ----------------

    /** 计算某条目的缓存文件（父目录顺带建好，供合成器直接写入） */
    private fun cacheFileFor(
        book: String,
        chapterIndex: Int,
        segIndex: Int,
        text: String,
        voiceKey: String,
    ): File {
        val hash = ReadAloudAudioCacheKeys.contentHash(voiceKey, speechRate, text)
        val file = if (segIndex == ReadAloudAudioCacheKeys.TITLE_SEG_INDEX) {
            audioCache.titleFile(book, chapterIndex, hash)
        } else {
            audioCache.cueFile(book, chapterIndex, segIndex, hash)
        }
        file.parentFile?.mkdirs()
        return file
    }

    private fun cueCacheFile(segIndex: Int, text: String, voiceKey: String): File = cacheFileFor(
        book = ReadBook.book?.name.orEmpty(),
        chapterIndex = readerReadAloudChapter?.chapterIndex ?: ReadBook.durChapterIndex,
        segIndex = segIndex,
        text = text,
        voiceKey = voiceKey,
    )

    /** 缓存文件有效判定：存在且非空（0 字节视为未缓存，避免播放解码失败被跳过） */
    private fun File.isValidAudio(): Boolean = exists() && length() > 0L

    /** 音频日志中的 cue 标识：#序号 + 声线标签 */
    private fun cueLabel(index: Int, voice: ReadAloudVoice): String =
        "#$index " + voice.speakerId.ifBlank { voice.displayName }

    /** 音频日志中的文本摘要（单行、截断） */
    private fun snippet(text: String, max: Int = 24): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length > max) oneLine.take(max) + "…" else oneLine
    }

    /**
     * 文件型引擎合成 + 失败重试一次（400ms 后）。
     * [attempt] 返回失败原因（null = 成功），失败原因会写入音频缓存日志。
     */
    private suspend fun synthesizeWithRetry(
        label: String,
        text: String,
        attempt: suspend () -> String?,
    ): String? {
        val first = attempt()
        if (first == null) return null
        AppLog.putAudio("【音频缓存】合成失败重试 $label: $first | ${snippet(text)}")
        delay(400)
        return attempt()
    }

    private fun hasFileSynthesisCue(): Boolean = playbackQueue.cues.indices.any { index ->
        voiceForCue(playbackQueue, index, ReadAloud.httpTTS ?: return@any false).engineType in
            setOf(
                ReadAloudVoice.ENGINE_SYSTEM,
                ReadAloudVoice.ENGINE_CLOUD,
                ReadAloudVoice.ENGINE_TTS_SERVER,
            )
    }

    private fun voiceForCue(
        queue: ReadAloudPlaybackQueue,
        index: Int,
        default: HttpTTS,
    ): ReadAloudVoice {
        val cue = queue.cues.getOrNull(index)
            ?: return ReadAloudVoice(
                id = "runtime-http:${default.id}",
                engineType = ReadAloudVoice.ENGINE_HTTP,
                engineId = default.id.toString(),
                speakerId = "",
                displayName = default.name,
            )
        return SpeechVoiceRouter.route(
            cue = cue,
            supportedEngineTypes = setOf(
                ReadAloudVoice.ENGINE_HTTP,
                ReadAloudVoice.ENGINE_SYSTEM,
                ReadAloudVoice.ENGINE_CLOUD,
                ReadAloudVoice.ENGINE_TTS_SERVER,
            ),
            defaultRoute = SpeechEngineRoute(
                engineType = ReadAloud.coordinatorDefaultEngineType,
                engineId = ReadAloud.coordinatorDefaultEngineId,
                speakerId = ReadAloud.coordinatorDefaultSpeakerId,
            ),
        ).voice!!
    }

    private fun httpTtsForCue(index: Int, default: HttpTTS): HttpTTS {
        return httpTtsForCue(playbackQueue, index, default)
    }

    private fun httpTtsForCue(
        queue: ReadAloudPlaybackQueue,
        index: Int,
        default: HttpTTS,
    ): HttpTTS {
        val cue = queue.cues.getOrNull(index) ?: return default
        val routed = SpeechVoiceRouter.route(
            cue = cue,
            supportedEngineTypes = setOf(ReadAloudVoice.ENGINE_HTTP),
            defaultRoute = SpeechEngineRoute(
                engineType = ReadAloudVoice.ENGINE_HTTP,
                engineId = default.id.toString(),
            ),
        ).voice ?: return default
        val id = routed.engineId.toLongOrNull() ?: return default
        return appDb.httpTTSDao.get(id) ?: default
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                upPlayPos()
            }
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        if (readerReadAloudChapter == null) return
        playIndexJob = lifecycleScope.launch {
            if (isChapterTitleAt(nowSpeak)) return@launch
            if (exoPlayer.duration <= 0) {
                upTtsProgress(readAloudNumber + 1)
                return@launch
            }
            val speakTextLength = contentList[nowSpeak].length
            if (speakTextLength <= 0) {
                return@launch
            }
            val sleep = exoPlayer.duration / speakTextLength
            val start = speakTextLength * exoPlayer.currentPosition / exoPlayer.duration
            upTtsProgress(readAloudNumber + start.toInt())
            for (i in start until contentList[nowSpeak].length) {
                val chapterPosition = readAloudNumber + i.toInt()
                updateReadAloudProgressSnapshot(chapterPosition)
                if (moveToReadAloudPage(chapterPosition)) {
                    upTtsProgress(chapterPosition)
                }
                delay(sleep)
            }
        }
    }

    /**
     * 更新朗读速度
     * 全局语速已改为播放端 (ExoPlayer) 变速, 对已合成音频即时生效, 无需重新下载。
     */
    override fun upSpeechRate(reset: Boolean) {
        exoPlayer.setPlaybackSpeed(globalPlaybackSpeed)
        upMediaMetadata()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_IDLE -> {
                // 空闲
            }

            Player.STATE_BUFFERING -> {
                // 缓冲中
            }

            Player.STATE_READY -> {
                // 准备好
                if (pause) return
                exoPlayer.play()
                upPlayPos()
            }

            Player.STATE_ENDED -> {
                // 结束
                playErrorNo = 0
                val interval = readAloudSettings.ttsParagraphInterval.toLong()
                if (interval > 0) {
                    val isLastParagraph = nowSpeak >= contentList.lastIndex
                    updateNextPos(naturalCompletion = true)
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    if (!pause && !isLastParagraph) {
                        AppLog.putDebug("HttpTTS段落开始停顿: $interval 毫秒")
                        execute {
                            delay(interval)
                            if (!pause) {
                                launch(Main) {
                                    if (!pause) {
                                        play()
                                        AppLog.putDebug("HttpTTS段落停顿结束，恢复播放")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    updateNextPos(naturalCompletion = true)
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                }
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }

            else -> {}
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        updateNextPos(naturalCompletion = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        upPlayPos()
        upMediaMetadata(showContent = true)
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        AppLog.putAudio(
            "【音频缓存】播放失败已跳过本条 #$nowSpeak: ${error.localizedMessage} | " +
                snippet(contentList.getOrNull(nowSpeak).orEmpty()),
            error,
        )
        deleteCurrentSpeakFile()
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})")
            AppLog.putAudio("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})", error)
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                updateNextPos(naturalCompletion = true)
            }
        }
    }

    private fun deleteCurrentSpeakFile() {
        if (readAloudSettings.streamReadAloudAudio) {
            return
        }
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration?.uri?.path ?: return
        val file = File(filePath)
        // 只删音频缓存目录内的文件（静音占位不在其中）
        if (file.absolutePath.startsWith(audioCache.rootDir().absolutePath)) {
            file.delete()
        }
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<HttpReadAloudService>(actionStr)
    }

    class CustomLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            return C.TIME_UNSET
        }
    }

}

/** 源级语速默认值, 对应 1 倍速, 与全局语速共用 0..80 的刻度 */
private const val DEFAULT_TTS_SPEED = 5
