package io.legado.app.help.readaloud.audio

import android.content.Context
import android.os.Environment
import com.github.jing332.compat.fs.TtsDirProvider
import io.legado.app.help.http.await
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.zip.ZipFile

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
        /** 同素材再次触发的静默窗口（毫秒） */
        val cooldownMs: Long = 60_000L,
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
        Rule(Lane.BGM, Regex("(战斗|厮杀|交战|杀意|生死相搏|混战|动手)"), "战斗", holdCues = 20, cooldownMs = 150_000L),
        Rule(Lane.BGM, Regex("(紧张|危机|危险|杀机|不对劲|剑拔弩张|阴森|压迫)"), "紧张", holdCues = 14, cooldownMs = 150_000L),
        Rule(Lane.BGM, Regex("(温柔|回忆|往事|曾经|思念|重逢|喜欢)"), "温柔", holdCues = 14, cooldownMs = 150_000L),
        Rule(Lane.BGM, Regex("(灵气|仙门|宗门|御剑|修真|仙气|秘境)"), "仙侠紧张", holdCues = 14, cooldownMs = 150_000L),
    )

    fun match(lane: Lane, text: String): Rule? =
        rules.firstOrNull { it.lane == lane && it.pattern.containsMatchIn(text) }
}

object TmDemoAssets {

    private const val CNB_RAW_BASE =
        "https://cnb.cool/applecabal/yinpin/-/git/raw/master/"

    private data class RemoteSpec(val urlRel: String, val saveRel: String)
    private data class ZipSpec(val keywords: List<String>, val saveRel: String)

    /**
     * 远程示例素材：音效 = JRead 核心 MP3；环境 = matrix24 WAV。
     * B33.2 起由「音频库管理页」接全量（3514/6272/450 + BGM 1483）。
     */
    private val remoteSpecs = listOf(
        // 音效（硬音效/拟音/戏内声源/主观声）
        RemoteSpec(
            "yinxiao/jread_audio_normalized/audio/core/medium_sfx/door_window/door_open_05.mp3",
            "sfx/硬音效/开门.mp3",
        ),
        RemoteSpec(
            "yinxiao/jread_audio_normalized/audio/core/medium_sfx/door_window/door_close_01.mp3",
            "sfx/硬音效/关门.mp3",
        ),
        RemoteSpec(
            "yinxiao/jread_audio_normalized/audio/core/micro_sfx/object_handle/cn_cha_bei_bai_fang_wan_kuai_01.mp3",
            "sfx/拟音/茶杯摆放.mp3",
        ),
        RemoteSpec(
            "yinxiao/jread_audio_normalized/audio/core/strong_sfx/blade_weapon/blade_clash_metal_02.mp3",
            "sfx/硬音效/兵器交锋.mp3",
        ),
        RemoteSpec(
            "yinxiao/jread_audio_normalized/audio/core/scene/bell/bell_chime_03.mp3",
            "sfx/戏内声源/钟声.mp3",
        ),
        RemoteSpec(
            "yinxiao/jread_audio_normalized/audio/core/emotion/fear_tension/cn_xin_tiao_01.mp3",
            "sfx/主观声/心跳.mp3",
        ),
        RemoteSpec(
            "yinxiao/jread_audio_normalized/audio/core/medium_sfx/body_movement/footstep_run_05.mp3",
            "sfx/拟音/脚步跑.mp3",
        ),
        RemoteSpec(
            "yinxiao/jread_audio_normalized/audio/core/sfx/misc/cn_he_cha_sheng_01.mp3",
            "sfx/拟音/喝茶.mp3",
        ),
        // 环境（matrix24 环境声；wav 大文件，下载后长期复用）
        RemoteSpec(
            "yinxiao/matrix24/audio/crowd/crowd/ancient_shared_amb_市集日景_matrix24_l07_amb_a037_base_v01.wav",
            "sfx/环境声/市集日景.wav",
        ),
        RemoteSpec(
            "yinxiao/matrix24/audio/weather_nature/weather_rain/ancient_shared_amb_竹林雨夜_matrix24_l07_amb_a045_base_v01.wav",
            "sfx/环境声/竹林雨夜.wav",
        ),
        RemoteSpec(
            "yinxiao/matrix24/audio/animal_creature/animal/general_amb_清晨鸟鸣_matrix24_l01_amb_amb118_a_v01.wav",
            "sfx/环境声/清晨鸟鸣.wav",
        ),
        RemoteSpec(
            "yinxiao/matrix24/audio/scene_environment/room/ancient_shared_amb_客栈大堂_matrix24_l07_amb_a031_base_v01.wav",
            "sfx/环境声/客栈大堂.wav",
        ),
    )

