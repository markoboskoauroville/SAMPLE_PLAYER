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

    private val _live = MutableStateFlow(FloatArray(0))

    /**
     * THE WAVEFORM AS IT FORMS, so the tile shows the recording rather than a blank box.
     *
     * A recorder that shows nothing until it stops asks you to talk into a hole and find out
     * afterwards. The level meter says audio is arriving; only the shape says what arrived. On the
     * phone, with the tile in front of you, this is the difference between knowing the take was
     * good and pressing play to find out.
     *
     * ONE PEAK PER READ BLOCK, not one per sample. A block is about 250ms at this buffer size, so
     * a ninety second ceiling is under four hundred values and the array never needs trimming
     * during a normal take. Past [LIVE_MAX] it drops every other value and halves the rate, which
     * is the same thing a waveform does when it is drawn narrower.
     */
    val live: StateFlow<FloatArray> = _live

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    private val vu = Vu()

    /**
     * 44.1 kHz FIRST, and it is the format this app records in unless the phone refuses it.
     *
     * WAV, 44.1 kHz, mono, 16-bit. Not 48: 44.1 is what the material this ends up in is cut at,
     * and a file that matches the timeline is one resample fewer between the microphone and the
     * finished thing. 48 is kept as the fallback because a phone that will not open 44.1 will
     * usually open 48, and 16 below that because an early take is better than none.
     *
     * MONO ON PURPOSE, not as a compromise. One voice, one phone microphone, and a stereo file
     * would be two copies of the same signal at twice the size.
     *
     * PROBED, NOT ASSUMED. getMinBufferSize returns an error for a rate the device will not take,
     * and a recorder that initialises on a refused rate records silence.
     */
    val RATES = intArrayOf(44_100, 48_000, 16_000)

    @Volatile private var probed = 0

    fun bestRate(): Int {
        if (probed != 0) return probed
        for (rate in RATES) {
            val size = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (size > 0) {
                probed = rate
                return rate
            }
        }
        probed = 16_000
        return probed
    }

    /**
     * THE SILENCE CEILING LIVES IN [Ceiling], NOT HERE.
     *
     * It used to be four lines inside the reading thread below, where Test 1 could not reach it.
     * A guard against losing a recording that cannot itself be tested is a guard nobody can trust.
     */

    @SuppressLint("MissingPermission")
    /**
     * Record into [target]. When [promoteTo] is given, [target] is a scratch file and is moved on
     * to [promoteTo] only if the take turns out to be usable.
     */
    fun start(
        target: File,
        slotIndex: Int,
        promoteTo: File? = null,
        onEnded: (SampleQuality) -> Unit,
    ) {
        if (running) return
        target.parentFile?.mkdirs()

        val rate = bestRate()
        val minBuf = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            rate,
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
        _live.value = FloatArray(0)
        vu.reset()

        worker = thread(name = "sample-recorder") {
            val raf = RandomAccessFile(target, "rw")
            raf.setLength(0)
            writeWavHeader(raf, 0, rate)

            val buf = ShortArray(minBuf)
            val bytes = ByteArray(minBuf * 2)
            var written = 0L
            val began = System.currentTimeMillis()
            val ceiling = Ceiling()
            val shape = ArrayList<Float>(LIVE_MAX)
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

                // The shape so far. Raw peak rather than the smoothed meter value: the meter is
                // smoothed so it is watchable, and a smoothed waveform is a lie about what was
                // recorded.
                shape.add((peak / 32767f).coerceIn(0f, 1f))
                if (shape.size > LIVE_MAX) {
                    val halved = ArrayList<Float>(LIVE_MAX / 2 + 1)
                    var i = 0
                    while (i < shape.size) { halved.add(shape[i]); i += 2 }
                    shape.clear()
                    shape.addAll(halved)
                }
                _live.value = shape.toFloatArray()

                val now = System.currentTimeMillis()
                _elapsedMs.value = (now - began).toInt()

                if (ceiling.exceeded(lvl, now)) running = false

                for (i in 0 until n) {
                    val s = buf[i].toInt()
                    bytes[i * 2] = (s and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    // Kept for the quality check. Sixteen kHz mono for ninety seconds is under
                    // three megabytes, which is small enough to hold and check before storing.
                    if (collected.size < rate * 120) collected.add(buf[i])
                }
                raf.write(bytes, 0, n * 2)
                written += n * 2L
            }
            rec.stop()
            rec.release()

            writeWavHeader(raf, written, rate)
            raf.close()

                _level.value = 0f
            _live.value = FloatArray(0)
            _slot.value = null
            record = null

            // JUDGE THE RECORDING BEFORE IT COUNTS AS ONE. A slot that fills with two seconds of
            // room tone looks completely successful: the tile is solid, the count is right, the
            // waveform has a shape, and the only symptom appears later in the transcription.
            val quality = SampleCheck.assess(collected.toShortArray(), rate)
            if (quality != SampleQuality.GOOD) {
                // The pending file goes, and whatever was in the slot before is still there.
                // A retake that goes wrong must not destroy the take it was replacing.
                target.delete()
                onEnded(quality)
                return@thread
            }

            // NORMALISE, AND ONLY AFTER THE TAKE HAS PASSED. Order matters both ways round: the
            // check must see the recording as it was made, or a quiet-but-usable take and a
            // room-tone take look identical once both have been pulled up to the same peak. And
            // the file must be rewritten before anybody plays it, or thirty phrases recorded
            // across a week play back as thirty different volumes.
            //
            // Read, scale, write. It is a whole extra pass over the file, which for a phrase is a
            // few hundred kilobytes and a few milliseconds, and it happens once per take rather
            // than once per playback.
            val raw = read(target)
            val loud = normalise(raw)
            if (loud !== raw) writeWav(target, loud, rate)

            // PROMOTE. The take has been judged and levelled, so it becomes the recording. This is
            // the moment a retake replaces what was there, and it is the last thing that happens
            // rather than the first.
            if (promoteTo != null) {
                if (!target.renameTo(promoteTo)) {
                    target.copyTo(promoteTo, overwrite = true)
                    target.delete()
                }
            }
            onEnded(quality)
        }
    }

    fun stop() {
        running = false
    }

    val isRecording: Boolean get() = running

    /** Sixteen-bit mono PCM, sample rate from [Dsp], written twice: once empty, once with sizes. */
    private fun writeWavHeader(raf: RandomAccessFile, dataBytes: Long, sr: Int) {
        raf.seek(0)
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

    /** Past this many live values the shape is halved rather than grown without a bound. */
    const val LIVE_MAX = 512

    /**
     * Write a whole WAV in one go, used to replace a take with its normalised self.
     *
     * A temporary file and a rename, not a truncate in place. A process killed halfway through
     * rewriting a recording over itself leaves a corrupt file where a good one was, and this is
     * the one file in the app that cannot be made again.
     */
    fun writeWav(target: File, samples: ShortArray, rate: Int) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(0)
            writeWavHeader(raf, samples.size * 2L, rate)
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = samples[i].toInt()
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            raf.seek(44)
            raf.write(bytes)
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * WHERE THE AUDIO ACTUALLY STARTS, AND AT WHAT RATE.
     *
     * NOT BYTE 44. That is where it starts in a file this app wrote, and nowhere else. Speechify
     * returns WAV with a `LIST` chunk between `fmt ` and `data`, so the audio begins at byte 78 —
     * reading from 44 gives you twenty-six bytes of metadata rendered as a click and every sample
     * after it shifted. And its `data` size field is `0xFFFFFFFF`, a streaming placeholder, so a
     * length taken from the header is four gigabytes.
     *
     * Measured 30.8.2026: Speechify WAV is 48 kHz mono 16-bit PCM with `fmt `, `LIST`, `data`.
     *
     * Returns offset, rate and the number of sample frames.
     */
    private fun layout(file: File): Triple<Int, Int, Int> {
        val fallback = Triple(44, Dsp.SAMPLE_RATE, 0)
        if (!file.isFile || file.length() < 44) return fallback
        val head = file.inputStream().use { it.readNBytes(4096) }
        if (head.size < 44) return fallback

        fun le32(at: Int): Long =
            (head[at].toLong() and 0xFF) or
                ((head[at + 1].toLong() and 0xFF) shl 8) or
                ((head[at + 2].toLong() and 0xFF) shl 16) or
                ((head[at + 3].toLong() and 0xFF) shl 24)

        fun tag(at: Int) = String(head, at, 4, Charsets.US_ASCII)

        if (tag(0) != "RIFF" || tag(8) != "WAVE") return fallback

        var rate = Dsp.SAMPLE_RATE
        var i = 12
        // Bounded: a header longer than the buffer is a file this app should not be reading.
        while (i + 8 <= head.size) {
            val id = tag(i)
            val size = le32(i + 4)
            val body = i + 8
            if (id == "fmt " && body + 8 <= head.size) {
                val r = le32(body + 4).toInt()
                if (r in 8_000..192_000) rate = r
            }
            if (id == "data") {
                // 0xFFFFFFFF is a streaming placeholder, and so is any size past the file.
                val onDisk = file.length() - body
                val frames = (if (size <= 0 || size > onDisk) onDisk else size) / 2
                return Triple(body, rate, frames.toInt())
            }
            if (size <= 0) break
            i = body + size.toInt() + (size.toInt() and 1)
        }
        return Triple(44, rate, ((file.length() - 44) / 2).toInt())
    }

    /** The rate this file was made at, from its own header. */
    fun rateOf(file: File): Int = layout(file).second

    /** Length in milliseconds, from the real data chunk rather than from the file size. */
    fun lengthMs(file: File): Int {
        val (_, rate, frames) = layout(file)
        if (frames <= 0) return 0
        return (frames.toLong() * 1000L / rate).toInt()
    }

    /**
     * Read a WAV back as samples, for the waveform and the quality check.
     *
     * Through [layout], so it reads the same bytes the player will, whoever wrote the file.
     */
    fun read(file: File): ShortArray {
        val (offset, _, frames) = layout(file)
        if (frames <= 0) return ShortArray(0)
        val raw = file.readBytes()
        val n = minOf(frames, (raw.size - offset) / 2).coerceAtLeast(0)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val lo = raw[offset + i * 2].toInt() and 0xFF
            val hi = raw[offset + i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }


}
