# NEXT_DEFAULTS — why each decision was made

Every entry names what was chosen, what was rejected, and what the rejected option would have cost.
Read this before undoing anything in `HANDOFF.md`.

---

# 29.8.2026 — the specification, before any code

## The main view is thirty full-width lines, not a three across grid

**Chosen: thirty full-width lines in a scrolling list.**

The brief asked for thirty tiles, three across and ten down, described as the stopwatch's sample line
"expanded from nine to thirty, same design". The sample line is not a grid. `MINIMALIST_STOPWATCH`
v17 deliberately replaced a three by three pad grid with nine full-width lines, and recorded the
reason: a grid of small squares reads as a keypad, nine full-width lines read as a sample list, and
the width is what allows a waveform at 96 buckets instead of 28.

**Three across at thirty tiles is narrower than the pads v17 rejected.** On a 390px phone it is under
130dp per tile, carrying a title above and a waveform below. That is the same mistake with more of
it.

**What it costs, plainly.** Ten screens of scrolling instead of three, and no view of the whole set
at once. Baba asked for whatever makes a comfortable interface and this is the answer, but if seeing
all thirty at a glance turns out to matter more than the waveform does, the honest fix is a zoomed
overview mode, not a narrower tile.

## Two modes share one gesture, which is the thing to watch

**Chosen: stopped means a press records, playing means a press seeks.**

The stopwatch deleted tap-anywhere at v1 precisely because two contradictory meanings must not share
a surface, and the dangerous half there was destructive. The same objection applies here and it is
accepted anyway, for one reason: **jumping between phrases by pressing them is the feature**, and a
separate seek control for every line would be thirty more controls.

**The mitigation is that the mode is visible and is the Play button.** Not a hidden state, not a long
press, not a gesture nobody can see.

**Said plainly: this is the line in the whole app where a bug destroys work that cannot be
re-recorded.** A press that records while the person believed it was seeking overwrites a take. Test
1 walks both modes against every line state, and a mutation flips the mode check so the sweep proves
something is watching it.

## The playhead travels, and the list jumps rather than scrolls

**Chosen: a thin vertical mark crossing each line's own waveform, moving to the next line when a
sample ends. When the playing line is off screen, the list jumps to it.**

`design-language.md` §8 is explicit that Baba cannot watch animated scrolling and that a direct jump
is taken wherever the platform allows one. A smooth scroll following a playhead down thirty lines is
exactly the animation that rule forbids, and it would be running for the whole length of a playback
rather than for a moment.

**Rejected: pinning the playing line to the top and scrolling continuously.** It removes the sense of
position entirely: every line looks like the first one, and the thing the mark is for — knowing where
you are in the set — is lost.

## The VU meter was removed and put back

**Chosen: the hairline meter stays.**

It was taken out on instruction, on the grounds that the overlay should be a triangle and nothing
else. The argument for putting it back is not tidiness: **with the app in the background there is
nothing else that says audio is arriving.** `recorder.md` §3 states the one lie a level display must
never tell, and `MINIMALIST_STOPWATCH` v16 recorded the matching failure from the other end — a
sampler that keeps two seconds of room tone as the sound of a word looks completely successful. The
slot fills, the count is right, the waveform has a shape, and the only symptom appears later in the
transcription.

Baba agreed on the argument rather than on the request, which is the right way round.

**What it costs:** one device pixel across the top of every screen while recording, and one more
window to manage. `MaRecordingLine` already argues that at that height it intercepts nothing and
sits inside the gesture bar's own margin.

## The overlay is three pieces and each one carries one thing

**Chosen: hairline meter at the top edge, triangle under the camera hole while recording, status line
under the camera hole while generating. Never more than two at once.**

`MaRecordingLine`'s own header records what happened when this was got wrong in TTT mini: it became a
black notch carrying a meter, a red dot, a clock, a bin, a send arrow and a language badge, six
things in a strip stolen from the status bar, and every control on it had a better home already.

So the triangle is not a transport. It advances, and that is all it does. Stopping is done in the
app, by switching to it. **Rejected: adding a stop button beside the triangle**, which is how the
notch grew the first time.

## The triangle stops at 30 and shows the number

