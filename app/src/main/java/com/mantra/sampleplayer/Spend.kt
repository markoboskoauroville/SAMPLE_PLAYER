package com.mantra.sampleplayer

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * WHAT EACH CALL COST, WRITTEN DOWN AS IT HAPPENS.
 *
 * NO PROVIDER HERE PUBLISHES A BALANCE. Probed with real keys on 30.8.2026: Hume's `/v0/usage`,
 * `/v0/billing` and `/v0/account` are all 404; AssemblyAI's `/v2/account` returns an empty object
 * and every other spelling is 404; Speechify has no usage surface at all; Anthropic's usage report
 * needs an Admin key, which is a different key from the one in the note.
 *
 * WHAT THEY ALL DO REPORT IS WHAT THE CALL JUST COST. Speechify states the characters it billed,
 * Hume the seconds it produced, AssemblyAI the audio duration it read. Those are facts about the
 * call rather than estimates of it, so one line is written for each.
 *
 * THE TOTAL IS THE LOG ADDED UP AND IS NEVER STORED. A running total kept beside a log is a number
 * that can disagree with the lines it came from, and the day it does there is no way to tell which
 * one is lying. Clearing the log therefore empties the box by arithmetic rather than by a second
 * thing having to be remembered.
 *
 * NO KEY, NO ACCOUNT NAME, NOT EVEN A FINGERPRINT. What was spent is a fact about the work; which
 * of twenty-one accounts paid for it is not something a file that grows for months needs to hold.
 */
class Spend(private val context: Context) {

    /**
     * Each provider is billed in its own unit, and adding characters to seconds would be a number
     * that means nothing.
     */
    companion object {
        val UNITS = mapOf(
            Engines.SPEECHIFY to "characters",
            Engines.HUME to "seconds",
            "assemblyai" to "seconds of audio",
        )

        /**
         * THE RATES DEFAULT TO ZERO AND ARE ASKED FOR RATHER THAN GUESSED.
         *
         * I do not know what Baba pays. Published prices change, they differ per plan, and several
         * of these accounts are on terms I cannot see — a number invented here would appear on
         * screen looking exactly as authoritative as the character count beside it, which is
         * measured. Units are logged always; money appears once a rate is entered.
         */
        const val NO_RATE = 0.0

        /** Detail is capped: a log line is a reminder, not a copy of the work. */
        const val MAX_DETAIL = 80
    }

    private val file get() = File(context.filesDir, "spend.jsonl")
    private val ratesFile get() = File(context.filesDir, "rates.json")

    data class Row(val at: Long, val provider: String, val units: Double, val detail: String)

    data class Total(val calls: Int, val units: Double, val unit: String, val cost: Double)

    /**
     * One line, appended.
     *
     * JSON LINES RATHER THAN ONE ARRAY. An append cannot corrupt what is already there, a
     * half-written last line is one lost call rather than a lost file, and the whole thing can be
     * read in a text editor when something looks wrong — which is the only reason to keep a log.
     */
    fun add(provider: String, units: Double, detail: String) {
        // A refused call bills nothing, and a row saying zero is a row that has to be read to
        // discover it says nothing.
        if (units <= 0.0) return
        val o = JSONObject()
            .put("at", System.currentTimeMillis() / 1000)
            .put("provider", provider)
            .put("units", Math.round(units * 1000.0) / 1000.0)
            .put("detail", detail.take(MAX_DETAIL))
        runCatching { file.appendText(o.toString() + "\n") }
    }

    fun rows(): List<Row> {
        if (!file.isFile) return emptyList()
        val out = ArrayList<Row>()
        for (line in file.readLines()) {
            if (line.isBlank()) continue
            // A torn last line is skipped rather than taking the file down with it.
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            out.add(
                Row(
                    o.optLong("at"),
                    o.optString("provider"),
                    o.optDouble("units", 0.0),
                    o.optString("detail"),
                ),
            )
        }
        return out
    }

    fun rates(): Map<String, Double> {
        if (!ratesFile.isFile) return emptyMap()
        val o = runCatching { JSONObject(ratesFile.readText()) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Double>()
        for (k in o.keys()) out[k] = o.optDouble(k, NO_RATE)
        return out
    }

    fun setRate(provider: String, rate: Double) {
        val o = JSONObject()
        for ((k, v) in rates()) o.put(k, v)
        o.put(provider, rate)
        runCatching { ratesFile.writeText(o.toString()) }
    }

    /** Per provider, computed on every read. */
    fun totals(): Map<String, Total> {
        val r = rates()
        val calls = HashMap<String, Int>()
        val units = HashMap<String, Double>()
        for (row in rows()) {
            calls[row.provider] = (calls[row.provider] ?: 0) + 1
            units[row.provider] = (units[row.provider] ?: 0.0) + row.units
        }
        return calls.keys.associateWith { p ->
            val u = units[p] ?: 0.0
            Total(
                calls = calls[p] ?: 0,
                units = Math.round(u * 100.0) / 100.0,
                unit = UNITS[p] ?: "units",
                cost = Math.round(u * (r[p] ?: NO_RATE) * 10000.0) / 10000.0,
            )
        }
    }

    fun money(): Double =
        Math.round(totals().values.sumOf { it.cost } * 10000.0) / 10000.0

    fun clear(): Int {
        val n = rows().size
        runCatching { file.delete() }
        return n
    }
}
