package io.legado.app.help.readaloud.playback

import io.legado.app.domain.model.readaloud.ReadAloudVoice

/** 播放计划只依赖这个窄接口；具体实现可替换，测试可注入假声源。 */
interface SpeechVoiceSource {
    suspend fun byTag(tag: String): ReadAloudVoice?
    suspend fun narrator(): ReadAloudVoice?
    suspend fun defaultDialog(gender: String, seed: Int): ReadAloudVoice?
}
