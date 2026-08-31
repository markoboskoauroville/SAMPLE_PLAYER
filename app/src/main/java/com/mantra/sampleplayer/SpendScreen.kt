package com.mantra.sampleplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * EVERY BILLABLE CALL, AND WHAT IT COST.
 *
 * THE LOG IS THE BOX AND THE BOX IS THE LOG. Clearing this empties the figure in settings by
 * arithmetic rather than by a second thing having to be remembered — there is no running total
 * stored anywhere that could survive the clearing and disagree with it.
 *
 * The numbers are what the PROVIDER said it charged for: Speechify states the characters it
 * billed, Hume the seconds it produced, AssemblyAI the audio duration it read. Facts about the
 * call rather than estimates of it, which is the only reason a log like this is worth keeping.
 */
@Composable
fun SpendScreen(
    spend: Spend,
    onRate: (String, Double) -> Unit,
    onClear: () -> Unit,
    tick: Int,
    onBack: () -> Unit,
) {
    val totals = remember(tick) { spend.totals().toSortedMap() }
    val rows = remember(tick) { spend.rows().asReversed() }
    val money = remember(tick) { spend.money() }
    val rates = remember(tick) { spend.rates() }
    val when1 = remember { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .padding(horizontal = 10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader("Spent", onBack)

        if (totals.isEmpty()) {
            Text(
                "nothing logged yet",
                color = Color(0xFF71717A),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        for ((provider, t) in totals) {
            Text(
                "$provider: ${t.calls} calls, ${t.units} ${t.unit}" +
                    if (t.cost > 0) "  \u2014 ${t.cost}" else "",
                color = providerColour(provider),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (money > 0) {
            Text(
                "total $money",
                color = PLAY_AMBER,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ── RATE PER UNIT ────────────────────────────────────────────────────────────────────
        Spacer(Modifier.height(14.dp))
        Text(
            "RATE PER UNIT",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        for (provider in Spend.UNITS.keys.sorted()) {
            var typed by remember(tick, provider) {
                mutableStateOf((rates[provider] ?: Spend.NO_RATE).toString())
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    provider,
                    color = providerColour(provider),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 10.dp),
                )
                BasicTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    cursorBrush = SolidColor(PLAY_AMBER),
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, Color(0xFF3F3F46))
                        .padding(8.dp),
                )
                Button("Set", Modifier.width(64.dp)) {
                    // A rate that is not a number is left alone rather than becoming zero, which
                    // would silently wipe a figure somebody had entered.
                    typed.trim().toDoubleOrNull()?.let { onRate(provider, it) }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Button("Clear the log", Modifier.fillMaxWidth(), accent = RECORDING_RED) { onClear() }

        // ── EVERY CALL ───────────────────────────────────────────────────────────────────────
        Spacer(Modifier.height(18.dp))
        Text(
            "EVERY CALL, NEWEST FIRST",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(4.dp))
        // Capped at what a person will actually scroll. The figures above are computed from ALL
        // of them, so a long log is still counted in full even though it is not all drawn.
        for (row in rows.take(300)) {
            Column(Modifier.padding(vertical = 3.dp)) {
                Text(
                    "${row.provider}  ${when1.format(Date(row.at * 1000))}  " +
                        "${row.units} ${Spend.UNITS[row.provider] ?: "units"}",
                    color = providerColour(row.provider),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                if (row.detail.isNotBlank()) {
                    Text(
                        row.detail,
                        color = Color(0xFF71717A),
                        fontSize = 10.sp,
                        maxLines = 1,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        if (rows.size > 300) {
            Text(
                "\u2026and ${rows.size - 300} older, counted in the figures above",
                color = Color(0xFF52525B),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        // ── THE HELP ─────────────────────────────────────────────────────────────────────────
        Spacer(Modifier.height(24.dp))
        Text(
            "WHAT THESE DO",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(6.dp))
        Help(
            "The numbers",
            "What the PROVIDER said it charged for that call \u2014 Speechify the characters it " +
                "billed, Hume the seconds it produced, AssemblyAI the audio duration it read. " +
                "Facts about the call rather than guesses about it.",
        )
        Help(
            "Rate per unit",
            "Empty by default, and asked for rather than invented: nothing here knows what you " +
                "pay, published prices change, and several of these accounts are on terms this " +
                "app cannot see. A number it made up would sit on screen looking exactly as " +
                "certain as the measured count beside it.",
        )
        Help(
            "Clear the log",
            "Empties it, and the figure in settings with it. The total has never been stored " +
                "anywhere \u2014 it is this log added up on every read \u2014 so there is no " +
                "second number that could survive the clearing and disagree.",
        )
        Spacer(Modifier.height(24.dp))
    }
}
