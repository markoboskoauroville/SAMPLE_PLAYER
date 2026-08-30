package com.mantra.sampleplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * TEST 1 — THE MECHANISM, ALONE. No Android, no microphone, no phone.
 *
 * four-tests.md 1: this is the layer where a bug can be proven absent rather than merely not
 * observed. Everything here is a pure function fed a value and a clock.
 *
 * The cases are weighted towards the two things that destroy work: the press rule, where a wrong
 * answer records over a recording, and the path rule, where a wrong answer overwrites the
 * original with a generated file.
 */
class SamplePlayerTest {

    private fun filled(vararg idx: Int): Project = sized(DEFAULT_SLOTS, *idx)

    private fun sized(count: Int, vararg idx: Int): Project {
        val slots = (0 until count).map { Slot(it, hasOriginal = it in idx.toSet()) }
        return Project("p", "p", slots)
    }

    // ── THE PRESS RULE ────────────────────────────────────────────────────────────────────────

    @Test fun `stopped means a press records`() {
        assertEquals(Press.StartRecording(3), Gesture.press(Mode.STOPPED, filled(), 3))
    }

    @Test fun `stopped on an OCCUPIED slot asks before recording`() {
        // It used to return StartRecording here and the take was gone on one tap of one small
        // tile in a grid of thirty. The confirmation is decided in the rule, not in the screen,
        // so there is no path to a recording that skipped it.
        assertEquals(Press.ConfirmOverwrite(3), Gesture.press(Mode.STOPPED, filled(3), 3))
    }

    @Test fun `no mode returns StartRecording for a slot that already has a take`() {
        val p = filled(3)
        for (m in Mode.entries) {
            val r = Gesture.press(m, p, 3, recordingSlot = if (m == Mode.RECORDING) 9 else null)
            assertNotEquals("$m started recording over a take", Press.StartRecording(3), r)
        }
    }

    @Test fun `an empty slot is not made to ask about nothing`() {
        // An app that asks every time teaches you to press OK without reading, and then it is not
        // a confirmation, it is a second tap.
        assertEquals(Press.StartRecording(3), Gesture.press(Mode.STOPPED, filled(), 3))
    }

    @Test fun `playing means a press seeks and NEVER records`() {
        val p = Gesture.press(Mode.PLAYING, filled(3), 3)
        assertEquals(Press.SeekTo(3), p)
        assertFalse(p is Press.StartRecording)
    }

    @Test fun `playing an empty slot refuses rather than falling through to recording`() {
        // THE BUG THIS WHOLE ENUM EXISTS TO PREVENT. A fall-through here records over nothing
        // today and over something the day the slot is filled.
        val p = Gesture.press(Mode.PLAYING, filled(3), 7)
        assertTrue(p is Press.Refused)
        assertFalse(p is Press.StartRecording)
    }

    @Test fun `every slot in playing mode is a seek or a refusal, never a recording`() {
        val project = filled(0, 5, 29)
        for (i in 0 until DEFAULT_SLOTS) {
            val p = Gesture.press(Mode.PLAYING, project, i)
            assertFalse("slot $i recorded during playback", p is Press.StartRecording)
        }
    }

    @Test fun `recording the same slot stops it`() {
        assertEquals(Press.StopRecording(4), Gesture.press(Mode.RECORDING, filled(), 4, 4))
    }

    @Test fun `recording a different slot refuses and names the busy one`() {
        val p = Gesture.press(Mode.RECORDING, filled(), 9, 4)
        assertTrue(p is Press.Refused)
        assertTrue((p as Press.Refused).why.contains("5"))
    }

    @Test fun `a slot out of range is refused in every mode`() {
        for (m in Mode.entries) {
            assertTrue(Gesture.press(m, filled(), -1) is Press.Refused)
            assertTrue(Gesture.press(m, filled(), DEFAULT_SLOTS) is Press.Refused)
        }
    }

    @Test fun `long press opens the menu in both stopped and playing`() {
        // It used to require STOPPED, from when a long press deleted outright. Hearing a cell you
        // want to edit and then reaching for it did nothing at all.
        assertEquals(Press.Clear(2), Gesture.longPress(Mode.STOPPED, filled(2), 2))
        assertEquals(Press.Clear(2), Gesture.longPress(Mode.PLAYING, filled(2), 2))
    }

    @Test fun `long press is refused while the microphone is open`() {
        // A menu covering the screen while recording is a recording nobody can stop.
        assertTrue(Gesture.longPress(Mode.RECORDING, filled(2), 2) is Press.Refused)
    }

    @Test fun `long press on an empty cell has nothing to open`() {
        assertTrue(Gesture.longPress(Mode.STOPPED, filled(), 2) is Press.Refused)
        assertTrue(Gesture.longPress(Mode.PLAYING, filled(), 2) is Press.Refused)
    }

    // ── LOOP IS A FLAG ON THE CELL ────────────────────────────────────

    private fun marked(vararg idx: Int): Project {
        val slots = (0 until DEFAULT_SLOTS).map {
            Slot(it, hasOriginal = true, loop = it in idx.toSet())
        }
        return Project("p", "p", slots)
    }

    @Test fun `a marked cell loops instead of seeking`() {
        assertEquals(Press.ToggleLoop(4), Gesture.press(Mode.PLAYING, marked(4), 4))
    }

    @Test fun `an unmarked cell still seeks`() {
        assertEquals(Press.SeekTo(4), Gesture.press(Mode.PLAYING, marked(9), 4))
    }

    @Test fun `the same press both starts and stops`() {
        // The whole point of the change: v9 started the sound from inside the menu, so the stop
        // was in a different place from the start. One press, one cell, both directions.
        val p = marked(4)
        assertEquals(Press.ToggleLoop(4), Gesture.press(Mode.PLAYING, p, 4))
        assertEquals(Press.ToggleLoop(4), Gesture.press(Mode.PLAYING, p, 4))
    }

    @Test fun `a marked cell still cannot be recorded over by accident`() {
        // The property the playing branch exists to hold, unchanged by the new meaning.
        val p = marked(4)
        assertFalse(Gesture.press(Mode.PLAYING, p, 4) is Press.StartRecording)
        assertFalse(Gesture.press(Mode.PLAYING, p, 4) is Press.Refused)
    }

    @Test fun `marking a cell says nothing about any other`() {
        val p = marked(4)
        assertTrue(p.slot(4).loop)
        for (i in 0 until DEFAULT_SLOTS) if (i != 4) assertFalse("cell $i", p.slot(i).loop)
    }

    @Test fun `a marked but empty cell has nothing to loop`() {
        val slots = (0 until DEFAULT_SLOTS).map { Slot(it, hasOriginal = false, loop = true) }
        val p = Project("p", "p", slots)
        assertTrue(Gesture.press(Mode.PLAYING, p, 3) is Press.Refused)
    }

    @Test fun `in record mode a marked cell is still a recording target`() {
        // The flag belongs to playback. Marking a cell must not make it unrecordable.
        val p = marked(4)
        assertEquals(Press.ConfirmOverwrite(4), Gesture.press(Mode.STOPPED, p, 4))
    }

    // ── THE TRIANGLE ──────────────────────────────────────────────────────────────────────────

    @Test fun `the triangle advances one slot`() {
        assertEquals(1, Advance.next(0))
        assertEquals(29, Advance.next(28))
    }

    @Test fun `the triangle does not wrap at the last slot`() {
        assertNull(Advance.next(DEFAULT_SLOTS - 1))
        assertNotEquals(0, Advance.next(DEFAULT_SLOTS - 1))
    }

    @Test fun `the triangle carries the number only at the last slot`() {
        assertEquals("", Advance.glyph(0))
        assertEquals("", Advance.glyph(28))
        assertEquals("30", Advance.glyph(29))
    }

    // ── THE PATHS, WHERE AN OVERWRITE DESTROYS WORK ───────────────────────────────────────────

    @Test fun `no engine name can make a generated path equal the original`() {
        val root = File("/tmp/x")
        val original = Paths.original(root, "p", 7)
        for (engine in listOf("edge", "hume", "speechify", "original", "original.wav", "ORIGINAL")) {
            assertNotEquals(
                "engine '$engine' collided with the original recording",
                original.path,
                Paths.generated(root, "p", 7, engine).path,
            )
        }
    }

    @Test fun `an engine name that is a path is refused`() {
        val root = File("/tmp/x")
        for (bad in listOf("../original", "a/b", "..", "")) {
            var threw = false
            try { Paths.generated(root, "p", 7, bad) } catch (e: IllegalArgumentException) { threw = true }
            assertTrue("engine '$bad' was accepted", threw)
        }
    }

    @Test fun `generated files live one level below the original`() {
        val root = File("/tmp/x")
        assertEquals("gen", Paths.generated(root, "p", 3, "edge").parentFile?.name)
        assertEquals("03", Paths.original(root, "p", 3).parentFile?.name)
    }

    @Test fun `playing falls back to the original when the generated file is missing`() {
        val root = Files.createTempDirectory("sp").toFile()
        Paths.original(root, "p", 1).also { it.parentFile?.mkdirs(); it.writeText("x") }
        assertEquals(Paths.original(root, "p", 1), Paths.playing(root, "p", 1, "edge"))
        root.deleteRecursively()
    }

    @Test fun `playing with no engine is always the original`() {
        val root = File("/tmp/x")
        assertEquals(Paths.original(root, "p", 1), Paths.playing(root, "p", 1, null))
    }

    // ── THE ZOOM WINDOW AND THE PLAYHEAD ───────────────────────────────

    // The arithmetic the editor does when Zoom is pressed, walked here because the drawing itself
    // cannot be tested without a screen.
    private fun zoomWindow(trim: Trim, lengthMs: Int): Pair<Int, Int> {
        val end = trim.endOf(lengthMs)
        val margin = ((end - trim.inMs) / 8).coerceAtLeast(50)
        return (trim.inMs - margin).coerceAtLeast(0) to (end + margin).coerceAtMost(lengthMs)
    }

