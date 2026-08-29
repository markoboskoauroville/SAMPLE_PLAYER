package com.mantra.sampleplayer

/**
 * THE WHOLE APP AS A STATE MACHINE, IMPORTING NOTHING.
 *
 * No Android, no files, no audio. Fed a press and a clock, it says what should happen. That is
 * what makes Test 1 possible without a phone, and this app needs Test 1 more than the stopwatch
 * did, because the thing a bug destroys here is a recording that cannot be made again.
 */

/** Thirty, and it is thirty everywhere. */
const val SLOTS = 30

/**
 * WHICH PRESS MEANS WHAT.
 *
 * The dangerous line in the whole app. A press on a slot means two different things depending on
 * this value, and one of those meanings destroys a recording.
 *
 * The stopwatch deleted tap-anywhere at v1 for exactly this reason: two contradictory meanings
 * must not share a surface. It is accepted here for one reason only — jumping between phrases by
 * pressing them IS the player, and thirty separate seek controls is not an interface.
 *
 * The mitigation is that the mode is not hidden. It is the Play button, it is visible, and it is
 * the only thing separating a seek from an overwrite.
 */
enum class Mode {
    /** Nothing is playing. A press RECORDS into that slot. */
    STOPPED,

    /** A recording is in progress. A press on the recording slot stops it. */
    RECORDING,

    /** The set is playing. A press on any filled slot SEEKS to it. */
    PLAYING,
}

/** What a press was decided to mean. Returned rather than performed, so a test can read it. */
sealed interface Press {
    data class StartRecording(val slot: Int) : Press
    data class StopRecording(val slot: Int) : Press
    data class SeekTo(val slot: Int) : Press
    data class Clear(val slot: Int) : Press

    /** Nothing happens, and the reason is carried so the interface can say it out loud. */
    data class Refused(val why: String) : Press
}

/**
 * One of the thirty. [title] is what is written above the line, [words] is the transcript.
 *
 * `hasOriginal` and `generated` are separate on purpose. `original.wav` is a different filename
 * from every generated file, not a version of the same one, because a rule enforced by a path
 * survives an off-by-one in a loop and a rule enforced by a convention does not.
 */
data class Slot(
    val index: Int,
    val hasOriginal: Boolean = false,
    val words: String = "",
    val generated: Set<String> = emptySet(),
    val lengthMs: Int = 0,
) {
    val isEmpty: Boolean get() = !hasOriginal

    /**
     * The title, which is never stored while it can be derived.
     *
     * Before transcription there is nothing better to call a slot than its number. After it, the
     * first word of what was said is the shortest thing that tells them apart.
     */
    fun title(): String {
        val first = words.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        return if (first.isEmpty()) "Title %02d".format(index + 1) else first
    }

    /** Whether this slot has audio from a named engine, so a line knows what it will play. */
    fun playsGenerated(engine: String?): Boolean = engine != null && engine in generated
}

/**
 * A PROJECT IS THIRTY SLOTS AND NOTHING IS GLOBAL EXCEPT THE KEYS.
 *
 * This arrived after the rest of the specification and it changed the storage layout rather than
 * the screen: `projects/<id>/samples/NN/`. A session that reads only the interface description
 * would build one global set of thirty and the migration afterwards would be real work.
 */
data class Project(
    val id: String,
    val name: String,
    val slots: List<Slot> = (0 until SLOTS).map { Slot(it) },
    /** The custom running order from the Seq view. Empty means natural order. */
    val order: List<Int> = emptyList(),
    /** Which engine's audio the lines play, or null for Baba's own recordings. */
    val engine: String? = null,
) {
    val filled: List<Int> get() = slots.filter { it.hasOriginal }.map { it.index }

    /**
     * The running order actually played.
     *
     * Only filled slots appear, and a stored order is filtered against what exists rather than
     * trusted. A slot cleared after the order was saved would otherwise be a gap that plays
     * nothing and looks like the player has stopped.
     */
    fun sequence(): List<Int> {
        if (order.isEmpty()) return filled
        val live = filled.toSet()
        val kept = order.filter { it in live }
        // Anything recorded after the order was last arranged goes on the end rather than being
        // dropped. Silently not playing a recording is the worse of the two failures.
        return kept + filled.filter { it !in kept.toSet() }
    }

    fun slot(i: Int): Slot = slots[i]

    fun withSlot(i: Int, f: (Slot) -> Slot): Project =
        copy(slots = slots.toMutableList().also { it[i] = f(it[i]) })
}

/**
 * WHAT A PRESS MEANS, AND IT IS THE ONE FUNCTION TO TEST HARDEST.
 *
 * Every press on a slot goes through here. There is exactly one place that decides record or
 * seek, so a mode that was right for the main view and wrong for the Seq view is impossible
 * rather than merely unlikely.
 */
object Gesture {

    fun press(mode: Mode, project: Project, slot: Int, recordingSlot: Int? = null): Press {
        if (slot !in 0 until SLOTS) return Press.Refused("no such slot")
        return when (mode) {
            Mode.STOPPED -> Press.StartRecording(slot)

            Mode.RECORDING ->
                if (slot == recordingSlot) {
                    Press.StopRecording(slot)
                } else {
                    // NOT a second recording, and not a silent no-op either. Two microphones
                    // cannot be open at once and the honest thing is to say which slot is busy.
                    Press.Refused("slot ${(recordingSlot ?: 0) + 1} is recording")
                }

            Mode.PLAYING ->
                if (project.slot(slot).hasOriginal) {
                    Press.SeekTo(slot)
                } else {
                    // An empty slot during playback does NOT fall through to recording. That
                    // fall-through is the bug this whole enum exists to prevent.
                    Press.Refused("nothing in slot ${slot + 1}")
                }
        }
    }

