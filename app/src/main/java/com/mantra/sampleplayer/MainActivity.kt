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
    private lateinit var keys: Keys
    private var player: MediaPlayer? = null

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * THE KEY NOTE ARRIVES AS A FILE, NOT AS A PASTE.
     *
     * A text field on a phone is a keyboard, and this app is dictated by somebody who does not
     * type. The system picker opens wherever the note already lives — Drive, Downloads, a folder
     * synced from the desktop — and hands back a stream.
     *
     * `OpenDocument` rather than `GetContent`, because the former returns a durable URI and the
     * latter can hand back one that is gone by the time it is read.
     */
    private var onKeyFile: ((String) -> Unit)? = null

    private var pendingSave: java.io.File? = null

    /**
     * SAVE A TAKE WHEREVER HE WANTS IT.
     *
     * `CreateDocument` rather than writing into a folder the app picked: the phone's own chooser
     * puts it in Drive, in Downloads, in a synced folder, wherever the rest of the work already
     * lives. The app does not need storage permission to do this and does not ask for one.
     *
     * WHAT IS EXPORTED IS THE ORIGINAL, untrimmed and exactly as recorded. The playback points are
     * this app's opinion about where the phrase starts; the file is the take.
     */
    private val saveSample = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav"),
    ) { uri ->
        val src = pendingSave
        pendingSave = null
        if (uri == null || src == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
        }
    }

    private val pickKeyFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        // The URI, never the contents, and not even the URI if it might carry a query string.
        onKeyFile?.invoke(text ?: "")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = Vault(filesDir)
        prefs = Prefs(this)
        keys = Keys(this)
        vault.ensure(DEFAULT_PROJECT, prefs.slotCount)
        setContent { App() }
    }

    override fun onDestroy() {
        Looper.stop()
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun slotCountNow(): Int = prefs.slotCount

    @Volatile private var stageSink: ((String) -> Unit)? = null

    /** Report progress from a worker thread to whichever screen asked for it. */
    private fun setStage(text: String) {
        val sink = stageSink
        runOnUiThread { sink?.invoke(text) }
    }

    /**
     * Run a network job off the main thread.
     *
     * A plain thread, not a coroutine scope, because there is exactly one of these at a time and
     * the alternative is machinery around a single call. Every job reports what it is doing: a six
     * minute Generate that says nothing is indistinguishable from one that has died.
     */
    private fun work(onStage: (String) -> Unit, job: () -> Unit) {
        stageSink = onStage
        Thread {
            runCatching { job() }.onFailure { setStage(it.javaClass.simpleName) }
        }.start()
    }

    private fun playFile(f: File) {
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            prepare()
            start()
        }
    }

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
        var optionsFor by remember { mutableStateOf<Int?>(null) }
        var editorFor by remember { mutableStateOf<Int?>(null) }
        var showKeys by remember { mutableStateOf(false) }
        var keyRows by remember { mutableStateOf(keys.rows()) }
        var keyBusy by remember { mutableStateOf("") }
        var confirmOverwrite by remember { mutableStateOf<Int?>(null) }
        var stage by remember { mutableStateOf("") }
        var engine by remember { mutableStateOf<String?>(null) }
        var voices by remember { mutableStateOf(emptyList<Voice>()) }
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

        val editing = editorFor
        if (editing != null) {
            val slotNow = project.slot(editing)
            var t by remember(editing) { mutableStateOf(Words(this).trim(project.id, editing)) }
            WaveEditor(
                slot = slotNow,
                waveform = waveformOf(project.id, editing),
                trim = t,
                playhead = if (playing == editing) fraction else null,
                onTrim = {
                    t = it
                    Words(this).setTrim(project.id, editing, it)
                },
                onPreview = { startPlaying(editing, project, PlayMode.SINGLE) { playing = it } },
                onReset = {
                    t = Trim.NONE
                    Words(this).setTrim(project.id, editing, Trim.NONE)
                },
                onBack = {
                    player?.let { runCatching { it.stop() }; it.release() }
                    player = null
                    playing = null
                    editorFor = null
                    project = load(project.id, slotCountNow())
                },
            )
            return
        }

        if (showKeys) {
            KeysScreen(
                rows = keyRows,
                busy = keyBusy,
                onImportFile = {
                    onKeyFile = { text ->
                        val added = keys.import(text)
                        keyRows = keys.rows()
                        keyBusy = if (added.isEmpty()) {
                            "nothing key-shaped in that file"
                        } else {
                            added.entries.joinToString(", ") { e -> "${e.value} ${e.key}" } +
                                " imported"
                        }
                    }
                    // text/* rather than text/plain: a note exported from a phone is as likely to
                    // arrive as octet-stream or as a .md, and a picker that shows nothing is
                    // indistinguishable from a picker that is broken.
                    pickKeyFile.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
                },
                onTest = { row ->
                    work({ keyBusy = it }) {
                        setStage("testing ${row.masked}…")
                        val c = keys.credentialFor(row)
                        val r = if (c == null) null else Providers.test(c, row.providerId)
                        runOnUiThread {
                            keyRows = keyRows.map { if (it.key == row.key) it.copy(result = r) else it }
                            keyBusy = ""
                        }
                    }
                },
                onTestAll = {
                    work({ keyBusy = it }) {
                        val rows = keys.rows()
                        val done = ArrayList<KeyRow>(rows.size)
                        for ((i, row) in rows.withIndex()) {
                            setStage("testing ${i + 1} of ${rows.size}…")
                            val c = keys.credentialFor(row)
                            done.add(row.copy(result = if (c == null) null else Providers.test(c, row.providerId)))
                        }
                        runOnUiThread {
                            keyRows = done
                            keyBusy = ""
                        }
                    }
                },
                onDelete = { row ->
                    keys.delete(row.key)
                    keyRows = keys.rows()
                    keyBusy = "deleted"
                },
                onBack = { showKeys = false },
            )
            return
        }

        val openSlot = optionsFor
        if (openSlot != null) {
            SlotOptions(
                slot = project.slot(openSlot),
                stage = stage,
                voices = voices,
                engine = engine,
                canSpeechify = keys.has(Engines.SPEECHIFY),
                canHume = keys.has(Engines.HUME),
                hasGenerated = project.slot(openSlot).generated.isNotEmpty(),
                looping = Looper.slot == openSlot,
                onDelete = {
                    Paths.slotDir(filesDir, project.id, openSlot)
                        .walkTopDown().filter { it.isFile }.forEach { it.delete() }
                    project = load(project.id, slotCountNow())
                    message = "cell ${openSlot + 1} deleted"
                    optionsFor = null
                },
                onEngine = { chosen ->
                    engine = chosen
                    voices = emptyList()
                    // TRANSCRIBE ON THE WAY, NEVER AS A STEP. Nobody wants a transcript; they want
                    // a different voice, and the transcript is what the app needs to give them
                    // one. It happens here, with a line saying what is going on.
                    work(
                        onStage = { stage = it },
                        job = {
                            val slotNow = project.slot(openSlot)
                            if (slotNow.words.isBlank()) {
                                setStage("transcribing…")
                                val wav = Paths.original(filesDir, project.id, openSlot)
                                when (val t = Transcribe.of(wav, keys.ring("assemblyai"))) {
                                    is Transcribe.Result.Text -> {
                                        Words(this@MainActivity).put(project.id, openSlot, t.words)
                                    }
                                    is Transcribe.Result.Failed -> {
                                        setStage(t.why)
                                        return@work
                                    }
                                }
                            }
                            setStage("fetching voices…")
                            val ring = keys.ring(chosen)
                            val (list, why) = if (chosen == Engines.SPEECHIFY) {
                                Engines.speechifyVoices(ring)
                            } else {
                                Engines.humeVoices(ring)
                            }
                            runOnUiThread {
                                voices = list
                                project = load(project.id, slotCountNow())
                                // THE REASON, NOT JUST THE ABSENCE. v6 said "no voices came back"
                                // whether the key was missing, Cloudflare had blocked it, the
                                // account was throttled or the ids were simply wrong — which is
                                // why the last bug had to be found from a desk instead of here.
                                stage = why
                            }
                        },
                    )
                },
                onPreview = { v ->
                    work({ stage = it }) {
                        setStage("preparing ${v.name}…")
                        val bytes = if (v.preview != null) {
                            Net.bytes(v.preview)
                        } else {
                            val words = project.slot(openSlot).words.ifBlank { "This is my voice." }
                            Engines.speak(v, words, keys.ring(v.engine)).first
                        }
                        if (bytes == null) {
                            setStage("could not hear ${v.name}")
                        } else {
                            val f = File(cacheDir, "preview.audio")
                            f.writeBytes(bytes)
                            runOnUiThread { stage = "" ; playFile(f) }
                        }
                    }
                },
                onUse = { v ->
                    work({ stage = it }) {
                        val words = project.slot(openSlot).words
                        if (words.isBlank()) {
                            setStage("nothing transcribed yet")
                            return@work
                        }
                        setStage("generating ${v.name}…")
                        val (bytes, why) = Engines.speak(v, words, keys.ring(v.engine))
                        if (bytes == null) {
                            setStage(why)
                            return@work
                        }
                        // BESIDE, NEVER ON TOP. The generated file has a different name in a
                        // different directory from the recording, so no engine string and no loop
                        // index can make one become the other.
                        val out = Paths.generated(filesDir, project.id, openSlot, v.engine)
                        out.parentFile?.mkdirs()
                        out.writeBytes(bytes)
                        Words(this@MainActivity).setVoice(project.id, openSlot, v.engine)
                        runOnUiThread {
                            project = load(project.id, slotCountNow())
                            stage = "${v.name} saved. Your recording is untouched."
                        }
                    }
                },
                onRevert = {
                    // THE RECORDING WAS NEVER REPLACED. This does not restore anything; it stops
                    // pointing at the generated file. The original has been sitting there the
                    // whole time under a different name.
                    Words(this@MainActivity).setVoice(project.id, openSlot, null)
                    project = load(project.id, slotCountNow())
                    stage = "back to your own recording"
                },
                onEdit = {
                    editorFor = openSlot
                    optionsFor = null
                },
                onSave = {
                    pendingSave = Paths.original(filesDir, project.id, openSlot)
                    saveSample.launch("${project.name}-%02d.wav".format(openSlot + 1))
                },
                onLoop = {
                    if (Looper.slot == openSlot) {
                        Looper.stop()
                        stage = ""
                    } else {
                        // Stop ordinary playback first. Two things sounding at once is not a
                        // feature, and the loop is the one that was just asked for.
                        player?.let { runCatching { it.stop() }; it.release() }
                        player = null
                        playing = null
                        val why = Looper.start(
                            Paths.original(filesDir, project.id, openSlot),
                            openSlot,
                            Words(this@MainActivity).trim(project.id, openSlot),
                        )
                        stage = why.ifBlank { "looping" }
                    }
                },
                onBack = { optionsFor = null },
            )
            return
        }

        if (showSettings) {
            // A SCREEN, NOT A SHEET OVER THE GRID. design-language.md: a panel that covers the
            // thing it configures leaves nowhere to look while deciding, and v5 of the stopwatch
            // shipped exactly that and had no way back out of it in landscape.
            SettingsScreen(
                playMode = playMode,
                slotCount = slotCount,
                usage = vault.usageOf(project.id),
                keySummary = keys.summary(),
                keysHeld = keys.all().map { it.providerId }.toSet(),
                onKeys = {
                    keyRows = keys.rows()
                    keyBusy = ""
                    showKeys = true
                },
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
                                // RECORDING OVER A TAKE ASKS FIRST. One finger, one small tile
                                // in a grid of thirty, and the thing on the other side of the
                                // mistake cannot be made again.
                                is Press.ConfirmOverwrite -> confirmOverwrite = p.slot
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
                            // A LONG PRESS NOW OPENS A MENU RATHER THAN DESTROYING A TAKE. The
                            // gesture that deletes and the gesture that opens options must not be
                            // the same gesture, now that there is more than one thing a cell can
                            // be asked to do.
                            when (val p = Gesture.longPress(mode, project, slot.index)) {
                                is Press.Clear -> {
                                    optionsFor = p.slot
                                    stage = ""
                                    engine = null
                                    voices = emptyList()
                                }
                                is Press.Refused -> message = p.why
                                else -> Unit
                            }
                        },
                    )
                }
              }
            }

            val overwrite = confirmOverwrite
            if (overwrite != null) {
                ConfirmBar(
                    question = "Record over cell ${overwrite + 1}? The take there is deleted.",
                    onCancel = { confirmOverwrite = null },
                    onOk = {
                        confirmOverwrite = null
                        if (!hasMic()) {
                            askMic.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            beginRecording(overwrite) { loaded, said ->
                                project = loaded
                                recordingSlot = null
                                message = said
                            }
                            recordingSlot = overwrite
                            message = ""
                        }
                    },
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Seq and Voice used to sit here and did nothing but print a promise. A
                // control that announces a future version is a control that has to be pressed to
                // find that out, and it was taking a third of the row. Changing a voice lives on
                // the cell it belongs to, reached by a long press; there is nothing on the main
                // screen that needs a global voice button.
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
        val f = Paths.playing(filesDir, project.id, slot, project.slot(slot).voice)
        if (!f.isFile) { onSlot(null); return }
        // THE IN AND OUT POINTS ARE APPLIED HERE, AND ONLY HERE. Nothing was cut from the file,
        // so if this is skipped the editor is a screen that changes nothing. The out point is a
        // watched deadline rather than a seek, because MediaPlayer has no concept of stopping
        // early: it plays to the end of the file unless something stops it.
        val t = Words(this).trim(project.id, slot)
        val lengthMs = project.slot(slot).lengthMs
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            prepare()
            if (t.inMs > 0) seekTo(t.inMs)
            setOnCompletionListener {
                val next = nextInPlayback(project, slot, mode)
                if (next == null) onSlot(null) else startPlaying(next, project, mode, onSlot)
            }
            start()
        }
        onSlot(slot)
        val end = t.endOf(lengthMs)
        if (t.isSet(lengthMs)) stopAt(slot, end, project, mode, onSlot)
    }

    /**
     * WATCH FOR THE OUT POINT AND STOP THERE.
     *
     * `MediaPlayer` has no concept of stopping early: it plays a file to its end. Since nothing is
     * ever cut from the recording, the out point has to be enforced by watching the clock — which
     * is why the editor could exist at all without touching the audio.
     *
     * The poll is generous at 40ms. A few tens of milliseconds past the point is inaudible; the
     * thing that must not happen is running on into the click of the second press, and that is
     * hundreds of milliseconds wide.
     */
    private fun stopAt(
        slot: Int,
        endMs: Int,
        project: Project,
        mode: PlayMode,
        onSlot: (Int?) -> Unit,
    ) {
        val watched = player ?: return
        Thread {
            // A COUNTED LOOP, NOT `while (true)`. G5 refuses the second kind and is right to: a
            // loop that ends because of a condition in the middle stops ending when somebody edits
            // the middle. The ceiling is the recording's own length plus a second, so the watcher
            // cannot outlive the thing it is watching even if every exit below is wrong.
            val ticks = (endMs / POLL_MS) + 25
            for (unused in 0..ticks) {
                Thread.sleep(POLL_MS.toLong())
                val p = player
                if (p !== watched) return@Thread
                val position = runCatching { p.currentPosition }.getOrNull() ?: return@Thread
                if (!runCatching { p.isPlaying }.getOrDefault(false)) return@Thread
                if (position < endMs) continue
                runOnUiThread {
                    if (player !== watched) return@runOnUiThread
                    runCatching { watched.stop() }
                    watched.release()
                    player = null
                    val next = nextInPlayback(project, slot, mode)
                    if (next == null) onSlot(null) else startPlaying(next, project, mode, onSlot)
                }
                return@Thread
            }
        }.start()
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
                Words(this@MainActivity).clear(DEFAULT_PROJECT, slot)
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
        val words = Words(this)
        val slots = (0 until count).map { i ->
            val f = Paths.original(filesDir, id, i)
            val gen = File(Paths.slotDir(filesDir, id, i), "gen")
                .listFiles()?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()
            Slot(
                index = i,
                hasOriginal = f.isFile,
                words = words.get(id, i),
                generated = gen,
                voice = words.voice(id, i),
                lengthMs = Recorder.lengthMs(f),
            )
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
        /** How often the out point is checked. Tens of milliseconds late is inaudible. */
        const val POLL_MS = 40

        const val DEFAULT_PROJECT = "project-01"

        /** Three across, ten down, which puts the whole set on one screen. */
        const val COLUMNS = 3

        /** Thirty-two, which is what a third of a screen width can show honestly. */
        const val WAVEFORM_BUCKETS = 32
    }
}
