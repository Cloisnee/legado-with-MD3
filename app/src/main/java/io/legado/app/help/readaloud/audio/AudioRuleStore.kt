package io.legado.app.help.readaloud.audio

import android.content.Context
import com.github.jing332.compat.fs.TtsDirProvider
import io.legado.app.constant.AppLog
import io.legado.app.help.http.await
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * B33.3d · CNB 音效规则数据接入（意图 / 别名 / 声音表 / 过滤）。
 *
 * - 数据源：自家 fork 优先、失败回退上游（`jread_audio_normalized/registry/…`）；
 * - 缓存：`_store/audio_rules/`（7 天保鲜；网络失败保留旧缓存）；
 * - 结构：audio_intent（2219 意图·anchors） / audio_director_sound_map（意图→声音） /
 *   sound_library（3514 声音名与别名） / alias_rules（7269 别名→声音） / tag_filters（默认关闭分类）。
 */
object AudioRuleStore {

    // ------------------------------------------------------------ 模型

    data class Intent(
        val id: String,
        val type: String,
        val subType: String,
        val anchors: List<String>,
        val priority: Int,
        val volume: Float,
        val timing: String,
        val runtimeEnabled: Boolean,
    )

    data class SoundMeta(
        val soundId: String,
        /** 主名称（legacyName） */
        val name: String,
        /** 主名 + legacyNames + aliases（解析候选） */
        val names: List<String>,
    )

    class RuleData(
        val intents: List<Intent>,
        val soundIdsByIntent: Map<String, List<String>>,
        val soundById: Map<String, SoundMeta>,
        val soundIdByAlias: Map<String, String>,
        /** 默认关闭分类（18+ 等）涉及的声音 id，匹配时排除 */
        val excludedSoundIds: Set<String>,
        /** 模式下标 → intents 下标（Aho-Corasick 输出映射） */
        val intentIndexByPattern: IntArray,
        val matcher: AhoCorasick,
        val origin: String,
    ) {
        val intentCount: Int get() = intents.size

        fun soundIdOfAlias(alias: String): String? =
            soundIdByAlias[alias.trim().lowercase()]

        fun namesOfSound(soundId: String): List<String> =
            soundById[soundId]?.names.orEmpty()

        /** 名称/别名 → soundId（惰性反查表；扫描与回填用） */
        fun soundIdByName(name: String): String {
            val idx = nameIndex ?: buildMap {
                soundById.values.forEach { m -> m.names.forEach { n -> putIfAbsent(n, m.soundId) } }
            }.also { nameIndex = it }
            return idx[name.trim()].orEmpty()
        }

        private var nameIndex: Map<String, String>? = null
    }

    // ------------------------------------------------------------ 常量 / 状态

    private const val REFRESH_MS = 7L * 24 * 3600 * 1000
    private const val RETRY_GATE_MS = 5L * 60 * 1000

    private const val FILE_INTENT = "audio_intent.json"
    private const val FILE_ALIAS = "alias_rules.json"
    private const val FILE_MAP = "audio_director_sound_map.json"
    private const val FILE_SOUNDS = "sound_library.json"
    private const val FILE_TAGS = "tag_filters.json"

    private val ALL_FILES = listOf(FILE_INTENT, FILE_ALIAS, FILE_MAP, FILE_SOUNDS, FILE_TAGS)
    private val MANDATORY = listOf(FILE_INTENT, FILE_MAP)

    /** 数据源：fork 优先、失败回退上游 */
    private val BASES = listOf(
        "https://cnb.cool/Cloisnee/yinpin/-/git/raw/master/yinxiao/jread_audio_normalized/registry/",
        "https://cnb.cool/applecabal/yinpin/-/git/raw/master/yinxiao/jread_audio_normalized/registry/",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    @Volatile
    private var data: RuleData? = null

    @Volatile
    private var loading = false

    @Volatile
    private var lastAttemptAt = 0L

    fun current(): RuleData? = data

    /** 预热（非阻塞；失败 5 分钟后可再试） */
    fun warmUp(context: Context) {
        if (data != null || loading) return
        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < RETRY_GATE_MS) return
        lastAttemptAt = now
        val appCtx = context.applicationContext
        scope.launch { runCatching { ensureLoaded(appCtx) } }
    }

