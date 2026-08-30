package com.mantra.sampleplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MALE_C = Color(0xFF60A5FA)
private val FEMALE_C = Color(0xFFF472B6)
private val DIMMED = Color(0xFF71717A)
private val LABEL_C = Color(0xFF94A3B8)

private fun cardTint(v: VoiceInfo): Color = when (v.gender.lowercase()) {
    "male" -> MALE_C
    "female" -> FEMALE_C
    else -> LABEL_C
}

@Composable
private fun Field(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label.padEnd(10),
            color = DIMMED,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            value,
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * ONE VOICE, ON A WHOLE SCREEN.
 *
 * The chooser answers "which of these eleven hundred", and it has to do that in one line per voice.
 * This answers "is this the one", and the difference is that here there is room: every facet the
 * provider publishes, the model the id forces, the id itself, and — for Hume — the whole grid of
 * acting directions with a preview on each.
 *
 * WHY THE FACETS ARE LISTED RATHER THAN SUMMARISED. Speechify tags a voice with up to a dozen
 * use-cases and Hume gives three accents on one voice. A card that shows the first two and an
 * ellipsis is a card that has to be tapped to be read, and the reason to be on this screen at all
 * is to stop tapping.
 */
@Composable
fun VoiceCard(
    voice: VoiceInfo,
    starred: Boolean,
    direction: String,
    playing: String,
    onStar: () -> Unit,
    onPreviewPlain: () -> Unit,
    onPreviewDirection: (Emotions.Direction) -> Unit,
    onDirection: (String) -> Unit,
    onUse: () -> Unit,
    onClose: () -> Unit,
) {
    val tint = cardTint(voice)

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .padding(horizontal = 10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(voice.name, onClose)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                voice.name,
                color = tint,
                fontSize = 22.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.pointerInput(voice.id) { detectTapGestures(onTap = { onStar() }) },
            ) {
                Text(
                    if (starred) "\u2605" else "\u2606",
                    color = if (starred) PLAY_AMBER else Color(0xFF52525B),
                    fontSize = 22.sp,
                )
            }
        }
        Text(
            if (voice.engine == Engines.HUME) "Hume" else "Speechify",
            color = DIMMED,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (voice.flagship) {
            Text(
                "\u2726 flagship voice \u2014 the newest Speechify model carries only these eight",
                color = PLAY_AMBER,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button("Hear it", Modifier.weight(1f)) { onPreviewPlain() }
            Button("Use this voice", Modifier.weight(1f), solid = true, accent = tint) { onUse() }
        }

        if (playing.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                playing,
                color = PLAY_AMBER,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        // ── WHAT THE PROVIDER SAYS ABOUT IT ──────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        Text(
            "THE VOICE",
            color = LABEL_C,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(4.dp))
        Field("gender", voice.gender)
        Field("age", voice.age)
        Field("language", voice.language)
        Field("accent", voice.accent)
        Field("id", voice.id)
        // THE MODEL IS DERIVED, NOT STORED. simba-3.2 answers 400 for 984 of 992 voices, so
        // showing it here is showing the rule that decides whether this voice can speak at all.
        if (voice.engine == Engines.SPEECHIFY) {
            Field("model", Engines.modelFor(voice.id))
        }
        Field("preview", if (voice.preview != null) "published by Speechify" else "generated on demand")

        // Every remaining tag, grouped by its namespace.
        val grouped = voice.tags
            .filter { it.contains(':') }
            .groupBy({ it.substringBefore(':') }, { it.substringAfter(':') })
            .toSortedMap()
        for ((facet, values) in grouped) {
            if (facet in listOf("gender", "age", "language", "accent")) continue
            Field(facet, values.sorted().joinToString(", "))
        }

        // ── HOW IT DELIVERS THE LINE ─────────────────────────────────────────────────────────
        if (Emotions.availableFor(voice.engine)) {
            Spacer(Modifier.height(18.dp))
            Text(
                "HOW IT SAYS IT",
                color = LABEL_C,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "Tap one to hear it. The preview says the voice's own name, so eight emotions is " +
                    "eight short calls rather than eight readings of your line.",
                color = DIMMED,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            Row(Modifier.padding(vertical = 4.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(38.dp)
                        .background(if (direction.isBlank()) PLAY_AMBER else Color.Black)
                        .border(1.dp, PLAY_AMBER)
                        .pointerInput("plain") { detectTapGestures(onTap = { onDirection("") }) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "no direction",
                        color = if (direction.isBlank()) Color.Black else PLAY_AMBER,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            for (group in Emotions.GROUPS) {
                Text(
                    group.uppercase(),
                    color = DIMMED,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
                // THREE ACROSS. A glyph and a word have to both be legible, and at two across the
                // rows run out of screen before the groups run out of directions.
                val inGroup = Emotions.of(group)
                var row = 0
                while (row * 3 < inGroup.size) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (col in 0 until 3) {
                            val at = row * 3 + col
                            if (at >= inGroup.size) {
                                Spacer(Modifier.weight(1f))
                                continue
                            }
                            val d = inGroup[at]
                            val on = direction == d.text
                            Column(
                                Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .background(if (on) tint else Color.Black)
                                    .border(1.dp, if (on) tint else Color(0xFF3F3F46))
                                    .pointerInput(d.label) {
                                        detectTapGestures(
                                            onTap = {
                                                onDirection(d.text)
                                                onPreviewDirection(d)
                                            },
                                        )
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    d.glyph,
                                    color = if (on) Color.Black else tint,
                                    fontSize = 16.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    d.label,
                                    color = if (on) Color.Black else Color(0xFFCBD5E1),
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                    row++
                }
            }

            val chosen = Emotions.byText(direction)
            if (chosen != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "sent to Hume as: \u201c${chosen.text}\u201d",
                    color = DIMMED,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
