package com.grandma.rumorguard

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Immutable settings for one analysis run. */
data class AiConfig(
    val apiKey: String,
    val baseUrl: String,
    val visionModel: String,
    val checkModel: String,
    val webSearch: Boolean,
    val deepThinking: Boolean,
    val callName: String,
) {
    /** Alibaba Cloud Bailian (DashScope); the only provider whose web-search parameters we send. */
    val isDashScope: Boolean get() = baseUrl.contains("aliyuncs.com")
}

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey by string("api_key", "")
    var baseUrl by string("base_url", DEFAULT_BASE_URL)
    var visionModel by string("vision_model", DEFAULT_MODEL)
    var checkModel by string("check_model", DEFAULT_MODEL)
    var webSearch by boolean("web_search", true)
    var deepThinking by boolean("deep_thinking", false)
    var callName by string("call_name", "奶奶")
    var speakMode by int("speak_mode", SPEAK_WARNINGS)
    var slowSpeech by boolean("slow_speech", true)
    var dailyLimit by int("daily_limit", 600)
    /** Substring of WeChat's 视频号 activity class names ("finder" is 视频号's internal name). */
    var finderKeyword by string("finder_keyword", "finder")
    /** Also watch WeChat mini programs (小程序) — many short-video/drama apps grandma uses live there. */
    var watchMiniProgram by boolean("watch_miniprogram", true)
    /** Watch any app (抖音/快手/others) when a video is actually playing — not just WeChat. */
    var watchAllApps by boolean("watch_all_apps", true)
    var overlayX by int("overlay_x", -1)
    var overlayY by int("overlay_y", -1)

    val speechRate: Float get() = if (slowSpeech) 0.8f else 1.0f

    fun aiConfig() = AiConfig(
        apiKey = apiKey.trim(),
        baseUrl = baseUrl.trim().ifEmpty { DEFAULT_BASE_URL },
        visionModel = visionModel.trim().ifEmpty { DEFAULT_MODEL },
        checkModel = checkModel.trim().ifEmpty { DEFAULT_MODEL },
        webSearch = webSearch,
        deepThinking = deepThinking,
        callName = callName.trim().ifEmpty { "奶奶" },
    )

    /** Counts one paid analysis against today's limit; false when the limit is reached. */
    fun tryConsumeAnalysis(): Boolean {
        val used = usedToday
        if (used >= dailyLimit) return false
        sp.edit().putString(KEY_DAY, LocalDate.now().toString()).putInt(KEY_USED, used + 1).apply()
        return true
    }

    val usedToday: Int
        get() = if (sp.getString(KEY_DAY, null) == LocalDate.now().toString()) sp.getInt(KEY_USED, 0) else 0

    private fun string(key: String, def: String) = object : ReadWriteProperty<Prefs, String> {
        override fun getValue(thisRef: Prefs, property: KProperty<*>) = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Prefs, property: KProperty<*>, value: String) =
            sp.edit().putString(key, value).apply()
    }

    private fun boolean(key: String, def: Boolean) = object : ReadWriteProperty<Prefs, Boolean> {
        override fun getValue(thisRef: Prefs, property: KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Prefs, property: KProperty<*>, value: Boolean) =
            sp.edit().putBoolean(key, value).apply()
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Prefs, Int> {
        override fun getValue(thisRef: Prefs, property: KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Prefs, property: KProperty<*>, value: Int) =
            sp.edit().putInt(key, value).apply()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        const val DEFAULT_MODEL = "qwen3.7-flash"
        const val SPEAK_WARNINGS = 0
        const val SPEAK_ALL = 1
        const val SPEAK_NEVER = 2
        private const val KEY_DAY = "quota_day"
        private const val KEY_USED = "quota_used"
    }
}

/** Live diagnostics shown on the setup screen. Written by the service, read by the activity. */
object Status {
    @Volatile var serviceRunning = false
    @Volatile var watchdogRunning = false
    @Volatile var canWriteSecure = false
    @Volatile var reEnableCount = 0
    @Volatile var lastWeChatScreen = ""
    @Volatile var lastVerdict = ""
    @Volatile var lastError = ""
    @Volatile var lastLatencyMs = 0L
}
