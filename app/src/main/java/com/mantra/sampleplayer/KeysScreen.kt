package com.mantra.sampleplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One row of the ring: what it is, whose account, and what the last test said. */
data class KeyRow(
    val key: String,
    val providerId: String,
    val label: String,
    val masked: String,
    val paired: Boolean,
    val result: Providers.Result? = null,
)

/**
 * THE KEY RING, THE WAY `Key_Tester` DOES IT.
 *
 * List, test, delete. Not a settings row saying "keys: 12" — the whole point of the tester is that
 * when something says a key is dead you can find out which one and whether it is true, without a
 * cable and without another app.
 *
 * WHAT IS SHOWN: provider, account label, and six characters and four. Never a key. There is no
 * code path in this file that can render one, which is a stronger guarantee than a rule saying not
 * to.
 */
@Composable
fun KeysScreen(
    rows: List<KeyRow>,
    busy: String,
    onImportFile: () -> Unit,
    onTest: (KeyRow) -> Unit,
    onTestAll: () -> Unit,
    onDelete: (KeyRow) -> Unit,
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
            "Keys",
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 10.dp),
        )

        // WHAT IS NEEDED, AT THE TOP OF THE SCREEN WHERE IT GETS FIXED. Settings says the same
        // thing; this is the place somebody is standing when they can do something about it.
        Text(
            Needs.lines(rows.map { it.providerId }.toSet()).joinToString("\n"),
            color = Color(0xFF94A3B8),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 10.dp),
        )

        // IMPORT IS A FILE, NOT A PASTE BOX. A text field on a phone is a keyboard, and this app
        // is dictated by somebody who does not type. The picker opens the phone's own file
        // chooser, so the note can come from Drive, Downloads, or anywhere else it already lives.
        Button("Import keys from a text file", Modifier.fillMaxWidth()) { onImportFile() }
        Text(
            "The whole note. Account names on the line above each key are kept and shown here; " +
                "the keys themselves are never displayed anywhere in this app.",
            color = Color(0xFF71717A),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (busy.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                busy,
                color = PLAY_AMBER,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        if (rows.isEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                "No keys yet.",
                color = Color(0xFF71717A),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        } else {
            Spacer(Modifier.height(14.dp))
            Button("Test all", Modifier.fillMaxWidth()) { onTestAll() }
            Spacer(Modifier.height(10.dp))

            for (row in rows) {
                val colour = when (row.result?.status) {
                    Status.WORKING -> Color(0xFF4ADE80)
                    // A THROTTLED KEY IS A GOOD KEY. Amber, not red, and never a suggestion to
                    // delete it: a ring that buries busy accounts eats itself in an afternoon.
                    Status.LIMITED -> PLAY_AMBER
                    Status.REJECTED -> RECORDING_RED
                    Status.OFFLINE -> Color(0xFF94A3B8)
                    Status.OTHER -> Color(0xFF94A3B8)
                    null -> Color(0xFF71717A)
                }
                Column(Modifier.padding(bottom = 10.dp)) {
                    Text(
                        (Providers.byId(row.providerId)?.display ?: row.providerId) +
                            if (row.paired) "  (pair)" else "",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        (row.label.ifBlank { "no account name in the note" }) + "   " + row.masked,
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    row.result?.let {
                        Text(
                            "HTTP ${it.code} — ${it.detail}",
                            color = colour,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button("Test", Modifier.weight(1f), accent = colour) { onTest(row) }
                        Button("Delete", Modifier.weight(1f), accent = RECORDING_RED) {
                            onDelete(row)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Button("Back", Modifier.fillMaxWidth()) { onBack() }
        Spacer(Modifier.height(20.dp))
    }
}
