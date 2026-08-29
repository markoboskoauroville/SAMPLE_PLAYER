#!/usr/bin/env python3
"""
verify.py — the checks that are cheap enough to run on every push, and that a compiler will not
run for you.

Every check PRINTS WHAT IT EXAMINED, not only what it found. delivery-gate.md 14: a check that
finds nothing and a check that runs nothing look identical from outside, and this account has
already had a check report zero findings for a twelve-entry enum because the indentation
differed. A zero here is a failure of the check until proven otherwise, which is why the file
asserts a minimum number of checks and a minimum number of files looked at.

REWRITTEN FOR THIS APP rather than inherited. The stopwatch's version asserted things about
Stopwatch.kt, VoiceEngine.kt and a template matcher, none of which exist here, and a check that
cannot fail is worse than no check at all.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/mantra/sampleplayer"
TEST = ROOT / "app/src/test/java/com/mantra/sampleplayer/SamplePlayerTest.kt"

failures = []
checks_run = []


def code_only(text):
    """
    The file with its comments removed.

    NEVER GREP SOURCE AS PROSE. In the stopwatch this went wrong three times in both directions:
    a comment containing "tap " satisfied a check the code no longer met, a comment explaining
    that the code avoids while(true) failed a gate the code passed, and a comment explaining why
    SpeechRecognizer was removed failed a check asserting it was gone. Three times is not bad
    luck. Every check about what the CODE does goes through here.
    """
    without_block = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(
        line for line in without_block.split("\n") if not line.strip().startswith("//")
    )


def check(name, ok, detail):
    checks_run.append(name)
    print(f"  {'PASS' if ok else 'FAIL'}  {name}: {detail}")
    if not ok:
        failures.append(name)


src = {p.name: p.read_text() for p in sorted(MAIN.glob("*.kt"))}
print(f"source files examined: {len(src)}")
for n in src:
    print(f"  {n}  ({len(src[n].splitlines())} lines)")
if len(src) < 8:
    sys.exit(f"expected at least 8 source files, found {len(src)}: this check is broken, not the code")

print()
print("CHECKS")

model_code = code_only(src["Model.kt"])
vault_code = code_only(src["Vault.kt"])
ui_code = code_only(src["MainActivity.kt"])
overlay_code = code_only(src["Overlay.kt"])
ring_code = code_only(src["KeyRing.kt"])
settings_code = code_only(src["Settings.kt"])
recorder_code = code_only(src["Recorder.kt"])
tile_code = code_only(src["Ui.kt"])
dsp_code = code_only(src["Dsp.kt"])

# ── THE ORIGINAL RECORDING ────────────────────────────────────────────────────────────────────
#
# The hardest rule in the app: a bug here destroys work that cannot be re-recorded. It is enforced
# by the path rather than by a convention, and this asserts nothing else in the tree builds that
# filename for itself.
writers = [
    n for n, t in src.items()
    if n != "Vault.kt" and ("ORIGINAL" in code_only(t) or '"original' in code_only(t))
]
check(
    "only Vault.kt names the original file",
    writers == [],
    f"files naming original.wav outside Vault.kt: {writers or 'none'}",
)
check(
    "generated audio is forced under gen/ and an engine name that is a path is refused",
    '"gen"' in vault_code and "require(" in vault_code,
    "Paths.generated builds a gen/ subdirectory and validates the engine name",
)

# ── THE PRESS RULE ────────────────────────────────────────────────────────────────────────────
#
# One gesture means two things and the mode is the only thing between them. A mode that was right
# for the main view and wrong for the Seq view is what this asserts is impossible.
press_defs = sum(len(re.findall(r"fun press\(", code_only(t))) for t in src.values())
check(
    "there is exactly one press rule",
    press_defs == 1,
    f"definitions of fun press(): {press_defs}",
)
check(
    "the interface routes presses through the rule rather than deciding for itself",
    "Gesture.press(" in ui_code and "Gesture.longPress(" in ui_code,
    "MainActivity calls Gesture.press and Gesture.longPress",
)
playing_branch = re.search(r"Mode\.PLAYING ->(.*?)\n        \}", model_code, re.S)
check(
    "the playing branch cannot start a recording",
    playing_branch is not None and "StartRecording" not in playing_branch.group(1),
    "Mode.PLAYING resolves only to SeekTo or Refused",
)

# ── THE TRIANGLE ──────────────────────────────────────────────────────────────────────────────
check(
    "the triangle stops at the last slot rather than wrapping",
    "if (from >= total - 1) null" in model_code,
    "Advance.next returns null at the end of THIS set, never 0",
)

# ── THIRTY IS WRITTEN ONCE ────────────────────────────────────────────────────────────────────
bare_30 = sum(len(re.findall(r"(?<![\w.])30(?![\w.])", code_only(t))) for t in src.values())
check(
    "the default count is declared once and nowhere hard-coded",
    "const val DEFAULT_SLOTS = 30" in model_code and bare_30 <= 2,
    f"DEFAULT_SLOTS declared once; bare 30s in code: {bare_30}",
)

# ── THE SET IS NOT ALWAYS THIRTY ──────────────────────────────────────────────────────────────
#
# Thirty stopped being a law and became a default. Anything still counting to a constant would be
# right for a thirty-cell project and quietly wrong for every other size.
check(
    "nothing counts to a fixed slot total any more",
    "0 until SLOTS" not in "".join(code_only(t) for t in src.values()),
    "bounds come from the project or from what is on disk",
)
check(
    "the triangle asks the project where the end is",
    "Advance.next(from, project.size)" in ui_code,
    "a fifteen-cell set ends at fifteen, not at thirty",
)
check(
    "a chosen cell count is sanitised against the offered list",
    "in SLOT_CHOICES" in settings_code,
    "a hand-edited preference must not produce a project of minus four slots",
)

# ── PAGING, AND NO CELL SPENT ON IT ───────────────────────────────────────────────────────────
#
# Splitting the last cell into forward and back was considered and rejected in the same breath: a
# set of thirty that spends one on navigation is a set of twenty-nine, and the arrow would sit
# somewhere different on every page. The count goes in the header and the screen is flipped.
check(
    "the page arithmetic is one pure object",
    "object Paging" in model_code,
    "pageCount, slotsOn, pageOf and label, all testable without a screen",
)
check(
    "the pages are flipped rather than navigated by a control",
    "HorizontalPager" in ui_code,
    "no next-page button anywhere",
)
check(
    "the page count is shown in the header",
    "Paging.label(pagerState.currentPage" in ui_code,
    "the number is where the project name and the slot count already are",
)
check(
    "the label is silent when there is only one page",
    "if (pages <= 1)" in model_code,
    "an app that says page 1 of 1 is an app telling you about itself",
)
check(
    "lowering the cell count does not delete anything",
    "deleteRecursively" not in ui_code and "onSlotCount" in ui_code,
    "raising it again brings the recordings back; storage keeps counting the hidden ones",
)

# ── SPEECHRECOGNIZER ──────────────────────────────────────────────────────────────────────────
#
# Five versions of evidence in the stopwatch that it does not work on this phone. Checked against
# stripped code, because the comment explaining its absence contains its name.
recognizer = [n for n, t in src.items() if "SpeechRecognizer" in code_only(t)]
check(
    "SpeechRecognizer is absent from the code",
    recognizer == [],
    f"files using it: {recognizer or 'none'}",
)

# ── THE OVERLAY ───────────────────────────────────────────────────────────────────────────────
check(
    "the hairline is not touchable",
    "FLAG_NOT_TOUCHABLE" in overlay_code,
    "the level strip intercepts nothing, so every touch reaches the app underneath",
)
check(
    "every overlay window is an accessibility overlay",
    overlay_code.count("TYPE_ACCESSIBILITY_OVERLAY") >= 3
    and "TYPE_APPLICATION_OVERLAY" not in overlay_code,
    f"accessibility overlay used {overlay_code.count('TYPE_ACCESSIBILITY_OVERLAY')} times, "
    "application overlay never",
)
check(
    "the foreground service declares the microphone type",
    "FOREGROUND_SERVICE_TYPE_MICROPHONE" in overlay_code,
    "without it Android 14 stops the capture when the app leaves the screen",
)

# ── THE KEY RING ──────────────────────────────────────────────────────────────────────────────
check(
    "429 is not condemned",
    "code == 429 -> Status.LIMITED" in ring_code,
    "a throttled key is a healthy key with a busy minute",
)
check(
    "Cloudflare 1010 is not read as a dead key",
    '"1010" in b' in ring_code,
    "api.hume.ai answers a request with no User-Agent that way on every endpoint",
)
check(
    "the dead list stores fingerprints rather than keys",
    "MessageDigest" in ring_code and "fun fingerprint()" in ring_code,
    "a file of dead keys would be a file of keys",
)

# ── NO KEY MAY BE IN THE SOURCE ───────────────────────────────────────────────────────────────
KEY_SHAPES = re.compile(r"(gsk_|AIza|ghp_|github_pat_|sk-ant-|sk_)[A-Za-z0-9_-]{20,}")
hits = []
for n, t in src.items():
    for m in KEY_SHAPES.finditer(t):
        window = t[max(0, m.start() - 60):m.start() + 60]
        if "repeat(" in window:  # a test fixture, not a key
            continue
        hits.append((n, len(m.group(0))))
check(
    "no key-shaped literal in any source file",
    hits == [],
    f"hits: {hits or 'none'} (file and length only, never the value)",
)

# ── THE APP DRAWS INSIDE THE SAFE AREA ────────────────────────────────────
#
# v1 put the project name under the clock and the action row under the navigation buttons, so the
# widest control in the app was the one hardest to press. Targeting SDK 35 means edge-to-edge is
# not opt-in, so the padding has to be asked for, and it has to come from the system rather than
# from a number somebody typed after looking at one phone.
check(
    "the layout takes the system insets",
    "safeDrawingPadding()" in ui_code,
    "the root column is padded by what the system reports, not by a constant",
)
check(
    "no hard-coded status bar or navigation bar height",
    "24.dp)" not in ui_code or "statusBar" not in ui_code,
    "a phone with the camera hole somewhere else is not ours to guess at",
)

# ── THE GRID ─────────────────────────────────────────────────────
check(
    "the set is three across so all thirty fit on one screen",
    "const val COLUMNS = 3" in ui_code and "GridCells.Fixed(COLUMNS)" in ui_code,
    "ten rows of three, and the column count is written once",
)

# ── THE MODE IS A CONTROL, NOT A SIDE EFFECT ──────────────────────────────
#
# The difference between a press that seeks and a press that records over a take must be named on
# screen. With thirty small tiles the target is smaller and the consequence is unchanged.
check(
    "there is a visible REC/PLAY toggle",
    '"PLAY" else "REC"' in ui_code,
    "the toggle is labelled with what it will do",
)
check(
    "flipping the toggle stops whatever the old mode was doing",
    "Recorder.stop()" in ui_code and "armed =" in ui_code,
    "a recording left running while the app calls itself a player is a press in the wrong branch",
)

# ── THE WAVEFORM FORMS WHILE RECORDING ─────────────────────────────────
check(
    "the recorder publishes the shape as it arrives",
    "val live: StateFlow<FloatArray>" in code_only(src["Recorder.kt"]),
    "a recorder that shows nothing until it stops asks you to talk into a hole",
)
check(
    "the recording tile draws the live shape rather than the file on disk",
    "liveShape" in ui_code,
    "the file does not exist yet while the take is running",
)
check(
    "the live shape is bounded",
    "LIVE_MAX" in code_only(src["Recorder.kt"]),
    "an unbounded array on a ninety second ceiling is a leak with a clock on it",
)

# ── NORMALISATION HAPPENS, AND IN THE RIGHT ORDER ──────────────────────────────
#
# ORDER MATTERS BOTH WAYS ROUND. The quality check must see the take as it was recorded, or a
# quiet-but-usable phrase and a recording of an empty room look identical once both have been
# pulled up to the same peak. And the file must be rewritten before anybody plays it, or thirty
# phrases recorded across a week play back as thirty different volumes.
check(
    "the recorder normalises the finished take",
    "normalise(raw)" in recorder_code and "writeWav(" in recorder_code,
    "read, scale, write, once per take rather than once per playback",
)
if "SampleCheck.assess" in recorder_code and "normalise(raw)" in recorder_code:
    order_ok = recorder_code.index("SampleCheck.assess") < recorder_code.index("normalise(raw)")
else:
    order_ok = False
check(
    "the quality check runs BEFORE normalisation",
    order_ok,
    "otherwise room tone and a quiet phrase are indistinguishable once both are at the same peak",
)
check(
    "the target leaves headroom rather than sitting on the rail",
    "TARGET_PEAK = 0.98855f" in dsp_code,
    "-0.1 dBFS, so nothing downstream has to round in our favour",
)
check(
    "the gain is capped",
    "MAX_GAIN" in dsp_code and "coerceAtMost(maxGain)" in dsp_code,
    "without a ceiling a near-silent room is multiplied into a convincing wall of hiss",
)
check(
    "the take is rewritten through a temporary file",
    ".tmp" in recorder_code and "renameTo" in recorder_code,
    "a process killed mid-rewrite must not leave a corrupt file where the only copy was",
)

# ── PLAY MODE ───────────────────────────────────────────────────────
check(
    "what happens after a sample ends is one pure rule",
    "fun nextInPlayback(" in model_code,
    "the mode is an argument, so Test 1 walks both without a preferences file",
)
check(
    "the player asks that rule rather than deciding for itself",
    "nextInPlayback(project, slot, mode)" in ui_code,
    "MainActivity does not carry its own copy of the running order",
)
check(
    "continuous is the default",
    "getOrDefault(PlayMode.CONTINUOUS)" in code_only(src["Settings.kt"]),
    "a missing or corrupt preference plays the set, which is what the app is for",
)

# ── SETTINGS ────────────────────────────────────────────────────────
check(
    "settings is a screen and takes the insets too",
    "safeDrawingPadding()" in settings_code,
    "the second screen must not repeat the mistake the first one made",
)
# The clumsy version of this check failed while the code was correct, by testing a phrase in a
# string literal instead of a behaviour. It now asserts the thing that matters: settings never
# names the original recording and never deletes a tree.
check(
    "settings cannot reach an original recording",
    "Paths.original" not in settings_code and "deleteRecursively" not in settings_code,
    "the only delete offered here goes through Vault.clearGenerated, which walks gen/ alone",
)

# ── THE GESTURE READS LIVE STATE, NOT A PHOTOGRAPH OF IT ───────────────────────
#
# v3 shipped with the second tap doing nothing. `pointerInput` restarts its block only when its
# key changes, so keyed on the slot number it held the callback from the first composition for
# ever — the one that captured mode = STOPPED. Tapping a recording tile asked to START a
# recording, the recorder ignored it because one was running, and nothing happened at all. The
# state was right the whole time; the gesture was reading a photograph of it.
check(
    "the tile's gesture calls the current callback",
    "rememberUpdatedState(onPress)" in tile_code,
    "a live handle rather than a closure captured at first composition",
)
check(
    "the tile's gesture block is not keyed on something that never changes with state",
    "pointerInput(slot.index)" not in tile_code,
    "keying on the slot number is what froze the callback",
)

# ── A RETAKE CANNOT DESTROY WHAT IT FAILED TO REPLACE ─────────────────────────
check(
    "a take is recorded to a pending file",
    "Paths.pending(" in ui_code and "promoteTo" in ui_code,
    "the recording becomes the original only after it has been judged usable",
)
check(
    "the recorder promotes rather than writing over the original",
    "promoteTo" in recorder_code and "renameTo(promoteTo)" in recorder_code,
    "the swap is the last thing that happens, not the first",
)
check(
    "a retake clears the audio generated from the take it replaced",
    '"gen"' in ui_code,
    "otherwise a voice says words that are no longer in the slot",
)

# ── THE PERMISSION DOORS ARE IN THE ORDER THEY OPEN ──────────────────────────
check(
    "the app's own settings page is offered as well",
    "ACTION_APPLICATION_DETAILS_SETTINGS" in ui_code,
    "an accessibility service from an APK is a restricted setting until unlocked from that page",
)
if "Open app properties" in settings_code and "Open accessibility settings" in settings_code:
    door_order = settings_code.index("Open app properties") < settings_code.index(
        "Open accessibility settings"
    )
else:
    door_order = False
check(
    "app properties comes before accessibility",
    door_order,
    "sending somebody to the second door first is sending them to a locked one",
)

# ── THE NETWORK ───────────────────────────────────────────────────
net_code = code_only(src["Net.kt"])
voices_code = code_only(src["Voices.kt"])
keys_code = code_only(src["Keys.kt"])

check(
    "the User-Agent is set in exactly one place",
    net_code.count('setRequestProperty("User-Agent"') == 2
    and "User-Agent" not in voices_code,
    "measured: 21 of 21 Hume pairs return 403/1010 without one, and 21 of 21 work with any",
)
check(
    "no engine opens its own connection",
    "HttpURLConnection" not in voices_code and "URL(" not in voices_code,
    "every request goes through Net, which is why the UA cannot be forgotten in one of them",
)
check(
    "AssemblyAI is sent the raw key with no Bearer",
    '"authorization" to credential.key' in voices_code,
    "a 401 on a good-looking AssemblyAI key is almost always a Bearer that should not be there",
)
check(
    "one key is held for a whole transcription job",
    "ring.current()" in voices_code and voices_code.count("val credential = ring.current()") == 1,
    "an upload belongs to the account that made it; rotating mid-job condemns good accounts",
)
check(
    "429 rests the account rather than condemning it",
    "Status.LIMITED -> ring.rest" in voices_code or "ring.rest(c," in voices_code,
    "a throttled key is a healthy key with a busy minute",
)
check(
    "the model is stored beside the voice",
    "val model: String" in code_only(src["Voices.kt"]),
    "simba-3.2 returns 400 for any voice outside the curated eight",
)
# Written first as `Engines.quote(text)`, which is how a caller outside the object would spell it
# and not how the object calls itself. The check was wrong and the code was right — the failure
# mode delivery-gate.md 14 names, caught here only because a red gate is read rather than trusted.
speak_body = voices_code[voices_code.index("fun speak("):] if "fun speak(" in voices_code else ""
raw_text_in_body = re.findall(r"\$\{?text\}?", speak_body.split("fun quote(")[0])
check(
    "text bound for an engine is escaped",
    "fun quote(" in voices_code and speak_body.count("quote(text)") == 2 and not raw_text_in_body,
    f"both engines send quote(text); raw interpolations of the transcript: {len(raw_text_in_body)}",
)

# ── KEYS ──────────────────────────────────────────────────────────
check(
    "importing keys appends rather than replaces",
    "appendText" in keys_code,
    "pasting the Speechify note must not drop the Hume accounts imported last week",
)
check(
    "no screen can render a key",
    "masked" not in settings_code and "it.key" not in settings_code,
    "the summary is provider, count and account label; there is no path that shows a value",
)
check(
    "the import goes through the canonical parser",
    "KeyParser.extract" in keys_code,
    "the note is a working note with prose and URLs in it, and the parser was written for that",
)

# ── TRANSCRIPTION IS NOT A STEP ─────────────────────────────────────
#
# The brief had a Transcribe button and the instruction replaced it. Nobody wants a transcript;
# they want a different voice, and the transcript is what the app needs in order to give them one.
check(
    "there is no Transcribe control anywhere",
    '"Transcribe"' not in ui_code and '"Transcribe"' not in settings_code,
    "it happens on the way to a voice, with a line saying so",
)
check(
    "choosing an engine transcribes first if it has to",
    "Transcribe.of(" in ui_code and "onEngine" in ui_code,
    "the cell is transcribed on the path to the voice list",
)

# ── A LONG PRESS OPENS OPTIONS, IT DOES NOT DESTROY ──────────────────────────
options_code = code_only(src["SlotOptions.kt"])
check(
    "a long press opens a menu",
    "optionsFor = p.slot" in ui_code,
    "the gesture that deletes must not be the gesture that opens options",
)
check(
    "delete is a labelled control with a warning beside it",
    "Delete this recording" in options_code and "no undo" in options_code,
    "destroying a take should take reading a word",
)
check(
    "the voice is chosen per cell",
    "setVoice(project.id, openSlot" in ui_code,
    "changing cell four must not silently change the other twenty-nine",
)
check(
    "there is a way back to the original recording",
    "onRevert" in ui_code and "Play my own recording again" in options_code,
    "the recording was never replaced, so this only stops pointing at the generated file",
)

# ── NOTHING TESTED MAY BE UNREACHABLE ─────────────────────────────────────────
#
# v1 shipped with a ported state machine that nothing called, and a suite of green tests proving
# it worked. A test of unreachable code is worse than no test: it reports confidence about a
# path the app never takes. Every class Test 1 exercises must be reached from the app itself.
check(
    "the silence ceiling the tests exercise is the one the recorder uses",
    "Ceiling()" in recorder_code,
    "Recorder constructs Ceiling rather than repeating its logic inline",
)
check(
    "the recorder does not carry its own copy of the ceiling",
    "quietSince" not in recorder_code,
    "the rule exists in exactly one place",
)

# ── TEST 1 WALKS THE DANGEROUS RULES ──────────────────────────────────────────────────────────
tests = TEST.read_text()
cases = len(re.findall(r"@Test", tests))
print(f"\ntest cases declared: {cases}")
check("Test 1 is large enough to be a suite", cases >= 100, f"{cases} cases")
for required in [
    "playing an empty slot refuses rather than falling through to recording",
    "no engine name can make a generated path equal the original",
    "the triangle does not wrap at the last slot",
    "429 leaves the key alive",
    "clearing generated audio leaves every original untouched",
]:
    check(f"Test 1 covers: {required[:46]}", required in tests, "present")

print()
print(f"checks run: {len(checks_run)}   failures: {len(failures)}")
if len(checks_run) < 72:
    sys.exit(f"only {len(checks_run)} checks ran: this file is broken, not the code")
if failures:
    sys.exit("failed: " + ", ".join(failures))
print("all structural checks passed")
