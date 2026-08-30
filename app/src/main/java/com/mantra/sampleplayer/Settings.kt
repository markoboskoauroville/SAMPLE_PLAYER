package com.mantra.sampleplayer

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * WHERE THE SETTINGS LIVE, AND IT IS A FILE RATHER THAN A CLASS FULL OF STATE.
 *
 * `SharedPreferences` directly, no wrapper, no injection. There are two values.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("sampleplayer", Context.MODE_PRIVATE)

    var playMode: PlayMode
        get() = runCatching { PlayMode.valueOf(sp.getString(KEY_PLAY_MODE, null) ?: "") }
            .getOrDefault(PlayMode.CONTINUOUS)
        set(value) = sp.edit().putString(KEY_PLAY_MODE, value.name).apply()

    /**
     * How many cells the set has. Sanitised against the offered list rather than trusted, so a
     * corrupt or hand-edited preference cannot produce a project of minus four slots.
     */
    /** How many cells. Clamped to the range rather than trusted, so a corrupt value cannot make
     *  a project of minus four slots. */
    var slotCount: Int
        get() = sp.getInt(KEY_SLOT_COUNT, DEFAULT_SLOTS).coerceIn(MIN_SLOTS, MAX_SLOTS)
        set(value) = sp.edit().putInt(KEY_SLOT_COUNT, value.coerceIn(MIN_SLOTS, MAX_SLOTS)).apply()

    /** How many pages to spread them over. Never more pages than cells. */
    var pageSpread: Int
        get() = sp.getInt(KEY_PAGES, 1).coerceIn(1, slotCount)
        set(value) = sp.edit().putInt(KEY_PAGES, value.coerceIn(1, MAX_SLOTS)).apply()

    /** 1x to 4x. Clamped, because a stored 9 would be a hundred and twenty cells of work. */
    var waveScale: Int
        get() = sp.getInt(KEY_WAVE, WAVEFORM_SCALES.first())
            .coerceIn(WAVEFORM_SCALES.first(), WAVEFORM_SCALES.last())
        set(value) = sp.edit()
            .putInt(KEY_WAVE, value.coerceIn(WAVEFORM_SCALES.first(), WAVEFORM_SCALES.last()))
            .apply()

    /** Which colour the wave is drawn in. */
    var waveColourIndex: Int
        get() = sp.getInt(KEY_WAVE_COLOUR, 0).coerceIn(0, WAVE_COLOURS.size - 1)
        set(value) = sp.edit().putInt(KEY_WAVE_COLOUR, value.coerceIn(0, WAVE_COLOURS.size - 1)).apply()

    /**
     * Starred voices, as `engine/id`.
     *
     * A set in one preference rather than a row per voice: there are eleven hundred voices and a
     * preferences file with a key for each one is a file nobody can read when something goes wrong.
     */
    var starredVoices: Set<String>
        get() = sp.getStringSet(KEY_STARS, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_STARS, value).apply()

    private companion object {
        const val KEY_WAVE_COLOUR = "wave_colour"
        const val KEY_STARS = "starred_voices"
        const val KEY_WAVE = "wave_scale"
        const val KEY_PLAY_MODE = "play_mode"
        const val KEY_SLOT_COUNT = "slot_count"
        const val KEY_PAGES = "page_spread"
    }
}

private val LABEL = Color(0xFF94A3B8)
private val DIM = Color(0xFF71717A)

