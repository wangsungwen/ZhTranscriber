package tw.transcribe.app

import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI 相容的 /audio/transcriptions 介面。
 * 可用於 Groq（whisper-large-v3，免費額度）、OpenAI（whisper-1 / gpt-4o-transcribe），
 * 或自架的 faster-whisper / whisper.cpp server（相容 OpenAI 格式者）。
 */
class WhisperClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    suspend fun transcribe(file: File, prompt: String, offsetSec: Double, durationSec: Double): List<Segment> {
        val verbose = model.contains("whisper", ignoreCase = true)
        val url = baseUrl.trimEnd('/') + "/audio/transcriptions"

        for (attempt in 0..3) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", model)
                .addFormDataPart("language", "zh")
                .addFormDataPart("response_format", if (verbose) "verbose_json" else "json")
                .addFormDataPart("temperature", "0")
                .apply { if (prompt.isNotBlank()) addFormDataPart("prompt", prompt) }
                .build()
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            val result: Pair<Int, String> = try {
                http.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
            } catch (e: IOException) {
                if (attempt == 3) throw IOException("網路錯誤：${e.message}")
                delay(2000L * (attempt + 1))
                continue
            }
            val (code, txt) = result
            if (code in 200..299) return parse(txt, offsetSec, durationSec)
            if ((code == 429 || code >= 500) && attempt < 3) {
                delay(if (code == 429) 15_000L * (attempt + 1) else 3000L * (attempt + 1))
                continue
            }
            throw IOException(
                when (code) {
                    401 -> "API Key 無效（HTTP 401）"
                    413 -> "檔案太大（HTTP 413），請在設定把分段長度調小"
                    else -> "HTTP $code：${errorMessage(txt)}"
                }
            )
        }
        throw IOException("多次重試仍失敗")
    }

    private fun parse(txt: String, offset: Double, dur: Double): List<Segment> {
        val j = JSONObject(txt)
        val segs = j.optJSONArray("segments")
        if (segs != null && segs.length() > 0) {
            return (0 until segs.length()).map {
                val s = segs.getJSONObject(it)
                Segment(offset + s.optDouble("start", 0.0), offset + s.optDouble("end", 0.0), s.optString("text").trim())
            }.filter { it.text.isNotBlank() }
        }
        val t = j.optString("text").trim()
        return if (t.isBlank()) emptyList() else listOf(Segment(offset, offset + dur, t))
    }

    private fun errorMessage(txt: String): String = try {
        JSONObject(txt).optJSONObject("error")?.optString("message") ?: txt.take(200)
    } catch (_: Exception) {
        txt.take(200)
    }
}
