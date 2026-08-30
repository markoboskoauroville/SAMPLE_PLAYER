package com.mantra.sampleplayer

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
 * WHAT A LONG PRESS OPENS.
 *
 * THE CONTROLS FIRST, ALL OF THEM, THEN THE HELP IN ONE BLOCK AT THE BOTTOM.
 *
 * Every button used to carry two or three lines of explanation directly underneath it. Read once
 * that is useful; read the second time it is noise, and by the tenth time it is a screen four times
 * taller than it needs to be with the control you want somewhere in the middle of it. Somebody who
 * wants the explanation can scroll to the end and it is all in one place. Somebody who knows what
 * the buttons do — which after a week is everybody — never sees it.
 *
 * A long press opens this rather than deleting, because the gesture that destroys a take should not
 * be the gesture that opens a menu.
 *
 * TRANSCRIPTION IS NOT A STEP HERE. It is offered as an action of its own, and it also happens on
 * the way to a voice when it has to. Nobody wants a transcript; they want a different voice, and
 * the transcript is what the app needs in order to give them one.
 */
@Composable
fun SlotOptions(
    slot: Slot,
    stage: String,
    voiceCount: Int,
    engine: String?,
    canSpeechify: Boolean,
    canHume: Boolean,
    hasGenerated: Boolean,
    looping: Boolean,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onLoop: () -> Unit,
    onTranscribe: () -> Unit,
    onEngine: (String) -> Unit,
    onRevert: () -> Unit,
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
        ScreenHeader("Cell %02d".format(slot.index + 1), onBack)

        if (slot.words.isNotBlank()) {
            Text(
                slot.words,
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        if (stage.isNotBlank()) {
            Text(
                stage,
                color = PLAY_AMBER,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // ── THE CONTROLS ─────────────────────────────────────────────────────────────────────

        // Edit is the first action and the one reached most often, so it takes two thirds of the
        // row. The loop flag is one glyph: ∞ already says what it is, and "Loop is on for this
        // cell" was a full-width button saying in six words what the symbol says in one character.
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button("Edit playback points", Modifier.weight(2f)) { onEdit() }
            Button(
                label = "\u221e",
                modifier = Modifier.weight(1f),
                solid = slot.loop,
                accent = PLAY_AMBER,
            ) { onLoop() }
        }

        Button("Transcribe", Modifier.fillMaxWidth()) { onTranscribe() }
        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                label = "Speechify",
                modifier = Modifier.weight(1f),
                solid = engine == Engines.SPEECHIFY,
                accent = if (canSpeechify) PLAY_AMBER else Color(0xFF3F3F46),
            ) { if (canSpeechify) onEngine(Engines.SPEECHIFY) }
            Button(
                label = "Hume",
                modifier = Modifier.weight(1f),
                solid = engine == Engines.HUME,
                accent = if (canHume) PLAY_AMBER else Color(0xFF3F3F46),
            ) { if (canHume) onEngine(Engines.HUME) }
        }

        if (hasGenerated) {
            Spacer(Modifier.height(6.dp))
            Button("Play my own recording again", Modifier.fillMaxWidth()) { onRevert() }
        }

        Spacer(Modifier.height(6.dp))
        Button("Save recording to a file", Modifier.fillMaxWidth()) { onSave() }

        Spacer(Modifier.height(16.dp))
        Button("Delete this recording", Modifier.fillMaxWidth(), accent = RECORDING_RED) {
            onDelete()
        }

        // ── THE HELP, ALL OF IT, DOWN HERE ───────────────────────────────────────────────────
        Spacer(Modifier.height(28.dp))
        Text(
            "WHAT THESE DO",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(6.dp))
        Help("Edit playback points", "Drag where this cell starts and stops. Nothing is cut: the recording is untouched and the points go back to the ends whenever you want.")
        Help("\u221e", "A flag, not a start button. A marked cell carries \u221e on the grid, and in play mode one press starts it looping and the next stops it. Held in memory and repeated with no gap at the join, between the playback points.")
        Help("Transcribe", "The words become the title of the cell, exactly as spoken. It also happens on its own on the way to a voice, so you are never asked to do it twice.")
        Help("Speechify / Hume", "Opens the voice chooser for that engine. " + if (voiceCount > 0) "$voiceCount voices loaded." else "An engine with no key is not offered; import keys in settings.")
        Help("Play my own recording again", "Your recording was never replaced. The generated voice sits beside it in a different file, and this points the cell back at yours.")
        Help("Save recording to a file", "The original WAV, untrimmed and exactly as recorded. You choose where it goes.")
        Help("Delete this recording", "There is no undo, and there is not meant to be one.")
        Spacer(Modifier.height(24.dp))
    }
}

/** One help entry: what it is called, and what it does. */
@Composable
fun Help(label: String, text: String) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(
            label,
            color = Color(0xFFCBD5E1),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text,
            color = Color(0xFF71717A),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
