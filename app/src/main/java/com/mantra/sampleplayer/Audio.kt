package com.mantra.sampleplayer

import kotlin.math.sqrt

/**
 * THE TWO PIECES OF THE STOPWATCH'S EAR THAT THIS APP KEEPS.
 *
 * [Vu] is the level a meter draws, smoothed and floored. [Capture] is the state machine that
 * decides when a recording has begun and when it is over.
 *
 * Both are pure: fed a level and a clock, importing nothing from Android. That is deliberate
 * and it is the reason this app can be tested at all. Every part of the stopwatch that needed
 * a microphone to test is the part that took ten versions to get right.
 */
// The command vocabulary, the command gate, the speech gate and the diagnostics line are not
// here. They belong to a stopwatch that listens for the words start, stop and reset. This app
// never listens for a word: it records phrases and sends them to AssemblyAI. SpeechRecognizer is
// not reached for at all, on five versions of evidence that it does not work on this phone.

class Vu {

    private var level = 0f

    /** SpeechRecognizer's rmsdB to the 0..1 the original curve expects. */
    fun fromRms(rmsDb: Float): Float {
        val span = RMS_MAX - RMS_MIN
        // No clamp here: update() clamps on the way in, and a second one was two places to be
        // right about the same thing. The mutation sweep found it by breaking this line and
        // watching nothing happen, which is the honest way to discover a redundant guard.
        return update((rmsDb - RMS_MIN) / span)
    }

    /**
     * A PCM16 peak, which is how TTT mini feeds this curve and the only honest source of level
     * there is. Used by MicProbe, which owns the microphone directly while voice commands are
     * off, so the tester can answer "does audio reach this app" without asking the recogniser.
     *
     * VISUAL_FULL_SCALE is TTT mini's number and not full scale. Speech rarely reaches PCM16
     * maximum, so dividing by 32767 gives a bar that barely moves for a normal voice. 16000 is
     * the calibration that has been looked at on a real screen for years in the other app.
     */
    fun fromPeak(peak: Int): Float = update(peak.coerceAtLeast(0) / VISUAL_FULL_SCALE)

    /** The curve from TTT mini, taking an already normalised 0..1 rather than a PCM16 peak. */
    fun update(normalised: Float): Float {
        val gated = ((normalised.coerceIn(0f, 1f) - NOISE_GATE) / (1f - NOISE_GATE)).coerceIn(0f, 1f)
        val curved = sqrt(gated)
        val rate = if (curved > level) ATTACK else RELEASE
        level += (curved - level) * rate
        if (level < REST_EPSILON && curved == 0f) level = 0f
        return level
    }

    fun reset(): Float {
        level = 0f
        return level
    }

    private companion object {
        // SpeechRecognizer's documented range is loose and device-dependent. These two are what
        // silence and a normal speaking voice actually read as; anything outside is clamped.
        const val RMS_MIN = -2f
        const val RMS_MAX = 10f

        /** TTT mini's calibration: speech rarely reaches PCM16 full scale, so this is not it. */
        const val VISUAL_FULL_SCALE = 16_000f

        // Unchanged from TTT mini.
        const val NOISE_GATE = 0.025f
        const val ATTACK = 0.55f
        const val RELEASE = 0.20f
        const val REST_EPSILON = 0.005f
    }
}

/**
 * FIRE ONCE PER UTTERANCE, AND THIS IS THE BUG THE WHOLE FILE EXISTS TO STOP.
 *
 * The listener acts on PARTIAL results, because a stopwatch command that lands half a second
 * late has already missed the thing being timed. But partial results arrive several times for
 * one spoken word: "st", "start", "start" again as the recogniser firms up. Each of those
 * contains the command, so without a gate a single spoken "start" presses play three or four
 * times — and play is a TOGGLE, so the clock would start, pause, start, pause and end up
 * wherever the count of partials left it. It would look like the microphone was possessed.
 *
 * TWO RULES, BECAUSE ONE IS NOT ENOUGH:
 *
 *   once per utterance   the gate closes on the first match and only [newUtterance] reopens it,
 *                        which the listener calls when it starts listening again
 *   and a minimum gap    because the recogniser restarts every couple of seconds and the tail
 *                        of the same word can land in the next utterance as well
 *
 * It is pure, and it takes the clock as an argument, for the same reason the stopwatch does:
 * every case here can be walked in Test 1 without a microphone.
 */
enum class CaptureState { WAITING, SPEAKING, DONE, TIMED_OUT }

/**
 * LISTENING FOR ONE WORD AND STOPPING WHEN IT ENDS.
 *
 * The arm button is gone, and so is the fixed second and a half. Pressing a pad starts a capture:
 * it waits for you to begin, records while you speak, and stops when you stop. A fixed length is
 * a worse recording in both directions — it keeps the silence you left at the end if you were
 * quick, and it cuts you off if you were not.
 *
 * FOUR NUMBERS, and each one is a decision rather than a default:
 *
 *   ONSET       the level that counts as speech starting. The same threshold the command gate
 *               uses, because they are answering the same question about the same signal
 *   HANGOVER    how long the level must stay down before the word is over. Too short and it
 *               stops inside the gap in the middle of a word like "re-set"; too long and it
 *               keeps a second of room at the end
 *   MAX_SPEECH  a ceiling, so a noisy room cannot hold a capture open for ever
 *   WAIT        how long to wait for you to start before giving up and saying so
 *
 * It is a pure state machine fed a level and a clock, so all of that is testable without a
 * microphone — which matters here, because every part of this app that needed a microphone to
 * test is the part that took ten versions to get right.
 */
class Capture(
    private val onset: Float = ONSET,
    private val hangoverMs: Long = HANGOVER_MS,
    private val maxSpeechMs: Long = MAX_SPEECH_MS,
    private val waitMs: Long = WAIT_MS,
) {
    private var startedAt = 0L
    private var onsetAt = 0L
    private var quietSince = 0L
    private var state = CaptureState.WAITING

    fun begin(now: Long) {
        startedAt = now
        onsetAt = 0L
        quietSince = 0L
        state = CaptureState.WAITING
    }

    fun update(level: Float, now: Long): CaptureState {
        when (state) {
            CaptureState.WAITING -> {
                if (level >= onset) {
                    onsetAt = now
                    quietSince = 0L
                    state = CaptureState.SPEAKING
                } else if (now - startedAt >= waitMs) {
                    state = CaptureState.TIMED_OUT
                }
            }
            CaptureState.SPEAKING -> {
                if (level >= onset) {
                    quietSince = 0L
                } else {
                    if (quietSince == 0L) quietSince = now
                    if (now - quietSince >= hangoverMs) state = CaptureState.DONE
                }
                if (now - onsetAt >= maxSpeechMs) state = CaptureState.DONE
            }
            else -> Unit
        }
        return state
    }

    /**
     * How far back to read from the ring, in milliseconds, once the capture is done.
     *
     * From a little BEFORE the onset, because the level only crosses the threshold once the word
     * is already underway — the first consonant is always quieter than the vowel that follows it,
     * and reading from the crossing point would clip every recording at the front.
     */
    fun windowMs(now: Long): Int =
        ((now - onsetAt) + LEAD_MS).toInt().coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)

    companion object {
        const val ONSET = 0.30f
        const val HANGOVER_MS = 550L
        const val MAX_SPEECH_MS = 2_000L
        const val WAIT_MS = 4_000L

        /** Reach back past the crossing point to catch the start of the word. */
        const val LEAD_MS = 250L
        const val MIN_WINDOW_MS = 400
        const val MAX_WINDOW_MS = 2_000
    }
}
