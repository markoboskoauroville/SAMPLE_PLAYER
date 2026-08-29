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

    const val SPEECHIFY = "speechify"
    const val HUME = "hume"

    /**
     * SPEECHIFY: THE EIGHT CURATED VOICES, and the catalogue is not walked.
     *
     * `/v1/voices` returns 985 voices, 50 at a time, alphabetically — so an unwalked call returns
     * A names and an unfiltered walk returns a list nobody can choose from. These eight are the
     * `simba-3.2` set that `MA_READER_SPEECHIFY` already uses, chosen by ear rather than by
     * enumeration.
     *
     * The preview URLs are fetched live rather than hard-coded, because a CDN path is not ours.
     */
    private val SPEECHIFY_SHORTLIST =
        listOf("beatrice", "dominic", "edmund", "geffen", "harper", "hugh", "imogen", "wyatt")

    fun speechifyVoices(ring: Ring): List<Voice> {
        val c = ring.current() ?: return emptyList()
        val r = Net.get(
            "https://api.sws.speechify.com/v1/voices?limit=200",
            mapOf("Authorization" to "Bearer ${c.key}"),
        )
        if (!r.ok) {
            if (r.status == Status.REJECTED) ring.condemn(c)
            if (r.status == Status.LIMITED) ring.rest(c, 60_000)
            return emptyList()
        }
        // Each voice object is one brace-balanced chunk. Splitting on `"id":` and reading forward
        // is enough for four fields and avoids carrying a JSON parser for one screen.
        val out = ArrayList<Voice>()
        for (want in SPEECHIFY_SHORTLIST) {
            val marker = "\"id\":\"$want\""
            val at = r.body.indexOf(marker)
            if (at < 0) continue
            val chunk = r.body.substring(at, minOf(r.body.length, at + 4000))
            val name = Net.str(chunk, "display_name") ?: want
            val model = if (chunk.contains("simba-3.2")) "simba-3.2" else "simba-english"
            out.add(Voice(SPEECHIFY, want, name, model, Net.str(chunk, "preview_audio")))
        }
        return out
    }

    fun humeVoices(ring: Ring): List<Voice> {
        val c = ring.current() ?: return emptyList()
        val r = Net.get(
            "https://api.hume.ai/v0/tts/voices?provider=HUME_AI&page_size=100",
            mapOf("X-Hume-Api-Key" to c.key),
        )
        if (!r.ok) {
            if (r.status == Status.REJECTED) ring.condemn(c)
            if (r.status == Status.LIMITED) ring.rest(c, 60_000)
            return emptyList()
        }
        val out = ArrayList<Voice>()
        var from = 0
        while (true) {
            val at = r.body.indexOf("\"id\":", from)
            if (at < 0) break
            val chunk = r.body.substring(at, minOf(r.body.length, at + 1200))
            val id = Net.str(chunk, "id")
            val name = Net.str(chunk, "name")
            if (id != null && name != null) out.add(Voice(HUME, id, name))
            from = at + 5
        }
        // Hume publishes no preview clips, so a voice is heard by generating this cell's own words
        // in it. That is a better audition anyway: the question is not what the voice sounds like,
        // it is what THIS line sounds like in it.
        return out.distinctBy { it.id }
    }

    /**
     * Speak [text] in [voice], returning WAV or MP3 bytes, or null with a reason.
     *
     * HUME PACES AT ABOUT TWELVE SECONDS. Faster calls come back 429, which is valid and throttled
     * and must never condemn the account.
     */
    fun speak(voice: Voice, text: String, ring: Ring): Pair<ByteArray?, String> {
        val c = ring.current() ?: return null to "no ${voice.engine} key"
        return when (voice.engine) {
            SPEECHIFY -> {
                val r = Net.postJson(
                    "https://api.sws.speechify.com/v1/audio/speech",
                    mapOf("Authorization" to "Bearer ${c.key}"),
                    """{"input":${quote(text)},"voice_id":"${voice.id}",""" +
                        """"audio_format":"mp3","model":"${voice.model}"}""",
                )
                decode(r, "audio_data", c, ring)
            }
            HUME -> {
                val r = Net.postJson(
                    "https://api.hume.ai/v0/tts",
                    mapOf("X-Hume-Api-Key" to c.key),
                    """{"utterances":[{"text":${quote(text)},"voice":{"id":"${voice.id}"}}],""" +
                        """"format":{"type":"wav"},"num_generations":1}""",
                )
                decode(r, "audio", c, ring)
            }
            else -> null to "unknown engine"
        }
    }

    private fun decode(
        r: Net.Reply,
        field: String,
        c: Credential,
        ring: Ring,
    ): Pair<ByteArray?, String> {
        if (!r.ok) {
            when (r.status) {
                Status.LIMITED -> {
                    ring.rest(c, 15_000)
                    return null to "busy, resting that account"
                }
                Status.REJECTED -> {
                    ring.condemn(c)
                    return null to "that account was refused"
                }
                else -> return null to "failed (${r.code})"
            }
        }
        val b64 = Net.str(r.body, field) ?: return null to "no audio in the reply"
        return try {
            Base64.decode(b64, Base64.DEFAULT) to ""
        } catch (e: IllegalArgumentException) {
            null to "the audio did not decode"
        }
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
