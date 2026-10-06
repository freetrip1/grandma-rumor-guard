package com.grandma.rumorguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictParserTest {
    @Test
    fun parsesFencedJsonSurroundedByProse() {
        val reply = """
            好的，结论如下：
            ```json
            {"verdict":"rumor","label":"健康谣言","speech":"奶奶，这是谣言。","reason":"生鸡蛋不能降血压",
             "summary":"讲吃生鸡蛋降压","claims":["吃生鸡蛋能降血压",""],"needs_search":true,"search_query":"生鸡蛋 降血压"}
            ```
            希望有帮助。
        """.trimIndent()

        val v = VerdictParser.parse(reply, "奶奶")!!

        assertEquals(Level.RUMOR, v.level)
        assertEquals("健康谣言", v.label)
        assertEquals("奶奶，这是谣言。", v.speech)
        assertEquals(listOf("吃生鸡蛋能降血压"), v.claims)
        assertTrue(v.needsSearch)
        assertEquals("生鸡蛋 降血压", v.searchQuery)
    }

    @Test
    fun bracesInsideStringsDoNotEndTheObject() {
        val v = VerdictParser.parse("""{"verdict":"ok","reason":"标题里有 {奇怪} 的 } 符号"}""", "奶奶")!!

        assertEquals(Level.OK, v.level)
        assertEquals("标题里有 {奇怪} 的 } 符号", v.reason)
    }

    @Test
    fun ignoresDraftJsonInsideThinkBlock() {
        val reply = """<think>先写个草稿 {"verdict":"ok"}</think>{"verdict":"scam","label":"卖保健品"}"""

        assertEquals(Level.SCAM, VerdictParser.parse(reply, "奶奶")!!.level)
    }

    @Test
    fun unrecognisedVerdictIsUnverifiedNeverOk() {
        assertEquals(Level.UNVERIFIED, VerdictParser.parse("""{"verdict":"true"}""", "奶奶")!!.level)
        assertEquals(Level.UNVERIFIED, VerdictParser.parse("""{"verdict":"real"}""", "奶奶")!!.level)
    }

    @Test
    fun missingTextFieldsFallBackToDefaultsAddressingHer() {
        val v = VerdictParser.parse("""{"verdict":"ai","label":"","speech":null}""", "外婆")!!

        assertEquals(Level.AI.defaultLabel, v.label)
        assertTrue(v.speech, v.speech.startsWith("外婆，"))
    }

    @Test
    fun unescapedQuotesStillYieldTheVerdict() {
        val reply = """{"verdict": "rumor", "speech": "奶奶，"转发保平安"是假的"}"""

        val v = VerdictParser.parse(reply, "奶奶")!!

        assertEquals(Level.RUMOR, v.level)
        assertEquals(Phrases.defaultSpeech(Level.RUMOR, "奶奶"), v.speech)
    }

    @Test
    fun replyWithoutVerdictIsRejected() {
        assertNull(VerdictParser.parse("抱歉，我无法判断这个视频。", "奶奶"))
        assertNull(VerdictParser.parse("""{"label":"不知道"}""", "奶奶"))
    }
}
