package io.legado.app.help.readaloud.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** org.json 为 Android 框架实现：单测走 Robolectric（否则 JVM 下为 Not mocked 桩）。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class AudioRemoteTest {

    @Test
    fun `manifest 解析 含默认开关`() {
        val text = """
        {"packages":[
          {"id":"core","label":"JRead 广播剧核心音效库","indexUrl":"https://x/core.json","soundCount":3514,"defaultEnabled":true},
          {"id":"adult_romance","label":"JRead 18+音效库","indexUrl":"https://x/adult.json","soundCount":20,"defaultEnabled":false}
        ]}
        """.trimIndent()
        val packs = AudioRemoteCatalog.parseManifest(text)
        assertEquals(2, packs?.size)
        assertEquals("core", packs?.get(0)?.id)
        assertEquals(3514, packs?.get(0)?.soundCount)
        assertEquals(true, packs?.get(0)?.defaultEnabled)
        assertEquals(false, packs?.get(1)?.defaultEnabled)
    }

    @Test
    fun `index 解析 别名合并去重`() {
        val text = """
        {"sounds":[
          {"soundId":"arrow_volley_01","legacyName":"万箭齐发音效","aliases":["万箭齐发","万箭齐射"],
           "legacyNames":["万箭齐射","万箭齐发音效"],"category":"strong_sfx","subType":"projectile",
           "pack":"core","url":"https://x/a.mp3","assetPath":"audio/core/strong_sfx/projectile/arrow_volley_01.mp3"}
        ]}
        """.trimIndent()
        val list = AudioRemoteCatalog.parseIndex(text)
        assertEquals(1, list?.size)
        val s = list?.get(0)
        assertEquals("万箭齐发音效", s?.name)
        assertEquals(listOf("万箭齐发", "万箭齐射", "万箭齐发音效"), s?.aliases)
        assertEquals("strong_sfx", s?.category)
        assertEquals("https://x/a.mp3", s?.url)
    }

    @Test
    fun `落库目录 环境类收敛到环境声 其余统一音效`() {
        assertEquals(
            "sfx/环境声",
            AudioRemoteCatalog.folderOf(AudioRemoteCatalog.RemoteSound(category = "scene")),
        )
        assertEquals(
            "sfx/环境声",
            AudioRemoteCatalog.folderOf(
                AudioRemoteCatalog.RemoteSound(category = "sfx", categoryName = "环境声")
            ),
        )
        assertEquals(
            "sfx/音效",
            AudioRemoteCatalog.folderOf(AudioRemoteCatalog.RemoteSound(category = "strong_sfx")),
        )
        assertEquals(
            "sfx/音效",
            AudioRemoteCatalog.folderOf(
                AudioRemoteCatalog.RemoteSound(category = "sfx", categoryName = "戏内声源")
            ),
        )
        assertEquals(
            "sfx/音效",
            AudioRemoteCatalog.folderOf(AudioRemoteCatalog.RemoteSound()),
        )
    }

    @Test
    fun `文件名 中文纯净名与扩展回退`() {
        assertEquals(
            "万箭齐发音效.mp3",
            AudioRemoteCatalog.fileNameOf(
                AudioRemoteCatalog.RemoteSound(
                    name = "万箭齐发音效",
                    assetPath = "audio/core/strong_sfx/projectile/arrow_volley_01.mp3",
                )
            ),
        )
        assertEquals(
            "老宅夜静.wav",
            AudioRemoteCatalog.fileNameOf(
                AudioRemoteCatalog.RemoteSound(
                    name = "老宅夜静",
                    assetPath = "audio/horror_thriller_v1/amb/SFX_A_A001_x.wav",
                )
            ),
        )
        assertEquals(
            "a_b.mp3",
            AudioRemoteCatalog.fileNameOf(AudioRemoteCatalog.RemoteSound(name = "a/b")),
        )
    }

    @Test
    fun `搜索 精确优先于前缀优先于包含`() {
        val a = AudioRemoteCatalog.RemoteSound(soundId = "a", name = "万箭齐发音效")
        val b = AudioRemoteCatalog.RemoteSound(soundId = "b", name = "查无此声", aliases = listOf("万箭齐发"))
        val c = AudioRemoteCatalog.RemoteSound(soundId = "c", name = "小万箭齐发")
        val list = listOf(a, b, c)
        val r = AudioRemoteCatalog.search(list, "万箭齐发")
        assertEquals(listOf("b", "a", "c"), r.map { it.soundId })
    }
}
