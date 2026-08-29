package com.mantra.sampleplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
 * HOLLOW IS EMPTY, SOLID IS FULL, and it is the same language as the stopwatch and TTT mini.
 *
 * design-language.md 4: an outline means off and a filled shape means on, everywhere, so the state
 * is legible without reading a word and without telling two colours apart.
 */
private val EMPTY_EDGE = Color(0xFF3F3F46)
private val FILLED_EDGE = Color(0xFFE8A64B)
private val WAVE = Color(0xFF94A3B8)
private val PLAYHEAD = Color(0xFFFDE68A)
val RECORDING_RED = Color(0xFFEF4444)
val PLAY_AMBER = Color(0xFFE8A64B)

/**
 * ONE OF THE THIRTY, THREE ACROSS.
 *
 * v1 built these as thirty full-width lines, arguing from `MINIMALIST_STOPWATCH` v17 that a grid
 * of small squares reads as a keypad while a full-width line reads as a sample list. On the phone
 * that was wrong, and the phone is the authority: fourteen rows visible out of thirty, and four
 * screens of scrolling to reach the end, in an app whose whole point is filling slots quickly.
 * Ten rows of three fits the entire set on one screen.
 *
 * WHAT IT COSTS, said plainly rather than buried: the waveform drops from 96 buckets to 32. That
 * is the resolution v17 objected to. The argument was not wrong, it was outweighed.
 */
@Composable
fun Tile(
    slot: Slot,
    playhead: Float?,
    recording: Boolean,
    waveform: FloatArray,
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
    onLongPress: () -> Unit,
) {
    val edge = when {
        recording -> RECORDING_RED
        slot.hasOriginal -> FILLED_EDGE
        else -> EMPTY_EDGE
    }
    Column(
        modifier
            .padding(2.dp)
            .border(1.dp, edge)
            .pointerInput(slot.index) {
                detectTapGestures(
                    onTap = { onPress() },
                    onLongPress = { onLongPress() },
                )
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Text(
            "%02d %s".format(slot.index + 1, slot.title()),
            color = if (slot.hasOriginal) Color.White else Color(0xFF71717A),
            fontSize = 9.sp,
            maxLines = 1,
            fontFamily = FontFamily.Monospace,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .drawBehind {
                    // THE WAVEFORM. While recording this is the shape FORMING, live; when stopped
                    // it is the stored recording. A recorder that shows nothing until it stops
                    // asks you to talk into a hole and find out afterwards. The meter says audio
                    // is arriving; only the shape says what arrived.
                    if (waveform.isNotEmpty()) {
                        val w = size.width / waveform.size
                        for (i in waveform.indices) {
                            val h = (waveform[i] * size.height).coerceAtLeast(1f)
                            drawRect(
                                color = if (recording) RECORDING_RED else WAVE,
                                topLeft = Offset(i * w, (size.height - h) / 2f),
                                size = Size((w * 0.7f).coerceAtLeast(1f), h),
                            )
                        }
                    }
                    // THE PLAYHEAD. A thin vertical mark crossing this tile's own waveform. When
                    // the sample ends it leaves this tile and appears at the start of the next, so
                    // over a full play the mark travels down the whole set.
                    if (playhead != null) {
                        val x = playhead.coerceIn(0f, 1f) * size.width
                        drawRect(
                            color = PLAYHEAD,
                            topLeft = Offset(x, 0f),
                            size = Size(2f, size.height),
                        )
                    }
                },
        )
    }
}

/** A control is a rectangle with a word in it. Nothing here needs to be more than that. */
@Composable
fun Button(
    label: String,
    modifier: Modifier = Modifier,
    solid: Boolean = false,
    accent: Color = Color(0xFF52525B),
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(42.dp)
            .background(if (solid) accent else Color.Black)
            .border(1.dp, accent)
            .pointerInput(label) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (solid) Color.Black else Color.White,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
