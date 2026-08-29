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
private val RECORDING = Color(0xFFEF4444)

/**
 * ONE OF THE THIRTY.
 *
 * Title on the left, waveform filling the rest, playhead crossing the waveform. The line is the
 * player: without the mark it is a picture of a recording, with it you can see where you are.
 */
@Composable
fun Line(
    slot: Slot,
    playhead: Float?,
    recording: Boolean,
    waveform: FloatArray,
    onPress: () -> Unit,
    onLongPress: () -> Unit,
) {
    val edge = when {
        recording -> RECORDING
        slot.hasOriginal -> FILLED_EDGE
        else -> EMPTY_EDGE
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .border(1.dp, edge)
            .pointerInput(slot.index) {
                detectTapGestures(
                    onTap = { onPress() },
                    onLongPress = { onLongPress() },
                )
            }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            "%02d  %s".format(slot.index + 1, slot.title()),
            color = if (slot.hasOriginal) Color.White else Color(0xFF71717A),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .drawBehind {
                    // THE WAVEFORM. Ninety-six buckets, which is what a full-width line allows.
                    // A pad that says only "filled" tells you a recording exists; a pad with the
                    // shape on it tells you WHICH recording, and whether what you captured was a
                    // word at all or a cough at one end and silence at the other.
                    if (waveform.isNotEmpty()) {
                        val w = size.width / waveform.size
                        for (i in waveform.indices) {
                            val h = (waveform[i] * size.height).coerceAtLeast(1f)
                            drawRect(
                                color = WAVE,
                                topLeft = Offset(i * w, (size.height - h) / 2f),
                                size = Size(w * 0.7f, h),
                            )
                        }
                    }
                    // THE PLAYHEAD. A thin vertical mark crossing this line's own waveform. When
                    // the sample ends it leaves this line and appears at the start of the next, so
                    // over a full play it travels down the whole set.
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
fun Button(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(44.dp)
            .background(Color.Black)
            .border(1.dp, Color(0xFF52525B))
            .pointerInput(label) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}
