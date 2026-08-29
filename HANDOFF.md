# HANDOFF — Sample Player

**Current version: none. This is the specification, pushed before a line of code exists.**
Repository public at `markoboskoauroville/SAMPLE_PLAYER`.

The reasoning behind each decision, including what was tried and rejected, is in
[`NEXT_DEFAULTS.md`](NEXT_DEFAULTS.md). What was and was not proven about a shipped artefact will be
in `DELIVERY_RECORD.md` from v1 onward.

This document exists before the code because two thirds of `MINIMALIST_STOPWATCH`'s versions exist
because something decided on a build server was wrong on glass. A specification that can be read and
corrected in five minutes is cheaper than a build that has to be reversed.

---

## What it is

An Android app for recording thirty spoken phrases, transcribing them, replacing the voice, and
playing the set back in order. It is a fork of `MINIMALIST_STOPWATCH` and a merge of parts that
already exist in four other repositories.

**The workflow it exists for:** read a script from the phone screen while the app is in the
background, speak a line, press the triangle overlay, speak the next one. Samples fill from one slot
to the next like buckets, without ever leaving the app you are reading from.

## The project model

**The app holds projects. A project is a set of thirty slots.** Create a new project, record into
it, and switch between projects from settings. Nothing is global except the keys.

    projects/<project-id>/samples/NN/original.wav      what Baba said. Never overwritten
    projects/<project-id>/samples/NN/gen/<engine>.wav  what an engine said. Beside, never on top
    projects/<project-id>/project.json                 titles, transcripts, chosen engine, order

**`original.wav` is a different filename from every generated file, not a version of the same one.**
That is the hardest rule in this app: a bug that overwrites it destroys work that cannot be
re-recorded.

## The main view

Thirty full-width lines, scrolling, in the manner of `MINIMALIST_STOPWATCH` v19's VOICE tab. **Not a
three across grid.** v17 of the stopwatch replaced a three by three pad grid with nine full-width
lines for a recorded reason: a grid of small squares reads as a keypad, a full-width line reads as a
sample list, and the line is what makes a waveform legible at 96 buckets instead of 28. Three across
at thirty tiles is narrower than the pads that decision rejected.

Each line carries its title, its waveform, and a playhead. Empty is hollow, filled is solid, one
icon language with the stopwatch and with TTT mini.

**Titles start as `Title 01` … `Title 30`.** After transcription the title becomes the first word of
the transcribed text, because before transcription there is nothing better to call it.

Action buttons across the bottom: **Seq**, **Voice**, and one wide **Play**.

## The two modes, and the rule that keeps them apart

    STOPPED   pressing a line RECORDS into it. Pressing again stops. Long press clears it
    PLAYING   pressing a line SEEKS to it and plays from there

**One gesture, two meanings, and the mode is the only thing separating them.** This is the shape the
stopwatch rejected at v1 when it deleted tap-anywhere, on the grounds that two contradictory things
must not share a surface. It is accepted here because the destructive half is gated behind a mode
the person can see, and because seeking during playback is the whole point of a player you can jump
around in.

**It is nevertheless the most dangerous line in this document**, and it is written down so it is
tested rather than assumed: a press that records while the app believed it was playing destroys a
recording. Test 1 walks both modes against every line state; a mutation flips the mode check.

## The playhead

**Every line carries a thin vertical line travelling left to right across its own waveform**, showing
where inside that sample the playback is. When a sample finishes, the playhead leaves that line and
appears at the start of the next. Over a full play the mark travels down the whole list.

The lines are tiny players and the playhead is what makes them players rather than pictures.

**The list follows the playhead by jumping, never by smooth scrolling.** `design-language.md` §8:
nothing animated that the eye has to follow. When the playing line is off screen the list jumps so
that it is on screen, and it does not move again until the playhead leaves that line.

## The capture

Ported from `MINIMALIST_STOPWATCH` v17, which is where this behaviour was paid for.

- **Press to start, press again to stop.** The stopwatch ends a capture on the silence after a word,
  because a command is one word long. Here the phrases are of unknown length, so the person decides.
- **A silence ceiling sits underneath the press**, so a slot left recording by mistake does not
  record the room until the disk fills. This was not asked for; it is here because a forgotten tile
  is the obvious failure of press-to-stop and the cost of the guard is one number.
- **The window reaches back 250ms past the onset.** The level only crosses the threshold once the
  word is underway, because the first consonant is quieter than the vowel after it. Reading from the
  crossing point clips the front off every recording, and that fault looks like poor transcription
  rather than like a bad capture.
- **A recording is judged before it is stored.** Silent, too short and clipped are refused with an
  instruction rather than a code. `Dsp.kt` already carries this.

## Background recording, and the overlay

This is the part the app exists for and the part `TTT_MINI` already solved. Ported, not redesigned.

- `MaRecordingService`, a foreground service with `FOREGROUND_SERVICE_TYPE_MICROPHONE`.
- **Everything overlaid uses `TYPE_ACCESSIBILITY_OVERLAY`**, as `MaRecordingLine` does, so one
  permission covers the whole app.

Three overlay pieces, and no more:

    VU METER      a hairline across the very top, full width, one device pixel, not touchable.
                  TTT mini's MaRecordingLine and MaVuView, ported constant for constant
    TRIANGLE      a small outlined triangle pointing right, centred under the camera hole.
                  Touchable. Present only while recording
    STATUS        a terminal-style status line, as small as it can be read, centred under the
                  camera hole. Present only while generating. Shows "12 / 30"

**The triangle does one thing: stop this recording and start the next slot.** It is not a general
transport. To stop recording altogether, switch to the app and stop it there.

