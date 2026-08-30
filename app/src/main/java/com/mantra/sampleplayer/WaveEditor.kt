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
import androidx.compose.runtime.rememberUpdatedState
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
    waveTint: Color,
    playhead: Float?,
    onTrim: (Trim) -> Unit,
    onPreview: () -> Unit,
    onBack: () -> Unit,
) {
    val length = slot.lengthMs
    var width by remember { mutableStateOf(1f) }
    var draggingIn by remember { mutableStateOf(true) }

    // THE VISIBLE WINDOW, WHICH IS THE WHOLE TAKE UNTIL IT IS NOT.
    //
    // "Whole take" was a button that put both points back to the ends, and it was the wrong thing
    // to spend half a row on: it is one drag to undo by hand and it is not what you want in the
    // middle of trimming. Zoom is. Half a second of breath at the front of a four second take is
    // thirty pixels wide, and thirty pixels is not something a finger can place.
    //
    // THE WINDOW IS FROZEN WHEN ZOOM IS PRESSED, not recomputed from the points as they move.
    // Deriving it live would be circular — dragging the in point would move the window, which
    // would move where the in point appears, which would move it again under the finger.
    var window by remember(slot.index) { mutableStateOf<Pair<Int, Int>?>(null) }

    val winFrom = window?.first ?: 0
    val winTo = window?.second ?: length.coerceAtLeast(1)
    val winSpan = (winTo - winFrom).coerceAtLeast(1)

    // THE IN AND OUT POINTS WERE UNDOING EACH OTHER, AND IT IS THE SAME BUG AS v3's SECOND TAP.
    //
    // `pointerInput` restarts its block only when its key changes, and this one is keyed on the
    // length of the recording, which never changes while the editor is open. So the drag handler
    // was built once and captured `trim` AS IT WAS THEN, for ever. Each drag was computed from
    // that photograph and handed back a whole new pair, throwing the other point away.
    val live by rememberUpdatedState(trim)
    val change by rememberUpdatedState(onTrim)
    val from by rememberUpdatedState(winFrom)
    val span by rememberUpdatedState(winSpan)

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .padding(horizontal = 10.dp),
    ) {
        ScreenHeader("Cell %02d — playback points".format(slot.index + 1), onBack)

        if (!Trim.editable(length)) {
            Text(
                "This recording is too short to trim.",
                color = Color(0xFF71717A),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .border(1.dp, Color(0xFF3F3F46))
                .pointerInput(length) {
                    detectHorizontalDragGestures(
                        // THE SIDE YOU START FROM PICKS THE HANDLE, AND IT HOLDS FOR THE WHOLE
                        // DRAG. Choosing whichever handle is nearer sounds helpful and is not:
                        // once the two points are close the same gesture grabs a different end
                        // depending on a few pixels.
                        onDragStart = { start -> draggingIn = start.x < width / 2f },
                    ) { it, _ ->
                        if (length <= 0 || width <= 1f) return@detectHorizontalDragGestures
                        // Position is read through the window, so a drag means the same thing
                        // zoomed in as zoomed out: where the finger is on the visible waveform.
                        val ms = from + ((it.position.x / width) * span).toInt()
                        change(
                            if (draggingIn) {
                                Trim.withIn(live, ms, length)
                            } else {
                                Trim.withOut(live, ms, length)
                            },
                        )
                    }
                }
                .drawBehind {
                    width = size.width
                    val end = trim.endOf(length)

                    fun xOf(ms: Int): Float =
                        ((ms - winFrom).toFloat() / winSpan) * size.width

                    val inX = xOf(trim.inMs)
                    val outX = xOf(end)

                    if (waveform.isNotEmpty() && length > 0) {
                        // Only the buckets inside the window, stretched across the full width.
                        val firstB = (waveform.size.toLong() * winFrom / length).toInt()
                            .coerceIn(0, waveform.size - 1)
                        val lastB = (waveform.size.toLong() * winTo / length).toInt()
                            .coerceIn(firstB + 1, waveform.size)
                        val shown = lastB - firstB
                        val w = size.width / shown
                        for (i in 0 until shown) {
                            val h = (waveform[firstB + i] * size.height * 0.9f).coerceAtLeast(1f)
                            val x = i * w
                            // Outside the points is dimmed rather than hidden. It is still part of
                            // the recording and it is still there to drag back to.
                            val inside = x >= inX && x <= outX
                            drawRect(
                                color = if (inside) waveTint else Color(0xFF3F3F46),
                                topLeft = Offset(x, (size.height - h) / 2f),
                                size = Size(w.coerceAtLeast(1f), h),
                            )
                        }
                    }

                    // TWO HAIRLINES, TWO COLOURS. YELLOW IN, RED OUT. Both were amber and three
                    // pixels wide, so with the out point at its default the red line sat under the
                    // border at the right edge and could not be seen at all.
                    val hw = HANDLE_PX
                    val inAt = inX.coerceIn(0f, size.width - hw)
                    val outAt = (outX - hw).coerceIn(0f, size.width - hw)
                    drawRect(PLAY_AMBER, Offset(inAt, 0f), Size(hw, size.height))
                    drawRect(RECORDING_RED, Offset(outAt, 0f), Size(hw, size.height))
                    drawRect(PLAY_AMBER, Offset(inAt, 0f), Size(hw * 3, hw * 3))
                    drawRect(RECORDING_RED, Offset(outAt - hw * 2, 0f), Size(hw * 3, hw * 3))

                    // THE PLAYHEAD RUNS BETWEEN THE POINTS, NOT ACROSS THE BOX.
                    //
                    // The player sounds the region and reports a fraction OF THE REGION, and this
                    // drew that fraction across the whole width — so the mark started at the left
                    // edge while the audio started at the in point, and reached the right edge
                    // while the audio was still short of the out point. The picture disagreed with
                    // the sound in the one place built for looking at them together.
                    if (playhead != null) {
                        val at = inX + playhead.coerceIn(0f, 1f) * (outX - inX)
                        drawRect(
                            Color(0xFFFDE68A),
                            Offset(at.coerceIn(0f, size.width - 2f), 0f),
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
            Button(
                label = if (window == null) "Zoom to in / out" else "Zoom out",
                modifier = Modifier.weight(1f),
                solid = window != null,
                accent = PLAY_AMBER,
            ) {
                window = if (window != null) {
                    null
                } else {
                    // A MARGIN EACH SIDE, or the two handles land exactly on the edges of the box
                    // and there is nowhere left to drag them outwards from.
                    val end = trim.endOf(length)
                    val margin = ((end - trim.inMs) / 8).coerceAtLeast(50)
                    (trim.inMs - margin).coerceAtLeast(0) to (end + margin).coerceAtMost(length)
                }
            }
        }

        // ── THE HELP, DOWN HERE ──────────────────────────────────────────────────────────────
        Spacer(Modifier.height(28.dp))
        Text(
            "WHAT THESE DO",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(6.dp))
        Help(
            "The two handles",
            "Drag from the LEFT half of the waveform to move the start, from the RIGHT half to " +
                "move the stop. The side you begin the drag on decides which handle you have, and " +
                "it does not change under your finger halfway through.",
        )
        Help(
            "Play",
            "Plays this cell between the points, once. Closing this screen while a set is " +
                "playing applies the new points immediately \u2014 you do not have to stop and " +
                "start again to hear the change.",
        )
        Help(
            "Zoom to in / out",
            "Shows only the part between the points, with a little either side so the handles can " +
                "still be dragged outwards. Half a second of breath at the front of a four second " +
                "take is thirty pixels wide, which is not something a finger can place. Press it " +
                "again to see the whole recording. Nothing is ever cut either way: the points are " +
                "two numbers stored beside the recording and the audio is untouched.",
        )
    }
}
