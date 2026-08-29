package com.mantra.sampleplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

/**
 * THE MAIN VIEW: TEN ROWS OF THREE.
 *
 * v1 shipped thirty full-width lines and it was wrong on glass. See `Tile` for the reasoning and
 * for what the change costs.
 *
 * IT DRAWS INSIDE THE SAFE AREA. v1 went edge to edge, which on Android 15 is not a choice — an
 * app targeting SDK 35 is handed the whole display including the status bar and the gesture bar.
 * The project name ended up underneath the clock and the action row underneath the navigation
 * buttons, so the widest control in the app was the one hardest to press. `safeDrawingPadding`
 * takes the insets the system reports rather than a hard-coded number, because a phone with a
 * camera hole in a different place is not this developer's to guess at.
 */
class MainActivity : ComponentActivity() {

    private lateinit var vault: Vault
    private lateinit var prefs: Prefs
    private var player: MediaPlayer? = null

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = Vault(filesDir)
        prefs = Prefs(this)
        vault.ensure(DEFAULT_PROJECT, prefs.slotCount)
        setContent { App() }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun slotCountNow(): Int = prefs.slotCount

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Composable
    private fun App() {
        var slotCount by remember { mutableStateOf(prefs.slotCount) }
        var project by remember { mutableStateOf(load(DEFAULT_PROJECT, slotCount)) }

        // THE MODE IS NOW A CONTROL, NOT A SIDE EFFECT.
        //
        // In v1 the mode was implied by whether the Play button had been pressed, which meant the
        // difference between a press that seeks and a press that RECORDS OVER A TAKE was a state
        // nothing on screen named. With thirty small tiles that is worse, not better: the target
        // is smaller and the consequence is the same. So there is one toggle, it says REC or PLAY,
        // and it is solid in the colour of what it will do.
        var armed by remember { mutableStateOf(Mode.STOPPED) }
        var recordingSlot by remember { mutableStateOf<Int?>(null) }
        var playing by remember { mutableStateOf<Int?>(null) }
        var fraction by remember { mutableStateOf(0f) }
        var message by remember { mutableStateOf("") }
        var showSettings by remember { mutableStateOf(false) }
        var playMode by remember { mutableStateOf(prefs.playMode) }
        val pagerState = rememberPagerState(pageCount = { project.pages })

        val level by Recorder.level.collectAsState()
        val liveShape by Recorder.live.collectAsState()
        val advance by OverlayState.advanceRequested.collectAsState()

        val mode = when {
            recordingSlot != null -> Mode.RECORDING
            armed == Mode.PLAYING -> Mode.PLAYING
            else -> Mode.STOPPED
        }

        // THE TRIANGLE. One press: stop this recording and start the next slot. It is not a
        // transport, and at slot 30 it stops rather than wrapping.
        LaunchedEffect(advance) {
            if (advance == 0L || recordingSlot == null) return@LaunchedEffect
            val from = recordingSlot ?: return@LaunchedEffect
            Recorder.stop()
            val next = Advance.next(from, project.size)
            if (next == null) {
                recordingSlot = null
                message = "slot ${project.size} is the last one"
            } else {
                delay(150)
                beginRecording(next) { loaded, _ -> project = loaded }
                recordingSlot = next
            }
        }

        LaunchedEffect(recordingSlot, level) {
            OverlayService.instance?.showRecording(recordingSlot, level)
        }

        // THE PLAYHEAD. Polled rather than driven by a callback, because MediaPlayer's position is
        // the only honest source. Sixty milliseconds is fast enough that the mark moves smoothly
        // across a tile and slow enough that it costs nothing.
        LaunchedEffect(playing) {
            while (playing != null) {
                val p = player ?: break
                val dur = p.duration.coerceAtLeast(1)
                fraction = (p.currentPosition.toFloat() / dur).coerceIn(0f, 1f)
                delay(60)
            }
        }

        if (showSettings) {
            // A SCREEN, NOT A SHEET OVER THE GRID. design-language.md: a panel that covers the
            // thing it configures leaves nowhere to look while deciding, and v5 of the stopwatch
            // shipped exactly that and had no way back out of it in landscape.
            SettingsScreen(
                playMode = playMode,
                slotCount = slotCount,
                usage = vault.usageOf(project.id),
                onPlayMode = {
                    playMode = it
                    prefs.playMode = it
                },
                onSlotCount = {
                    // RAISING THE COUNT REVEALS CELLS; LOWERING IT HIDES THEM. Nothing is deleted
                    // either way. A setting that silently destroys recordings because a number
                    // went down would be the worst control in the app, and the storage figures
                    // above keep counting the hidden ones so the space is never a mystery.
                    slotCount = it
                    prefs.slotCount = it
                    vault.ensure(project.id, it)
                    project = load(project.id, it)
                },
                onClearGenerated = {
                    val n = vault.clearGenerated(project.id)
                    message = "cleared $n generated file(s)"
                },
                onAppProperties = { openAppProperties() },
                onPermissions = { openAccessibilitySettings() },
                onBack = { showSettings = false },
            )
            return
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .safeDrawingPadding()
                .padding(horizontal = 6.dp),
        ) {
            Text(
                buildString {
                    append(project.name)
                    append("   ")
                    append(project.filled.size)
                    append(" / ")
                    append(project.size)
                    // THE PAGE COUNT GOES HERE AND THERE IS NO NEXT BUTTON. Baba's own answer,
                    // after considering splitting a cell into forward and back and rejecting it in
                    // the same breath: put the number somewhere and flip the screen.
                    val pageLabel = Paging.label(pagerState.currentPage, project.size)
                    if (pageLabel.isNotEmpty()) {
                        append("   ·   ")
                        append(pageLabel)
                    }
                    if (message.isNotEmpty()) {
                        append("   ·   ")
                        append(message)
                    }
                },
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 6.dp),
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
              val onThisPage = Paging.slotsOn(page, project.size).map { project.slot(it) }
              LazyVerticalGrid(columns = GridCells.Fixed(COLUMNS)) {
                items(onThisPage, key = { it.index }) { slot ->
                    Tile(
                        slot = slot,
                        playhead = if (playing == slot.index) fraction else null,
                        recording = recordingSlot == slot.index,
                        // While this tile is recording it draws the shape arriving, not the file
                        // on disk, which does not exist yet.
                        waveform = if (recordingSlot == slot.index) {
                            liveShape
                        } else {
                            waveformOf(project.id, slot.index)
                        },
                        onPress = {
                            when (val p = Gesture.press(mode, project, slot.index, recordingSlot)) {
                                is Press.StartRecording -> {
                                    if (!hasMic()) {
                                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        beginRecording(p.slot) { loaded, said ->
                                            project = loaded
                                            recordingSlot = null
                                            message = said
                                        }
                                        recordingSlot = p.slot
                                        message = ""
                                    }
                                }
                                is Press.StopRecording -> {
                                    Recorder.stop()
                                    recordingSlot = null
                                    project = load(project.id, slotCountNow())
                                }
                                is Press.SeekTo -> startPlaying(p.slot, project, playMode) { playing = it }
                                is Press.Clear -> Unit
                                is Press.Refused -> message = p.why
                            }
                        },
                        onLongPress = {
                            when (val p = Gesture.longPress(mode, project, slot.index)) {
                                is Press.Clear -> {
                                    Paths.slotDir(filesDir, project.id, p.slot)
                                        .walkTopDown().filter { it.isFile }.forEach { it.delete() }
                                    project = load(project.id, slotCountNow())
                                    message = "slot ${p.slot + 1} cleared"
                                }
                                is Press.Refused -> message = p.why
                                else -> Unit
                            }
                        },
                    )
                }
              }
            }

            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button("Seq", Modifier.width(58.dp)) { message = "Seq arrives later" }
                Button("Voice", Modifier.width(66.dp)) { message = "Voice arrives later" }
                Button(
                    label = if (armed == Mode.PLAYING) "PLAY" else "REC",
                    modifier = Modifier.weight(1f),
                    solid = true,
                    accent = if (armed == Mode.PLAYING) PLAY_AMBER else RECORDING_RED,
                ) {
                    // Flipping the toggle stops whatever the old mode was doing. Leaving a
                    // recording running while the app claims to be a player is how a press lands
                    // in the wrong branch.
                    if (recordingSlot != null) {
                        Recorder.stop()
                        recordingSlot = null
                        project = load(project.id, slotCountNow())
                    }
                    player?.let { runCatching { it.stop() }; it.release() }
                    player = null
                    playing = null
                    armed = if (armed == Mode.PLAYING) Mode.STOPPED else Mode.PLAYING
                    message = ""
                }
                // The gear last, and narrow. It is the control pressed least often in the app and
                // it sits at the end of the row for that reason, not because there was space left.
                Button("\u2699", Modifier.width(52.dp)) { showSettings = true }
            }
        }
    }

    private fun startPlaying(
        slot: Int,
        project: Project,
        mode: PlayMode,
        onSlot: (Int?) -> Unit,
    ) {
        player?.release()
        val f = Paths.playing(filesDir, project.id, slot, project.engine)
        if (!f.isFile) { onSlot(null); return }
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            prepare()
            setOnCompletionListener {
                val next = nextInPlayback(project, slot, mode)
                if (next == null) onSlot(null) else startPlaying(next, project, mode, onSlot)
            }
            start()
        }
        onSlot(slot)
    }

    private fun beginRecording(slot: Int, onDone: (Project, String) -> Unit) {
        startForegroundService(Intent(this, RecordingService::class.java))
        val pending = Paths.pending(filesDir, DEFAULT_PROJECT, slot)
        val original = Paths.original(filesDir, DEFAULT_PROJECT, slot)
        Recorder.start(pending, slot, promoteTo = original) { quality ->
            if (quality == SampleQuality.GOOD) {
                // A RETAKE REPLACES EVERYTHING DERIVED FROM THE TAKE IT REPLACED. The recording is
                // new, so the transcript is about words that are no longer there and the generated
                // voices are saying them. Keeping either is the answer whose wrongness does not
                // show up until a lot of work has been done.
                File(Paths.slotDir(filesDir, DEFAULT_PROJECT, slot), "gen")
                    .listFiles()?.forEach { it.delete() }
            }
            onDone(load(DEFAULT_PROJECT, slotCountNow()), SampleCheck.describe(quality))
        }
    }

    private fun waveformOf(id: String, slot: Int): FloatArray {
        val f = Paths.original(filesDir, id, slot)
        if (!f.isFile) return FloatArray(0)
        return waveform(Recorder.read(f), WAVEFORM_BUCKETS)
    }

    private fun load(id: String, count: Int): Project {
        val slots = (0 until count).map { i ->
            val f = Paths.original(filesDir, id, i)
            Slot(index = i, hasOriginal = f.isFile, lengthMs = Recorder.lengthMs(f))
        }
        return Project(id = id, name = id, slots = slots)
    }

    /** The direct link out to the accessibility page, so nobody has to find it by hand. */
    fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /**
     * THE APP'S OWN PAGE IN SYSTEM SETTINGS, AND IT HAS TO COME FIRST.
     *
     * On this phone an accessibility service installed from an APK cannot simply be switched on:
     * Android calls it a restricted setting and the switch is greyed out with no explanation of
     * what to do about it. The way through is this page, then the three dots at the top right,
     * then "Allow restricted settings" — and only after that does the accessibility screen accept
     * the toggle.
     *
     * That is three steps in two different places, none of which the app can perform on the
     * person's behalf. What it can do is put the first door next to the second one, in the order
     * they have to be opened.
     */
    fun openAppProperties() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    companion object {
        const val DEFAULT_PROJECT = "project-01"

        /** Three across, ten down, which puts the whole set on one screen. */
        const val COLUMNS = 3

        /** Thirty-two, which is what a third of a screen width can show honestly. */
        const val WAVEFORM_BUCKETS = 32
    }
}