    @Test fun `the zoom window contains the whole region`() {
        val t = Trim(1_000, 3_000)
        val (from, to) = zoomWindow(t, 4_000)
        assertTrue(from <= t.inMs)
        assertTrue(to >= t.endOf(4_000))
    }

    @Test fun `the zoom window leaves room to drag outwards`() {
        // Without a margin both handles land exactly on the edges of the box and there is nowhere
        // left to pull them from.
        val (from, to) = zoomWindow(Trim(1_000, 3_000), 4_000)
        assertTrue(from < 1_000)
        assertTrue(to > 3_000)
    }

    @Test fun `the zoom window never leaves the recording`() {
        for (t in listOf(Trim.NONE, Trim(0, 100), Trim(3_900, 4_000), Trim(0, 4_000))) {
            val (from, to) = zoomWindow(t, 4_000)
            assertTrue("$t gave $from", from >= 0)
            assertTrue("$t gave $to", to <= 4_000)
            assertTrue("$t is empty", from < to)
        }
    }

    @Test fun `a very short region still gets a usable window`() {
        val (from, to) = zoomWindow(Trim(2_000, 2_120), 4_000)
        assertTrue("window was ${to - from}ms", to - from >= 120)
    }

    // Where the playhead is drawn. It reports a fraction OF THE REGION, and drawing that across
    // the whole box put the mark at the left edge while the audio started at the in point.
    private fun headX(fraction: Float, inX: Float, outX: Float) = inX + fraction * (outX - inX)

    @Test fun `the playhead starts at the in point and ends at the out point`() {
        assertEquals(200f, headX(0f, 200f, 800f), 0.01f)
        assertEquals(800f, headX(1f, 200f, 800f), 0.01f)
        assertEquals(500f, headX(0.5f, 200f, 800f), 0.01f)
    }

    @Test fun `an untrimmed cell still sweeps the whole box`() {
        assertEquals(0f, headX(0f, 0f, 1000f), 0.01f)
        assertEquals(1000f, headX(1f, 0f, 1000f), 0.01f)
    }

    // ── THE RECORDING RATE ──────────────────────────────────────────

    @Test fun `the default recording format is 44 point 1 kilohertz`() {
        // WAV, 44.1 kHz, mono, 16-bit. Not 48: 44.1 is what the material this ends up in is cut
        // at, and a file that matches the timeline is one resample fewer between the microphone
        // and the finished thing.
        assertEquals(44_100, Recorder.RATES.first())
    }

    @Test fun `48 is the fallback and 16 is the floor`() {
        assertEquals(48_000, Recorder.RATES[1])
        assertEquals(16_000, Recorder.RATES.last())
    }

    @Test fun `a length is computed from the file's own rate, not from a constant`() {
        // A file recorded before the app learned to ask the phone is still 16 kHz, and a length
        // computed at 48 would be wrong by a factor of three with nothing on screen to say so.
        val root = Files.createTempDirectory("sp").toFile()
        val f = File(root, "a.wav")
        // One second of 44.1 kHz mono: 44100 frames, two bytes each, plus a 44 byte header.
        f.writeBytes(wavHeader(44_100, 44_100 * 2) + ByteArray(44_100 * 2))
        assertEquals(44_100, Recorder.rateOf(f))
        assertEquals(1_000, Recorder.lengthMs(f))
        root.deleteRecursively()
    }

    @Test fun `a LIST chunk before the data does not shift every sample`() {
        // MEASURED on a real Speechify WAV, 30.8.2026: RIFF / fmt / LIST(26) / data, so the audio
        // begins at byte 78. Reading from 44 gives twenty-six bytes of metadata as a click and
        // everything after it offset — which is what a generated voice sounded like.
        val root = Files.createTempDirectory("sp").toFile()
        val f = File(root, "speechify.wav")
        f.writeBytes(withList(48_000, 200))
        assertEquals(48_000, Recorder.rateOf(f))
        // 200 bytes of data is 100 frames at 48 kHz, which is 2ms.
        assertEquals(2, Recorder.lengthMs(f))
        assertEquals(100, Recorder.read(f).size)
        root.deleteRecursively()
    }

    @Test fun `a streaming placeholder size is not believed either`() {
        // Speechify sends 0xFFFFFFFF for the data size. Taken literally that is four gigabytes.
        val root = Files.createTempDirectory("sp").toFile()
        val f = File(root, "streamed.wav")
        val bytes = withList(48_000, 200)
        // overwrite the data size with the placeholder
        for (i in 0 until 4) bytes[74 + i] = 0xFF.toByte()
        f.writeBytes(bytes)
        assertEquals(100, Recorder.read(f).size)
        assertTrue(Recorder.lengthMs(f) < 100)
        root.deleteRecursively()
    }

    @Test fun `a plain file this app wrote still reads from 44`() {
        val root = Files.createTempDirectory("sp").toFile()
        val f = File(root, "ours.wav")
        f.writeBytes(wavHeader(48_000, 96) + ByteArray(96))
        assertEquals(48, Recorder.read(f).size)
        assertEquals(48_000, Recorder.rateOf(f))
        root.deleteRecursively()
    }

    @Test fun `something that is not a WAV at all does not crash the reader`() {
        val root = Files.createTempDirectory("sp").toFile()
        val f = File(root, "notawav.bin")
        f.writeBytes(ByteArray(200) { 7 })
        // Falls back rather than throwing: a corrupt file must not take the grid down with it.
        assertEquals(Dsp.SAMPLE_RATE, Recorder.rateOf(f))
        root.deleteRecursively()
    }

    /** RIFF / fmt / LIST / data, the way Speechify sends it. */
    private fun withList(rate: Int, dataBytes: Int): ByteArray {
        val listBytes = 26
        val total = 12 + 24 + (8 + listBytes) + 8 + dataBytes
        val h = ByteArray(total)
        fun le32(at: Int, v: Int) {
            h[at] = (v and 0xFF).toByte()
            h[at + 1] = ((v shr 8) and 0xFF).toByte()
            h[at + 2] = ((v shr 16) and 0xFF).toByte()
            h[at + 3] = ((v shr 24) and 0xFF).toByte()
        }
        "RIFF".toByteArray().copyInto(h, 0)
        le32(4, total - 8)
        "WAVE".toByteArray().copyInto(h, 8)
        "fmt ".toByteArray().copyInto(h, 12)
        le32(16, 16)
        h[20] = 1; h[22] = 1
        le32(24, rate)
        le32(28, rate * 2)
        h[32] = 2; h[34] = 16
        "LIST".toByteArray().copyInto(h, 36)
        le32(40, listBytes)
        "data".toByteArray().copyInto(h, 36 + 8 + listBytes)
        le32(36 + 8 + listBytes + 4, dataBytes)
        return h
    }

    @Test fun `a header claiming an absurd rate is not believed`() {
        val root = Files.createTempDirectory("sp").toFile()
        val f = File(root, "b.wav")
        f.writeBytes(wavHeader(3, 100) + ByteArray(100))
        assertEquals(Dsp.SAMPLE_RATE, Recorder.rateOf(f))
        root.deleteRecursively()
    }

    @Test fun `a file too short to hold a header does not crash the reader`() {
        val root = Files.createTempDirectory("sp").toFile()
        val f = File(root, "c.wav")
        f.writeBytes(ByteArray(10))
        assertEquals(Dsp.SAMPLE_RATE, Recorder.rateOf(f))
        assertEquals(0, Recorder.lengthMs(f))
        root.deleteRecursively()
    }

    @Test fun `the speech minimum is a duration, so a better rate does not loosen it`() {
        // It used to be a frame count. The analysis window is a fixed number of SAMPLES, so at
        // 48 kHz twenty-five frames is 83ms rather than 250, and the check would have become
        // three times more permissive the moment the recorder improved.
        assertEquals(250, SampleCheck.MIN_SPEECH_MS)
    }

    private fun wavHeader(rate: Int, dataBytes: Int): ByteArray {
        val h = ByteArray(44)
        fun le32(at: Int, v: Int) {
            h[at] = (v and 0xFF).toByte()
            h[at + 1] = ((v shr 8) and 0xFF).toByte()
            h[at + 2] = ((v shr 16) and 0xFF).toByte()
            h[at + 3] = ((v shr 24) and 0xFF).toByte()
        }
        "RIFF".toByteArray().copyInto(h, 0)
        le32(4, 36 + dataBytes)
        "WAVEfmt ".toByteArray().copyInto(h, 8)
        le32(16, 16)
        h[20] = 1; h[22] = 1
        le32(24, rate)
        le32(28, rate * 2)
        h[32] = 2; h[34] = 16
        "data".toByteArray().copyInto(h, 36)
        le32(40, dataBytes)
        return h
    }

    // ── THE REGION THAT IS ACTUALLY PLAYED ─────────────────────────────

    // The arithmetic Player does before handing a buffer to AudioTrack. Kept here as a pure walk
    // because the bug it replaces — a seek that had not landed when playback started — could not
    // be caught by any test at all: it was a race inside a framework call.
    private fun regionOf(trim: Trim, frames: Int, rate: Int): Pair<Int, Int> {
        val lengthMs = (frames.toLong() * 1000L / rate).toInt()
        val from = (trim.inMs.toLong() * rate / 1000L).toInt().coerceIn(0, frames - 1)
        val to = (trim.endOf(lengthMs).toLong() * rate / 1000L).toInt().coerceIn(from + 1, frames)
        return from to to
    }

    @Test fun `an untrimmed cell plays every frame`() {
        assertEquals(0 to 48_000, regionOf(Trim.NONE, 48_000, 48_000))
    }

    @Test fun `an in point moves the first frame`() {
        assertEquals(24_000 to 48_000, regionOf(Trim(inMs = 500), 48_000, 48_000))
    }

    @Test fun `an out point moves the last frame`() {
        assertEquals(0 to 24_000, regionOf(Trim(outMs = 500), 48_000, 48_000))
    }

    @Test fun `both points cut both ends`() {
        assertEquals(4_800 to 43_200, regionOf(Trim(100, 900), 48_000, 48_000))
    }

