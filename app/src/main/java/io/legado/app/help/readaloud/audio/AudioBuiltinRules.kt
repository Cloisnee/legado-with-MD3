package io.legado.app.help.readaloud.audio

import android.content.Context
import com.github.jing332.compat.fs.TtsDirProvider
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.Collections

/**
 * B33.4a · 内置规则包（mingwuyan/Z·阅读 规则 + 环境/BGM 词典）：
 *
 * - 资源（APK assets）：`audio_rules/mingwuyan_rules.json`（音效，842条/826标签）、
 *   `audio_rules/env_dict.json`、`audio_rules/bgm_dict.json`（环境/BGM 词典，正则写法）；
 * - 匹配：规则多且正则重 → **关键词预筛**（AhoCorasick 选候选）→ 仅对候选跑正则；
 * - 提供 label→合并正则（`(?:p1)|(?:p2)`），供「命中即回填」写入条目「匹配规则」；
 * - 优先级（引擎侧）：条目规则 > 本内置包 > CNB 意图（示例规则已退役）。
 */
object AudioBuiltinRules {

    class Rule(
        val label: String,
        val pattern: String,
        val regex: Regex,
        val keywords: List<String>,
    )

    class Pack internal constructor(
        val name: String,
        val rules: List<Rule>,
        private val alwaysIndices: IntArray,
    ) {
        internal var matcher: AhoCorasick? = null
        internal var kwToRules: Map<Int, IntArray> = emptyMap()

        /** label → 合并正则（回填用） */
        val merged: Map<String, String> by lazy {
            rules.groupBy { it.label }.mapValues { (_, rs) ->
                if (rs.size == 1) rs[0].pattern else rs.joinToString("|") { "(?:${it.pattern})" }
            }
        }

        /** 一行 → 首个命中规则（关键词候选 + 常跑位，按规则顺序） */
        fun hit(text: String): Rule? {
            if (rules.isEmpty()) return null
            if (matcher == null && alwaysIndices.isEmpty()) return null
            val cand = java.util.TreeSet<Int>()
            matcher?.matchAll(text)?.forEach { m ->
                kwToRules[m.patternIndex]?.forEach { cand.add(it) }
            }
            alwaysIndices.forEach { cand.add(it) }
            for (i in cand) {
                val r = rules.getOrNull(i) ?: continue
                if (r.regex.containsMatchIn(text)) return r
            }
            return null
        }
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    @Volatile private var mingwuyan: Pack? = null

    @Volatile private var env: Pack? = null

    @Volatile private var bgm: Pack? = null

    private val backfilled = Collections.synchronizedSet(HashSet<String>())

    private val cjkRuns = Regex("[\\u4e00-\\u9fff]{2,}")

    /** 非阻塞预热（引擎创建时调用） */
    fun warmUp(context: Context) {
        if (mingwuyan != null) return
        ioScope.launch { runCatching { ensureLoaded(context.applicationContext) } }
    }

    /** 加载全部内置包（幂等；预合成扫描前确保就绪） */
    suspend fun ensureLoaded(context: Context) {
        if (mingwuyan != null && env != null && bgm != null) return
        lock.withLock {
            if (mingwuyan == null) {
                mingwuyan = loadAsset(context, "audio_rules/mingwuyan_rules.json", "mingwuyan音效")
            }
            if (env == null) {
                env = loadAsset(context, "audio_rules/env_dict.json", "环境词典")
            }
            if (bgm == null) {
                bgm = loadAsset(context, "audio_rules/bgm_dict.json", "BGM词典")
            }
        }
        // 18+ 规则（受开关；网络失败静默回退缓存）
        runCatching { ensureAdult(context) }
    }

    /** 引擎用：按轨命中（未加载/无命中返回 null；解析素材由引擎完成） */
    fun hit(lane: DemoLanes.Lane, text: String): Rule? = when (lane) {
        DemoLanes.Lane.SFX -> mingwuyan?.hit(text)
            ?: adult?.takeIf { adultEnabled() }?.hit(text)
        DemoLanes.Lane.AMBIENCE -> env?.hit(text)
        DemoLanes.Lane.BGM -> bgm?.hit(text)
    }

    /** 「命中即回填」：把内置规则的合并正则写入条目「匹配规则」（仅当条目未写规则） */
    fun tryBackfill(context: Context, lane: DemoLanes.Lane, label: String, asset: AudioLibrary.AudioAsset) {
        if (asset.pattern.isNotBlank()) return
        val merged = mergedPattern(lane, label) ?: return
        if (!backfilled.add("${lane.name}|${asset.id}")) return
        val appCtx = context.applicationContext
        ioScope.launch {
            runCatching {
                AudioLibrary.updateAsset(
                    appCtx,
                    asset.copy(
                        pattern = merged,
                        isRegex = true,
                        scopeTitle = false,
                        scopeContent = true,
                    ),
                )
            }
        }
    }

    fun mergedPattern(lane: DemoLanes.Lane, label: String): String? = when (lane) {
        DemoLanes.Lane.SFX -> mingwuyan?.merged?.get(label)
        DemoLanes.Lane.AMBIENCE -> env?.merged?.get(label)
        DemoLanes.Lane.BGM -> bgm?.merged?.get(label)
    }

    // ------------------------------------------------------------ 加载与预筛构建

    private fun loadAsset(context: Context, assetPath: String, name: String): Pack? = runCatching {
        val text = context.assets.open(assetPath).use { it.readBytes().decodeToString() }
        buildPack(name, text)
    }.getOrNull()

    /** 解析 {rules:[{label,pattern}]} 并编译为 Pack（关键词预筛 + 正则） */
    private fun buildPack(name: String, text: String): Pack? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val arr = root.optJSONArray("rules") ?: return null
        val rules = ArrayList<Rule>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val label = o.optString("label").trim()
            val pattern = o.optString("pattern")
            if (label.isEmpty() || pattern.isEmpty()) continue
            val regex = runCatching { Regex(pattern) }.getOrNull() ?: continue
            rules.add(Rule(label, pattern, regex, extractKeywords(pattern)))
        }
        if (rules.isEmpty()) return null
        // 关键词 → 规则索引（全局去重）
        val kwIndex = HashMap<String, Int>()
        val kws = ArrayList<String>()
        val kwToRules = HashMap<Int, MutableList<Int>>()
        val always = ArrayList<Int>()
        rules.forEachIndexed { idx, r ->
            if (r.keywords.isEmpty()) {
                always.add(idx)
                return@forEachIndexed
            }
            for (k in r.keywords) {
                val ki = kwIndex.getOrPut(k) { kws.add(k); kws.size - 1 }
                kwToRules.getOrPut(ki) { mutableListOf() }.add(idx)
            }
        }
        val pack = Pack(name, rules, always.toIntArray())
        if (kws.isNotEmpty()) {
            runCatching {
                pack.matcher = AhoCorasick(kws)
                pack.kwToRules = kwToRules.mapValues { (_, v) -> v.distinct().toIntArray() }
            }
        }
        AppLog.putAudio(
            "【音效与背景音】内置规则：$name ${rules.size} 条 / ${pack.merged.size} 标签" +
                "（预筛词 ${kws.size}，常跑 ${always.size}）"
        )
        return pack
    }

    // ------------------------------------------------------------ 18+ 远程规则（随包拉取，受开关）

    @Volatile
    private var adult: Pack? = null

    private val http by lazy {
        okhttp3.OkHttpClient.Builder()
            .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun adultEnabled(): Boolean =
        runCatching { AppConfigStore.getBoolean(PreferKey.audioAdultEnabled) == true }.getOrDefault(false)

    /** 加载 18+ 规则（仅开关开启时；优先本地缓存，失败回退缓存） */
    suspend fun ensureAdult(context: Context) {
        if (adult != null || !adultEnabled()) return
        runCatching {
            val cache = java.io.File(
                TtsDirProvider.baseDir(context), "_store/audio_rules/mingwuyan_adult_rules.json"
            )
            val url = runCatching {
                AudioRemoteCatalog.manifest(context).firstOrNull { it.id == "mingwuyan_adult" }?.rulesUrl
            }.getOrNull().orEmpty()
            var text: String? = null
            if (url.isNotBlank()) {
                text = runCatching {
                    val req = okhttp3.Request.Builder().url(url).build()
                    http.newCall(req).execute().use { r -> r.body?.string() }
                }.getOrNull()
                if (!text.isNullOrBlank()) {
                    cache.parentFile?.mkdirs()
                    runCatching { cache.writeText(text!!) }
                }
            }
            if (text.isNullOrBlank() && cache.isFile) text = cache.readText()
            if (!text.isNullOrBlank()) {
                adult = buildPack("mingwuyan18+", text!!)
            }
        }
    }

    private fun extractKeywords(pattern: String): List<String> =
        cjkRuns.findAll(pattern).map { it.value }.distinct().toList()
}