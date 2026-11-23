package io.legado.app.domain.gateway

import io.legado.app.domain.model.readaloud.ReadAloudVoice
import kotlinx.coroutines.flow.Flow

interface ReadAloudVoiceGateway {
    fun observeVoices(): Flow<List<ReadAloudVoice>>
    suspend fun getVoices(): List<ReadAloudVoice>
    suspend fun getEnabledVoices(): List<ReadAloudVoice>
    suspend fun getVoice(id: String): ReadAloudVoice?
    suspend fun upsertVoice(voice: ReadAloudVoice)
    suspend fun deleteVoice(voice: ReadAloudVoice)
}
