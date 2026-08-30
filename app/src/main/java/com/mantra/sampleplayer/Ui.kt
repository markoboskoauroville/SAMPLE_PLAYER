package com.mantra.sampleplayer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
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
    looping: Boolean,
    waveform: FloatArray,
    waveTint: Color = WAVE,
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
    onLongPress: () -> Unit,
) {
    val press by rememberUpdatedState(onPress)
    val longPress by rememberUpdatedState(onLongPress)
    val edge = when {
        recording -> RECORDING_RED
        slot.hasAudio -> FILLED_EDGE
        else -> EMPTY_EDGE
    }
    Column(
        modifier
            .padding(2.dp)
            .border(1.dp, edge)
            // WHY THIS IS NOT `pointerInput(slot.index) { ... onPress() }`.
            //
            // THE SECOND TAP DID NOTHING, and this was why. `pointerInput` restarts its block only
            // when its key changes. Keyed on the slot number, the block is created once and holds
            // the `onPress` closure from THAT composition for ever — the one that captured
            // mode = STOPPED and recordingSlot = null. Tapping a recording tile therefore called
            // the old lambda, which asked to start a recording, which the recorder ignored because
            // one was already running. Nothing happened, nothing failed, and there was nothing to
            // see. The state was correct the whole time and the gesture was reading a photograph
            // of it.
            //
            // `rememberUpdatedState` keeps a live handle, so the block stays created once and the
            // callback it calls is always the current one.
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { press() },
                    onLongPress = { longPress() },
                )
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
        Text(
            "%02d".format(slot.index + 1),
            color = if (slot.hasAudio) Color.White else Color(0xFF71717A),
            fontSize = 9.sp,
            maxLines = 1,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.weight(1f))
        // THE MARK. A cell set to loop says so on the grid, because the grid is where it will be
        // pressed. Amber while it is the one sounding, dim while it is only marked.
        if (slot.loop) {
            Text(
                "\u221e",
                color = if (looping) PLAY_AMBER else Color(0xFF71717A),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .drawBehind {
                    // THE WAVEFORM. While recording this is the shape FORMING, live; when stopped
                    // it is the stored recording. A recorder that shows nothing until it stops
                    // asks you to talk into a hole and find out afterwards. The meter says audio
                    // is arriving; only the shape says what arrived.
                    if (waveform.isNotEmpty()) {
                        val w = size.width / waveform.size
                        for (i in waveform.indices) {
                            val h = (waveform[i] * size.height).coerceAtLeast(1f)
                            // CONTINUOUS, NOT A PICKET FENCE. The bars were drawn at seven
                            // tenths of their slot, which left a black gap between every one of
                            // them — at 32 slices that reads as a striped pattern rather than as
                            // a shape, and at 128 it reads as a grey haze. Full width, and the
                            // waveform is a waveform.
                            drawRect(
                                color = if (recording) RECORDING_RED else waveTint,
                                topLeft = Offset(i * w, (size.height - h) / 2f),
                                size = Size(w.coerceAtLeast(1f), h),
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
        ) {
            // THE WORDS OVER THE WAVE.
            //
            // An untranscribed cell shows the shape alone: there is nothing to say about it that
            // it is not already saying. A transcribed cell carries its own sentence, because at
            // three across the number and the first two words are all that fit in a title, and
            // the whole point of transcribing is knowing which line this is.
            //
            // IT SCROLLS ONLY WHILE THE CELL IS SOUNDING, and only when the sentence is wider
            // than the cell. Text crawling on twenty-nine silent cells would be the animation
            // design-language.md 8 rules out — something moving that the eye is pulled towards
            // with no reason to look. On the one cell that is playing it is the opposite: it is
            // the thing being listened to.
            if (slot.words.isNotBlank()) {
                var textW by remember(slot.words) { mutableStateOf(0) }
                var boxW by remember { mutableStateOf(0) }
                val over = (textW - boxW).coerceAtLeast(0)
                val shift = if (playhead != null && over > 0) {
                    val move = rememberInfiniteTransition(label = "words")
                    move.animateFloat(
                        initialValue = 0f,
                        targetValue = -over.toFloat(),
                        animationSpec = infiniteRepeatable(
                            // Twenty-five milliseconds a pixel is about the pace of reading, and
                            // the restart is a jump rather than a slide back, so nothing crosses
                            // the cell in the wrong direction.
                            animation = tween(over * 25, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                        label = "shift",
                    ).value
                } else {
                    0f
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clipToBounds()
                        .onSizeChanged { boxW = it.width },
                ) {
                    Text(
                        slot.words,
                        color = Color(0xFFE2E8F0),
                        fontSize = 9.sp,
                        maxLines = 1,
                        softWrap = false,
                        fontFamily = FontFamily.Monospace,
                        onTextLayout = { textW = it.size.width },
                        modifier = Modifier.offset { IntOffset(shift.toInt(), 0) },
                    )
                }
            }
        }
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

/**
 * CANCEL, PLAY, OK — AND PLAY IS THE ONE THAT WAS MISSING.
 *
 * The question only appears when a press would record over a take, and the honest reading of that
 * moment is not "are you sure you want to destroy this". Most of the time the person meant to HEAR
 * the cell and had forgotten which mode the app was in. Offering only Cancel and OK makes them
 * cancel, find the toggle, flip it, and press the same cell again — four actions to correct a
 * mistake the app already understood.
 *
 * So Play does what they meant: it plays that cell and leaves the app in play mode, so the next
 * press does the same thing rather than asking again.
 *
 * ORDER AND COLOUR MATTER HERE. Cancel is first and plain. Play is amber, the colour of the play
 * toggle, so the thing most likely to be wanted is the thing the eye lands on. OK is red and last,
 * because it is the one that destroys a recording.
 *
 * A bar rather than a dialog. A dialog dims the screen and takes the grid away, and the question is
 * about a cell you can see.
 */
@Composable
fun ConfirmBar(
    question: String,
    onCancel: () -> Unit,
    onPlay: () -> Unit,
    onOk: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, RECORDING_RED)
            .padding(8.dp),
    ) {
        Text(
            question,
            color = Color.White,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("Cancel", Modifier.weight(1f)) { onCancel() }
            Button("Play", Modifier.weight(1f), solid = true, accent = PLAY_AMBER) { onPlay() }
            Button("OK", Modifier.weight(1f), solid = true, accent = RECORDING_RED) { onOk() }
        }
    }
}

/**
 * ONE CLOSE BUTTON, TOP RIGHT, ON EVERY SCREEN THAT IS NOT THE GRID.
 *
 * Back buttons were full width and there were two of them on the longer screens, which is two
 * rows of glass spent on leaving. An X in the corner is where every other app on the phone puts
 * it, it is always in the same place whatever the screen is, and it costs one square.
 */
@Composable
fun ScreenHeader(title: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(38.dp)
                .border(1.dp, Color(0xFF52525B))
                .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "\u00d7",
                color = Color.White,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
