# DEVELOPMENT — Sample Player

**Every bug, every fix, every feature, and why each decision was made.**

[`HANDOFF.md`](HANDOFF.md) is the finished state. This is the record of getting there, and it exists
so nobody undoes a decision that was already paid for. Read it before changing something that looks
strange: quite a lot of what looks strange is the fix.

Fourteen versions in one day, 29.8.2026. Twenty-six commits.

---

# PART ONE — THE BUGS

Listed first because they are the reason this file exists. Every one of them is a real failure that
reached either a build server or the phone.

## THE SAME BUG, THREE TIMES: a gesture reading a photograph of the state

`pointerInput` restarts its block **only when its key changes**. Anything the block closes over is
captured once and never refreshed. This has now cost three separate features in this one repository.

**v3 — the second tap did nothing.** The tile keyed `pointerInput` on the slot number, which never
changes. So the block held the callback from the first composition — the one that captured
`mode = STOPPED` and `recordingSlot = null`. Tapping a recording cell called that old lambda, which
asked to START a recording, which the recorder ignored because one was already running. **Nothing
happened, nothing failed, and there was nothing to see.** The state machine was innocent the whole
time.

**v12 — the in and out points undid each other.** The editor keyed its drag on the recording's
length, which never changes while the editor is open. Move the in point: the handler still holds the
pair with in at zero. Move the out point: it computes from that photograph and hands back a whole
new pair with in back at zero. Each drag threw the other away.

**The fix, both times:** `rememberUpdatedState`. `verify.py` now checks for it in the tile and in the
editor, because I made the same mistake twice in the same repository.

## v9 — a retake destroyed the take it failed to replace

Re-recording wrote straight over `original.wav`, truncating it on the first byte. If the new take
then failed the quality check the file was deleted. **A cough into an occupied cell destroyed a good
recording.**

Baba asked that re-recording replace the old take, and it does. What it must never do is delete the
old take and accept nothing. Takes go to `pending.wav` now and are promoted only once judged usable.

## v6–v9 — neither speech engine returned a single voice

**Two independent bugs on one screen**, and the screen said the same thing for both.

**Speechify.** The eight flagship voices are `beatrice_32`, `imogen_32` and so on. I searched for
`beatrice`. Walking the live catalogue: 992 voices, and of those eight names only `edmund`,
`dominic` and `harper` exist as bare ids at all — each a *different voice* from the `_32` one sharing
its display name. So the bug had two shapes available and picked the quieter one: nothing for five
names, silently the wrong voice for three.

`MAHA_TRANSCRIBE_STREAMLIT/ttt/providers/speechify.py` had the right ids since 24.8.2026. I did not
read it until the app failed on the phone.

**Hume.** Listing worked; speaking did not. Three of the twenty-one accounts on this ring answer
`400 E0300 zero_credits` and the first one is account one. `speak()` asked the ring for a key,
condemned it correctly, **and then gave up** — so eighteen good accounts sat unreachable behind three
dead ones. A condemnation means retry the same request on the next key.

**And the reason both took a desk to find:** every failure returned an empty list. No key,
Cloudflare, throttled, wrong ids, a body that did not parse — all of them arrived on screen as "no
voices came back". That is a message nobody can act on.

