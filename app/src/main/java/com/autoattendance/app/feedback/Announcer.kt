package com.autoattendance.app.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.autoattendance.app.data.AttendanceStatus
import com.autoattendance.app.data.Operation
import com.autoattendance.app.data.Repository

/**
 * Spoken feedback. Uses the alarm stream so it is audible even when the ringer is silenced.
 * Callers must only invoke [announceStatus] after the attendance app's screen has confirmed the new status.
 */
object Announcer {
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pending = mutableListOf<String>()

    /** Speaks the status once per status per day. Returns true if it was announced. */
    fun announceStatus(context: Context, status: AttendanceStatus): Boolean {
        val repo = Repository.get(context)
        if (status == AttendanceStatus.UNKNOWN) return false
        if (!repo.tryMarkAnnounced(status)) return false
        if (!repo.settings.value.voiceEnabled) return true
        tone(ToneGenerator.TONE_PROP_ACK, 300)
        handler.postDelayed({
            speak(context, if (status == AttendanceStatus.SIGNED_OUT) "Signed out successfully" else "Signed in successfully")
        }, 400)
        return true
    }

    fun failure(
        context: Context,
        op: Operation?,
        spoken: String = "Automatic ${op?.label?.lowercase() ?: "attendance check"} failed. Please check the attendance app.",
    ) {
        if (!Repository.get(context).settings.value.voiceEnabled) return
        // Distinct, harsher alert pattern than the success chime.
        tone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500)
        handler.postDelayed({ speak(context, spoken) }, 1700)
    }

    /** Test helpers for the settings screen – bypass the once-per-day rule. */
    fun preview(context: Context, status: AttendanceStatus) {
        tone(ToneGenerator.TONE_PROP_ACK, 300)
        handler.postDelayed({
            speak(context, if (status == AttendanceStatus.SIGNED_OUT) "Signed out successfully" else "Signed in successfully")
        }, 400)
    }

    private fun tone(type: Int, durationMs: Int) {
        val gen = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 90) }.getOrNull() ?: return
        gen.startTone(type, durationMs)
        handler.postDelayed({ gen.release() }, durationMs + 200L)
    }

    private fun speak(context: Context, text: String) {
        handler.post {
            val engine = tts
            if (engine != null && ttsReady) {
                engine.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
                return@post
            }
            pending += text
            if (engine == null) {
                tts = TextToSpeech(context.applicationContext) { status ->
                    handler.post {
                        ttsReady = status == TextToSpeech.SUCCESS
                        val t = tts ?: return@post
                        if (ttsReady) {
                            t.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ALARM)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build(),
                            )
                            pending.forEach { t.speak(it, TextToSpeech.QUEUE_ADD, null, it.hashCode().toString()) }
                        } else {
                            tts = null
                        }
                        pending.clear()
                    }
                }
            }
        }
    }
}
