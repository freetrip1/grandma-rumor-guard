package com.grandma.rumorguard

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors

/**
 * Watches WeChat. While a 视频号 page is in front it shows the overlay, screenshots each video the
 * user stays on, asks the [Analyzer] for a verdict, speaks warnings, and warns again when the
 * user tries to forward a suspicious video.
 */
class GuardService : AccessibilityService() {
    private lateinit var prefs: Prefs
    private lateinit var overlay: Overlay
    private lateinit var speaker: Speaker
    private lateinit var analyzer: Analyzer
    private val main = Handler(Looper.getMainLooper())
    private val encoder = Executors.newSingleThreadExecutor()
    private val activityClasses = HashMap<String, Boolean>()
    private val verdictCache = object : LinkedHashMap<String, Verdict>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Verdict>) = size > 60
    }

    private var connected = false
    private var dynamicEvents = 0
    private var lastWeChatActivity = ""
    private var inFinder = false
    private var finderFeed = false
    private var launcherPkg: String? = null
    private lateinit var audio: AudioManager
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            // A video starting/stopping in any app may change what we should watch.
            main.removeCallbacks(refreshForeground)
            main.postDelayed(refreshForeground, AUDIO_DEBOUNCE_MS)
        }
    }
    /** Bumped for every new video; async work for an older video is dropped. */
    private var videoGen = 0
    private var currentSig = ""
    private var currentVerdict: Verdict? = null
    private var protectedCapture = false
    private var gestureMaxDelta = 0
    private var gestureViewPager = false
    private var lastSpokenLevel: Level? = null
    private var lastFeedIndex = -1
    private var pendingFeedIndex = -1
    private var lastShareWarnAt = 0L
    private var lastSpokenSig = ""
    private var lastSpokenAt = 0L

    private val refreshForeground = Runnable { updateForeground() }
    private val feedSettled = Runnable { onFeedSettled() }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        audio = getSystemService(AudioManager::class.java)
        launcherPkg = runCatching {
            packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        }.getOrNull()
        runCatching { audio.registerAudioPlaybackCallback(playbackCallback, main) }
        speaker = Speaker(this, prefs)
        analyzer = Analyzer(prefs)
        overlay = Overlay(this, prefs, onRepeat = { currentVerdict?.let { speaker.speak(it.speech) } })
        connected = true
        Status.serviceRunning = true
        log("service connected")
        // The system binds enabled accessibility services on boot (no OEM auto-start needed), so
        // starting the watchdog here brings the whole protection chain back after a reboot without
        // anyone opening the app.
        runCatching { WatchdogService.start(this) }
        updateForeground()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!connected) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            main.removeCallbacks(refreshForeground)
            main.postDelayed(refreshForeground, 150)
            return
        }
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowStateChanged(pkg, event)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (inFinder) onFeedScrolled(event)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (inFinder && pkg == WECHAT) {
                val texts = eventTexts(event)
                log("click ${brief(texts)}")
                if (isShareControl(texts)) warnBeforeForward()
            }
        }
    }

    /**
     * Apps may call AccessibilityManager.interrupt() at any time; a warning she has not heard yet
     * must not be cut off by that. Speech stops when the video changes instead (see [newVideo]).
     */
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (!connected) return
        connected = false
        log("service stopped")
        runCatching { audio.unregisterAudioPlaybackCallback(playbackCallback) }
        main.removeCallbacksAndMessages(null)
        analyzer.shutdown()
        encoder.shutdownNow()
        overlay.hide()
        speaker.shutdown()
        Status.serviceRunning = false
    }

    // ---- Where is the user? ----

    private fun onWindowStateChanged(pkg: String, event: AccessibilityEvent) {
        if (pkg == WECHAT) {
            val cls = event.className?.toString().orEmpty()
            val activity = cls.isNotEmpty() && isActivity(pkg, cls)
            log("wechat window $cls activity=$activity")
            if (activity) {
                val previous = lastWeChatActivity
                lastWeChatActivity = cls
                Status.lastWeChatScreen = cls
                if (isWatchedPage(previous)) {
                    // Leaving 视频号 for WeChat's "choose a chat" screen means the video is being forwarded.
                    if (cls.contains("Select", ignoreCase = true)) warnBeforeForward()
                    // Another 视频号 page (a different video, a profile…) counts as a new video.
                    if (isWatchedPage(cls) && cls != previous && inFinder) newVideo()
                }
            } else if (inFinder) {
                // A popup over the video; the share sheet is one.
                val texts = eventTexts(event)
                log("popup ${brief(texts)}")
                if (isShareControl(texts)) warnBeforeForward()
            }
        }
        updateForeground()
    }

    private fun updateForeground() {
        if (!connected) return
        val root = topAppWindowRoot { true }
        val fg = root?.packageName?.toString() ?: return
        val wechat = fg == WECHAT
        val watch = computeWatching(fg, wechat, root)
        setDynamicEvents(scroll = watch, click = watch && wechat)
        if (watch == inFinder) return
        inFinder = watch
        finderFeed = watch && wechat && !inMiniProgram()
        log("watching=$watch foreground=$fg page=$lastWeChatActivity")
        if (watch) {
            overlay.show()
            lastFeedIndex = -1
            newVideo()
        } else {
            videoGen++
            analyzer.cancel()
            main.removeCallbacks(feedSettled)
            overlay.hide()
        }
    }

    /**
     * Should the overlay watch the current screen? WeChat 视频号/小程序 always; any other app only when
     * [Prefs.watchAllApps] is on and a video is actually playing — a known video app, or an unknown one
     * on a text-sparse (video-like) screen. Calls, the launcher, system UI and our own app are skipped,
     * and the active-playback gate means static screens (chats, settings) are never screenshotted.
     */
    private fun computeWatching(fg: String, wechat: Boolean, root: AccessibilityNodeInfo?): Boolean {
        if (wechat) return isWatchedPage(lastWeChatActivity)
        if (!prefs.watchAllApps) return false
        if (fg == packageName || fg == launcherPkg || fg in EXCLUDED_APPS) return false
        if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) return false
        // A known short-video app is watched whenever it's in front — even a muted / autoplay video.
        if (fg in KNOWN_VIDEO_APPS) return true
        // Unknown app: watch if a video is actually playing (has audio) OR the screen is a text-sparse,
        // video-like page. Using OR (not AND) means a playing video counts even with a busy UI, and a
        // muted video on a bare screen still counts; chats/reading screens (lots of text) are left alone.
        val texts = if (root == null) Int.MAX_VALUE else ScreenText.collect(root).size
        return mediaPlaying() || texts <= VIDEO_MAX_TEXTS
    }

    /** True when some app is actively playing media (video) audio right now. */
    private fun mediaPlaying(): Boolean = runCatching {
        audio.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA }
    }.getOrDefault(false)


    private fun wechatRoot(): AccessibilityNodeInfo? =
        topAppWindowRoot { it.packageName == WECHAT } ?: rootInActiveWindow?.takeIf { it.packageName == WECHAT }

    private fun topAppWindowRoot(accept: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val list = try {
            windows
        } catch (e: RuntimeException) {
            return null
        }
        for (w in list.sortedByDescending { it.layer }) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = w.root ?: continue
            return root.takeIf(accept) ?: continue
        }
        return null
    }

    private fun isActivity(pkg: String, cls: String): Boolean = activityClasses.getOrPut(cls) {
        try {
            packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** True on a page we watch: 视频号 (finder) or, if enabled, a WeChat mini program (小程序 video apps). */
    private fun isWatchedPage(cls: String): Boolean {
        if (cls.isEmpty()) return false
        if (cls.contains(prefs.finderKeyword.ifBlank { "finder" }, ignoreCase = true)) return true
        return prefs.watchMiniProgram && cls.contains("appbrand", ignoreCase = true)
    }

    private fun inMiniProgram() = lastWeChatActivity.contains("appbrand", ignoreCase = true)

    /** Scroll events fire while watching any app; click events (forward guard) only inside WeChat. */
    private fun setDynamicEvents(scroll: Boolean, click: Boolean) {
        val wanted = (if (scroll) AccessibilityEvent.TYPE_VIEW_SCROLLED else 0) or
            (if (click) AccessibilityEvent.TYPE_VIEW_CLICKED else 0)
        if (wanted == dynamicEvents) return
        val info = serviceInfo ?: return
        info.eventTypes = BASE_EVENTS or wanted
        serviceInfo = info
        dynamicEvents = wanted
    }

    // ---- Which video is this? ----

    private fun onFeedScrolled(event: AccessibilityEvent) {
        val source = event.source
        // Mini-program feeds emit scrolls with a zero-size, class-less source; the height filter
        // (meant to skip 视频号 comment lists) would wrongly drop them, so skip it inside mini programs.
        if (source != null && finderFeed) {
            val bounds = Rect()
            source.getBoundsInScreen(bounds)
            val ratio = bounds.height().toFloat() / resources.displayMetrics.heightPixels
            // The video feed fills the screen; comment lists and carousels don't.
            if (ratio < FEED_MIN_HEIGHT) {
                log("scroll ignored ${source.className} height=${"%.2f".format(ratio)}")
                return
            }
        }
        log("scroll ${source?.className} from=${event.fromIndex} finderFeed=$finderFeed")
        pendingFeedIndex = event.fromIndex
        val dy = event.scrollDeltaY
        gestureMaxDelta = maxOf(gestureMaxDelta, if (dy == -1) 0 else if (dy < 0) -dy else dy)
        if (source?.className?.contains("Pager") == true) gestureViewPager = true
        main.removeCallbacks(feedSettled)
        main.postDelayed(feedSettled, SCROLL_SETTLE_MS)
    }

    private fun onFeedSettled() {
        if (!inFinder) return
        val index = pendingFeedIndex
        val viewPager = gestureViewPager
        val maxDelta = gestureMaxDelta
        gestureViewPager = false
        gestureMaxDelta = 0
        if (finderFeed) {
            // WeChat 视频号 feed: on-screen text / feed index tell us whether the clip really changed.
            val sig = ScreenText.signature(ScreenText.collect(wechatRoot()))
            val changed = when {
                sig.isNotEmpty() -> sig != currentSig
                index >= 0 -> index != lastFeedIndex
                else -> true
            }
            log("settled from=$index sig=$sig current=$currentSig changed=$changed")
            if (index >= 0) lastFeedIndex = index
            if (changed) newVideo()
            return
        }
        // Mini program / other app: no on-screen text or feed index. Skip obvious popup/comment panels,
        // and skip a 手滑 bounce on a paged feed (a ViewPager that barely moved); otherwise identify it.
        val textCount = ScreenText.collect(topAppWindowRoot { true }).size
        if (textCount > PANEL_TEXTS) {
            log("settled ignored: looks like a panel ($textCount texts)")
            return
        }
        if (viewPager && maxDelta < REAL_SCROLL_PX) {
            log("settled ignored: 手滑 bounce (paged feed moved ${maxDelta}px)")
            return
        }
        newVideo()
    }

    private fun newVideo() {
        val gen = ++videoGen
        log("new video #$gen")
        analyzer.cancel()
        speaker.stop()
        currentVerdict = null
        currentSig = ""
        overlay.showPending()
        main.postDelayed({ firstLook(gen) }, FIRST_LOOK_DELAY_MS)
    }

    // ---- Look, ask, tell ----

    private fun firstLook(gen: Int) {
        if (gen != videoGen || !inFinder) return
        val texts = ScreenText.collect(wechatRoot())
        val sig = ScreenText.signature(texts)
        currentSig = sig
        val cached = if (sig.isEmpty()) null else verdictCache[sig]
        log("first look #$gen texts=${texts.size} sig=$sig cached=${cached != null}")
        if (cached != null) {
            applyVerdict(gen, cached)
            return
        }
        if (prefs.apiKey.isBlank()) {
            overlay.showMessage(Level.ERROR, "还没设置AI密钥", "请家人打开“辟谣小助手”，填写 API Key。")
            return
        }
        capture(gen) { first ->
            // Copy-protected video comes back black; don't burn a daily analysis or speak a false warning.
            if (first == null && protectedCapture) {
                overlay.showMessage(Level.ERROR, "这下没看清", "画面是黑的、没截到（可能在放广告、正在加载，或这个视频有防截屏）。划下一个、或等一下再看就行。")
                return@capture
            }
            if (!prefs.tryConsumeAnalysis()) {
                overlay.showMessage(Level.ERROR, "今天次数用完了", "已到每天最多分析次数，可以在辟谣小助手里调大。")
                return@capture
            }
            main.postDelayed({ secondLook(gen, texts, first) }, SECOND_LOOK_DELAY_MS)
        }
    }

    private fun secondLook(gen: Int, texts: List<String>, first: String?) {
        if (gen != videoGen || !inFinder) return
        capture(gen) { second ->
            val images = listOfNotNull(first, second)
            val screenText = ScreenText.forPrompt(texts)
            if (images.isEmpty() && screenText.isBlank()) {
                if (protectedCapture) {
                    overlay.showMessage(Level.ERROR, "这下没看清", "画面是黑的、没截到（可能在放广告、正在加载，或这个视频有防截屏）。划下一个、或等一下再看就行。")
                } else {
                    overlay.showMessage(Level.ERROR, "看不到画面", "截图失败了，可能是手机系统不允许。")
                }
                return@capture
            }
            val started = SystemClock.elapsedRealtime()
            val sig = currentSig
            log("analyze #$gen images=${images.size} textChars=${screenText.length}")
            analyzer.analyze(images, screenText, object : Analyzer.Listener {
                override fun onPreliminary(verdict: Verdict) {
                    log("preliminary #$gen ${verdict.level} '${verdict.label}'")
                    if (gen == videoGen) overlay.showVerdict(verdict, verifying = true)
                }

                override fun onFinal(verdict: Verdict) {
                    Status.lastLatencyMs = SystemClock.elapsedRealtime() - started
                    log("final #$gen ${verdict.level} '${verdict.label}' verified=${verdict.verified} ${Status.lastLatencyMs}ms")
                    if (sig.isNotEmpty()) verdictCache[sig] = verdict
                    applyVerdict(gen, verdict)
                }

                override fun onVerifyFailed(message: String) {
                    log("verify failed #$gen $message")
                    // The vision verdict still arrives through onFinal; keep the reason for the setup screen.
                    Status.lastError = message
                }

                override fun onError(message: String) {
                    log("error #$gen $message")
                    Status.lastError = message
                    if (gen == videoGen) overlay.showMessage(Level.ERROR, "暂时看不了", message)
                }
            })
        }
    }

    private fun applyVerdict(gen: Int, verdict: Verdict) {
        if (gen != videoGen) return
        currentVerdict = verdict
        Status.lastVerdict = "${verdict.level.emoji} ${verdict.label}：${verdict.reason}"
        overlay.showVerdict(verdict, verifying = false)
        // Don't auto-open the big card over the feed — it would block swiping to the next video. The
        // colored badge + voice already warn her; family can tap the badge for the detailed reason.
        val wanted = when (prefs.speakMode) {
            Prefs.SPEAK_ALL -> true
            Prefs.SPEAK_NEVER -> false
            else -> verdict.level.warn
        }
        val now = SystemClock.elapsedRealtime()
        // Don't repeat the voice for the same clip (revisited), or the same warning again within a short
        // window — the badge still updates each video, we just don't talk over and over on 手滑/loops.
        val repeat = (currentSig.isNotEmpty() && currentSig == lastSpokenSig && now - lastSpokenAt < REPEAT_QUIET_MS) ||
            (verdict.level == lastSpokenLevel && now - lastSpokenAt < VOICE_DEDUP_MS)
        log("show #$gen ${verdict.level} speak=${wanted && !repeat}")
        if (wanted && !repeat) {
            speaker.speak(verdict.speech)
            lastSpokenSig = currentSig
            lastSpokenLevel = verdict.level
            lastSpokenAt = now
        }
    }

    /** Screenshot of the whole screen with our overlay blacked out, as a JPEG data URL (null on failure). */
    private fun capture(gen: Int, done: (String?) -> Unit) {
        val mask = overlay.boundsOnScreen()
        protectedCapture = false
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = try {
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    hw?.copy(Bitmap.Config.ARGB_8888, false).also { hw?.recycle() }
                } finally {
                    buffer.close()
                }
                log("screenshot ${bitmap?.width}x${bitmap?.height} stale=${gen != videoGen}")
                if (bitmap == null || gen != videoGen) {
                    bitmap?.recycle()
                    if (gen == videoGen) done(null)
                    return
                }
                // Copy-protected / DRM video surfaces come back as an all-black frame (nothing to judge).
                if (isBlankCapture(bitmap)) {
                    bitmap.recycle()
                    log("screenshot blank (protected)")
                    protectedCapture = true
                    if (gen == videoGen) done(null)
                    return
                }
                encoder.execute {
                    val url = ImageEncoder.toJpegDataUrl(bitmap, mask)
                    bitmap.recycle()
                    main.post { if (gen == videoGen) done(url) }
                }
            }

            override fun onFailure(errorCode: Int) {
                log("screenshot failed code=$errorCode")
                Status.lastError = "截图失败（错误码 $errorCode）"
                if (gen == videoGen) done(null)
            }
        })
    }

    /** True when a capture is essentially all black — a copy-protected (FLAG_SECURE) video surface. */
    private fun isBlankCapture(bm: Bitmap): Boolean {
        val w = bm.width
        val h = bm.height
        if (w == 0 || h == 0) return true
        val stepX = (w / 48).coerceAtLeast(1)
        val stepY = (h / 96).coerceAtLeast(1)
        var sampled = 0
        var lit = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = bm.getPixel(x, y)
                if (maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) > 16) lit++
                sampled++
                x += stepX
            }
            y += stepY
        }
        // Even a dark night video shows the status bar / subtitles; a protected frame is ~0% lit.
        return sampled > 0 && lit * 1000 < sampled * 3
    }

    // ---- Forward guard ----

    private fun warnBeforeForward() {
        val verdict = currentVerdict
        if (verdict == null || !verdict.level.warn) {
            log("forward: no warning, verdict=${verdict?.level}")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastShareWarnAt < SHARE_WARN_COOLDOWN_MS) return
        lastShareWarnAt = now
        log("forward warning ${verdict.level}")
        overlay.expandFor(AUTO_EXPAND_MS)
        if (prefs.speakMode != Prefs.SPEAK_NEVER) {
            speaker.speak(Phrases.forwardWarning(verdict.level, prefs.aiConfig().callName))
        }
    }

    /**
     * A share button or share-sheet item: a short label such as "分享", "转发 1.2万" or "发送给朋友".
     * Long texts are ignored so a video description saying "转发给家人" doesn't count as a tap on share.
     */
    private fun isShareControl(texts: List<String>) = texts.any { raw ->
        val t = raw.trim()
        t.length <= MAX_SHARE_LABEL && (SHARE_PREFIXES.any(t::startsWith) || t in SHARE_SHEET_LABELS)
    }

    private fun eventTexts(event: AccessibilityEvent): List<String> = buildList {
        event.text.forEach { cs -> cs?.let { add(it.toString()) } }
        event.contentDescription?.let { add(it.toString()) }
        event.source?.let { node ->
            node.text?.let { add(it.toString()) }
            node.contentDescription?.let { add(it.toString()) }
        }
    }

    private fun brief(texts: List<String>) = texts.map { it.replace('\n', ' ').take(20) }.take(12).toString()

    /** Debug trace, read with `adb logcat -s RumorGuard`. Screen text appears only as short UI labels. */
    private fun log(message: String) {
        Log.d(TAG, message)
    }

    private companion object {
        const val WECHAT = "com.tencent.mm"
        const val TAG = "RumorGuard"
        const val BASE_EVENTS = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
        val SHARE_PREFIXES = listOf("转发", "分享")
        val SHARE_SHEET_LABELS = setOf("发送给朋友", "发给朋友", "发送给好友", "发给好友")
        const val MAX_SHARE_LABEL = 12
        const val FEED_MIN_HEIGHT = 0.6f
        const val SCROLL_SETTLE_MS = 600L
        /** Swiping past within this time costs nothing. */
        const val FIRST_LOOK_DELAY_MS = 1_500L
        const val SECOND_LOOK_DELAY_MS = 2_500L
        const val AUTO_EXPAND_MS = 12_000L
        const val SHARE_WARN_COOLDOWN_MS = 8_000L
        const val REPEAT_QUIET_MS = 120_000L
        const val PANEL_TEXTS = 18
        const val REAL_SCROLL_PX = 200
        const val VOICE_DEDUP_MS = 10_000L
        const val VIDEO_MAX_TEXTS = 12
        const val AUDIO_DEBOUNCE_MS = 250L
        val KNOWN_VIDEO_APPS = setOf(
            "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite", // 抖音 / 极速版
            "com.smile.gifmaker", "com.kuaishou.nebula",                 // 快手 / 极速版
            "com.ss.android.article.video", "com.baidu.haokan",          // 西瓜视频 / 好看视频
            "com.ss.android.article.news", "com.ss.android.ugc.live", "com.tencent.news", // 今日头条/抖音火山版/腾讯新闻
            "tv.danmaku.bili", "com.tencent.qqlive",                     // 哔哩哔哩 / 腾讯视频
            "com.qiyi.video", "com.youku.phone",                         // 爱奇艺 / 优酷
            "com.hunantv.imgo.activity", "com.tencent.weishi", "com.sup.android.superb", // 芒果TV/微视/皮皮虾
        )
        val EXCLUDED_APPS = setOf("com.android.systemui", "com.eg.android.AlipayGphone", "com.unionpay")
    }
}
