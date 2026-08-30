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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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

    private var onTextFile: ((String) -> Unit)? = null

    /**
     * A TEXT FILE TO BE READ ALOUD.
     *
     * The other direction through the app: instead of speaking a line and having it transcribed,
     * hand it words and have a voice say them. `OpenDocument` rather than a text box, for the same
     * reason keys arrive as a file — a phone keyboard is the thing this app exists to avoid.
     *
     * `text/*` first but `*/ *` accepted, because a note exported from a phone arrives as
     * `application/octet-stream` about as often as not, and a picker that shows nothing is
     * indistinguishable from a picker that is broken.
     */
    private val pickTextFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        onTextFile?.invoke(text.orEmpty())
    }

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
        Player.stop()
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

    /**
     * Speak one short line in one voice and play it, off the main thread.
     *
     * Shared by the card's plain preview and its emotion grid so that both go through the same
     * ring, the same rest-and-retry and the same failure message. Two copies would drift, and the
     * one used less would be the drifted one.
     */
    private fun hearVoice(
        v: VoiceInfo,
        line: String,
        direction: String,
        onStage: (String) -> Unit,
    ) {
        work(onStage) {
            setStage("hearing ${v.name}…")
            val bytes = if (v.preview != null && direction.isBlank()) {
                // Speechify publishes a clip: free, instant, and the same performance every time.
                Net.bytes(v.preview)
            } else {
                Engines.speak(
                    Voice(v.engine, v.id, v.name, v.model),
                    line,
                    keys.ring(v.engine),
                    direction,
                ).first
            }
            if (bytes == null) {
                setStage("could not hear ${v.name}")
            } else {
                val f = File(cacheDir, "preview.audio")
                f.writeBytes(bytes)
                runOnUiThread { onStage("") ; playFile(f) }
            }
        }
    }

    /**
     * Play an arbitrary audio file, used only for voice previews.
     *
     * STILL `MediaPlayer`, AND DELIBERATELY. A Speechify preview clip comes off their CDN as an
     * mp3, so it needs a decoder — [Player] takes PCM out of a WAV and would have nothing to do
     * with it. Nothing about the trim applies here either: a preview is a whole short clip.
     */
    /**
     * Speak the cell's own text in the voice it is already set to.
     *
     * Used when a text file is read into a cell that already has a voice. Choosing a voice from
     * the chooser takes the other path and both end in the same place, which is deliberate: one
     * of them would otherwise drift and it would be this one, used less.
     */
    private fun speakCurrentText(slot: Int, engineId: String, onStage: (String) -> Unit) {
        work(onStage) {
            val words = Words(this).get(DEFAULT_PROJECT, slot)
            if (words.isBlank()) {
                setStage("nothing to read")
                return@work
            }
            setStage("reading it in the $engineId voice…")
            val chosen = Voice(engineId, voiceIdFor(slot, engineId), engineId)
            if (chosen.id.isBlank()) {
                setStage("this cell has no voice chosen yet")
                return@work
            }
            val (bytes, why) = Engines.speak(chosen, words, keys.ring(engineId))
            if (bytes == null) {
                setStage(why)
                return@work
            }
            val out = Paths.generated(filesDir, DEFAULT_PROJECT, slot, engineId)
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
            runOnUiThread { onStage("read into cell ${slot + 1}") }
        }
    }

    /** Which voice this cell last used on that engine, remembered beside the cell. */
    private fun voiceIdFor(slot: Int, engineId: String): String =
        Words(this).voiceId(DEFAULT_PROJECT, slot).orEmpty().let {
            if (it.startsWith("$engineId/")) it.removePrefix("$engineId/") else ""
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
        var pageSpread by remember { mutableStateOf(prefs.pageSpread) }
        var waveScale by remember { mutableStateOf(prefs.waveScale) }
        var waveColour by remember { mutableStateOf(prefs.waveColourIndex) }
        var starred by remember { mutableStateOf(prefs.starredVoices) }
        var chooserFor by remember { mutableStateOf<Int?>(null) }
        var catalogue by remember { mutableStateOf(emptyList<VoiceInfo>()) }
        var direction by remember { mutableStateOf("") }
        var cardFor by remember { mutableStateOf<VoiceInfo?>(null) }
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
        // Mirrored into composition state, because Looper.slot is a plain field and nothing
        // would redraw the mark when it changed.
        var loopingSlot by remember { mutableStateOf(Looper.slot) }
        var stage by remember { mutableStateOf("") }
        var engine by remember { mutableStateOf<String?>(null) }
        var playMode by remember { mutableStateOf(prefs.playMode) }
        val layout = Grid.of(project.size, pageSpread)
        val pagerState = rememberPagerState(pageCount = { project.pages(pageSpread) })

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

        // THE PLAYHEAD ASKS THE PLAYER WHERE IT IS, and the player counts frames of the region
        // it is actually sounding. Fraction of the REGION, not of the file, so a trimmed cell's
        // mark crosses the part being heard rather than a slice of the middle.
        LaunchedEffect(playing) {
            while (playing != null && Player.isPlaying) {
                fraction = Player.fraction()
                delay(60)
            }
        }

        val choosing = chooserFor
        val card = cardFor
        if (card != null && choosing != null) {
            VoiceCard(
                voice = card,
                starred = VoiceSearch.key(card) in starred,
                direction = direction,
                playing = stage,
                onStar = {
                    val k = VoiceSearch.key(card)
                    starred = if (k in starred) starred - k else starred + k
                    prefs.starredVoices = starred
                },
                onPreviewPlain = { hearVoice(card, "This is ${card.name}.", direction) { stage = it } },
                onPreviewDirection = { d ->
                    // THE PREVIEW SAYS THE NAME AND THE EMOTION, nothing longer. Eight emotions is
                    // eight calls and Hume paces at about twelve seconds, so a full sentence would
                    // be two minutes of waiting to hear four seconds of difference.
                    hearVoice(card, Emotions.previewLine(card.name, d), d.text) { stage = it }
                },
                onDirection = { direction = it },
                onUse = {
                    work({ stage = it }) {
                        val words = project.slot(choosing).words
                        if (words.isBlank()) {
                            setStage("transcribe this cell first")
                            return@work
                        }
                        setStage("generating ${card.name}…")
                        val (bytes, why) = Engines.speak(
                            Voice(card.engine, card.id, card.name, card.model),
                            words,
                            keys.ring(card.engine),
                            direction,
                        )
                        if (bytes == null) {
                            setStage(why)
                            return@work
                        }
                        val out = Paths.generated(filesDir, project.id, choosing, card.engine)
                        out.parentFile?.mkdirs()
                        out.writeBytes(bytes)
                        Words(this@MainActivity).setVoice(project.id, choosing, card.engine)
                        Words(this@MainActivity).setVoiceId(
                            project.id,
                            choosing,
                            VoiceSearch.key(card),
                        )
                        runOnUiThread {
                            project = load(project.id, slotCountNow())
                            stage = "${card.name} saved. Your recording is untouched."
                            cardFor = null
                            chooserFor = null
                        }
                    }
                },
                onClose = { cardFor = null },
            )
            return
        }

        if (choosing != null) {
            VoiceChooser(
                engine = engine ?: Engines.SPEECHIFY,
                voices = catalogue,
                loading = stage,
                starred = starred,
                onSearchChanged = { },
                onOpen = { cardFor = it },
                onStar = { v ->
                    val k = VoiceSearch.key(v)
                    starred = if (k in starred) starred - k else starred + k
                    prefs.starredVoices = starred
                },
                onClose = { chooserFor = null },
            )
            return
        }

        val editing = editorFor
        if (editing != null) {
            val slotNow = project.slot(editing)
            var t by remember(editing) { mutableStateOf(Words(this).trim(project.id, editing)) }
            WaveEditor(
                slot = slotNow,
                // The editor draws one recording across a whole screen, so it always takes
                // the finest the setting allows: the cost that made this a setting is
                // thirty cells at once, and here there is one.
                // The editor draws the ORIGINAL even when a voice is chosen, because the
                // points it sets belong to the recording. Drawing the generated audio here would
                // be dragging handles across one waveform and applying them to another.
                waveform = waveformOf(project.id, editing, WAVEFORM_SCALES.last()),
                waveTint = Color(waveColour(waveColour)),
                trim = t,
                playhead = if (playing == editing) fraction else null,
                onTrim = {
                    t = it
                    Words(this).setTrim(project.id, editing, it)
                },
                onPreview = { startPlaying(editing, project, PlayMode.SINGLE) { playing = it } },
                onBack = {
                    // THE NEW POINTS TAKE EFFECT IMMEDIATELY, WITHOUT STOPPING.
                    //
                    // Closing the editor while the set is playing used to leave the old in and out
                    // running until the person stopped and started again — which means the change
                    // they just made was not the change they were listening to. If this cell is
                    // the one sounding, playback restarts from the new in point; if a loop is
                    // running on it, the loop is rebuilt from the new region. Everything else is
                    // left alone.
                    val wasPlaying = playing == editing
                    val wasLooping = Looper.slot == editing
                    Player.stop()
                    playing = null
                    editorFor = null
                    project = load(project.id, slotCountNow())
                    if (wasLooping) {
                        Looper.stop()
                        Looper.start(
                            Paths.playing(
                                filesDir,
                                project.id,
                                editing,
                                project.slot(editing).voice,
                            ),
                            editing,
                            Words(this@MainActivity).trim(project.id, editing),
                        )
                        loopingSlot = Looper.slot
                    } else if (wasPlaying) {
                        startPlaying(editing, project, playMode) { playing = it }
                    }
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
                voiceCount = catalogue.size,
                engine = engine,
                canSpeechify = keys.has(Engines.SPEECHIFY),
                canHume = keys.has(Engines.HUME),
                hasGenerated = project.slot(openSlot).generated.isNotEmpty(),
                looping = loopingSlot == openSlot,
                onDelete = {
                    Paths.slotDir(filesDir, project.id, openSlot)
                        .walkTopDown().filter { it.isFile }.forEach { it.delete() }
                    project = load(project.id, slotCountNow())
                    message = "cell ${openSlot + 1} deleted"
                    optionsFor = null
                },
                onEngine = { chosen ->
                    engine = chosen
                    catalogue = emptyList()
                    // TRANSCRIBE ON THE WAY, NEVER AS A STEP. Nobody wants a transcript; they want
                    // a different voice, and the transcript is what the app needs to give them
                    // one. It happens here, with a line saying what is going on.
                    work(
                        onStage = { stage = it },
                        job = {
                            if (project.slot(openSlot).words.isBlank()) {
                                setStage("transcribing…")
                                val said = transcribeSlot(project.id, openSlot)
                                if (said.isNotBlank()) {
                                    setStage(said)
                                    return@work
                                }
                            }
                            setStage("fetching the ${chosen} catalogue…")
                            val ring = keys.ring(chosen)
                            val (list, why) = if (chosen == Engines.SPEECHIFY) {
                                Catalogue.speechify(ring)
                            } else {
                                Catalogue.hume(ring)
                            }
                            runOnUiThread {
                                catalogue = list
                                project = load(project.id, slotCountNow())
                                // THE REASON, NOT JUST THE ABSENCE.
                                stage = why
                                if (list.isNotEmpty()) {
                                    chooserFor = openSlot
                                    optionsFor = null
                                }
                            }
                        },
                    )
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
                onTranscribe = {
                    work({ stage = it }) {
                        setStage("transcribing…")
                        val said = transcribeSlot(project.id, openSlot)
                        runOnUiThread {
                            project = load(project.id, slotCountNow())
                            stage = said
                        }
                    }
                },
                onReadText = {
                    onTextFile = { raw ->
                        val words = Text.forSpeaking(raw)
                        if (words.isBlank()) {
                            stage = "nothing readable in that file"
                        } else {
                            Words(this@MainActivity).put(project.id, openSlot, words)
                            project = load(project.id, slotCountNow())
                            val voice = project.slot(openSlot).voice
                            if (voice == null) {
                                // NO VOICE YET, SO ASK FOR ONE. The engine buttons are directly
                                // below this message and choosing one leads to the chooser, whose
                                // Use already speaks whatever text the cell holds — so the
                                // imported words are read the moment a voice is picked, with no
                                // second path to keep in step.
                                stage = Text.report(raw) +
                                ". Now pick Speechify or Hume to read it."
                            } else {
                                speakCurrentText(openSlot, voice) { stage = it }
                            }
                        }
                    }
                    pickTextFile.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
                },
                onSave = {
                    pendingSave = Paths.original(filesDir, project.id, openSlot)
                    saveSample.launch("${project.name}-%02d.wav".format(openSlot + 1))
                },
                onLoop = {
                    // A FLAG, NOT A START. Marking it here and pressing it on the grid keeps the
                    // stop where the start was: v9 started the sound from inside this menu, so the
                    // only way to stop it was to find the same cell and open the same menu again.
                    val now = !project.slot(openSlot).loop
                    Words(this@MainActivity).setLoop(project.id, openSlot, now)
                    if (!now && Looper.slot == openSlot) Looper.stop()
                    project = load(project.id, slotCountNow())
                    stage = if (now) "marked to loop" else "loop off"
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
                version = BuildConfig.VERSION_NAME,
                playMode = playMode,
                slotCount = slotCount,
                pageSpread = pageSpread,
                waveScale = waveScale,
                waveColour = waveColour,
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
                onWaveColour = {
                    waveColour = it
                    prefs.waveColourIndex = it
                },
                onWaveScale = {
                    waveScale = it
                    prefs.waveScale = it
                },
                onPageSpread = {
                    pageSpread = it
                    prefs.pageSpread = it
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
                    val pageLabel =
                        Paging.label(pagerState.currentPage, project.size, layout.perPage)
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
              // A PAGE IS A FIXED GRID THAT FILLS THE SPACE, not a list that scrolls.
              //
              // LazyVerticalGrid gives every cell the height its content asks for and scrolls the
              // rest. Here the number of rows is known before anything is drawn, so each row takes
              // an equal share of what is left after the header and the controls, and two cells on
              // a page really are halves of the page.
              val onThisPage =
                  Paging.slotsOn(page, project.size, layout.perPage).map { project.slot(it) }
              Column(Modifier.fillMaxSize()) {
                for (rowIndex in 0 until layout.rows) {
                  Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (colIndex in 0 until layout.columns) {
                      val at = rowIndex * layout.columns + colIndex
                      if (at >= onThisPage.size) {
                        // An empty place on the last row keeps its share of the width, so the
                        // cells beside it stay the size of every other cell rather than stretching
                        // to swallow the gap.
                        Spacer(Modifier.weight(1f))
                        continue
                      }
                      val slot = onThisPage[at]
                      Tile(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        slot = slot,
                        playhead = if (playing == slot.index) fraction else null,
                        recording = recordingSlot == slot.index,
                        looping = loopingSlot == slot.index,
                        waveTint = Color(waveColour(waveColour)),
                        // While this tile is recording it draws the shape arriving, not the file
                        // on disk, which does not exist yet.
                        waveform = if (recordingSlot == slot.index) {
                            liveShape
                        } else {
                            waveformOf(project.id, slot.index, waveScale, slot.voice)
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
                                is Press.SeekTo ->
                                    startPlaying(p.slot, project, playMode) { playing = it }
                                is Press.ToggleLoop -> {
                                    if (Looper.slot == p.slot) {
                                        Looper.stop()
                                        message = ""
                                    } else {
                                        // Two things sounding at once is not a feature, and the
                                        // loop is the one that was just asked for.
                                        Player.stop()
                                        playing = null
                                        // LOOPS WHAT THE CELL PLAYS. It could only ever loop
                                        // the original while Speechify returned mp3; both engines
                                        // return WAV now, so the loop is the thing being heard.
                                        val why = Looper.start(
                                            Paths.playing(
                                                filesDir,
                                                project.id,
                                                p.slot,
                                                project.slot(p.slot).voice,
                                            ),
                                            p.slot,
                                            Words(this@MainActivity).trim(project.id, p.slot),
                                        )
                                        message = why
                                    }
                                    loopingSlot = Looper.slot
                                }
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
                                    catalogue = emptyList()
                                }
                                is Press.Refused -> message = p.why
                                else -> Unit
                            }
                        },
                      )
                    }
                  }
                }
              }
            }

            val overwrite = confirmOverwrite
            if (overwrite != null) {
                ConfirmBar(
                    question = "Cell ${overwrite + 1} already has a take. Play it, or record over it?",
                    onCancel = { confirmOverwrite = null },
                    onPlay = {
                        // WHAT THEY ALMOST CERTAINLY MEANT. Switching the mode as well as playing
                        // is the point: leaving it in REC would ask the same question on the next
                        // cell, having just been told the answer.
                        confirmOverwrite = null
                        armed = Mode.PLAYING
                        startPlaying(overwrite, project, playMode) { playing = it }
                    },
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
                // THE CONTROLS TAKE A CORNER, NOT A ROW.
                //
                // REC was full width, which is a lot of glass for a button pressed once per take,
                // on a screen whose whole job is showing cells. It is about a fifth of the width
                // now and it sits bottom right where a thumb already is. Everything it gave up
                // went to the cells above it.
                //
                // Seq and Voice used to be here too and did nothing but print a promise.
                Spacer(Modifier.weight(1f))
                Button(
                    label = if (armed == Mode.PLAYING) "PLAY" else "REC",
                    modifier = Modifier.weight(0.32f),
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
                    Player.stop()
                    playing = null
                    armed = if (armed == Mode.PLAYING) Mode.STOPPED else Mode.PLAYING
                    message = ""
                }
                // The gear last, and narrow. It is the control pressed least often in the app and
                // it sits at the end of the row for that reason, not because there was space left.
                Button("\u2699", Modifier.width(44.dp)) { showSettings = true }
            }
        }
    }

    /**
     * Play one cell between its points, and move on when it ends.
     *
     * THE IN AND OUT POINTS ARE APPLIED BY CUTTING THE REGION, NOT BY SEEKING. See [Player] for
     * why: `MediaPlayer.seekTo` is asynchronous, so the seek had not landed when playback started
     * and the file played from zero — and while the seek settled `isPlaying` reported false, which
     * made the out-point watcher decide playback had finished and stop watching. One asynchronous
     * call broke both ends of the trim, silently, because a cell playing start to finish is
     * exactly what an untrimmed cell does.
     */
    private fun startPlaying(
        slot: Int,
        project: Project,
        mode: PlayMode,
        onSlot: (Int?) -> Unit,
    ) {
        val f = Paths.playing(filesDir, project.id, slot, project.slot(slot).voice)
        if (!f.isFile) { onSlot(null); return }
        val why = Player.play(f, slot, Words(this).trim(project.id, slot)) {
            runOnUiThread {
                val next = nextInPlayback(project, slot, mode)
                if (next == null) onSlot(null) else startPlaying(next, project, mode, onSlot)
            }
        }
        if (why.isNotBlank()) {
            onSlot(null)
            return
        }
        onSlot(slot)
    }


    /**
     * Transcribe one cell and store the words. Returns a reason when it failed, or empty.
     *
     * ONE PATH, TWO DOORS. Transcribe is now an action of its own and it is also what happens on
     * the way to choosing a voice. Two copies of an upload-submit-poll would drift, and the half
     * that drifted would be the one used less.
     */
    private fun transcribeSlot(projectId: String, slot: Int): String {
        val wav = Paths.original(filesDir, projectId, slot)
        if (!wav.isFile) return "nothing recorded in that cell"
        return when (val t = Transcribe.of(wav, keys.ring("assemblyai"))) {
            is Transcribe.Result.Text -> {
                Words(this).put(projectId, slot, t.words)
                ""
            }
            is Transcribe.Result.Failed -> t.why
        }
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

    /**
     * THE SHAPE OF WHAT WILL ACTUALLY SOUND.
     *
     * It drew the original recording whatever the cell was set to play. Choose a voice for a cell
     * and the wave stayed the shape of Baba's take while the audio was somebody else's, saying the
     * same words at a different length — so the playhead crossed a shape that had nothing to do
     * with what was in the room. A picture that disagrees with the sound is worse than no picture.
     *
     * [voice] null means the original, which is also the fallback when a generated file is missing.
     */
    private fun waveformOf(
        id: String,
        slot: Int,
        scale: Int = 1,
        voice: String? = null,
    ): FloatArray {
        val f = Paths.playing(filesDir, id, slot, voice)
        if (!f.isFile) return FloatArray(0)
        return waveform(Recorder.read(f), waveformBuckets(scale))
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
                loop = words.loops(id, i),
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
        const val DEFAULT_PROJECT = "project-01"

        /** Three across, ten down, which puts the whole set on one screen. */
        const val COLUMNS = 3

        // The bucket count moved out to Model.kt, where the setting that scales it lives.
    }
}
