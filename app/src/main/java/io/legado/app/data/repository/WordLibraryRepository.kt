package io.legado.app.data.repository

import android.app.Application
import com.github.jing332.compat.fs.TtsDirProvider
import io.legado.app.domain.gateway.GenericNameClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.Normalizer

/**
 * 词库（裸属词 / 特殊词）：不属于朗读分析真源，只是分类辅助；仍使用数据目录下的
 * `bare_words.json` / `special_words.json`（用户可在角色页入库）。
 */
class WordLibraryRepository(private val app: Application) : GenericNameClassifier {

    companion object {
        const val BARE_WORDS_FILE = "bare_words.json"
        const val SPECIAL_WORDS_FILE = "special_words.json"

        /** 裸属词（脚本 BARE_CLASS_WORDS 原样迁入）。 */
        private val BARE_WORDS = setOf(
            "你", "我", "他", "她", "祂", "它", "有人", "一人", "众人", "大家", "路人", "陌生人",
            "男人", "女人", "男孩", "女孩", "少年", "少女", "青年", "老人", "孩子", "大人", "姑娘",
            "小伙子", "老太太", "老先生", "先生", "女士", "大哥", "大姐", "大嫂", "老板", "掌柜的",
            "店小二", "师父", "师傅", "师兄", "师弟", "师姐", "师妹", "小姐", "公子", "夫人", "老爷",
            "少爷", "丫鬟", "侍卫", "士兵", "捕快", "书生", "郎中", "车夫", "猎人", "樵夫", "将军",
            "王爷", "公主", "太子", "陛下", "太监", "宫女", "嬷嬷", "道长", "和尚", "尼姑", "掌柜", "小二",
        )

        /** 特殊类词（系统播报等）。 */
        private val SPECIAL_WORDS = setOf(
            "系统", "系统提示", "系统消息", "系统公告", "系统音", "主系统", "智能ai", "人工智能",
            "ai助手", "语音助手", "虚拟助手", "机器人", "机械音", "电子音", "合成音", "广播", "喇叭",
            "扬声器", "通知", "公告", "提示音", "邮件", "电子邮件", "短信", "讯息", "留言", "弹幕",
            "字幕", "画外音", "智脑", "主脑", "光脑", "中央电脑", "电脑", "终端", "通讯器", "通讯仪",
            "全息投影",
        )
    }

    private fun dataDir(): File = File(TtsDirProvider.baseDir(app), "data").apply { mkdirs() }

    private fun loadExtra(fileName: String): Set<String> = runCatching {
        val file = File(dataDir(), fileName)
        if (!file.exists()) return@runCatching emptySet<String>()
        val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
        (0 until arr.length())
            .map { arr.optString(it).trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }.getOrDefault(emptySet())

    private data class CachedWords(val stamp: Long, val words: Set<String>)

    @Volatile
    private var bareCache: CachedWords? = null

    @Volatile
    private var specialCache: CachedWords? = null

    private fun cachedExtra(fileName: String, current: CachedWords?): Set<String> {
        val file = File(dataDir(), fileName)
        if (!file.exists()) return emptySet()
        val stamp = file.lastModified()
        current?.takeIf { it.stamp == stamp }?.let { return it.words }
        val words = loadExtra(fileName)
        if (fileName == BARE_WORDS_FILE) {
            bareCache = CachedWords(stamp, words)
        } else {
            specialCache = CachedWords(stamp, words)
        }
        return words
    }

    private fun bareExtra(): Set<String> = cachedExtra(BARE_WORDS_FILE, bareCache)

    private fun specialExtra(): Set<String> = cachedExtra(SPECIAL_WORDS_FILE, specialCache)

    override suspend fun isGeneric(name: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = normalizeWord(name)
        normalized.length <= 1 || normalized in BARE_WORDS || normalized in bareExtra()
    }

    suspend fun isBareClassWord(word: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = normalizeWord(word)
        normalized.isNotEmpty() && (normalized in BARE_WORDS || normalized in bareExtra())
    }

    suspend fun isSpecialClassWord(word: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = normalizeWord(word)
        normalized.isNotEmpty() && (normalized in SPECIAL_WORDS || normalized in specialExtra())
    }

    suspend fun bareWords(): Set<String> = withContext(Dispatchers.IO) { BARE_WORDS + bareExtra() }

    suspend fun specialWords(): Set<String> = withContext(Dispatchers.IO) { SPECIAL_WORDS + specialExtra() }

    suspend fun append(fileName: String, words: List<String>): Int = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(dataDir(), fileName)
            val lib = runCatching { JSONArray(file.readText().removePrefix("\uFEFF")) }
                .getOrDefault(JSONArray())
            val existing = LinkedHashSet<String>()
            for (i in 0 until lib.length()) {
                lib.optString(i).trim().takeIf { it.isNotEmpty() }?.let(existing::add)
            }
            var added = 0
            words.forEach { word ->
                val value = word.trim()
                if (value.isNotEmpty() && existing.add(value)) added++
            }
            if (added > 0) {
                file.parentFile?.mkdirs()
                file.writeText(JSONArray(existing.toList()).toString())
                if (fileName == BARE_WORDS_FILE) bareCache = null else specialCache = null
            }
            added
        }.getOrDefault(0)
    }

    private fun normalizeWord(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), "")
            .lowercase()
}
