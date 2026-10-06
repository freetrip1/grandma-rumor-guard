package com.grandma.rumorguard

import org.json.JSONException
import org.json.JSONObject

/** Turns a model reply into a [Verdict], tolerating code fences, prose, <think> blocks and broken JSON. */
object VerdictParser {
    /** Models overshoot the requested 8 characters; the badge ellipsizes, this only bounds garbage. */
    private const val MAX_LABEL = 16
    private const val MAX_SPEECH = 60
    private const val MAX_TEXT = 150
    private val THINK = Regex("<think>[\\s\\S]*?</think>")
    private val VERDICT_FIELD = Regex("\"verdict\"\\s*:\\s*\"([A-Za-z]+)\"")

    fun parse(reply: String, name: String): Verdict? {
        val text = reply.replace(THINK, "")
        val obj = extractJsonObject(text)?.let {
            try {
                JSONObject(it)
            } catch (e: JSONException) {
                null
            }
        }
        if (obj == null || !obj.has("verdict")) {
            // Models sometimes put unescaped quotes inside strings; salvage the verdict alone.
            val key = VERDICT_FIELD.find(text)?.groupValues?.get(1) ?: return null
            val level = Level.fromModel(key)
            return Verdict(level, level.defaultLabel, Phrases.defaultSpeech(level, name), "")
        }
        val level = Level.fromModel(obj.str("verdict"))
        val claims = obj.optJSONArray("claims")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).trim().ifEmpty { null } }.take(3)
        } ?: emptyList()
        return Verdict(
            level = level,
            label = obj.str("label").orEmpty().trim().take(MAX_LABEL).ifEmpty { level.defaultLabel },
            speech = obj.str("speech").orEmpty().trim().take(MAX_SPEECH)
                .ifEmpty { Phrases.defaultSpeech(level, name) },
            reason = obj.str("reason").orEmpty().trim().take(MAX_TEXT),
            summary = obj.str("summary").orEmpty().trim().take(MAX_TEXT),
            claims = claims,
            needsSearch = obj.optBoolean("needs_search", false),
            searchQuery = obj.str("search_query").orEmpty().trim().take(MAX_TEXT),
        )
    }

    /** First balanced `{...}` in [text]; braces inside JSON strings are ignored. */
    fun extractJsonObject(text: String): String? {
        var start = text.indexOf('{')
        while (start >= 0) {
            val end = matchingBrace(text, start)
            if (end > 0) return text.substring(start, end + 1)
            start = text.indexOf('{', start + 1)
        }
        return null
    }

    private fun matchingBrace(s: String, open: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return -1
    }
}

/** String value or null when the key is absent or JSON null (org.json would return "null"). */
internal fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)
