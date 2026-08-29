package com.mantra.sampleplayer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * MATCHING A SPOKEN WORD AGAINST A RECORDING OF IT, AND IT IMPORTS NOTHING.
 *
 * WHY THIS EXISTS AT ALL. SpeechRecognizer does not work on this phone. Four versions of evidence
 * say so: the meter proves AudioRecord opens the microphone and delivers audio, and every time
 * the recogniser is handed that microphone it churns and plays a tone and recognises nothing.
 * There is no fifth thing to try inside that API. So it is gone, and with it the tone, because
 * the tone was the recogniser's session boundary and there are no longer any sessions.
 *
 * WHAT REPLACES IT. Baba records himself saying each command once. At run time the incoming audio
 * is compared against those three recordings and the closest one wins, if it is close enough.
 * This is older and dumber than speech recognition and it is better here for four reasons:
 *
 *   it needs no model, no network and no Google service, so nothing can be missing
 *   it makes no sound, because nothing is being started and stopped
 *   it is language-agnostic: the template is whatever he actually said, in whatever language,
 *     with whatever accent, so "kreni" and "start" are the same problem
 *   it is testable end to end on a plain JVM, which none of the recogniser path ever was
 *
 * WHAT IT COSTS, plainly. It only knows the voice that recorded it, in roughly the conditions it
 * was recorded in. A different room or a cold will hurt it. It has no idea what a word means, so
 * a word that merely SOUNDS like the template will match. And the templates have to be recorded
 * before anything works at all, which is a step that did not exist before.
 *
 * HOW IT WORKS, in the order the audio moves:
 *
 *   frames        25ms of samples every 10ms, Hamming windowed
 *   spectrum      a radix-2 FFT, magnitude only, phase discarded
 *   mel bands     20 triangular bands on a mel scale, so the resolution follows the ear
 *   log           because loudness is multiplicative and distance should not be
 *   normalise     each frame minus the mean of the whole utterance, which is what makes this
 *                 survive a change of volume or a change of microphone gain
 *   endpoint      leading and trailing quiet frames trimmed, so "start" and "  start  " are
 *                 the same utterance
 *   DTW           the two sequences aligned in time, because nobody says a word at the same
 *                 speed twice, and a straight frame-by-frame comparison would fail on that alone
 */
object Dsp {

    const val SAMPLE_RATE = 16_000
    const val FRAME = 400          // 25ms
    const val HOP = 160            // 10ms
    const val FFT_SIZE = 512
    const val BANDS = 20

    // ── the transform ────────────────────────────────────────────────────────────────────────

    /**
     * An in-place iterative radix-2 FFT. Real input, complex output in the two arrays given.
     *
     * Written out rather than pulled in because the whole point of this file is that it can be
     * walked by Test 1 with no Android and no dependencies, and because a transform is the kind
     * of thing whose correctness should be visible rather than trusted.
     */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n and (n - 1) == 0) { "FFT size must be a power of two, was $n" }

        // Bit reversal.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun melOf(hz: Double) = 2595.0 * kotlin.math.log10(1.0 + hz / 700.0)
    private fun hzOf(mel: Double) = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    /** Triangular filter edges, in FFT bin numbers, evenly spaced on the mel scale. */
    private val edges: IntArray by lazy {
        val low = melOf(80.0)
        val high = melOf(SAMPLE_RATE / 2.0)
        IntArray(BANDS + 2) { i ->
            val mel = low + (high - low) * i / (BANDS + 1)
            (hzOf(mel) * FFT_SIZE / SAMPLE_RATE).toInt().coerceIn(0, FFT_SIZE / 2)
        }
    }

    private val window: DoubleArray by lazy {
        DoubleArray(FRAME) { 0.54 - 0.46 * cos(2.0 * PI * it / (FRAME - 1)) }
    }

    // ── features ─────────────────────────────────────────────────────────────────────────────

    /**
     * The utterance as a sequence of 20-band log-mel frames, endpointed and normalised.
     *
     * Returns an empty list for silence, which is the honest answer and lets everything above
     * treat "nothing was said" as a case rather than as an error.
     */
    fun features(samples: ShortArray): List<DoubleArray> {
        if (samples.size < FRAME) return emptyList()
        val frames = ArrayList<DoubleArray>()
        val energies = ArrayList<Double>()

        var start = 0
        while (start + FRAME <= samples.size) {
            val re = DoubleArray(FFT_SIZE)
            val im = DoubleArray(FFT_SIZE)
            var power = 0.0
            for (i in 0 until FRAME) {
                val v = samples[start + i] / 32768.0
                power += v * v
                re[i] = v * window[i]
            }
            fft(re, im)

            val band = DoubleArray(BANDS)
            for (b in 0 until BANDS) {
                val lo = edges[b]
                val mid = edges[b + 1]
                val hi = edges[b + 2]
                var sum = 0.0
                for (k in lo until hi) {
                    val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                    // Triangular weighting: full at the middle edge, zero at the two outer ones.
                    val w = if (k < mid) {
                        if (mid == lo) 1.0 else (k - lo).toDouble() / (mid - lo)
                    } else {
                        if (hi == mid) 1.0 else (hi - k).toDouble() / (hi - mid)
                    }
                    sum += mag * w
                }
                // The floor stops log going to negative infinity on a silent band, which would
                // poison every distance computed against it.
                band[b] = ln(sum + 1e-9)
            }
            frames.add(band)
            energies.add(power / FRAME)
            start += HOP
        }

        val kept = endpoint(frames, energies)
        return normalise(kept)
    }

    /**
     * Trims leading and trailing quiet frames. The threshold is relative to the loudest frame of
     * this utterance rather than absolute, because an absolute one would be a guess about a
     * microphone gain nobody has measured.
     */
    private fun endpoint(frames: List<DoubleArray>, energies: List<Double>): List<DoubleArray> {
        if (frames.isEmpty()) return frames
        val peak = energies.max()
        if (peak <= 0.0) return emptyList()
        val floor = peak * SILENCE_FRACTION
        var first = energies.indexOfFirst { it >= floor }
        var last = energies.indexOfLast { it >= floor }
        if (first < 0 || last < first) return emptyList()
        // A little air either side, so a soft consonant at the edge is not cut off.
        first = (first - 3).coerceAtLeast(0)
        last = (last + 3).coerceAtMost(frames.size - 1)
        return frames.subList(first, last + 1)
    }

    /**
     * EACH FRAME MINUS ITS OWN MEAN ACROSS THE BANDS. This is what makes the matcher survive
     * loudness: a quiet "start" and a loud one differ by a constant in the log domain, and
     * subtracting that constant removes exactly the gain while leaving the SHAPE of the spectrum,
     * which is the part that says which word it was.
     *
     * THE FIRST VERSION SUBTRACTED THE MEAN ACROSS THE UTTERANCE INSTEAD, and Test 1 found it by
     * the back door. A steady tone has the same spectrum in every frame, so the per-utterance
     * mean IS each frame, and subtracting it left every frame a vector of zeros. Cosine distance
     * between two zero vectors is numerical noise, and the matcher scored a tone as further from
     * a recording of itself than from a different tone entirely.
     *
     * Real speech is not stationary, so on a voice the fault would have been milder and much
     * harder to see — a matcher that works badly rather than one that visibly cannot work. The
     * synthetic signals in the test are pathological on purpose and that is what made it obvious.
     */
    private fun normalise(frames: List<DoubleArray>): List<DoubleArray> =
        frames.map { f ->
            var mean = 0.0
            for (b in 0 until BANDS) mean += f[b]
            mean /= BANDS
            DoubleArray(BANDS) { b -> f[b] - mean }
        }

    // ── comparison ───────────────────────────────────────────────────────────────────────────

    /** Cosine distance, 0 for identical direction and 2 for opposite. */
}

