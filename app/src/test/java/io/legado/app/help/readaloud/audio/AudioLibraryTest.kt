package io.legado.app.help.readaloud.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** org.json 为 Android 框架实现：单测走 Robolectric（否则 JVM 下为 Not mocked 桩）。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class AudioLibraryTest {

    private fun asset(rel: String, name: String? = null, aliases: List<String> = emptyList()) =
        AudioLibrary.AudioAsset(
            name = name ?: rel.substringAfterLast('/').substringBeforeLast('.'),
            relPath = rel,
            category = AudioLibrary.categoryOf(rel),
            aliases = aliases,
        )

    @Test
    fun `分类收敛 全部四栏判定`() {
        assertEquals("BGM", AudioLibrary.categoryOf("bgm/战斗.m4a"))
        assertEquals("BGM", AudioLibrary.categoryOf("bgm/古风/战斗.m4a"))
        assertEquals("环境声", AudioLibrary.categoryOf("sfx/环境声/客栈大堂.wav"))
        assertEquals("音效", AudioLibrary.categoryOf("sfx/拟音/茶杯摆放.mp3"))
        assertEquals("音效", AudioLibrary.categoryOf("sfx/硬音效/开门.mp3"))
        assertEquals("音效", AudioLibrary.categoryOf("sfx/开门.mp3"))
        assertEquals("音效", AudioLibrary.categoryOf("导入/铜铃轻响.mp3"))
        assertEquals("音效", AudioLibrary.categoryOf("misc/x.mp3"))
    }

    @Test
    fun `解析链顺序 精确优先于别名优先于包含`() {
        val list = listOf(
            asset("sfx/拟音/竹林雨夜.mp3"),
            asset("sfx/拟音/清晨鸟鸣.mp3", name = "清晨鸟鸣", aliases = listOf("鸟鸣", "晨鸟")),
            asset("sfx/硬音效/客栈大堂钟声.mp3", name = "客栈大堂钟声"),
        )
        assertEquals("竹林雨夜", AudioLibrary.lookup(list, "竹林雨夜")?.name)
        assertEquals("清晨鸟鸣", AudioLibrary.lookup(list, "鸟鸣")?.name)
        assertEquals("客栈大堂钟声", AudioLibrary.lookup(list, "钟声")?.name)
        assertNull(AudioLibrary.lookup(list, "不存在的词"))
    }

    @Test
    fun `registry 序列化与解析往返一致`() {
        val assets = listOf(
            asset("sfx/拟音/铜铃轻响.mp3", name = "铜铃轻响", aliases = listOf("铃铛"))
                .copy(
                    source = AudioLibrary.SOURCE_GENERATED,
                    size = 136232,
                    mtime = 1234L,
                    pattern = "铃|钟",
                    tagDesc = "铜铃轻响音效",
                    scopeTitle = true,
                    enabled = false,
                    volume = 1.5f,
                    speed = 1.2f,
                    pitch = 0.9f,
                ),
            asset("bgm/战斗.m4a", name = "战斗")
                .copy(enabled = true, pattern = "战斗", scopeContent = false),
        )
        val text = AudioLibrary.serializeRegistry(assets)
        val parsed = AudioLibrary.parseRegistry(text)
        assertEquals(2, parsed?.size)
        val a = parsed?.get("sfx/拟音/铜铃轻响.mp3")
        assertEquals("铜铃轻响", a?.name)
        assertEquals(AudioLibrary.SOURCE_GENERATED, a?.source)
        assertEquals(listOf("铃铛"), a?.aliases)
        assertEquals(136232L, a?.size)
        assertEquals(1234L, a?.mtime)
        assertEquals("铃|钟", a?.pattern)
        assertEquals("铜铃轻响音效", a?.tagDesc)
        assertEquals(true, a?.isRegex)
        assertEquals(true, a?.scopeTitle)
        assertEquals(true, a?.scopeContent)
        assertEquals(false, a?.enabled)
        assertEquals(1.5f, a?.volume)
        assertEquals(1.2f, a?.speed)
        assertEquals(0.9f, a?.pitch)
        val b = parsed?.get("bgm/战斗.m4a")
        assertEquals("战斗", b?.pattern)
        assertEquals(false, b?.scopeContent)
        assertEquals(true, b?.enabled)
        assertEquals("BGM", b?.category)
    }

    @Test
    fun `损坏的 registry 解析返回空而不是抛出`() {
        assertNull(AudioLibrary.parseRegistry("{not-json"))
        assertNull(AudioLibrary.parseRegistry(""))
    }

    @Test
    fun `注册表 序列化与解析含 soundId 与别名（sidecar 自带规则）`() {
        val a = AudioLibrary.AudioAsset(
            name = "林鸟惊飞音效",
            relPath = "sfx/环境声/林鸟惊飞音效.mp3",
            category = "环境声",
            soundId = "bird_call_02",
            aliases = listOf("林鸟惊飞", "林鸟惊飞音效"),
        )
        val parsed = AudioLibrary.parseRegistry(AudioLibrary.serializeRegistry(listOf(a)))
        val item = parsed?.values?.firstOrNull()
        assertEquals("bird_call_02", item?.soundId)
        assertEquals(listOf("林鸟惊飞", "林鸟惊飞音效"), item?.aliases)
    }
}