    /** BGM 示例：从甲方下载的 BGM 库 zip 按关键字提取（不解压全库） */
    private val zipSpecs = listOf(
        ZipSpec(listOf("urban_battle_heroic_high_loop"), "bgm/战斗.m4a"),
        ZipSpec(listOf("history_tension_tension_mid_loop"), "bgm/紧张.m4a"),
        ZipSpec(listOf("romance_warm_warm_low_loop"), "bgm/温柔.m4a"),
        ZipSpec(listOf("xianxia_tension_tension_high_loop"), "bgm/仙侠紧张.m4a"),
    )

    fun libRoot(context: Context): File =
        File(File(TtsDirProvider.baseDir(context), "data"), "audio_lib")

    /** 在库内按文件名找素材（精确名优先，其次包含匹配；B33.2 换 registry 检索） */
    fun findFile(context: Context, keyword: String): File? {
        val root = libRoot(context)
        if (!root.exists()) return null
        val files = walkFiles(root, 0).toList()
        return files.firstOrNull { it.nameWithoutExtension == keyword }
            ?: files.firstOrNull { it.name.contains(keyword, ignoreCase = true) }
    }

    private fun walkFiles(dir: File, depth: Int): Sequence<File> {
        if (depth > 4) return emptySequence()
        return dir.listFiles().orEmpty().asSequence().flatMap { f ->
            if (f.isDirectory) walkFiles(f, depth + 1) else sequenceOf(f)
        }
    }

    private fun findBgmZip(): File? {
        val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val direct = File(dlDir, "JRead_BGM_Library_Curated_20260701.zip")
        if (direct.isFile) return direct
        return dlDir.listFiles().orEmpty()
            .firstOrNull { it.isFile && it.name.startsWith("JRead_BGM") && it.name.endsWith(".zip") }
    }

    /** 准备示例素材；返回概要文本。重复执行会跳过已有文件（增量补齐）。 */
    suspend fun ensureDemoAssets(
        context: Context,
        log: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val root = libRoot(context).apply { mkdirs() }
        var ok = 0
        var skip = 0
        var fail = 0

        for (spec in remoteSpecs) {
            val out = File(root, spec.saveRel)
            if (out.isFile && out.length() > 0) {
                skip++
                continue
            }
            val done = runCatching {
                out.parentFile?.mkdirs()
                val request = Request.Builder().url(CNB_RAW_BASE + spec.urlRel).build()
                okHttpClient.newCall(request).await().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("empty body")
                    body.byteStream().use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (out.length() <= 0) error("empty file")
                true
            }.getOrElse {
                runCatching { out.delete() }
                log("【四轨·素材】失败：${spec.saveRel}（${it.localizedMessage}）")
                false
            }
            if (done) {
                ok++
                log("【四轨·素材】已下载：${spec.saveRel}")
            } else {
                fail++
            }
        }

        // BGM：从 zip 提取示例曲
        val zip = findBgmZip()
        if (zip == null) {
            log("【四轨·素材】未找到 BGM 库 zip（Download/JRead_BGM_Library_Curated_*.zip），跳过 BGM 示例")
        } else {
            runCatching {
                ZipFile(zip).use { zf ->
                    val entries = zf.entries().toList()
                    for (spec in zipSpecs) {
                        val out = File(root, spec.saveRel)
                        if (out.isFile && out.length() > 0) {
                            skip++
                            continue
                        }
                        val hit = entries.firstOrNull { e ->
                            !e.isDirectory &&
                                spec.keywords.all { kw -> e.name.contains(kw) } &&
                                (e.name.endsWith(".m4a") || e.name.endsWith(".mp3"))
                        }
                        if (hit == null) {
                            log("【四轨·素材】zip 内未匹配到：${spec.keywords.joinToString("/")}")
                            fail++
                            continue
                        }
                        out.parentFile?.mkdirs()
                        zf.getInputStream(hit).use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (out.length() > 0) {
                            ok++
                            log("【四轨·素材】已提取：${spec.saveRel}")
                        } else {
                            fail++
                        }
                    }
                }
            }.onFailure {
                log("【四轨·素材】读取 BGM zip 失败：${it.localizedMessage}")
                fail++
            }
        }

        "示例素材准备完成：新增 $ok · 跳过 $skip · 失败 $fail"
    }
}