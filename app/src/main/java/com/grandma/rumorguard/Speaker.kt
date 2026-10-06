package com.grandma.rumorguard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Chinese text-to-speech. To be heard over a loud video it speaks on the ALARM stream — a separate,
 * loud channel the video (which plays on STREAM_MUSIC) can't share — and raises that stream to max
 * while talking, then restores it once speech ends (or after a safety timeout). The video's own
 * volume is never touched, because a background app can lower but not reliably raise it back.
 */
class Speaker(
    context: Context,
    private val prefs: Prefs,
    private val onStateChanged: (() -> Unit)? = null,
) : TextToSpeech.OnInitListener {
    enum class State { STARTING, READY, NO_CHINESE, FAILED }

    @Volatile var state = State.STARTING
        private set

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    // The voice speaks on the ALARM stream so it has its own volume the video can't share (the video
    // and ordinary TTS both live on STREAM_MUSIC). We only raise the alarm stream — never the video's,
    // which a background app can lower but not reliably raise back.
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    // GAIN_TRANSIENT (not MAY_DUCK): ask a compliant video to pause, not merely lower, while we talk.
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(focusAttributes)
        .build()
    private val maxAlarmVolume = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
    private val tts = TextToSpeech(context.applicationContext, this)
    private var pending: String? = null
    private var utterance = 0

    /** True while the alarm stream is maxed for speech; guards against double apply/restore. */
    private var quieting = false
    /** Alarm volume before we maxed it, restored afterwards. */
    private var savedAlarm: Int? = null

    /** Restores volume + focus; debounced so back-to-back sentences stay loud. */
    private val stopPlayback = Runnable { restoreAndRelease() }

    override fun onInit(status: Int) {
        main.post {
            state = if (status != TextToSpeech.SUCCESS) {
                State.FAILED
            } else {
                val lang = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
                tts.setAudioAttributes(speechAttributes)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = scheduleStop("done")

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = scheduleStop("error")
                })
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                    State.NO_CHINESE
                } else {
                    State.READY
                }
            }
            onStateChanged?.invoke()
            pending?.let { pending = null; speak(it) }
        }
    }

    /** Speaks [text], interrupting whatever is being said. Main thread only. */
    fun speak(text: String) {
        when (state) {
            State.STARTING -> pending = text
            State.READY -> {
                main.removeCallbacks(stopPlayback)
                tts.setSpeechRate(prefs.speechRate)
                audio.requestAudioFocus(focus)
                if (!quieting) {
                    quieting = true
                    savedAlarm = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                    // Put the voice on the separate alarm stream at full volume so it cuts over the video.
                    runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarmVolume, 0) }
                    Log.d(TAG, "voice loud: alarm $savedAlarm->$maxAlarmVolume")
                }
                // Safety net: restore even if the engine never reports done.
                main.postDelayed(stopPlayback, SAFETY_MS)
                val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "say-${utterance++}")
            }
            State.NO_CHINESE, State.FAILED -> Unit
        }
    }

    fun stop() {
        pending = null
        if (state == State.READY) tts.stop()
        main.removeCallbacks(stopPlayback)
        main.post(stopPlayback)
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun scheduleStop(reason: String) {
        Log.d(TAG, "tts $reason")
        main.removeCallbacks(stopPlayback)
        main.postDelayed(stopPlayback, RESTORE_DELAY_MS)
    }

    private fun restoreAndRelease() {
        if (quieting) {
            savedAlarm?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
            Log.d(TAG, "restore: alarm->$savedAlarm")
            quieting = false
            savedAlarm = null
        }
        audio.abandonAudioFocusRequest(focus)
    }

    private companion object {
        const val TAG = "RumorGuard"
        /** Keep the voice loud briefly between consecutive sentences before restoring. */
        const val RESTORE_DELAY_MS = 400L
        /** Hard cap: restore even if the engine never calls onDone, so we never leave the video muted. */
        const val SAFETY_MS = 20_000L
    }
}