    suspend fun ensureLoaded(context: Context) {
        if (data != null) return
        lock.withLock {
            if (data != null) return
            loading = true
            try {
                val loaded = load(context.applicationContext)
                if (loaded != null) {
                    data = loaded
                    runCatching {
                        AudioLibrary.backfillFromRules(
                            context.applicationContext,
                            { sid -> loaded.namesOfSound(sid) },
                            { name -> loaded.soundIdByName(name) },
                        )
                    }.onSuccess { n ->
                        if (n > 0) AppLog.putAudio("【音效与背景音】素材别名回填 $n 条")
                    }
                }
            } finally {
                loading = false
            }
        }
    }

    // ------------------------------------------------------------ 加载

    private suspend fun load(context: Context): RuleData? = withContext(Dispatchers.IO) {
        val dir = File(TtsDirProvider.baseDir(context), "_store/audio_rules")
        val metaAt = File(dir, "meta.json").takeIf { it.isFile }?.let { f ->
            runCatching { JSONObject(f.readText().removePrefix("\uFEFF")).optLong("fetchedAt", 0L) }
                .getOrDefault(0L)
        } ?: 0L
        val fresh = System.currentTimeMillis() - metaAt < REFRESH_MS
        var origin = "缓存"
        if (!fresh) {
            val ok = runCatching { fetchAll(dir) }.getOrDefault(false)
            if (ok) origin = "网络"
        }
        val intentText = readText(dir, FILE_INTENT)
        val mapText = readText(dir, FILE_MAP)
        if (intentText == null || mapText == null) {
            AppLog.putAudio("【音效与背景音】意图规则未就绪（拉取失败且无缓存）；内置规则包仍旧生效")
            return@withContext null
        }
        val intents = parseIntents(intentText) ?: return@withContext null
        val soundIdsByIntent = parseSoundMap(mapText) ?: return@withContext null
        val soundById = readText(dir, FILE_SOUNDS)?.let { parseSoundLibrary(it) }.orEmpty()
        val aliasRules = readText(dir, FILE_ALIAS)?.let { parseAliasRules(it) }.orEmpty()
        val excluded = readText(dir, FILE_TAGS)?.let { parseTagFilters(it) }.orEmpty()
        val rd = buildData(intents, soundIdsByIntent, soundById, aliasRules, excluded, origin)
        AppLog.putAudio(
            "【音效与背景音】已加载：意图 ${rd.intentCount} · 别名 ${aliasRules.size} · 声音 ${soundById.size}（$origin）"
        )
        rd
    }

    /** 拉取全部文件到缓存目录；返回「必需文件齐备」 */
    private suspend fun fetchAll(dir: File): Boolean {
        dir.mkdirs()
        var ok = 0
        var fail = 0
        for (name in ALL_FILES) {
            val text = runCatching { fetchText(name) }.getOrNull()
            if (text.isNullOrBlank()) {
                fail++
                continue
            }
            runCatching {
                val tmp = File(dir, "$name.tmp")
                tmp.writeText(text)
                val out = File(dir, name)
                if (!tmp.renameTo(out)) {
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                }
            }.onSuccess { ok++ }.onFailure { fail++ }
        }
        if (fail > 0) AppLog.putAudio("【音效与背景音】拉取完成：成功 $ok · 失败 $fail")
        if (ok > 0) {
            runCatching {
                File(dir, "meta.json").writeText(
                    JSONObject().put("fetchedAt", System.currentTimeMillis()).toString()
                )
            }
        }
        return MANDATORY.all { File(dir, it).isFile }
    }

    private suspend fun fetchText(name: String): String {
        var last: Throwable? = null
        for (base in BASES) {
            val result = runCatching {
                val req = Request.Builder()
                    .url(base + name)
                    .header("User-Agent", "legado-audio-rules")
                    .build()
                val resp = okHttpClient.newCall(req).await()
                require(resp.isSuccessful) { "HTTP ${resp.code}" }
                resp.body?.string() ?: error("空响应")
            }
            result.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            result.exceptionOrNull()?.let { last = it }
        }
        throw last ?: IllegalStateException("规则源不可用：$name")
    }

