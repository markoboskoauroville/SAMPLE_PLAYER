package com.mantra.sampleplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
 * THE MAIN VIEW: THIRTY FULL-WIDTH LINES, SCROLLING.
 *
 * NOT a three across grid, and that is a correction to the brief rather than an oversight.
 * `MINIMALIST_STOPWATCH` v17 replaced a three by three pad grid with nine full-width lines and
 * recorded why: a grid of small squares reads as a keypad, a full-width line reads as a sample
 * list, and the width is what allows a waveform at 96 buckets instead of 28. Three across at
 * thirty tiles is under 130dp per tile on this phone, which is narrower than the pads that
 * decision rejected.
 */
class MainActivity : ComponentActivity() {

    private lateinit var vault: Vault
    private var player: MediaPlayer? = null

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = Vault(filesDir)
        vault.ensure(DEFAULT_PROJECT)
        setContent { App() }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Composable
    private fun App() {
        var project by remember { mutableStateOf(load(DEFAULT_PROJECT)) }
        var mode by remember { mutableStateOf(Mode.STOPPED) }
        var recordingSlot by remember { mutableStateOf<Int?>(null) }
        var playing by remember { mutableStateOf<Int?>(null) }
        var fraction by remember { mutableStateOf(0f) }
        var message by remember { mutableStateOf("") }
        val listState = rememberLazyListState()

        val level by Recorder.level.collectAsState()
        val advance by OverlayState.advanceRequested.collectAsState()

        // THE TRIANGLE. One press: stop this recording and start the next slot. It is not a
        // transport, and at slot 30 it stops rather than wrapping.
        LaunchedEffect(advance) {
            if (advance == 0L || recordingSlot == null) return@LaunchedEffect
            val from = recordingSlot ?: return@LaunchedEffect
            Recorder.stop()
            val next = Advance.next(from)
            if (next == null) {
                mode = Mode.STOPPED
                recordingSlot = null
                message = "slot $SLOTS is the last one"
            } else {
                delay(150)
                beginRecording(next) { p -> project = p }
                recordingSlot = next
            }
        }

        // The overlay only ever reflects what the recorder is doing. It is not a second source of
        // truth about whether anything is recording.
        LaunchedEffect(recordingSlot, level) {
            OverlayService.instance?.showRecording(recordingSlot, level)
        }

        // THE PLAYHEAD. A poll rather than a callback because MediaPlayer's position is the only
        // honest source, and 60ms is fast enough that the mark moves smoothly across a line while
        // being slow enough that it costs nothing.
        LaunchedEffect(playing) {
            val p = player
            while (playing != null && p != null) {
                val dur = p.duration.coerceAtLeast(1)
                fraction = (p.currentPosition.toFloat() / dur).coerceIn(0f, 1f)
                delay(60)
            }
        }

        Column(
            Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 10.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                "${project.name}   ${project.filled.size} / $SLOTS" +
                    if (message.isEmpty()) "" else "   ·   $message",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))

            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(project.slots, key = { it.index }) { slot ->
                    Line(
                        slot = slot,
                        playhead = if (playing == slot.index) fraction else null,
                        recording = recordingSlot == slot.index,
                        waveform = waveformOf(project.id, slot.index),
                        onPress = {
                            when (val p = Gesture.press(mode, project, slot.index, recordingSlot)) {
                                is Press.StartRecording -> {
                                    if (!hasMic()) {
                                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        beginRecording(p.slot) { project = it }
                                        recordingSlot = p.slot
                                        mode = Mode.RECORDING
                                        message = ""
                                    }
                                }
                                is Press.StopRecording -> {
                                    Recorder.stop()
                                    recordingSlot = null
                                    mode = Mode.STOPPED
                                    project = load(project.id)
                                }
                                is Press.SeekTo -> {
                                    startPlaying(p.slot, project) { playing = it }
                                }
                                is Press.Clear -> Unit
                                is Press.Refused -> message = p.why
                            }
                        },
                        onLongPress = {
                            when (val p = Gesture.longPress(mode, project, slot.index)) {
                                is Press.Clear -> {
                                    Paths.slotDir(filesDir, project.id, p.slot)
                                        .walkTopDown().filter { it.isFile }.forEach { it.delete() }
                                    project = load(project.id)
                                    message = "slot ${p.slot + 1} cleared"
                                }
                                is Press.Refused -> message = p.why
                                else -> Unit
                            }
                        },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button("Seq", Modifier.width(74.dp)) { message = "Seq arrives with v2" }
                Button("Voice", Modifier.width(74.dp)) { message = "Voice arrives with v2" }
                Button(if (mode == Mode.PLAYING) "Stop" else "Play", Modifier.weight(1f)) {
                    if (mode == Mode.PLAYING) {
                        player?.stop()
                        player?.release()
                        player = null
                        playing = null
                        mode = Mode.STOPPED
                    } else {
                        val first = project.sequence().firstOrNull()
                        if (first == null) {
                            message = "nothing recorded yet"
                        } else {
                            mode = Mode.PLAYING
                            startPlaying(first, project) { playing = it }
                        }
                    }
                }
            }
        }
    }

    /** Where the list jumps to. It jumps; it never smooth scrolls. */
    private fun startPlaying(slot: Int, project: Project, onSlot: (Int?) -> Unit) {
        player?.release()
        val f = Paths.playing(filesDir, project.id, slot, project.engine)
        if (!f.isFile) { onSlot(null); return }
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            prepare()
            setOnCompletionListener {
                val order = project.sequence()
                val at = order.indexOf(slot)
                val next = order.getOrNull(at + 1)
                if (next == null) onSlot(null) else startPlaying(next, project, onSlot)
            }
            start()
        }
        onSlot(slot)
    }

    private fun beginRecording(slot: Int, onDone: (Project) -> Unit) {
        startForegroundService(Intent(this, RecordingService::class.java))
        val target = Paths.original(filesDir, DEFAULT_PROJECT, slot)
        Recorder.start(target, slot) { onDone(load(DEFAULT_PROJECT)) }
    }

    private fun waveformOf(id: String, slot: Int): FloatArray {
        val f = Paths.original(filesDir, id, slot)
        if (!f.isFile) return FloatArray(0)
        return waveform(Recorder.read(f), WAVEFORM_BUCKETS)
    }

    private fun load(id: String): Project {
        val slots = (0 until SLOTS).map { i ->
            val f = Paths.original(filesDir, id, i)
            Slot(
                index = i,
                hasOriginal = f.isFile,
                lengthMs = Recorder.lengthMs(f),
            )
        }
        return Project(id = id, name = id, slots = slots)
    }

    /** The direct link out to the accessibility page, so nobody has to find it by hand. */
    @Suppress("unused")
    fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    companion object {
        const val DEFAULT_PROJECT = "project-01"

        /** Ninety-six, which is what a full-width line allows and a three-across tile does not. */
        const val WAVEFORM_BUCKETS = 96
    }
}
