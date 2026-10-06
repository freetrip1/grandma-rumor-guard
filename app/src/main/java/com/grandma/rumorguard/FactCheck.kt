package com.grandma.rumorguard

import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException

/**
 * The two-stage check without Android threading: look at the screenshots, then — for checkable
 * claims, when web search is available — verify them with DashScope web search.
 */
object FactCheck {
    /**
     * Returns the final verdict. Throws [IOException] only when the vision stage fails, since there
     * is nothing to show without it. A failed verification keeps the vision verdict (shown without
     * the "已上网核实" note): a warning that wasn't double-checked beats no warning. The reason
     * goes to [onVerifyFailed].
     */
    fun judge(
        cfg: AiConfig,
        images: List<String>,
        screenText: String,
        today: String,
        request: (JSONObject) -> String,
        onPreliminary: (Verdict) -> Unit,
        onVerifyFailed: (String) -> Unit,
    ): Verdict {
        val firstReply = request(Prompts.look(cfg, images, screenText, today))
        val first = VerdictParser.parse(firstReply, cfg.callName)
            ?: throw ApiException("AI 的回答看不懂：${firstReply.take(80)}")
        if (!(cfg.webSearch && cfg.isDashScope && first.needsSearch)) return first
        onPreliminary(first)
        val secondReply = try {
            request(Prompts.check(cfg, first, screenText, today))
        } catch (e: InterruptedIOException) {
            onVerifyFailed("上网核实超时")
            return first
        } catch (e: IOException) {
            onVerifyFailed("上网核实失败：${e.message ?: "网络出错了"}")
            return first
        }
        val checked = VerdictParser.parse(secondReply, cfg.callName)
        if (checked == null) {
            onVerifyFailed("上网核实的回答看不懂：${secondReply.take(80)}")
            return first
        }
        return checked.copy(summary = first.summary, claims = first.claims, verified = true)
    }
}
