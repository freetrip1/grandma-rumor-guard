package com.grandma.rumorguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class FactCheckTest {
    private val cfg = AiConfig(
        apiKey = "sk-test",
        baseUrl = Prefs.DEFAULT_BASE_URL,
        visionModel = "vision",
        checkModel = "check",
        webSearch = true,
        deepThinking = false,
        callName = "奶奶",
    )
    private val visionRumor =
        """{"verdict":"rumor","label":"生鸡蛋降压是谣言","summary":"讲吃生鸡蛋降压","claims":["吃生鸡蛋能降血压"],"needs_search":true}"""

    private class Outcome(
        val verdict: Verdict,
        val requests: Int,
        val preliminary: List<Verdict>,
        val verifyErrors: List<String>,
    )

    /** Runs a check whose n-th request is answered by replies[n]; an extra request fails the test. */
    private fun judge(config: AiConfig, vararg replies: () -> String): Outcome {
        var requests = 0
        val preliminary = mutableListOf<Verdict>()
        val verifyErrors = mutableListOf<String>()
        val verdict = FactCheck.judge(
            config, emptyList(), "每天吃3个生鸡蛋，降血压比吃药还管用", "2026-10-04",
            request = { replies[requests++]() },
            onPreliminary = { preliminary += it },
            onVerifyFailed = { verifyErrors += it },
        )
        return Outcome(verdict, requests, preliminary, verifyErrors)
    }

    @Test
    fun failedVerificationKeepsTheVisionWarning() {
        val failures = listOf<() -> String>(
            { throw IOException("请求太频繁或额度用完了（429）") },
            { throw SocketTimeoutException("Read timed out") },
            { "抱歉，我没搜到相关信息。" },
        )
        for (failure in failures) {
            val out = judge(cfg, { visionRumor }, failure)

            assertEquals(Level.RUMOR, out.verdict.level)
            assertFalse(out.verdict.verified)
            assertEquals(listOf(Level.RUMOR), out.preliminary.map { it.level })
            assertEquals(1, out.verifyErrors.size)
        }
    }

    @Test
    fun verificationDecidesAndKeepsTheVideoSummary() {
        val out = judge(cfg, { visionRumor }, { """{"verdict":"unverified","label":"查不到可靠来源"}""" })

        assertEquals(Level.UNVERIFIED, out.verdict.level)
        assertTrue(out.verdict.verified)
        assertEquals("讲吃生鸡蛋降压", out.verdict.summary)
        assertTrue(out.verifyErrors.isEmpty())
    }

    @Test
    fun noSecondRequestWhenSearchIsNotNeededOrUnavailable() {
        val cases = listOf(
            cfg to """{"verdict":"ok","needs_search":false}""",
            cfg.copy(webSearch = false) to visionRumor,
            cfg.copy(baseUrl = "https://api.openai.com/v1") to visionRumor,
        )
        for ((config, reply) in cases) {
            val out = judge(config, { reply })

            assertEquals(1, out.requests)
            assertTrue(out.preliminary.isEmpty())
        }
    }
}
