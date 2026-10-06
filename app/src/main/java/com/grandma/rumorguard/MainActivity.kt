package com.grandma.rumorguard

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Setup screen for the family member: permissions, voice, AI key, a live test and diagnostics. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var analyzer: Analyzer
    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            main.postDelayed(this, 1_000)
        }
    }

    private lateinit var serviceStatus: TextView
    private lateinit var ttsStatus: TextView
    private lateinit var diagnostics: TextView
    private lateinit var testResult: TextView
    private lateinit var callName: EditText
    private lateinit var speakMode: RadioGroup
    private lateinit var slowSpeech: CheckBox
    private lateinit var apiKey: EditText
    private lateinit var baseUrl: EditText
    private lateinit var visionModel: EditText
    private lateinit var checkModel: EditText
    private lateinit var webSearch: CheckBox
    private lateinit var deepThinking: CheckBox
    private lateinit var dailyLimit: EditText
    private lateinit var finderKeyword: EditText
    private lateinit var watchMiniProgram: CheckBox
    private lateinit var watchAllApps: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        speaker = Speaker(this, prefs) { refreshStatus() }
        analyzer = Analyzer(prefs)

        serviceStatus = findViewById(R.id.service_status)
        ttsStatus = findViewById(R.id.tts_status)
        diagnostics = findViewById(R.id.diagnostics)
        testResult = findViewById(R.id.test_result)
        callName = findViewById(R.id.call_name)
        speakMode = findViewById(R.id.speak_mode)
        slowSpeech = findViewById(R.id.slow_speech)
        apiKey = findViewById(R.id.api_key)
        baseUrl = findViewById(R.id.base_url)
        visionModel = findViewById(R.id.vision_model)
        checkModel = findViewById(R.id.check_model)
        webSearch = findViewById(R.id.web_search)
        deepThinking = findViewById(R.id.deep_thinking)
        dailyLimit = findViewById(R.id.daily_limit)
        finderKeyword = findViewById(R.id.finder_keyword)
        watchMiniProgram = findViewById(R.id.watch_miniprogram)
        watchAllApps = findViewById(R.id.watch_all_apps)
        loadSettings()

        findViewById<Button>(R.id.open_accessibility).setOnClickListener {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.open_app_info).setOnClickListener {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        findViewById<Button>(R.id.battery).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.test_voice).setOnClickListener {
            saveSettings()
            speaker.speak("${prefs.aiConfig().callName}，我是辟谣小助手。看视频的时候，我会提醒你哪些可能是假的。")
        }
        findViewById<Button>(R.id.open_tts).setOnClickListener {
            open(Intent("com.android.settings.TTS_SETTINGS"), fallback = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.save).setOnClickListener {
            saveSettings()
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.test_ai).setOnClickListener { testAi() }

        WatchdogService.start(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        main.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(ticker)
        saveSettings()
    }

    override fun onDestroy() {
        analyzer.shutdown()
        speaker.shutdown()
        super.onDestroy()
    }

    private fun loadSettings() {
        callName.setText(prefs.callName)
        speakMode.check(
            when (prefs.speakMode) {
                Prefs.SPEAK_ALL -> R.id.speak_all
                Prefs.SPEAK_NEVER -> R.id.speak_never
                else -> R.id.speak_warnings
            },
        )
        slowSpeech.isChecked = prefs.slowSpeech
        apiKey.setText(prefs.apiKey)
        baseUrl.setText(prefs.baseUrl)
        visionModel.setText(prefs.visionModel)
        checkModel.setText(prefs.checkModel)
        webSearch.isChecked = prefs.webSearch
        deepThinking.isChecked = prefs.deepThinking
        dailyLimit.setText(prefs.dailyLimit.toString())
        finderKeyword.setText(prefs.finderKeyword)
        watchMiniProgram.isChecked = prefs.watchMiniProgram
        watchAllApps.isChecked = prefs.watchAllApps
    }

    private fun saveSettings() {
        prefs.callName = callName.text.toString().trim()
        prefs.speakMode = when (speakMode.checkedRadioButtonId) {
            R.id.speak_all -> Prefs.SPEAK_ALL
            R.id.speak_never -> Prefs.SPEAK_NEVER
            else -> Prefs.SPEAK_WARNINGS
        }
        prefs.slowSpeech = slowSpeech.isChecked
        prefs.apiKey = apiKey.text.toString().trim()
        prefs.baseUrl = baseUrl.text.toString().trim().ifEmpty { Prefs.DEFAULT_BASE_URL }
        prefs.visionModel = visionModel.text.toString().trim().ifEmpty { Prefs.DEFAULT_MODEL }
        prefs.checkModel = checkModel.text.toString().trim().ifEmpty { Prefs.DEFAULT_MODEL }
        prefs.webSearch = webSearch.isChecked
        prefs.deepThinking = deepThinking.isChecked
        prefs.dailyLimit = dailyLimit.text.toString().toIntOrNull()?.coerceIn(1, 5_000) ?: prefs.dailyLimit
        prefs.finderKeyword = finderKeyword.text.toString().trim().ifEmpty { "finder" }
        prefs.watchMiniProgram = watchMiniProgram.isChecked
        prefs.watchAllApps = watchAllApps.isChecked
    }

    /** Runs the real two-stage pipeline on a fabricated health-rumor frame and speaks the result. */
    private fun testAi() {
        saveSettings()
        if (prefs.apiKey.isBlank()) {
            testResult.text = "请先填写 API Key。"
            return
        }
        testResult.text = "正在测试，大约需要 5～20 秒…"
        val started = SystemClock.elapsedRealtime()
        analyzer.analyze(listOf(SampleFrame.dataUrl()), SampleFrame.SCREEN_TEXT, object : Analyzer.Listener {
            override fun onPreliminary(verdict: Verdict) {
                testResult.text = "看图结果：${describe(verdict)}\n\n正在上网核实…"
            }

            private var verifyError = ""

            override fun onVerifyFailed(message: String) {
                verifyError = message
            }

            override fun onFinal(verdict: Verdict) {
                val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
                val head = if (verifyError.isEmpty()) "测试成功" else "测试完成，但没能上网核实"
                testResult.text = buildString {
                    append(String.format(Locale.ROOT, "%s（用时 %.1f 秒）\n", head, seconds))
                    append(describe(verdict))
                    if (verifyError.isNotEmpty()) append('\n').append(verifyError).append("（只用了看图的结论）")
                }
                speaker.speak(verdict.speech)
            }

            override fun onError(message: String) {
                testResult.text = "测试失败：$message"
            }
        })
    }

    private fun describe(v: Verdict) = buildString {
        append(v.level.emoji).append(' ').append(v.label)
        if (v.reason.isNotEmpty()) append("\n理由：").append(v.reason)
        append("\n语音：").append(v.speech)
        if (v.verified) append("\n（已上网核实）")
    }

    /** Runs every second; only touches views whose content changed so the screen stays idle. */
    private fun refreshStatus() {
        val enabled = isServiceEnabled()
        serviceStatus.show(
            if (enabled) "✅ 无障碍服务已开启" else "❌ 无障碍服务没开：点下面的按钮，找到“辟谣小助手”打开",
            if (enabled) GREEN else RED,
        )
        val ttsText = when (speaker.state) {
            Speaker.State.STARTING -> "⏳ 语音引擎启动中…"
            Speaker.State.READY -> "✅ 中文语音可以用"
            Speaker.State.NO_CHINESE -> "❌ 语音引擎不支持中文：在“文字转语音”里换一个支持中文的引擎"
            Speaker.State.FAILED -> "❌ 手机上没有可用的语音引擎"
        }
        ttsStatus.show(ttsText, if (speaker.state == Speaker.State.READY) GREEN else RED)
        diagnostics.show(buildString {
            append("服务在运行：").append(if (Status.serviceRunning) "是" else "否").append('\n')
            append("保护（防系统关闭）：").append(
                when {
                    !Status.watchdogRunning -> "未启动"
                    Status.canWriteSecure -> "已开启"
                    else -> "待电脑授权一次（见说明）"
                },
            )
            if (Status.reEnableCount > 0) append("，已自动恢复 ").append(Status.reEnableCount).append(" 次")
            append('\n')
            append("今天已分析：").append(prefs.usedToday).append(" 个视频\n")
            append("最近的微信界面：").append(Status.lastWeChatScreen.ifEmpty { "（还没看到）" }).append('\n')
            append("最近的结论：").append(Status.lastVerdict.ifEmpty { "（还没有）" }).append('\n')
            if (Status.lastLatencyMs > 0) {
                append(String.format(Locale.ROOT, "最近一次用时：%.1f 秒\n", Status.lastLatencyMs / 1000.0))
            }
            append("最近的错误：").append(Status.lastError.ifEmpty { "无" })
        })
    }

    private fun TextView.show(value: String, color: Int? = null) {
        if (text.toString() != value) text = value
        if (color != null && currentTextColor != color) setTextColor(color)
    }

    private fun isServiceEnabled(): Boolean =
        getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已经允许后台运行了", Toast.LENGTH_SHORT).show()
            return
        }
        open(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
    }

    private fun open(intent: Intent, fallback: Intent? = null) {
        for (candidate in listOfNotNull(intent, fallback)) {
            try {
                startActivity(candidate)
                return
            } catch (e: ActivityNotFoundException) {
                // try the next one
            }
        }
        Toast.makeText(this, "这台手机打不开这个设置页", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val GREEN = 0xFF2E7D32.toInt()
        const val RED = 0xFFC62828.toInt()
    }
}
