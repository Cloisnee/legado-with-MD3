package io.legado.app.domain.usecase

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.repository.RaCharacterRepository
import io.legado.app.data.repository.WordLibraryRepository
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.v4.RaIds
import io.legado.app.domain.model.readaloud.v4.RaSegment
import io.legado.app.domain.model.readaloud.v4.RaVoiceProfile
import io.legado.app.domain.model.readaloud.v4.RaVoiceProfileKind
import io.legado.app.help.readaloud.playback.SpeechVoiceSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class BuildSpeechPlanUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var characters: RaCharacterRepository
    private lateinit var useCase: BuildSpeechPlanUseCase
    private val narrator = ReadAloudVoice("narrator", "tts_server", "e", "narrator", "旁白")
    private val dialog = ReadAloudVoice("dialog", "tts_server", "e", "duihuaA", "默认对话")

    private inner class FakeVoices : SpeechVoiceSource {
        override suspend fun byTag(tag: String): ReadAloudVoice? =
            if (tag == "tagA") ReadAloudVoice("a", "tts_server", "e", "tagA", "tagA") else null

        override suspend fun narrator(): ReadAloudVoice = narrator
        override suspend fun defaultDialog(gender: String, seed: Int): ReadAloudVoice = dialog
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        characters = RaCharacterRepository(
            db, db.raCharacterDao, db.raBuildDao, WordLibraryRepository(context),
        )
        useCase = BuildSpeechPlanUseCase(characters, FakeVoices())
    }

    @After
    fun tearDown() = db.close()

    private fun segment(characterId: String?, paragraph: Int = 0, role: SpeechRoleType = SpeechRoleType.Character) =
        RaSegment(
            id = "s$paragraph-$role",
            analysisId = "a",
            bookKey = BOOK,
            chapterIndex = 3,
            paragraphIndex = paragraph,
            start = 0,
            end = 1,
            chapterPosition = paragraph,
            text = "你好",
            roleType = role,
            characterId = characterId,
            source = SpeechResolutionSource.Ai,
        )

    @Test
    fun `character voice comes from profile and rename does not change it`() = runBlocking {
        val character = characters.ensureCharacter(
            BOOK, "沈孤鸿", "核心", "男", "男青年", 0, emptyList(), "ACTIVE", "ai", "inferred",
        )
        characters.saveVoiceProfile(
            RaVoiceProfile(
                id = RaIds.voiceProfile(), bookKey = BOOK, characterId = character.id,
                kind = RaVoiceProfileKind.DEFAULT, label = "默认", voiceTag = "tagA",
            ),
        )
        val before = useCase(BOOK, listOf(segment(character.id))).single()
        assertEquals("tagA", before.voice?.displayName)
        characters.updateDisplayName(character.id, "新名字")
        val after = useCase(BOOK, listOf(segment(character.id))).single()
        assertEquals("tagA", after.voice?.displayName)
        assertEquals("新名字", after.characterDisplayName)
    }

    @Test
    fun `missing character uses one stable default dialog for the whole chapter`() = runBlocking {
        val plan = useCase(BOOK, listOf(segment(null, 0), segment(null, 1)))
        assertSame(plan[0].voice, plan[1].voice)
        assertEquals("默认对话", plan[0].voice?.displayName)
    }

    @Test
    fun `narrator uses narrator voice`() = runBlocking {
        val plan = useCase(BOOK, listOf(segment(null, role = SpeechRoleType.Narrator)))
        assertEquals("旁白", plan.single().voice?.displayName)
    }

    @Test
    fun `merge redirect resolves to target profile`() = runBlocking {
        val from = characters.ensureCharacter(
            BOOK, "甲", "核心", "男", "男青年", 0, emptyList(), "ACTIVE", "ai", "inferred",
        )
        val to = characters.ensureCharacter(
            BOOK, "乙", "核心", "男", "男青年", 1, emptyList(), "ACTIVE", "ai", "inferred",
        )
        characters.saveVoiceProfile(
            RaVoiceProfile(
                id = RaIds.voiceProfile(), bookKey = BOOK, characterId = to.id,
                kind = RaVoiceProfileKind.DEFAULT, label = "默认", voiceTag = "tagA",
            ),
        )
        characters.mergeCharacters(from.id, to.id, "test")
        val plan = useCase(BOOK, listOf(segment(from.id))).single()
        assertEquals("tagA", plan.voice?.displayName)
        assertEquals("乙", plan.characterDisplayName)
    }

    companion object {
        private const val BOOK = "bk_test"
    }
}