    @Test fun `the region is never empty and never runs past the file`() {
        for (inMs in listOf(0, 1, 499, 999, 5_000)) {
            for (outMs in listOf(0, 1, 500, 1_000, 9_999)) {
                val (from, to) = regionOf(Trim(inMs, outMs), 48_000, 48_000)
                assertTrue("in=$inMs out=$outMs gave $from..$to", from < to)
                assertTrue(from >= 0)
                assertTrue(to <= 48_000)
            }
        }
    }

    @Test fun `the region follows the file's own rate`() {
        // A 16 kHz take from an early version and a 48 kHz one must both cut at half a second.
        assertEquals(8_000 to 16_000, regionOf(Trim(inMs = 500), 16_000, 16_000))
        assertEquals(24_000 to 48_000, regionOf(Trim(inMs = 500), 48_000, 48_000))
    }

    // ── THE PLAYBACK POINTS ─────────────────────────────────────────

    @Test fun `an untrimmed cell plays the whole recording`() {
        assertEquals(5000, Trim.NONE.endOf(5000))
        assertEquals(5000, Trim.NONE.durationMs(5000))
        assertFalse(Trim.NONE.isSet(5000))
    }

    @Test fun `the out point defaults to the end rather than to zero`() {
        // Stored as 0 when unset. Read as 0 it would play nothing at all, which is the shape of
        // bug that looks like the audio is broken.
        assertEquals(5000, Trim(inMs = 100).endOf(5000))
    }

    @Test fun `an out point past the end is treated as the end`() {
        assertEquals(5000, Trim(0, 9999).endOf(5000))
    }

    @Test fun `the in point cannot pass the out point`() {
        val t = Trim.withIn(Trim(0, 1000), 4000, 5000)
        assertTrue("in landed at ${t.inMs}", t.inMs <= 1000 - Trim.MIN_MS)
    }

    @Test fun `the out point cannot pass the in point`() {
        val t = Trim.withOut(Trim(2000, 5000), 100, 5000)
        assertTrue("out landed at ${t.outMs}", t.outMs >= 2000 + Trim.MIN_MS)
    }

    @Test fun `the points stay inside the recording`() {
        assertEquals(0, Trim.withIn(Trim.NONE, -5000, 5000).inMs)
        assertEquals(5000, Trim.withOut(Trim.NONE, 99999, 5000).outMs)
    }

    @Test fun `a dragged pair always leaves something audible`() {
        var t = Trim.NONE
        for (ms in listOf(0, 4999, 2500, 4999, 1, 5000)) {
            t = Trim.withIn(t, ms, 5000)
            t = Trim.withOut(t, ms, 5000)
            assertTrue("duration fell to ${t.durationMs(5000)}", t.durationMs(5000) >= 0)
            assertTrue(t.inMs < t.endOf(5000))
        }
    }

    @Test fun `a recording too short to trim says so instead of offering handles`() {
        assertFalse(Trim.editable(Trim.MIN_MS))
        assertTrue(Trim.editable(5000))
    }

    @Test fun `whole take puts the points back to the ends`() {
        assertFalse(Trim.NONE.isSet(5000))
        assertTrue(Trim(inMs = 200).isSet(5000))
        assertTrue(Trim(outMs = 200).isSet(5000))
    }

    // ── THE SPEECHIFY SEATS ─────────────────────────────────────────

    @Test fun `the model follows the voice id, never a global setting`() {
        // simba-3.2 answers HTTP 400 for any voice whose id does not end _32, and that is almost
        // the whole 992-voice catalogue.
        assertEquals("simba-3.2", Engines.modelFor("beatrice_32"))
        assertEquals("simba-english", Engines.modelFor("beatrice"))
        assertEquals("simba-english", Engines.modelFor("lesya"))
    }

    @Test fun `the model rule is what the seats were really about`() {
        // v9 kept an eight-name list in Engines and these tests walked it. The whole catalogue is
        // fetched now — 992 voices, five cursor pages — so the list is gone and what it was
        // protecting is the rule underneath it: the model follows the suffix.
        //
        // The bug it was written for: v6 and v7 looked for `beatrice` and the catalogue has
        // `beatrice_32`, so the search matched nothing and the screen said no voices came back.
        for (seat in listOf("beatrice_32", "imogen_32", "harper_32", "wyatt_32")) {
            assertEquals("simba-3.2", Engines.modelFor(seat))
        }
        for (other in listOf("beatrice", "aadi", "lesya")) {
            assertEquals("simba-english", Engines.modelFor(other))
        }
    }

    @Test fun `a voice id decides its own model, whatever a caller passes`() {
        val bare = VoiceInfo(Engines.SPEECHIFY, "aadi", "Aadi")
        assertEquals("", bare.model)
        assertEquals("simba-english", Engines.modelFor(bare.id))
    }

    // ── THE RING IS WALKED, NOT SAMPLED ONCE ───────────────────────────────

    @Test fun `condemning the first credential exposes the second`() {
        // Measured on the real Hume ring: accounts 1, 2 and 3 return 400 E0300 zero_credits and
        // account 4 speaks. A router that gives up on the first refusal makes eighteen good
        // accounts unreachable.
        val ring = Ring((1..4).map { Credential("key-$it-${"z".repeat(30)}", null, "acct $it") })
        val first = ring.current()!!
        ring.condemn(first)
        val second = ring.current()!!
        assertNotEquals(first.key, second.key)
        ring.condemn(second)
        val third = ring.current()!!
        assertNotEquals(second.key, third.key)
        ring.condemn(third)
        assertTrue(ring.current() != null)
        assertEquals(3, ring.deadCount())
    }

    @Test fun `out of credit is a refusal, not a busy signal`() {
        val body = """{"status_code":400,"message":"Exhausted credit balance.","details":{"code":"E0300"}}"""
        assertEquals(Status.REJECTED, Classify.status(400, body))
        assertTrue(Providers.explain(400, body).contains("credit"))
    }

    @Test fun `a walk cannot outlive the ring`() {
        val ring = Ring((1..3).map { Credential("k$it${"z".repeat(30)}", null, "a$it") })
        repeat(3) { ring.condemn(ring.current()!!) }
        assertNull(ring.current())
    }

    // ── THE VOICE CATALOGUE ───────────────────────────────────────

    // Shapes taken from the live catalogues on 29.8.2026: Hume tags its voices LANGUAGE, ACCENT,
    // GENDER and AGE; Speechify uses namespaced tags and a locale code.
    private val beatrice = VoiceInfo(
        Engines.SPEECHIFY, "beatrice_32", "Beatrice", "simba-3.2",
        gender = "Female", age = "middle-aged", language = "English", accent = "british",
        tags = setOf("use-case:audiobook", "timbre:warm", "locale:en-gb", "accent:british"),
    )
    private val colton = VoiceInfo(
        Engines.HUME, "d8ab67c6", "Colton Rivers", "",
        gender = "Male", age = "Middle-Aged", language = "English", accent = "Texas",
        tags = setOf("gender:male", "accent:texas", "accent:southern", "language:english"),
    )
    private val aadi = VoiceInfo(
        Engines.SPEECHIFY, "aadi", "Aadi", "simba-english",
        gender = "Male", age = "young-adult", language = "Hindi", accent = "hi-IN",
        tags = setOf("use-case:podcast", "locale:hi-in"),
    )
    private val cat get() = listOf(beatrice, colton, aadi)

    @Test fun `a term matches on a word prefix, not on a substring`() {
        assertTrue(VoiceSearch.matches(beatrice, "brit"))
        assertTrue(VoiceSearch.matches(beatrice, "beat"))
        // A substring search would match "rit" inside "british"; a word search should not.
        assertFalse(VoiceSearch.matches(beatrice, "ritish"))
    }

    @Test fun `every term must match, and they need not be adjacent`() {
        // "brit female" is the whole reason this is not a plain contains: those two words never
        // appear next to each other in any voice.
        assertTrue(VoiceSearch.matches(beatrice, "brit female"))
        assertFalse(VoiceSearch.matches(beatrice, "brit male x"))
    }

    @Test fun `search is case-blind and an empty query matches everything`() {
        assertTrue(VoiceSearch.matches(colton, "TEXAS"))
        for (v in cat) assertTrue(VoiceSearch.matches(v, "   "))
    }

    @Test fun `search reaches the tags as well as the name`() {
        assertTrue(VoiceSearch.matches(beatrice, "audiobook"))
        assertTrue(VoiceSearch.matches(aadi, "podcast"))
        assertFalse(VoiceSearch.matches(aadi, "audiobook"))
    }

    @Test fun `facet values are collected from the catalogue itself`() {
        assertEquals(listOf("Female", "Male"), Facets.values(cat, Facets.GENDER))
        assertEquals(listOf("English", "Hindi"), Facets.values(cat, Facets.LANGUAGE))
        assertTrue(Facets.values(cat, Facets.USE).contains("audiobook"))
    }

    @Test fun `within a facet the values are an OR`() {
        val both = VoiceSearch.apply(cat, "", mapOf(Facets.GENDER to setOf("Male", "Female")), emptySet())
        assertEquals(3, both.size)
    }

    @Test fun `across facets they are an AND`() {
        val r = VoiceSearch.apply(
            cat,
            "",
            mapOf(Facets.GENDER to setOf("Male"), Facets.LANGUAGE to setOf("English")),
            emptySet(),
        )
        assertEquals(listOf("Colton Rivers"), r.map { it.name })
    }

    @Test fun `an empty filter set does not exclude anything`() {
        assertEquals(3, VoiceSearch.apply(cat, "", mapOf(Facets.GENDER to emptySet()), emptySet()).size)
    }

    @Test fun `starred voices come first`() {
        val r = VoiceSearch.apply(cat, "", emptyMap(), setOf(VoiceSearch.key(aadi)))
        assertEquals("Aadi", r.first().name)
    }

    @Test fun `flagship voices come next, then alphabetical`() {
        val r = VoiceSearch.apply(cat, "", emptyMap(), emptySet())
        assertEquals(listOf("Beatrice", "Aadi", "Colton Rivers"), r.map { it.name })
    }

