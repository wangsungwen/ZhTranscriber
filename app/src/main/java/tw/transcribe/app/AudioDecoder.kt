package tw.transcribe.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.exp

class AudioChunk(val file: File, val startSec: Double, val durationSec: Double)

/**
 * 把任何 Android 能解碼的音訊/影片（mp3、m4a、aac、wav、ogg、flac、mp4…）
 * 轉成 16kHz 單聲道 16-bit WAV，並依長度切段（避開雲端 API 的 25MB 上限）。
 */
object AudioDecoder {
    const val TARGET_RATE = 16000

    fun decodeToWavChunks(
        context: Context,
        uri: Uri,
        outDir: File,
        chunkSeconds: Int,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): List<AudioChunk> {
        outDir.mkdirs()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            extractor.release()
            throw IOException("無法讀取檔案：${e.message}")
        }

        val track = (0 until extractor.trackCount)
            .map { it to extractor.getTrackFormat(it) }
            .firstOrNull { it.second.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        if (track == null) {
            extractor.release()
            throw IOException("檔案中找不到音訊軌")
        }
        val trackIndex = track.first
        val format: MediaFormat = track.second
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        var isFloat = false
        val resampler = Resampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), TARGET_RATE)
        val writer = WavChunkWriter(outDir, TARGET_RATE, chunkSeconds.toLong() * TARGET_RATE)

        val codec = try {
            MediaCodec.createDecoderByType(mime).also {
                it.configure(format, null, null, 0)
                it.start()
            }
        } catch (e: Exception) {
            extractor.release()
            throw IOException("不支援的音訊格式（$mime）")
        }

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastReported = -1
        try {
            while (!outputDone) {
                if (isCancelled()) throw CancellationException("已取消")

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        if (isFloat) {
                            val fb = buf.asFloatBuffer()
                            val frames = fb.remaining() / channels
                            for (f in 0 until frames) {
                                var sum = 0f
                                for (c in 0 until channels) sum += fb.get()
                                resampler.push(sum / channels, writer)
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            val frames = sb.remaining() / channels
                            for (f in 0 until frames) {
                                var sum = 0f
                                for (c in 0 until channels) sum += sb.get() / 32768f
                                resampler.push(sum / channels, writer)
                            }
                        }
                        if (durationUs > 0) {
                            val pct = (info.presentationTimeUs * 100 / durationUs).toInt()
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress((pct / 100f).coerceIn(0f, 1f))
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) resampler.setSourceRate(of.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
            }
            return writer.finish()
        } catch (e: Throwable) {
            writer.abort()
            throw e
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            extractor.release()
        }
    }
}

/** 線性內插重取樣 + 單極低通（降取樣時抑制混疊）。 */
private class Resampler(srcRate: Int, private val dstRate: Int) {
    private var step = 1.0
    private var alpha = 1f
    private var t = 1.0
    private var prev = 0f
    private var lp = 0f

    init { setSourceRate(srcRate) }

    fun setSourceRate(rate: Int) {
        step = rate.toDouble() / dstRate
        alpha = if (rate > dstRate) (1.0 - exp(-2.0 * PI * 7000.0 / rate)).toFloat() else 1f
    }

    fun push(x: Float, w: WavChunkWriter) {
        lp += alpha * (x - lp)
        val cur = lp
        while (t <= 1.0) {
            w.write(prev + (cur - prev) * t.toFloat())
            t += step
        }
        t -= 1.0
        prev = cur
    }
}

private class WavChunkWriter(private val dir: File, private val rate: Int, private val maxSamples: Long) {
    private val chunks = mutableListOf<AudioChunk>()
    private var out: FileOutputStream? = null
    private var file: File? = null
    private val buf = ByteArray(1 shl 16)
    private var bufPos = 0
    private var count = 0L
    private var total = 0L
    private var index = 0

    fun write(sample: Float) {
        if (out == null) open()
        val s = (sample * 32767f).coerceIn(-32768f, 32767f).toInt()
        buf[bufPos++] = (s and 0xff).toByte()
        buf[bufPos++] = ((s shr 8) and 0xff).toByte()
        if (bufPos >= buf.size) flush()
        count++
        if (count >= maxSamples) close()
    }

    private fun flush() {
        out?.write(buf, 0, bufPos)
        bufPos = 0
    }

    private fun open() {
        val f = File(dir, "chunk_%03d.wav".format(index++))
        file = f
        out = FileOutputStream(f).also { it.write(ByteArray(44)) }
        count = 0
        bufPos = 0
    }

    private fun close() {
        val o = out ?: return
        flush()
        o.close()
        out = null
        val f = file!!
        writeHeader(f, count * 2)
        chunks.add(AudioChunk(f, total.toDouble() / rate, count.toDouble() / rate))
        total += count
    }

    fun finish(): List<AudioChunk> {
        close()
        return chunks.filter { it.durationSec >= 0.3 }
    }

    fun abort() {
        try { out?.close() } catch (_: Exception) {}
        out = null
    }

    private fun writeHeader(f: File, dataLen: Long) {
        RandomAccessFile(f, "rw").use { raf ->
            fun str(s: String) = raf.write(s.toByteArray(Charsets.US_ASCII))
            fun i32(v: Long) { raf.write((v and 0xff).toInt()); raf.write(((v shr 8) and 0xff).toInt()); raf.write(((v shr 16) and 0xff).toInt()); raf.write(((v shr 24) and 0xff).toInt()) }
            fun i16(v: Int) { raf.write(v and 0xff); raf.write((v shr 8) and 0xff) }
            raf.seek(0)
            str("RIFF"); i32(36 + dataLen); str("WAVE")
            str("fmt "); i32(16); i16(1); i16(1); i32(rate.toLong()); i32(rate * 2L); i16(2); i16(16)
            str("data"); i32(dataLen)
        }
    }
}
