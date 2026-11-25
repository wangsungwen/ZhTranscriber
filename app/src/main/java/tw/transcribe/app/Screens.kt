package tw.transcribe.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("逐字稿", text))
    toast(ctx, "已複製")
}

private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmm", Locale.TAIWAN).format(Date())

// ───────────────────────── 即時轉寫 ─────────────────────────

@Composable
fun LiveScreen(vm: LiveViewModel) {
    val ctx = LocalContext.current
    val settings = remember { AppSettings(ctx) }
    val listState = rememberLazyListState()
    val view = LocalView.current

    DisposableEffect(vm.running) {
        view.keepScreenOn = vm.running
        onDispose { view.keepScreenOn = false }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.start() else toast(ctx, "需要麥克風權限才能即時轉寫")
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            try {
                FileUtil.writeText(ctx, uri, TranscriptFormat.toText(vm.segments, settings.showTimestamps))
                toast(ctx, "已儲存")
            } catch (e: Exception) {
                toast(ctx, "儲存失敗：${e.message}")
            }
        }
    }

    val count = vm.segments.size + if (vm.partial.isNotBlank()) 1 else 0
    LaunchedEffect(count, vm.partial) {
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    if (vm.running) vm.stop()
                    else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.start()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                colors = if (vm.running) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                modifier = Modifier.height(52.dp),
            ) {
                Text(if (vm.running) "■ 停止" else "● 開始", fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text(vm.status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()

        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                items(vm.segments.size) { i ->
                    val s = vm.segments[i]
                    Row(Modifier.padding(vertical = 4.dp)) {
                        if (settings.showTimestamps) {
                            Text(
                                TranscriptFormat.ts(s.startSec) + "  ",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 13.sp,
                            )
                        }
                        Text(s.text, fontSize = 18.sp, lineHeight = 26.sp)
                    }
                }
                if (vm.partial.isNotBlank()) {
                    item {
                        Text(
                            vm.partial,
                            fontSize = 18.sp,
                            lineHeight = 26.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val has = vm.segments.isNotEmpty()
            TextButton(enabled = has, onClick = { copy(ctx, TranscriptFormat.toText(vm.segments, settings.showTimestamps)) }) { Text("複製") }
            TextButton(enabled = has, onClick = { FileUtil.share(ctx, TranscriptFormat.toText(vm.segments, settings.showTimestamps)) }) { Text("分享") }
            TextButton(enabled = has, onClick = { saveLauncher.launch("即時逐字稿_${stamp()}.txt") }) { Text("存成TXT") }
            TextButton(enabled = has || vm.partial.isNotBlank(), onClick = { vm.clear() }) { Text("清除") }
        }
    }
}

// ───────────────────────── 檔案批次 ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatchScreen(vm: BatchViewModel) {
    val ctx = LocalContext.current
    val settings = remember { AppSettings(ctx) }
    val view = LocalView.current
    val expanded = remember { mutableStateListOf<Long>() }
    var pendingExport by remember { mutableStateOf<String?>(null) }

    DisposableEffect(vm.running) {
        view.keepScreenOn = vm.running
        onDispose { view.keepScreenOn = false }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addUris(uris)
    }
    val saveOne = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val content = pendingExport
        pendingExport = null
        if (uri != null && content != null) {
            try { FileUtil.writeText(ctx, uri, content); toast(ctx, "已儲存") } catch (e: Exception) { toast(ctx, "儲存失敗：${e.message}") }
        }
    }
    val exportAll = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            val n = exportAllTo(ctx, tree, vm.items.filter { it.status == ItemStatus.DONE }, settings.showTimestamps)
            toast(ctx, "已匯出 $n 個檔案")
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !vm.running, onClick = { pick.launch(arrayOf("audio/*", "video/*")) }) { Text("＋ 選擇音檔") }
            if (vm.running) {
                Button(onClick = { vm.cancel() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("停止") }
            } else {
                Button(enabled = vm.items.any { it.status != ItemStatus.DONE }, onClick = { vm.start() }) { Text("開始轉寫") }
            }
            OutlinedButton(enabled = vm.items.any { it.status == ItemStatus.DONE }, onClick = { exportAll.launch(null) }) { Text("全部匯出") }
            TextButton(enabled = !vm.running && vm.items.isNotEmpty(), onClick = { vm.clearAll() }) { Text("清空") }
        }
        if (settings.apiKey.isBlank()) {
            Text("⚠ 尚未設定 API Key，請先到「設定」分頁填寫。", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp))
        }
        if (vm.message.isNotBlank()) {
            Text(vm.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp))
        }
        if (vm.items.isEmpty()) {
            Text(
                "可一次選多個檔案（mp3、m4a、wav、aac、ogg、flac、mp4 影片…），也可以在錄音機或 LINE 中「分享」音檔到本 App。\n\n長檔案會自動切段處理，完成後可匯出 TXT（含時間戳記）或 SRT 字幕。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
        }

        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.items, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            item.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.status == ItemStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (item.status == ItemStatus.DECODING || item.status == ItemStatus.TRANSCRIBING) {
                            LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                        val hasText = item.segments.isNotEmpty()
                        val isOpen = item.id in expanded
                        if (hasText && isOpen) {
                            SelectionContainer {
                                Text(
                                    TranscriptFormat.toText(item.segments, settings.showTimestamps),
                                    fontSize = 16.sp,
                                    lineHeight = 24.sp,
                                    modifier = Modifier.padding(top = 8.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                                )
                            }
                        }
                        FlowRow {
                            if (hasText) {
                                TextButton(onClick = { if (isOpen) expanded.remove(item.id) else expanded.add(item.id) }) { Text(if (isOpen) "收合" else "查看") }
                                TextButton(onClick = { copy(ctx, TranscriptFormat.toText(item.segments, settings.showTimestamps)) }) { Text("複製") }
                                TextButton(onClick = { FileUtil.share(ctx, TranscriptFormat.toText(item.segments, settings.showTimestamps)) }) { Text("分享") }
                            }
                            if (item.status == ItemStatus.DONE) {
                                TextButton(onClick = {
                                    pendingExport = TranscriptFormat.toText(item.segments, settings.showTimestamps)
                                    saveOne.launch(FileUtil.baseName(item.name) + ".txt")
                                }) { Text("存TXT") }
                                TextButton(onClick = {
                                    pendingExport = TranscriptFormat.toSrt(item.segments)
                                    saveOne.launch(FileUtil.baseName(item.name) + ".srt")
                                }) { Text("存SRT") }
                            }
                            if (!vm.running && (item.status == ItemStatus.ERROR || item.status == ItemStatus.CANCELLED)) {
                                TextButton(onClick = { vm.retry(item) }) { Text("重試") }
                            }
                            if (item.status != ItemStatus.DECODING && item.status != ItemStatus.TRANSCRIBING) {
                                TextButton(onClick = { vm.remove(item) }) { Text("移除") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun exportAllTo(ctx: Context, tree: Uri, done: List<BatchItem>, withTs: Boolean): Int {
    var n = 0
    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    for (item in done) {
        val base = FileUtil.baseName(item.name)
        try {
            DocumentsContract.createDocument(ctx.contentResolver, parent, "text/plain", "$base.txt")?.let {
                FileUtil.writeText(ctx, it, TranscriptFormat.toText(item.segments, withTs)); n++
            }
            DocumentsContract.createDocument(ctx.contentResolver, parent, "application/octet-stream", "$base.srt")?.let {
                FileUtil.writeText(ctx, it, TranscriptFormat.toSrt(item.segments)); n++
            }
        } catch (_: Exception) {
        }
    }
    return n
}

// ───────────────────────── 設定 ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val s = remember { AppSettings(ctx) }
    var baseUrl by remember { mutableStateOf(s.baseUrl) }
    var apiKey by remember { mutableStateOf(s.apiKey) }
    var model by remember { mutableStateOf(s.model) }
    var prompt by remember { mutableStateOf(s.prompt) }
    var chunk by remember { mutableStateOf(s.chunkMinutes.toString()) }
    var showKey by remember { mutableStateOf(false) }
    var showTs by remember { mutableStateOf(s.showTimestamps) }
    var offline by remember { mutableStateOf(s.preferOffline) }
    var mute by remember { mutableStateOf(s.muteBeep) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("檔案批次轉寫（雲端 Whisper）", style = MaterialTheme.typography.titleMedium)
        Text(
            "使用 OpenAI 相容的語音轉文字 API。推薦 Groq（whisper-large-v3，有免費額度，速度快）：到 console.groq.com 註冊取得 API Key。",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                baseUrl = "https://api.groq.com/openai/v1"; model = "whisper-large-v3"
                s.baseUrl = baseUrl; s.model = model
            }) { Text("套用 Groq") }
            OutlinedButton(onClick = {
                baseUrl = "https://api.openai.com/v1"; model = "whisper-1"
                s.baseUrl = baseUrl; s.model = model
            }) { Text("套用 OpenAI") }
        }
        OutlinedTextField(baseUrl, { baseUrl = it; s.baseUrl = it }, label = { Text("API 網址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            apiKey, { apiKey = it; s.apiKey = it },
            label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隱藏" else "顯示") } },
        )
        OutlinedTextField(model, { model = it; s.model = it }, label = { Text("模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            prompt, { prompt = it; s.prompt = it },
            label = { Text("提示詞（可加入專有名詞提升準確度）") }, modifier = Modifier.fillMaxWidth(), minLines = 2,
        )
        OutlinedTextField(
            chunk,
            { v -> chunk = v.filter { it.isDigit() }.take(2); chunk.toIntOrNull()?.let { s.chunkMinutes = it } },
            label = { Text("分段長度（分鐘，1–12）") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()
        Text("即時轉寫（手機內建語音辨識）", style = MaterialTheme.typography.titleMedium)
        SwitchRow("優先離線辨識（需先下載「中文（台灣）」離線語音包）", offline) { offline = it; s.preferOffline = it }
        SwitchRow("靜音每句之間的提示音", mute) { mute = it; s.muteBeep = it }

        HorizontalDivider()
        Text("輸出", style = MaterialTheme.typography.titleMedium)
        SwitchRow("逐字稿顯示／匯出時間戳記", showTs) { showTs = it; s.showTimestamps = it }

        Spacer(Modifier.height(8.dp))
        Text(
            "說明：API Key 只儲存在本機。即時轉寫使用手機的 Google 語音服務，不需要 API Key；若結果出現簡體字，請確認「Google」App 的語音語言為中文（台灣）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