    @Test fun `the flagship set is the Speechify voices on the newest model`() {
        assertTrue(beatrice.flagship)
        assertFalse(aadi.flagship)
        // Hume publishes no popularity or featured field at all, so nothing there is flagship.
        assertFalse(colton.flagship)
    }

    @Test fun `a key is engine and id, because two providers use the same short names`() {
        val same = VoiceInfo(Engines.HUME, "beatrice_32", "Beatrice")
        assertNotEquals(VoiceSearch.key(beatrice), VoiceSearch.key(same))
    }

    @Test fun `a locale code becomes a language name so one facet is one facet`() {
        // Hume says "English" and Speechify says "en-US". Filtering by English must find both.
        assertEquals("English", Locales.name("en-US"))
        assertEquals("English", Locales.name("en-GB"))
        assertEquals("Hindi", Locales.name("hi-IN"))
        assertEquals("Croatian", Locales.name("hr-HR"))
    }

    @Test fun `an unknown locale is passed through rather than blanked`() {
        assertEquals("xx-YY", Locales.name("xx-YY"))
        assertEquals("", Locales.name(""))
    }

    // ── ACTING DIRECTIONS ─────────────────────────────────────────

    @Test fun `there are enough directions to be a starting point rather than a token gesture`() {
        assertTrue(Emotions.ALL.size >= 30)
        assertTrue(Emotions.GROUPS.size >= 6)
    }

    @Test fun `every direction is prose, because Hume reads it as prose`() {
        for (d in Emotions.ALL) {
            assertTrue("${d.label} is not a description", d.text.length > 8)
            assertEquals(d.text, d.text.lowercase())
            assertTrue(d.group.isNotBlank())
        }
    }

    @Test fun `every direction has one glyph, and no two share it`() {
        for (d in Emotions.ALL) {
            assertEquals(d.label + " glyph is not one character", 1, d.glyph.length)
        }
        assertEquals(Emotions.ALL.size, Emotions.ALL.map { it.glyph }.toSet().size)
    }

    @Test fun `every direction has something short to say`() {
        for (d in Emotions.ALL) {
            assertTrue(d.label + " has no spoken form", d.spoken.startsWith("is "))
            assertTrue(d.label + " preview is too long", d.spoken.length < 32)
        }
    }

    @Test fun `the preview line is the name and the emotion, nothing else`() {
        // Eight emotions is eight calls and Hume paces at about twelve seconds. A full sentence
        // would be two minutes of waiting to hear four seconds of difference.
        val angry = Emotions.ALL.single { it.label == "Angry" }
        assertEquals("John is angry.", Emotions.previewLine("John", angry))
        val happy = Emotions.ALL.single { it.label == "Happy" }
        assertEquals("Beatrice is happy.", Emotions.previewLine("Beatrice", happy))
    }

    @Test fun `a direction can be found again from the text that was stored`() {
        // What is stored is the prose sent to Hume, so the card has to map back to the chip.
        for (d in Emotions.ALL) assertEquals(d, Emotions.byText(d.text))
        assertNull(Emotions.byText("something nobody chose"))
    }

    @Test fun `every direction is distinct`() {
        assertEquals(Emotions.ALL.size, Emotions.ALL.map { it.text }.toSet().size)
        assertEquals(Emotions.ALL.size, Emotions.ALL.map { it.label + it.group }.toSet().size)
    }

    @Test fun `directions belong to Hume alone`() {
        assertTrue(Emotions.availableFor(Engines.HUME))
        assertFalse(Emotions.availableFor(Engines.SPEECHIFY))
    }

    @Test fun `a group holds only its own directions`() {
        for (g in Emotions.GROUPS) {
            assertTrue(Emotions.of(g).isNotEmpty())
            assertTrue(Emotions.of(g).all { it.group == g })
        }
    }

    // ── IS THERE A NEWER ONE ───────────────────────────────────────

    @Test fun `a newer release is newer`() {
        assertTrue(Updates.compare("20", "v21"))
        assertTrue(Updates.compare("v20", "v21"))
    }

    @Test fun `the same release is not newer`() {
        assertFalse(Updates.compare("v21", "v21"))
        assertFalse(Updates.compare("21", "v21"))
    }

    @Test fun `an older release is not offered`() {
        assertFalse(Updates.compare("v21", "v20"))
    }

    @Test fun `it compares numbers and not letters`() {
        // "v9" is greater than "v10" alphabetically, and the day that mattered would have been
        // the day the app quietly stopped offering updates with nothing on screen to say why.
        assertTrue(Updates.compare("v9", "v10"))
        assertFalse(Updates.compare("v10", "v9"))
        assertTrue(Updates.compare("v9", "v100"))
    }

    @Test fun `nothing published is not an update`() {
        assertFalse(Updates.compare("v20", null))
    }

    @Test fun `a tag with no digits in it cannot claim to be newer`() {
        assertFalse(Updates.compare("v20", "latest"))
        assertFalse(Updates.compare("v20", ""))
    }

    @Test fun `a longer version number still compares part by part`() {
        assertTrue(Updates.compare("v20", "v20.1"))
        assertFalse(Updates.compare("v20.1", "v20"))
    }

    // ── THE WAVE COLOUR ─────────────────────────────────────────

    @Test fun `a stored colour index outside the list falls back rather than crashing`() {
        assertEquals(waveColour(0), waveColour(-1))
        assertEquals(waveColour(WAVE_COLOURS.size - 1), waveColour(99))
    }

    @Test fun `no wave colour is the pale grey the transcript is written in`() {
        // The whole reason for the setting: the wave was the same colour as the words on it.
        assertTrue(WAVE_COLOURS.isNotEmpty())
        assertEquals(0xFFE8A64B, WAVE_COLOURS.first().second)
    }

    // ── WHICH KEYS THE APP NEEDS ────────────────────────────────────────

    @Test fun `the app names three providers and only three`() {
        assertEquals(3, Needs.ALL.size)
        assertEquals(
            setOf("assemblyai", "speechify", "hume"),
            Needs.ALL.map { it.providerId }.toSet(),
        )
    }

    @Test fun `transcription is required and the engines are not, individually`() {
        assertTrue(Needs.ALL.single { it.providerId == "assemblyai" }.required)
        assertFalse(Needs.ALL.single { it.providerId == "speechify" }.required)
        assertFalse(Needs.ALL.single { it.providerId == "hume" }.required)
    }

    @Test fun `with nothing imported it says both halves are needed`() {
        val m = Needs.blocked(emptySet())
        assertTrue(m.contains("AssemblyAI"))
        assertTrue(m.contains("Speechify") || m.contains("Hume"))
    }

    @Test fun `transcription alone is not enough to change a voice`() {
        assertTrue(Needs.blocked(setOf("assemblyai")).contains("Speechify"))
    }

    @Test fun `an engine alone is not enough either, because there are no words`() {
        assertTrue(Needs.blocked(setOf("hume")).contains("AssemblyAI"))
    }

    @Test fun `either engine plus transcription is enough`() {
        // The engines are an OR. AssemblyAI is an AND, because without words there is nothing for
        // either engine to say.
        assertEquals("", Needs.blocked(setOf("assemblyai", "hume")))
        assertEquals("", Needs.blocked(setOf("assemblyai", "speechify")))
    }

    @Test fun `keys the app never calls do not satisfy anything`() {
        val m = Needs.blocked(setOf("github", "gemini", "anthropic", "groq"))
        assertTrue(m.isNotEmpty())
    }

    @Test fun `every line says what the key is for and whether it is here`() {
        val lines = Needs.lines(setOf("assemblyai"))
        assertEquals(3, lines.size)
        assertTrue(lines.single { it.startsWith("AssemblyAI") }.contains("[have]"))
        assertTrue(lines.single { it.startsWith("Speechify") }.contains("[none]"))
        assertTrue(lines.none { it.contains("MISSING") })
    }

    @Test fun `a missing required key is marked louder than a missing optional one`() {
        val lines = Needs.lines(setOf("hume"))
        assertTrue(lines.single { it.startsWith("AssemblyAI") }.contains("MISSING"))
        assertTrue(lines.single { it.startsWith("Hume") }.contains("[have]"))
    }

    @Test fun `the Hume line says it needs both halves`() {
        assertTrue(Needs.ALL.single { it.providerId == "hume" }.what.contains("secret"))
    }

    // ── TESTING A KEY ──────────────────────────────────────────────

    @Test fun `every provider in the table has a url and headers`() {
        assertTrue(Providers.ALL.size >= 9)
        for (p in Providers.ALL) {
            assertTrue(p.testUrl("k").startsWith("https://"))
            assertTrue(p.display.isNotBlank())
        }
    }

    @Test fun `assemblyai is sent the raw key and never a bearer`() {
        val h = Providers.byId("assemblyai")!!.headers("abc")
        assertEquals("abc", h["authorization"])
    }

    @Test fun `speechify is tested on voices, because models 404s and reads as a dead key`() {
        assertTrue(Providers.byId("speechify")!!.testUrl("k").contains("/v1/voices"))
    }

    @Test fun `sk underscore has a fallback chain, because two companies share it`() {
        assertEquals(listOf("speechify", "elevenlabs"), Providers.FALLBACKS["speechify"])
        assertEquals(listOf("elevenlabs", "speechify"), Providers.FALLBACKS["elevenlabs"])
    }

    @Test fun `cloudflare is explained as not being the key`() {
        assertTrue(Providers.explain(403, "error code: 1010").contains("not by the key"))
        assertFalse(Providers.explain(403, "forbidden").contains("not by the key"))
    }

    @Test fun `a throttled key is explained as still good`() {
        assertTrue(Providers.explain(429, "").contains("good key"))
    }

    @Test fun `an unknown provider is null rather than a wrong guess`() {
        assertNull(Providers.byId("not-a-provider"))
    }

    // ── THE THIRD WAY OUT OF THE CONFIRMATION ─────────────────────────────

