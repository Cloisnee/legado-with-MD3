package io.legado.app.help.readaloud.audio

import android.content.Context
import com.github.jing332.compat.fs.TtsDirProvider
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfigStore
import io.legado.app.constant.PreferKey
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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * P1 · 词网（唯一概念）：把「说法 → 库内声音」接到 App。
 *
 * - 数据源：仓库 `声效/index/{manifest,catalog,aliases}.json`（CNB raw，无鉴权；负缓存问题不影响 App）；
 * - 缓存：`_store/audio_index/`（manifest 版本变化才重拉 catalog/aliases）；
 * - 能力：① [lookup] 归一（任意自由说法 → 库内资产）② [suggest] 章级扫描（AC 多词命中 → 建议名=库内名）
 *   ③ [localWords] 本地加词（条目「匹配规则」关正则=加词模式，多词 `|、;换行` 分隔）并入有效词网
 *   ④ [fetchAsset] 按需下载（file → raw URL）并落库登记（soundId 直连）。
 *
 * 有效词网 = 仓库词网 + 本地加词；三处生效：播放匹配 / 建议内联 / 归一层拉回。
 */
object AudioNetStore {

    private const val BASE = "https://cnb.cool/Cloisnee/yinpin/-/git/raw/master/"
    private const val INDEX_DIR = "声效/index/"
    private const val CACHE_DIR = "_store/audio_index"

    /** P1.5 · manifest 复核节流间隔（已加载时 ≥10 分钟才拉一次；force 不受限） */
    private const val MANIFEST_RECHECK_MS = 10 * 60 * 1000L

    /** 词网资产（catalog 条目精简） */
    data class NetAsset(
        val id: String,
        val name: String,
        val lane: String,       // sfx/amb/bgm/adult
        val file: String,
        val category: String = "",
        val aliases: List<String> = emptyList(),
        val adult: Boolean = false,
        val volume: Float = 1f,
        val loop: Boolean = false,
    )

    /** 建议（章级扫描用）：lane + 库内规范名 + 命中位比例 */
    data class NetSuggestion(val lane: SynthLane, val name: String, val ratio: Float)

    // ---------------- 状态 ----------------
    @Volatile private var byId: Map<String, NetAsset> = emptyMap()
    @Volatile private var aliasToIds: Map<String, List<String>> = emptyMap()

    /** M5：ADULT 独立别名（aliases_adult.json）；默认不并入，开关开启才参与检索/建议 */
    @Volatile private var aliasToIdsAdult: Map<String, List<String>> = emptyMap()
    @Volatile private var localWordToName: Map<String, String> = emptyMap()
    @Volatile private var ac: AhoCorasick? = null
    @Volatile private var acPatterns: List<String> = emptyList()
    @Volatile private var loaded = false

    /** P1.5 · 最近一次 manifest 复核时间（节流用）与当前词网版本 */
    @Volatile private var lastManifestCheckAt: Long = 0L
    @Volatile private var netVersion: String = ""

    /** 词网当前版本（catalog.version；未加载=空串） */
    val version: String get() = netVersion

