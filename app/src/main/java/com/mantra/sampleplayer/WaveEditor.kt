package com.mantra.sampleplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * THE ONLY EDITOR, AND IT EDITS NOTHING.
 *
 * Two handles on a waveform: where playback starts, where it stops. The recording on disk is not
 * touched, not trimmed, not rewritten. The two numbers live beside it and can be dragged back to
 * the ends at any time, for ever.
 *
 * That is the whole feature and it is deliberately the whole feature. The take is the thing that
 * cannot be made again, and an editor that cuts is one wrong drag away from destroying the head of
 * a phrase on a phone with no undo.
 *
 * WHAT IT IS ACTUALLY FOR: the breath before the first word, and the click of the second press at
 * the end. Those two are on nearly every take, and moving past them is the difference between a
 * set that plays as speech and a set that plays as thirty separate recordings.
 */
private const val HANDLE_PX = 5f

@Composable
fun WaveEditor(
    slot: Slot,
    waveform: FloatArray,
    trim: Trim,
    playhead: Float?,
    onTrim: (Trim) -> Unit,
    onPreview: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    val length = slot.lengthMs
    var width by remember { mutableStateOf(1f) }
    var draggingIn by remember { mutableStateOf(true) }

    // Wide enough to see and to put a finger near. Three pixels was neither.

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .padding(horizontal = 10.dp),
    ) {
        Text(
            "Cell %02d — playback points".format(slot.index + 1),
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 10.dp),
        )

        if (!Trim.editable(length)) {
            Text(
                "This recording is too short to trim.",
                color = Color(0xFF71717A),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        // THE WAVEFORM, FULL WIDTH AND TALL. This is the one screen where the recording is the
        // subject rather than a thumbnail, so it gets the height the grid could not give it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .border(1.dp, Color(0xFF3F3F46))
                .pointerInput(length) {
                    detectHorizontalDragGestures(
                        // THE SIDE YOU START FROM PICKS THE HANDLE, AND IT HOLDS FOR THE WHOLE
                        // DRAG.
                        //
                        // v7 chose whichever handle was nearer to the finger, which sounded
                        // helpful and is not: once the two points are close together the same
                        // gesture grabs a different end depending on a few pixels, and once you
                        // have dragged one point past the middle it starts answering to the wrong
                        // side of the screen. Left half is the start, right half is the stop, and
                        // it does not change under your finger halfway through.
                        onDragStart = { start -> draggingIn = start.x < width / 2f },
                    ) { change, _ ->
                        if (length <= 0 || width <= 1f) return@detectHorizontalDragGestures
                        val ms = ((change.position.x / width) * length).toInt()
                        onTrim(
                            if (draggingIn) {
                                Trim.withIn(trim, ms, length)
                            } else {
                                Trim.withOut(trim, ms, length)
                            },
                        )
                    }
                }
                .drawBehind {
                    width = size.width
                    val end = trim.endOf(length)
                    val inX = if (length > 0) size.width * trim.inMs / length else 0f
                    val outX = if (length > 0) size.width * end / length else size.width

                    if (waveform.isNotEmpty()) {
                        val w = size.width / waveform.size
                        for (i in waveform.indices) {
                            val h = (waveform[i] * size.height * 0.9f).coerceAtLeast(1f)
                            val x = i * w
                            // Outside the points is dimmed rather than hidden. It is still part of
                            // the recording and it is still there to drag back to.
                            val inside = x >= inX && x <= outX
                            drawRect(
                                color = if (inside) Color(0xFF94A3B8) else Color(0xFF3F3F46),
                                topLeft = Offset(x, (size.height - h) / 2f),
                                size = Size((w * 0.7f).coerceAtLeast(1f), h),
                            )
                        }
                    }
                    // TWO HAIRLINES, TWO COLOURS. YELLOW IN, RED OUT.
                    //
                    // Both were amber and both were three pixels wide, which meant that with the
                    // out point at its default — the end of the recording — the red line sat under
                    // the one-pixel border at the right edge and could not be seen at all. It was
                    // there and it was doing its job; there was simply nothing on screen saying
                    // so, which is indistinguishable from a missing feature.
                    //
                    // So: different colours, wider, and both PULLED INSIDE the box so neither can
                    // hide under an edge.
                    val w = HANDLE_PX
                    val inAt = inX.coerceIn(0f, size.width - w)
                    val outAt = (outX - w).coerceIn(0f, size.width - w)
                    drawRect(PLAY_AMBER, Offset(inAt, 0f), Size(w, size.height))
                    drawRect(RECORDING_RED, Offset(outAt, 0f), Size(w, size.height))
                    // A cap at the top of each, so the two ends are tellable apart at a glance
                    // even when the waveform behind them is busy.
                    drawRect(PLAY_AMBER, Offset(inAt, 0f), Size(w * 3, w * 3))
                    drawRect(RECORDING_RED, Offset(outAt - w * 2, 0f), Size(w * 3, w * 3))
                    if (playhead != null) {
                        drawRect(
                            Color(0xFFFDE68A),
                            Offset(playhead.coerceIn(0f, 1f) * size.width, 0f),
                            Size(2f, size.height),
                        )
                    }
                },
        )

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "▌yellow = in",
                color = PLAY_AMBER,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "▌red = out",
                color = RECORDING_RED,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "in %.2fs   out %.2fs   plays %.2fs of %.2fs".format(
                trim.inMs / 1000f,
                trim.endOf(length) / 1000f,
                trim.durationMs(length) / 1000f,
                length / 1000f,
            ),
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button("Play", Modifier.weight(1f), solid = true, accent = PLAY_AMBER) { onPreview() }
            Button("Whole take", Modifier.weight(1f)) { onReset() }
        }
        Text(
            "Drag from the left half to move the start, from the right half to move the stop. " +
                "Nothing is cut: these are two numbers beside the recording, and Whole take puts " +
                "them back to the ends.",
            color = Color(0xFF71717A),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(20.dp))
        Button("Back", Modifier.fillMaxWidth()) { onBack() }
    }
}