All of this is now written up in the manifest as
[`modules/generating-audio.md`](https://github.com/markoboskoauroville/MANTRA_MANIFEST/blob/main/modules/generating-audio.md).

## v2 — the app drew under the clock and the navigation buttons

The project name sat beneath the status bar and the action row beneath the navigation buttons, so
the widest control in the app was the hardest to press. Targeting SDK 35 means edge-to-edge is not
opt-in. `safeDrawingPadding` takes what the system reports; a typed constant would be wrong on a
phone with the camera hole elsewhere.

## v12 — the out point was invisible

Both handles were amber and three pixels wide, so with the out point at its default — the end of the
recording — the line sat under the one-pixel border at the right edge. It was drawn, it was
draggable, and nothing on screen said so, which is the same thing as missing.

## v1 — a ported state machine that nothing called

Test 1 failed on the hangover arithmetic. Fixing the arithmetic turned up the real fault: **nothing
in the app ever called `Capture`.** Five green tests were describing a state machine the recorder
does not touch — worse than no test, because it reports confidence about a path the app never takes.

What was real was four lines guarding a forgotten recording, inline in the reading thread where no
test could reach them. That is `Ceiling` now: pure, used, and tested through the path the app takes.

## v11 — a regex renamed a test and left it asserting the old rule

I rewrote the paging call sites with a regex. It missed six inside loop bodies, which the compiler
found in 24 seconds. Worse, it **successfully renamed** a test and left it asserting that sixty cells
is two pages — true only while a page was a fixed thirty. A call site that will not compile is found
immediately; a test that compiles while asserting a rule the app no longer has is found only when it
goes red, or never.

## v4 — a patch I wrote and never ran

`Paths.pending` was called from three places and defined in none. Everything was consistent except
that the definition was not there. The cross-check I run before pushing now looks for members called
on our own objects that do not exist.

## v12, v14 — imports in the order they were added

`animateFloat` is an extension and needs importing by name; so does `border`. Both files had
accumulated their imports in the order I patched them across five or six rounds, which is how one
goes missing without being obvious. Both are sorted now.

## v6, v7 — the delivery gate refused unbounded loops, twice

`while (true)` with a break in the middle. It terminates, but "terminates because of a condition in
the middle" is the shape that stops terminating when somebody edits the middle. Both are counted
loops now, and G5 is run locally before pushing.

## Test arithmetic, twice in two runs

Both the same mistake: counting a silence from when speech happened rather than from when the level
fell again. Every time in those tests is derived from the constant now, and the state machines are
walked in a throwaway Python simulation before pushing. **CI is a four-minute debugger and a red
that means "I cannot add up" teaches nothing.**

---

# PART TWO — THE DECISIONS

What was chosen, what was rejected, and what the rejected option would have cost.

## The main view: thirty lines, then a grid, then a filled page

**v1 built thirty full-width lines**, arguing from `MINIMALIST_STOPWATCH` v17 that a grid of small
squares reads as a keypad while a full-width line reads as a sample list, and that width is what
allows a waveform at 96 buckets instead of 28.

**v2 replaced them with three across**, because on the phone the argument was outweighed: fourteen
rows of thirty visible, four screens of scrolling, in an app whose point is filling cells quickly.
The waveform dropped to 32 buckets, which is exactly what v17 objected to. The argument was not
wrong; it lost.

**v11 made the layout derived.** Two numbers — how many cells, how many pages — and the rest falls
out. Columns are the square root of a page-load capped at three, which keeps thirty looking the way
it did rather than reflowing to six across. Nothing scrolls: a fixed screenful meant a set of twelve
was twelve small tiles and two thirds of a black screen.

## The mode became a visible control

In v1 the mode was implied by whether Play had been pressed, so the difference between a press that
seeks and a press that **records over a take** was a state nothing on screen named. Thirty small
tiles make the target smaller and leave the consequence identical. One toggle, labelled with what it
will do, and flipping it stops whatever the old mode was doing.

## Confirmation before overwriting, and only then

Decided in `Gesture.press` rather than in the screen, so there is no path to a recording that skipped
it. An empty cell is never asked about: **an app that asks every time teaches you to press OK without
reading, and then it is not a confirmation, it is a second tap.**

## Re-recording clears everything derived from the take

Transcript, chosen voice, generated audio. Keeping them is the answer whose wrongness does not appear
until a lot of work has been done: the cell would play a synthetic voice reciting words that are no
longer there, under a title taken from them, and nothing on screen would look wrong.

## The editor edits nothing

Two numbers beside the recording, not a trim. The take is the thing that cannot be made again, and an
editor that cuts is one wrong drag from destroying the head of a phrase with no undo. Since nothing
is cut, playback seeks to the in point and a bounded watcher stops at the out point — `MediaPlayer`
has no concept of stopping before the end of a file, which is exactly why the editor could exist
without touching the audio.

**v14** made the new points apply on the way out: closing the editor while a set is playing used to
leave the old points running, so the change just made was not the change being listened to.

## Loop is a flag, not a button

**v9 put Loop in the menu and started the sound from there**, which is why there was no way to stop
it: the start lived inside a menu belonging to one cell. A control whose off switch is three gestures
from its on switch does not have an off switch.

**v13** made it a flag. The cell carries ∞ on the grid, one press starts it and the next stops it.
Both halves are the same press on the same cell.

The loop is `AudioTrack` in `MODE_STATIC` with `setLoopPoints` — the region goes to the hardware once
and the read pointer wraps, so the join is sample-accurate. `MediaPlayer.setLooping` restarts a
decoder, which is a click and tens of milliseconds of silence. It loops the original recording, and
the screen says so: a Speechify mp3 would need the decoder this approach exists to avoid.

## Normalisation, and the two guards that would be bugs if left out

−0.1 dBFS. Not 0: a sample on the rail may already have been clipped, and resampling or a codec
downstream can push an intersample peak over it.

**The gain is capped at 20 dB.** Dividing by the peak of a nearly silent take is a very large number,
and without a ceiling a recording of an empty room is multiplied into a convincing wall of hiss under
a healthy-looking waveform.

**The quality check runs before normalisation.** Afterwards, room tone and a quiet phrase are
indistinguishable, because both sit at the same peak.

## 48 kHz, and the knock-on that would have been quiet

Sixteen kilohertz was inherited from a stopwatch whose microphone existed to hear the word "start".

The quality check measured **frames**, and the analysis window is a fixed number of samples — so at
48 kHz twenty-five frames is 83 milliseconds instead of 250. The check would have become three times
more permissive the moment the recorder improved, and the symptom would have been coughs accepted as
takes. It measures milliseconds now.

## Transcription is never asked for on the way to a voice

The brief had a Transcribe button; the instruction replaced it. Nobody wants a transcript, they want
a different voice, and the transcript is what the app needs to give them one.

**v11** made it its own action as well, and the title became the whole sentence rather than the first
word. First word was right while transcription was a side effect and a cell just needed something
short to be called. Asked for directly, the words are the point.

Both doors go through one function: two copies of upload-submit-poll would drift, and the drifted one
would be the half used less.

## The voice chooser is built on their vocabulary, not mine

A hand-made taxonomy over eleven hundred voices is a third vocabulary nobody asked for. Facet chips
are built **from** the catalogue, so a value either provider adds appears without anybody editing the
app.

**On "popular", honestly:** Speechify's eight `simba-3.2` seats are its flagship and every other
voice returns 400 for that model, so they are marked. Hume publishes no popularity, rank or featured
field of any kind — every field on all 160 voices was read. Rather than invent a ranking, Hume voices
reach the top by being starred.

## Emotions are a list because a blank page is not a choice

Hume's `description` is an acting direction read as prose, not an enum. That is exactly why there is
a list: a free text box is a blank page, and a blank page in the middle of choosing a voice is where
somebody takes the default. Speechify has no such field, so the section is hidden for it rather than
offering a control that does nothing. And a direction is sent only when it has a value — an empty
description is not neutral, it is a field asking to be interpreted.

## Keys come from a file

The paste box is gone entirely. A text field on a phone is a keyboard, and this app is dictated. The
system picker opens wherever the note already lives.

Deleting a key writes `DELETED` over the token rather than cutting the line, because the parser reads
a key's label from the line above and cutting shifts what the next key thinks its account is called.

## Lowering the cell count hides, it does not delete

Go from 60 back to 30 and cells 31–60 leave the grid with their recordings intact. The storage
figures walk what is on disk rather than counting to the current setting, so hidden recordings keep
being counted and the megabytes are never a mystery.

## Small ones, recorded so they are not undone

- **No control announces a future version.** Seq and Voice printed a promise and took a third of a
  row, and a button that announces a future version has to be pressed to find that out.
- **One × in the top right, no Back buttons.** Two full-width Back buttons on the longer screens was
  two rows of glass spent on leaving.
- **The waveform is continuous.** It was drawn at seven tenths of each slot, which left a black gap
  between every bar — at 32 slices that reads as stripes and at 128 as a grey haze.
- **The wave colour is a setting**, because the transcript is written over it and it was the same
  pale grey as the words.
- **Waveform detail is a setting**, because every bucket is a scan and a rectangle on up to 120 cells
  at once. The editor always takes the finest: there it is one recording.
- **The transcript scrolls only on the cell that is sounding.** Text crawling on twenty-nine silent
  cells is the animation the design language rules out; on the one being listened to it is the thing
  being listened to.
- **Long press opens the menu in play mode too.** It required STOPPED, from when it deleted outright.
  Recording still refuses it — a menu covering the screen while the microphone is open is a recording
  nobody can stop.
- **The triangle does not wrap at the last cell.** Wrapping would overwrite cell 1 while the phone is
  in a pocket, which is the exact condition under which nobody would notice.

---

# PART THREE — THE VERSIONS

| v | What landed |
|---|---|
| 1 | Recorder, player, overlay, projects. Transcription and voices not wired |
| 2 | Icon, live waveform while recording, three across, safe area, REC/PLAY toggle |
| 3 | Settings, play mode single or continuous, normalisation to −0.1 dBFS |
| 4 | Second tap fixed, pending file, app-properties permission door |
| 5 | Cell count and page spread, paging by flipping, no next-page control |
| 6 | Transcription, Speechify and Hume, keys, cell options menu |
| 7 | Overwrite confirmation, wave editor, key ring screen from `Key_Tester` |
| 8 | Drag side picks the handle, settings says which keys are needed |
| 9 | Voice ids fixed, ring walked past exhausted accounts, gapless loop, save to file |
| 10 | 48 kHz mono, red out handle, dead buttons removed |
| 11 | Layout fills the page, steppers, transcribe as its own action, full-sentence titles |
| 12 | Trim closure fixed, waveform detail setting, transcript over the cell, REC in the corner |
| 13 | Loop as a per-cell flag with ∞ on the grid |
| 14 | Voice chooser: 1152 voices, search, facets, stars, emotions, one close button |

---

# PART FOUR — WHAT WAS INHERITED

Forked from `MINIMALIST_STOPWATCH` v19. `Dsp` and `Vu` came across directly. The template matcher and
the command vocabulary did not: this app never listens for a word, and **SpeechRecognizer is not
reached for at all**, on five versions of evidence in the stopwatch that it does not work on this
phone.

`KeyParser`, the provider table and the status classifier are ported from `Key_Tester` line for line
rather than rewritten. The VU meter and the overlay come from `TTT_MINI`. The voice protocols come
from `MAHA_TRANSCRIBE_STREAMLIT` and `MA_READER_SPEECHIFY` — and reading those two earlier would have
saved three releases.
