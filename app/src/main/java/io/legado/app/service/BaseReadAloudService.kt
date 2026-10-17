@file:Suppress("DEPRECATION")

package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.annotation.CallSuper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.data.repository.HighlightRuleRepository
import io.legado.app.data.repository.ReadAloudDataRepository
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.model.PlaybackTimer
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ReadAloudPlaybackCursor
import io.legado.app.domain.model.readaloud.ReadAloudPlaybackInfo
import io.legado.app.domain.model.readaloud.ReadAloudPlaybackQueue
import io.legado.app.domain.model.readaloud.ReadAloudSessionStatus
import io.legado.app.domain.model.readaloud.SpeechPlanItem
import io.legado.app.domain.model.readaloud.resolveReadAloudStartPosition
import io.legado.app.domain.model.settings.ReadAloudTimerMode
import io.legado.app.domain.usecase.PrepareChapterSpeechPlanUseCase
import io.legado.app.help.readaloud.analysis.AnalysisSchedulerV3
import io.legado.app.feature.reader.core.navigation.ReaderPageNavigator
import io.legado.app.feature.reader.core.readaloud.ReaderReadAloudChapter
import io.legado.app.feature.reader.legacy.LegacyReaderChapterPaginationResult
import io.legado.app.feature.reader.legacy.LegacyReaderChapterPaginator
import io.legado.app.feature.reader.legacy.LegacyReaderPageDecorationFactory
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudSessionStore
import io.legado.app.service.readaloud.Spoken
import io.legado.app.model.ReadBook
import io.legado.app.model.reader.ReaderChapterInput
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.service.BaseReadAloudService.Companion.speechDrivingNavigation
import io.legado.app.domain.gateway.PlaybackCapsuleGateway
import io.legado.app.domain.model.PlaybackCapsuleSource
import io.legado.app.service.readaloud.ReadAloudOverlayWindow
import io.legado.app.ui.config.readConfig.ReadConfig
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.LogUtils
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.isNightMode
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get
import splitties.init.appCtx
import splitties.systemservices.audioManager
import splitties.systemservices.notificationManager
import splitties.systemservices.powerManager
import splitties.systemservices.telephonyManager
import splitties.systemservices.wifiManager

/**
 * 朗读服务
 */
