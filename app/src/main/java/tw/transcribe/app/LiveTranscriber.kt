package tw.transcribe.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 以系統 SpeechRecognizer（zh-TW）做「連續」即時辨識。
 * 系統辨識器每句話講完就會結束，所以這裡在每句結束後自動重新啟動，形成不間斷的逐字稿。
 * 必須在主執行緒呼叫。
 */
class LiveTranscriber(
    private val ctx: Context,
    private val preferOffline: Boolean,
    private val muteBeep: Boolean,
    private val cb: Callback,
) {
    interface Callback {
        fun onSpeechStart()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onStatus(msg: String)
        fun onFatal(msg: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mutedStreams = mutableListOf<Int>()
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var lastPartial = ""
    private var networkErrors = 0

    fun start() {
        active = true
        if (muteBeep) mute()
        createRecognizer()
        listen()
    }

    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.let {
            try { it.cancel() } catch (_: Exception) {}
            try { it.destroy() } catch (_: Exception) {}
        }
        recognizer = null
        // 延遲解除靜音，避免停止時的提示音
        handler.postDelayed({ unmute() }, 400)
    }

    private fun createRecognizer() {
        recognizer?.let { try { it.destroy() } catch (_: Exception) {} }
        recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(listener) }
    }

    private fun listen() {
        if (!active) return
        lastPartial = ""
        try {
            recognizer?.startListening(buildIntent())
        } catch (e: Exception) {
            scheduleRestart(500, recreate = true)
        }
    }

    private fun scheduleRestart(delayMs: Long, recreate: Boolean = false) {
        if (!active) return
        handler.postDelayed({
            if (active) {
                if (recreate) createRecognizer()
                listen()
            }
        }, delayMs)
    }

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-TW")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        if (Build.VERSION.SDK_INT >= 33) {
            // 讓辨識結果自動加標點（支援的裝置）
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        }
    }

    private fun firstResult(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    private fun fatal(msg: String) {
        stop()
        cb.onFatal(msg)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = cb.onStatus("聆聽中…")
        override fun onBeginningOfSpeech() = cb.onSpeechStart()
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = firstResult(partialResults)
            if (!t.isNullOrBlank()) {
                lastPartial = t
                cb.onPartial(t)
            }
        }

        override fun onResults(results: Bundle?) {
            val t = firstResult(results).takeUnless { it.isNullOrBlank() } ?: lastPartial
            lastPartial = ""
            if (t.isNotBlank()) cb.onFinal(t)
            networkErrors = 0
            scheduleRestart(30)
        }

        override fun onError(error: Int) {
            if (!active) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (lastPartial.isNotBlank()) cb.onFinal(lastPartial)
                    lastPartial = ""
                    scheduleRestart(30)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT ->
                    scheduleRestart(300, recreate = true)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                    if (lastPartial.isNotBlank()) { cb.onFinal(lastPartial); lastPartial = "" }
                    networkErrors++
                    if (networkErrors > 5) {
                        fatal("網路連線失敗。可在設定開啟「優先離線辨識」並下載中文（台灣）離線語音包。")
                    } else {
                        cb.onStatus("網路不穩，重試中…")
                        scheduleRestart(1000L * networkErrors, recreate = true)
                    }
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fatal("缺少麥克風權限")
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    fatal("此裝置的語音服務不支援／尚未下載「中文（台灣）」。請到 Google 語音設定下載離線語言，或關閉「優先離線辨識」。")
                else -> scheduleRestart(500, recreate = true)
            }
        }
    }

    private fun mute() {
        for (st in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try {
                if (!audio.isStreamMute(st)) {
                    audio.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0)
                    mutedStreams.add(st)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun unmute() {
        for (st in mutedStreams) {
            try { audio.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        }
        mutedStreams.clear()
    }
}
