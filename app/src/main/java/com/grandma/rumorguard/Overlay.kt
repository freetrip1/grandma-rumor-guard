package com.grandma.rumorguard

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.roundToInt

/** ScrollView that never grows past [maxHeight]px, so a long card still fits on screen and scrolls. */
private class MaxHeightScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
    }
}

/**
 * Floating verdict badge drawn above WeChat as an accessibility overlay (no extra permission).
 * Tap the badge to open/close the detail card; drag the badge or the card's title bar to move it.
 * The card's text scrolls, its buttons stay visible, and it never closes on its own — only on tap,
 * the 收起 button, or when the next video starts.
 */
@SuppressLint("ClickableViewAccessibility")
class Overlay(
    service: AccessibilityService,
    private val prefs: Prefs,
    private val onRepeat: () -> Unit,
) {
    private val ctx = service
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(service).scaledTouchSlop
    private var shown = false

    private val badgeShape = GradientDrawable().apply { cornerRadius = dp(22).toFloat() }
    private val badge = TextView(ctx).apply {
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = cardW()
        background = badgeShape
        elevation = dp(4).toFloat()
    }

    /** Title doubles as a drag handle for the card. */
    private val title = TextView(ctx).apply {
        textSize = 22f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }
    private val reason = TextView(ctx).apply {
        textSize = 19f
        setTextColor(0xFF212121.toInt())
        setLineSpacing(0f, 1.2f)
    }
    private val summary = TextView(ctx).apply {
        textSize = 16f
        setTextColor(0xFF616161.toInt())
        setLineSpacing(0f, 1.2f)
    }
    private val note = TextView(ctx).apply {
        textSize = 13f
        setTextColor(0xFF9E9E9E.toInt())
    }
    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(reason)
        addView(summary, topMargin(8))
        addView(note, topMargin(8))
    }

    /** Long explanations scroll here; the buttons below stay put. */
    private val scroll = MaxHeightScrollView(ctx, (screenHeight() * 0.45f).roundToInt()).apply {
        isVerticalScrollBarEnabled = true
        addView(body)
    }
    private val repeatButton = Button(ctx).apply {
        text = "🔊 再说一遍"
        textSize = 18f
        setOnClickListener { onRepeat() }
    }
    private val closeButton = Button(ctx).apply {
        text = "收起"
        textSize = 18f
        setOnClickListener { collapse() }
    }
    private val buttonRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(repeatButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(closeButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
    private val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(16).toFloat()
        }
        elevation = dp(6).toFloat()
        visibility = View.GONE
        addView(title)
        addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(buttonRow, topMargin(8))
    }
    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        addView(badge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(card, LinearLayout.LayoutParams(cardW(), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.overlayX.takeIf { it >= 0 } ?: dp(8)
        y = prefs.overlayY.takeIf { it >= 0 } ?: (screenHeight() * 0.18f).roundToInt()
    }

    init {
        badge.setOnTouchListener(DragListener(onTap = ::toggle))
        title.setOnTouchListener(DragListener(onTap = null))
        showPending()
    }

    fun show() {
        if (shown) return
        wm.addView(root, params)
        shown = true
    }

    fun hide() {
        collapse()
        if (!shown) return
        wm.removeView(root)
        shown = false
    }

    fun showPending() {
        render(Level.PENDING, "正在看这个视频，请稍等…", "", "AI 判断仅供参考")
        collapse()
    }

    fun showVerdict(verdict: Verdict, verifying: Boolean) {
        val note = when {
            verifying -> "正在上网核实…"
            verdict.verified -> "已上网核实 · AI 判断仅供参考"
            else -> "AI 判断仅供参考"
        }
        val summary = if (verdict.summary.isEmpty()) "" else "视频内容：${verdict.summary}"
        // Badge shows only the plain 能看/别信 headline; the AI's specifics go in the card body.
        val detail = if (verdict.label.isBlank()) verdict.reason else "${verdict.label}：${verdict.reason}"
        render(verdict.level, detail, summary, note)
    }

    fun showMessage(level: Level, label: String, detail: String) {
        render(level, detail, "", "", "${level.emoji} $label")
    }

    /**
     * Opens the card. The [millis] argument is ignored — the card no longer closes on a timer;
     * it stays until the user taps 收起/the badge, or the next video resets it.
     */
    fun expandFor(millis: Long) = expand()

    fun expand() {
        if (!shown || card.visibility == View.VISIBLE) return
        card.visibility = View.VISIBLE
        scroll.scrollTo(0, 0)
        main.post { keepOnScreen() }
    }

    fun collapse() {
        if (card.visibility == View.GONE) return
        card.visibility = View.GONE
        val savedY = prefs.overlayY
        if (savedY >= 0 && params.y != savedY) {
            params.y = savedY
            if (shown) wm.updateViewLayout(root, params)
        }
    }

    /** Where the overlay is on screen, so screenshots can black it out. */
    fun boundsOnScreen(): Rect? {
        if (!shown || root.width == 0) return null
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
    }

    private fun render(
        level: Level,
        detail: String,
        summaryText: String,
        noteText: String,
        headline: String = "${level.emoji} ${level.headline}",
    ) {
        badgeShape.setColor(level.background)
        badge.setTextColor(level.foreground)
        badge.text = headline
        title.setTextColor(level.accent)
        title.text = headline
        reason.text = detail
        reason.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        summary.text = summaryText
        summary.visibility = if (summaryText.isEmpty()) View.GONE else View.VISIBLE
        note.text = noteText
        note.visibility = if (noteText.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toggle() {
        if (card.visibility == View.VISIBLE) collapse() else expand()
    }

    /** Nudge the window up so the whole card (now height-capped) is on screen. */
    private fun keepOnScreen() {
        if (!shown) return
        val maxY = screenHeight() - root.height - dp(8)
        if (params.y > maxY) {
            params.y = maxY.coerceAtLeast(dp(4))
            wm.updateViewLayout(root, params)
        }
    }

    private inner class DragListener(private val onTap: (() -> Unit)?) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging && shown) {
                        params.x = (startX + dx).roundToInt().coerceIn(0, (screenWidth() - root.width).coerceAtLeast(0))
                        params.y = (startY + dy).roundToInt().coerceIn(0, (screenHeight() - root.height).coerceAtLeast(0))
                        wm.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        prefs.overlayX = params.x
                        prefs.overlayY = params.y
                    } else {
                        onTap?.invoke()
                    }
                }
            }
            return true
        }
    }

    /** Near full width so big-font text wraps less and reads easily. */
    private fun cardW() = (screenWidth() - dp(20)).coerceAtMost(dp(460))
    private fun screenWidth() = wm.currentWindowMetrics.bounds.width()
    private fun screenHeight() = wm.currentWindowMetrics.bounds.height()
    private fun dp(v: Int) = (v * density).roundToInt()
    private fun topMargin(v: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(v) }
}
