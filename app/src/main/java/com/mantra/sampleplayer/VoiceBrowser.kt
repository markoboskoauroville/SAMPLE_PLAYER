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
import androidx.compose.foundation.layout.width
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

private val MALE = Color(0xFF60A5FA)
private val FEMALE = Color(0xFFF472B6)
private val NEITHER = Color(0xFF94A3B8)

private fun sexColour(v: VoiceInfo): Color = when (v.gender.lowercase()) {
    "male" -> MALE
    "female" -> FEMALE
    else -> NEITHER
}

@Composable
private fun Chip(label: String, on: Boolean, count: Int?, accent: Color, onTap: () -> Unit) {
    Box(
        Modifier
            .padding(end = 4.dp, bottom = 4.dp)
            .background(if (on) accent else Color.Black)
            .border(1.dp, if (on) accent else Color(0xFF3F3F46))
            .pointerInput(label + on) { detectTapGestures(onTap = { onTap() }) }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            label + if (count == null) "" else "  $count",
            color = if (on) Color.Black else Color(0xFFCBD5E1),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * THE VOICE BROWSER: A SCREEN FOR LOOKING, NOT FOR CHOOSING.
 *
 * The chooser has always been reached FROM a cell, which means the only way to hear what eleven
 * hundred voices sound like is to pick a cell first and then either use one or back out. That is
 * the wrong shape for the thing Baba actually does — deciding who reads a part, before there is a
 * take to read it over.
 *
 * So this is its own tab, with no cell attached. Nothing here can change a recording.
 *
 * EVERY ROW CARRIES ITS OWN PLAY, which is the whole point. Opening a card to hear one voice and
 * closing it again is two gestures and a screen change per candidate, and at a hundred candidates
 * that is why nobody auditions and everybody takes the first name they recognise.
 *
 * BOTH ENGINES ON ONE SCREEN. They are picked between at the top rather than being two separate
 * places to go, because "which voice" is a question about voices and not about vendors — and the
 * two lists are 992 and 160, which are not comparable amounts of scrolling.
 */
@Composable
fun VoiceBrowser(
    engine: String,
    voices: List<VoiceInfo>,
    loading: String,
    starred: Set<String>,
    canSpeechify: Boolean,
    canHume: Boolean,
    onEngine: (String) -> Unit,
    onStar: (VoiceInfo) -> Unit,
    onPreview: (VoiceInfo) -> Unit,
    onOpen: (VoiceInfo) -> Unit,
    onTab: (String) -> Unit,
    tabSlot: Int?,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf(mapOf<String, Set<String>>()) }
    var openFacet by remember { mutableStateOf<String?>(null) }
    var starredOnly by remember { mutableStateOf(false) }

    val shown = remember(voices, query, filters, starred, starredOnly) {
        val base = if (starredOnly) voices.filter { VoiceSearch.key(it) in starred } else voices
        VoiceSearch.apply(base, query, filters, starred)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .padding(horizontal = 8.dp),
    ) {
        ScreenHeader("Voices", onBack)
        Tabs("voices", tabSlot, onTab)

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

        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(PLAY_AMBER),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF52525B))
                .padding(8.dp),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        "find \u2014 try: brit female, or meditation",
                        color = Color(0xFF52525B),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                inner()
            },
        )

        // ── THE FACETS ───────────────────────────────────────────────────────────────────────
        //
        // Built FROM the catalogue rather than from a list in this file, so a value either
        // provider adds next month appears without anybody editing the app.
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Chip("\u2605 starred", starredOnly, null, PLAY_AMBER) { starredOnly = !starredOnly }
            for (facet in Facets.ORDER) {
                if (Facets.values(voices, facet).isEmpty()) continue
                val chosen = filters[facet].orEmpty()
                Chip(
                    label = facet + if (chosen.isEmpty()) "" else " (${chosen.size})",
                    on = openFacet == facet || chosen.isNotEmpty(),
                    count = null,
                    accent = PLAY_AMBER,
                ) { openFacet = if (openFacet == facet) null else facet }
            }
        }

        val facet = openFacet
        if (facet != null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                // THE COUNT IS COMPUTED AGAINST THE OTHER FILTERS, and with this row's own
                // selection ignored — so the numbers do not collapse to zero the moment one value
                // in the row is picked, and a chip that would leave nothing says 0 BEFORE it is
                // pressed rather than after.
                val pool = voices.filter { v ->
                    filters.all { (f, wanted) ->
                        f == facet || wanted.isEmpty() || wanted.any { Facets.has(v, f, it) }
                    } && VoiceSearch.matches(v, query)
                }
                for (value in Facets.values(voices, facet)) {
                    val on = value in filters[facet].orEmpty()
                    val n = pool.count { Facets.has(it, facet, value) }
                    Chip(value, on, n, Color(0xFF60A5FA)) {
                        val was = filters[facet].orEmpty()
                        filters = filters + (facet to if (on) was - value else was + value)
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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .border(1.dp, sexColour(v).copy(alpha = 0.5f))
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            v.name + if (v.flagship) "  \u2726" else "" + if (star) "  \u2605" else "",
                            color = sexColour(v),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            listOf(v.gender, v.age, v.language, v.accent)
                                .filter { it.isNotBlank() }
                                .joinToString(" \u00b7 "),
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        val extra = v.tags
                            .filter { it.startsWith("use-case:") || it.startsWith("timbre:") }
                            .joinToString(" ") { it.substringAfter(':') }
                        if (extra.isNotBlank()) {
                            Text(
                                extra,
                                color = Color(0xFF52525B),
                                fontSize = 9.sp,
                                maxLines = 1,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    Button("\u25b6", Modifier.width(52.dp)) { onPreview(v) }
                    Spacer(Modifier.width(4.dp))
                    Button("open", Modifier.width(64.dp)) { onOpen(v) }
                }
            }
        }
    }
}
