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

    private fun filled(vararg idx: Int): Project {
        val slots = (0 until SLOTS).map { Slot(it, hasOriginal = it in idx.toSet()) }
        return Project("p", "p", slots)
    }

    // ── THE PRESS RULE ────────────────────────────────────────────────────────────────────────

    @Test fun `stopped means a press records`() {
        assertEquals(Press.StartRecording(3), Gesture.press(Mode.STOPPED, filled(), 3))
    }

    @Test fun `stopped records even into a slot that already has audio`() {
        // Deliberate: re-recording is allowed. The interface warns; the rule does not refuse.
        assertEquals(Press.StartRecording(3), Gesture.press(Mode.STOPPED, filled(3), 3))
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
        for (i in 0 until SLOTS) {
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
            assertTrue(Gesture.press(m, filled(), SLOTS) is Press.Refused)
        }
    }

    @Test fun `long press clears only when stopped and only when there is something to clear`() {
        assertEquals(Press.Clear(2), Gesture.longPress(Mode.STOPPED, filled(2), 2))
        assertTrue(Gesture.longPress(Mode.PLAYING, filled(2), 2) is Press.Refused)
        assertTrue(Gesture.longPress(Mode.RECORDING, filled(2), 2) is Press.Refused)
        assertTrue(Gesture.longPress(Mode.STOPPED, filled(), 2) is Press.Refused)
    }

    // ── THE TRIANGLE ──────────────────────────────────────────────────────────────────────────

    @Test fun `the triangle advances one slot`() {
        assertEquals(1, Advance.next(0))
        assertEquals(29, Advance.next(28))
    }

    @Test fun `the triangle does not wrap at the last slot`() {
        assertNull(Advance.next(SLOTS - 1))
        assertNotEquals(0, Advance.next(SLOTS - 1))
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

    // ── TITLES ────────────────────────────────────────────────────────────────────────────────

    @Test fun `an untranscribed slot is called by its number, one-based`() {
        assertEquals("Title 01", Slot(0).title())
        assertEquals("Title 30", Slot(29).title())
    }

    @Test fun `a transcribed slot is called by its first word`() {
        assertEquals("Danas", Slot(0, words = "  Danas je lijep dan ").title())
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
        val slots = (0 until SLOTS).map {
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
        assertTrue(j!! <= SLOTS - 8)
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
        val c = Ceiling(afterMs = 1_000L)
        assertFalse(c.exceeded(0.05f, 100))
        assertFalse(c.exceeded(0.90f, 900))
        assertFalse(c.exceeded(0.05f, 1_500))
        assertFalse(c.exceeded(0.05f, 1_899))
        assertTrue(c.exceeded(0.05f, 1_900))
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
