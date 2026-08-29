package com.mantra.sampleplayer

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * THE MICROPHONE, AND IT IS HELD BY EXACTLY ONE OBJECT.
 *
 * `MINIMALIST_STOPWATCH` v12 inverted this ownership after five versions of trying it the other
 * way round: the recogniser and the meter both opened the microphone, and on this phone the
 * second one to ask got nothing. Whatever is drawing a level and whatever is writing a file must
 * be reading the same buffer, not competing for the same device.
 *
 * So: one `AudioRecord`, one reading thread, a level anybody can observe, and a file written from
 * the same frames the level was computed from.
 */
object Recorder {

    private val _level = MutableStateFlow(0f)

    /** 0..1, smoothed and floored, for every meter in the app. */
    val level: StateFlow<Float> = _level

    private val _slot = MutableStateFlow<Int?>(null)

    /** Which slot is being recorded into, or null. The overlay reads this to show its number. */
    val slot: StateFlow<Int?> = _slot

    private val _elapsedMs = MutableStateFlow(0)
    val elapsedMs: StateFlow<Int> = _elapsedMs

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    private val vu = Vu()

    /**
     * THE SILENCE CEILING.
     *
     * Press-to-stop was asked for because these phrases are of unknown length. The obvious failure
     * of press-to-stop is a slot left open by mistake, and with the app in the background there is
     * nothing on screen to notice it. So a capture that hears nothing for this long ends itself.
     *
     * It is deliberately long. Ninety seconds is far past any pause inside a spoken sentence and
     * far short of filling a phone.
     */
    const val SILENCE_CEILING_MS = 90_000L

    /** Below this the level counts as silence for the ceiling. Capture.ONSET, same signal. */
    private const val ONSET = Capture.ONSET

    @SuppressLint("MissingPermission")
    fun start(target: File, slotIndex: Int, onEnded: (SampleQuality) -> Unit) {
        if (running) return
        target.parentFile?.mkdirs()

        val minBuf = AudioRecord.getMinBufferSize(
            Dsp.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            Dsp.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onEnded(SampleQuality.SILENT)
            return
        }

        record = rec
        running = true
        _slot.value = slotIndex
        _elapsedMs.value = 0
        vu.reset()

        worker = thread(name = "sample-recorder") {
            val raf = RandomAccessFile(target, "rw")
            raf.setLength(0)
            writeWavHeader(raf, 0)

            val buf = ShortArray(minBuf)
            val bytes = ByteArray(minBuf * 2)
            var written = 0L
            var quietSince = 0L
            val began = System.currentTimeMillis()
            val collected = ArrayList<Short>()

            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue

                var peak = 0
                for (i in 0 until n) {
                    val v = abs(buf[i].toInt())
                    if (v > peak) peak = v
                }
                val lvl = vu.fromPeak(peak)
                _level.value = lvl

                val now = System.currentTimeMillis()
                _elapsedMs.value = (now - began).toInt()

                if (lvl >= ONSET) {
                    quietSince = 0L
                } else {
                    if (quietSince == 0L) quietSince = now
                    if (now - quietSince >= SILENCE_CEILING_MS) running = false
                }

                for (i in 0 until n) {
                    val s = buf[i].toInt()
                    bytes[i * 2] = (s and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    // Kept for the quality check. Sixteen kHz mono for ninety seconds is under
                    // three megabytes, which is small enough to hold and check before storing.
                    if (collected.size < Dsp.SAMPLE_RATE * 120) collected.add(buf[i])
                }
                raf.write(bytes, 0, n * 2)
                written += n * 2L
            }
            rec.stop()
            rec.release()

            writeWavHeader(raf, written)
            raf.close()

            _level.value = 0f
            _slot.value = null
            record = null

            // JUDGE THE RECORDING BEFORE IT COUNTS AS ONE. A slot that fills with two seconds of
            // room tone looks completely successful: the line is solid, the count is right, the
            // waveform has a shape, and the only symptom appears later in the transcription.
            val quality = SampleCheck.assess(collected.toShortArray())
            if (quality != SampleQuality.GOOD) target.delete()
            onEnded(quality)
        }
    }

    fun stop() {
        running = false
    }

    val isRecording: Boolean get() = running

    /** Sixteen-bit mono PCM, sample rate from [Dsp], written twice: once empty, once with sizes. */
    private fun writeWavHeader(raf: RandomAccessFile, dataBytes: Long) {
        raf.seek(0)
        val sr = Dsp.SAMPLE_RATE
        val byteRate = sr * 2
        fun le32(v: Long) = byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte(),
        )
        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        raf.write("RIFF".toByteArray())
        raf.write(le32(36 + dataBytes))
        raf.write("WAVEfmt ".toByteArray())
        raf.write(le32(16))
        raf.write(le16(1))
        raf.write(le16(1))
        raf.write(le32(sr.toLong()))
        raf.write(le32(byteRate.toLong()))
        raf.write(le16(2))
        raf.write(le16(16))
        raf.write("data".toByteArray())
        raf.write(le32(dataBytes))
        if (dataBytes > 0) raf.seek(44 + dataBytes)
    }

    /** Read a WAV back as samples, for the waveform and the quality check. */
    fun read(file: File): ShortArray {
        if (!file.isFile || file.length() <= 44) return ShortArray(0)
        val raw = file.readBytes()
        val n = (raw.size - 44) / 2
        val out = ShortArray(n)
        for (i in 0 until n) {
            val lo = raw[44 + i * 2].toInt() and 0xFF
            val hi = raw[44 + i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }

    /** Length in milliseconds from the file size, without decoding it. */
    fun lengthMs(file: File): Int {
        if (!file.isFile || file.length() <= 44) return 0
        return (((file.length() - 44) / 2) * 1000L / Dsp.SAMPLE_RATE).toInt()
    }
}
