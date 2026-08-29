package com.mantra.sampleplayer

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
        get() = sp.getInt(KEY_WAVE, 1).coerceIn(1, WAVEFORM_SCALES.size)
        set(value) = sp.edit().putInt(KEY_WAVE, value.coerceIn(1, WAVEFORM_SCALES.size)).apply()

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

@Composable
private fun Note(text: String) {
    Text(
        text,
        color = DIM,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * THE SETTINGS SCREEN.
 *
 * Two switches and some arithmetic. It is not a preferences tree and it should not become one:
 * every option here is one that has already earned its place by being asked for.
 */
@Composable
fun SettingsScreen(
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
        ScreenHeader("Settings", onBack)

        // ── HOW MANY CELLS, AND OVER HOW MANY PAGES ──────────────────────────────────────────
        //
        // PLUS AND MINUS, NOT A MENU OF FOUR SIZES. v5 offered 15, 30, 60 and 120 on the grounds
        // that a text field is a keyboard. The objection is to typing, not to choosing a number,
        // and a stepper is a number without a keyboard. Four fixed sizes meant a set of twelve
        // lines had to be a set of fifteen.
        Heading("CELLS")
        Stepper(
            value = slotCount,
            onChange = { onSlotCount(it.coerceIn(MIN_SLOTS, MAX_SLOTS)) },
            step = 1,
            bigStep = 10,
        )
        Note("Raising this adds cells at the end. Nothing already recorded moves or is lost.")

        Heading("PAGES")
        Stepper(
            value = pageSpread,
            onChange = { onPageSpread(it.coerceIn(1, slotCount)) },
            step = 1,
            bigStep = 5,
        )
        val layout = Grid.of(slotCount, pageSpread)
        Note(
            "${layout.perPage} per page, ${layout.columns} across and ${layout.rows} down. " +
                "The cells fill the page: one on a page is the size of the page, two are halves.",
        )

        // ── WAVEFORM DETAIL ──────────────────────────────────────────────────────────────────
        //
        // A SETTING RATHER THAN A DECISION, because it is a real trade. Every bucket is a scan
        // over its share of the samples and a rectangle to draw, on up to a hundred and twenty
        // cells at once, so four times the detail is four times the work every time a page opens.
        Heading("WAVEFORM DETAIL")
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
        Note("${waveformBuckets(waveScale)} slices per cell. Higher is slower to open a page.")

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
        Note("The transcript is written over the wave, so a pale wave makes it unreadable.")

        // ── WHAT HAPPENS WHEN A SAMPLE ENDS ──────────────────────────────────────────────────
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
        Note(
            if (playMode == PlayMode.CONTINUOUS) {
                "Plays on down the running order. The playhead travels through the set."
            } else {
                "Plays the tile you pressed and stops. For hearing one take four times."
            },
        )

        // ── STORAGE ──────────────────────────────────────────────────────────────────────────
        Heading("STORAGE")
        Text(
            "${usage.filledSlots} slots recorded\n" +
                "${usage.files} files, ${usage.megabytes()}",
            color = Color.White,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(8.dp))
        Button("Clear generated audio", Modifier.fillMaxWidth()) { onClearGenerated() }
        Note(
            "Removes engine voices only. Your own recordings are in a different file and are " +
                "never touched by this. To delete one of your takes, long press its tile.",
        )

        // ── KEYS ─────────────────────────────────────────────────────────────────────────────
        //
        // A DOOR, NOT A PANEL. Listing, testing and deleting keys is a screen's worth of work and
        // it belongs on its own screen, the way `Key_Tester` has it. The paste box that used to be
        // here is gone: a text field on a phone is a keyboard, and this app is dictated.
        Heading("KEYS")

        // WHAT THE APP WANTS, BESIDE WHAT IT HAS. A list of whatever was imported says what you
        // have and never what is missing, and then "why is Hume greyed out" is a question with no
        // answer on the screen that caused it.
        Text(
            Needs.lines(keysHeld).joinToString("\n"),
            color = Color(0xFF94A3B8),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        val blocked = Needs.blocked(keysHeld)
        if (blocked.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                blocked,
                color = PLAY_AMBER,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Note(
            "Anything else in the note — GitHub, Gemini, Anthropic — is kept and can be tested, " +
                "but this app never calls it.",
        )
        Spacer(Modifier.height(10.dp))

        if (keySummary.isEmpty()) {
            Note("Nothing imported yet.")
        } else {
            Text(
                keySummary.joinToString("\n"),
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(8.dp))
        Button("Keys — import, test, delete", Modifier.fillMaxWidth()) { onKeys() }

        // ── PERMISSIONS ──────────────────────────────────────────────────────────────────────
        //
        // TWO BUTTONS, IN THE ORDER THE DOORS HAVE TO BE OPENED. An accessibility service
        // installed from an APK is a restricted setting: the switch on the accessibility screen is
        // greyed out until it has been unlocked from the app's own page, and nothing on the greyed
        // switch says so. Sending somebody to the second door first is sending them to a locked
        // one.
        Heading("PERMISSIONS — IN THIS ORDER")
        Note(
            "The overlay is what lets you record without leaving the app you are reading from: " +
                "the level line across the top of every screen, and the triangle under the " +
                "camera that stops this sample and starts the next one. Android calls that an " +
                "accessibility service, and it will not switch on until it is unlocked below. " +
                "It does not read your screen.",
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "1.",
            color = LABEL,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Button("Open app properties", Modifier.fillMaxWidth()) { onAppProperties() }
        Note("Then the three dots at the top right, then Allow restricted settings.")
        Spacer(Modifier.height(10.dp))
        Text(
            "2.",
            color = LABEL,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Button("Open accessibility settings", Modifier.fillMaxWidth()) { onPermissions() }
        Note("Find Sample Player in the list and turn it on. It stays on until you turn it off.")

        Spacer(Modifier.height(20.dp))
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
