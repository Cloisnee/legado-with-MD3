package io.legado.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/** B33.3c-附 · 音频合成端点拼接：BaseUrl 带/不带版本段均归一，防 `v1/v1` 双段 404。 */
class AudioSynthPlatformsTest {

    @Test
    fun `endpoint base 带 v1 后缀不再重复`() {
        assertEquals(
            "https://api.stepfun.com/v1/audio/generate",
            AudioSynthPlatforms.endpoint("https://api.stepfun.com/v1", "/v1/audio/generate"),
        )
        assertEquals(
            "https://api.senseaudio.cn/v1/sound-effects/generations",
            AudioSynthPlatforms.endpoint("https://api.senseaudio.cn/v1", "/v1/sound-effects/generations"),
        )
        assertEquals(
            "https://api.senseaudio.cn/v1/music/song/pending/abc",
            AudioSynthPlatforms.endpoint("https://api.senseaudio.cn/v1", "/v1/music/song/pending/abc"),
        )
    }

    @Test
    fun `endpoint base 不带 v1 正常拼接`() {
        assertEquals(
            "https://api.stepfun.com/v1/audio/generate",
            AudioSynthPlatforms.endpoint("https://api.stepfun.com", "/v1/audio/generate"),
        )
        assertEquals(
            "https://api.senseaudio.cn/v1/audio/generate",
            AudioSynthPlatforms.endpoint("https://api.senseaudio.cn", "/v1/audio/generate"),
        )
    }

    @Test
    fun `endpoint 路径版本高于 base 版本（music v2）`() {
        assertEquals(
            "https://api.senseaudio.cn/v2/music/song/create",
            AudioSynthPlatforms.endpoint("https://api.senseaudio.cn/v1", "/v2/music/song/create"),
        )
    }

    @Test
    fun `endpoint 尾斜杠与多段路径 base`() {
        assertEquals(
            "https://api.stepfun.com/v1/audio/generate",
            AudioSynthPlatforms.endpoint("https://api.stepfun.com/v1/", "v1/audio/generate"),
        )
        assertEquals(
            "https://proxy.example.com/api/v1/audio/generate",
            AudioSynthPlatforms.endpoint("https://proxy.example.com/api/v1", "/v1/audio/generate"),
        )
    }
}