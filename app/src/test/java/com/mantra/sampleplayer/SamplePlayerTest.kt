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
        assertEquals(1, Paging.pageCount(30))
        assertEquals(1, Paging.pageCount(1))
    }

    @Test fun `an empty set still has a screen to look at`() {
        assertEquals(1, Paging.pageCount(0))
    }

    @Test fun `a set larger than one screen is more pages`() {
        assertEquals(2, Paging.pageCount(60))
        assertEquals(4, Paging.pageCount(120))
        assertEquals(2, Paging.pageCount(31))
    }

    @Test fun `the last page is short rather than padded`() {
        assertEquals(30 until 31, Paging.slotsOn(1, 31))
    }

    @Test fun `every offered cell count divides into whole pages of thirty`() {
        // Not required, but if one of these ever leaves a page holding a single cell it should be
        // a decision rather than a surprise.
        for (n in SLOT_CHOICES) {
            val pages = Paging.pageCount(n)
            assertTrue("$n cells gave $pages pages", pages >= 1)
            assertEquals(n, (0 until pages).sumOf { Paging.slotsOn(it, n).count() })
        }
    }

    @Test fun `a page past the end holds nothing rather than wrapping`() {
        assertTrue(Paging.slotsOn(5, 30).isEmpty())
    }

    @Test fun `a slot knows which page it is on`() {
        assertEquals(0, Paging.pageOf(0))
        assertEquals(0, Paging.pageOf(29))
        assertEquals(1, Paging.pageOf(30))
        assertEquals(3, Paging.pageOf(119))
    }

    @Test fun `the page label is silent when there is nothing to flip`() {
        assertEquals("", Paging.label(0, 30))
        assertEquals("page 1 / 2", Paging.label(0, 60))
        assertEquals("page 2 / 2", Paging.label(1, 60))
    }

    @Test fun `no cell is spent on navigation`() {
        // Splitting the last cell into forward and back was considered and rejected: a set of
        // thirty that spends one on navigation is a set of twenty-nine, and the arrow would sit
        // somewhere different on every page.
        for (n in SLOT_CHOICES) {
            assertEquals(n, (0 until Paging.pageCount(n)).sumOf { Paging.slotsOn(it, n).count() })
        }
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
        assertEquals(1, sized(15).pages)
        assertEquals(4, sized(120).pages)
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
