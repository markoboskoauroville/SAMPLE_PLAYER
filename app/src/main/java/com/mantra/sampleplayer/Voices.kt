package com.mantra.sampleplayer

import android.util.Base64
import java.io.File

/**
 * ONE VOICE IS ONE VOICE, WHATEVER ENGINE MADE IT.
 *
 * [id] is what the engine is told. [model] is stored BESIDE the voice and never as a global
 * default: Speechify's `simba-3.2` works only for the eight curated voices and returns HTTP 400
 * for everything else, so a voice that lives on `simba-multilingual` fails under a global setting
 * that names a different model. [preview] is a URL where the engine publishes one, or null where
 * it does not.
 */
data class Voice(
    val engine: String,
    val id: String,
    val name: String,
    val model: String = "",
    val preview: String? = null,
)

/**
 * ASSEMBLYAI, AND ONE KEY FOR THE WHOLE JOB.
 *
 * `keyring.md`: an upload belongs to the account that made it. A ring asked for a key on every call
 * uploaded on account A, submitted on account B, got `403 Cannot access uploaded file` and read it
 * as a dead key — walking one clip through six good accounts and condemning all six. So the key is
 * taken once, held through upload, submit and poll, and if it genuinely dies the JOB restarts
 * rather than continuing on an account that cannot see the upload.
 *
 * THE AUTH HEADER IS THE RAW KEY. No `Bearer`. A 401 on a key that looks fine is almost always
 * this, and it is the single most common way to lose an hour here.
 */
object Transcribe {

    private const val BASE = "https://api.assemblyai.com/v2"

    /** English only, which is what this app is. `language_code` told wrongly returns confident nonsense. */
    private const val LANGUAGE = "en"

    sealed interface Result {
        data class Text(val words: String) : Result
        data class Failed(val why: String) : Result
    }

    fun of(wav: File, ring: Ring): Result {
        val credential = ring.current() ?: return Result.Failed("no AssemblyAI key")
        val auth = mapOf("authorization" to credential.key)

        val bytes = wav.readBytes()
        if (bytes.size <= 44) return Result.Failed("nothing to transcribe")

        val up = Net.postBytes("$BASE/upload", auth, bytes)
        if (!up.ok) return failure(up, credential, ring, "upload")
        val url = Net.str(up.body, "upload_url") ?: return Result.Failed("no upload url returned")

        val started = Net.postJson(
            "$BASE/transcript",
            auth,
            """{"audio_url":"$url","language_code":"$LANGUAGE"}""",
        )
        if (!started.ok) return failure(started, credential, ring, "submit")
        val id = Net.str(started.body, "id") ?: return Result.Failed("no transcript id returned")

        // POLL. There is no callback in the shape used here. Bounded: a phrase is seconds long and
        // anything past a minute of polling is a job that is not coming back, not a slow one.
        var waited = 0L
        while (waited < 60_000) {
            Thread.sleep(1_500)
            waited += 1_500
            val got = Net.get("$BASE/transcript/$id", auth)
            if (!got.ok) return failure(got, credential, ring, "poll")
            when (Net.str(got.body, "status")) {
                "completed" -> {
                    val text = Net.str(got.body, "text").orEmpty().trim()
                    return if (text.isEmpty()) Result.Failed("nothing heard") else Result.Text(text)
                }
                "error" -> return Result.Failed(Net.str(got.body, "error") ?: "transcription failed")
                else -> Unit
            }
        }
        return Result.Failed("timed out")
    }

    private fun failure(r: Net.Reply, c: Credential, ring: Ring, stage: String): Result {
        when (r.status) {
            // 429 IS ALIVE. Rest it, never condemn it: a ring that buries throttled keys eats
            // itself in an afternoon.
            Status.LIMITED -> ring.rest(c, 60_000)
            Status.REJECTED -> ring.condemn(c)
            else -> Unit
        }
        return Result.Failed("$stage failed (${r.code})")
    }
}