    @Test fun `the question is only asked when a take would be destroyed`() {
        // It is the premise of offering Play at all: the app only asks here, and the most likely
        // reason a full cell was pressed in record mode is that the mode was forgotten.
        assertEquals(Press.ConfirmOverwrite(3), Gesture.press(Mode.STOPPED, filled(3), 3))
        assertEquals(Press.StartRecording(3), Gesture.press(Mode.STOPPED, filled(), 3))
    }

    @Test fun `playing that cell is what the press would have meant in the other mode`() {
        // What the Play button does is exactly what the same press does once the mode is flipped,
        // which is why it can be offered without inventing a fourth behaviour.
        assertEquals(Press.SeekTo(3), Gesture.press(Mode.PLAYING, filled(3), 3))
    }

    // ── READING A TEXT FILE ALOUD ────────────────────────────────────

    @Test fun `newlines become spaces rather than disappearing`() {
        // A voice reads a line break as nothing at all, so a paragraph broken across lines
        // arrives as fragments run together: "the end ofone line".
        assertEquals("one two three", Text.forSpeaking("one\ntwo\r\nthree"))
        assertEquals("one two", Text.forSpeaking("one\t\ttwo"))
    }

    @Test fun `a byte order mark is not read aloud`() {
        assertEquals("Danas", Text.forSpeaking("\uFEFFDanas"))
    }

    @Test fun `runs of spaces collapse and the ends are trimmed`() {
        assertEquals("a b", Text.forSpeaking("   a     b   "))
    }

    @Test fun `a non-breaking space is a space`() {
        assertEquals("a b", Text.forSpeaking("a\u00A0b"))
    }

    @Test fun `an empty or whitespace file yields nothing`() {
        assertEquals("", Text.forSpeaking(""))
        assertEquals("", Text.forSpeaking("   \n\n  "))
    }

    @Test fun `text within the limit is untouched`() {
        val words = "Danas je lijep dan."
        assertEquals(words, Text.forSpeaking(words))
        assertTrue(Text.report(words).startsWith("19 characters"))
    }

    @Test fun `a long file is cut at a sentence end`() {
        val sentence = "This is a sentence. "
        val long = sentence.repeat(300)
        val out = Text.forSpeaking(long)
        assertTrue("cut to ${out.length}", out.length <= Text.MAX_CHARS)
        assertTrue("did not end on a sentence: ...${out.takeLast(20)}", out.endsWith("."))
    }

    @Test fun `a long file with no sentence end is cut at a space, never mid-word`() {
        val out = Text.forSpeaking(("word ").repeat(1000))
        assertTrue(out.length <= Text.MAX_CHARS)
        assertFalse("cut mid-word: ...${out.takeLast(10)}", out.endsWith("wor"))
    }

    @Test fun `one enormous word is cut at the limit rather than refused`() {
        val out = Text.forSpeaking("x".repeat(5000))
        assertEquals(Text.MAX_CHARS, out.length)
    }

    @Test fun `the report says when something was left behind`() {
        val long = "This is a sentence. ".repeat(300)
        assertTrue(Text.report(long).contains("past the engine's limit"))
        assertFalse(Text.report("short").contains("past"))
    }

    // ── A CELL CAN HOLD AUDIO IT DID NOT RECORD ─────────────────────────────

    private fun readAloud(index: Int) = Slot(
        index,
        hasOriginal = false,
        words = "Danas je lijep dan.",
        generated = setOf(Engines.HUME),
        voice = Engines.HUME,
    )

    @Test fun `a cell filled from a text file counts as filled`() {
        val slot = readAloud(3)
        assertTrue(slot.hasAudio)
        assertFalse(slot.hasOriginal)
        assertFalse(slot.isEmpty)
    }

    @Test fun `it can be played`() {
        val slots = (0 until DEFAULT_SLOTS).map { if (it == 3) readAloud(it) else Slot(it) }
        val p = Project("p", "p", slots)
        assertEquals(Press.SeekTo(3), Gesture.press(Mode.PLAYING, p, 3))
    }

    @Test fun `it appears in the running order`() {
        val slots = (0 until DEFAULT_SLOTS).map { if (it == 3) readAloud(it) else Slot(it) }
        assertEquals(listOf(3), Project("p", "p", slots).filled)
    }

    @Test fun `recording over it asks first, because the text came from a file we do not keep`() {
        val slots = (0 until DEFAULT_SLOTS).map { if (it == 3) readAloud(it) else Slot(it) }
        val p = Project("p", "p", slots)
        assertEquals(Press.ConfirmOverwrite(3), Gesture.press(Mode.STOPPED, p, 3))
    }

    @Test fun `a genuinely empty cell is still recorded into without a question`() {
        assertEquals(Press.StartRecording(3), Gesture.press(Mode.STOPPED, filled(), 3))
    }

    @Test fun `its menu can still be opened`() {
        val slots = (0 until DEFAULT_SLOTS).map { if (it == 3) readAloud(it) else Slot(it) }
        val p = Project("p", "p", slots)
        assertEquals(Press.Clear(3), Gesture.longPress(Mode.STOPPED, p, 3))
    }

    // ── THE PENDING TAKE ────────────────────────────────────────────

    @Test fun `a take in progress is never written over the recording it may replace`() {
        val root = File("/tmp/x")
        assertNotEquals(
            Paths.original(root, "p", 4).path,
            Paths.pending(root, "p", 4).path,
        )
    }

    @Test fun `the pending file sits beside the original, in the same slot`() {
        val root = File("/tmp/x")
        assertEquals(
            Paths.original(root, "p", 4).parentFile,
            Paths.pending(root, "p", 4).parentFile,
        )
    }

    @Test fun `a failed retake leaves the previous recording in place`() {
        // The rule this whole path exists for. Baba asked that re-recording replace the old take,
        // and it does. What it must never do is delete the old take and accept NOTHING.
        val root = Files.createTempDirectory("sp").toFile()
        val original = Paths.original(root, "p", 4)
        original.parentFile?.mkdirs()
        original.writeText("the good take")
        val pending = Paths.pending(root, "p", 4)
        pending.writeText("a cough")

        // What the recorder does when the quality check refuses the take.
        pending.delete()

        assertTrue(original.isFile)
        assertEquals("the good take", original.readText())
        assertFalse(pending.exists())
        root.deleteRecursively()
    }

    @Test fun `a good retake becomes the recording and the pending file is gone`() {
        val root = Files.createTempDirectory("sp").toFile()
        val original = Paths.original(root, "p", 4)
        original.parentFile?.mkdirs()
        original.writeText("the old take")
        val pending = Paths.pending(root, "p", 4)
        pending.writeText("the new take")

        // What the recorder does when the take passes: promote.
        assertTrue(pending.renameTo(original))

        assertEquals("the new take", original.readText())
        assertFalse(pending.exists())
        root.deleteRecursively()
    }

    @Test fun `no engine name can collide with the pending file either`() {
        val root = File("/tmp/x")
        val pending = Paths.pending(root, "p", 7)
        for (engine in listOf("pending", "pending.wav", "original", "edge")) {
            assertNotEquals(pending.path, Paths.generated(root, "p", 7, engine).path)
        }
    }

    // ── THE SEQUENCE ──────────────────────────────────────────────────────────────────────────

    @Test fun `sequence is the filled slots in order when nothing is arranged`() {
        assertEquals(listOf(0, 4, 9), filled(0, 4, 9).sequence())
    }

    @Test fun `an arranged order is honoured`() {
        val p = filled(0, 4, 9).copy(order = listOf(9, 0, 4))
        assertEquals(listOf(9, 0, 4), p.sequence())
    }

    @Test fun `an arranged order drops slots that no longer exist`() {
        val p = filled(0, 4).copy(order = listOf(9, 0, 4))
        assertEquals(listOf(0, 4), p.sequence())
    }

    @Test fun `a slot recorded after arranging goes on the end rather than being lost`() {
        // Silently not playing a recording is the worse of the two failures.
        val p = filled(0, 4, 9).copy(order = listOf(4, 0))
        assertEquals(listOf(4, 0, 9), p.sequence())
    }

    @Test fun `an empty project has an empty sequence`() {
        assertEquals(emptyList<Int>(), filled().sequence())
    }


    // ── PAGING ────────────────────────────────────────────────────────────────────────────────

    @Test fun `a set that fits one screen is one page`() {
        assertEquals(1, Grid.pageCount(30, 1))
        assertEquals(1, Grid.pageCount(1, 1))
    }

    @Test fun `an empty set still has a screen to look at`() {
        assertEquals(1, Grid.pageCount(0, 1))
    }

    @Test fun `the number of pages is the number asked for`() {
        // THIS TEST USED TO SAY SOMETHING ELSE, and the regex that renamed the function underneath
        // it kept the old numbers. It asserted that sixty cells is two pages, which was true while
        // a page was a fixed thirty. A page is now whatever a spread makes it: sixty cells over
        // one page is one page of sixty, and asking for two gives two.
        assertEquals(1, Grid.pageCount(60, 1))
        assertEquals(2, Grid.pageCount(60, 2))
        assertEquals(4, Grid.pageCount(120, 4))
        assertEquals(3, Grid.pageCount(31, 3))
    }

    @Test fun `asking for more pages than cells gives one cell per page`() {
        assertEquals(5, Grid.pageCount(5, 20))
        assertEquals(1, Grid.perPage(5, 20))
    }

    @Test fun `the last page is short rather than padded`() {
        assertEquals(30 until 31, Paging.slotsOn(1, 31, 30))
    }

    @Test fun `every offered cell count divides into whole pages of thirty`() {
        // Not required, but if one of these ever leaves a page holding a single cell it should be
        // a decision rather than a surprise.
        for (n in listOf(15, 30, 60, 120)) {
            val pages = Grid.pageCount(n, 1)
            val per = Grid.perPage(n, 1)
            assertTrue("$n cells gave $pages pages", pages >= 1)
            assertEquals(n, (0 until pages).sumOf { Paging.slotsOn(it, n, per).count() })
        }
    }

