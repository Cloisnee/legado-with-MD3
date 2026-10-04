package io.legado.app.help.readaloud.audio

import android.content.Context
import io.legado.app.constant.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * M3 · 内置音效规则（slim 回归版）：`assets/builtin_sfx_rules.json`
 *   = 旧规则集 842 条 → label 重定向为现行库内名（只保留可重定向项；仅普通音效）。
 *
 * - 只服务普通音效；输出供建议链使用（词网 → 本层 → 用户条目规则兜底），建议名=库内规范名；
 * - 不保留「命中即回填」（不回写条目「匹配规则」）；不含环境/BGM/ADULT；
 * - 匹配：关键词预筛（AhoCorasick）→ 候选跑正则（与旧实现同口径）。
 */
object AudioBuiltinSfxRules {

    /** 命中（name=现行库内名；start=命中起点，供句内位置换算） */
    class Hit(val name: String, val start: Int)

    internal class Rule(val name: String, val regex: Regex, val keywords: List<String>)

    internal class Pack(
        val rules: List<Rule>,
        val matcher: AhoCorasick?,
        val kwToRules: Map<Int, IntArray>,
        val always: IntArray,
    ) {
        /** 一行 → 首个命中（关键词候选 + 常跑位，按规则顺序） */
        fun hit(text: String): Hit? {
            if (rules.isEmpty()) return null
            val cand = java.util.TreeSet<Int>()
            matcher?.matchAll(text)?.forEach { m -> kwToRules[m.patternIndex]?.forEach { cand.add(it) } }
            always.forEach { cand.add(it) }
            for (i in cand) {
                val r = rules.getOrNull(i) ?: continue
                val m = r.regex.find(text) ?: continue
                return Hit(r.name, m.range.first)
            }
            return null
        }
    }

    private val lock = Mutex()
    private val cjkRuns = Regex("[\\u4e00-\\u9fff]{2,}")

    @Volatile
    private var pack: Pack? = null

    /** 载入（幂等；建议链调用前确保就绪；失败静默空转） */
    suspend fun ensureLoaded(context: Context) {
        if (pack != null) return
        lock.withLock {
            if (pack != null) return
            val built = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.applicationContext.assets
                        .open("builtin_sfx_rules.json").use { it.readBytes().decodeToString() }
                    buildPack(text)
                }.getOrNull()
            } ?: return
            pack = built
            AppLog.putAudio("【音效与背景音】内置音效规则就绪：${built.rules.size}条（现行名重定向）")
        }
    }

    /** 命中：返回现行名 + 命中起点（未加载/无命中 = null） */
    fun hit(text: String): Hit? = pack?.hit(text)

    // ------------------------------------------------------------ 解析（内部）

    internal fun buildPack(text: String): Pack? = runCatching {
        val root = JSONObject(text)
        val arr = root.optJSONArray("rules") ?: return null
        val rules = ArrayList<Rule>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            val pattern = o.optString("pattern")
            if (name.isEmpty() || pattern.isEmpty()) continue
            val regex = runCatching { Regex(pattern) }.getOrNull() ?: continue
            rules.add(Rule(name, regex, extractKeywords(pattern)))
        }
        if (rules.isEmpty()) return null
        // 关键词 → 规则索引（全局去重；无关键词的进常跑位）
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
        val matcher = if (kws.isEmpty()) null else runCatching { AhoCorasick(kws) }.getOrNull()
        Pack(
            rules = rules,
            matcher = matcher,
            kwToRules = kwToRules.mapValues { (_, v) -> v.distinct().toIntArray() },
            always = always.toIntArray(),
        )
    }.getOrNull()

    private fun extractKeywords(pattern: String): List<String> =
        cjkRuns.findAll(pattern).map { it.value }.distinct().toList()
}