    private val loadLock = Mutex()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy {
        okHttpClient.newBuilder()
            .callTimeout(120, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 预热（幂等、异步、失败静默）：加载词网 + 合并本地加词 + 清理旧链缓存（换新裤子，无残留） */
    fun warmUp(context: Context) {
        val appCtx = context.applicationContext
        io.launch {
            runCatching { File(TtsDirProvider.baseDir(appCtx), "_store/audio_remote").deleteRecursively() }
            runCatching { ensureLoaded(appCtx) }
            runCatching { rebuildLocalWords(appCtx) }
        }
    }

    /** 词网快照（远程素材库页/镜像用） */
    fun snapshot(): List<NetAsset> = byId.values.sortedBy { it.id }

    /** 媒体 URL（媒体根 + 逐段编码） */
    fun urlOf(file: String): String = BASE + encodePath(file)

    val isReady: Boolean get() = loaded
    val assetCount: Int get() = byId.size
    val aliasCount: Int get() = aliasToIds.size

    // ---------------- 加载 ----------------

    /**
     * 预热/复核（幂等）：
     * - 未加载：缓存优先加载 + 拉 manifest 比对；
     * - 已加载：**节流复核**（[MANIFEST_RECHECK_MS] 内不重复请求）；[force]=true 强制复核（远程素材库打开/刷新）。
     * 复核失败写音频日志（不再静默）；有版本变化即热更（catalog/aliases/adult）。
     */
    suspend fun ensureLoaded(context: Context, force: Boolean = false): Boolean {
        if (loaded && !force && System.currentTimeMillis() - lastManifestCheckAt < MANIFEST_RECHECK_MS) {
            return true
        }
        return withContext(Dispatchers.IO) {
            loadLock.withLock {
                if (loaded && !force && System.currentTimeMillis() - lastManifestCheckAt < MANIFEST_RECHECK_MS) {
                    return@withLock true
                }
                val dir = cacheDir(context)
                val manifestFile = File(dir, "manifest.json")
                val catalogFile = File(dir, "catalog.json")
                val aliasesFile = File(dir, "aliases.json")
                val adultFile = File(dir, "aliases_adult.json")
                val rulesFile = File(dir, "rules_builtin.json")
                // 1) 首次：先用缓存
                if (!loaded && catalogFile.isFile && aliasesFile.isFile) {
                    runCatching {
                        applyData(
                            catalogFile.readText().removePrefix("\uFEFF"),
                            aliasesFile.readText().removePrefix("\uFEFF"),
                            adultFile.takeIf { it.isFile }?.readText()?.removePrefix("\uFEFF"),
                        )
                    }
                }
                // 2) 拉 manifest 比对版本（节流/强制；失败写日志、有缓存即可用）
                if (force || !loaded || System.currentTimeMillis() - lastManifestCheckAt >= MANIFEST_RECHECK_MS) {
                    val remoteManifest = try {
                        fetchText(BASE + encodePath(INDEX_DIR + "manifest.json"))
                    } catch (e: Exception) {
                        if (loaded) {
                            AppLog.putAudio(
                                "【音效与背景音】词网复核失败（保留本地 v$netVersion）：${e.localizedMessage}"
                            )
                        }
                        null
                    }
                    // 无论成败都进入下一轮节流窗口（force 不受限）
                    lastManifestCheckAt = System.currentTimeMillis()
                    if (remoteManifest != null) {
                        val remoteVer = runCatching { JSONObject(remoteManifest).optString("version") }
                            .getOrNull().orEmpty()
                        val localVer = manifestFile.takeIf { it.isFile }
                            ?.let {
                                runCatching {
                                    JSONObject(it.readText().removePrefix("\uFEFF")).optString("version")
                                }.getOrNull()
                            }
                            .orEmpty()
                        if (remoteVer.isBlank() || remoteVer != localVer || !loaded) {
                            runCatching {
                                val cat = fetchText(BASE + encodePath(INDEX_DIR + "catalog.json"))
                                val ali = fetchText(BASE + encodePath(INDEX_DIR + "aliases.json"))
                                val adultFetched = runCatching {
                                    fetchText(BASE + encodePath(INDEX_DIR + "aliases_adult.json"))
                                }.getOrNull()
                                // 第三刀：内置音效规则表外挂——随词网同步拉取（成功后热换载）
                                val rulesFetched = runCatching {
                                    fetchText(BASE + encodePath(INDEX_DIR + "rules_builtin.json"))
                                }.getOrNull()
                                dir.mkdirs()
                                manifestFile.writeText(remoteManifest)
                                writeAtomic(catalogFile, cat)
                                writeAtomic(aliasesFile, ali)
                                if (!adultFetched.isNullOrBlank()) writeAtomic(adultFile, adultFetched)
                                if (!rulesFetched.isNullOrBlank()) {
                                    writeAtomic(rulesFile, rulesFetched)
                                    runCatching { AudioBuiltinSfxRules.invalidate() }
                                }
                                applyData(
                                    cat, ali,
                                    adultFetched
                                        ?: adultFile.takeIf { it.isFile }?.readText()?.removePrefix("\uFEFF"),
                                )
                                AppLog.putAudio(
                                    "【音效与背景音】词网更新 v$remoteVer：资产 ${byId.size}、" +
                                        "别名 ${aliasToIds.size}（ADULT ${aliasToIdsAdult.size} 另存，开关开才并入）" +
                                        (if (!rulesFetched.isNullOrBlank()) "；规则表已同步" else "")
                                )
                            }
                        }
                    }
                }
                loaded
            }
        }
    }

    private fun applyData(catalogText: String, aliasesText: String, adultText: String? = null) {
        val cat = parseCatalog(catalogText) ?: return
        val ali = parseAliases(aliasesText) ?: return
        byId = cat
        aliasToIds = ali
        aliasToIdsAdult = adultText?.let { parseAliases(it) }.orEmpty()
        netVersion = runCatching { JSONObject(catalogText).optString("version") }.getOrDefault("")
        rebuildAc()
        loaded = true
    }

    // ---------------- 本地加词（自助词网） ----------------

    /**
     * 合并本地条目加词（编辑条目「匹配规则」关正则=加词模式；多词 `|、;，换行` 分隔）+ 条目别名。
     * 词 → 条目素材名（播放/建议/归一层三处生效）。条目编辑/重扫后调用。
     */
    suspend fun rebuildLocalWords(context: Context) {
        val words = HashMap<String, String>()
        runCatching {
            AudioLibrary.ensureLoaded(context.applicationContext)
            AudioLibrary.assets(context.applicationContext).forEach { a ->
                if (!a.enabled) return@forEach
                if (!a.isRegex && a.pattern.isNotBlank()) {
                    splitWords(a.pattern).forEach { w -> words.putIfAbsent(w, a.name) }
                }
                a.aliases.filter { it.isNotBlank() }.forEach { w -> words.putIfAbsent(w.trim(), a.name) }
            }
        }
        localWordToName = words
        rebuildAc()
    }

    private fun rebuildAc() {
        val patterns = LinkedHashSet<String>()
        patterns.addAll(aliasToIds.keys)
        if (adultEnabled()) patterns.addAll(aliasToIdsAdult.keys)
        patterns.addAll(localWordToName.keys)
        val list = patterns.filter { it.isNotBlank() }.toList()
        acPatterns = list
        ac = runCatching { AhoCorasick(list) }.getOrNull()
    }

    // ---------------- 检索 ----------------

    /** 归一层：任意说法 → 库内最佳资产（null=词网里没有；M5：默认不含 ADULT，开关开启才并入） */
    fun lookup(word: String): NetAsset? = lookupFiltered(word) { true }

    /**
     * P1.5 · 跨栏治理（同栏严格）：只在指定轨内解析；同栏无命中 → null（调用方转补缺合成）。
     * 多栏命中时取同栏；仅异栏命中 = 视为未命中（可用 [crossLaneLabels] 记诊断日志）。
     */
    fun lookupForLane(word: String, lane: SynthLane): NetAsset? =
        lookupFiltered(word) { laneOf(it.lane) == lane }

    /**
     * P1.5 · 跨栏诊断：同轨未命中时，返回异轨命中的展示名列表（仅日志/统计用，不参与播放）。
     * 无任何异轨命中 = 空列表。
     */
    fun crossLaneLabels(word: String, lane: SynthLane): List<String> {
        val out = ArrayList<String>(2)
        SynthLane.entries.forEach { l ->
            if (l == lane) return@forEach
            if (lookupFiltered(word) { laneOf(it.lane) == l } != null) out.add(laneDisplay(l))
        }
        return out
    }

    /** P1.5 · 跨栏忽略提示文案（各接入点日志复用；异轨无命中 = null） */
    fun crossLaneMessage(lane: SynthLane, keyword: String): String? {
        val labels = crossLaneLabels(keyword, lane)
        if (labels.isEmpty()) return null
        val tail = if (labels.size == 1) "仅${labels.first()}库有" else "${labels.joinToString("、")}库有"
        return "跨栏素材忽略：${laneDisplay(lane)}「$keyword」→ $tail（转补缺）"
    }

    /** P1.5 · 轨展示名（日志口径：环境声，非「环境」） */
    internal fun laneDisplay(lane: SynthLane): String = when (lane) {
        SynthLane.SFX -> "音效"
        SynthLane.AMB -> "环境声"
        SynthLane.BGM -> "BGM"
    }

    /** 检索主实现：归一 → 精确/本地词/柔和后缀/包含兜底；[allow] 过滤候选（跨栏治理用） */
    private fun lookupFiltered(word: String, allow: (NetAsset) -> Boolean): NetAsset? {
        val w = norm(word)
        if (w.isBlank()) return null
        val adultOk = adultEnabled()
        bestOf(idsFor(w, adultOk).filter { id -> byId[id]?.let(allow) == true })?.let { return it }
        // 本地加词优先（用户亲手挂的）
        localWordToName[w]?.let { name ->
            byId.values.firstOrNull { it.name == name && allow(it) }?.let { return it }
        }
        // 柔和后缀：加减「声/音效」
        softVariants(w).forEach { v ->
            bestOf(idsFor(v, adultOk).filter { id -> byId[id]?.let(allow) == true })?.let { return it }
            localWordToName[v]?.let { name ->
                byId.values.firstOrNull { it.name == name && allow(it) }?.let { return it }
            }
        }
        // 包含兜底：词网里谁包含它 / 它包含谁（长词优先，防“一门”类误配）
        var best: NetAsset? = null
        var bestLen = 0
        for ((alias, ids) in effectiveAliases(adultOk)) {
            if (alias.length < 2) continue
            if ((w.length >= 2 && alias.contains(w)) || (w.length >= 2 && w.contains(alias))) {
                val asset = bestOf(ids.filter { id -> byId[id]?.let(allow) == true }) ?: continue
                val l = minOf(alias.length, w.length)
                if (l > bestLen) { bestLen = l; best = asset }
            }
        }
        return best
    }

    /** M5：命中候选——默认仅普通词网；开关开启并入 ADULT（同键两集合求并） */
    private fun idsFor(word: String, adultOk: Boolean): List<String> {
        val normal = aliasToIds[word]
        if (!adultOk) return normal.orEmpty()
        val adult = aliasToIdsAdult[word]
        return when {
            normal == null -> adult.orEmpty()
            adult == null -> normal
            else -> normal + adult
        }
    }

    /** M5：包含兜底用的有效别名集（开关开启时并入 ADULT） */
    private fun effectiveAliases(adultOk: Boolean): Map<String, List<String>> {
        if (!adultOk || aliasToIdsAdult.isEmpty()) return aliasToIds
        val merged = HashMap<String, List<String>>(aliasToIds.size + aliasToIdsAdult.size)
        merged.putAll(aliasToIds)
        aliasToIdsAdult.forEach { (k, v) ->
            val prev = merged[k]
            merged[k] = if (prev == null) v else prev + v
        }
        return merged
    }

    /** 18+ 内容开关（M5：ADULT 别名/建议/归一层跟随） */
    private fun adultEnabled(): Boolean = runCatching {
        AppConfigStore.getBoolean(PreferKey.audioAdultEnabled) == true
    }.getOrDefault(false)

    /** 章级扫描：文本 → 建议（AC 多词一遍过；名字=库内规范名，ratio=命中位） */
    fun suggest(text: String, maxPerLane: Int = 1): List<NetSuggestion> {
        val matcher = ac ?: return emptyList()
        val t = text.trim()
        if (t.length < 2) return emptyList()
        val adultOk = adultEnabled()
        data class Cand(val lane: SynthLane, val name: String, val ratio: Float, val len: Int)
        val best = HashMap<SynthLane, Cand>()
        for (m in matcher.matchAll(t)) {
            val alias = acPatterns.getOrNull(m.patternIndex) ?: continue
            if (alias.length < 2) continue
            // P1.6.2+：本地加词优先——命中用户挂词/本地别名 → 直接产出「本地条目名」（不再绕远程查名）
            val localName = localWordToName[alias]
            if (localName != null) {
                val localAsset = AudioLibrary.snapshot().firstOrNull { it.enabled && it.name == localName }
                if (localAsset != null) {
                    val lane = AudioLibrary.laneSynthOfAsset(localAsset)
                    val ratio = AudioPositions.ratioOfMatch(m.start, t.length)
                    val cand = Cand(lane, localName, ratio, m.length)
                    val prev = best[lane]
                    if (prev == null || cand.len > prev.len) best[lane] = cand
                    continue
                }
            }
            val asset = bestOf(aliasToIds[alias]) ?: continue
            if (asset.adult && !adultOk) continue
            val lane = laneOf(asset.lane) ?: continue
            val ratio = AudioPositions.ratioOfMatch(m.start, t.length)
            val cand = Cand(lane, asset.name, ratio, m.length)
            val prev = best[lane]
            if (prev == null || cand.len > prev.len) best[lane] = cand
        }
        return best.values.map { NetSuggestion(it.lane, it.name, it.ratio) }
    }

    // ---------------- 下载落库 ----------------

    /** 按需下载一条词网资产（file → raw URL）→ 落库登记（soundId 直连）。已存在则直接返回。 */
    suspend fun fetchAsset(context: Context, asset: NetAsset, onProgress: (Long) -> Unit = {}): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = TmDemoAssets.libRoot(context)
                val ext = asset.file.substringAfterLast('.', "mp3").lowercase()
                // P1.6.2+：落库名=词网文件基名（含变体区分；如 手机按键_2.wav）
                val baseName = asset.file.substringAfterLast('/').substringBeforeLast('.')
                val name = sanitizeGeneratedName(baseName.ifBlank { asset.name }) + "." + ext
                val folder = folderOf(asset.lane)
                val out = File(root, "$folder/$name")
                val relPath = out.relativeTo(root).path.replace(File.separatorChar, '/')
                if (out.isFile && out.length() > 0L &&
                    AudioLibrary.sourceOfRelPath(relPath) != AudioLibrary.SOURCE_GENERATED
                ) {
                    AudioLibrary.notifyFileAdded(
                        context, out, AudioLibrary.SOURCE_REMOTE, asset.id,
                        asset.aliasesForNotify(),
                    )
                    return@runCatching out
                }
                out.parentFile?.mkdirs()
                val tmp = File(out.parentFile, "$name.part")
                val req = Request.Builder()
                    .url(BASE + encodePath(asset.file))
                    .header("User-Agent", "legado-audio-net")
                    .build()
                val resp = http.newCall(req).await()
                require(resp.isSuccessful) { "HTTP ${resp.code}" }
                val body = resp.body ?: error("空响应")
                var downloaded = 0L
                var lastReport = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            if (downloaded - lastReport >= 256 * 1024) {
                                lastReport = downloaded
                                runCatching { onProgress(downloaded) }
                            }
                        }
                    }
                }
                if (downloaded <= 0L) {
                    runCatching { tmp.delete() }
                    error("下载为空")
                }
                if (!tmp.renameTo(out)) {
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                }
                AudioLibrary.notifyFileAdded(
                    context, out, AudioLibrary.SOURCE_REMOTE, asset.id, asset.aliasesForNotify(),
                )
                out
            }.getOrNull()
        }

    // P1.6.2+：下载只带主名（词林不再随文件下放；用户日后自行加词上传）
    private fun NetAsset.aliasesForNotify(): List<String> = listOf(name)

    // ---------------- 纯函数（可单测） ----------------

    /** P1.6.2+：本地词优先——该说法对应的「本地条目名」（含用户挂词/条目别名；无则 null）。播放链本地优先判定用 */
    fun localAssetName(word: String): String? {
        val w = norm(word)
        if (w.isBlank()) return null
        localWordToName[w]?.let { return it }
        softVariants(w).forEach { v -> localWordToName[v]?.let { return it } }
        return null
    }

    /** 说法归一：去空白、去柔和后缀（音效/声效/的声音/声音） */
    internal fun norm(raw: String): String {
        var t = raw.trim()
        for (suf in listOf("音效", "声效", "的声音", "声音")) {
            if (t.length > suf.length && t.endsWith(suf)) {
                t = t.removeSuffix(suf)
                break
            }
        }
        return t.trim()
    }

    /** 加减「声/音」形态（候选扩展；基于去尾干词，去重且排除原词） */
    internal fun softVariants(w: String): List<String> {
        if (w.length < 2) return emptyList()
        val stem = w.removeSuffix("声").removeSuffix("音").ifEmpty { w }
        return listOf(stem, stem + "声", stem + "音")
            .filter { it.isNotBlank() && it != w }
            .distinct()
    }

    /** 加词模式的分词：`|`、顿号、分号、逗号、换行、制表符分隔 */
    internal fun splitWords(raw: String): List<String> = splitWordList(raw)

    /** 路径逐段百分号编码（中文/空格/符号；raw 通道要求） */
    internal fun encodePath(path: String): String =
        path.split('/').joinToString("/") { seg ->
            URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }

    internal fun laneOf(lane: String): SynthLane? = when (lane.lowercase()) {
        "sfx", "adult" -> SynthLane.SFX
        "amb" -> SynthLane.AMB
        "bgm" -> SynthLane.BGM
        else -> null
    }

    internal fun folderOf(lane: String): String = when (lane.lowercase()) {
        "bgm" -> "bgm"
        "amb" -> "sfx/环境声"
        "adult" -> "sfx/ADULT"
        else -> "sfx/音效"
    }

    /** 候选去重后取最佳：优先名==说法本身 → 名最短 → 首个 */
    private fun bestOf(ids: List<String>?): NetAsset? {
        val list = ids?.mapNotNull { byId[it] } ?: return null
        if (list.isEmpty()) return null
        return list.sortedWith(
            compareBy({ it.name.length }, { it.id }),
        ).first()
    }

    // ---------------- 解析（内部） ----------------

    private fun parseCatalog(text: String): Map<String, NetAsset>? = runCatching {
        val root = JSONObject(text)
        val arr = root.optJSONArray("assets") ?: return null
        val map = LinkedHashMap<String, NetAsset>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            map[id] = NetAsset(
                id = id,
                name = o.optString("name").ifBlank { id },
                lane = o.optString("lane", "sfx"),
                file = o.optString("file"),
                category = o.optString("category"),
                aliases = o.optJSONArray("aliases").let { arr ->
                    if (arr == null) emptyList()
                    else (0 until arr.length()).mapNotNull { k -> arr.optString(k).takeIf { it.isNotBlank() } }
                },
                adult = o.optBoolean("adult", false),
                volume = o.optDouble("volume", 1.0).toFloat(),
                loop = o.optBoolean("loop", false),
            )
        }
        map
    }.getOrNull()

    private fun parseAliases(text: String): Map<String, List<String>>? = runCatching {
        val root = JSONObject(text)
        val arr = root.optJSONArray("aliases") ?: return null
        val map = LinkedHashMap<String, List<String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val alias = o.optString("alias").trim()
            if (alias.isBlank()) continue
            val ids = ArrayList<String>(2)
            o.optJSONArray("ids")?.let { a ->
                for (k in 0 until a.length()) a.optString(k).takeIf { it.isNotBlank() }?.let { ids.add(it) }
            }
            if (ids.isNotEmpty()) map[alias] = ids
        }
        map
    }.getOrNull()

    private fun cacheDir(context: Context): File =
        File(TtsDirProvider.baseDir(context), CACHE_DIR)

    private fun writeAtomic(f: File, text: String) {
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }
    }

    private suspend fun fetchText(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "legado-audio-net")
            .build()
        val resp = http.newCall(req).await()
        require(resp.isSuccessful) { "HTTP ${resp.code}" }
        return resp.body?.string() ?: error("空响应")
    }
}