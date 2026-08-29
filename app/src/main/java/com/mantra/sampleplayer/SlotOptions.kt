package com.mantra.sampleplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.background
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
 * v5 made a long press delete the recording outright. That was fine while delete was the only
 * thing a cell could be asked to do; it is wrong now, because a gesture that destroys a take
 * should not be the same gesture that opens a menu. Long press opens this, and delete is a
 * labelled control inside it.
 *
 * TRANSCRIPTION IS NOT A STEP HERE, and that is deliberate. The brief had a Transcribe button and
 * Baba's instruction replaced it: nobody wants a transcript, they want a different voice, and the
 * transcript is something the app needs in order to give them one. So it happens on the way, with
 * a line saying what is going on, and it is never asked for.
 */
@Composable
fun SlotOptions(
    slot: Slot,
    stage: String,
    voices: List<Voice>,
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
    onPreview: (Voice) -> Unit,
    onUse: (Voice) -> Unit,
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
        Text(
            "Cell %02d".format(slot.index + 1),
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 10.dp),
        )

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

        // ── TRANSCRIBE ───────────────────────────────────────────────────────────────────────
        //
        // ITS OWN ACTION NOW, NOT A SIDE EFFECT OF CHOOSING A VOICE. It still happens on the way
        // to a voice when it has to, so nobody is made to do it twice — but asking for it
        // directly is a different thing being asked for. What comes back is the title of the cell,
        // in full: a cell called "Danas" tells you which cell it is, and a cell called "Danas je
        // lijep dan" tells you what is in it.
        Button("Transcribe this cell", Modifier.fillMaxWidth()) { onTranscribe() }
        Text(
            "The words become the title of the cell, exactly as spoken.",
            color = Color(0xFF71717A),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(12.dp))

        // ── LOOP ─────────────────────────────────────────────────────────────────────────────
        Button(
            label = if (looping) "Stop looping" else "Loop this cell",
            modifier = Modifier.fillMaxWidth(),
            solid = looping,
            accent = PLAY_AMBER,
        ) { onLoop() }
        Text(
            "Held in memory and repeated with no gap at the join, between the playback points. " +
                "It loops your own recording, not a generated voice.",
            color = Color(0xFF71717A),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(12.dp))

        // ── SAVE ─────────────────────────────────────────────────────────────────────────────
        Button("Save this recording to a file", Modifier.fillMaxWidth()) { onSave() }
        Text(
            "The original WAV, untrimmed and exactly as recorded. Choose where it goes.",
            color = Color(0xFF71717A),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(12.dp))

        // ── EDIT ─────────────────────────────────────────────────────────────────────────────
        Button("Edit playback points", Modifier.fillMaxWidth()) { onEdit() }
        Text(
            "Drag where this cell starts and stops. Nothing is cut: the recording is untouched " +
                "and the points go back to the ends whenever you want.",
            color = Color(0xFF71717A),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp),
        )

        // ── CHANGE VOICE ─────────────────────────────────────────────────────────────────────
        Text(
            "CHANGE VOICE",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )
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
        if (!canSpeechify || !canHume) {
            Text(
                "An engine with no key is not offered. Import keys in settings.",
                color = Color(0xFF71717A),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ── THE VOICES ───────────────────────────────────────────────────────────────────────
        //
        // A NAME IS NOT A CHOICE. Every voice can be heard before it is picked: Speechify publishes
        // a preview clip, and Hume does not, so a Hume voice is auditioned by speaking THIS cell's
        // own words in it — which is the better audition anyway, because the question was never
        // what the voice sounds like, it was what this line sounds like in it.
        if (voices.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            for (v in voices) {
                Row(
                    Modifier.padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(v.name, Modifier.weight(1f)) { onPreview(v) }
                    Button("Use", Modifier.height(42.dp).padding(0.dp)) { onUse(v) }
                }
            }
        }

        if (hasGenerated) {
            Spacer(Modifier.height(14.dp))
            Button("Play my own recording again", Modifier.fillMaxWidth()) { onRevert() }
            Text(
                "Your recording was never replaced. The generated voice sits beside it in a " +
                    "different file, and this puts the cell back to yours.",
                color = Color(0xFF71717A),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ── DELETE ───────────────────────────────────────────────────────────────────────────
        Spacer(Modifier.height(20.dp))
        Button("Delete this recording", Modifier.fillMaxWidth(), accent = RECORDING_RED) {
            onDelete()
        }
        Text(
            "There is no undo, and there is not meant to be one.",
            color = Color(0xFF71717A),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(20.dp))
        Button("Back", Modifier.fillMaxWidth()) { onBack() }
        Spacer(Modifier.height(20.dp))
    }
}