abstract class BaseReadAloudService : BaseService(),
    AudioManager.OnAudioFocusChangeListener {

    private var capsuleOverlayWindow: ReadAloudOverlayWindow? = null

    companion object {

        @JvmStatic
        @Volatile
        var isRun = false
            private set

        /** requestStop clears isRun immediately; teardown must finish before replacing the service. */
        @Volatile
        var isAlive = false
            private set

        @JvmStatic
        @Volatile
        var pause = true
            private set

        @Volatile
        private var stopRequested = false

        /** 让停止意图在服务异步销毁前即可被阅读器观察到，避免重排正文时重新启动朗读。 */
        @JvmStatic
        @Synchronized
        fun requestStop(): Boolean {
            if (!isRun || stopRequested) return false
            stopRequested = true
            isRun = false
            pause = true
            return true
        }

        @JvmStatic
        var timeMinute: Int = 0
            private set(value) {
                field = PlaybackTimer.normalize(value)
            }

        @JvmStatic
        @Volatile
        var currentChapterIndex: Int = -1
            private set

        /**
         * 批215：当前朗读章的页表是否为「段落分页代启」——真实分页快照未就绪时的兜底启动态。
         * 真实快照落地后由 [io.legado.app.model.ReadBook] 请求接轨（syncReaderLayout 重建精确页表并清此标志）。
         */
        @JvmStatic
        @Volatile
        var currentChapterProvisional: Boolean = false
            private set

        @JvmStatic
        @Volatile
        var currentProgress: Int = 0
            private set

        /**
         * 朗读服务自身驱动页面移动（按页朗读、进度翻页、换章）时为 true；
         * 供 [io.legado.app.model.ReadBook] 区分「朗读驱动翻页」与「用户手动导航」，
         * 避免朗读推进被误判为手动脱离。
         */
        @JvmStatic
        @Volatile
        var speechDrivingNavigation: Boolean = false
            private set

        /** 在朗读驱动的页面移动/同步外侧调用，期间 [speechDrivingNavigation] 为 true。 */
        @JvmStatic
        fun <T> withSpeechNavigation(block: () -> T): T {
            speechDrivingNavigation = true
            return try {
                block()
            } finally {
                speechDrivingNavigation = false
            }
        }

        fun isPlay(): Boolean {
            return isRun && !pause
        }

        private const val TAG = "BaseReadAloudService"
        private const val ACTION_ADD_TIMER = "io.legado.app.action.ADD_READ_ALOUD_TIMER"
        private const val ACTION_OPEN_MEDIA_CONTROL_READER =
            "io.legado.app.action.OPEN_READ_ALOUD_MEDIA_CONTROL"

        /**
         * 语速 1.0x 时的每秒朗读字数估算值, 用于把字符进度换算成媒体播放器时间轴
         */
        private const val ESTIMATED_CHARS_PER_SECOND = 4f

        private const val READ_ALOUD_MEDIA_SESSION_ACTIONS =
            (PlaybackStateCompat.ACTION_PLAY
                    or PlaybackStateCompat.ACTION_PAUSE
                    or PlaybackStateCompat.ACTION_PLAY_PAUSE
                    or PlaybackStateCompat.ACTION_STOP
                    or PlaybackStateCompat.ACTION_SEEK_TO
                    or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    or PlaybackStateCompat.ACTION_SKIP_TO_NEXT)

    }

    private val sessionStore: ReadAloudSessionStore by lazy {
        get(ReadAloudSessionStore::class.java)
    }
    protected val bookSession get() = ReadAloud.bookSession

    private val useWakeLock = ReadConfig.readAloudWakeLock
    private val wakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:ReadAloudService")
            .apply {
                this.setReferenceCounted(false)
            }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:AudioPlayService")
            ?.apply {
                setReferenceCounted(false)
            }
    }
    private val mFocusRequest: AudioFocusRequestCompat by lazy {
        MediaHelp.buildAudioFocusRequestCompat(this)
    }
    private val mediaSessionCompat: MediaSessionCompat by lazy {
        MediaSessionCompat(this, "readAloud")
    }
    private val phoneStateListener by lazy {
        ReadAloudPhoneStateListener()
    }
    internal var contentList = emptyList<String>()
    private var contentChapterPositions = emptyList<Int?>()
    /** Canonical, character-aware plan for the current chapter. Playback adoption is incremental. */
    internal var speechPlan = emptyList<SpeechPlanItem>()
    internal var playbackQueue = ReadAloudPlaybackQueue.Empty
    internal var playbackCursor: ReadAloudPlaybackCursor? = null
    internal var nowSpeak: Int = 0
    internal var readAloudNumber: Int = 0
    internal var readerReadAloudChapter: ReaderReadAloudChapter? = null
    internal var pageIndex = 0
    private var needResumeOnAudioFocusGain = false
    private var needResumeOnCallStateIdle = false
    private var registeredPhoneStateListener = false
    private var dsJob: Job? = null
    private var upNotificationJob: Job? = null
    private var upMediaProgressJob: Job? = null
    private var lastMediaSessionState = PlaybackStateCompat.STATE_NONE
    private var lastMediaSessionPositionMs = -1L
    private var lastMediaSessionUpdateElapsedMs = 0L
    @Volatile
    private var systemMediaCompatibilityEnabled =
        ReadConfig.systemMediaControlCompatibilityChange
    @Volatile
    private var androidMediaControlEnabled = ReadConfig.androidMediaControlEnabled
    private val finishChapterTimerLock = Any()
    private var finishChapterAtIndex = NO_FINISH_CHAPTER

    /**
     * 章节定时的剩余章数；`null` 表示未开启。
     *
     * 与 [finishChapterAtIndex] 一样只在 [finishChapterTimerLock] 下读写：两者在同一处
     * 章末判定里一起被消费，分开加锁会出现「臂标已清除但计数没减」的中间态。
     */
    private var chapterQuota: Int? = null
    private var prepareReadAloudJob: Coroutine<*>? = null
    private var prepareReadAloudGeneration = 0L

    // 追更216b：分页快照等待窗口已下线（「自排版」取代——快照缺失时直接跑分页器得真页表）。

    /**
     * 追更216b「自排版」：快照缺失时用捕获的排版环境（[ReadBook.paginationEnvironment]）
     * 跑 [LegacyReaderChapterPaginator] 得真页表（与阅读器同源；换章起播不再依赖排版批次落地）。
     */
    private suspend fun paginatePageStartsLocally(input: ReaderChapterInput): List<Int>? {
        val environment = ReadBook.paginationEnvironment ?: return null
        return try {
            val result = LegacyReaderChapterPaginator.paginate(
                book = input.book,
                bookSource = input.bookSource,
                chapter = input.chapter,
                displayTitle = input.displayTitle,
                content = input.content,
                source = input.source,
                revision = 0L,
                viewportWidthPx = environment.widthPx,
                viewportHeightPx = environment.heightPx,
                contentPaddingLeftPx = environment.contentPaddingLeftPx,
                contentPaddingTopPx = environment.contentPaddingTopPx,
                contentPaddingRightPx = environment.contentPaddingRightPx,
                contentPaddingBottomPx = environment.contentPaddingBottomPx,
                paginationStyle = environment.style,
                highlightRules = HighlightRuleRepository().loadEnabled(ReadBookConfig.durConfig.name),
                titleMode = ReadBookConfig.titleMode,
                paragraphIndent = ReadBookConfig.paragraphIndent,
                textFullJustify = ReadBookConfig.textFullJustify,
                headerExtentPx = LegacyReaderPageDecorationFactory.headerExtentPx(),
                footerExtentPx = LegacyReaderPageDecorationFactory.footerExtentPx(),
            )
            when (result) {
                is LegacyReaderChapterPaginationResult.Success ->
                    result.pages.map(ReaderPageNavigator::pageStart).takeIf { it.isNotEmpty() }
                is LegacyReaderChapterPaginationResult.Unsupported -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.putAnalysis("自排版失败: ${e.localizedMessage}", e)
            null
        }
    }
    private var cover: Bitmap =
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.ic_launcher)
    var pageChanged = false
    private var toLast = false
    var paragraphStartPos = 0

    /** 当前朗读段在章节语义文本中的绝对起始位置；页内切段不引入换行符，进度必须以它为准 */
    protected fun paragraphChapterPositionAt(index: Int): Int? =
        contentChapterPositions.getOrNull(index)

    protected fun isChapterTitleAt(index: Int): Boolean =
        index in contentChapterPositions.indices && contentChapterPositions[index] == null
    protected open val useSpeechPlaybackQueue: Boolean = false

    /**
     * newReadAloud 整体替换本章播放状态(队列/游标/段落)后回调,
     * 引擎实现用它作废旧章节发言的迟到回调
     */
    protected open fun onPlaybackStateReplaced() {}
    protected val hasSpeechPlaybackQueue: Boolean
        get() = useSpeechPlaybackQueue && !playbackQueue.isEmpty

    /** 当前朗读倍速, 用于把字符进度估算成媒体播放器时间轴 */
    protected open val currentSpeechRate: Float
        get() = 1f

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                pauseReadAloud()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        isAlive = true
        stopRequested = false
        isRun = true
        pause = false
        get<PlaybackCapsuleGateway>(PlaybackCapsuleGateway::class.java).setSessionAvailable(
            PlaybackCapsuleSource.ReadAloud,
            true
        )
        capsuleOverlayWindow = ReadAloudOverlayWindow(this)
        // 新朗读会话默认跟随当前显示页（用户手动翻页脱离后由阅读界面负责恢复）
        sessionStore.restoreReadAloudFollow()
        observeLiveBus()
        initMediaSession()
        observeMediaControlSettings()
        initBroadcastReceiver()
        initPhoneStateListener()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        applyPersistedTimer()
        execute {
            ImageLoader
                .loadBitmap(this@BaseReadAloudService, bookSession.book?.getDisplayCover())
                .submit()
                .get()
        }.onSuccess {
            if (it.width > 16 && it.height > 16) {
                cover = it
                upMediaMetadata()
                upReadAloudNotification()
            }
        }
    }

    fun observeLiveBus() {
        observeEvent<Bundle>(EventBus.READ_ALOUD_PLAY) {
            val play = it.getBoolean("play")
            val pageIndex = it.getInt("pageIndex")
            val startPos = it.getInt("startPos")
            val chapterPosition = it.getInt("chapterPosition", -1).takeIf { position -> position >= 0 }
            handlePlayRequest(play, pageIndex, startPos, chapterPosition)
        }
        lifecycleScope.launch {
            merge(
                AppConfigStore.observeBoolean(PreferKey.ignoreAudioFocus).drop(1),
                AppConfigStore.observeBoolean(PreferKey.pauseReadAloudWhilePhoneCalls).drop(1),
            ).collect {
                initPhoneStateListener()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        capsuleOverlayWindow?.onConfigurationChanged()
    }

    override fun onDestroy() {
        get<PlaybackCapsuleGateway>(PlaybackCapsuleGateway::class.java).setSessionAvailable(
            PlaybackCapsuleSource.ReadAloud,
            false
        )
        capsuleOverlayWindow?.close()
        capsuleOverlayWindow = null
        // [ttsrv] 会话进度随服务销毁收尾（自排版/预碎比不落盘的空档也并进来）
        bookSession.upReadTime()
        bookSession.updateProgress(currentProgress, forceSave = true, expectedChapterIndex = currentChapterIndex)
        super.onDestroy()
        prepareReadAloudGeneration++
        prepareReadAloudJob?.cancel()
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        isRun = false
        pause = true
        runCatching {
            val scheduler: AnalysisSchedulerV3 = get(AnalysisSchedulerV3::class.java)
            scheduler.stopSession()
        }
        currentChapterIndex = -1
        currentProgress = 0
        currentChapterProvisional = false
        abandonFocus()
        unregisterReceiver(broadcastReceiver)
        postEvent(EventBus.ALOUD_STATE, Status.STOP)
        notificationManager.cancel(NotificationId.ReadAloudService)
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_STOPPED)
        systemMediaCompatibilityEnabled = false
        androidMediaControlEnabled = false
        mediaSessionCompat.isActive = false
        mediaSessionCompat.release()
        bookSession.uploadProgress()
        unregisterPhoneStateListener(phoneStateListener)
        bookSession.finishReadTime()
        upNotificationJob?.invokeOnCompletion {
            notificationManager.cancel(NotificationId.ReadAloudService)
        }
        isAlive = false
        sessionStore.stop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.play -> {
                if (intent.hasExtra("sessionGeneration") &&
                    intent.getLongExtra("sessionGeneration", -1) != bookSession.state.value.generation
                ) return super.onStartCommand(intent, flags, startId)
                handlePlayRequest(
                    intent.getBooleanExtra("play", true),
                    intent.getIntExtra("pageIndex", ReadBook.durPageIndex),
                    intent.getIntExtra("startPos", 0),
                    intent.getIntExtra("chapterPosition", -1).takeIf { it >= 0 },
                )
            }

            IntentAction.pause -> pauseReadAloud()
            IntentAction.resume -> resumeReadAloud()
            IntentAction.upTtsSpeechRate -> upSpeechRate(true)
            IntentAction.syncReadAloudLayout -> syncReaderLayout()
            IntentAction.prevParagraph -> prevP()
            IntentAction.nextParagraph -> nextP()
            IntentAction.prev -> prevChapter()
            IntentAction.next -> nextChapter()
            IntentAction.addTimer -> addTimer()
            IntentAction.setTimer -> setTimer(intent.getIntExtra("minute", 0))
            IntentAction.setTimerChapters ->
                setTimerChapters(intent.getIntExtra("chapters", 0))
            IntentAction.stop -> stopReadAloudService()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun stopReadAloudService() {
        requestStop()
        stopSelf()
    }

    // ---- M4：待命启动（防误触；点「开始朗读」先进待命，再点一次才走全套）----
    @Volatile
    private var standbyArmed = false

    /** 本服务实例是否已真正启动过朗读（待命不算）——同实例的后续普通启动不再进待命 */
    @Volatile
    private var sessionPrepared = false

    private fun standbyEnabled(): Boolean = runCatching {
        val gateway: ReadAloudSettingsGateway = get(ReadAloudSettingsGateway::class.java)
        gateway.currentSettings.standbyStart
    }.getOrDefault(true)

    /** M4：朗读启动请求统一入口（待命判定 / 待命确认 / 正常启动） */
    private fun handlePlayRequest(play: Boolean, pageIndex: Int, startPos: Int, chapterPosition: Int?) {
        if (standbyArmed) {
            if (!play) return // 待命中：非播放请求（如翻页同步）忽略，保持待命
            standbyArmed = false
            newReadAloud(true, pageIndex, startPos, chapterPosition)
            return
        }
        if (play && !sessionPrepared && standbyEnabled()) {
            enterStandby()
            return
        }
        newReadAloud(play, pageIndex, startPos, chapterPosition)
    }

    /** M4：进入待命——不调度分析 / 不建计划（无「等分析就绪」计时）/ 不合成 / 不播放；再点一次走全套 */
    private fun enterStandby() {
        standbyArmed = true
        pause = true
        sessionStore.setStatus(ReadAloudSessionStatus.Paused)
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        upReadAloudNotification()
        toastOnUi("朗读已待命：再点一次开始")
    }

    /** M4：待命确认——清除待命并走全套启动（建计划/调度分析/播放）；true=已消费（调用方直接返回） */
    protected fun tryStartFromStandby(): Boolean {
        if (!standbyArmed) return false
        standbyArmed = false
        newReadAloud(
            play = true,
            requestedPageIndex = ReadBook.durPageIndex,
            requestedStartPos = 0,
            requestedChapterPosition = null,
        )
        return true
    }

    private fun newReadAloud(
        play: Boolean,
        requestedPageIndex: Int,
        requestedStartPos: Int,
        requestedChapterPosition: Int?,
    ) {
        sessionPrepared = true
        // 每次"从指定位置开始朗读"都是新会话：恢复页面跟随朗读（手动脱离后点"从此处朗读"等）
        sessionStore.restoreReadAloudFollow()
        clearFinishChapterTimerIfChapterChanged(bookSession.chapterIndex)
        val generation = ++prepareReadAloudGeneration
        prepareReadAloudJob?.cancel()
        playStop()
        prepareReadAloudJob = execute(executeContext = IO) {
            val prepared = bookSession.prepareChapter()
            val input = requireNotNull(prepared.input)
            val pagination = requireNotNull(prepared.pagination)
            val preparedChapter = ReaderReadAloudChapter.create(
                chapterIndex = input.chapter.index,
                title = input.displayTitle,
                semanticContent = input.source.semanticContent,
                pageStarts = pagination.pageStarts,
            )
            val start = resolveReadAloudStartPosition(
                requestedPageIndex = requestedPageIndex,
                requestedOffsetInPage = requestedStartPos,
                requestedChapterPosition = requestedChapterPosition,
                pageIndexAt = preparedChapter::pageIndexAt,
                pageStart = preparedChapter::pageStart,
            )
            val pageIndex = start.pageIndex
            val startPos = start.offsetInPage
            var preparedReadAloudNumber = preparedChapter.pageStart(pageIndex) + startPos
            val startsAtChapterBeginning = preparedReadAloudNumber == 0
            // 追更216d：不发声的段（空白/纯符号）不进朗读单元集合——游标落在这种段上时高亮
            // 停住不动，而引擎入队又跳过它，回调下标与 nowSpeak 就整条错开（见 readAloudParagraphs）。
            val preparedParagraphs = preparedChapter.readAloudParagraphs()
            var preparedContentList = preparedParagraphs
                .map { it.text }
            var preparedContentChapterPositions: List<Int?> =
                preparedParagraphs.map { it.chapterPosition }
            // B10.4.4：先启动分析调度（本会话的分析生产源），再构建播放计划——
            // 「等分析就绪再出声」模式下计划构建会阻塞等待分析结果，若调度后启动会形成自锁
            runCatching {
                val scheduler: AnalysisSchedulerV3 = get(AnalysisSchedulerV3::class.java)
                val bookUrl: String = bookSession.book?.bookUrl.orEmpty()
                if (bookUrl.isNotEmpty()) {
                    scheduler.startSession(bookUrl, bookSession.chapterIndex)
                }
            }.onFailure {
                // 批212g：会话取消是预期语义（换章/换书/停止），静默
                if (it !is io.legado.app.help.coroutine.ActivelyCancelException &&
                    it !is kotlinx.coroutines.CancellationException
                ) {
                    AppLog.putAnalysis("分析调度会话启动失败: ${it.localizedMessage}", it)
                }
            }
            // B10.5·Q4：记录「最近朗读的书」（非嵌入入口的书籍管理/角色管理自动切换用；cunfang 由入口 switchBook 同步）
            // 批212g：会话取消（换章/换书/停止时旧 prepareJob 被 cancel）是预期语义，静默；
            // 仅真实失败记日志（此前取消噪音会刷满分析日志）。
            runCatching {
                val bookName: String = bookSession.book?.name.orEmpty()
                if (bookName.isNotEmpty()) {
                    val readAloudRepo: ReadAloudDataRepository = get(ReadAloudDataRepository::class.java)
                    readAloudRepo.setRecentReadBook(bookName)
                }
            }.onFailure {
                if (it !is io.legado.app.help.coroutine.ActivelyCancelException &&
                    it !is kotlinx.coroutines.CancellationException
                ) {
                    AppLog.putAnalysis("记录最近朗读书名失败: ${it.localizedMessage}", it)
                }
            }
            val preparedSpeechPlan = buildSpeechPlan(
                bookUrl = prepared.book?.bookUrl.orEmpty(),
                chapterIndex = input.chapter.index,
                paragraphs = preparedChapter.canonicalSpeechParagraphs(),
            )
            if (generation != prepareReadAloudGeneration) return@execute
            var preparedPlaybackQueue = runCatching {
                ReadAloudPlaybackQueue.from(preparedSpeechPlan)
            }.onFailure {
                AppLog.putAnalysis("创建多角色播放队列失败，使用原朗读方式\n${it.localizedMessage}", it)
            }.getOrDefault(ReadAloudPlaybackQueue.Empty)
            var preparedPlaybackCursor = preparedPlaybackQueue.cursorAt(preparedReadAloudNumber)
            var pos = startPos
            val usePreparedPlaybackQueue = useSpeechPlaybackQueue && !preparedPlaybackQueue.isEmpty
            var preparedNowSpeak = preparedChapter.paragraphIndexAtOrAfter(preparedReadAloudNumber + 1)
            if (!usePreparedPlaybackQueue && preparedNowSpeak !in preparedContentList.indices) {
                AppLog.put(
                    "启动朗读失败：无法定位朗读段落 position=$preparedReadAloudNumber " +
                        "pageIndex=$pageIndex startPos=$startPos"
                )
                return@execute
            }
            val moveToLast = toLast
            if (moveToLast) {
                preparedReadAloudNumber = preparedParagraphs.last().chapterPosition
                preparedNowSpeak = preparedContentList.lastIndex
                pos = 0
            }
            // startPos 是页内偏移，需换算为目标朗读段内的偏移
            if (!usePreparedPlaybackQueue && !moveToLast &&
                preparedNowSpeak in preparedParagraphs.indices
            ) {
                val target = preparedParagraphs[preparedNowSpeak]
                pos = (preparedReadAloudNumber - target.chapterPosition)
                    .coerceIn(0, target.text.length)
                preparedReadAloudNumber = target.chapterPosition
            }
            var preparedParagraphStartPos = pos
            if (usePreparedPlaybackQueue) {
                preparedPlaybackQueue.cursorAt(preparedReadAloudNumber)?.let { cursor ->
                    preparedPlaybackCursor = cursor
                    preparedContentList = preparedPlaybackQueue.cues.map { it.text }
                    preparedNowSpeak = cursor.cueIndex
                    preparedParagraphStartPos = cursor.offset
                    preparedReadAloudNumber = preparedPlaybackQueue.cues[cursor.cueIndex].chapterStart
                }
            }
            val shouldReadChapterTitle = !moveToLast && startsAtChapterBeginning &&
                    preparedChapter.title.isNotBlank()
            if (shouldReadChapterTitle) {
                if (usePreparedPlaybackQueue) {
                    preparedPlaybackQueue =
                        preparedPlaybackQueue.withChapterTitle(
                            preparedChapter.title,
                            ReadAloudPlaybackQueue.narratorVoiceOf(preparedSpeechPlan),
                        )
                    preparedPlaybackCursor = ReadAloudPlaybackCursor(0, 0)
                    preparedContentList = preparedPlaybackQueue.cues.map { it.text }
                    preparedContentChapterPositions = preparedPlaybackQueue.cues.map { cue ->
                        cue.chapterStart.takeUnless { cue.isChapterTitle }
                    }
                } else {
                    preparedContentList = listOf(preparedChapter.title.trim()) + preparedContentList
                    preparedContentChapterPositions = listOf(null) + preparedContentChapterPositions
                }
                preparedNowSpeak = 0
                preparedParagraphStartPos = 0
                preparedReadAloudNumber = 0
            } else if (usePreparedPlaybackQueue) {
                preparedContentChapterPositions = preparedPlaybackQueue.cues.map { it.chapterStart }
            }
            if (generation != prepareReadAloudGeneration) return@execute
            this@BaseReadAloudService.pageIndex = pageIndex
            readerReadAloudChapter = preparedChapter
            contentList = preparedContentList
            contentChapterPositions = preparedContentChapterPositions
            speechPlan = preparedSpeechPlan
            playbackQueue = preparedPlaybackQueue
            playbackCursor = preparedPlaybackCursor
            nowSpeak = preparedNowSpeak
            readAloudNumber = preparedReadAloudNumber
            paragraphStartPos = preparedParagraphStartPos
            updateReadAloudProgressSnapshot(preparedReadAloudNumber + preparedParagraphStartPos + 1)
            onPlaybackStateReplaced()
            if (moveToLast) toLast = false
            preparedPlaybackCursor?.takeIf { hasSpeechPlaybackQueue }?.let(::publishPlaybackInfo)
            launch(Main) {
                if (generation != prepareReadAloudGeneration) return@launch
                syncReaderToPlaybackPage()
                upMediaMetadata()
                if (play) play() else pageChanged = true
            }
        }.onError {
            AppLog.putAnalysis("启动朗读出错\n${it.localizedMessage}", it, toast = true)
        }
    }

    protected suspend fun buildSpeechPlan(
        bookUrl: String,
        chapterIndex: Int,
        paragraphs: List<CanonicalSpeechParagraph>,
    ): List<SpeechPlanItem> {
        if (bookUrl.isEmpty()) return emptyList()
        val multiSpeakerOn = ReadConfig.useMultiSpeaker
        // 「多角色朗读」是唯一的发声开关：关掉就没有任何计划，整章回到用户在朗读设置里
        // 选的默认引擎。
        if (!multiSpeakerOn) return emptyList()
        val prepareSpeechPlan: PrepareChapterSpeechPlanUseCase =
            get(PrepareChapterSpeechPlanUseCase::class.java)
        return try {
            prepareSpeechPlan(
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                paragraphs = paragraphs,
                useMultiSpeaker = ReadConfig.useMultiSpeaker,
                bookName = bookSession.book?.name.orEmpty(),
            )
        } catch (e: CancellationException) {
            // B10.4.3：重新触发朗读/停止会取消等待中的计划构建——取消不是失败，不记错、交回协程机制
            throw e
        } catch (e: Throwable) {
            AppLog.putAnalysis("生成多角色朗读计划失败，使用原朗读方式\n${e.localizedMessage}", e)
            emptyList()
        }
    }

    /**
     * 马上要送去发声的那一份文本。
     *
     * 必须在偏移（`paragraphStartPos` 等）用完之后才调用，且只作用于这一句：
     * [contentList] 里存的那一份要保留原样，改了会让进度定位错位。
     */
    protected open fun speechText(text: String): String = spokenFor(text).text

    /**
     * [speechText] 的那一份文本 + 引擎回传下标 → 正文下标的还原。
     *
     * 「从半句接着念」与朗读进度共用这一份换算；本移植版无标记删改，两份下标天然一致。
     * [base] 传这一句在段落里的起点，[Spoken.storedOf] 就直接给出段内绝对偏移。
     */
    protected open fun spokenFor(storedTail: String, base: Int = 0): Spoken =
        Spoken.passThrough(storedTail, base)



    @SuppressLint("WakelockTimeout")
    open fun play() {
        if (stopRequested) return
        if (useWakeLock) {
            wakeLock.acquire()
            wifiLock?.acquire()
        }
        isRun = true
        pause = false
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        upMediaProgress()
        upReadAloudNotification()
        sessionStore.setStatus(ReadAloudSessionStatus.Playing)
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
        bookSession.upReadTime()
    }

    abstract fun playStop()

    @CallSuper
    open fun pauseReadAloud(abandonFocus: Boolean = true) {
        bookSession.upReadTime()
        bookSession.updateProgress(currentProgress, forceSave = true, expectedChapterIndex = currentChapterIndex)
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        pause = true
        if (abandonFocus) {
            abandonFocus()
        }
        upMediaProgressJob?.cancel()
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        sessionStore.setStatus(ReadAloudSessionStatus.Paused)
        postEvent(EventBus.ALOUD_STATE, Status.PAUSE)
        bookSession.uploadProgress()
        doDs()
        bookSession.finishReadTime()
    }

    @SuppressLint("WakelockTimeout")
    @CallSuper
    open fun resumeReadAloud() {
        if (tryStartFromStandby()) return
        resumeReadAloudInternal()
    }

    private fun resumeReadAloudInternal() {
        pause = false
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        upMediaProgress()
        sessionStore.setStatus(ReadAloudSessionStatus.Playing)
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
        bookSession.upReadTime()
    }

    abstract fun upSpeechRate(reset: Boolean = false)

    fun upTtsProgress(progress: Int) {
        bookSession.upReadTime()
        val chapterPosition = progress.coerceAtLeast(0)
        if (hasSpeechPlaybackQueue) {
            playbackQueue.cursorAt(chapterPosition)?.let(::publishPlaybackInfo)
        } else {
            val chapterLength = readerReadAloudChapter?.chapterLength ?: chapterPosition
            sessionStore.updatePlayback(
                ReadAloudPlaybackInfo(
                    chapterTitle = readerReadAloudChapter?.title.orEmpty(),
                    chapterIndex = readerReadAloudChapter?.chapterIndex ?: -1,
                    chapterPosition = chapterPosition,
                    chapterLength = chapterLength.coerceAtLeast(1),
                    text = contentList.getOrNull(nowSpeak).orEmpty(),
                )
            )
            refreshMediaSessionPlaybackState()
        }
        updateReadAloudProgressSnapshot(progress)
        postEvent(EventBus.TTS_PROGRESS, progress)
    }

    /** 同一个音效单元只响一次的判据（[takeCueSounds] 消费这里）。 */
    private var soundedCue: io.legado.app.domain.model.readaloud.ReadAloudPlaybackCue? = null

    /**
     * 解析这一朗读单元身上挂的音效串，取出 `(路径, 延迟千分位)`；同一个单元只给一次。
     *
     * 串格式：多条换行分隔、可带 `#千分位`（与生产方契约一致）；本函数是唯一解析端。
     *
     * 音效**不另起朗读单元**：一个单元就是一次串行的 TTS 合成请求，多一个单元多一次串行等待。
     * 因此千分位只能按命中字符在单元内的占比估算，不是精确时长。
     * 消费方：Http 路由子类 scheduleCueSounds（媒体时钟定位）；系统 TTS 直读走 [playCueSounds]。
     */
    protected fun takeCueSounds(index: Int): List<Pair<String, Int>> {
        val cue = playbackQueue.cues.getOrNull(index) ?: return emptyList()
        if (cue.soundEffect.isBlank() || cue === soundedCue) return emptyList()
        soundedCue = cue
        return cue.soundEffect
            .split("\n")
            .filter { it.isNotBlank() }
            .map { entry ->
                val at = entry.indexOf('#')
                val path = if (at > 0) entry.substring(0, at) else entry
                val permille = if (at > 0) entry.substring(at + 1).toIntOrNull() ?: 0 else 0
                path to permille
            }
    }

    /** 系统 TTS 直读那条路的音效：本移植版统一走自建声效引擎，此处不重复播。 */
    protected fun playCueSounds(index: Int) {
    }



    protected fun updateReadAloudProgressSnapshot(progress: Int) {
        currentChapterIndex = readerReadAloudChapter?.chapterIndex ?: currentChapterIndex
        val newProgress = progress.coerceAtLeast(0)
        if (newProgress < currentProgress) {
            // 进度回退(上一段/上一章等), 重置媒体进度锚点, 允许进度条跟随回退
            lastMediaSessionPositionMs = -1L

        }
        // 背景配乐轨跟着朗读位置走：标题段静音，正文段按场景换曲（第二条音轨，不抢焦点）。
        // 只能在进度前进时生效，所以不能塞进上面的回退分支里。
        currentProgress = newProgress
        bookSession.updateProgress(newProgress, expectedChapterIndex = currentChapterIndex)
    }

    protected fun followsReader(): Boolean =
        bookSession.owns(ReadBook.book?.bookUrl) && sessionStore.state.value.followReadAloudPosition

    protected fun syncReaderToPlaybackPage() {
        if (!followsReader()) return
        val chapter = readerReadAloudChapter ?: return
        val position = chapter.pageStart(pageIndex)
        if (ReadBook.durChapterIndex != chapter.chapterIndex) {
            withSpeechNavigation {
                ReadBook.openChapter(chapter.chapterIndex, position, preserveReadAloudPosition = true)
            }
        } else {
            ReadBook.syncReadAloudPage(chapter.chapterIndex, position)
        }
    }

    private fun switchChapter(index: Int, play: Boolean) {
        if (index < 0) return
        val bookUrl = bookSession.book?.bookUrl ?: return
        val generation = ++prepareReadAloudGeneration
        prepareReadAloudJob?.cancel()
        playStop()
        prepareReadAloudJob = execute(executeContext = IO) {
            io.legado.app.data.appDb.bookChapterDao.getChapter(bookUrl, index) != null
        }.onSuccess { exists ->
            if (generation != prepareReadAloudGeneration || bookSession.book?.bookUrl != bookUrl) return@onSuccess
            if (!exists) {
                stopReadAloudService()
            } else {
                bookSession.updateProgress(currentProgress, forceSave = true, expectedChapterIndex = currentChapterIndex)
                bookSession.selectChapter(index)
                // newReadAloud 开头会 cancel 这个 prepareReadAloudJob，而这段回调正跑在它里面：
                // 直接调用等于取消掉自己脚下这份作业，下一章一帧都不准备（切章后没声也不报错）。
                launch(Main) { newReadAloud(play, 0, 0, 0) }
            }
        }.onError {
            AppLog.put("加载朗读章节失败", it, true)
            pauseReadAloud()
        }
    }

    /**
     * 重跑本章准备（朗读列表还空着时的兜底），只重跑本会话这本书。
     *
     * 已经在准备中就不能再起一次：`play()` 会在 contentList 填好之前被调到，那时
     * pageIndex 与 currentProgress 都还是 0，重起会把「从当前页开始」冲成章首。
     */
    protected fun preparePlayback() {
        if (prepareReadAloudJob?.isActive == true) return
        newReadAloud(!pause, pageIndex, 0, currentProgress)
    }

    protected fun moveToReadAloudPage(chapterPosition: Int): Boolean {
        val chapter = readerReadAloudChapter ?: return false
        val targetPageIndex = findReadAloudPageIndex(
            currentPageIndex = pageIndex,
            chapterPosition = chapterPosition,
            pageCount = chapter.pageCount,
            pageStart = chapter::pageStart,
        )
        if (targetPageIndex == pageIndex) return false
        // 页面脱离朗读位置（用户手动翻页）后不再驱动可见页面，仅推进朗读内部页游标
        val follow = followsReader()
        repeat(targetPageIndex - pageIndex) {
            pageIndex++
            // 批215：段落代启页表与阅读器真实分页不同构——代启期间不驱动阅读器翻页（防多发错翻）；
            // 接轨升级后（syncReaderLayout 清标志）恢复正常。
            if (follow && !currentChapterProvisional) {
                withSpeechNavigation { ReadBook.moveToNextPage() }
            }
        }
        return true
    }



    private fun syncReaderLayout() {
        if (!bookSession.owns(ReadBook.book?.bookUrl)) return
        bookSession.syncReaderInput()
        val input = ReadBook.readerChapterInputWindow.current ?: return
        val serviceChapter = readerReadAloudChapter ?: return
        if (input.chapter.index != serviceChapter.chapterIndex) return
        val pagination = ReadBook.readerPagination(input.chapter.index) ?: return
        val latestChapter = ReaderReadAloudChapter.create(
            chapterIndex = input.chapter.index,
            title = input.displayTitle,
            semanticContent = input.source.semanticContent,
            pageStarts = pagination.pageStarts,
        )
        val latestPageIndex = latestChapter.pageIndexAt((currentProgress - 1).coerceAtLeast(0))
        readerReadAloudChapter = latestChapter
        pageIndex = latestPageIndex
        if (currentChapterProvisional) {
            currentChapterProvisional = false
            AppLog.putDebug("朗读页表接轨：真实分页已就绪 chapterIndex=${input.chapter.index}")
        }
        if (sessionStore.state.value.followReadAloudPosition) {
            ReadBook.syncReadAloudPage(
                chapterIndex = latestChapter.chapterIndex,
                chapterPos = latestChapter.pageStart(latestPageIndex),
            )
        }
        upTtsProgress(currentProgress)
    }

    private fun prevP() {
        bookSession.upReadTime()
        if (hasSpeechPlaybackQueue) {
            val current = playbackCursor ?: ReadAloudPlaybackCursor(nowSpeak, paragraphStartPos)
            playbackQueue.previous(current)?.let { previous ->
                playStop()
                moveToPlaybackCursor(previous)
                play()
            } ?: run {
                toLast = true
                switchChapter(bookSession.chapterIndex - 1, !pause)
            }
            return
        }
        if (nowSpeak > 0) {
            playStop()
            var foundPreviousReadableParagraph = false
            do {
                nowSpeak--
                readAloudNumber -= contentList[nowSpeak].length + 1 + paragraphStartPos
                paragraphStartPos = 0
                foundPreviousReadableParagraph =
                    !contentList[nowSpeak].matches(AppPattern.notReadAloudRegex)
            } while (!foundPreviousReadableParagraph && nowSpeak > 0)
            if (!foundPreviousReadableParagraph) {
                toLast = true
                switchChapter(bookSession.chapterIndex - 1, !pause)
                return
            }
            readerReadAloudChapter?.let {
                readAloudNumber = paragraphChapterPositionAt(nowSpeak) ?: 0
                if (readAloudNumber < it.pageStart(pageIndex)) {
                    pageIndex--
                    syncReaderToPlaybackPage()
                }
            }
            upTtsProgress(readAloudNumber + 1)
            upMediaMetadata(showContent = true)
            play()
        } else {
            toLast = true
            switchChapter(bookSession.chapterIndex - 1, !pause)
        }
    }

    private fun nextP() {
        if (hasSpeechPlaybackQueue) {
            val current = playbackCursor ?: ReadAloudPlaybackCursor(nowSpeak, paragraphStartPos)
            playbackQueue.next(current)?.let { next ->
                playStop()
                moveToPlaybackCursor(next)
                play()
            } ?: nextChapter()
            return
        }
        if (nowSpeak < contentList.size - 1) {
            playStop()
            readAloudNumber += contentList[nowSpeak].length.plus(1) - paragraphStartPos
            paragraphStartPos = 0
            nowSpeak++
            readerReadAloudChapter?.let {
                readAloudNumber = paragraphChapterPositionAt(nowSpeak) ?: 0
                if (pageIndex + 1 < it.pageCount
                    && readAloudNumber >= it.pageStart(pageIndex + 1)
                ) {
                    pageIndex++
                    syncReaderToPlaybackPage()
                }
            }
            upTtsProgress(readAloudNumber + 1)
            upMediaMetadata(showContent = true)
            play()
        } else {
            nextChapter()
        }
    }

    protected fun moveToPlaybackCursor(cursor: ReadAloudPlaybackCursor) {
        val cue = playbackQueue.cues[cursor.cueIndex]
        playbackCursor = cursor
        nowSpeak = cursor.cueIndex
        paragraphStartPos = cursor.offset
        readAloudNumber = cue.chapterStart
        publishPlaybackInfo(cursor)
        readerReadAloudChapter?.let { chapter ->
            val targetPosition = cue.chapterStart + cursor.offset
            val targetPage = chapter.pageIndexAt(targetPosition)
            while (pageIndex < targetPage) {
                pageIndex++
                syncReaderToPlaybackPage()
            }
            while (pageIndex > targetPage) {
                pageIndex--
                syncReaderToPlaybackPage()
            }
            if (cue.isChapterTitle) {
                updateReadAloudProgressSnapshot(0)
            } else {
                upTtsProgress(targetPosition + 1)
            }
        }
        upMediaMetadata(showContent = true)
    }

    private fun publishPlaybackInfo(cursor: ReadAloudPlaybackCursor) {
        val cue = playbackQueue.cues.getOrNull(cursor.cueIndex) ?: return
        sessionStore.updatePlayback(ReadAloudPlaybackInfo(
            chapterTitle = readerReadAloudChapter?.title.orEmpty(),
            chapterIndex = readerReadAloudChapter?.chapterIndex ?: -1,
            chapterPosition = cue.chapterStart + cursor.offset,
            chapterLength = playbackQueue.cues.lastOrNull()?.chapterEnd ?: cue.chapterEnd,
            text = cue.text,
            engineName = cue.voice?.displayName.orEmpty(),
            characterName = speechPlan.getOrNull(
                cursor.cueIndex - playbackQueue.leadingTitleCueCount
            )?.segment?.characterName.orEmpty(),
            roleType = cue.roleType,
        ))
        refreshMediaSessionPlaybackState()
    }

    /**
     * 按持久化设置恢复定时。
     *
     * 两种模式互斥：分钟模式起倒计时，章节模式只装剩余章数（到数后停在章末，由
     * [completeCurrentChapter] 消费），因此这里不需要清另一侧的臂标。
     */
    private fun applyPersistedTimer() {
        when (ReadAloudTimerMode.fromStorage(ReadConfig.readAloudTimerMode)) {
            ReadAloudTimerMode.Minute -> {
                val minutes = PlaybackTimer.normalize(ReadConfig.ttsTimer)
                timeMinute = minutes
                doDs()
                if (minutes > 0) {
                    toastOnUi(appCtx.getString(R.string.timer_m, minutes))
                }
            }

            ReadAloudTimerMode.Chapter -> {
                val chapters = PlaybackTimer.normalizeChapters(ReadConfig.readAloudTimerChapters)
                synchronized(finishChapterTimerLock) { chapterQuota = chapters.takeIf { it > 0 } }
                sessionStore.updateTimer(PlaybackTimer.MIN_MINUTES)
                if (chapters > 0) {
                    toastOnUi(appCtx.getString(R.string.timer_chapters, chapters))
                }
            }
        }
    }

    /**
     * 设置分钟倒计时。
     *
     * 只清章节配额（两种模式互斥），**不碰** [finishChapterAtIndex]：「读完本章再停」是
     * 分钟模式的修饰项，用户调分钟数不应顺手取消一个已经在等的章末停读。
     */
    private fun setTimer(minute: Int) {
        synchronized(finishChapterTimerLock) { chapterQuota = null }
        timeMinute = minute
        doDs()
    }

    private fun addTimer() {
        synchronized(finishChapterTimerLock) { chapterQuota = null }
        timeMinute = PlaybackTimer.addIncrement(timeMinute)
        doDs()
    }

    /**
     * 章节定时剩余章数。
     *
     * 只动配额、不清 [finishChapterAtIndex]：章节模式的语义是「再读 N 章就停」，
     * 换章不算取消。用户改用分钟模式时才交给 [setTimer] 一起清。
     */
    private fun setTimerChapters(chapters: Int) {
        synchronized(finishChapterTimerLock) {
            chapterQuota = PlaybackTimer.normalizeChapters(chapters).takeIf { it > 0 }
        }
    }

    /**
     * 分钟倒计时。
     *
     * 到点时若用户勾了「读完本章再停」，就给 [finishChapterAtIndex] 装臂标，由
     * [completeCurrentChapter] 在章末停；否则立刻停。章节模式是另一条独立路径
     * （[chapterQuota]），两者不会互相清除。
     */
    @Synchronized
    private fun doDs() {
        sessionStore.updateTimer(timeMinute)
        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
        upReadAloudNotification()
        dsJob?.cancel()
        dsJob = null
        if (timeMinute == PlaybackTimer.MIN_MINUTES) return
        dsJob = lifecycleScope.launch {
            while (isActive) {
                delay(60000)
                if (timeMinute == PlaybackTimer.MIN_MINUTES) break
                if (!pause) {
                    val finishChapter = synchronized(finishChapterTimerLock) {
                        timeMinute--
                        if (timeMinute == PlaybackTimer.MIN_MINUTES &&
                            ReadConfig.finishCurrentChapterAfterTimer
                        ) {
                            // 臂标锚定正在朗读的章节：脱离（手动翻到后章）时 durChapterIndex
                            // 已领先，"读完本章"指正在读的这章，不是页面停留的章节
                            finishChapterAtIndex = BaseReadAloudService.currentChapterIndex
                                .takeIf { it >= 0 }
                                ?: bookSession.chapterIndex
                            finishChapterAtIndex != NO_FINISH_CHAPTER
                        } else {
                            false
                        }
                    }
                    if (timeMinute == PlaybackTimer.MIN_MINUTES) {
                        if (!finishChapter) {
                            ReadAloud.stop(this@BaseReadAloudService)
                        }
                        sessionStore.updateTimer(timeMinute)
                        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                        upReadAloudNotification()
                        break
                    }
                }
                sessionStore.updateTimer(timeMinute)
                postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                upReadAloudNotification()
            }
        }
    }

    /**
     * 请求音频焦点
     * @return 音频焦点
     */
    fun requestFocus(): Boolean {
        if (ReadConfig.ignoreAudioFocus) {
            return true
        }
        val requestFocus = MediaHelp.requestFocus(mFocusRequest)
        if (!requestFocus) {
            pauseReadAloud(false)
            toastOnUi("未获取到音频焦点")
        }
        return requestFocus
    }

    /**
     * 放弃音频焦点
     */
    private fun abandonFocus() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, mFocusRequest)
    }

    /**
     * 更新媒体状态
     */
    private fun upMediaSessionPlaybackState(state: Int) {
        val now = SystemClock.elapsedRealtime()
        val position = nextMediaSessionPositionMs(
            state = state,
            lastState = lastMediaSessionState,
            estimate = mediaProgressPositionMs(),
            lastPosition = lastMediaSessionPositionMs,
            nowElapsedRealtime = now,
            lastUpdateElapsedRealtime = lastMediaSessionUpdateElapsedMs,
        )
        if (state == lastMediaSessionState && position == lastMediaSessionPositionMs) {
            return
        }
        lastMediaSessionState = state
        lastMediaSessionPositionMs = position
        lastMediaSessionUpdateElapsedMs = now
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                if (androidMediaControlEnabled) {
                    READ_ALOUD_MEDIA_SESSION_ACTIONS
                } else {
                    // 「使用媒体通道」这一路用原有按键集合，锁屏媒体控件行为不变。
                    MediaHelp.MEDIA_SESSION_ACTIONS
                }
            )
            // TTS 没有真实时间轴, 位置按字符进度与语速估算为毫秒时间
            .setState(
                state,
                position,
                if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f,
            )
        if (androidMediaControlEnabled) {
            playbackState.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    ACTION_ADD_TIMER,
                    getString(R.string.set_timer),
                    R.drawable.ic_time_add_24dp,
                ).build()
            )
        }
        mediaSessionCompat.setPlaybackState(playbackState.build())
    }

    private fun refreshMediaSessionPlaybackState() {
        upMediaSessionPlaybackState(
            if (pause) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING
        )
    }

    /**
     * 播放时每秒推送一次媒体进度, 系统媒体播放器进度条随朗读推进
     */
    private fun upMediaProgress() {
        upMediaProgressJob?.cancel()
        upMediaProgressJob = lifecycleScope.launch {
            while (isActive) {
                refreshMediaSessionPlaybackState()
                delay(1000)
            }
        }
    }

    private fun mediaProgressPositionMs(): Long {
        val chapterLength = currentChapterLength()
        val charsPerSecond = currentCharsPerSecond()
        if (chapterLength <= 0 || charsPerSecond <= 0f) return 0L
        return estimatedReadAloudTimeMs(
            currentProgress.coerceIn(0, chapterLength),
            charsPerSecond,
        )
    }

    /**
     * 当前章节按语速估算的朗读总时长, TTS 没有真实时间轴
     */
    private fun mediaProgressDurationMs(): Long {
        val chapterLength = currentChapterLength()
        val charsPerSecond = currentCharsPerSecond()
        if (chapterLength <= 0 || charsPerSecond <= 0f) return 0L
        return estimatedReadAloudTimeMs(chapterLength, charsPerSecond)
    }

    private fun currentChapterLength(): Int = readerReadAloudChapter?.chapterLength ?: 0

    private fun currentCharsPerSecond(): Float =
        (ESTIMATED_CHARS_PER_SECOND * currentSpeechRate).coerceAtLeast(0.1f)

    /** 在已准备好的本章队列中定位，保留暂停状态和章节定时，不重新分析或切换章节。 */
    internal fun seekToMediaPosition(positionMs: Long) {
        if (stopRequested) return
        val chapter = readerReadAloudChapter ?: return
        val target = resolveReadAloudMediaSeek(
            positionMs = positionMs,
            charsPerSecond = currentCharsPerSecond(),
            chapterLength = chapter.chapterLength,
            content = contentList,
            chapterPositions = contentChapterPositions,
        ) ?: return
        val wasPaused = pause
        ++prepareReadAloudGeneration
        prepareReadAloudJob?.cancel()
        playStop()
        onPlaybackStateReplaced()
        nowSpeak = target.paragraphIndex
        paragraphStartPos = target.offset
        readAloudNumber = target.chapterPosition - target.offset
        playbackCursor = if (hasSpeechPlaybackQueue) {
            ReadAloudPlaybackCursor(target.paragraphIndex, target.offset)
        } else {
            null
        }
        toLast = false
        pageIndex = chapter.pageIndexAt(target.chapterPosition)
        // 主动定位允许前进或后退；暂停时也必须舍弃原来冻结的媒体时间。
        lastMediaSessionPositionMs = -1L
        updateReadAloudProgressSnapshot(target.chapterPosition)
        upTtsProgress(target.chapterPosition)
        upMediaMetadata(showContent = true)
        if (followsReader() &&
            ReadBook.durChapterIndex == chapter.chapterIndex
        ) {
            ReadBook.syncReadAloudPage(chapter.chapterIndex, chapter.pageStart(pageIndex))
        }
        if (wasPaused) {
            // HTTP 引擎恢复时重新生成目标段音频，不能恢复已作废的播放器队列。
            pageChanged = true
            upReadAloudNotification()
        } else {
            play()
        }
    }

    /**
     * 更新媒体元数据, 用于车机蓝牙显示
     * @param showContent 是否显示当前朗读内容作为歌词
     */
    internal fun upMediaMetadata(showContent: Boolean = false) {
        val currentContent = if (showContent && nowSpeak in contentList.indices) {
            contentList[nowSpeak]
        } else {
            null
        }
        val metadata = MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover)
            .putText(MediaMetadataCompat.METADATA_KEY_TITLE, bookSession.book?.name ?: "")
            .putText(MediaMetadataCompat.METADATA_KEY_ARTIST, readerReadAloudChapter?.title ?: "")
            .putText(MediaMetadataCompat.METADATA_KEY_ALBUM, bookSession.book?.author ?: "")
            .putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, currentContent ?: "")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, mediaProgressDurationMs())
            .build()
        mediaSessionCompat.setMetadata(metadata)
    }

    /**
     * 初始化MediaSession, 注册多媒体按钮
     */
    @SuppressLint("UnspecifiedImmutableFlag")
    private fun initMediaSession() {
        mediaSessionCompat.setSessionActivity(readAloudMediaControlActivityPendingIntent())
        mediaSessionCompat.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                resumeReadAloud()
            }

            override fun onPause() {
                pauseReadAloud()
            }

            override fun onSkipToNext() {
                if (ReadConfig.mediaButtonPerNext) {
                    nextChapter()
                } else {
                    nextP()
                }
            }

            override fun onSkipToPrevious() {
                if (ReadConfig.mediaButtonPerNext) {
                    prevChapter()
                } else {
                    prevP()
                }
            }

            override fun onStop() {
                stopReadAloudService()
            }

            override fun onSeekTo(pos: Long) {
                if (androidMediaControlEnabled) seekToMediaPosition(pos)
            }

            override fun onCustomAction(action: String, extras: Bundle?) {
                if (action == ACTION_ADD_TIMER) addTimer()
            }

            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                return MediaButtonReceiver.handleIntent(
                    this@BaseReadAloudService, mediaButtonEvent
                )
            }
        })
        updateMediaSessionActivation()
    }

    private fun observeMediaControlSettings() {
        lifecycleScope.launch {
            AppConfigStore.observeBoolean(PreferKey.readAloudAndroidMediaControl).collect {
                setAndroidMediaControlEnabled(it == true)
            }
        }
        lifecycleScope.launch {
            AppConfigStore.observeBoolean(
                PreferKey.systemMediaControlCompatibilityChange
            ).collect {
                setSystemMediaCompatibilityEnabled(it ?: true)
            }
        }
        lifecycleScope.launch {
            AppConfigStore.observeBoolean(PreferKey.mediaButtonPerNext)
                .drop(1)
                .collect { upReadAloudNotification() }
        }
    }

    private fun setAndroidMediaControlEnabled(enabled: Boolean) {
        val changed = androidMediaControlEnabled != enabled
        androidMediaControlEnabled = enabled
        updateMediaSessionActivation()
        if (enabled) {
            upMediaMetadata()
        }
        // 强制重新推送, 让新的媒体按键集合生效
        lastMediaSessionState = PlaybackStateCompat.STATE_NONE
        refreshMediaSessionPlaybackState()
        if (changed || enabled) {
            upReadAloudNotification()
        }
    }

    private fun setSystemMediaCompatibilityEnabled(enabled: Boolean) {
        if (systemMediaCompatibilityEnabled == enabled) {
            updateMediaSessionActivation()
            return
        }
        systemMediaCompatibilityEnabled = enabled
        updateMediaSessionActivation()
        refreshMediaSessionPlaybackState()
        upReadAloudNotification()
    }

    private fun updateMediaSessionActivation() {
        mediaSessionCompat.isActive =
            systemMediaCompatibilityEnabled || androidMediaControlEnabled
    }

    /**
     * 注册多媒体按钮监听
     */
    private fun initBroadcastReceiver() {
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(broadcastReceiver, intentFilter)
    }

    /**
     * 音频焦点变化
     */
    override fun onAudioFocusChange(focusChange: Int) {
        if (ReadConfig.ignoreAudioFocus) {
            AppLog.put("忽略音频焦点处理(TTS)")
            return
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (needResumeOnAudioFocusGain) {
                    AppLog.put("音频焦点获得,继续朗读")
                    resumeReadAloud()
                } else {
                    AppLog.put("音频焦点获得")
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                AppLog.put("音频焦点丢失,暂停朗读")
                pauseReadAloud()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                AppLog.put("音频焦点暂时丢失并会很快再次获得,暂停朗读")
                if (!pause) {
                    needResumeOnAudioFocusGain = true
                    pauseReadAloud(false)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 短暂丢失焦点，这种情况是被其他应用申请了短暂的焦点希望其他声音能压低音量（或者关闭声音）凸显这个声音（比如短信提示音），
                AppLog.put("音频焦点短暂丢失,不做处理")
            }
        }
    }

    private fun upReadAloudNotification() {
        upNotificationJob = lifecycleScope.launch(Main.immediate) {
            try {
                val notification = createForegroundNotification()
                notificationManager.notify(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
            }
        }
    }

    private fun choiceMediaStyle(): androidx.media.app.NotificationCompat.MediaStyle {
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(1, 2, 4)
        if (systemMediaCompatibilityEnabled && !androidMediaControlEnabled) {
            mediaStyle.setMediaSession(mediaSessionCompat.sessionToken)
        }
        return mediaStyle
    }

    private fun createNotification(): NotificationCompat.Builder {
        var nTitle: String = when {
            standbyArmed -> "朗读待命"
            pause -> getString(R.string.read_aloud_pause)
            timeMinute > 0 -> getString(
                R.string.read_aloud_timer,
                timeMinute
            )

            else -> getString(R.string.read_aloud_t)
        }
        nTitle += ": ${bookSession.book?.name}"
        var nSubtitle = readerReadAloudChapter?.title
        if (nSubtitle.isNullOrBlank())
            nSubtitle = getString(R.string.read_aloud_s)
        val builder = NotificationCompat
            .Builder(this, AppConst.channelIdReadAloud)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setSubText(getString(R.string.read_aloud))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(nTitle)
            .setContentText(nSubtitle)
            .setContentIntent(readAloudActivityPendingIntent())
            .setVibrate(null)
            .setSound(null)
            .setLights(0, 0, 0)
        builder.setLargeIcon(cover)
        // 按钮定义：上一章、播放、停止、下一章、定时
        builder.addAction(
            R.drawable.ic_skip_previous,
            getString(R.string.previous_chapter),
            aloudServicePendingIntent(IntentAction.prev)
        )
        if (pause) {
            builder.addAction(
                R.drawable.ic_play,
                getString(R.string.resume),
                aloudServicePendingIntent(IntentAction.resume)
            )
        } else {
            builder.addAction(
                R.drawable.ic_pause,
                getString(R.string.pause),
                aloudServicePendingIntent(IntentAction.pause)
            )
        }
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.stop),
            aloudServicePendingIntent(IntentAction.stop)
        )
        builder.addAction(
            R.drawable.ic_skip_next,
            getString(R.string.next_chapter),
            aloudServicePendingIntent(IntentAction.next)
        )
        builder.addAction(
            R.drawable.ic_time_add_24dp,
            getString(R.string.set_timer),
            aloudServicePendingIntent(IntentAction.addTimer)
        )
        builder.setStyle(choiceMediaStyle())
        return builder
    }

    private fun createForegroundNotification(): NotificationCompat.Builder =
        if (androidMediaControlEnabled) {
            createAndroidMediaControlNotification()
        } else {
            createNotification()
        }

    private fun createAndroidMediaControlNotification(): NotificationCompat.Builder {
        val navigateByChapter = ReadConfig.mediaButtonPerNext
        val previousAction = if (navigateByChapter) {
            IntentAction.prev
        } else {
            IntentAction.prevParagraph
        }
        val nextAction = if (navigateByChapter) IntentAction.next else IntentAction.nextParagraph
        val previousLabel = getString(
            if (navigateByChapter) R.string.previous_chapter else R.string.prev_sentence
        )
        val nextLabel = getString(
            if (navigateByChapter) R.string.next_chapter else R.string.next_sentence
        )
        val chapterTitle = readerReadAloudChapter?.title
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.read_aloud_s)
        return NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setSubText(bookSession.book?.author ?: getString(R.string.read_aloud))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(bookSession.book?.name ?: getString(R.string.read_aloud))
            .setContentText(chapterTitle)
            .setContentIntent(readAloudMediaControlActivityPendingIntent())
            .setLargeIcon(cover)
            .setVibrate(null)
            .setSound(null)
            .setLights(0, 0, 0)
            .apply {
                if (!resources.configuration.isNightMode) {
                    // Some OEM media controls derive a white progress tint from light artwork.
                    // Keep the system surface and provide a contrasting accent in light mode.
                    setColor(Color.BLACK)
                    setColorized(false)
                }
            }
            .addAction(
                R.drawable.ic_skip_previous,
                previousLabel,
                aloudServicePendingIntent(previousAction),
            )
            .addAction(
                if (pause) R.drawable.ic_play else R.drawable.ic_pause,
                getString(if (pause) R.string.resume else R.string.pause),
                aloudServicePendingIntent(
                    if (pause) IntentAction.resume else IntentAction.pause
                ),
            )
            .addAction(
                R.drawable.ic_skip_next,
                nextLabel,
                aloudServicePendingIntent(nextAction),
            )
            .addAction(
                R.drawable.ic_time_add_24dp,
                getString(R.string.set_timer),
                aloudServicePendingIntent(IntentAction.addTimer),
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSessionCompat.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
    }

    private fun readAloudActivityPendingIntent(): PendingIntent? = activityPendingIntent(
        // 跨文件依赖：MainActivity 在 Manifest 里没有 launchMode（即 standard）。不带这两个 flag 时，
        // 每次从通知点回来都是再开一个 MainActivity，阅读页随之叠加，返回要按同样次数才回得到书架。
        MainActivity.createReadBookIntent(this, bookUrl = bookSession.book?.bookUrl, readAloud = true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),        "activity",
    )

    private fun readAloudMediaControlActivityPendingIntent(): PendingIntent? = activityPendingIntent(
        MainActivity.createReadBookMediaControlIntent(this),
        ACTION_OPEN_MEDIA_CONTROL_READER,
    )

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        try {
            val notification = createForegroundNotification()
            startForeground(NotificationId.ReadAloudService, notification.build())
        } catch (e: Exception) {
            AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
            //创建通知出错不结束服务就会崩溃,服务必须绑定通知
            stopReadAloudService()
        }
    }

    abstract fun aloudServicePendingIntent(actionStr: String): PendingIntent?

    /** 通知分析调度：朗读跨章（触点2） */
    private fun notifyAnalysisChapterChanged() {
        runCatching {
            val scheduler: AnalysisSchedulerV3 = get(AnalysisSchedulerV3::class.java)
            val bookUrl: String = ReadBook.book?.bookUrl.orEmpty()
            if (bookUrl.isNotEmpty()) {
                scheduler.onChapterChanged(bookUrl, ReadBook.durChapterIndex)
            }
        }.onFailure {
            AppLog.putAnalysis("分析调度换章通知失败: ${it.localizedMessage}", it)
        }
    }

    open fun prevChapter() {
        // 只清分钟模式装的章末臂标；章节配额的计数跨章保留，否则「读 N 章」永远数不满
        clearFinishChapterFlag()
        bookSession.upReadTime()
        toLast = false
        resumeReadAloudInternal()
        withSpeechNavigation { ReadBook.moveToPrevChapter(true, toLast = false) }
        notifyAnalysisChapterChanged()
    }

    open fun nextChapter() {
        clearFinishChapterFlag()
        bookSession.upReadTime()
        AppLog.putDebug("${readerReadAloudChapter?.title} 朗读结束跳转下一章并朗读")
        resumeReadAloudInternal()
        if (!withSpeechNavigation { ReadBook.moveToNextChapter(true) }) {
            stopReadAloudService()
        } else {
            notifyAnalysisChapterChanged()
        }
    }

    /** Handles a playback engine's natural chapter boundary atomically with timer expiry. */
    protected fun completeCurrentChapter() {
        synchronized(finishChapterTimerLock) {
            val chapterIndex = readerReadAloudChapter?.chapterIndex ?: currentChapterIndex
            val decision = decideChapterCompletion(
                durChapterIndex = bookSession.chapterIndex,
                finishedChapterIndex = chapterIndex,
                finishChapterAtIndex = finishChapterAtIndex,
                chapterQuota = chapterQuota,
            )
            if (decision.clearTimer) {
                finishChapterAtIndex = NO_FINISH_CHAPTER
            }
            if (decision.remainingChapters != null) {
                chapterQuota = decision.remainingChapters
            }
            when (decision.action) {
                ChapterCompletionAction.STOP -> {
                    // 先标记停止，避免提交末页触发重排时重新启动朗读。章末不是最后一次
                    // TTS/音频进度回调的位置：引擎可能只上报段首，或已把下一段游标归零。
                    requestStop()
                    if (bookSession.chapterIndex == chapterIndex) {
                        val end = ((readerReadAloudChapter?.chapterLength ?: 1) - 1).coerceAtLeast(0)
                        currentProgress = end
                        bookSession.updateProgress(end, forceSave = true)
                    }
                    if (followsReader()) {
                        readerReadAloudChapter?.let { chapter ->
                            ReadBook.syncReadAloudPage(
                                chapterIndex = chapter.chapterIndex,
                                chapterPos = (chapter.chapterLength - 1).coerceAtLeast(0),
                            )
                        }
                    }
                    stopSelf()
                }

                ChapterCompletionAction.ADVANCE -> {
                    // synchronized is reentrant, so nextChapter() may clear the same state safely.
                    nextChapter()
                }

                ChapterCompletionAction.SKIP -> Unit
            }
        }
    }

    /** 清掉分钟模式装的章末臂标；章节配额不动。 */
    private fun clearFinishChapterFlag() {
        synchronized(finishChapterTimerLock) {
            finishChapterAtIndex = NO_FINISH_CHAPTER
        }
    }

    /** 两种定时一起清：取消定时或切换模式时用。 */
    private fun clearFinishChapterTimer() {
        synchronized(finishChapterTimerLock) {
            finishChapterAtIndex = NO_FINISH_CHAPTER
            chapterQuota = null
        }
    }

    private fun clearFinishChapterTimerIfChapterChanged(chapterIndex: Int) {
        synchronized(finishChapterTimerLock) {
            if (finishChapterAtIndex != NO_FINISH_CHAPTER &&
                finishChapterAtIndex != chapterIndex
            ) {
                finishChapterAtIndex = NO_FINISH_CHAPTER
            }
        }
    }

    private fun initPhoneStateListener() {
        val needRegister = ReadConfig.ignoreAudioFocus && ReadConfig.pauseReadAloudWhilePhoneCalls
        if (needRegister && registeredPhoneStateListener) {
            return
        }
        if (needRegister) {
            registerPhoneStateListener(phoneStateListener)
        } else {
            unregisterPhoneStateListener(phoneStateListener)
        }
    }

    private fun unregisterPhoneStateListener(l: PhoneStateListener) {
        if (registeredPhoneStateListener) {
            withReadPhoneStatePermission {
                telephonyManager.listen(l, PhoneStateListener.LISTEN_NONE)
                registeredPhoneStateListener = false
            }
        }
    }

    private fun registerPhoneStateListener(l: PhoneStateListener) {
        withReadPhoneStatePermission {
            telephonyManager.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            registeredPhoneStateListener = true
        }
    }

    private fun withReadPhoneStatePermission(block: () -> Unit) {
        try {
            block.invoke()
        } catch (_: SecurityException) {
            PermissionsCompat.Builder()
                .addPermissions(Permissions.READ_PHONE_STATE)
                .rationale(R.string.read_aloud_read_phone_state_permission_rationale)
                .onGranted {
                    try {
                        block.invoke()
                    } catch (_: SecurityException) {
                        LogUtils.d(TAG, "Grant read phone state permission fail.")
                    }
                }
                .request()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    inner class ReadAloudPhoneStateListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            when (state) {
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (needResumeOnCallStateIdle) {
                        AppLog.put("来电结束,继续朗读")
                        resumeReadAloud()
                    } else {
                        AppLog.put("来电结束")
                    }
                }

                TelephonyManager.CALL_STATE_RINGING -> {
                    if (!pause) {
                        AppLog.put("来电响铃,暂停朗读")
                        needResumeOnCallStateIdle = true
                        pauseReadAloud()
                    } else {
                        AppLog.put("来电响铃")
                    }
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    AppLog.put("来电接听,不做处理")
                }
            }
        }
    }

}

