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

    private companion object {
        const val KEY_PLAY_MODE = "play_mode"
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
    usage: Usage,
    onPlayMode: (PlayMode) -> Unit,
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
            "${usage.filledSlots} of $SLOTS slots recorded\n" +
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
