package tw.transcribe.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

// ───────────────────────── 即時轉寫 ─────────────────────────

class LiveViewModel(app: Application) : AndroidViewModel(app) {
    val segments = mutableStateListOf<Segment>()
    var partial by mutableStateOf("")
        private set
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("按「開始」後直接說話")
        private set

    private var transcriber: LiveTranscriber? = null
    private var sessionStartMs = 0L
    private var elapsedBeforeMs = 0L
    private var utteranceStartSec = -1.0

    private fun nowSec() = (SystemClock.elapsedRealtime() - sessionStartMs) / 1000.0

    fun start() {
        if (running) return
        val ctx = getApplication<Application>()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            status = "此裝置沒有可用的語音辨識服務，請安裝或啟用「Google」App。"
            return
        }
        val s = AppSettings(ctx)
        sessionStartMs = SystemClock.elapsedRealtime() - elapsedBeforeMs
        running = true
        status = "啟動中…"
        transcriber = LiveTranscriber(ctx, s.preferOffline, s.muteBeep, object : LiveTranscriber.Callback {
            override fun onSpeechStart() {
                if (utteranceStartSec < 0) utteranceStartSec = nowSec()
            }

            override fun onPartial(text: String) {
                if (utteranceStartSec < 0) utteranceStartSec = nowSec()
                partial = text
            }

            override fun onFinal(text: String) = commit(text)
            override fun onStatus(msg: String) { status = msg }
            override fun onFatal(msg: String) {
                transcriber = null
                finishSession()
                status = msg
            }
        }).also { it.start() }
    }

    private fun commit(text: String) {
        val end = nowSec()
        val start = if (utteranceStartSec >= 0) utteranceStartSec else end
        segments.add(Segment(start, end, text))
        partial = ""
        utteranceStartSec = -1.0
    }

    fun stop() {
        transcriber?.stop()
        transcriber = null
        finishSession()
        status = "已停止"
    }

    private fun finishSession() {
        if (partial.isNotBlank()) commit(partial)
        if (running) elapsedBeforeMs = SystemClock.elapsedRealtime() - sessionStartMs
        running = false
    }

    fun clear() {
        if (running) stop()
        segments.clear()
        partial = ""
        elapsedBeforeMs = 0
        status = "已清除"
    }

    override fun onCleared() {
        transcriber?.stop()
    }
}

// ───────────────────────── 批次檔案 ─────────────────────────

enum class ItemStatus { PENDING, DECODING, TRANSCRIBING, DONE, ERROR, CANCELLED }

class BatchItem(val id: Long, val uri: Uri, val name: String) {
    var status by mutableStateOf(ItemStatus.PENDING)
    var progress by mutableFloatStateOf(0f)
    var detail by mutableStateOf("等待中")
    var segments by mutableStateOf<List<Segment>>(emptyList())
}

class BatchViewModel(app: Application) : AndroidViewModel(app) {
    val items = mutableStateListOf<BatchItem>()
    var running by mutableStateOf(false)
        private set
    var message by mutableStateOf("")

    private var job: Job? = null
    private var nextId = 1L

    fun addUris(uris: List<Uri>) {
        val ctx = getApplication<Application>()
        for (u in uris) {
            if (items.any { it.uri == u }) continue
            try {
                ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            items.add(BatchItem(nextId++, u, FileUtil.displayName(ctx, u)))
        }
    }

    fun remove(item: BatchItem) {
        if (running && item.status in setOf(ItemStatus.DECODING, ItemStatus.TRANSCRIBING)) return
        items.remove(item)
    }

    fun clearAll() {
        if (running) return
        items.clear()
    }

    fun start() {
        if (running) return
        val s = AppSettings(getApplication())
        if (s.apiKey.isBlank()) {
            message = "請先到「設定」填入 API Key"
            return
        }
        message = ""
        running = true
        job = viewModelScope.launch {
            try {
                for (item in items.toList()) {
                    if (item.status == ItemStatus.DONE) continue
                    process(item, s)
                }
            } finally {
                running = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun retry(item: BatchItem) {
        item.status = ItemStatus.PENDING
        item.detail = "等待中"
        item.progress = 0f
        start()
    }

    private suspend fun process(item: BatchItem, s: AppSettings) {
        val ctx = getApplication<Application>()
        val client = WhisperClient(s.baseUrl, s.apiKey, s.model)
        val workDir = File(ctx.cacheDir, "chunks/${item.id}")
        try {
            item.status = ItemStatus.DECODING
            item.segments = emptyList()
            item.progress = 0f
            item.detail = "轉換音訊格式中…"
            val chunks = withContext(Dispatchers.IO) {
                AudioDecoder.decodeToWavChunks(
                    ctx, item.uri, workDir, s.chunkMinutes * 60,
                    isCancelled = { !isActive },
                    onProgress = { p -> item.progress = p; item.detail = "轉換音訊格式中… ${(p * 100).toInt()}%" },
                )
            }
            if (chunks.isEmpty()) throw IOException("沒有可辨識的音訊內容")

            item.status = ItemStatus.TRANSCRIBING
            val all = mutableListOf<Segment>()
            chunks.forEachIndexed { i, c ->
                item.detail = "辨識中 第 ${i + 1}/${chunks.size} 段"
                item.progress = i.toFloat() / chunks.size
                val segs = withContext(Dispatchers.IO) { client.transcribe(c.file, s.prompt, c.startSec, c.durationSec) }
                all += segs
                item.segments = all.toList()
                c.file.delete()
            }
            item.progress = 1f
            item.status = ItemStatus.DONE
            item.detail = "完成（${TranscriptFormat.ts(chunks.last().startSec + chunks.last().durationSec)}）"
        } catch (e: CancellationException) {
            item.status = ItemStatus.CANCELLED
            item.detail = "已取消"
            throw e
        } catch (e: Exception) {
            item.status = ItemStatus.ERROR
            item.detail = e.message ?: e.toString()
        } finally {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { workDir.deleteRecursively() }
        }
    }
}