/**
 * 把已朗读字符数按每秒朗读字数估算为媒体播放器时间轴毫秒值
 */
internal fun estimatedReadAloudTimeMs(
    chars: Int,
    charsPerSecond: Float,
): Long {
    if (chars <= 0 || charsPerSecond <= 0f) return 0L
    return (chars * 1000.0 / charsPerSecond).toLong()
}

internal data class ReadAloudMediaSeekPosition(
    val paragraphIndex: Int,
    val offset: Int,
    val chapterPosition: Int,
)

/** 时间轴只对应正文；换行间隙落到下一段，章末落到最后一个字符，避免提交空发言。 */
internal fun resolveReadAloudMediaSeek(
    positionMs: Long,
    charsPerSecond: Float,
    chapterLength: Int,
    content: List<String>,
    chapterPositions: List<Int?>,
): ReadAloudMediaSeekPosition? {
    if (chapterLength <= 0 || !charsPerSecond.isFinite() || charsPerSecond <= 0f) return null
    val position = (positionMs.coerceAtLeast(0) * charsPerSecond.toDouble() / 1000.0)
        .coerceAtMost((chapterLength - 1).toDouble()).toInt()
    var lastBodyIndex = -1
    for (index in content.indices) {
        val start = chapterPositions.getOrNull(index) ?: continue
        if (content[index].isEmpty()) continue
        lastBodyIndex = index
        if (start + content[index].length > position) break
    }
    if (lastBodyIndex < 0) return null
    val text = content[lastBodyIndex]
    val start = chapterPositions[lastBodyIndex] ?: return null
    var offset = (position - start).coerceIn(0, text.lastIndex)
    // 媒体毫秒值可以落在 UTF-16 代理项中间，保留完整字符供引擎合成。
    if (offset > 0 && text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate()) {
        offset--
    }
    return ReadAloudMediaSeekPosition(lastBodyIndex, offset, start + offset)
}

