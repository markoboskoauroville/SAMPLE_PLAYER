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
catalogue_code = code_only(src["Catalogue.kt"])
chooser_code = code_only(src["VoiceChooser.kt"])
card_code = code_only(src["VoiceCard.kt"])
emotions_code = code_only(src["Emotions.kt"])

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
    "a chosen cell count is clamped to the range",
    "coerceIn(MIN_SLOTS, MAX_SLOTS)" in settings_code,
    "the four fixed sizes became a free number, so the guard became a clamp",
)
check(
    "the page spread can never exceed the number of cells",
    "coerceIn(1, slotCount)" in settings_code,
    "more pages than cells is a set with an empty screen in the middle of it",
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
# THE CELLS FILL THE PAGE. A fixed screenful of thirty meant a set of twelve showed twelve small
# tiles and two thirds of a black screen, which is a phone being wasted.
check(
    "the layout is derived, not fixed",
    "object Grid" in model_code and "fun of(total: Int, pages: Int)" in model_code,
    "how many cells and how many pages decide the rest",
)
check(
    "a page fills its space rather than scrolling",
    "LazyVerticalGrid" not in ui_code and "layout.rows" in ui_code and "weight(1f)" in ui_code,
    "the row count is known before anything is drawn, so each row takes an equal share",
)
check(
    "three across is a ceiling, not a constant",
    "MAX_COLUMNS" in model_code and "coerceIn(1, MAX_COLUMNS)" in model_code,
    "one cell is one column, four are a two by two, thirty stay three by ten",
)
check(
    "a gap on the last row keeps its share of the width",
    "Spacer(Modifier.weight(1f))" in ui_code,
    "otherwise the cells beside it stretch and stop matching every other cell",
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
    'tabSlot = p.slot' in ui_code and 'go("cell")' in ui_code,
    "the gesture that deletes must not be the gesture that opens options",
)
check(
    "there is one switch for the three screens rather than three booleans",
    'var tab by remember { mutableStateOf<String?>(null) }' in ui_code
    and "showSettings" not in ui_code and "optionsFor" not in ui_code,
    "three booleans can all be true, and the day two are, the screen underneath has no way out",
)
check(
    "all three screens carry the same tab row",
    all("Tabs(" in code_only(src[f])
        for f in ["SlotOptions.kt", "Settings.kt", "KeysScreen.kt"]),
    "whichever was asked for opens first and the other two are one press away",
)
tabs_code = code_only(src["Ui.kt"])
check(
    "the tab row is in one place",
    tabs_code.count("fun Tabs(") == 1,
    "three copies would drift apart the first time one was restyled",
)
roles_code = code_only(src["Roles.kt"])
check(
    "the role is read from the name",
    "object Roles" in roles_code and "fun tagFor(" in roles_code,
    "Hume publishes four tags and none is a role, but the NAME carries one on 78 of 160",
)
check(
    "a name with no role word gets no role",
    "return null" in roles_code,
    "a blank is a fact; a guess is not",
)
check(
    "the role reaches the facets as a tag",
    "Roles.tagFor(name)" in catalogue_code and "ROLE" in catalogue_code,
    "the chips, counts and search already work on facet:value strings",
)
check(
    "the card carries a glyph and a copy",
    "Spec.initials(" in card_code and "onCopy()" in card_code,
    "every card has something in the same place, and the copy is the whole specification",
)
check(
    "the specification is measured rather than remembered",
    "api.sws.speechify.com" in roles_code and "E0300" in roles_code,
    "otherwise the chat it is pasted into researches it all again",
)
check(
    "the voice browser is its own screen with no cell behind it",
    "fun VoiceBrowser(" in code_only(src["VoiceBrowser.kt"])
    and "canUse: Boolean" in code_only(src["VoiceCard.kt"]),
    "deciding who reads a part happens before there is a take to read it over",
)
check(
    "every row in the browser carries its own play",
    "onPreview(v)" in code_only(src["VoiceBrowser.kt"]),
    "opening a card per candidate is why nobody auditions",
)
check(
    "the browser chips carry counts computed against the other filters",
    "pool.count { Facets.has(it, facet, value) }" in code_only(src["VoiceBrowser.kt"]),
    "a chip that would leave nothing says 0 before it is pressed rather than after",
)
check(
    "the keyring is always last",
    tabs_code.index('onTab("cell")') < tabs_code.index('onTab("voices")')
    < tabs_code.index('onTab("settings")')
    < tabs_code.index('onTab("keys")'),
    "a row whose contents move is a row that has to be read every time",
)
check(
    "the cell arrows stop at the ends rather than wrapping",
    "at in 0 until slotCountNow()" in ui_code,
    "cell 1 arriving after the last one is a surprise, and the set has an order",
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

# ── RECORDING OVER A TAKE ASKS FIRST ───────────────────────────────────
editor_code = code_only(src["WaveEditor.kt"])
keysscreen_code = code_only(src["KeysScreen.kt"])
providers_code = code_only(src["Providers.kt"])

check(
    "the confirmation is decided in the rule, not in the screen",
    "ConfirmOverwrite" in model_code and "hasOriginal" in model_code,
    "there is no path to a recording that skipped it",
)
check(
    "the interface honours the confirmation",
    "is Press.ConfirmOverwrite ->" in ui_code and "ConfirmBar(" in ui_code,
    "OK or Cancel, and only when a take would be destroyed",
)

# ── THE EDITOR EDITS NOTHING ───────────────────────────────────────
#
# Two numbers beside the recording. The take is the thing that cannot be made again, and an editor
# that cuts is one wrong drag from destroying the head of a phrase with no undo.
check(
    "the editor never writes audio",
    "writeWav" not in editor_code and "delete()" not in editor_code,
    "it moves two numbers and nothing else",
)
check(
    "the points are clamped in one place",
    "fun withIn(" in model_code and "fun withOut(" in model_code
    and "coerceIn" in model_code,
    "a rule enforced at one edge is a rule with a way round it",
)
# THE POINTS ARE APPLIED BY CUTTING THE REGION, NOT BY SEEKING.
#
# MediaPlayer.seekTo is asynchronous: the seek had not landed when start() returned, so playback
# began at zero, and while it settled isPlaying reported false, which made the out-point watcher
# decide playback had finished. One asynchronous call broke both ends of the trim at once, and
# silently, because a cell playing start to finish is what an untrimmed cell does.
player_code = code_only(src["Player.kt"])
check(
    "playback cuts the region rather than seeking into the file",
    "copyOfRange(from, to)" in player_code and "seekTo" not in player_code,
    "no seek to be late, no deadline to watch, and boundaries at the sample",
)
check(
    "cell playback goes through the PCM player",
    "Player.play(" in ui_code and "player = MediaPlayer()" not in ui_code.split("playFile")[0],
    "MediaPlayer is left only for preview clips, which are mp3 and need a decoder",
)
check(
    "the out-point watcher is gone",
    "stopAt(" not in ui_code,
    "AudioTrack in MODE_STATIC stops at the last sample of its own accord",
)
check(
    "the playhead measures the region being heard",
    "Player.fraction()" in ui_code,
    "a fraction of the whole file would put the mark on a slice of the middle",
)
# ZOOM REPLACED "WHOLE TAKE". Putting both points back to the ends is one drag to do by hand and
# is not what you want mid-trim; half a second of breath on a four second take is thirty pixels,
# which is not something a finger can place.
check(
    "the editor can zoom to the region",
    "Zoom to in / out" in editor_code and "Zoom out" in editor_code,
    "one press in, one press back out",
)
check(
    "the zoom window is frozen rather than derived from the points",
    "var window by remember" in editor_code,
    "deriving it live is circular: dragging the in point would move the window under the finger",
)
check(
    "the zoom leaves a margin",
    "margin" in editor_code,
    "otherwise the handles land on the edges with nowhere to drag outwards from",
)

# ── KEYS COME FROM A FILE ────────────────────────────────────────
check(
    "keys are imported from a file, not typed",
    "OpenDocument()" in ui_code,
    "a text field on a phone is a keyboard, and this app is dictated",
)
# The paste box for KEYS is still gone; the one text field in the app is the voice search, which
# is a filter over a list rather than a way to enter a secret.
check(
    "the only text field is the voice search",
    "BasicTextField" not in settings_code and "BasicTextField" not in keysscreen_code,
    "keys come from a file; a search box is a filter, not a way to type a secret",
)
check(
    "the key screen can test and delete",
    "onTest" in keysscreen_code and "onDelete" in keysscreen_code
    and "onTestAll" in keysscreen_code,
    "the same functions Key_Tester has, on the same rows",
)
check(
    "deleting a key keeps the shape of the note",
    'replace(key, "DELETED")' in keys_code,
    "cutting a line out shifts what the next key thinks its account is called",
)
check(
    "a throttled key is never coloured as dead",
    "Status.LIMITED -> PLAY_AMBER" in keysscreen_code,
    "a ring that buries busy accounts eats itself in an afternoon",
)
check(
    "the provider table is the ported one",
    "object Providers" in providers_code and providers_code.count("Provider(") >= 10,
    "every row is a URL somebody has already been wrong about once",
)
check(
    "Hume is tested as a pair",
    "oauth2-cc/token" in providers_code and "NO_WRAP" in providers_code,
    "testing the api key alone proves nothing about the secret; the default Base64 wraps at 76",
)

# ── THE EDITOR HANDLE IS CHOSEN BY THE SIDE, NOT BY THE DISTANCE ──────────────────
#
# v7 grabbed whichever point was nearer the finger. Once the two points are close together the same
# gesture takes a different end depending on a few pixels, and once one point is past the middle it
# answers to the wrong side of the screen. Left half is the start, right half is the stop, decided
# when the drag begins and held for its whole length.
check(
    "the side the drag starts on picks the handle",
    "onDragStart" in editor_code and "start.x < width / 2f" in editor_code,
    "left half moves the start, right half moves the stop",
)
check(
    "nothing chooses a handle by distance any more",
    "abs(ms - trim.inMs)" not in editor_code,
    "the handle must not change under the finger halfway through a drag",
)
check(
    "the rule is written down where the help lives",
    "LEFT half" in editor_code and "RIGHT half" in editor_code,
    "a gesture nobody is told about is a gesture nobody uses",
)

# ── WHAT THE APP NEEDS IS SAID WHERE IT CAN BE FIXED ─────────────────────────
check(
    "the needed keys are one pure list",
    "object Needs" in model_code and "fun blocked(" in model_code,
    "so both screens say the same thing and Test 1 can walk every combination",
)
check(
    "settings says which keys the app wants",
    "Needs.lines(keysHeld)" in settings_code,
    "a list of what was imported never says what is missing",
)
check(
    "the key screen says it too",
    "Needs.lines(" in keysscreen_code,
    "that is the screen somebody is standing on when they can do something about it",
)

# ── THE VOICE BUG, AND THE SHAPE THAT HID IT ──────────────────────────────
looper_code = code_only(src["Looper.kt"])

# THE WHOLE CATALOGUE NOW, not eight known names. 992 Speechify voices across five cursor pages
# and 160 from Hume across two, parsed with org.json rather than by reading substrings — which is
# how v6 shipped a voice list with no gender, no age and no accent on any of it.
check(
    "both catalogues are walked to the end",
    "next_cursor" in catalogue_code and "total_pages" in catalogue_code,
    "one Speechify call returns fifty names beginning with A and looks like the whole thing",
)
check(
    "the catalogue is parsed rather than scraped out of the body",
    "JSONObject" in catalogue_code and "Net.str(" not in catalogue_code,
    "the tag block is nested arrays and a flat string reader cannot see it",
)
check(
    "the model still follows the suffix",
    'endsWith("_32")' in voices_code,
    "simba-3.2 answers 400 for almost the whole catalogue",
)
check(
    "the model is derived from the id",
    "fun modelFor(" in voices_code and "endsWith(\"_32\")" in voices_code,
    "simba-3.2 returns 400 for almost the whole catalogue",
)
check(
    "a catalogue failure says why",
    "Pair<List<VoiceInfo>, String>" in catalogue_code and "Providers.explain" in catalogue_code,
    "an empty list for every possible cause is a message that cannot be acted on",
)
check(
    "speak walks the ring rather than giving up on one refusal",
    "for (unused in 0 until ring.size" in voices_code,
    "three of this ring's twenty-one Hume accounts are out of credit and the first one is first",
)

# ── LOOPING ────────────────────────────────────────────────────
check(
    "the loop is a static buffer, not a restarting player",
    "MODE_STATIC" in looper_code and "setLoopPoints" in looper_code,
    "MediaPlayer.setLooping restarts a decoder, which is a click and a gap",
)
check(
    "the loop obeys the playback points",
    "trim.inMs" in looper_code and "trim.endOf" in looper_code,
    "looping a whole take loops the breath at the front and the click at the end",
)
check(
    "the loop cannot outlive the screen",
    "Looper.stop()" in ui_code,
    "an AudioTrack left playing after onDestroy is a sound with nothing to stop it",
)
check(
    "a loop that cannot start says so",
    "\"nothing to loop\"" in looper_code and "\"too short to loop\"" in looper_code,
    "a loop button that does nothing is indistinguishable from a broken one",
)

# ── SAVING A TAKE ─────────────────────────────────────────────
check(
    "saving goes through the system chooser",
    "CreateDocument(" in ui_code,
    "wherever the rest of the work already lives, and no storage permission asked for",
)
check(
    "what is exported is the original",
    "pendingSave = Paths.original(" in ui_code,
    "the playback points are this app's opinion; the file is the take",
)

# ── THE RATE FOLLOWS THE PHONE AND THE FILE ──────────────────────────────
check(
    "the recorder asks the phone for a rate rather than assuming one",
    "fun bestRate()" in recorder_code and "getMinBufferSize" in recorder_code,
    "48k, then 44.1k, then 16k, mono, probed because a refusal records silence",
)
check(
    "the rate is written into the file and read back out of it",
    "fun rateOf(" in recorder_code and "writeWavHeader(raf, dataBytes: Long, sr: Int)" not in recorder_code,
    "older 16k takes must still report their own length",
)
check(
    "nothing computes a duration from a hard-coded rate",
    "/ Dsp.SAMPLE_RATE" not in recorder_code,
    "a length at the wrong rate is wrong by a factor of three with nothing on screen to say so",
)
check(
    "the loop uses the rate of the file it loaded",
    "Recorder.rateOf(wav)" in looper_code,
    "a loop built at the wrong rate plays at the wrong pitch",
)
check(
    "the quality check measures speech in time, not in frames",
    "MIN_SPEECH_MS" in dsp_code and "MIN_FRAMES" not in dsp_code,
    "a fixed frame count gets three times more permissive at 48 kHz",
)

# ── THE EDITOR HAS TWO HANDLES AND THEY LOOK DIFFERENT ──────────────────────
#
# Both were amber and three pixels wide, so with the out point at its default the red line sat
# under the one-pixel border at the right edge and could not be seen. It was there and it was
# working, and there was nothing on screen saying so, which is the same as missing.
check(
    "the out handle is a different colour from the in handle",
    "drawRect(RECORDING_RED, Offset(outAt" in editor_code
    and "drawRect(PLAY_AMBER, Offset(inAt" in editor_code,
    "yellow in, red out",
)
check(
    "neither handle can hide under an edge",
    "coerceIn(0f, size.width - hw)" in editor_code,
    "both are pulled inside the box",
)
check(
    "the playhead runs between the points, not across the box",
    "inX + playhead.coerceIn(0f, 1f) * (outX - inX)" in editor_code,
    "the player reports a fraction OF THE REGION; drawn across the width it disagreed with the sound",
)
check(
    "a drag is read through the window",
    "from + ((it.position.x / width) * span)" in editor_code,
    "so a drag means the same thing zoomed in as zoomed out",
)
check(
    "the colours are named on the screen",
    "yellow = in" in editor_code and "red = out" in editor_code,
    "a colour that has to be guessed is a colour nobody reads",
)

# ── NO CONTROL PROMISES A FUTURE VERSION ───────────────────────────────
check(
    "the dead buttons are gone",
    "arrives later" not in ui_code and "arrives with v" not in ui_code,
    "a button that announces a future version has to be pressed to find that out",
)
# ONE CLOSE BUTTON, TOP RIGHT, EVERYWHERE. Two full-width Back buttons on the longer screens was
# two rows of glass spent on leaving.
check(
    "no screen carries a Back button any more",
    'Button("Back"' not in "".join(code_only(t) for t in src.values()),
    "an X in the corner is where every other app on the phone puts it",
)
check(
    "every screen that is not the grid has the same header",
    all(
        "ScreenHeader(" in code_only(src[f])
        for f in ["Settings.kt", "KeysScreen.kt", "SlotOptions.kt", "WaveEditor.kt", "VoiceChooser.kt"]
    ),
    "always in the same place whatever the screen is",
)

# ── TRANSCRIBE IS AN ACTION AND ALSO A STEP, THROUGH ONE PATH ──────────────────
check(
    "transcription lives in one function",
    ui_code.count("Transcribe.of(") == 1 and "fun transcribeSlot(" in ui_code,
    "two copies of upload-submit-poll would drift, and the used-less half would be the drifted one",
)
check(
    "a cell can be transcribed on its own",
    "onTranscribe" in ui_code and '"Transcribe"' in options_code,
    "asking for the words is a different thing from asking for a voice",
)

# ── NO SCREEN REPEATS ITSELF ──────────────────────────────────────
#
# The cell menu shipped with Transcribe, Loop and Save on it TWICE, for three versions. A patch
# sliced a block out with its start index AFTER its end index; Python returns "" for that, and the
# surrounding concatenation then duplicates everything between them. Nothing complained: it
# compiled, it ran, and both copies worked.
for screen in ["SlotOptions.kt", "Settings.kt", "KeysScreen.kt", "WaveEditor.kt", "VoiceCard.kt"]:
    body = code_only(src[screen])
    labels = re.findall(r'Button\(\s*(?:label = )?"([^"]{3,})"', body)
    repeated = sorted({lbl for lbl in labels if labels.count(lbl) > 1})
    check(
        screen + " has no button twice",
        not repeated,
        str(len(labels)) + " buttons, repeated: " + str(repeated or "none"),
    )

# ── CONTROLS FIRST, HELP AT THE BOTTOM ────────────────────────────
#
# Two or three lines of explanation under every button is useful once, noise the second time, and
# by the tenth time it is a screen four times taller than it needs to be with the control you want
# somewhere in the middle of it.
# EVERY SCREEN, not just the cell menu. Two or three lines under every control is useful once,
# noise the second time, and by the fiftieth time it is a screen three times taller than it needs to
# be with the thing you came for somewhere in the middle of it.
for screen in ["SlotOptions.kt", "Settings.kt", "KeysScreen.kt", "WaveEditor.kt", "VoiceCard.kt"]:
    body = code_only(src[screen])
    check(
        screen + " keeps its help in one block",
        "WHAT THESE DO" in body and body.count("Help(") >= 3,
        str(body.count("Help(")) + " entries, all at the bottom",
    )
check(
    "there is one Help composable, not one per screen",
    sum(1 for t in src.values() if "fun Help(" in code_only(t)) == 1,
    "five copies would drift apart the first time one was restyled",
)
check(
    "the version is on the settings screen",
    "Settings   v$version" in settings_code,
    "the first question about any bug is which build it happened on",
)
check(
    "the loop control is one glyph",
    "Loop is on for this cell" not in options_code,
    "the symbol says in one character what six words were saying",
)
check(
    "the title is what was said, in full",
    "val said = words.trim()" in model_code,
    "a cell called Danas says which cell; Danas je lijep dan says what is in it",
)

# ── THE EDITOR'S GESTURE READS LIVE STATE ───────────────────────────────
#
# The in and out points were undoing each other, and it is the same bug as v3's second tap:
# pointerInput restarts only when its key changes, so the drag handler held the trim as it was
# when the editor opened. Each drag was computed from that photograph and handed back a whole new
# pair, throwing away the other point.
check(
    "the editor's drag reads the current trim",
    "rememberUpdatedState(trim)" in editor_code and "rememberUpdatedState(onTrim)" in editor_code,
    "a move of one point is computed from the current pair, never a remembered one",
)
check(
    "the drag body cannot see the stale parameter",
    "Trim.withIn(live," in editor_code and "Trim.withIn(trim," not in editor_code,
    "both branches go through the live handle",
)

# ── WAVEFORM DETAIL IS A SETTING ───────────────────────────────────
check(
    "the bucket count is derived from the setting",
    "fun waveformBuckets(" in model_code and "waveformBuckets(scale)" in ui_code,
    "1x to 4x, clamped, because four times the detail is four times the work per page",
)
check(
    "the editor takes the finest, because it draws one recording",
    "WAVEFORM_SCALES.last()" in ui_code,
    "the cost that made this a setting is thirty cells at once",
)
check(
    "the scale is a multiplier, not an index",
    "coerceIn(WAVEFORM_SCALES.first(), WAVEFORM_SCALES.last())" in model_code,
    "a stored 4 meant 4x while the list had four entries and would mean 128x now",
)

# ── THE CELL CARRIES ITS OWN SENTENCE ───────────────────────────────
check(
    "a transcribed cell shows its words over the wave",
    "slot.words.isNotBlank()" in tile_code and "clipToBounds" in tile_code,
    "at three across the number and two words are all a title fits",
)
check(
    "the words scroll only while that cell is sounding",
    "playhead != null && over > 0" in tile_code,
    "text crawling on twenty-nine silent cells is the animation the design language rules out",
)

# ── A LONG PRESS OPENS THE MENU IN EITHER MODE ─────────────────────────
check(
    "only recording refuses a long press",
    "mode == Mode.RECORDING -> Press.Refused" in model_code
    and "nothing in that cell yet" not in model_code,
    "an empty cell opens too, because the page holds the line as well as the recording",
)
check(
    "the line on the cell page is editable",
    "BasicTextField" in options_code and "onWords" in options_code,
    "a transcript with one wrong word used to mean recording the take again",
)
check(
    "a typed line is cleaned the same way as an opened file",
    "Text.forSpeaking(text)" in ui_code,
    "both arrive at an engine in the same shape",
)

# ── THE CONTROLS TAKE A CORNER ────────────────────────────────────
check(
    "REC no longer takes the whole row",
    "weight(0.32f)" in ui_code,
    "a lot of glass for a button pressed once per take, on a screen for showing cells",
)

# ── LOOP IS A FLAG, AND THE STOP IS WHERE THE START WAS ──────────────────────
#
# v9 put Loop in the menu and started the sound from there, so the only way to stop it was to find
# that exact cell again and open the same menu. Marking the cell puts both halves on the grid.
check(
    "the press rule knows about looping",
    "ToggleLoop" in model_code and "project.slot(slot).loop" in model_code,
    "a marked cell loops instead of seeking, and neither can record",
)
check(
    "the menu sets a flag rather than starting a sound",
    "setLoop(project.id, openSlot, now)" in ui_code,
    "the start and the stop are the same press on the same cell",
)
check(
    "the flag is stored beside the cell",
    "fun setLoop(" in code_only(src["Words.kt"]) and "fun loops(" in code_only(src["Words.kt"]),
    "it survives leaving the screen, which an in-memory flag would not",
)
check(
    "a marked cell says so on the grid",
    "slot.loop" in tile_code and "u221e" in tile_code,
    "the mark is where the press will happen",
)
check(
    "the mark is redrawn when the loop starts or stops",
    "loopingSlot" in ui_code,
    "Looper.slot is a plain field and nothing would recompose on it",
)

# ── THE VOICE CHOOSER ───────────────────────────────────────────
check(
    "search and filtering are pure",
    "object VoiceSearch" in catalogue_code and "fun apply(" in catalogue_code,
    "1152 voices is the part that has to be right, and Test 1 can walk all of it",
)
check(
    "the facet values come from the catalogue rather than a hard-coded list",
    "fun values(voices: List<VoiceInfo>" in catalogue_code,
    "a value either provider adds appears without anybody editing this app",
)
check(
    "male and female are different colours",
    "private val MALE" in chooser_code and "private val FEMALE" in chooser_code,
    "at this size a glyph has to be read and a colour does not",
)
check(
    "starred voices sort above everything",
    "compareByDescending<VoiceInfo> { key(it) in starred }" in catalogue_code,
    "a star is the only signal that came from this person rather than from a provider",
)
check(
    "the preview says the voice's own name",
    'This is ${card.name}.' in ui_code and "Emotions.previewLine(" in ui_code,
    "auditioning ten voices on a long line was ten long waits and ten times the credit",
)
check(
    "both previews go through one function",
    ui_code.count("private fun hearVoice(") == 1 and ui_code.count("hearVoice(card,") == 2,
    "two copies would drift and the one used less would be the drifted one",
)
check(
    "acting directions are offered for Hume and not for Speechify",
    "fun availableFor(" in emotions_code
    and "Emotions.availableFor(voice.engine)" in card_code,
    "Speechify has no description field; offering the control would be a lie",
)
check(
    "every direction carries a glyph and something to say",
    "val glyph: String" in emotions_code and "val spoken: String" in emotions_code,
    "a direction found by shape before it is read, and a preview short enough to hear eight of",
)
check(
    "the emotion preview is the name and the emotion, not the line",
    "fun previewLine(" in emotions_code,
    "eight emotions at twelve seconds each is two minutes to hear four seconds of difference",
)

# \u2500\u2500 THE VOICE CARD \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
check(
    "the chooser opens a card rather than acting on the row",
    "onOpen" in chooser_code and "onUse" not in chooser_code,
    "three lines per voice across eleven hundred voices, and nowhere for the emotions to live",
)
check(
    "the card shows every tag the provider publishes",
    "groupBy({ it.substringBefore" in card_code,
    "a card that shows two use-cases and an ellipsis has to be tapped to be read",
)
check(
    "the card shows the model the id forces",
    "Engines.modelFor(voice.id)" in card_code,
    "it is the rule that decides whether this voice can speak at all",
)
check(
    "a direction is sent only when there is one",
    'if (direction.isNotBlank())' in voices_code,
    "an empty description is not neutral, it is a field asking to be interpreted",
)

# ── THE WAVE ────────────────────────────────────────────────────
check(
    "the waveform is continuous",
    "w * 0.7f" not in tile_code and "w * 0.7f" not in editor_code,
    "seven tenths of each slot left a black gap between every bar",
)
check(
    "the wave colour is a setting",
    "WAVE_COLOURS" in model_code and "waveColour(" in ui_code,
    "the transcript sits on the wave, so a pale wave makes the words unreadable",
)

# ── CHANGED POINTS TAKE EFFECT WITHOUT STOPPING ──────────────────────────
check(
    "leaving the editor re-applies the points to whatever is sounding",
    "val wasPlaying = playing == editing" in ui_code and "val wasLooping" in ui_code,
    "otherwise the change just made is not the change being listened to",
)

# ── THE PICTURE AGREES WITH THE SOUND ──────────────────────────────
check(
    "the tile draws the audio that will actually play",
    "waveformOf(project.id, slot.index, waveScale, slot.voice)" in ui_code,
    "it drew the original while playing somebody else saying the same words at another length",
)
check(
    "the WAV reader walks chunks instead of assuming byte 44",
    "private fun layout(" in recorder_code and '"data"' in recorder_code,
    "Speechify puts a LIST chunk before the data and its size field is a streaming placeholder",
)
check(
    "both engines are asked for WAV",
    '"audio_format":"wav"' in voices_code,
    "an mp3 stored as .wav cannot be drawn and cannot go into a static AudioTrack buffer",
)
check(
    "the loop plays what the cell plays",
    ui_code.count("Looper.start(") == 2 and "Paths.original(filesDir, project.id, p.slot)" not in ui_code,
    "it could only loop the original while Speechify returned mp3",
)

# ── A TEXT FILE CAN BE READ ALOUD ──────────────────────────────────
text_code = code_only(src["Text.kt"])
check(
    "the text is cleaned before it is spoken",
    "fun forSpeaking(" in text_code and "Text.forSpeaking(" in ui_code,
    "a file has a byte-order mark, line endings and possibly two hundred kilobytes in it",
)
check(
    "it is cut here rather than silently at the far end",
    "MAX_CHARS" in text_code and "fun report(" in text_code,
    "Speechify truncates at two thousand characters and bills what it was sent",
)
check(
    "a cut lands on a sentence or a space, never mid-word",
    "lastIndexOfAny" in text_code and "lastIndexOf(' ')" in text_code,
    "half a word is something a voice will try to pronounce",
)
check(
    "a cell with no voice is asked for one rather than guessing",
    "pick Speechify or Hume to read it" in ui_code,
    "the engine buttons are directly below the message",
)
check(
    "playing rules ask whether there is audio, not whether it was recorded",
    "val hasAudio" in model_code and "project.slot(slot).hasAudio" in model_code,
    "a cell filled from a text file has no take of Baba's at all",
)
check(
    "the irreplaceable file still has its own question",
    "hasOriginal" in model_code and "generated.isNotEmpty()" in model_code,
    "hasAudio is for playing; hasOriginal is for the thing that cannot be made again",
)
check(
    "which voice is remembered, not just which engine",
    "fun setVoiceId(" in code_only(src["Words.kt"]),
    "reading a file later has to speak it again, and Hume is not something you can send to Hume",
)

# ── IS THERE A NEWER ONE ──────────────────────────────────────────
updates_code = code_only(src["Updates.kt"])
check(
    "the version check compares numbers rather than strings",
    "toInt()" in updates_code and "maxOf(a.size, b.size)" in updates_code,
    "v9 is greater than v10 alphabetically",
)
check(
    "it looks for the APK rather than the first asset",
    'endsWith(".apk")' in updates_code,
    "a release carries a source zip too, and the wrong one does nothing when it is tapped",
)
check(
    "it carries no token",
    "token" not in updates_code.lower() and "Authorization" not in updates_code,
    "the repository is public, and an app that carried one would be an app carrying a token",
)
check(
    "the app hands the APK to the browser rather than installing it",
    "Intent.ACTION_VIEW" in ui_code and "REQUEST_INSTALL_PACKAGES" not in ui_code,
    "replacing itself would mean the permission to install anything at all",
)

# ── WHAT WAS SPENT ────────────────────────────────────────────
spend_code = code_only(src["Spend.kt"])
check(
    "the total is computed from the log rather than stored",
    "fun totals()" in spend_code and "totalFile" not in spend_code,
    "a total kept beside a log is a number that can disagree with the lines it came from",
)
check(
    "each provider keeps its own unit",
    "val UNITS = mapOf(" in spend_code,
    "characters added to seconds is a number that means nothing",
)
check(
    "rates default to nothing rather than to a guess",
    "const val NO_RATE = 0.0" in spend_code,
    "an invented price looks exactly as certain as the measured count beside it",
)
check(
    "a call that billed nothing is not logged",
    "if (units <= 0.0) return" in spend_code,
    "a row saying zero has to be read to discover it says nothing",
)
check(
    "a torn last line does not take the file down",
    "runCatching { JSONObject(line) }.getOrNull() ?: continue" in spend_code,
    "one lost call rather than a lost file",
)
check(
    "what a call cost comes from the provider",
    "billable_characters_count" in voices_code and "audio_duration" in voices_code,
    "facts about the call rather than estimates of it",
)
check(
    "only Hume has a credit test",
    "fun humeCredit(" in voices_code
    and 'row.providerId == "hume"' in code_only(src["KeysScreen.kt"]),
    "nothing else publishes a balance: probed with real keys on 30.8.2026",
)
check(
    "the credit probe does not depend on one voice existing",
    '"utterances":[{"text":"Hi."}]' in voices_code,
    "a probe tied to a voice would one day report no credit when that voice was retired",
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
check("Test 1 is large enough to be a suite", cases >= 240, f"{cases} cases")
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
if len(checks_run) < 198:
    sys.exit(f"only {len(checks_run)} checks ran: this file is broken, not the code")
if failures:
    sys.exit("failed: " + ", ".join(failures))
print("all structural checks passed")
