package com.mantra.sampleplayer

import android.content.Context
import java.io.File

/**
 * HOW KEYS REACH THE APP.
 *
 * A pasted note, parsed by the canonical [KeyParser]. Not a text field per provider: the note Baba
 * already has is a working note with account names, dates, the word CANCELLED, blank lines and
 * pasted URLs in it, and the parser was written to read exactly that. Asking him to pick the keys
 * out of it by hand is asking him to do the job the parser does, on a phone, without typing.
 *
 * WHAT IS STORED AND WHERE. One file in the app's private directory, which no other app can read.
 * Not `SharedPreferences`, which is also private but is the place people look first when they want
 * to dump an app's state. Nothing about a key ever reaches a log, a screen or a crash report: the
 * only form that leaves this file is [Credential.masked], six characters and four.
 *
 * `secrets.md`: never print a key, never commit one. There is no code path here that can display
 * one, which is stronger than a rule that says not to.
 */
class Keys(context: Context) {

    private val file = File(context.filesDir, "keys.txt")

    /** Everything the parser found, in file order. */
    fun all(): List<KeyParser.Found> =
        if (file.isFile) KeyParser.extract(file.readText()) else emptyList()

    fun forProvider(id: String): List<Credential> =
        all().filter { it.providerId == id }
            .map { Credential(it.key, it.secret, it.label) }

    fun ring(id: String): Ring = Ring(forProvider(id))

    fun has(id: String): Boolean = forProvider(id).isNotEmpty()

    /**
     * Add a pasted note to what is already held.
     *
     * APPEND, NEVER REPLACE. Pasting the Speechify note must not silently drop the Hume accounts
     * imported last week. The parser folds duplicates by key, so pasting the same note twice is
     * harmless.
     */
    fun import(note: String): Map<String, Int> {
        val before = all().map { it.key }.toSet()
        file.appendText("\n" + note + "\n")
        val added = all().filter { it.key !in before }
        return added.groupingBy { it.providerId }.eachCount()
    }

    /** What settings shows: provider, how many, and the account labels. Never a key. */
    fun summary(): List<String> =
        all().groupBy { it.providerId }
            .toSortedMap()
            .map { (provider, found) ->
                val labels = found.mapNotNull { it.label.takeIf { l -> l.isNotBlank() } }
                    .take(3)
                    .joinToString(", ")
                val tail = if (found.size > 3) ", …" else ""
                "$provider — ${found.size}" + if (labels.isEmpty()) "" else "  ($labels$tail)"
            }

    fun forget() {
        file.delete()
    }
}
