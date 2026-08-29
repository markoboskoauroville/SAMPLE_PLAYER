package com.mantra.sampleplayer

import java.io.File

/**
 * WHERE AUDIO LIVES, AND THE ARITHMETIC BEHIND THE FILE MANAGER.
 *
 * The path rules are pure and take a [File] root, so Test 1 can walk them in a temporary
 * directory with no Android at all. Only [Vault] itself touches a real device path.
 *
 *     projects/<id>/samples/NN/original.wav       what Baba said. NEVER overwritten
 *     projects/<id>/samples/NN/gen/<engine>.wav   what an engine said. Beside, never on top
 *     projects/<id>/project.json                  titles, transcripts, order, chosen engine
 */
object Paths {

    const val ORIGINAL = "original.wav"

    fun projectDir(root: File, id: String): File = File(File(root, "projects"), id)

    fun slotDir(root: File, id: String, slot: Int): File =
        File(File(projectDir(root, id), "samples"), "%02d".format(slot))

    /**
     * The original recording.
     *
     * THIS IS THE HARDEST RULE IN THE APP AND IT IS ENFORCED BY THE PATH.
     *
     * `original.wav` sits in the slot directory and every generated file sits one level down in
     * `gen/`. They are different names in different directories, not two versions of one name, so
     * there is no loop index or engine string that can make [generated] return this path. A rule
     * enforced by a path survives a tired evening; a rule enforced by a convention does not.
     */
    fun original(root: File, id: String, slot: Int): File =
        File(slotDir(root, id, slot), ORIGINAL)

    fun generated(root: File, id: String, slot: Int, engine: String): File {
        require(engine.isNotBlank()) { "an engine with no name would collide with itself" }
        require(!engine.contains('/') && !engine.contains("..")) { "engine name is a path" }
        return File(File(slotDir(root, id, slot), "gen"), "$engine.wav")
    }

    /** What the main view will actually sound, given the project's chosen engine. */
    fun playing(root: File, id: String, slot: Int, engine: String?): File {
        if (engine == null) return original(root, id, slot)
        val g = generated(root, id, slot, engine)
        // Falling back to the original rather than playing silence. A missing generated file is a
        // Generate that did not finish, and the recording underneath it is still perfectly good.
        return if (g.exists()) g else original(root, id, slot)
    }
}

/** What the file manager screen shows for one project, and for all of them together. */
data class Usage(val projects: Int, val filledSlots: Int, val files: Int, val bytes: Long) {

    /**
     * Megabytes, to one decimal, because a phone that fills up reports its symptom as a recording
     * that failed rather than as a disk that is full.
     */
    fun megabytes(): String = "%.1f MB".format(bytes / 1_048_576.0)

    operator fun plus(other: Usage) = Usage(
        projects + other.projects,
        filledSlots + other.filledSlots,
        files + other.files,
        bytes + other.bytes,
    )

    companion object {
        val NONE = Usage(0, 0, 0, 0L)

        fun ofProject(root: File, id: String): Usage {
            val dir = Paths.projectDir(root, id)
            if (!dir.isDirectory) return Usage(1, 0, 0, 0L)
            var files = 0
            var bytes = 0L
            var filled = 0
            for (slot in 0 until SLOTS) {
                if (Paths.original(root, id, slot).isFile) filled++
                val sd = Paths.slotDir(root, id, slot)
                sd.walkTopDown().filter { it.isFile }.forEach { files++; bytes += it.length() }
            }
            return Usage(1, filled, files, bytes)
        }
    }
}

/**
 * The real thing on a real phone. Everything above it is testable; this is the part that is not,
 * and keeping it this thin is what makes that acceptable.
 */
class Vault(private val root: File) {

    fun projectIds(): List<String> =
        File(root, "projects").listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted()
            ?: emptyList()

    fun ensure(id: String) {
        for (slot in 0 until SLOTS) Paths.slotDir(root, id, slot).mkdirs()
    }

    fun usage(): Usage = projectIds().fold(Usage.NONE) { acc, id -> acc + Usage.ofProject(root, id) }

    fun usageOf(id: String): Usage = Usage.ofProject(root, id)

    /**
     * Delete one project outright, which is a thing Baba asked for by name.
     *
     * There is no undo and there is not meant to be one. A deleted recording is gone; pretending
     * otherwise with a bin that is never emptied is how a phone fills up quietly.
     */
    fun deleteProject(id: String): Boolean = Paths.projectDir(root, id).deleteRecursively()

    /** Clear the generated audio for a whole project, leaving every original untouched. */
    fun clearGenerated(id: String): Int {
        var removed = 0
        for (slot in 0 until SLOTS) {
            val gen = File(Paths.slotDir(root, id, slot), "gen")
            gen.listFiles()?.forEach { if (it.delete()) removed++ }
        }
        return removed
    }
}