**Chosen: at the last slot the triangle carries the number 30 inside it, and pressing it stops rather
than wrapping.**

Wrapping would begin overwriting slot 1 while the phone is in a pocket and the person is reading from
another app, which is the exact condition under which nobody would notice. **Rejected: refusing
silently**, because a control that does nothing is indistinguishable from a control that is broken.
The number inside the triangle is what makes the end visible without counting.

## Everything is a project

**Chosen: the app holds projects, each project is a set of thirty slots, and projects are managed
from settings.**

This arrived after the rest of the specification and it changes the storage layout rather than the
interface: nothing is global except the keys. It is recorded here because a session that reads only
the interface description would build a single global set of thirty and the migration afterwards
would be real work.

## Generate does what it can and reports what it skipped

**Chosen: generate the transcribed samples, report the count skipped.**

**Rejected: refusing the whole batch when one sample is untranscribed.** Thirty samples times six
minutes is not a batch to refuse over one empty slot, and refusing is the app making a decision that
belongs to the person. **Rejected: asking**, because a dialogue in front of a six minute job is one
more thing between the person and the work, and the answer would be the same every time.

## Generated audio lives beside the original, under a different name

**Chosen: `original.wav` and `gen/<engine>.wav`, per slot, per project.**

The rule Baba named as the hardest in the brief is that his recordings are never overwritten. A
filename that differs by content rather than by path is how that rule gets broken by an off-by-one
in a loop. **Rejected: versioning one filename**, which makes "play mine again" a search rather than
a path.

## Re-recording clears the transcript, the title and the generated audio

**Chosen: clearing them.**

**Rejected: keeping them**, and this is the answer whose wrongness does not appear until a lot of
work has been done. A line re-recorded with new words, holding an old transcript and old generated
audio, plays a synthetic voice saying something the person did not say, under a title taken from
words that are no longer there. Nothing about the screen would look wrong.

## English only, and no selector

**Chosen: no language control at all.**

The brief specified an HR/EN choice ported from TTT mini. English only means there is nothing to
choose between, and `design-language.md` §1 says nothing appears or disappears — but a control with
one option is not a dimmed control, it is a control that should not have been built. If Croatian
arrives it is a new decision.

**What it costs:** AssemblyAI's fast path is an allow-list of languages whose output has actually
been read, and for this account that is English only, so English-only and the fast path agree. That
agreement is a coincidence and not a reason.

## Speechify is the eight curated voices

**Chosen: beatrice, dominic, edmund, geffen, harper, hugh, imogen, wyatt.**

**Rejected: the full catalogue.** Twenty-one keys times a paged catalogue is a list nobody chooses
from, and every voice has to be previewable to be a choice at all. The eight are the simba-3.2 set
`MA_READER_SPEECHIFY` already uses.

## The account label comes from the note, not from the URL

**Chosen: the label on the line above each key, which the canonical `KeyParser` already captures.**

The Speechify file's first line is a pasted `https://speechify.ai/?srsltid=…` link, and its 56
character parameter was identified as an account id. It is a Google Shopping click-tracking token.
`secrets.md` §2 and `keyring.md` §4 both name `srsltid` specifically as the reason to extract by
shape rather than by whitespace, alongside the attempt to authenticate with the word *cafeteria*.

**The provenance Baba wanted already exists in the file**: every one of the twenty-one keys has its
own label on the line above it, six to twenty-three characters, and the parser has been carrying them
all along. Displaying an ad tracking string under the word *account* would have been worse than
showing nothing, because it would have been believed.

## Permissions are offered with a link, twice

**Chosen: a direct intent to the correct settings page at first run, and the same again from the
settings screen.**

Accessibility, overlay and microphone each live on a different Android page and two of them are
several levels deep. An app that says "enable accessibility" and stops has handed the person a search
problem. Offering it twice matters because the first run is exactly when somebody says *later*.

## The file manager is a feature, not housekeeping

**Chosen: settings shows filled slots and megabytes per project and in total, and can delete.**

Thirty slots holding an original and up to three generated files is a folder that grows without
anybody watching it. **A phone fills up and the symptom is a recording that fails**, which reads as a
bug in the recorder. Being able to see the size and clear a project from inside the app is what
prevents that, and Baba asked for it directly.