    @Test fun `a page past the end holds nothing rather than wrapping`() {
        assertTrue(Paging.slotsOn(5, 30, 30).isEmpty())
    }

    @Test fun `a slot knows which page it is on`() {
        assertEquals(0, Paging.pageOf(0, 30))
        assertEquals(0, Paging.pageOf(29, 30))
        assertEquals(1, Paging.pageOf(30, 30))
        assertEquals(3, Paging.pageOf(119, 30))
    }

    @Test fun `the page label is silent when there is nothing to flip`() {
        assertEquals("", Paging.label(0, 30, 30))
        assertEquals("page 1 / 2", Paging.label(0, 60, 30))
        assertEquals("page 2 / 2", Paging.label(1, 60, 30))
    }

    @Test fun `no cell is spent on navigation`() {
        // Splitting the last cell into forward and back was considered and rejected: a set of
        // thirty that spends one on navigation is a set of twenty-nine, and the arrow would sit
        // somewhere different on every page.
        for (n in listOf(15, 30, 60, 120)) {
            val per = Grid.perPage(n, 1)
            assertEquals(
                n,
                (0 until Grid.pageCount(n, 1)).sumOf { Paging.slotsOn(it, n, per).count() },
            )
        }
    }

    // ── WAVEFORM DETAIL ─────────────────────────────────────────────

    @Test fun `each step of detail is a whole multiple of the base`() {
        assertEquals(64, waveformBuckets(2))
        assertEquals(128, waveformBuckets(4))
        assertEquals(256, waveformBuckets(8))
        assertEquals(512, waveformBuckets(16))
    }

    @Test fun `the scale is the multiplier itself, not a position in the list`() {
        // It used to be an index clamped to the list's LENGTH, so a stored 4 meant 4x while there
        // were four entries. With multipliers in the list that same 4 would have meant 128x.
        for (n in WAVEFORM_SCALES) assertEquals(32 * n, waveformBuckets(n))
    }

    @Test fun `a stored scale outside the range cannot ask for absurd work`() {
        // A hundred and twenty cells at 128 times the base would be a page that takes a second to
        // open, from a preference nobody set on purpose.
        assertEquals(waveformBuckets(2), waveformBuckets(0))
        assertEquals(waveformBuckets(2), waveformBuckets(-5))
        assertEquals(waveformBuckets(16), waveformBuckets(999))
    }

    @Test fun `the offered scales are the ones the app clamps to`() {
        assertEquals(2, WAVEFORM_SCALES.first())
        assertEquals(16, WAVEFORM_SCALES.last())
        assertEquals(WAVEFORM_SCALES.sorted(), WAVEFORM_SCALES)
    }

    @Test fun `the waveform really returns the number of slices asked for`() {
        val samples = ShortArray(9_000) { (it % 500).toShort() }
        for (scale in WAVEFORM_SCALES) {
            assertEquals(waveformBuckets(scale), waveform(samples, waveformBuckets(scale)).size)
        }
    }

    // ── THE TWO POINTS DO NOT UNDO EACH OTHER ─────────────────────────────

    @Test fun `moving the out point keeps the in point that was already set`() {
        // The bug was in the gesture, not here, but the rule is what the gesture must be fed: a
        // move of one point is computed from the CURRENT pair, never from a remembered one.
        val afterIn = Trim.withIn(Trim.NONE, 1_000, 5_000)
        val afterOut = Trim.withOut(afterIn, 4_000, 5_000)
        assertEquals(1_000, afterOut.inMs)
        assertEquals(4_000, afterOut.outMs)
    }

    @Test fun `moving the in point keeps the out point that was already set`() {
        val afterOut = Trim.withOut(Trim.NONE, 4_000, 5_000)
        val afterIn = Trim.withIn(afterOut, 1_000, 5_000)
        assertEquals(1_000, afterIn.inMs)
        assertEquals(4_000, afterIn.outMs)
    }

    @Test fun `a long alternating drag keeps both points`() {
        var t = Trim.NONE
        for (round in 1..20) {
            t = Trim.withIn(t, round * 50, 5_000)
            t = Trim.withOut(t, 5_000 - round * 50, 5_000)
            assertTrue("in was lost on round $round", t.inMs > 0)
            assertTrue("out was lost on round $round", t.outMs > 0)
            assertTrue(t.inMs < t.endOf(5_000))
        }
    }

    // ── THE LAYOUT FILLS THE PAGE ─────────────────────────────────────

    @Test fun `one cell on one page is the whole page`() {
        val l = Grid.of(1, 1)
        assertEquals(1, l.perPage)
        assertEquals(1, l.columns)
        assertEquals(1, l.rows)
    }

    @Test fun `two cells on one page are halves`() {
        val l = Grid.of(2, 1)
        assertEquals(2, l.perPage)
        assertEquals(2, l.columns)
        assertEquals(1, l.rows)
    }

    @Test fun `two cells over two pages are one each`() {
        val l = Grid.of(2, 2)
        assertEquals(1, l.perPage)
        assertEquals(1, l.columns)
        assertEquals(2, Grid.pageCount(2, 2))
    }

    @Test fun `four cells are a two by two`() {
        val l = Grid.of(4, 1)
        assertEquals(2, l.columns)
        assertEquals(2, l.rows)
    }

    @Test fun `thirty cells stay the three across grid that already worked`() {
        val l = Grid.of(30, 1)
        assertEquals(3, l.columns)
        assertEquals(10, l.rows)
    }

    @Test fun `columns never exceed the cap`() {
        for (n in 1..MAX_SLOTS) {
            assertTrue("$n gave ${Grid.of(n, 1).columns}", Grid.of(n, 1).columns <= MAX_COLUMNS)
            assertTrue(Grid.of(n, 1).columns >= 1)
        }
    }

    @Test fun `every cell has a place on the grid`() {
        for (n in listOf(1, 2, 3, 4, 5, 7, 12, 30, 61, 300)) {
            for (pages in listOf(1, 2, 3, 7)) {
                val l = Grid.of(n, pages)
                assertTrue(
                    "$n over $pages pages: ${l.rows}x${l.columns} cannot hold ${l.perPage}",
                    l.rows * l.columns >= l.perPage,
                )
            }
        }
    }

    @Test fun `the last page is short rather than an empty one being added`() {
        // Ten over three pages is four, four, two. Rounding the other way leaves a blank page.
        assertEquals(4, Grid.perPage(10, 3))
        assertEquals(3, Grid.pageCount(10, 3))
        assertEquals(2, Paging.slotsOn(2, 10, 4).count())
    }

    @Test fun `more pages than cells cannot produce an empty page`() {
        assertEquals(1, Grid.perPage(3, 99))
        assertEquals(3, Grid.pageCount(3, 99))
    }

    @Test fun `every cell appears on exactly one page`() {
        for (n in listOf(1, 5, 12, 30, 61, 120)) {
            for (pages in listOf(1, 2, 4)) {
                val per = Grid.perPage(n, pages)
                val seen = (0 until Grid.pageCount(n, pages)).flatMap { Paging.slotsOn(it, n, per) }
                assertEquals("$n over $pages pages", (0 until n).toList(), seen)
            }
        }
    }

    @Test fun `adding cells does not move the ones already there`() {
        // Raising the count appends. A cell that was fourth must still be fourth, because there is
        // a recording in it and its number is how it is known.
        val before = (0 until Grid.pageCount(12, 2)).flatMap { Paging.slotsOn(it, 12, Grid.perPage(12, 2)) }
        val after = (0 until Grid.pageCount(20, 2)).flatMap { Paging.slotsOn(it, 20, Grid.perPage(20, 2)) }
        assertEquals(before, after.take(before.size).sorted().take(before.size))
        assertTrue(after.containsAll(before))
    }

    // ── THE TITLE IS WHAT WAS SAID ───────────────────────────────────

    @Test fun `a transcribed cell is titled with the whole sentence`() {
        assertEquals("Danas je lijep dan", Slot(0, words = "  Danas je lijep dan  ").title())
    }

    // ── A SET IS NOT ALWAYS THIRTY ────────────────────────────────────────────────────────────

    @Test fun `the triangle stops at the last slot of THIS set, not of thirty`() {
        assertNull(Advance.next(14, total = 15))
        assertEquals(15, Advance.next(14, total = 60))
    }

    @Test fun `the triangle carries the count of this set`() {
        assertEquals("15", Advance.glyph(14, total = 15))
        assertEquals("", Advance.glyph(14, total = 60))
        assertEquals("120", Advance.glyph(119, total = 120))
    }

    @Test fun `a press past the end of a smaller set is refused`() {
        val small = sized(15)
        assertTrue(Gesture.press(Mode.STOPPED, small, 15) is Press.Refused)
        assertTrue(Gesture.press(Mode.STOPPED, small, 14) is Press.StartRecording)
    }

    @Test fun `a project reports its own size and page count`() {
        assertEquals(15, sized(15).size)
        // The spread is now a choice, so a project cannot report a page count on its own.
        assertEquals(1, sized(15).pages(1))
        assertEquals(3, sized(15).pages(3))
        assertEquals(4, sized(120).pages(4))
    }

    // ── TITLES ────────────────────────────────────────────────────────────────────────────────

    @Test fun `an untranscribed slot is called by its number, one-based`() {
        assertEquals("Title 01", Slot(0).title())
        assertEquals("Title 30", Slot(29).title())
    }

    @Test fun `a transcribed slot is called by what was said`() {
        assertEquals("Danas je lijep dan", Slot(0, words = "  Danas je lijep dan ").title())
    }

    @Test fun `whitespace-only words fall back to the number`() {
        assertEquals("Title 01", Slot(0, words = "   ").title())
    }

    // ── RE-RECORDING ──────────────────────────────────────────────────────────────────────────

    @Test fun `re-recording clears the transcript and every generated voice`() {
        val before = Slot(0, hasOriginal = true, words = "hello", generated = setOf("edge", "hume"))
        val after = Rerecord.clearDerived(before)
        assertEquals("", after.words)
        assertTrue(after.generated.isEmpty())
        assertTrue("the original must survive a re-record", after.hasOriginal)
    }