/**
 * 计算推送给系统媒体播放器的进度位置。
 * 播放中保持单调不后退: 字符估算值领先时跟随估算值, 落后时按墙钟推进,
 * 避免每秒重复推送同一估算值导致进度条来回跳变;
 * 暂停时冻结在系统插值的显示位置, 恢复时从冻结位置继续, 不跳变。
 */
internal fun nextMediaSessionPositionMs(
    state: Int,
    lastState: Int,
    estimate: Long,
    lastPosition: Long,
    nowElapsedRealtime: Long,
    lastUpdateElapsedRealtime: Long,
): Long {
    val playing = PlaybackStateCompat.STATE_PLAYING
    val paused = PlaybackStateCompat.STATE_PAUSED
    return when {
        state == paused && lastState == playing ->
            if (lastPosition < 0) estimate
            else lastPosition + (nowElapsedRealtime - lastUpdateElapsedRealtime)

        state == paused -> if (lastPosition < 0) estimate else lastPosition

        state == playing && lastState == paused ->
            if (lastPosition < 0) estimate else lastPosition

        state == playing ->
            if (lastPosition < 0) estimate
            else maxOf(estimate, lastPosition + (nowElapsedRealtime - lastUpdateElapsedRealtime))

        else -> estimate
    }
}

/** Sentinel for "finish current chapter" timer not being armed. */
internal const val NO_FINISH_CHAPTER = -1

