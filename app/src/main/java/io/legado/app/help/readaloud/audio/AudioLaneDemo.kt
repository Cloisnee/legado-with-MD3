package io.legado.app.help.readaloud.audio

import android.content.Context
import com.github.jing332.compat.fs.TtsDirProvider
import java.io.File

/**
 * B33 四轨（音效/BGM/环境）· 小闭环演示层。
 *
 * - [DemoLanes]：内置的少量「示例规则」（正则 → 轨 / 素材关键字）。
 *   正式全量规则（CNB 2219 条导入 + 管理页）在 B33.3 接入；这里先给四轨动起来的最小集。
 * - [TmDemoAssets]：示例素材准备（远程 CNB 直链下载 + 从甲方 BGM 库 zip 提取示例曲）。
 *
 * 素材落位（与《B33 施工方案》目录契约一致的最小版，全中文）：
 *   <数据根>/data/audio_lib/sfx/{环境声|拟音|硬音效|戏内声源|主观声}/<中文名>.(wav|mp3)
 *   <数据根>/data/audio_lib/bgm/<中文名>.(m4a|mp3)
 */
object DemoLanes {

    enum class Lane { AMBIENCE, SFX, BGM }

    data class Rule(
        val lane: Lane,
        val pattern: Regex,
        /** 素材关键字（在 audio_lib 内按文件名匹配；B33.2 起换 registry 精确解析） */
        val keyword: String,
        /** 触发增益（0..1，最终音量 = 轨音量 × 该值） */
        val gain: Float = 0.8f,
        /** 相对段落起点的延迟（毫秒） */
        val delayMs: Long = 0L,
        /** BGM：持续多少个剧本行后淡出（其余轨忽略） */
        val holdCues: Int = 0,
    )

    val rules: List<Rule> = listOf(
        // —— 环境（场景底色，loop）——
        Rule(Lane.AMBIENCE, Regex("(市集|集市|街市|叫卖|摊位|商贩)"), "市集日景"),
        Rule(Lane.AMBIENCE, Regex("(细雨|大雨|暴雨|下雨|雨夜|雨水|雨点)"), "竹林雨夜"),
        Rule(Lane.AMBIENCE, Regex("(山林|树林|林间|鸟鸣|清晨|山间|山路)"), "清晨鸟鸣"),
        Rule(Lane.AMBIENCE, Regex("(客栈|酒楼|茶馆|大堂|店里|店内|客店)"), "客栈大堂"),

        // —— 音效（点状，卡在台词旁）——
        Rule(Lane.SFX, Regex("(推开.{0,4}门|门开了|打开.{0,3}门|推门而入)"), "开门", delayMs = 120),
        Rule(Lane.SFX, Regex("(关上.{0,4}门|关门|掩上.{0,3}门|门被关上)"), "关门", delayMs = 120),
        Rule(Lane.SFX, Regex("(茶杯|茶碗|茶盏|放下.{0,4}杯|端起.{0,4}杯)"), "茶杯摆放", delayMs = 200),
        Rule(Lane.SFX, Regex("(刀剑|长剑|利剑|拔剑|出鞘|兵刃|格挡|交锋)"), "兵器交锋", gain = 0.85f),
        Rule(Lane.SFX, Regex("(钟声|钟响|敲钟|晨钟|暮鼓)"), "钟声", gain = 0.7f),
        Rule(Lane.SFX, Regex("(心跳|心跳声)"), "心跳", gain = 0.65f),
        Rule(Lane.SFX, Regex("(脚步声|脚步|快步走来|奔跑声)"), "脚步跑", gain = 0.7f),
        Rule(Lane.SFX, Regex("(喝茶|饮茶|呷了一口|品了一口|茶水)"), "喝茶", delayMs = 150),

        // —— BGM（关键场景起乐；holdCues≈持续行数，之后自动淡出）——
        Rule(Lane.BGM, Regex("(战斗|厮杀|交战|杀意|生死相搏|混战|动手)"), "战斗", holdCues = 20),
        Rule(Lane.BGM, Regex("(紧张|危机|危险|杀机|不对劲|剑拔弩张|阴森|压迫)"), "紧张", holdCues = 14),
        Rule(Lane.BGM, Regex("(温柔|回忆|往事|曾经|思念|重逢|喜欢)"), "温柔", holdCues = 14),
        Rule(Lane.BGM, Regex("(灵气|仙门|宗门|御剑|修真|仙气|秘境)"), "仙侠紧张", holdCues = 14),
    )

    }

object TmDemoAssets {

    fun libRoot(context: Context): File =
        File(File(TtsDirProvider.baseDir(context), "data"), "audio_lib")

    /** 可识别的音频扩展名（B33.3c：过滤 .json sidecar 等非音频文件，防误命中） */
    private val AUDIO_EXTS = setOf("mp3", "m4a", "wav", "ogg", "flac", "aac")

    /** 在库内按文件名找素材（精确名优先，其次包含匹配；B33.2 换 registry 检索） */
    fun findFile(context: Context, keyword: String): File? {
        val root = libRoot(context)
        if (!root.exists()) return null
        val files = walkFiles(root, 0)
            .filter { it.extension.lowercase() in AUDIO_EXTS }
            .toList()
        return files.firstOrNull { it.nameWithoutExtension == keyword }
            ?: files.firstOrNull { it.name.contains(keyword, ignoreCase = true) }
    }

    private fun walkFiles(dir: File, depth: Int): Sequence<File> {
        if (depth > 4) return emptySequence()
        return dir.listFiles().orEmpty().asSequence().flatMap { f ->
            if (f.isDirectory) walkFiles(f, depth + 1) else sequenceOf(f)
        }
    }
}