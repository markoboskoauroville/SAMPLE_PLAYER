package com.mantra.sampleplayer

/**
 * THE WHOLE APP AS A STATE MACHINE, IMPORTING NOTHING.
 *
 * No Android, no files, no audio. Fed a press and a clock, it says what should happen. That is
 * what makes Test 1 possible without a phone, and this app needs Test 1 more than the stopwatch
 * did, because the thing a bug destroys here is a recording that cannot be made again.
 */

/**
 * Thirty, and it is now a DEFAULT rather than a law.
 *
 * Thirty was the brief and it is still what a new project gets. It stopped being a constant when
 * it turned out that a set is sometimes fifteen lines and sometimes a hundred, and an app that
 * makes you record into thirty boxes when you have twelve things to say is an app that counts for
 * you.
 */
const val DEFAULT_SLOTS = 30

/** What may be chosen. Not a free number: a text field is a keyboard, and this app is dictated. */
val SLOT_CHOICES = listOf(15, 30, 60, 120)

/**
 * ONE SCREENFUL, AND IT IS THE UNIT OF PAGING.
 *
 * Three across and ten down is what fits on this phone without scrolling, which is why thirty
 * looked right in the first place. A page is that, and a set larger than one page is flipped
 * sideways rather than scrolled.
 *
 * If a page does not fit some other screen it scrolls, which is a worse day than flipping but not
 * a broken one. Measuring the available height and choosing the row count from it would be more
 * correct and is not built, because it has not been needed on the only phone this runs on.
 */
