package tw.transcribe.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlin.math.max
import kotlin.math.roundToLong

/** 一段逐字稿（秒為單位）。 */
data class Segment(val startSec: Double, val endSec: Double, val text: String)

object TranscriptFormat {
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

    private fun srtTs(sec: Double): String {
        val ms = (sec * 1000).roundToLong().coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000)
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
