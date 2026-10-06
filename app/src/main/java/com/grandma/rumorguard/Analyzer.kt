package com.grandma.rumorguard

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * Runs one [FactCheck] at a time on a background thread. Starting a new analysis cancels the
 * previous one. Callbacks arrive on the main thread, and never for a cancelled analysis.
 */
class Analyzer(private val prefs: Prefs) {
    interface Listener {
        /** Vision result while web verification is still running. */
        fun onPreliminary(verdict: Verdict)
        /** Web verification failed; [onFinal] follows with the vision verdict. */
        fun onVerifyFailed(message: String)
        fun onFinal(verdict: Verdict)
        fun onError(message: String)
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active: Run? = null

    private inner class Run(val listener: Listener) {
        @Volatile var cancelled = false
        @Volatile var call: ChatCall? = null

        fun cancel() {
            cancelled = true
            call?.cancel()
        }

        fun post(block: Listener.() -> Unit) {
            main.post { if (!cancelled) listener.block() }
        }
    }

    fun analyze(images: List<String>, screenText: String, listener: Listener) {
        cancel()
        val run = Run(listener)
        active = run
        val cfg = prefs.aiConfig()
        executor.execute { execute(run, cfg, images, screenText) }
    }

    fun cancel() {
        active?.cancel()
        active = null
    }

    fun shutdown() {
        cancel()
        executor.shutdownNow()
    }

    private fun execute(run: Run, cfg: AiConfig, images: List<String>, screenText: String) {
        if (run.cancelled) return
        try {
            val verdict = FactCheck.judge(
                cfg, images, screenText, LocalDate.now().toString(),
                request = { body -> request(run, cfg, body) },
                onPreliminary = { first -> run.post { onPreliminary(first) } },
                onVerifyFailed = { message -> run.post { onVerifyFailed(message) } },
            )
            run.post { onFinal(verdict) }
        } catch (e: InterruptedIOException) {
            if (!run.cancelled) run.post { onError("AI 回复太慢了，稍后再试") }
        } catch (e: IOException) {
            if (!run.cancelled) run.post { onError(e.message ?: "网络出错了") }
        }
    }

    private fun request(run: Run, cfg: AiConfig, body: JSONObject): String {
        val call = ChatCall(cfg.baseUrl, cfg.apiKey)
        run.call = call
        if (run.cancelled) throw InterruptedIOException("cancelled")
        return call.execute(body, READ_TIMEOUT_MS)
    }

    private companion object {
        const val READ_TIMEOUT_MS = 60_000
    }
}