    private fun readText(dir: File, name: String): String? {
        val f = File(dir, name)
        if (!f.isFile) return null
        return runCatching { f.readText().removePrefix("\uFEFF") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------ 解析（可单测）

    internal fun parseIntents(text: String): List<Intent>? = runCatching {
        val arr = JSONObject(text).optJSONArray("intents") ?: JSONArray()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("intentId")
                if (id.isBlank()) continue
                val anchors = o.optJSONArray("anchors").toStringList()
                if (anchors.isEmpty()) continue
                add(
                    Intent(
                        id = id,
                        type = o.optString("type", "sfx"),
                        subType = o.optString("subType"),
                        anchors = anchors,
                        priority = o.optInt("priority", 3),
                        volume = o.optDouble("volume", 0.8).toFloat().coerceIn(0.05f, 1f),
                        timing = o.optString("timing", "sync"),
                        runtimeEnabled = o.optBoolean("runtimeEnabled", true),
                    )
                )
            }
        }
    }.getOrNull()

    internal fun parseSoundMap(text: String): Map<String, List<String>>? = runCatching {
        val arr = JSONObject(text).optJSONArray("mappings") ?: JSONArray()
        buildMap {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("intentId")
                if (id.isBlank()) continue
                val ids = o.optJSONArray("soundIds").toStringList()
                if (ids.isNotEmpty()) put(id, ids)
            }
        }
    }.getOrNull()

    internal fun parseSoundLibrary(text: String): Map<String, SoundMeta> = runCatching {
        val arr = JSONObject(text).optJSONArray("sounds") ?: JSONArray()
        buildMap {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sid = o.optString("soundId")
                if (sid.isBlank()) continue
                val name = o.optString("legacyName").ifBlank { sid }
                val names = LinkedHashSet<String>()
                names.add(name)
                o.optJSONArray("legacyNames").toStringList().forEach { names.add(it) }
                o.optJSONArray("aliases").toStringList().forEach { names.add(it) }
                put(sid, SoundMeta(sid, name, names.toList()))
            }
        }
    }.getOrDefault(emptyMap())

    internal fun parseAliasRules(text: String): Map<String, String> = runCatching {
        val arr = JSONObject(text).optJSONArray("aliases") ?: JSONArray()
        buildMap {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val alias = o.optString("alias").trim()
                val sid = o.optString("soundId").trim()
                if (alias.isNotBlank() && sid.isNotBlank()) put(alias.lowercase(), sid)
            }
        }
    }.getOrDefault(emptyMap())

    /** 默认关闭分类（enabledDefault=false）涉及的声音 id */
    internal fun parseTagFilters(text: String): Set<String> = runCatching {
        val arr = JSONObject(text).optJSONArray("filters") ?: JSONArray()
        val out = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("enabledDefault", true)) continue
            o.optJSONArray("soundIds").toStringList().forEach { out.add(it) }
        }
        out
    }.getOrDefault(emptySet())

    internal fun buildData(
        intents: List<Intent>,
        soundIdsByIntent: Map<String, List<String>>,
        soundById: Map<String, SoundMeta>,
        soundIdByAlias: Map<String, String>,
        excludedSoundIds: Set<String>,
        origin: String = "缓存",
    ): RuleData {
        val enabled = intents.filter { it.runtimeEnabled && it.anchors.isNotEmpty() }
        val patterns = ArrayList<String>()
        val idxMap = ArrayList<Int>()
        enabled.forEachIndexed { idx, it ->
            it.anchors.forEach { a ->
                val p = a.trim()
                if (p.isNotEmpty()) {
                    patterns.add(p)
                    idxMap.add(idx)
                }
            }
        }
        return RuleData(
            intents = enabled,
            soundIdsByIntent = soundIdsByIntent,
            soundById = soundById,
            soundIdByAlias = soundIdByAlias,
            excludedSoundIds = excludedSoundIds,
            intentIndexByPattern = idxMap.toIntArray(),
            matcher = AhoCorasick(patterns),
            origin = origin,
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until length()) {
            val s = optString(i).trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }
}