/**
 * THE TWO ENGINES.
 *
 * Edge is not here. The brief had three and Baba's instruction was two, Speechify or Hume, and an
 * engine nobody chose is a page nobody swipes past.
 */
object Engines {

    /** More voices than any account has. A ceiling on the scan, not a claim about the reply. */
    private const val MAX_VOICES = 500

    const val SPEECHIFY = "speechify"
    const val HUME = "hume"

    /**
     * SPEECHIFY: THE EIGHT CURATED SEATS, AND THEY ARE NOT FETCHED.
     *
     * v6 AND v7 RETURNED NO VOICES AT ALL, and the reason is one character.
     *
     * The eight curated voices are `beatrice_32`, `imogen_32` and so on. I looked for `beatrice`.
     * The catalogue does contain a bare `beatrice`… no it does not — it contains `edmund`,
     * `dominic` and `harper` as bare ids and nothing else from the list, so the search matched
     * nothing and the screen said "no voices came back". `MAHA_TRANSCRIBE_STREAMLIT`'s
     * `ttt/providers/speechify.py` has had the right ids since 24.8.2026 and I did not read it
     * until the app failed on the phone.
     *
     * SO THE LIST IS NOT FETCHED ANY MORE. It is the eight seats, written down, exactly as MAHA
     * writes them down. Walking 992 voices across five pages to find eight known names is work
     * that can fail, and it did.
     *
     * THE MODEL FOLLOWS THE ID. `simba-3.2` answers HTTP 400 for any voice whose id does not end
     * `_32` — "the selected voice is not available for simba-3.2" — and that is almost the whole
     * catalogue. The rule is the suffix, not a global default.
     */
    private val SPEECHIFY_SEATS = listOf(
        "beatrice_32" to "Beatrice",
        "imogen_32" to "Imogen",
        "harper_32" to "Harper",
        "geffen_32" to "Geffen",
        "edmund_32" to "Edmund",
        "hugh_32" to "Hugh",
        "dominic_32" to "Dominic",
        "wyatt_32" to "Wyatt",
    )

    /** MAHA's `model_for`: the suffix decides, never a global setting. */
    fun modelFor(voiceId: String): String =
        if (voiceId.endsWith("_32")) "simba-3.2" else "simba-english"

    fun speechifyVoices(ring: Ring): Pair<List<Voice>, String> {
        if (ring.current() == null) return emptyList<Voice>() to "no Speechify key"
        return SPEECHIFY_SEATS.map { (id, name) ->
            Voice(SPEECHIFY, id, name, modelFor(id))
        } to ""
    }

    /**
     * HUME: FETCHED, BECAUSE THE ACCOUNT DECIDES WHAT IS IN IT.
     *
     * AND IT SAYS WHY WHEN IT FAILS. v6 returned an empty list for every possible reason — no key,
     * a 403 from Cloudflare, a throttled account, a body that did not parse — and the screen said
     * "no voices came back" for all of them. That is a message that cannot be acted on, and it is
     * the reason this had to be debugged from a desk instead of from the phone.
     */
    fun humeVoices(ring: Ring): Pair<List<Voice>, String> {
        val c = ring.current() ?: return emptyList<Voice>() to "no Hume key"
        val r = Net.get(
            "https://api.hume.ai/v0/tts/voices?provider=HUME_AI&page_size=100",
            mapOf("X-Hume-Api-Key" to c.key),
        )
        if (!r.ok) {
            when (r.status) {
                Status.REJECTED -> ring.condemn(c)
                Status.LIMITED -> ring.rest(c, 60_000)
                else -> Unit
            }
            return emptyList<Voice>() to "Hume: ${Providers.explain(r.code, r.body)} (${r.code})"
        }
        val out = ArrayList<Voice>()
        var from = 0
        for (unused in 0 until MAX_VOICES) {
            val at = r.body.indexOf("\"id\":", from)
            if (at < 0) break
            val chunk = r.body.substring(at, minOf(r.body.length, at + 1200))
            val id = Net.str(chunk, "id")
            val name = Net.str(chunk, "name")
            if (id != null && name != null) out.add(Voice(HUME, id, name))
            from = at + 5
        }
        val list = out.distinctBy { it.id }
        // Hume publishes no preview clips, so a voice is heard by speaking THIS cell's own words
        // in it. That is the better audition anyway: the question is not what the voice sounds
        // like, it is what this line sounds like in it.
        return list to if (list.isEmpty()) "Hume returned 200 but no voices" else ""
    }

