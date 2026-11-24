package io.legado.app.help.readaloud.analysis.v4

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.repository.RaCharacterRepository
import io.legado.app.data.repository.WordLibraryRepository
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaResolution
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class IdentityResolverTest {

    private lateinit var db: AppDatabase
    private lateinit var characters: RaCharacterRepository
    private lateinit var resolver: IdentityResolver

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val wordLibrary = WordLibraryRepository(context)
        characters = RaCharacterRepository(db, db.raCharacterDao, db.raBuildDao, wordLibrary)
        resolver = IdentityResolver(characters, wordLibrary)
    }

    @After
    fun tearDown() = db.close()

    private fun speaker(
        analysisId: String,
        localKey: String,
        primary: String,
        names: List<String>,
        chapter: Int,
    ) = RaLocalSpeaker(
        id = "$analysisId#$localKey",
        analysisId = analysisId,
        localKey = localKey,
        surfaceNames = (names + primary).distinct(),
        primaryName = primary,
        roleType = "核心",
        gender = "男",
        ageBand = "男青年",
    )

    @Test
    fun `linked speaker writes new alias so next chapter links back`() = runBlocking {
        val bookKey = "bk_test"
        val first = resolver.resolve(bookKey, 0, speaker("a1", "L1", "沈孤鸿", emptyList(), 0))
        val second = resolver.resolve(
            bookKey,
            4,
            speaker("a2", "L1", "沈孤鸿", listOf("沈七"), 4),
        )
        assertEquals(first.characterId, second.characterId)
        assertTrue(characters.aliasesOf(first.characterId).any { it.surface == "沈七" })

        val third = resolver.resolve(bookKey, 8, speaker("a3", "L1", "沈七", emptyList(), 8))
        assertEquals(first.characterId, third.characterId)
        assertEquals(1, characters.characters(bookKey).size)
    }

    @Test
    fun `generic names never trigger a link by themselves`() = runBlocking {
        val bookKey = "bk_generic"
        resolver.resolve(bookKey, 0, speaker("a1", "L1", "师兄", emptyList(), 0))
        resolver.resolve(bookKey, 1, speaker("a2", "L1", "师兄", emptyList(), 1))
        // 泛称不参与链接；V4-1 每章各自新建（路人），但不会因“泛称命中”误并到核心人物
        assertEquals(2, characters.characters(bookKey).size)
    }

    @Test
    fun `multiple candidates with same alias do not grow character count`() = runBlocking {
        val bookKey = "bk_multi"
        val a = characters.ensureCharacter(
            bookKey, "甲", "核心", "男", "男青年", 0, listOf("老三"), "ACTIVE", "ai", "inferred",
        )
        characters.ensureCharacter(
            bookKey, "乙", "核心", "男", "男青年", 1, listOf("老三"), "ACTIVE", "ai", "inferred",
        )
        repeat(5) { chapter ->
            val result = resolver.resolve(
                bookKey,
                chapter + 10,
                speaker("a${chapter + 10}", "L1", "老三", emptyList(), chapter + 10),
            )
            assertEquals(a.id, result.characterId)
            assertEquals(RaResolution.PROVISIONAL, result.resolution)
        }
        assertEquals(2, characters.characters(bookKey).size)
    }
}
