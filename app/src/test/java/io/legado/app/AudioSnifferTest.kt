package io.legado.app

import com.github.jing332.common.audio.AudioSniffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AudioSniffer 回归（白噪音事故护栏）：
 *  - webm/ogg/amr 等压缩容器必须**直通**（曾被误当裸 PCM 包 WAV 头 → 播放滋滋白噪音）；
 *  - 仅未识别字节才按裸 PCM 包 WAV 头（采样率取条目声明值）。
 */
class AudioSnifferTest {

    private fun wavBytes() = "RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray() + ByteArray(64)
    private fun mp3SyncBytes() =
        byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x64.toByte(), 0xC4.toByte()) + ByteArray(64)

    private fun mp3Id3Bytes() = "ID3".toByteArray() + ByteArray(64)
    private fun webmBytes() = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(64)
    private fun oggBytes() = "OggS".toByteArray() + ByteArray(64)
    private fun m4aBytes() = ByteArray(4) + "ftyp".toByteArray() + ByteArray(64)
    private fun flacBytes() = "fLaC".toByteArray() + ByteArray(64)
    private fun amrBytes() = "#!AMR".toByteArray() + ByteArray(64)
    private fun pcmBytes() = ByteArray(128) { (it % 64).toByte() }

    @Test
    fun sniffCoversKnownContainers() {
        assertEquals(AudioSniffer.AudioKind.WAV, AudioSniffer.sniff(wavBytes()))
        assertEquals(AudioSniffer.AudioKind.MP3, AudioSniffer.sniff(mp3SyncBytes()))
        assertEquals(AudioSniffer.AudioKind.MP3, AudioSniffer.sniff(mp3Id3Bytes()))
        assertEquals(AudioSniffer.AudioKind.WEBM, AudioSniffer.sniff(webmBytes()))
        assertEquals(AudioSniffer.AudioKind.OGG, AudioSniffer.sniff(oggBytes()))
        assertEquals(AudioSniffer.AudioKind.MP4, AudioSniffer.sniff(m4aBytes()))
        assertEquals(AudioSniffer.AudioKind.FLAC, AudioSniffer.sniff(flacBytes()))
        assertEquals(AudioSniffer.AudioKind.AMR, AudioSniffer.sniff(amrBytes()))
    }

    @Test
    fun unknownBytesAndEmptyAreUnknownKind() {
        assertEquals(AudioSniffer.AudioKind.UNKNOWN, AudioSniffer.sniff(pcmBytes()))
        assertEquals(AudioSniffer.AudioKind.UNKNOWN, AudioSniffer.sniff(ByteArray(0)))
    }

    /** 白噪音事故核心回归：webm/ogg 直通，绝不包装 */
    @Test
    fun compressedContainersPassThroughUnwrapped() {
        listOf(webmBytes(), oggBytes(), mp3SyncBytes(), mp3Id3Bytes(), wavBytes(), m4aBytes(), flacBytes(), amrBytes())
            .forEach { bytes ->
                val n = AudioSniffer.normalizeForPlayback(bytes, 24000)
                assertFalse("容器格式不得被包 WAV 头", n.wrappedPcm)
                assertSame("容器格式应原样直通（同实例）", bytes, n.bytes)
            }
    }

    @Test
    fun unknownBytesWrappedAsPcmWav() {
        val pcm = pcmBytes()
        val n = AudioSniffer.normalizeForPlayback(pcm, 24000)
        assertTrue(n.wrappedPcm)
        assertEquals(44 + pcm.size, n.bytes.size)
        // RIFF/WAVE 头 + 采样率小端（偏移 24..27）+ data 大小（偏移 40..43）
        assertEquals("RIFF", String(n.bytes, 0, 4))
        assertEquals("WAVE", String(n.bytes, 8, 4))
        val sampleRate = n.bytes.leIntAt(24)
        assertEquals(24000, sampleRate)
        assertEquals(pcm.size, n.bytes.leIntAt(40))
        for (i in pcm.indices) {
            assertEquals(pcm[i], n.bytes[44 + i])
        }
    }

    @Test
    fun extNameMapping() {
        assertEquals("wav", AudioSniffer.extName(AudioSniffer.AudioKind.WAV))
        assertEquals("mp3", AudioSniffer.extName(AudioSniffer.AudioKind.MP3))
        assertEquals("webm", AudioSniffer.extName(AudioSniffer.AudioKind.WEBM))
        assertEquals("bin", AudioSniffer.extName(AudioSniffer.AudioKind.UNKNOWN))
    }

    private fun ByteArray.leIntAt(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
                ((this[offset + 1].toInt() and 0xFF) shl 8) or
                ((this[offset + 2].toInt() and 0xFF) shl 16) or
                ((this[offset + 3].toInt() and 0xFF) shl 24)
}
