package com.grandma.rumorguard

import org.json.JSONArray
import org.json.JSONObject

/** Request bodies for the two-stage check: look at the screenshots, then verify claims online. */
object Prompts {
    private const val NAME = "{称呼}"

    private val LOOK_SYSTEM = """
你是“辟谣小助手”，帮一位不太会分辨真假的老人看微信视频号短视频。你会收到老人正在看的视频的几张手机截图（按时间先后）和屏幕上读到的文字。请判断这个视频会不会误导老人，只输出一个 JSON 对象，不要输出任何其他内容。

verdict 只能是下面之一：
- "rumor"：内容可能是假的或误导的。例如：偏方治病、某种食物能防癌治糖尿病、“吃了会致癌”之类的健康谣言；伪科学；编造或夸大的新闻、政策、补贴、养老金消息；旧闻当新闻；灾难事故谣言；“不转不是中国人”“转发保平安”之类。
- "ai"：画面或声音明显是 AI 生成、换脸或合成的。例如：人物动作或手指怪异、口型对不上、AI 做的“名人/专家/老人”讲话、AI 编的感人故事或“新闻”画面。
- "scam"：卖货或骗局。例如：推销保健品、药品、理财、收藏品，诱导加微信、私信、免费领、高回报，冒充专家名人带货，直播间低价诱导下单。
- "unverified"：有具体的事实性说法，但仅凭画面无法确定真假。
- "ok"：没发现问题。例如：唱歌跳舞、搞笑、生活记录、风景、做菜、宠物，或者没有明显错误的正常新闻和科普。

JSON 格式：
{"verdict": "...", "label": "8个字以内的大白话结论，像“喝醋软化血管是谣言”“AI做的假视频”“卖保健品的广告”这样", "speech": "对老人说的一句话，以“$NAME，”开头，不超过30个字，语气温和亲切", "reason": "用一两句大白话说明为什么，不超过60个字", "summary": "视频在讲什么，不超过60个字", "claims": ["视频里需要核实的具体说法，最多3条"], "needs_search": true 或 false, "search_query": "用来上网核实的搜索关键词，不超过30个字"}

要求：
1. 宁可说“没法证实”，也不要说“这是真的”。拿不准时用 "unverified"。
2. 视频里有健康功效、新闻事件、政策补贴、名人言论、科学结论等可以核实的说法时，needs_search 为 true；纯娱乐、明显 AI、明显广告时为 false。
3. 忽略截图里的手机状态栏、微信按钮和被涂黑的方块。
""".trim()

    private val CHECK_SYSTEM = """
你是“辟谣小助手”，帮老人核实短视频里的说法。请先联网搜索核实，优先相信权威来源：中国互联网联合辟谣平台、科普中国、新华网、人民网、央视新闻、国家卫健委等政府网站、丁香医生。然后只输出一个 JSON 对象，不要输出任何其他内容：
{"verdict": "rumor、ai、scam、unverified、ok 之一", "label": "8个字以内的大白话结论，像“喝醋软化血管是谣言”“AI做的假视频”“卖保健品的广告”这样", "speech": "对老人说的一句话，以“$NAME，”开头，不超过30个字，语气温和亲切", "reason": "用一两句大白话说明为什么，可以提权威来源怎么说，不超过60个字"}

判断标准：
- 权威来源辟过谣，或者和科学常识明显矛盾 → "rumor"
- 搜不到可靠来源证实 → "unverified"
- 权威来源证实，而且视频没有夸大 → "ok"
- 初步判断是 "ai" 或 "scam" 时，除非搜索结果明确推翻，否则保持不变。
宁可说“没法证实”，也不要说“这是真的”。
""".trim()

    fun look(cfg: AiConfig, images: List<String>, screenText: String, today: String): JSONObject {
        val intro = buildString {
            append("今天是").append(today).append("。")
            if (images.isNotEmpty()) append("下面是老人正在看的视频的").append(images.size).append("张截图，按时间先后排列。")
            append(if (screenText.isBlank()) "\n屏幕上没有读到文字。" else "\n屏幕上读到的文字：$screenText")
        }
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", intro))
        for (url in images) {
            content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
        }
        return base(cfg, cfg.visionModel).put(
            "messages",
            JSONArray()
                .put(JSONObject().put("role", "system").put("content", LOOK_SYSTEM.replace(NAME, cfg.callName)))
                .put(JSONObject().put("role", "user").put("content", content)),
        )
    }

    fun check(cfg: AiConfig, first: Verdict, screenText: String, today: String): JSONObject {
        val user = buildString {
            append("今天是").append(today).append("。\n")
            append("视频内容：").append(first.summary.ifEmpty { "（不清楚）" }).append('\n')
            if (first.claims.isNotEmpty()) {
                append("要核实的说法：\n")
                first.claims.forEachIndexed { i, claim -> append(i + 1).append(". ").append(claim).append('\n') }
            }
            if (first.searchQuery.isNotEmpty()) append("建议搜索：").append(first.searchQuery).append('\n')
            append("初步判断：").append(first.level.key).append("（").append(first.reason).append("）\n")
            if (screenText.isNotBlank()) append("屏幕文字：").append(screenText.take(400))
        }
        return base(cfg, cfg.checkModel)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", CHECK_SYSTEM.replace(NAME, cfg.callName)))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
            .put("enable_search", true)
            .put("search_options", JSONObject().put("forced_search", true).put("search_strategy", "turbo"))
    }

    private fun base(cfg: AiConfig, model: String): JSONObject {
        val body = JSONObject().put("model", model).put("temperature", 0.2)
        // DashScope's Qwen 3.x models think by default; thinking roughly triples latency.
        if (cfg.isDashScope) body.put("enable_thinking", cfg.deepThinking)
        // Thinking tokens may count against max_tokens, so only cap plain replies.
        if (!cfg.deepThinking) body.put("max_tokens", 800)
        return body
    }
}