@Composable
private fun Heading(text: String) {
    Text(
        text,
        color = LABEL,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}


/**
 * THE SETTINGS SCREEN.
 *
 * TITLES AND CONTROLS ONLY. THE HELP IS ONE BLOCK AT THE BOTTOM.
 *
 * Every control here used to carry a line or two of explanation directly underneath it. Read once
 * that is useful; read the fiftieth time it is noise between you and the thing you came for, and it
 * made this screen roughly three times taller than the controls need. Somebody who wants to know
 * what "1x to 4x" costs can scroll to the end and find it with everything else.
 *
 * THE VERSION IS IN THE TITLE because this is the only screen that is always reachable, and the
 * first question about any bug is which build it happened on.
 */
@Composable
fun SettingsScreen(
    version: String,
    playMode: PlayMode,
    slotCount: Int,
    pageSpread: Int,
    waveScale: Int,
    waveColour: Int,
    usage: Usage,
    keySummary: List<String>,
    keysHeld: Set<String>,
    onKeys: () -> Unit,
    onPlayMode: (PlayMode) -> Unit,
    onSlotCount: (Int) -> Unit,
    onPageSpread: (Int) -> Unit,
    onWaveScale: (Int) -> Unit,
    onWaveColour: (Int) -> Unit,
    onClearGenerated: () -> Unit,
    onAppProperties: () -> Unit,
    onPermissions: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .padding(horizontal = 10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader("Settings   v$version", onBack)

        // ── CELLS ────────────────────────────────────────────────────────────────────────────
        Heading("CELLS")
        Stepper(
            value = slotCount,
            onChange = { onSlotCount(it.coerceIn(MIN_SLOTS, MAX_SLOTS)) },
            step = 1,
            bigStep = 10,
        )

        // ── PAGES ────────────────────────────────────────────────────────────────────────────
        val layout = Grid.of(slotCount, pageSpread)
        Heading("PAGES   ${layout.perPage} per page, ${layout.columns} \u00d7 ${layout.rows}")
        Stepper(
            value = pageSpread,
            onChange = { onPageSpread(it.coerceIn(1, slotCount)) },
            step = 1,
            bigStep = 5,
        )

        // ── WAVEFORM ─────────────────────────────────────────────────────────────────────────
        Heading("WAVEFORM DETAIL   ${waveformBuckets(waveScale)} slices")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in WAVEFORM_SCALES) {
                Button(
                    label = "${n}x",
                    modifier = Modifier.weight(1f),
                    solid = waveScale == n,
                    accent = PLAY_AMBER,
                ) { onWaveScale(n) }
            }
        }

        Heading("WAVEFORM COLOUR")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            for ((i, pair) in WAVE_COLOURS.withIndex()) {
                Box(
                    Modifier
                        .padding(end = 6.dp)
                        .height(38.dp)
                        .width(58.dp)
                        .background(if (waveColour == i) Color(pair.second) else Color.Black)
                        .border(1.dp, Color(pair.second))
                        .pointerInput(i) { detectTapGestures(onTap = { onWaveColour(i) }) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        pair.first,
                        color = if (waveColour == i) Color.Black else Color(pair.second),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        // ── PLAYBACK ─────────────────────────────────────────────────────────────────────────
        Heading("WHEN A SAMPLE ENDS")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                label = "Continuous",
                modifier = Modifier.weight(1f),
                solid = playMode == PlayMode.CONTINUOUS,
                accent = PLAY_AMBER,
            ) { onPlayMode(PlayMode.CONTINUOUS) }
            Button(
                label = "Single",
                modifier = Modifier.weight(1f),
                solid = playMode == PlayMode.SINGLE,
                accent = PLAY_AMBER,
            ) { onPlayMode(PlayMode.SINGLE) }
        }

        // ── STORAGE ──────────────────────────────────────────────────────────────────────────
        Heading(
            "STORAGE   ${usage.filledSlots} recorded, ${usage.files} files, ${usage.megabytes()}",
        )
        Button("Clear generated audio", Modifier.fillMaxWidth()) { onClearGenerated() }

        // ── KEYS ─────────────────────────────────────────────────────────────────────────────
        Heading("KEYS")
        Text(
            Needs.lines(keysHeld).joinToString("\n"),
            color = LABEL,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        val blocked = Needs.blocked(keysHeld)
        if (blocked.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                blocked,
                color = PLAY_AMBER,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (keySummary.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                keySummary.joinToString("\n"),
                color = Color.White,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(6.dp))
        Button("Keys \u2014 import, test, delete", Modifier.fillMaxWidth()) { onKeys() }

        // ── PERMISSIONS ──────────────────────────────────────────────────────────────────────
        Heading("PERMISSIONS \u2014 IN THIS ORDER")
        Button("1. Open app properties", Modifier.fillMaxWidth()) { onAppProperties() }
        Spacer(Modifier.height(6.dp))
        Button("2. Open accessibility settings", Modifier.fillMaxWidth()) { onPermissions() }

        // ── THE HELP, ALL OF IT, DOWN HERE ───────────────────────────────────────────────────
        Spacer(Modifier.height(28.dp))
        Text(
            "WHAT THESE DO",
            color = LABEL,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(6.dp))
        Help(
            "Cells",
            "How many cells the set has. Raising it adds cells at the end; nothing already " +
                "recorded moves or is lost. Lowering it HIDES cells rather than deleting them, " +
                "and raising it again brings the recordings back.",
        )
        Help(
            "Pages",
            "How many screens to spread the cells over. The cells fill the page: one on a page " +
                "is the size of the page, two are halves. Nothing scrolls \u2014 flip sideways, " +
                "and the page number is in the line at the top of the grid.",
        )
        Help(
            "Waveform detail",
            "How finely each cell draws its shape. Every slice is a scan over its share of the " +
                "samples and a rectangle to draw, on up to a hundred and twenty cells at once, so " +
                "four times the detail is four times the work each time a page opens. The editor " +
                "always uses the finest: there it is one recording on a whole screen.",
        )
        Help(
            "Waveform colour",
            "The transcript is written over the wave, so a pale wave makes the words unreadable.",
        )
        Help(
            "When a sample ends",
            "Continuous plays on down the running order. Single plays the cell you pressed and " +
                "stops, which is what you want when deciding whether take 14 is the one.",
        )
        Help(
            "Storage",
            "Counts everything on the phone, including cells currently hidden by a lower cell " +
                "count. Clear generated audio removes engine voices only \u2014 your own " +
                "recordings are in a different file and are never touched by it. To delete one " +
                "of your takes, long press its cell.",
        )
        Help(
            "Keys",
            "AssemblyAI transcribes and is required. Speechify and Hume are the two voice " +
                "engines and either one is enough; Hume needs its API key AND its secret key. " +
                "Anything else in the note is kept and testable and never called by this app. " +
                "Keys are never displayed anywhere.",
        )
        Help(
            "Permissions",
            "The overlay is what lets you record without leaving the app you are reading from: " +
                "the level line across the top, and the triangle under the camera that stops this " +
                "cell and starts the next. Android calls that an accessibility service and will " +
                "not switch it on until it is unlocked from the app's own page \u2014 so: 1, then " +
                "the three dots at the top right, then Allow restricted settings, then 2. It does " +
                "not read your screen.",
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * A NUMBER WITHOUT A KEYBOARD.
 *
 * Two steps in each direction rather than one: nudging a set from thirty to sixty one press at a
 * time is thirty presses, and the person doing it dictates rather than types precisely because
 * that kind of work is expensive for him.
 */
@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, step: Int, bigStep: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button("−$bigStep", Modifier.weight(1f)) { onChange(value - bigStep) }
        Button("−$step", Modifier.weight(1f)) { onChange(value - step) }
        Box(
            Modifier.weight(1.4f).height(42.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                value.toString(),
                color = Color.White,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Button("+$step", Modifier.weight(1f)) { onChange(value + step) }
        Button("+$bigStep", Modifier.weight(1f)) { onChange(value + bigStep) }
    }
}
