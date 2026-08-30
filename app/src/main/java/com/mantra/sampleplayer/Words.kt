package com.mantra.sampleplayer

import android.content.Context
import java.io.File

/**
 * WHAT IS KNOWN ABOUT A CELL BESIDES ITS AUDIO.
 *
 * The transcript, and which engine's voice the cell should play. One small text file per slot,
 * sitting in the slot's own directory beside the recording.
 *
 * WHY NOT ONE JSON FILE PER PROJECT. Because a single file is a single thing to corrupt. Thirty
 * cells sharing one index means a half-written save loses every transcript in the project, and
 * this app is used by somebody putting the phone in a pocket mid-session. A file per slot is
 * duller and the worst case is one cell forgetting its own words.
 *
 * THE VOICE IS PER CELL, NOT PER PROJECT. Baba's instruction was that a long press on ONE cell
 * changes THAT cell's voice. A project-wide engine would mean changing cell four silently changed
 * the other twenty-nine.
 */
class Words(context: Context) {

    private val root: File = context.filesDir

    private fun file(id: String, slot: Int) = File(Paths.slotDir(root, id, slot), "meta.txt")

    private fun read(id: String, slot: Int): Map<String, String> {
        val f = file(id, slot)
        if (!f.isFile) return emptyMap()
        return f.readLines()
            .mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at) to line.substring(at + 1)
            }
            .toMap()
    }

    private fun write(id: String, slot: Int, map: Map<String, String>) {
        val f = file(id, slot)
        f.parentFile?.mkdirs()
        // Newlines would split one value across two lines and the reader would take the tail as a
        // key with no value. A transcript of a spoken phrase should not contain one, but should
        // not is not the same as cannot.
        f.writeText(map.entries.joinToString("\n") { "${it.key}=${it.value.replace("\n", " ")}" })
    }

    fun get(id: String, slot: Int): String = read(id, slot)["words"].orEmpty()

    fun put(id: String, slot: Int, words: String) {
        write(id, slot, read(id, slot) + ("words" to words))
    }

    fun voice(id: String, slot: Int): String? = read(id, slot)["voice"]?.takeIf { it.isNotBlank() }

    /** Whether this cell is marked to loop. Survives everything except a re-record. */
    fun loops(id: String, slot: Int): Boolean = read(id, slot)["loop"] == "1"

    fun setLoop(id: String, slot: Int, on: Boolean) {
        write(id, slot, read(id, slot) + ("loop" to if (on) "1" else "0"))
    }

    /** The in and out points. Two numbers; the recording itself is never altered by editing. */
    fun trim(id: String, slot: Int): Trim {
        val m = read(id, slot)
        return Trim(m["in"]?.toIntOrNull() ?: 0, m["out"]?.toIntOrNull() ?: 0)
    }

    fun setTrim(id: String, slot: Int, t: Trim) {
        write(id, slot, read(id, slot) + mapOf("in" to t.inMs.toString(), "out" to t.outMs.toString()))
    }

    fun setVoice(id: String, slot: Int, engine: String?) {
        write(id, slot, read(id, slot) + ("voice" to (engine ?: "")))
    }

    /**
     * WHICH voice, not just which engine, as .
     *
     * The engine alone was enough while a voice was only ever chosen through the chooser, which
     * speaks the cell there and then. Reading a text file into a cell that already has a voice has
     * to speak it again LATER, and "Hume" is not something you can send to Hume.
     */
    fun voiceId(id: String, slot: Int): String? =
        read(id, slot)["voiceid"]?.takeIf { it.isNotBlank() }

    fun setVoiceId(id: String, slot: Int, key: String?) {
        write(id, slot, read(id, slot) + ("voiceid" to (key ?: "")))
    }

    /**
     * Everything derived from a recording, gone.
     *
     * Called when a cell is re-recorded. The words are about a phrase that is no longer there and
     * the chosen voice is saying them, so keeping either is how a cell ends up playing a synthetic
     * voice reciting something that was deleted last week.
     */
    fun clear(id: String, slot: Int) {
        file(id, slot).delete()
    }
}