const val PAGE_ROWS = 10
const val COLUMNS = 3
const val PAGE_SIZE = PAGE_ROWS * COLUMNS

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

    /**
     * The slot already holds a recording and the person pressed it in record mode.
     *
     * NOT `StartRecording`. Recording over a take destroys it, and a press is one finger on a
     * small tile in a grid of thirty. The confirmation is the whole difference between a mistake
     * that costs a tap and a mistake that costs a take, and it is decided here rather than in the
     * interface so that Test 1 can prove it is never skipped.
     */
    data class ConfirmOverwrite(val slot: Int) : Press
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
    /**
     * Which engine's voice THIS cell plays, or null for Baba's own recording.
     *
     * Per cell, not per project. A long press changes the voice of the cell that was pressed, and
     * a project-wide setting would mean changing cell four silently changed the other twenty-nine.
     */
    val voice: String? = null,
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
    val slots: List<Slot> = (0 until DEFAULT_SLOTS).map { Slot(it) },
    /** The custom running order from the Seq view. Empty means natural order. */
    val order: List<Int> = emptyList(),
    /** Which engine's audio the lines play, or null for Baba's own recordings. */
    val engine: String? = null,
) {
    val filled: List<Int> get() = slots.filter { it.hasOriginal }.map { it.index }

    /** How many cells this project has. Not a constant: it is chosen in settings. */
    val size: Int get() = slots.size

    /**
     * How many screenfuls this project is.
     *
     * There is no next-page button and there is not going to be one. Baba's own answer: put the
     * count somewhere and flip the screen. A control that steals a cell to navigate between cells
     * costs one of the thirty things the screen is for.
     */
    val pages: Int get() = Paging.pageCount(size)

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
        if (slot !in project.slots.indices) return Press.Refused("no such slot")
        return when (mode) {
            Mode.STOPPED ->
                if (project.slot(slot).hasOriginal) {
                    Press.ConfirmOverwrite(slot)
                } else {
                    Press.StartRecording(slot)
                }

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
        slot !in project.slots.indices -> Press.Refused("no such slot")
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
    fun next(from: Int, total: Int = DEFAULT_SLOTS): Int? =
        if (from >= total - 1) null else from + 1

    fun atEnd(slot: Int, total: Int = DEFAULT_SLOTS): Boolean = slot >= total - 1

    /** What the triangle draws inside itself: nothing, or the number of the last slot. */
    fun glyph(slot: Int, total: Int = DEFAULT_SLOTS): String =
        if (atEnd(slot, total)) total.toString() else ""
}

/**
 * WHAT HAPPENS WHEN A SAMPLE ENDS.
 *
 * CONTINUOUS is the default and it is what the app was built for: press a tile, hear the set from
 * there, the playhead travelling down the grid. SINGLE plays the one tile and stops.
 *
 * Single exists because auditioning and listening are different jobs. Deciding whether take 14 is
 * the one means hearing take 14 four times, and in continuous mode that is four presses each
 * followed by reaching for stop before take 15 starts talking over your thinking.
 */
enum class PlayMode {
    /** One after another, down the running order. The default. */
    CONTINUOUS,

    /** Play the tile that was pressed and stop. */
    SINGLE,
}

/**
 * The slot to play after [slot] finishes, or null to stop.
 *
 * Pure, and it takes the mode as an argument rather than reading a setting, so Test 1 can walk
 * both modes and every edge of the running order without a phone or a preferences file.
 */
fun nextInPlayback(project: Project, slot: Int, mode: PlayMode): Int? {
    if (mode == PlayMode.SINGLE) return null
    val order = project.sequence()
    val at = order.indexOf(slot)
    // A slot that is not in the running order at all stops rather than starting from the top. It
    // means the tile was cleared while it was sounding, and resuming somewhere else would be the
    // app deciding what to play next on its own.
    if (at < 0) return null
    return order.getOrNull(at + 1)
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
        return (playing - 1).coerceIn(0, (PAGE_SIZE - visibleCount).coerceAtLeast(0))
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

/**
 * HOW MANY SCREENFULS, AND WHICH CELLS ARE ON EACH.
 *
 * There is no next-page control. Baba considered splitting the last cell into a forward and a back
 * button and rejected it in the same breath, correctly: a set of thirty that spends one of them on
 * navigation is a set of twenty-nine, and the arrow would sit in a different place on every page.
 * The page count goes in the header and the screen is flipped sideways.
 */
object Paging {

    /** Never fewer than one page, so an empty project still has a screen to look at. */
    fun pageCount(total: Int, perPage: Int = PAGE_SIZE): Int {
        if (perPage <= 0) return 1
        return ((total + perPage - 1) / perPage).coerceAtLeast(1)
    }

    /** The slot indices on [page], zero-based. The last page is short rather than padded. */
    fun slotsOn(page: Int, total: Int, perPage: Int = PAGE_SIZE): IntRange {
        if (perPage <= 0 || total <= 0) return IntRange.EMPTY
        val from = page * perPage
        if (from >= total) return IntRange.EMPTY
        return from until minOf(from + perPage, total)
    }

    /** Which page a slot is on, so the playhead and the triangle can bring it into view. */
    fun pageOf(slot: Int, perPage: Int = PAGE_SIZE): Int =
        if (perPage <= 0) 0 else slot / perPage

    /** "2 / 4", or empty when the whole set is one screen and there is nothing to flip. */
    fun label(page: Int, total: Int, perPage: Int = PAGE_SIZE): String {
        val pages = pageCount(total, perPage)
        return if (pages <= 1) "" else "page ${page + 1} / $pages"
    }
}

/**
 * THE IN AND OUT POINTS, WHICH ARE THE ONLY EDIT THIS APP OFFERS.
 *
 * Not a trim, not a cut, not a fade. The recording on disk is never altered by editing: these are
 * two numbers stored beside it saying where playback starts and where it stops.
 *
 * WHY NOTHING IS CUT. The take is the thing that cannot be made again. An editor that rewrites the
 * file is one wrong drag away from destroying the head of a phrase, and there is no undo on a
 * phone in a pocket. Two numbers can be dragged back to the ends at any time, for ever.
 *
 * WHAT IT IS FOR. The breath before the first word and the click of the second press are at the
 * two ends of nearly every take. Being able to move past them is the difference between a set that
 * plays as speech and a set that plays as thirty separate recordings.
 */
data class Trim(val inMs: Int = 0, val outMs: Int = 0) {

    /** True when the points have been moved off the ends of the recording. */
    fun isSet(lengthMs: Int): Boolean = inMs > 0 || (outMs in 1 until lengthMs)

    /** Where playback stops, resolving the unset case to the end of the recording. */
    fun endOf(lengthMs: Int): Int = if (outMs <= 0 || outMs > lengthMs) lengthMs else outMs

    fun durationMs(lengthMs: Int): Int = (endOf(lengthMs) - inMs).coerceAtLeast(0)

    companion object {
        /** Below this a cell plays as a click rather than a word. */
        const val MIN_MS = 120

        val NONE = Trim()

        /**
         * Move a point, keeping both inside the recording and at least [MIN_MS] apart.
         *
         * The clamping is here rather than in the drag handler because a drag handler is one place
         * and there turn out to be several: two handles, a reset, and whatever restores the values
         * from disk. A rule enforced at one edge is a rule with a way round it.
         */
        fun withIn(t: Trim, ms: Int, lengthMs: Int): Trim {
            val end = t.endOf(lengthMs)
            return t.copy(inMs = ms.coerceIn(0, (end - MIN_MS).coerceAtLeast(0)))
        }

        fun withOut(t: Trim, ms: Int, lengthMs: Int): Trim {
            val lowest = (t.inMs + MIN_MS).coerceAtMost(lengthMs)
            return t.copy(outMs = ms.coerceIn(lowest, lengthMs))
        }

        /** A recording shorter than the minimum cannot be trimmed at all, and says so. */
        fun editable(lengthMs: Int): Boolean = lengthMs >= MIN_MS * 2
    }
}

/**
 * WHICH KEYS THIS APP ACTUALLY WANTS.
 *
 * A key screen that lists whatever was imported tells you what you have and not what is missing,
 * and "why is Hume greyed out" is then a question with no answer on the screen that caused it.
 *
 * THREE PROVIDERS AND NOT ONE MORE. The note being imported has GitHub tokens, Gemini keys and
 * Anthropic keys in it — they are carried, they are testable, and this app never calls them. Saying
 * so is more useful than hiding them, because it stops the next session wondering which one drives
 * what.
 */
data class Need(val providerId: String, val display: String, val what: String, val required: Boolean)

object Needs {

    val ALL: List<Need> = listOf(
        Need(
            "assemblyai", "AssemblyAI", "transcribes a cell so a voice has words to say",
            required = true,
        ),
        Need(
            "speechify", "Speechify", "one of the two voice engines — eight curated voices",
            required = false,
        ),
        Need(
            "hume", "Hume", "the other voice engine — needs the API key AND the secret key",
            required = false,
        ),
    )

    /**
     * One line per need, saying what it is for and whether it is here.
     *
     * [held] is the set of provider ids actually imported.
     */
    fun lines(held: Set<String>): List<String> = ALL.map { need ->
        val mark = if (need.providerId in held) "have" else if (need.required) "MISSING" else "none"
        "${need.display} — ${need.what}  [$mark]"
    }

    /**
     * What cannot be done yet, in one sentence, or empty when everything works.
     *
     * The two engines are an OR: either one makes voices possible. AssemblyAI is an AND, because
     * without words there is nothing for either engine to say.
     */
    fun blocked(held: Set<String>): String {
        val noWords = "assemblyai" !in held
        val noVoice = "speechify" !in held && "hume" !in held
        return when {
            noWords && noVoice -> "Add AssemblyAI, and Speechify or Hume, to change any voice."
            noWords -> "Add AssemblyAI: without it there are no words for a voice to say."
            noVoice -> "Add Speechify or Hume: either one is enough to change a voice."
            else -> ""
        }
    }
}
