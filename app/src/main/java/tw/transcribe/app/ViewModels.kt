package tw.transcribe.app

import android.app.Application
import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

// ───────────────────────── 即時轉寫 ─────────────────────────

class LiveViewModel(app: Application) : AndroidViewModel(app) {
    private val store = RecordStore(app)

    /** 已保存的紀錄（新到舊）。重置畫面不會影響這裡。 */
    val saved = mutableStateListOf<LiveRecord>().apply { addAll(store.load()) }

    /** 目前畫面上顯示的紀錄（可能包含多次開始／停止）。 */
    val screen = mutableStateListOf<LiveRecord>()

    var partial by mutableStateOf("")
        private set
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("按「開始」後直接說話")
        private set

    private var transcriber: LiveTranscriber? = null
    private var current: LiveRecord? = null
    private var utteranceStartMs = -1L

    private fun now() = System.currentTimeMillis()

    fun start() {
        if (running) return
        val ctx = getApplication<Application>()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            status = "此裝置沒有可用的語音辨識服務，請安裝或啟用「Google」App。"
            return
        }
        val s = AppSettings(ctx)
        // 以按下「開始」當下的手機時間建立一筆新紀錄
        val t = now()
        val rec = LiveRecord(id = t, startMs = t, endMs = t, entries = emptyList())
        current = rec
        screen.add(rec)
        utteranceStartMs = -1L
        partial = ""
        running = true
        status = "啟動中…（開始時間 ${RecordFormat.dateTime(t)}）"
        transcriber = LiveTranscriber(ctx, s.preferOffline, s.muteBeep, object : LiveTranscriber.Callback {
            override fun onSpeechStart() {
                if (utteranceStartMs < 0) utteranceStartMs = now()
            }

            override fun onPartial(text: String) {
                if (utteranceStartMs < 0) utteranceStartMs = now()
                partial = text
            }

            override fun onFinal(text: String) = commit(text)
            override fun onStatus(msg: String) { status = msg }
            override fun onFatal(msg: String) {
                transcriber = null
                status = msg + "\n" + finishSession()
            }
        }).also { it.start() }
    }

    private fun updateCurrent(transform: (LiveRecord) -> LiveRecord) {
        val c = current ?: return
        val n = transform(c)
        current = n
        val i = screen.indexOfFirst { it.id == c.id }
        if (i >= 0) screen[i] = n
    }

    private fun commit(text: String) {
        val end = now()
        val start = if (utteranceStartMs > 0) utteranceStartMs else end
        updateCurrent { it.copy(endMs = end, entries = it.entries + LiveEntry(start, end, text.trim())) }
        partial = ""
        utteranceStartMs = -1L
        // 每句完成就先存一次，避免 App 被系統關閉時遺失內容
        persistCurrent()
    }

    private fun persistCurrent() {
        val c = current ?: return
        if (c.entries.isEmpty()) return
        val i = saved.indexOfFirst { it.id == c.id }
        if (i >= 0) saved[i] = c else saved.add(0, c)
        persist()
    }

    @Volatile private var pendingSnapshot: List<LiveRecord> = emptyList()
    private val saveMutex = Mutex()

    private fun persist() {
        pendingSnapshot = saved.toList()
        viewModelScope.launch(Dispatchers.IO) {
            // 一律寫入「最新」的快照，避免多次寫入順序顛倒造成舊資料覆蓋新資料
            saveMutex.withLock {
                try { store.save(pendingSnapshot) } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        transcriber?.stop()
        transcriber = null
        status = finishSession()
    }

    /** 結束目前這筆紀錄並自動保存，回傳狀態訊息。 */
    private fun finishSession(): String {
        if (partial.isNotBlank()) commit(partial)
        partial = ""
        utteranceStartMs = -1L
        updateCurrent { it.copy(endMs = now()) }
        val c = current
        persistCurrent()
        current = null
        running = false
        return if (c != null && c.entries.isNotEmpty()) {
            "已停止，紀錄已自動保存（${c.entries.size} 句）"
        } else {
            if (c != null) screen.removeAll { it.id == c.id }
            "已停止（沒有辨識到內容，未產生紀錄）"
        }
    }

    /** 重置：只清除畫面上的逐字稿與暫存狀態，已保存的紀錄保留。 */
    fun reset() {
        if (running) stop()
        screen.clear()
        partial = ""
        utteranceStartMs = -1L
        status = "畫面已重置；先前的內容仍保存在「匯出紀錄」中"
    }

    /** 只有在「匯出紀錄」視窗中確認後才會呼叫。 */
    fun deleteRecords(ids: Set<Long>) {
        saved.removeAll { it.id in ids }
        persist()
    }

    override fun onCleared() {
        transcriber?.stop()
        transcriber = null
        if (running) {
            if (partial.isNotBlank()) commit(partial)
            updateCurrent { it.copy(endMs = now()) }
            val c = current
            if (c != null && c.entries.isNotEmpty()) {
                val i = saved.indexOfFirst { it.id == c.id }
                if (i >= 0) saved[i] = c else saved.add(0, c)
                pendingSnapshot = saved.toList()
                try { store.save(pendingSnapshot) } catch (_: Exception) {}
            }
        }
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