**At slot 30 the triangle carries the number 30 inside it**, so the last slot is visible without
counting. It does not wrap. Wrapping would overwrite slot 1 while the phone is in a pocket.

**The VU meter stays, and this is a reversal.** It was removed and put back, because with the app in
the background there is nothing else saying audio is arriving, and a sampler that keeps room tone as
the sound of a word fails silently: the slot looks filled, the count is right, and only the
transcription is wrong.

## The Voice view

**English only.** There is no language selector, because there is nothing to select between. If
Croatian is ever added it is a new decision and a new control, not a hidden setting.

1. **Transcribe.** AssemblyAI, the code path in `TTT_MINI`. Short phrases take the synchronous route
   rather than the polling one; thirty short samples through the slow path is thirty round trips.
   After it finishes every title becomes the first word of its text.
2. **Voice selection, one page per engine, swipe between them.** Edge, Hume, Speechify. **Only
   engines with usable keys get a page**, so the page count follows what is actually available.
3. **Every voice is previewable before it is chosen.** A list of names is not a choice.
4. **Generate Voices.** Calls the chosen engine for every transcribed sample.

**Generate does the samples that are ready and reports the count it skipped.** Refusing thirty
because one is untranscribed is the app making a decision that belongs to the person.

**Generating never overwrites `original.wav`.** After generating, a line plays the generated voice,
and one control puts every line back to the original.

## The engines

| Engine | Key | Voices | Note |
|---|---|---|---|
| Edge | none | large catalogue | free, no ring, no billing |
| Hume | **API key + Secret key, a pair** | account voices | 21 accounts held |
| Speechify | `sk_` ~46 chars | **8 curated simba-3.2** | 21 keys held |

**Speechify is the 8 curated simba-3.2 voices**: beatrice, dominic, edmund, geffen, harper, hugh,
imogen, wyatt. The full catalogue is a list nobody can choose from, and the shortlist is the set
`MA_READER_SPEECHIFY` already uses.

**`/v1/voices` pages.** Default 50, maximum 200, `has_more` and `next_cursor`. A single call looks
like it worked and returns the first fifty alphabetically. MA Reader v27 shipped a list that stopped
in the A's because of this.

**The model belongs to the voice, never to a global setting.** Each voice reports its own `models[]`.
Prefer `simba-3.2`, then `simba-english`, then whatever it offers first.

## Keys

Three rings and one router, per `keyring.md` and `provider-router.md`. The canonical `KeyParser` from
`Key_Tester` ports line for line, because it is Kotlin and so is this.

    the ring walks forward, never backwards into a key it has buried
    200 good · 401/403 dead, condemn and retry the same request · 429 valid and THROTTLED, rest it,
      never condemn · 404 usually the wrong path · anything else, stop and report
    ONE KEY PER JOB, never one key per call. An AssemblyAI upload belongs to the account that made
      it, and rotating mid-job returns 403 Cannot access uploaded file, which reads as six dead keys
    the dead list stores SHA-256 fingerprints, never keys

**Hume is a pair and the pair is the unit.** Test with token auth, `POST /oauth2-cc/token`, Basic
base64(apiKey:secret), `grant_type=client_credentials`. A 200 with an access_token proves both keys
are valid and matched; testing the API key alone cannot confirm the secret. The token lives about 30
minutes, which is shorter than a full Generate, so it is re-resolved on expiry and on a later 401.

**Send a User-Agent on every request.** api.hume.ai is behind Cloudflare: with no UA every pair
returns `403, error code: 1010` at once, measured 21 of 21 both ways. That reads as an entire dead
account list and is nothing to do with the keys. A 403 carrying 1010 is soft and never condemns.

**Hume pacing.** Roughly 12 seconds between TTS calls. Thirty samples is about six minutes, which is
why Generate has both a progress line in the app and a status overlay outside it.

**Each key carries the label on the line above it in the imported note**, which is what the canonical
parser already captures, and that label is what the tester and the importer display. A URL's
`srsltid` tracking parameter is not an account id, and is not shown.

## Permissions

Microphone, foreground service, accessibility overlay. **Every one of them is offered as a direct
link to the right settings screen at first run, and again from the settings screen**, so nobody has
to find an Android page by hand.

## File management

**Settings holds a file manager for samples.** Per project and in total: how many slots are filled,
how many megabytes they occupy, and a way to delete. Thirty samples times four audio files each adds
up, and the person who has to live with that must be able to see it and clear it without a cable.

## Re-recording

**A line can be re-recorded after transcription, and doing so clears that line's transcript, its
title and its generated audio.** Keeping them is the wrong answer and its wrongness does not appear
until a lot of work has been done: the line would play a generated voice saying the old words.

## Versioning, delivery, and the things not to pay for twice

Version is one whole number in `appVersion` in `gradle.properties`, and `versionCode`, `versionName`,
the filename `N-sampleplayer-vN.apk` and the tag `vN` are all derived from it. Only the two newest
releases are kept. Every GitHub Action is pinned by commit SHA.

Five lessons carried over from the stopwatch, each of which cost more than everything else there:

1. **A grep in a pipeline that matches nothing exits 1.** Under `pipefail` that fails a gate because
   the code is clean. It happened three times.
2. **Never grep source as prose.** Comments have satisfied checks the code no longer met and failed
   checks the code passed. Strip comments in one shared place.
3. **Assert an edit's anchor matched before replacing it.** Three edits reported success and did
   nothing, and one made a test file look green because the new tests were never there to fail.
4. **Every check has been wrong at least once**, and nearly all read as PASS while wrong.
5. **The phone is the authority.** Two thirds of the stopwatch's versions exist because something
   decided on a build server was wrong on glass.

And one from this app's own sources: **SpeechRecognizer does not work on this phone.** Five versions
of evidence. It is not reached for here at all.