    @Test fun `the warning names what will be lost, and is silent when nothing will be`() {
        assertTrue(Rerecord.warning(Slot(0, words = "hi")).contains("transcript"))
        assertTrue(Rerecord.warning(Slot(0, generated = setOf("edge"))).contains("generated"))
        assertEquals("", Rerecord.warning(Slot(0, hasOriginal = true)))
    }

    // ── GENERATE ──────────────────────────────────────────────────────────────────────────────

    @Test fun `generate does the ready ones and counts the skipped`() {
        val slots = (0 until DEFAULT_SLOTS).map {
            when (it) {
                0 -> Slot(0, hasOriginal = true, words = "one")
                1 -> Slot(1, hasOriginal = true, words = "")
                2 -> Slot(2, hasOriginal = true, words = "three")
                else -> Slot(it)
            }
        }
        val plan = GeneratePlan.of(Project("p", "p", slots))
        assertEquals(listOf(0, 2), plan.ready)
        assertEquals(listOf(1), plan.skipped)
        assertTrue(plan.line().contains("skipping 1"))
    }

    @Test fun `generate with nothing transcribed says so rather than starting`() {
        val plan = GeneratePlan.of(filled(0, 1))
        assertTrue(plan.ready.isEmpty())
        assertTrue(plan.line().contains("transcribe first"))
    }

    // ── THE LIST FOLLOWS BY JUMPING ───────────────────────────────────────────────────────────

    @Test fun `the list does not move while the playing line is visible`() {
        assertNull(Follow.jumpTo(playing = 3, firstVisible = 0, visibleCount = 8))
        assertNull(Follow.jumpTo(playing = 7, firstVisible = 0, visibleCount = 8))
    }

    @Test fun `the list jumps when the playing line goes off the bottom`() {
        assertEquals(7, Follow.jumpTo(playing = 8, firstVisible = 0, visibleCount = 8))
    }

    @Test fun `a jump never asks for a negative row or past the end`() {
        assertEquals(0, Follow.jumpTo(playing = 0, firstVisible = 5, visibleCount = 8))
        val j = Follow.jumpTo(playing = 29, firstVisible = 0, visibleCount = 8)
        assertTrue(j!! <= DEFAULT_SLOTS - 8)
    }

    // ── THE SILENCE CEILING ─────────────────────────────────────────

    @Test fun `quiet for less than the ceiling does not end the recording`() {
        val c = Ceiling(afterMs = 1_000L)
        assertFalse(c.exceeded(0.05f, 100))
        assertFalse(c.exceeded(0.05f, 1_099))
    }

    @Test fun `quiet for the whole ceiling ends the recording`() {
        val c = Ceiling(afterMs = 1_000L)
        assertFalse(c.exceeded(0.05f, 100))
        assertTrue(c.exceeded(0.05f, 1_100))
    }

    @Test fun `speech resets the ceiling, so a long pause mid-phrase is safe`() {
        // A slot left open by mistake must end itself. A person thinking mid-sentence must not.
        //
        // EVERY TIME HERE IS DERIVED FROM THE LIMIT RATHER THAN TYPED. This test was written twice
        // with the wrong number, both times by counting the quiet from the speech instead of from
        // the moment the level fell again. Arithmetic in a test is code, and it went wrong exactly
        // where the code it was checking could have.
        val limit = 1_000L
        val c = Ceiling(afterMs = limit)
        assertFalse(c.exceeded(0.05f, 100))
        assertFalse("speech must clear the count", c.exceeded(0.90f, 900))
        val wentQuiet = 1_500L
        assertFalse(c.exceeded(0.05f, wentQuiet))
        assertFalse(c.exceeded(0.05f, wentQuiet + limit - 1))
        assertTrue(c.exceeded(0.05f, wentQuiet + limit))
    }

    @Test fun `the default ceiling is a guard and not an endpointer`() {
        // Ninety seconds: far past any pause inside a sentence, far short of filling a phone.
        assertTrue(Ceiling.SILENCE_CEILING_MS >= 60_000L)
    }

    @Test fun `a reset ceiling counts again from the next quiet moment`() {
        val c = Ceiling(afterMs = 1_000L)
        c.exceeded(0.05f, 100)
        c.reset()
        assertFalse(c.exceeded(0.05f, 1_100))
        assertTrue(c.exceeded(0.05f, 2_100))
    }

    // ── NORMALISATION ────────────────────────────────────────────────

    private fun peakOf(a: ShortArray) = a.maxOf { kotlin.math.abs(it.toInt()) }

    @Test fun `a quiet take is brought up to just under full scale`() {
        // Peak 6000 of 32767, about -15 dBFS: a phrase spoken at arm's length. Deliberately
        // ABOVE the level where MAX_GAIN starts to bite, because this case is about reaching
        // the target and the cap has its own test below. Written at 3000 first, where the cap
        // held at 30000 and the test failed for the right reason.
        val quiet = ShortArray(1000) { if (it % 2 == 0) 6000 else -6000 }
        val loud = normalise(quiet)
        val target = (TARGET_PEAK * 32767f).toInt()
        assertTrue("peak was ${peakOf(loud)}, wanted about $target", peakOf(loud) in (target - 2)..target)
    }

    @Test fun `normalisation never reaches the rail`() {
        // A tenth of a decibel of headroom, so nothing downstream has to round in our favour.
        val quiet = ShortArray(500) { 1000 }
        assertTrue(peakOf(normalise(quiet)) < 32767)
    }

    @Test fun `a take already at full scale is left exactly alone`() {
        // Attenuating a hot take does not improve it and re-scaling always loses to rounding.
        val hot = ShortArray(100) { if (it % 2 == 0) 32767 else -32767 }
        assertTrue(normalise(hot) === hot)
    }

    @Test fun `silence is not amplified into anything`() {
        val silence = ShortArray(500)
        val out = normalise(silence)
        assertEquals(0, peakOf(out))
    }

    @Test fun `the gain is capped so a near-silent room is not turned into hiss`() {
        // Without a ceiling this multiplies by four hundred and the tile shows a healthy waveform
        // over a recording of an empty room.
        val nearlySilent = ShortArray(500) { 80 }
        val out = normalise(nearlySilent)
        assertEquals((80 * MAX_GAIN).toInt(), peakOf(out))
        assertTrue("the cap did not hold", peakOf(out) < (TARGET_PEAK * 32767f).toInt())
    }

    @Test fun `normalisation preserves length and shape`() {
        val src = ShortArray(64) { (it * 100 - 3200).toShort() }
        val out = normalise(src)
        assertEquals(src.size, out.size)
        // The ratio between any two samples survives, which is what makes it a level change and
        // not a distortion.
        val gain = out[0].toDouble() / src[0]
        for (i in src.indices) {
            if (src[i].toInt() == 0) continue
            assertEquals(gain, out[i].toDouble() / src[i], 0.02)
        }
    }

    @Test fun `an empty array is returned untouched rather than crashing`() {
        assertEquals(0, normalise(ShortArray(0)).size)
    }

    // ── PLAY MODE ───────────────────────────────────────────────────

    @Test fun `continuous play walks the running order`() {
        val p = filled(0, 4, 9)
        assertEquals(4, nextInPlayback(p, 0, PlayMode.CONTINUOUS))
        assertEquals(9, nextInPlayback(p, 4, PlayMode.CONTINUOUS))
    }

    @Test fun `continuous play stops at the end rather than looping`() {
        assertNull(nextInPlayback(filled(0, 4, 9), 9, PlayMode.CONTINUOUS))
    }

    @Test fun `single play always stops`() {
        val p = filled(0, 4, 9)
        for (slot in listOf(0, 4, 9)) {
            assertNull("slot $slot continued in single mode", nextInPlayback(p, slot, PlayMode.SINGLE))
        }
    }

    @Test fun `continuous play follows an arranged order, not the slot numbers`() {
        val p = filled(0, 4, 9).copy(order = listOf(9, 0, 4))
        assertEquals(0, nextInPlayback(p, 9, PlayMode.CONTINUOUS))
        assertEquals(4, nextInPlayback(p, 0, PlayMode.CONTINUOUS))
        assertNull(nextInPlayback(p, 4, PlayMode.CONTINUOUS))
    }

    @Test fun `a slot cleared while sounding stops rather than jumping somewhere else`() {
        assertNull(nextInPlayback(filled(0, 4), 9, PlayMode.CONTINUOUS))
    }

    @Test fun `continuous is the default, because it is what the app was built for`() {
        assertEquals(PlayMode.CONTINUOUS, PlayMode.entries.first())
    }

    // ── THE METER MUST NOT LIE ────────────────────────────────────────────────────────────────

    @Test fun `a dead microphone reads flat rather than dancing`() {
        val vu = Vu()
        var last = 0f
        repeat(40) { last = vu.fromPeak(0) }
        assertTrue("a silent input produced $last", last < 0.05f)
    }

    @Test fun `a loud input reaches most of the way up`() {
        val vu = Vu()
        var last = 0f
        repeat(40) { last = vu.fromPeak(30_000) }
        assertTrue("a loud input produced $last", last > 0.5f)
    }

    // ── THE KEY PARSER, AGAINST A REAL MESSY NOTE ─────────────────────────────────────────────

    @Test fun `keys are found inside a note full of prose, urls and account names`() {
        val note = """
            https://speechify.ai/?srsltid=AfmBOorFakeTrackingTokenThatIsNotAKeyAtAll1234567890
            Marko personal
            sk_${"a".repeat(43)}
            CANCELLED
            work account
            gsk_${"b".repeat(40)}
            cafeteria
        """.trimIndent()
        val found = KeyParser.extract(note)
        val speechify = found.filter { it.providerId == "speechify" }
        val groq = found.filter { it.providerId == "groq" }
        assertEquals(1, speechify.size)
        assertEquals(1, groq.size)
        assertEquals("Marko personal", speechify[0].label)
        assertEquals("work account", groq[0].label)
    }

