package com.grandma.rumorguard

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL

class ApiException(message: String) : IOException(message)

/**
 * One OpenAI-compatible `/chat/completions` request. Always streams: DashScope rejects
 * non-streamed web search on multimodal models. [cancel] may be called from any thread.
 */
class ChatCall(private val baseUrl: String, private val apiKey: String) {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    fun execute(body: JSONObject, readTimeoutMs: Int): String {
        body.put("stream", true)
        val conn = URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        connection = conn
        try {
            if (cancelled) throw InterruptedIOException("cancelled")
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "text/event-stream, application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw ApiException(describeHttpError(code, err))
            }
            val acc = CompletionAccumulator()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (acc.feed(line)) break
                }
            }
            if (cancelled) throw InterruptedIOException("cancelled")
            acc.error?.let { throw ApiException("AI 服务出错：$it") }
            return acc.result()
        } finally {
            conn.disconnect()
        }
    }
}

fun describeHttpError(code: Int, body: String): String {
    val message = try {
        JSONObject(body).optJSONObject("error")?.str("message")
    } catch (e: JSONException) {
        null
    } ?: body.take(200)
    return when (code) {
        401, 403 -> "AI 密钥不对或没开通（$code）：$message"
        404 -> "接口地址或模型名不对（404）：$message"
        429 -> "请求太频繁或额度用完了（429）：$message"
        else -> "AI 服务出错（$code）：$message"
    }
}

/** Collects assistant text from a chat completion response, streamed (SSE) or plain JSON. */
class CompletionAccumulator {
    private val content = StringBuilder()
    private val plainBody = StringBuilder()
    private var sawStream = false

    var error: String? = null
        private set

    /** Feeds one response line; returns true once the response is complete. */
    fun feed(line: String): Boolean {
        if (!line.startsWith("data:")) {
            if (!sawStream) plainBody.append(line).append('\n')
            return false
        }
        sawStream = true
        val data = line.substring(5).trim()
        if (data == "[DONE]") return true
        val obj = try {
            JSONObject(data)
        } catch (e: JSONException) {
            return false
        }
        if (readError(obj)) return true
        val choices = obj.optJSONArray("choices") ?: return false
        for (i in 0 until choices.length()) {
            // reasoning_content (thinking) is deliberately ignored.
            choices.optJSONObject(i)?.optJSONObject("delta")?.str("content")?.let(content::append)
        }
        return false
    }

    /** Final assistant text. Falls back to a non-streamed body when the server ignored `stream`. */
    fun result(): String {
        if (sawStream || plainBody.isBlank()) return content.toString()
        val obj = try {
            JSONObject(plainBody.toString())
        } catch (e: JSONException) {
            return ""
        }
        if (readError(obj)) return ""
        return obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.str("content").orEmpty()
    }

    private fun readError(obj: JSONObject): Boolean {
        if (obj.isNull("error")) return false
        val err = obj.get("error") // present and non-null: isNull() was false
        error = (err as? JSONObject)?.str("message") ?: err.toString()
        return true
    }
}
