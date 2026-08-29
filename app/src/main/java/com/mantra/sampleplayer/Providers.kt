package com.mantra.sampleplayer

import android.util.Base64

/**
 * THE PROVIDER TABLE, PORTED FROM `Key_Tester/Providers.kt`.
 *
 * Not rewritten. Every row is a URL and a header shape that somebody has already been wrong about
 * once, and the comments in that file record which. The three that cost the most:
 *
 *   ASSEMBLYAI TAKES THE RAW KEY. `authorization: <key>`, no `Bearer`. A 401 on a key that looks
 *   perfectly good is almost always this.
 *
 *   SPEECHIFY IS TESTED ON `/v1/voices`, NOT `/v1/models`, which returns `404 page not found` and
 *   reads as a dead key.
 *
 *   HUME IS A PAIR. Testing the API key alone proves nothing about the secret. The real test is
 *   the token endpoint, which returns 200 with an access token only when both belong together.
 *
 * The whole table is kept, not only the four providers this app calls. A key note contains
 * whatever it contains, and a tester that says "unknown" about a GitHub token it could perfectly
 * well have checked is a tester that makes you go and find another tool.
 */
object Providers {

    data class Provider(
        val id: String,
        val display: String,
        val testUrl: (String) -> String,
        val headers: (String) -> Map<String, String>,
    )

    val ALL: List<Provider> = listOf(
        Provider(
            "anthropic", "Anthropic",
            { "https://api.anthropic.com/v1/models" },
            { k -> mapOf("x-api-key" to k, "anthropic-version" to "2023-06-01") },
        ),
        Provider(
            "openai", "OpenAI",
            { "https://api.openai.com/v1/models" },
            { k -> mapOf("authorization" to "Bearer $k") },
        ),
        Provider(
            "groq", "Groq",
            { "https://api.groq.com/openai/v1/models" },
            { k -> mapOf("authorization" to "Bearer $k") },
        ),
        Provider(
            "gemini", "Google (AIza / AQ.)",
            { k -> "https://generativelanguage.googleapis.com/v1beta/models?key=$k" },
            { emptyMap() },
        ),
        Provider(
            "assemblyai", "AssemblyAI",
            { "https://api.assemblyai.com/v2/transcript?limit=1" },
            // THE RAW KEY. No Bearer. This one line is the most common hour lost here.
            { k -> mapOf("authorization" to k) },
        ),
        Provider(
            "elevenlabs", "ElevenLabs",
            { "https://api.elevenlabs.io/v1/user" },
            { k -> mapOf("xi-api-key" to k) },
        ),
        Provider(
            "speechify", "Speechify",
            // /v1/voices, NOT /v1/models, which 404s and looks like a dead key.
            { "https://api.sws.speechify.com/v1/voices?limit=1" },
            { k -> mapOf("authorization" to "Bearer $k") },
        ),
        Provider(
            "hume", "Hume AI (API + Secret)",
            { "https://api.hume.ai/v0/tts/voices?page_size=1" },
            { k -> mapOf("X-Hume-Api-Key" to k) },
        ),
        Provider(
            "github", "GitHub",
            { "https://api.github.com/user" },
            { k -> mapOf("authorization" to "Bearer $k", "accept" to "application/vnd.github+json") },
        ),
    )

    /**
     * `sk_` IS SHARED, SO A REJECTION IS NOT THE LAST WORD.
     *
     * Speechify and ElevenLabs both use it and only length separates them. A key on the wrong side
     * of 44 characters is tested against the wrong host and comes back 401, which looks exactly
     * like a dead key. Try the other one before saying so.
     */
    val FALLBACKS: Map<String, List<String>> = mapOf(
        "speechify" to listOf("speechify", "elevenlabs"),
        "elevenlabs" to listOf("elevenlabs", "speechify"),
    )

    fun byId(id: String): Provider? = ALL.firstOrNull { it.id == id }

    data class Result(val status: Status, val providerId: String, val code: Int, val detail: String)

    /**
     * Test one credential, walking the fallback chain where the shape is ambiguous.
     *
     * Hume goes to the token endpoint instead, because the pair is the unit and only that call can
     * prove the two halves belong together.
     */
    fun test(c: Credential, providerId: String): Result {
        if (providerId == "hume") return testHumePair(c)
        val chain = FALLBACKS[providerId] ?: listOf(providerId)
        var last = Result(Status.OTHER, providerId, 0, "no provider")
        for (id in chain) {
            val p = byId(id) ?: continue
            val r = Net.get(p.testUrl(c.key), p.headers(c.key))
            last = Result(r.status, id, r.code, explain(r.code, r.body))
            if (r.status != Status.REJECTED) return last
        }
        return last
    }

    /**
     * THE ONLY TEST THAT PROVES A HUME ACCOUNT.
     *
     * `POST /oauth2-cc/token`, Basic base64(apiKey:secret), `grant_type=client_credentials`. A 200
     * carrying an access token proves both keys are valid AND that they belong to each other.
     *
     * `Base64.NO_WRAP` is not optional: the default wraps at 76 characters and a wrapped
     * Authorization header is a corrupt one.
     */
    private fun testHumePair(c: Credential): Result {
        val secret = c.secret
            ?: return Result(Status.OTHER, "hume", 0, "no secret key: a Hume account is a pair")
        val basic = Base64.encodeToString("${c.key}:$secret".toByteArray(), Base64.NO_WRAP)
        val r = Net.postForm(
            "https://api.hume.ai/oauth2-cc/token",
            mapOf("Authorization" to "Basic $basic"),
            "grant_type=client_credentials",
        )
        val ok = r.ok && r.body.contains("access_token")
        return Result(
            if (ok) Status.WORKING else r.status,
            "hume",
            r.code,
            if (ok) "pair confirmed" else explain(r.code, r.body),
        )
    }

    /**
     * What a code and a body mean, in words rather than in numbers.
     *
     * The Cloudflare line is the one that matters: a 403 carrying 1010 says nothing whatever about
     * the key, and without this it reads as an entire dead account list.
     */
    fun explain(code: Int, body: String): String {
        val b = body.lowercase()
        return when {
            code == -1 -> "no network"
            code == 403 && ("1010" in b || "cloudflare" in b) ->
                "blocked by Cloudflare, not by the key"
            code in 200..299 -> "working"
            code == 401 -> "refused: wrong, revoked, or the wrong provider for this shape"
            code == 402 -> "refused: unpaid"
            code == 403 -> "refused: not permitted"
            code == 429 -> "busy: throttled, and still a good key"
            code == 400 && Classify.status(code, body) == Status.REJECTED -> "out of credit"
            code == 400 -> "the request was wrong, not the key"
            code == 404 -> "wrong endpoint for this provider"
            code >= 500 -> "their server, not the key"
            else -> "HTTP $code"
        }
    }
}