    /**
     * Speak [text] in [voice], returning audio bytes, or null with a reason.
     *
     * IT WALKS THE RING. This is the second half of the bug that made Hume useless: three of the
     * twenty-one accounts on this key ring have an exhausted credit balance, and the first one is
     * account one. v7 asked the ring for a credential, got the exhausted account, condemned it
     * correctly — and then gave up and reported failure, so every attempt failed for ever while
     * eighteen good accounts sat behind it.
     *
     * `provider-router.md` is explicit that a condemned key means RETRY THE SAME REQUEST on the
     * next one. Measured on this ring: accounts 1, 2 and 3 return `400 E0300 zero_credits` and
     * account 4 speaks.
     *
     * HUME PACES AT ABOUT TWELVE SECONDS. A faster call returns 429, which is valid and throttled
     * and rests the account rather than condemning it.
     */
    fun speak(voice: Voice, text: String, ring: Ring): Pair<ByteArray?, String> {
        var lastWhy = "no ${voice.engine} key"
        // Bounded by the size of the ring: every pass either succeeds, returns, or buries one
        // credential, so it cannot walk for ever.
        for (unused in 0 until ring.size.coerceAtLeast(1)) {
            val c = ring.current() ?: return null to lastWhy
            val r = when (voice.engine) {
                SPEECHIFY -> Net.postJson(
                    "https://api.sws.speechify.com/v1/audio/speech",
                    mapOf("Authorization" to "Bearer ${c.key}"),
                    """{"input":${quote(text)},"voice_id":"${voice.id}",""" +
                        """"audio_format":"mp3","model":"${voice.model.ifBlank { modelFor(voice.id) }}"}""",
                )
                HUME -> Net.postJson(
                    "https://api.hume.ai/v0/tts",
                    mapOf("X-Hume-Api-Key" to c.key),
                    """{"utterances":[{"text":${quote(text)},"voice":{"id":"${voice.id}"}}],""" +
                        """"format":{"type":"wav"},"num_generations":1}""",
                )
                else -> return null to "unknown engine"
            }
            if (r.ok) {
                val field = if (voice.engine == SPEECHIFY) "audio_data" else "audio"
                val b64 = Net.str(r.body, field) ?: return null to "no audio in the reply"
                return try {
                    Base64.decode(b64, Base64.DEFAULT) to ""
                } catch (e: IllegalArgumentException) {
                    null to "the audio did not decode"
                }
            }
            lastWhy = "${voice.engine}: ${Providers.explain(r.code, r.body)}"
            when (r.status) {
                Status.LIMITED -> {
                    // Alive, busy. Rested and the next account takes the call.
                    ring.rest(c, 15_000)
                }
                Status.REJECTED -> {
                    // Out of credit, revoked, or wrong. Buried, AND THE REQUEST IS TRIED AGAIN.
                    ring.condemn(c)
                }
                else -> return null to lastWhy
            }
        }
        return null to "$lastWhy — no account left to try"
    }

    /** JSON string escaping, because the text is a spoken phrase and will contain quotes. */
    fun quote(s: String): String {
        val b = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (ch < ' ') b.append("\\u%04x".format(ch.code)) else b.append(ch)
            }
        }
        return b.append('"').toString()
    }
}
