package com.mantra.sampleplayer

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
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
    var slotCount: Int
        get() {
            val stored = sp.getInt(KEY_SLOT_COUNT, DEFAULT_SLOTS)
            return if (stored in SLOT_CHOICES) stored else DEFAULT_SLOTS
        }
        set(value) {
            if (value in SLOT_CHOICES) sp.edit().putInt(KEY_SLOT_COUNT, value).apply()
        }

    private companion object {
        const val KEY_PLAY_MODE = "play_mode"
        const val KEY_SLOT_COUNT = "slot_count"
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
    usage: Usage,
    keySummary: List<String>,
    onImportKeys: (String) -> Unit,
    onPlayMode: (PlayMode) -> Unit,
    onSlotCount: (Int) -> Unit,
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
        Text(
            "Settings",
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 10.dp),
        )

        // ── HOW MANY CELLS ───────────────────────────────────────────────────────────────────
        //
        // Buttons rather than a number field. A text field is a keyboard, and this app is dictated
        // by somebody who does not type.
        Heading("CELLS")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in SLOT_CHOICES) {
                Button(
                    label = n.toString(),
                    modifier = Modifier.weight(1f),
                    solid = slotCount == n,
                    accent = PLAY_AMBER,
                ) { onSlotCount(n) }
            }
        }
        Note(
            if (slotCount <= PAGE_SIZE) {
                "One screen. Nothing to flip."
            } else {
                "${Paging.pageCount(slotCount)} screens. Flip sideways to reach them; the page " +
                    "number is in the line at the top."
            },
        )
        Note(
            "Lowering this hides cells, it does not delete them. Raise it again and the " +
                "recordings are still there. The storage figures below count everything on the " +
                "phone, hidden or not.",
        )

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
        // PASTE THE NOTE, NOT THE KEY. The note Baba already has is a working note with account
        // names, dates, the word CANCELLED and pasted URLs in it, and the canonical parser was
        // written to read exactly that. Asking him to pick the keys out of it by hand is asking
        // him to do the parser's job, on a phone, without typing.
        //
        // Nothing here can display a key. The summary is provider, count and account label, and
        // there is no code path that renders the value — which is stronger than a rule saying not
        // to.
        Heading("KEYS")
        if (keySummary.isEmpty()) {
            Note("None yet. Paste a key note below and import it.")
        } else {
            Text(
                keySummary.joinToString("\n"),
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(8.dp))
        var pasted by remember { mutableStateOf("") }
        BasicTextField(
            value = pasted,
            onValueChange = { pasted = it },
            textStyle = TextStyle(
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(PLAY_AMBER),
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .border(1.dp, Color(0xFF52525B))
                .padding(6.dp),
        )
        Spacer(Modifier.height(6.dp))
        Button("Import pasted keys", Modifier.fillMaxWidth()) {
            if (pasted.isNotBlank()) {
                onImportKeys(pasted)
                pasted = ""
            }
        }
        Note(
            "Paste the whole note. Account names on the line above each key are kept and shown; " +
                "keys themselves are never displayed anywhere in this app.",
        )

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
        Button("Back", Modifier.fillMaxWidth()) { onBack() }
        Spacer(Modifier.height(20.dp))
    }
}
