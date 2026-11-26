package tw.transcribe.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 即時轉寫的一句話；時間為說話當下的手機實際時間（epoch 毫秒）。 */
data class LiveEntry(val startMs: Long, val endMs: Long, val text: String)

/** 一筆即時轉寫紀錄：每次按下「開始」產生一筆，startMs 為按下開始的手機實際時間。 */
data class LiveRecord(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val entries: List<LiveEntry>,
)

object RecordFormat {
    private fun fmt(pattern: String, ms: Long) = SimpleDateFormat(pattern, Locale.TAIWAN).format(Date(ms))

    fun dateTime(ms: Long) = fmt("yyyy-MM-dd HH:mm:ss", ms)
    fun dateTimeWeek(ms: Long) = fmt("yyyy-MM-dd（EEEE）HH:mm:ss", ms)
    fun fileStamp(ms: Long) = fmt("yyyyMMdd_HHmmss", ms)

    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    /** 逐句時間：同一天只顯示時分秒，跨日時加上日期。 */
    fun entryTime(recordStartMs: Long, ms: Long) =
        if (sameDay(recordStartMs, ms)) fmt("HH:mm:ss", ms) else fmt("MM/dd HH:mm:ss", ms)

    fun duration(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) "%d 小時 %02d 分 %02d 秒".format(h, m, s) else "%d 分 %02d 秒".format(m, s)
    }

    fun recordText(r: LiveRecord, index: Int? = null): String = buildString {
        append("════════════════════════\n")
        if (index != null) append("紀錄 ").append(index).append('\n')
        append("錄音開始時間：").append(dateTimeWeek(r.startMs)).append('\n')
        append("錄音結束時間：").append(dateTime(r.endMs))
            .append("（共 ").append(duration(r.endMs - r.startMs)).append("）\n")
        append("句數：").append(r.entries.size).append('\n')
        append("────────────────────────\n")
        for (e in r.entries) {
            append('[').append(entryTime(r.startMs, e.startMs)).append("] ").append(e.text.trim()).append('\n')
        }
    }

    fun exportText(records: List<LiveRecord>): String = buildString {
        append("繁中逐字稿｜即時轉寫紀錄\n")
        append("匯出時間：").append(dateTime(System.currentTimeMillis())).append('\n')
        append("共 ").append(records.size).append(" 筆紀錄\n\n")
        records.sortedBy { it.startMs }.forEachIndexed { i, r ->
            append(recordText(r, i + 1)).append('\n')
        }
    }
}

/** 以 JSON 檔保存在 App 內部空間；重置畫面不會動到這裡的資料。 */
class RecordStore(ctx: Context) {
    private val file = File(ctx.applicationContext.filesDir, "live_records.json")
    private val lock = Any()

    fun load(): List<LiveRecord> = synchronized(lock) {
        try {
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val es = o.getJSONArray("entries")
                LiveRecord(
                    id = o.getLong("id"),
                    startMs = o.getLong("startMs"),
                    endMs = o.getLong("endMs"),
                    entries = (0 until es.length()).map { j ->
                        val e = es.getJSONObject(j)
                        LiveEntry(e.getLong("s"), e.getLong("e"), e.getString("t"))
                    },
                )
            }.sortedByDescending { it.startMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(records: List<LiveRecord>) = synchronized(lock) {
        val arr = JSONArray()
        for (r in records) {
            val es = JSONArray()
            for (e in r.entries) es.put(JSONObject().put("s", e.startMs).put("e", e.endMs).put("t", e.text))
            arr.put(JSONObject().put("id", r.id).put("startMs", r.startMs).put("endMs", r.endMs).put("entries", es))
        }
        // 先寫暫存檔再改名，避免寫到一半當機造成紀錄損毀
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }
}
