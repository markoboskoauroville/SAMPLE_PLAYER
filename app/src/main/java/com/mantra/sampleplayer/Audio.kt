package com.mantra.sampleplayer

import kotlin.math.sqrt

/**
 * THE TWO PIECES OF THE STOPWATCH'S EAR THAT THIS APP KEEPS.
 *
 * [Vu] is the level a meter draws, smoothed and floored. [Ceiling] decides when a recording has
 * been quiet long enough to end itself.
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
 * THE SILENCE CEILING THAT SITS UNDERNEATH THE PRESS.
 *
 * WHY THE STOPWATCH'S `Capture` IS NOT HERE. It was ported, and then removed, because nothing
 * reached it. That machine answers "when did this one word end", with a hangover, a maximum
 * speech length of two seconds and a lead-in that reaches back past the onset. All three are
 * wrong for this app:
 *
 *   - the phrases are of unknown length, so a two second ceiling would cut every one of them
 *   - recording starts when the tile is PRESSED, not when the level crosses, so there is nothing
 *     to reach back for. The lead-in exists to stop a first consonant being clipped, and here the
 *     microphone is already open before the mouth is
 *   - the end is decided by a second press, not by a hangover
 *
 * The brief asked for the lead-in to be carried across. It is not, and this is why: it would be a
 * line of code with nothing to do. Said plainly rather than ported and left inert.
 *
 * What remains is one honest job. A slot left recording by mistake, with the app in the
 * background and nothing on screen to notice it, must not record the room until the disk fills.
 *
 * That job was inline in the recorder's reading thread where Test 1 could not reach it. Here it
 * is a pure function of a level and a clock, which is the only form a rule can be proven in.
 */
class Ceiling(private val afterMs: Long = SILENCE_CEILING_MS, private val onset: Float = ONSET) {

    private var quietSince = 0L

    /** True when it has been quiet long enough that the recording should end itself. */
    fun exceeded(level: Float, now: Long): Boolean {
        if (level >= onset) {
            quietSince = 0L
            return false
        }
        if (quietSince == 0L) quietSince = now
        return now - quietSince >= afterMs
    }

    fun reset() {
        quietSince = 0L
    }

    companion object {
        /**
         * Ninety seconds. Deliberately long: far past any pause inside a spoken sentence, far
         * short of filling a phone. It is a guard against forgetting, not an endpointer.
         */
        const val SILENCE_CEILING_MS = 90_000L

        /** The level that counts as speech. The same threshold the meter is floored against. */
        const val ONSET = 0.30f
    }
}
