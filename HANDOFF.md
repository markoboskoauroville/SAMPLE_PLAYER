# HANDOFF — Sample Player

**The finished state of the app. Nothing about how it got here.**

Version 14. Repository public at `markoboskoauroville/SAMPLE_PLAYER`.
Latest artefact: `14-sampleplayer-v14.apk`, tag `v14`.

Every bug, every fix, every feature and every reason is in
[`DEVELOPMENT.md`](DEVELOPMENT.md). This file is for picking the app up, not for understanding how
it was built. If you are changing something and want to know why it is the way it is, that is the
other file, and reading it first will save you undoing a decision twice.

---

## WHAT IT IS

An Android app for recording spoken phrases, transcribing them, replacing the voice, and playing
the set back with a travelling playhead. Baba records lines for films; this holds a set of them.

**The workflow it exists for:** read a script off the phone while the app is in the background,
speak a line, press the triangle overlay, speak the next one. Cells fill one after another without
leaving the app being read from.

## THE MODEL

A **project** is a set of cells. Each cell holds one recording and everything derived from it.

    projects/<id>/samples/NN/original.wav      Baba's recording. NEVER overwritten
    projects/<id>/samples/NN/pending.wav       a take in progress, promoted only if usable
    projects/<id>/samples/NN/gen/<engine>.wav  a generated voice, beside the recording
    projects/<id>/samples/NN/meta.txt          words, chosen voice, in/out points, loop flag

**`original.wav` is protected by the PATH, not by a convention.** Generated audio lives one
directory down in `gen/`, so no engine string and no loop index can make one become the other. This
is the hardest rule in the app: a bug here destroys work that cannot be re-recorded.

## THE SCREENS

**The grid.** Cells, filling the page. Two settings decide the layout: how many cells, and how many
pages to spread them over. One cell on one page is the size of the page; two are halves; thirty are
three across and ten down. Nothing scrolls — pages are flipped sideways and the page number is in
the header line.

**Settings.** Cells and pages as steppers, waveform detail 1x–4x, waveform colour, play mode,
storage, keys, permissions.

**Keys.** Import from a file, test, delete. Ported from `Key_Tester`.

**Cell options** — long press. Edit playback points, transcribe, loop flag, save to a file, change
voice, delete.

**The wave editor.** Two handles on one recording. Yellow is the in point, red is the out point,
and the side you drag from picks which one.

**The voice chooser.** Both catalogues, searchable and faceted.

Every screen except the grid closes with one × in the top right.

## THE TWO MODES

    REC     pressing a cell records into it. Pressing again stops
    PLAY    pressing a cell plays from there, or toggles its loop if it is marked

One toggle in the bottom right corner, solid in the colour of what it will do. **This is the
dangerous line in the app**: one gesture, two meanings, and the mode is the only thing between a
seek and an overwrite. `Gesture.press` is the single place that decides, and recording over a cell
that already holds a take asks first.

## RECORDING

- The best rate the phone gives, probed: 48 kHz, then 44.1, then 16. Mono.
- Press to start, press again to stop. A ninety second silence ceiling sits underneath, so a
  forgotten cell does not record the room.
- Written to `pending.wav`, judged, normalised to **−0.1 dBFS**, then promoted.
- The quality check runs BEFORE normalisation, or room tone and a quiet phrase are indistinguishable.
- The rate is written into the WAV header and read back out; older takes are still 16 kHz.

## THE OVERLAY

One accessibility service holds three windows:

    hairline    one device pixel across the top, not touchable, the level
    triangle    under the camera, touchable, only while recording — advances to the next cell
    status      under the camera, only while generating

The triangle does one thing: stop this cell and start the next. At the last cell it carries the
count and stops rather than wrapping. Stopping altogether is done in the app.

**Permissions, in the order the doors open:** app properties → three dots → Allow restricted
settings, THEN accessibility. The switch is greyed out until the first is done and nothing on the
greyed switch says so.

## VOICES

**AssemblyAI** transcribes; required. **Speechify** and **Hume** are the two engines; either is
enough. The voice is per cell.

- Speechify: 992 voices, five cursor pages, `Authorization: Bearer`.
- Hume: 160 voices, two pages, `X-Hume-Api-Key`, and an account is an **api key + secret pair**.
- The model follows the voice id: `simba-3.2` only for ids ending `_32`, `simba-english` otherwise.
- Hume takes a `description` — an acting direction read as prose. Speechify has no such field.
- Preview says the voice's own name, not the cell's text.

Transcription is never a step asked for on the way to a voice; it happens if it has to.

**Keys** arrive as a pasted note in a text file, parsed by the canonical `KeyParser`. Nothing in
this app can render a key.

## THE FILES

| | |
|---|---|
| `Model.kt` | the whole app as pure state: cells, press rule, layout, trim, paging |
| `Vault.kt` | paths and storage arithmetic |
| `Words.kt` | per-cell transcript, voice, trim, loop flag |
| `Recorder.kt` | `AudioRecord`, WAV, normalisation |
| `Dsp.kt` | waveform, quality check, normalise |
| `Looper.kt` | gapless loop, `AudioTrack` static buffer |
| `Net.kt` | every HTTP request, one User-Agent |
| `Keys.kt`, `KeyRing.kt`, `Providers.kt` | the ring, ported from `Key_Tester` |
| `Catalogue.kt` | both voice catalogues, facets, search |
| `Emotions.kt` | acting directions |
| `Voices.kt`, `Transcribe` | speaking and transcribing |
| `MainActivity.kt` | screens and wiring |
| `Ui.kt`, `Settings.kt`, `SlotOptions.kt`, `WaveEditor.kt`, `KeysScreen.kt`, `VoiceChooser.kt` | screens |

## BUILDING

`appVersion` in `gradle.properties` is one whole number. `versionCode`, `versionName`, the filename
and the tag are all derived from it. Bump it, push, and CI builds, gates and releases. Only the two
newest releases are kept. Every action is pinned by commit SHA. The signing key is a repository
secret and is permanent, so every build installs over the last one.

    python3 scripts/verify.py     144 structural checks
    Test 1                        194 cases, plain JVM, no Android

## WHAT HAS NEVER BEEN PROVEN

Nothing in this app has been tested by anyone but Baba on his own phone. There is no instrumented
test, no emulator run, and no second device. In particular:

- the overlay windows and whether recording survives the app going to the background
- transcription and both voice engines end to end
- the gapless loop's join
- normalisation on real takes
- the file picker and the save-to-file chooser