enum class SampleQuality {
    /** Usable. */
    GOOD,

    /** Nothing crossed the endpointer: the microphone heard a room, not a word. */
    SILENT,

    /** Something was said, but too little of it to align against anything. */
    TOO_SHORT,

    /** Loud enough to be clipping, which destroys the spectrum the matcher compares. */
    CLIPPED,
}

object SampleCheck {

    /** Below a quarter of a second of speech there is not enough to warp against. */
    const val MIN_FRAMES = 25

    /** A tenth of the samples at the rail is not a loud voice, it is a broken recording. */
    const val CLIP_FRACTION = 0.10

    fun assess(samples: ShortArray): SampleQuality {
        if (samples.isEmpty()) return SampleQuality.SILENT

        var clipped = 0
        for (v in samples) if (v >= 32000 || v <= -32000) clipped++
        if (clipped > samples.size * CLIP_FRACTION) return SampleQuality.CLIPPED

        val frames = Dsp.features(samples)
        if (frames.isEmpty()) return SampleQuality.SILENT
        if (frames.size < MIN_FRAMES) return SampleQuality.TOO_SHORT
        return SampleQuality.GOOD
    }

    fun describe(q: SampleQuality): String = when (q) {
        SampleQuality.GOOD -> "saved"
        SampleQuality.SILENT -> "nothing heard, try again"
        SampleQuality.TOO_SHORT -> "too short, say the whole word"
        SampleQuality.CLIPPED -> "too loud, move back a little"
    }
}

/**
 * THE WAVEFORM ON THE PAD.
 *
 * A pad that says only "filled" tells you a recording exists. A pad with the shape of the
 * recording on it tells you WHICH recording, whether the word is centred in it, and whether what
 * you captured was a word at all or a cough at one end and silence at the other. On an Akai the
 * waveform is not decoration, it is how you know what is under your finger.
 *
 * Returns [buckets] peak amplitudes, 0..1, oldest first.
 */
fun waveform(samples: ShortArray, buckets: Int): FloatArray {
    if (samples.isEmpty() || buckets <= 0) return FloatArray(0)
    val out = FloatArray(buckets)
    val per = samples.size.toDouble() / buckets
    for (b in 0 until buckets) {
        val from = (b * per).toInt()
        val to = minOf(samples.size, ((b + 1) * per).toInt().coerceAtLeast(from + 1))
        var peak = 0
        for (i in from until to) {
            val v = abs(samples[i].toInt())
            if (v > peak) peak = v
        }
        out[b] = (peak / 32767f).coerceIn(0f, 1f)
    }
    return out
}
