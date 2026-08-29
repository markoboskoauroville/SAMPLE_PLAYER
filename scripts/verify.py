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
    "if (from >= SLOTS - 1) null" in model_code,
    "Advance.next returns null at slot 30, never 0",
)

# ── THIRTY IS WRITTEN ONCE ────────────────────────────────────────────────────────────────────
bare_30 = sum(len(re.findall(r"(?<![\w.])30(?![\w.])", code_only(t))) for t in src.values())
check(
    "the count of slots is a constant rather than a literal",
    "const val SLOTS = 30" in model_code and bare_30 <= 2,
    f"SLOTS declared once; bare 30s in code: {bare_30}",
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

# ── NOTHING TESTED MAY BE UNREACHABLE ─────────────────────────────────────────
#
# v1 shipped with a ported state machine that nothing called, and a suite of green tests proving
# it worked. A test of unreachable code is worse than no test: it reports confidence about a
# path the app never takes. Every class Test 1 exercises must be reached from the app itself.
recorder_code = code_only(src["Recorder.kt"])
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
check("Test 1 is large enough to be a suite", cases >= 45, f"{cases} cases")
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
if len(checks_run) < 18:
    sys.exit(f"only {len(checks_run)} checks ran: this file is broken, not the code")
if failures:
    sys.exit("failed: " + ", ".join(failures))
print("all structural checks passed")
