package tw.transcribe.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/** 一段逐字稿（秒為單位）。 */
data class Segment(val startSec: Double, val endSec: Double, val text: String)

/** 一次即時轉寫紀錄，recordedAtMs 是按下開始時的手機時間。 */
data class LiveRecord(val recordedAtMs: Long, val segments: List<Segment>)

object TranscriptFormat {
    private val recordDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.TAIWAN)

    fun ts(sec: Double): String {
        val t = sec.toLong().coerceAtLeast(0)
        return "%02d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
    }

    fun toText(segs: List<Segment>, withTimestamps: Boolean): String =
        segs.joinToString("\n") {
            if (withTimestamps) "[${ts(it.startSec)}] ${it.text.trim()}" else it.text.trim()
        }

    fun toSrt(segs: List<Segment>): String = buildString {
        segs.forEachIndexed { i, s ->
            append(i + 1).append('\n')
            append(srtTs(s.startSec)).append(" --> ")
                .append(srtTs(max(s.endSec, s.startSec + 0.5))).append('\n')
            append(s.text.trim()).append("\n\n")
        }
    }

    fun toHistoryText(records: List<LiveRecord>, withTimestamps: Boolean): String =
        records.sortedBy { it.recordedAtMs }.joinToString("\n\n") { record ->
            "===== 錄音時間：${recordDateFormat.format(Date(record.recordedAtMs))} =====\n" +
                toText(record.segments, withTimestamps)
        }

    private fun srtTs(sec: Double): String {
        val ms = (sec * 1000).roundToLong().coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000)
    }
}

object LiveRecordStore {
    private const val PREFS = "live_history"
    private const val KEY = "records"

    fun load(ctx: Context): List<LiveRecord> {
        return try {
            val array = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
            (0 until array.length()).mapNotNull { index ->
                val record = array.optJSONObject(index) ?: return@mapNotNull null
                val segmentsJson = record.optJSONArray("segments") ?: return@mapNotNull null
                val segments = (0 until segmentsJson.length()).mapNotNull { segmentIndex ->
                    val segment = segmentsJson.optJSONObject(segmentIndex) ?: return@mapNotNull null
                    Segment(
                        segment.optDouble("start", 0.0),
                        segment.optDouble("end", 0.0),
                        segment.optString("text").trim(),
                    ).takeIf { it.text.isNotBlank() }
                }
                LiveRecord(record.optLong("recordedAt", 0L), segments).takeIf {
                    it.recordedAtMs > 0 && it.segments.isNotEmpty()
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, records: List<LiveRecord>) {
        val array = JSONArray()
        records.sortedBy { it.recordedAtMs }.forEach { record ->
            val segments = JSONArray()
            record.segments.forEach { segment ->
                segments.put(JSONObject().apply {
                    put("start", segment.startSec)
                    put("end", segment.endSec)
                    put("text", segment.text)
                })
            }
            array.put(JSONObject().apply {
                put("recordedAt", record.recordedAtMs)
                put("segments", segments)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}

object FileUtil {
    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "audio"
    }

    fun baseName(name: String): String = name.substringBeforeLast('.', name)

    fun writeText(ctx: Context, uri: Uri, text: String) {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    fun share(ctx: Context, text: String, title: String = "分享逐字稿") {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        ctx.startActivity(Intent.createChooser(send, title))
    }
}
