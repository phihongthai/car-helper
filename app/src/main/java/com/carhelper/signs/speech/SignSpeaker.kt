package com.carhelper.signs.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Đọc cảnh báo bằng tiếng Việt. Tạm hạ âm lượng nhạc (duck) trong lúc đọc,
 * giống cách app dẫn đường hoạt động.
 */
class SignSpeaker(context: Context, private val onReady: (Boolean) -> Unit) : Closeable {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private val pending = AtomicInteger(0)
    private val counter = AtomicInteger(0)
    @Volatile private var ready = false
    private lateinit var tts: TextToSpeech

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) = finished()
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finished()
        override fun onError(utteranceId: String?, errorCode: Int) = finished()
    }

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS && run {
                val result = tts.setLanguage(Locale.forLanguageTag("vi-VN"))
                result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (ready) {
                tts.setAudioAttributes(attributes)
                tts.setSpeechRate(1.05f)
                tts.setOnUtteranceProgressListener(listener)
            }
            onReady(ready)
        }
    }

    private fun finished() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    /** An toàn khi gọi từ bất kỳ thread nào. */
    fun speak(text: String) {
        if (!ready) return
        if (pending.getAndIncrement() == 0) audioManager.requestAudioFocus(focusRequest)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "sign-${counter.incrementAndGet()}")
    }

    override fun close() {
        tts.stop()
        tts.shutdown()
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}
