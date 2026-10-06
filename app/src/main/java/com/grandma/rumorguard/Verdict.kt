package com.grandma.rumorguard

private const val WHITE = 0xFFFFFFFF.toInt()
private const val INK = 0xFF212121.toInt()

/** How a video was judged. [warn] levels are announced aloud and trigger the forward guard. */
enum class Level(
    val key: String,
    val emoji: String,
    val defaultLabel: String,
    /** The one glance an elderly viewer needs: can I watch/trust this or not. */
    val headline: String,
    val background: Int,
    val foreground: Int,
    val warn: Boolean,
) {
    PENDING("pending", "🔍", "正在看…", "", 0xFF546E7A.toInt(), WHITE, false),
    OK("ok", "✅", "没发现问题", "可以看", 0xFF2E7D32.toInt(), WHITE, false),
    UNVERIFIED("unverified", "❓", "说法没法证实", "先别信", 0xFFF9A825.toInt(), INK, true),
    RUMOR("rumor", "⚠️", "可能是假消息", "是假的", 0xFFC62828.toInt(), WHITE, true),
    AI("ai", "🤖", "AI合成的视频", "AI假的", 0xFF6A1B9A.toInt(), WHITE, true),
    SCAM("scam", "🛑", "小心卖货骗局", "别上当", 0xFFD84315.toInt(), WHITE, true),
    ERROR("error", "💤", "暂时看不了", "暂时看不了", 0xFF757575.toInt(), WHITE, false);

    /** Readable accent for text drawn on a white card (yellow is unreadable on white). */
    val accent: Int get() = if (this == UNVERIFIED) 0xFFE65100.toInt() else background

    companion object {
        /** Maps the model's verdict key. Anything unrecognised becomes UNVERIFIED, never OK. */
        fun fromModel(key: String?): Level = when (key?.trim()?.lowercase()) {
            "ok" -> OK
            "rumor", "fake", "misleading" -> RUMOR
            "ai" -> AI
            "scam", "ad" -> SCAM
            else -> UNVERIFIED
        }
    }
}

data class Verdict(
    val level: Level,
    val label: String,
    val speech: String,
    val reason: String,
    val summary: String = "",
    val claims: List<String> = emptyList(),
    val needsSearch: Boolean = false,
    val searchQuery: String = "",
    /** True once the claims were checked with web search. */
    val verified: Boolean = false,
)

/** Fixed sentences spoken to grandma. [name] is how she is addressed, e.g. 奶奶. */
object Phrases {
    fun defaultSpeech(level: Level, name: String): String = when (level) {
        Level.OK -> "${name}，这个视频没看出问题。"
        Level.RUMOR -> "${name}，这个视频可能是假的，别信也别转发。"
        Level.AI -> "${name}，这个视频是AI合成的，不是真人真事。"
        Level.SCAM -> "${name}，这是卖东西的视频，小心上当。"
        else -> "${name}，这个视频说的事没法证实，先别转发。"
    }

    fun forwardWarning(level: Level, name: String): String = when (level) {
        Level.RUMOR -> "${name}，这个视频可能是假的，先别转发到群里哦。"
        Level.AI -> "${name}，这个视频是AI合成的，先别转发哦。"
        Level.SCAM -> "${name}，这个视频是卖东西的，小心上当，先别转发哦。"
        else -> "${name}，这个视频说的事还没法证实，先别转发哦。"
    }
}
