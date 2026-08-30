package com.mantra.sampleplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MALE AND FEMALE ARE DIFFERENT COLOURS, and that is the first thing the eye does with a list of
 * nine hundred names it does not recognise.
 *
 * Two hues rather than an icon or a letter: at this size a glyph has to be read and a colour does
 * not. They are far apart on the wheel and different in brightness as well as in hue, so they stay
 * tellable apart without relying on colour vision alone.
 */
private val MALE = Color(0xFF60A5FA)
private val FEMALE = Color(0xFFF472B6)
private val UNKNOWN_SEX = Color(0xFF94A3B8)

private fun tint(v: VoiceInfo): Color = when (v.gender.lowercase()) {
    "male" -> MALE
    "female" -> FEMALE
    else -> UNKNOWN_SEX
}

@Composable
private fun Chip(label: String, on: Boolean, accent: Color = PLAY_AMBER, onTap: () -> Unit) {
    Box(
        Modifier
            .padding(end = 4.dp, bottom = 4.dp)
            .background(if (on) accent else Color.Black)
            .border(1.dp, if (on) accent else Color(0xFF3F3F46))
            .pointerInput(label + on) { detectTapGestures(onTap = { onTap() }) }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            color = if (on) Color.Black else Color(0xFFCBD5E1),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * THE VOICE CHOOSER, ON ITS OWN SCREEN.
 *
 * Nine hundred and ninety-two Speechify voices and a hundred and sixty from Hume. A list that long
 * is not a choice, it is a wall, and every part of this screen exists to cut it down:
 *
 *   SEARCH is word-prefix and multi-term, so "brit female" narrows on two axes at once even though
 *   those words never appear next to each other in any voice.
 *
 *   FACET CHIPS come from the catalogue itself rather than a hard-coded list, so a value either
 *   provider adds appears here without anybody editing this file. Within a facet they are an OR,
 *   across facets an AND, which is what tapping two genders and one accent should mean.
 *
 *   A STAR is the only signal that came from this person rather than from a provider, so starred
 *   voices sort above everything.
 *
 *   PREVIEW SAYS THE VOICE'S OWN NAME. It used to speak the cell's transcript, which meant
 *   auditioning ten voices for a long line was ten long waits and ten times the credit. "This is
 *   Beatrice" is two seconds and it is the thing being judged.
 */
@Composable
fun VoiceChooser(
    engine: String,
    voices: List<VoiceInfo>,
    loading: String,
    starred: Set<String>,
    onSearchChanged: () -> Unit,
    onStar: (VoiceInfo) -> Unit,
    onOpen: (VoiceInfo) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf(mapOf<String, Set<String>>()) }
    var openFacet by remember { mutableStateOf<String?>(null) }
    var starredOnly by remember { mutableStateOf(false) }

    val shown = remember(voices, query, filters, starred, starredOnly) {
        val base = if (starredOnly) {
            voices.filter { VoiceSearch.key(it) in starred }
        } else {
            voices
        }
        VoiceSearch.apply(base, query, filters, starred)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .padding(horizontal = 8.dp),
    ) {
        ScreenHeader(
            if (engine == Engines.HUME) "Hume voices" else "Speechify voices",
            onClose,
        )

        // ── SEARCH ───────────────────────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "find ",
                color = Color(0xFF71717A),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            BasicTextField(
                value = query,
                onValueChange = { query = it; onSearchChanged() },
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(PLAY_AMBER),
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .border(1.dp, Color(0xFF52525B))
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }

        // ── FACETS ───────────────────────────────────────────────────────────────────────────
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Chip("\u2605 starred", starredOnly) { starredOnly = !starredOnly }
            for (facet in Facets.ORDER) {
                val chosen = filters[facet].orEmpty()
                if (Facets.values(voices, facet).isEmpty()) continue
                Chip(
                    label = facet + if (chosen.isEmpty()) "" else " (${chosen.size})",
                    on = openFacet == facet || chosen.isNotEmpty(),
                ) { openFacet = if (openFacet == facet) null else facet }
            }
        }

        val facet = openFacet
        if (facet != null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                for (value in Facets.values(voices, facet)) {
                    val on = value in filters[facet].orEmpty()
                    Chip(value, on, Color(0xFF60A5FA)) {
                        val was = filters[facet].orEmpty()
                        val now = if (on) was - value else was + value
                        filters = filters + (facet to now)
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            if (loading.isNotBlank()) loading else "${shown.size} of ${voices.size}",
            color = if (loading.isNotBlank()) PLAY_AMBER else Color(0xFF71717A),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )

        // ── THE LIST ─────────────────────────────────────────────────────────────────────────
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { VoiceSearch.key(it) }) { v ->
                val star = VoiceSearch.key(v) in starred
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .border(1.dp, tint(v).copy(alpha = 0.5f))
                        .padding(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            v.name + if (v.flagship) "  \u2726" else "",
                            color = tint(v),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier
                                .padding(horizontal = 6.dp)
                                .pointerInput(VoiceSearch.key(v)) {
                                    detectTapGestures(onTap = { onStar(v) })
                                },
                        ) {
                            Text(
                                if (star) "\u2605" else "\u2606",
                                color = if (star) PLAY_AMBER else Color(0xFF52525B),
                                fontSize = 16.sp,
                            )
                        }
                    }
                    Text(
                        listOf(v.gender, v.age, v.language, v.accent)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    val extra = v.tags
                        .filter { it.startsWith("use-case:") || it.startsWith("timbre:") || it.startsWith("style:") }
                        .joinToString(" ") { it.substringAfter(':') }
                    if (extra.isNotBlank()) {
                        Text(
                            extra,
                            color = Color(0xFF52525B),
                            fontSize = 9.sp,
                            maxLines = 2,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // THE ROW OPENS THE CARD. It used to carry Hear it and Use, which made the
                    // list three lines per voice for eleven hundred voices, and gave the
                    // emotions nowhere to live. Everything about one voice is on its own screen.
                    Button("Open", Modifier.fillMaxWidth()) { onOpen(v) }
                }
            }
        }
    }
}