internal enum class ChapterCompletionAction {
    /** 定时到点 / 章节配额读完 —— 停在当前章末。 */
    STOP,

    /** 没有停读意图 —— 继续下一章。 */
    ADVANCE,

    /** 章节已经并发推进 —— 什么都不做。 */
    SKIP,
}

internal data class ChapterCompletionDecision(
    val action: ChapterCompletionAction,
    val clearTimer: Boolean,
    /** 非 null 时需要写回的新剩余章数（已扣掉刚读完的这一章）。 */
    val remainingChapters: Int? = null,
)

/**
 * Decides what a natural chapter boundary should do against the timer state. Pure and
 * thread-free so it can be unit tested.
 *
 * 两个来源：
 * - `finishChapterAtIndex`：锚定的章节读完了（分钟定时到点时装的臂标）；
 * - `chapterQuota`：章节定时的剩余章数，每读完一章扣一，扣到 0 时停。
 *
 * SKIP 只保留给"章已推进且臂标不属于已读完章节"的双重触发竞态（臂标消费后为
 * NO_FINISH，自然落入该分支）。臂标锚定的章节自然读完时必须 STOP：
 * 脱离浏览（durChapterIndex 领先于朗读章节）不算章节已推进。
 */
internal fun decideChapterCompletion(
    durChapterIndex: Int,
    finishedChapterIndex: Int,
    finishChapterAtIndex: Int,
    chapterQuota: Int?,
): ChapterCompletionDecision {
    if (durChapterIndex != finishedChapterIndex &&
        finishChapterAtIndex != finishedChapterIndex
    ) {
        // The chapter already advanced concurrently (race) — leave any foreign arm alone.
        return ChapterCompletionDecision(
            action = ChapterCompletionAction.SKIP,
            clearTimer = false,
        )
    }
    val remaining = chapterQuota?.let { PlaybackTimer.consumeChapter(it) }
    return when {
        remaining == 0 -> ChapterCompletionDecision(
            action = ChapterCompletionAction.STOP,
            clearTimer = true,
            remainingChapters = remaining,
        )

        finishChapterAtIndex == NO_FINISH_CHAPTER -> ChapterCompletionDecision(
            action = ChapterCompletionAction.ADVANCE,
            clearTimer = false,
            remainingChapters = remaining,
        )

        finishChapterAtIndex != finishedChapterIndex -> ChapterCompletionDecision(
            action = ChapterCompletionAction.ADVANCE,
            clearTimer = true,
            remainingChapters = remaining,
        )

        else -> ChapterCompletionDecision(
            action = ChapterCompletionAction.STOP,
            clearTimer = true,
            remainingChapters = remaining,
        )
    }
}

internal inline fun findReadAloudPageIndex(
    currentPageIndex: Int,
    chapterPosition: Int,
    pageCount: Int,
    pageStart: (Int) -> Int,
): Int {
    var targetPageIndex = currentPageIndex
    while (
        targetPageIndex + 1 < pageCount &&
        chapterPosition > pageStart(targetPageIndex + 1)
    ) {
        targetPageIndex++
    }
    return targetPageIndex
}