    /**
     * Long press clears, as in the stopwatch, and only when nothing is moving.
     *
     * Clearing during playback would delete the thing being played, and clearing during a
     * recording would delete the thing being written.
     */
    fun longPress(mode: Mode, project: Project, slot: Int): Press = when {
        slot !in 0 until SLOTS -> Press.Refused("no such slot")
        mode != Mode.STOPPED -> Press.Refused("stop first")
        project.slot(slot).isEmpty -> Press.Refused("already empty")
        else -> Press.Clear(slot)
    }
}

/**
 * WHERE THE TRIANGLE GOES NEXT.
 *
 * The overlay button does one thing: stop this recording and start the next slot. It is not a
 * transport. Stopping altogether is done by switching to the app, which is a deliberate cost —
 * every control that crept onto TTT mini's overlay strip had a better home already, and the strip
 * became a black notch carrying six things before anybody noticed.
 */
object Advance {

    /**
     * The next slot after [from], or null at the end.
     *
     * IT DOES NOT WRAP. Wrapping would begin overwriting slot 1 while the phone is in a pocket
     * and the person is reading from another app, which is the exact condition under which nobody
     * would notice. The triangle carries the number 30 at the last slot instead, so the end is
     * visible without counting.
     */
    fun next(from: Int): Int? = if (from >= SLOTS - 1) null else from + 1

    fun atEnd(slot: Int): Boolean = slot >= SLOTS - 1

    /** What the triangle draws inside itself: nothing, or the number of the last slot. */
    fun glyph(slot: Int): String = if (atEnd(slot)) SLOTS.toString() else ""
}

/**
 * THE PLAYHEAD, WHICH IS WHAT MAKES THE LINES PLAYERS RATHER THAN PICTURES.
 *
 * A thin vertical mark crosses each line's own waveform showing where inside that sample the
 * playback is. When a sample ends the mark leaves that line and appears at the start of the next,
 * so over a full play it travels down the whole set.
 */
data class Playhead(
    /** Which slot is sounding, or null when stopped. */
    val slot: Int? = null,
    /** How far through that slot, 0..1. */
    val fraction: Float = 0f,
) {
    fun isOn(candidate: Int): Boolean = slot == candidate

    companion object {
        val STOPPED = Playhead()

        fun at(slot: Int, elapsedMs: Int, lengthMs: Int): Playhead {
            if (lengthMs <= 0) return Playhead(slot, 0f)
            return Playhead(slot, (elapsedMs.toFloat() / lengthMs).coerceIn(0f, 1f))
        }
    }
}

/**
 * THE LIST FOLLOWS THE PLAYHEAD BY JUMPING, NEVER BY SCROLLING.
 *
 * design-language.md 8: nothing animated that the eye has to follow, and a direct jump wherever
 * the platform allows one. A smooth scroll chasing a playhead down thirty lines is exactly that
 * animation, and unlike a transition it would run for the whole length of a playback.
 *
 * Returns the index to jump to, or null to leave the list where it is. Leaving it alone is the
 * common case: a jump on every line would be the same animation delivered in steps.
 */
object Follow {

    fun jumpTo(playing: Int, firstVisible: Int, visibleCount: Int): Int? {
        if (visibleCount <= 0) return null
        val last = firstVisible + visibleCount - 1
        if (playing in firstVisible..last) return null
        // Put the playing line one row down from the top, so the next few are visible too and the
        // person can see what is coming rather than only what is sounding.
        return (playing - 1).coerceIn(0, (SLOTS - visibleCount).coerceAtLeast(0))
    }
}

/**
 * WHAT RE-RECORDING DESTROYS, WRITTEN DOWN SO IT IS DELIBERATE.
 *
 * A slot can be re-recorded after transcription, and doing so clears its transcript, its title
 * and every generated file. Keeping them is the answer whose wrongness does not appear until a
 * lot of work has been done: the line would play a synthetic voice saying words the person did
 * not say, under a title taken from words no longer there, and nothing on screen would look
 * wrong.
 */
object Rerecord {

    fun clearDerived(slot: Slot): Slot =
        slot.copy(words = "", generated = emptySet())

    /** What the interface warns about before it happens, or empty when there is nothing to lose. */
    fun warning(slot: Slot): String {
        val parts = buildList {
            if (slot.words.isNotBlank()) add("its transcript")
            if (slot.generated.isNotEmpty()) add("${slot.generated.size} generated voice(s)")
        }
        return if (parts.isEmpty()) "" else "Re-recording clears " + parts.joinToString(" and ")
    }
}

/**
 * GENERATE DOES WHAT IT CAN AND SAYS WHAT IT SKIPPED.
 *
 * Refusing thirty samples because one is untranscribed is the app making a decision that belongs
 * to the person, and asking is a dialogue in front of a six minute job whose answer would be the
 * same every time.
 */
data class GeneratePlan(val ready: List<Int>, val skipped: List<Int>) {

    fun line(): String = when {
        ready.isEmpty() -> "nothing to generate: transcribe first"
        skipped.isEmpty() -> "generating ${ready.size}"
        else -> "generating ${ready.size}, skipping ${skipped.size} untranscribed"
    }

    companion object {
        fun of(project: Project): GeneratePlan {
            val filled = project.slots.filter { it.hasOriginal }
            return GeneratePlan(
                ready = filled.filter { it.words.isNotBlank() }.map { it.index },
                skipped = filled.filter { it.words.isBlank() }.map { it.index },
            )
        }
    }
}