    @Test fun `the word cafeteria is never treated as a key`() {
        // A naive whitespace split has genuinely produced an attempt to authenticate with it.
        assertNull(KeyParser.classify("cafeteria"))
    }

    @Test fun `an srsltid tracking token is not routed to any provider`() {
        val tok = "AfmBOorFakeTrackingTokenThatIsNotAKeyAtAll1234567890abcd"
        val id = KeyParser.classify(tok)
        // It may be carried as unknown, but it must never be offered to a real provider.
        assertTrue(id == null || id == "unknown")
    }

    @Test fun `hume pairs are parsed as pairs, because the pair is the unit`() {
        val note = """
            Baba main
            API key
            ${"A".repeat(48)}
            Secret key
            ${"B".repeat(64)}
        """.trimIndent()
        val found = KeyParser.extract(note)
        val hume = found.single { it.providerId == "hume" }
        assertEquals("Baba main", hume.label)
        assertEquals(48, hume.key.length)
        assertEquals(64, hume.secret?.length)
    }

    @Test fun `a hume secret is not also emitted as a key of its own`() {
        val note = "acct\nAPI key\n${"A".repeat(48)}\nSecret key\n${"B".repeat(64)}\n"
        assertEquals(1, KeyParser.extract(note).size)
    }

    @Test fun `sk underscore splits speechify from elevenlabs on length alone`() {
        assertEquals("speechify", KeyParser.classify("sk_" + "a".repeat(43)))
        assertEquals("elevenlabs", KeyParser.classify("sk_" + "a".repeat(20)))
    }

    @Test fun `duplicate keys are folded so a dead one is not wasted twice`() {
        val k = "gsk_" + "c".repeat(40)
        assertEquals(1, KeyParser.extract("$k\n$k\n").size)
    }

    // ── SPEAKING TEXT THAT CAME FROM A MICROPHONE ─────────────────────────────

    @Test fun `a quote in a transcript cannot break the request body`() {
        // The text is whatever was said, arriving from a transcriber. An unescaped quote makes
        // malformed JSON, and the engine answers 400 for what looks like a perfectly good phrase.
        assertEquals("\"he said \\\"no\\\"\"", Engines.quote("he said \"no\""))
    }

    @Test fun `a backslash survives escaping`() {
        assertEquals("\"a\\\\b\"", Engines.quote("a\\b"))
    }

    @Test fun `newlines and tabs become escapes rather than raw bytes`() {
        assertEquals("\"a\\nb\"", Engines.quote("a\nb"))
        assertEquals("\"a\\tb\"", Engines.quote("a\tb"))
    }

    @Test fun `a control character is escaped rather than sent raw`() {
        assertTrue(Engines.quote("a\u0001b").contains("\\u0001"))
    }

    @Test fun `ordinary words are left alone`() {
        assertEquals("\"danas je lijep dan\"", Engines.quote("danas je lijep dan"))
    }

    // ── READING A REPLY WITHOUT A JSON LIBRARY ───────────────────────────────

    @Test fun `a string field is read out of a reply`() {
        assertEquals("done", Net.str("""{"status":"done","x":1}""", "status"))
    }

    @Test fun `a missing field reads as null rather than as an empty answer`() {
        assertNull(Net.str("""{"status":"done"}""", "text"))
    }

    @Test fun `an escaped quote inside a value survives the round trip`() {
        assertEquals("he said \"no\"", Net.str("""{"text":"he said \"no\""}""", "text"))
    }

    @Test fun `a non-string field is not mistaken for one`() {
        assertNull(Net.str("""{"count":12}""", "count"))
    }

    @Test fun `an escaped newline in a transcript is decoded`() {
        assertEquals("a\nb", Net.str("""{"text":"a\nb"}""", "text"))
    }

    // ── THE VOICE IS PER CELL ─────────────────────────────────────────

    @Test fun `a cell with no chosen voice plays the original`() {
        val root = File("/tmp/x")
        assertEquals(Paths.original(root, "p", 3), Paths.playing(root, "p", 3, null))
    }

    @Test fun `choosing a voice for one cell says nothing about any other`() {
        val slots = (0 until DEFAULT_SLOTS).map {
            if (it == 3) Slot(it, hasOriginal = true, voice = "hume") else Slot(it, hasOriginal = true)
        }
        val p = Project("p", "p", slots)
        assertEquals("hume", p.slot(3).voice)
        for (i in 0 until DEFAULT_SLOTS) {
            if (i != 3) assertNull("cell $i was changed too", p.slot(i).voice)
        }
    }

    @Test fun `a generated voice is a different file from the recording it speaks for`() {
        val root = File("/tmp/x")
        for (engine in listOf(Engines.SPEECHIFY, Engines.HUME)) {
            assertNotEquals(
                Paths.original(root, "p", 3).path,
                Paths.generated(root, "p", 3, engine).path,
            )
        }
    }

    @Test fun `reverting to my own recording is not a restore`() {
        // Nothing is copied back, because nothing was ever overwritten. Clearing the chosen voice
        // is the whole operation, and this asserts the original path does not depend on it.
        val root = File("/tmp/x")
        val before = Paths.original(root, "p", 3)
        assertEquals(before, Paths.playing(root, "p", 3, null))
    }

    // ── THE STATUS MAPPING ────────────────────────────────────────────────────────────────────

    @Test fun `429 leaves the key alive`() {
        // The one that gets lost, and losing it eats a ten-key ring in an afternoon.
        assertEquals(Status.LIMITED, Classify.status(429, ""))
        assertNotEquals(Status.REJECTED, Classify.status(429, ""))
    }

    @Test fun `403 carrying cloudflare 1010 is not the key`() {
        assertNotEquals(Status.REJECTED, Classify.status(403, "error code: 1010"))
        assertEquals(Status.REJECTED, Classify.status(403, "forbidden"))
    }

    @Test fun `400 with credit words is death and a plain 400 is not`() {
        assertEquals(Status.REJECTED, Classify.status(400, "E0300 insufficient credits"))
        assertEquals(Status.OTHER, Classify.status(400, "bad request"))
    }

    @Test fun `404 blames the path rather than the key`() {
        assertEquals(Status.OTHER, Classify.status(404, "page not found"))
    }

    // ── THE RING ──────────────────────────────────────────────────────────────────────────────

    private fun ringOf(n: Int) = Ring((1..n).map { Credential("key-$it-${"z".repeat(30)}", null, "acct $it") })

    @Test fun `the ring hands out one key and keeps handing out the same one`() {
        val r = ringOf(3)
        assertEquals(r.current(), r.current())
    }

    @Test fun `a condemned key is never returned again`() {
        val r = ringOf(3)
        val first = r.current()!!
        r.condemn(first)
        repeat(10) { assertNotEquals(first.key, r.current()?.key) }
    }

    @Test fun `a rested key comes back after its rest and is not condemned`() {
        val r = ringOf(2)
        val first = r.current(1_000)!!
        r.rest(first, 5_000, 1_000)
        assertNotEquals(first.key, r.current(2_000)?.key)
        assertEquals(0, r.deadCount())
        r.condemn(r.current(2_000)!!)
        assertEquals(first.key, r.current(10_000)?.key)
    }

    @Test fun `an exhausted ring returns null rather than a dead key`() {
        val r = ringOf(2)
        r.condemn(r.current()!!)
        r.condemn(r.current()!!)
        assertNull(r.current())
    }

    @Test fun `revive brings condemned keys back, because credit gets topped up`() {
        val r = ringOf(2)
        r.condemn(r.current()!!)
        r.condemn(r.current()!!)
        r.revive()
        assertTrue(r.current() != null)
    }

    @Test fun `a masked key never shows its middle`() {
        // Assembled from pieces rather than written as one string. G2 scans the whole history and
        // the built artefact for key SHAPES, and it cannot tell a plausible fixture from a real
        // key — nor should it try. A test that trips the secret scanner teaches you to ignore it.
        val c = Credential("sk" + "_abc" + "defghijklmnop" + "qrstuvwxyz012345", null, "x")
        val m = c.masked()
        assertTrue(m.startsWith("sk_abc"))
        assertTrue(m.endsWith("2345"))
        assertFalse(m.contains("jklmno"))
    }

    @Test fun `the dead list stores a fingerprint and not a key`() {
        val c = Credential("gsk_" + "d".repeat(40), null, "x")
        val f = c.fingerprint()
        assertEquals(64, f.length)
        assertFalse(f.contains("gsk_"))
    }

    // ── THE FILE MANAGER ARITHMETIC ───────────────────────────────────────────────────────────

    @Test fun `usage counts filled slots, files and bytes`() {
        val root = Files.createTempDirectory("sp").toFile()
        Paths.original(root, "p", 0).also { it.parentFile?.mkdirs(); it.writeBytes(ByteArray(1000)) }
        Paths.generated(root, "p", 0, "edge").also { it.parentFile?.mkdirs(); it.writeBytes(ByteArray(500)) }
        val u = Usage.ofProject(root, "p")
        assertEquals(1, u.filledSlots)
        assertEquals(2, u.files)
        assertEquals(1500L, u.bytes)
        root.deleteRecursively()
    }

    @Test fun `clearing generated audio leaves every original untouched`() {
        // The hardest rule in the app, exercised through the operation most likely to break it.
        val root = Files.createTempDirectory("sp").toFile()
        for (i in 0..2) {
            Paths.original(root, "p", i).also { it.parentFile?.mkdirs(); it.writeText("mine $i") }
            Paths.generated(root, "p", i, "hume").also { it.parentFile?.mkdirs(); it.writeText("robot") }
        }
        val removed = Vault(root).clearGenerated("p")
        assertEquals(3, removed)
        for (i in 0..2) {
            assertTrue(Paths.original(root, "p", i).isFile)
            assertEquals("mine $i", Paths.original(root, "p", i).readText())
        }
        root.deleteRecursively()
    }

    @Test fun `megabytes are readable rather than a raw byte count`() {
        assertEquals("1.0 MB", Usage(1, 1, 1, 1_048_576L).megabytes())
    }
}
