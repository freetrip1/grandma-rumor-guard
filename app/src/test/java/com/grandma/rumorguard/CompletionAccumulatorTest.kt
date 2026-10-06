package com.grandma.rumorguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionAccumulatorTest {
    @Test
    fun joinsStreamedContentSkippingThinkingAndNullDeltas() {
        val acc = CompletionAccumulator()
        val lines = listOf(
            """data: {"choices":[{"delta":{"role":"assistant","content":null}}]}""",
            """data: {"choices":[{"delta":{"reasoning_content":"想一想","content":""}}]}""",
            "",
            """data: {"choices":[{"delta":{"content":"{\"verdict\""}}]}""",
            """data: {"choices":[{"delta":{"content":":\"ok\"}"}}]}""",
        )
        lines.forEach { assertFalse(acc.feed(it)) }

        assertTrue(acc.feed("data: [DONE]"))
        assertEquals("""{"verdict":"ok"}""", acc.result())
        assertNull(acc.error)
    }

    @Test
    fun streamedErrorEndsTheResponse() {
        val acc = CompletionAccumulator()

        assertTrue(acc.feed("""data: {"error":{"message":"Arrearage","code":"Arrearage"}}"""))
        assertEquals("Arrearage", acc.error)
    }

    @Test
    fun acceptsPlainJsonWhenServerIgnoresStreaming() {
        val acc = CompletionAccumulator()
        listOf("{", """  "choices": [{"message": {"role": "assistant", "content": "你好"}}]""", "}")
            .forEach { assertFalse(acc.feed(it)) }

        assertEquals("你好", acc.result())
    }
}
