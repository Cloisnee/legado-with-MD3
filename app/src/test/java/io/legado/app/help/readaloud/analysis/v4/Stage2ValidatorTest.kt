package io.legado.app.help.readaloud.analysis.v4

import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.v4.RaEvidenceScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class Stage2ValidatorTest {

    private val paragraphs = listOf(
        CanonicalSpeechParagraph(
            index = 0,
            chapterPosition = 0,
            text = "沈孤鸿淡淡道：“青衫客正是沈七。”他转身离开。",
        ),
    )

    private fun validator() = Stage2Validator(
        paragraphs = paragraphs,
        chapterIndex = 99,
        prevText = "",
        nextText = "",
        isBareClassWord = { it == "师兄" || it == "店伙计" },
        isSpecialClassWord = { it == "系统" },
        log = {},
    )

    @Test
    fun `new format parses speakers mentions and evidence`() {
        val raw = """
            {"seqmap":{"1":"L1"},
             "speakers":[{"key":"L1","name":"沈孤鸿","aliases":["沈七"],"roletype":"核心",
               "gender":"男","age":"男青年","genderSource":"explicit","ageSource":"inferred"}],
             "mentions":[{"key":"M1","name":"林雪儿","aliases":[],"roletype":"核心","gender":"女","age":"少女"}],
             "evidence":[{"type":"ALIAS_EQUAL","subject":"L1","object":"沈七","ch":100,"p":1,
               "quote":"青衫客正是沈七"}]}
        """.trimIndent()
        val outcome = validator().validate(raw, 1)
        assertNotNull(outcome.data)
        val data = outcome.data!!
        assertEquals("L1", data.seqMap[1])
        assertEquals("沈孤鸿", data.speakers.single().name)
        assertEquals(listOf("沈七"), data.speakers.single().aliases)
        assertEquals("林雪儿", data.mentions.single().name)
        assertEquals(1, data.evidence.size)
        assertEquals(RaEvidenceScope.MAIN_TEXT, data.evidence.single().scope)
        assertTrue(data.evidence.single().start >= 0)
    }

    @Test
    fun `seqmap missing a sequence fails`() {
        val raw = """{"seqmap":{"1":"L1"},"speakers":[{"key":"L1","name":"沈孤鸿"}]}"""
        assertNull(validator().validate(raw, 2).data)
    }

    @Test
    fun `seqmap referencing an unknown key fails`() {
        val raw = """{"seqmap":{"1":"L9"},"speakers":[{"key":"L1","name":"沈孤鸿"}]}"""
        assertNull(validator().validate(raw, 1).data)
    }

    @Test
    fun `evidence whose quote is not in the chapter is dropped`() {
        val raw = """
            {"seqmap":{"1":"L1"},
             "speakers":[{"key":"L1","name":"沈孤鸿","aliases":[]}],
             "evidence":[{"type":"ALIAS_EQUAL","subject":"L1","object":"沈七","ch":100,"p":1,
               "quote":"这句原文不存在"}]}
        """.trimIndent()
        val data = validator().validate(raw, 1).data!!
        assertTrue(data.evidence.isEmpty())
    }

    @Test
    fun `N turns a line into narrator`() {
        val raw = """{"seqmap":{"1":"N","2":"L1"},"speakers":[{"key":"L1","name":"沈孤鸿"}]}"""
        val data = validator().validate(raw, 2).data!!
        assertEquals("N", data.seqMap[1])
        assertEquals("L1", data.seqMap[2])
    }

    @Test
    fun `old characters format is converted`() {
        val raw = """
            {"seqmap":{"01":"沈孤鸿"},
             "characters":[{"name":"沈孤鸿","aliases":"沈七","roletype":"核心","gender":"男","age":"男青年"}]}
        """.trimIndent()
        val data = validator().validate(raw, 1).data!!
        assertEquals("L1", data.seqMap[1])
        assertEquals("沈孤鸿", data.speakers.single().name)
        assertEquals(listOf("沈七"), data.speakers.single().aliases)
    }

    @Test
    fun `pronoun as main name is rejected`() {
        val raw = """{"seqmap":{"1":"L1"},"speakers":[{"key":"L1","name":"他"}]}"""
        assertNull(validator().validate(raw, 1).data)
    }

    @Test
    fun `bare class name is demoted to passerby and aliases cleared`() {
        val raw = """{"seqmap":{"1":"L1"},"speakers":[{"key":"L1","name":"师兄","aliases":["大师兄"]}]}"""
        val data = validator().validate(raw, 1).data!!
        assertEquals("路人", data.speakers.single().roleType)
        assertTrue(data.speakers.single().aliases.isEmpty())
    }

    @Test
    fun `same name without evidence keeps two speakers`() {
        val raw = """
            {"seqmap":{"1":"L1","2":"L2"},
             "speakers":[{"key":"L1","name":"沈七","aliases":[]},
                         {"key":"L2","name":"沈七","aliases":[]}]}
        """.trimIndent()
        val data = validator().validate(raw, 2).data!!
        assertEquals(2, data.speakers.size)
        assertEquals("L1", data.seqMap[1])
        assertEquals("L2", data.seqMap[2])
    }

    @Test
    fun `alias equal evidence merges and remaps seqmap`() {
        val raw = """
            {"seqmap":{"1":"L1","2":"L2"},
             "speakers":[{"key":"L1","name":"沈孤鸿","aliases":["沈七"]},
                         {"key":"L2","name":"沈七","aliases":[]}],
             "evidence":[{"type":"ALIAS_EQUAL","subject":"L1","object":"沈七","ch":100,"p":1,
               "quote":"青衫客正是沈七"}]}
        """.trimIndent()
        val data = validator().validate(raw, 2).data!!
        assertEquals(1, data.speakers.size)
        assertEquals("L1", data.seqMap[1])
        assertEquals("L1", data.seqMap[2])
    }

    @Test
    fun `seqmap with an extra key is rejected`() {
        val raw = """{"seqmap":{"1":"L1","2":"L1"},"speakers":[{"key":"L1","name":"沈孤鸿","aliases":[]}]}"""
        assertNull(validator().validate(raw, 1).data)
    }

    @Test
    fun `duplicate speaker key is rejected`() {
        val raw = """
            {"seqmap":{"1":"L1","2":"L1"},
             "speakers":[{"key":"L1","name":"沈孤鸿","aliases":[]},
                         {"key":"L1","name":"沈七","aliases":[]}]}
        """.trimIndent()
        assertNull(validator().validate(raw, 2).data)
    }

    @Test
    fun `context evidence with wrong chapter is dropped`() {
        val raw = """
            {"seqmap":{"1":"L1"},
             "speakers":[{"key":"L1","name":"沈孤鸿","aliases":[]}],
             "evidence":[{"type":"ALIAS_EQUAL","subject":"L1","object":"沈七","ch":98,"p":1,
               "quote":"沈七即沈孤鸿"}]}
        """.trimIndent()
        val v = Stage2Validator(
            paragraphs = paragraphs,
            chapterIndex = 99,
            prevText = "沈七即沈孤鸿",
            nextText = "",
            prevChapter = 99,
            nextChapter = null,
            isBareClassWord = { it == "师兄" },
            isSpecialClassWord = { it == "系统" },
            log = {},
        )
        val data = v.validate(raw, 1).data!!
        assertTrue(data.evidence.isEmpty())
    }
}
