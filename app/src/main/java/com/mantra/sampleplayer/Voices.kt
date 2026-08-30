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
     * The eight Speechify seats, kept because the model rule depends on the suffix.
     *
     * The voice LIST moved to `Catalogue`, which walks both providers properly with a real JSON
     * parser and keeps every facet they publish. What is left here is the one rule that belongs
     * beside speaking rather than beside listing: `simba-3.2` answers HTTP 400 for any voice whose
     * id does not end `_32`, and that is almost the whole 992-voice catalogue.
     */
    fun modelFor(voiceId: String): String =
        if (voiceId.endsWith("_32")) "simba-3.2" else "simba-english"

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
    /**
     * WAV FROM BOTH ENGINES, NOT MP3.
     *
     * Speechify was asked for mp3 and the bytes were written to a file called `<engine>.wav`, so
     * the app was storing an MP3 under a WAV name — which nothing complained about, because
     * MediaPlayer sniffs the content and plays it happily.
     *
     * It matters for two things that are not playback. **A waveform cannot be drawn from an MP3**
     * without a decoder, so a cell with a generated voice went on showing the shape of the
     * original recording while playing something else. And **an MP3 cannot go into an AudioTrack
     * static buffer**, so the gapless loop could only ever loop Baba's own take.
     *
     * Measured 30.8.2026: `audio_format: "wav"` returns 48 kHz mono 16-bit PCM, and Hume already
     * returns WAV. Both are now drawable and both are loopable. The file is about six times the
     * size of the mp3, which for a spoken phrase is a few hundred kilobytes.
     */
    fun speak(
        voice: Voice,
        text: String,
        ring: Ring,
        direction: String = "",
    ): Pair<ByteArray?, String> {
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
                        """"audio_format":"wav","model":"${voice.model.ifBlank { modelFor(voice.id) }}"}""",
                )
                HUME -> Net.postJson(
                    "https://api.hume.ai/v0/tts",
                    mapOf("X-Hume-Api-Key" to c.key),
                    // THE DESCRIPTION IS THE ACTING DIRECTION and it is the reason Hume is here at
                    // all. It is read as prose rather than matched against an enum, so it is only
                    // sent when there is one: an empty description is not neutral, it is a field
                    // asking to be interpreted.
                    buildString {
                        append("""{"utterances":[{"text":""")
                        append(quote(text))
                        append(""","voice":{"id":"""")
                        append(voice.id)
                        append(""""}""")
                        if (direction.isNotBlank()) {
                            append(""","description":""")
                            append(quote(direction))
                        }
                        append("""}],"format":{"type":"wav"},"num_generations":1}""")
                    },
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
