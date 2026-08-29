package com.mantra.sampleplayer

import java.security.MessageDigest

/**
 * THE KEY PARSER, PORTED LINE FOR LINE FROM `Key_Tester/KeyParser.kt`.
 *
 * Not rewritten. `keyring.md` 9 says a rebuilt ring has already reintroduced a bug in a session
 * that had been told to read the file that solves it, and this file is Kotlin talking to Kotlin,
 * so there is no excuse for a translation.
 *
 * WHY SHAPE AND NOT WHITESPACE. The key file is a working note. It has account names, dates, the
 * word CANCELLED, blank lines and pasted URLs in it. A whitespace split on a real note has
 * genuinely produced attempts to authenticate with the word *cafeteria* and with a Google
 * `srsltid` tracking token — and the Speechify note in this account begins with exactly such a
 * URL. That token reaches [classify] and comes back `unknown`, which is a provider nothing asks
 * for, so it is carried and never sent anywhere. That is the deny-by-default working.
 */
object KeyParser {

    private val HEX32 = Regex("[0-9a-fA-F]{32}")
    private val GOOGLE = Regex("(AQ\\.[0-9A-Za-z._-]{20,}|AIza[0-9A-Za-z_-]{20,})")
    private val ANTHROPIC = Regex("sk-ant-[0-9A-Za-z_-]{20,}")
    private val OPENAI = Regex("sk-(?!ant-)[0-9A-Za-z_-]{20,}")
    private val GROQ = Regex("gsk_[0-9A-Za-z_-]{20,}")
    private val GITHUB = Regex("(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[0-9A-Za-z_]{20,})")
    private val SK_UNDERSCORE = Regex("sk_[0-9A-Za-z_-]{16,}")
    private val LOOSE = Regex("[A-Za-z0-9._-]{24,220}")

    private val SEP = Regex("[\\s,;:\"'=|\\[\\](){}<>]+")

    data class Found(
        val key: String,
        val providerId: String,
        val label: String,
        val secret: String? = null,
    )

    /**
     * `sk_` IS SHARED, AND LENGTH IS THE ONLY THING SEPARATING TWO COMPANIES.
     *
     * Speechify and ElevenLabs both prefix with `sk_`. Shape cannot tell them apart, so the split
     * is at 44 characters. This account's Speechify keys are 46, so they land correctly — but a
     * future 43-character key would silently become ElevenLabs, and the fallback chain is what
     * catches that rather than this function.
     */
    fun classify(token: String): String? = when {
        ANTHROPIC.matches(token) -> "anthropic"
        GOOGLE.matches(token) -> "gemini"
        GROQ.matches(token) -> "groq"
        GITHUB.matches(token) -> "github"
        SK_UNDERSCORE.matches(token) -> if (token.length >= 44) "speechify" else "elevenlabs"
        OPENAI.matches(token) -> "openai"
        HEX32.matches(token) -> "assemblyai"
        LOOSE.matches(token) && token.any { it.isDigit() } && token.any { it.isLetter() } -> "unknown"
        else -> null
    }

    private fun lineHasKey(line: String): Boolean =
        line.split(SEP).any { classify(it.trim().trim('.', '-', '_')) != null }

    private fun nextNonEmpty(lines: List<String>, from: Int): Int {
        var j = from
        while (j < lines.size && lines[j].trim().isEmpty()) j++
        return j
    }

    fun extract(text: String): List<Found> {
        val lines = text.split("\n")
        val out = LinkedHashMap<String, Found>()
        val consumed = HashSet<String>()

        // PASS 1 — HUME ACCOUNT PAIRS. An api key and a secret key under an account name, and the
        // pair is the unit. Testing the api key alone cannot confirm the secret, so a ring that
        // stores single strings cannot represent a Hume account at all.
        var i = 0
        var prevNonEmpty = ""
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.equals("API key", ignoreCase = true)) {
                val accountName = prevNonEmpty
                val aIdx = nextNonEmpty(lines, i + 1)
                val apiKey = if (aIdx < lines.size) lines[aIdx].trim() else ""
                var k = aIdx + 1
                while (k < lines.size && !lines[k].trim().equals("Secret key", ignoreCase = true)) k++
                val sIdx = nextNonEmpty(lines, k + 1)
                val secret = if (k < lines.size && sIdx < lines.size) lines[sIdx].trim() else ""
                if (apiKey.isNotEmpty() && secret.isNotEmpty()) {
                    if (!out.containsKey(apiKey)) {
                        out[apiKey] = Found(apiKey, "hume", accountName, secret)
                    }
                    consumed.add(apiKey)
                    consumed.add(secret)
                    prevNonEmpty = ""
                    i = sIdx + 1
                    continue
                }
            }
            if (t.isNotEmpty()) prevNonEmpty = t
            i++
        }

        // PASS 2 — SINGLE TOKENS, skipping whatever the pairing already took.
        //
        // THE LABEL IS THE LINE ABOVE, and that is where the provenance Baba wanted already lives.
        // Every one of the twenty-one Speechify keys carries its own account name on the previous
        // line, six to twenty-three characters, and this has been reading them all along. The
        // srsltid parameter in the file's first URL is not an account id and is not shown.
        for (idx in lines.indices) {
            val line = lines[idx]
            val label = run {
                if (idx == 0) return@run ""
                val prev = lines[idx - 1].trim()
                if (prev.isEmpty() || lineHasKey(prev)) "" else prev
            }
            for (raw in line.split(SEP)) {
                val tok = raw.trim().trim('.', '-', '_')
                if (tok.isEmpty() || tok == "DELETED" || out.containsKey(tok) || tok in consumed) continue
                val id = classify(tok) ?: continue
                out[tok] = Found(tok, id, label)
            }
        }
        return out.values.toList()
    }
}

/** What a response says about the key that made it. */
enum class Status { WORKING, REJECTED, LIMITED, OFFLINE, OTHER }

/**
 * THE STATUS MAPPING, WHICH IS WHERE REINVENTED RINGS GO WRONG.
 *
 * Ported from `Key_Tester/Providers.kt`. Three of these lines each cost a measurement:
 *
 *   429 IS ALIVE. A throttled key is a healthy key with a busy minute. A ring that condemns it
 *   eats ten keys in an afternoon and reports that every key is finished.
 *
 *   403 CARRYING 1010 IS CLOUDFLARE, NOT THE KEY. api.hume.ai answers a request with no
 *   User-Agent that way on every endpoint. Measured 21 of 21 both ways: it reads as an entire
 *   dead account list and is nothing to do with the credentials.
 *
 *   400 WITH CREDIT WORDS IS DEATH. An empty Hume account returns 400/E0300, not 401.
 */
object Classify {

    private val CREDIT_WORDS = listOf("credit", "balance", "quota", "insufficient", "e0300", "payment")

    fun status(code: Int, body: String): Status {
        val b = body.lowercase()
        return when {
            code == -1 -> Status.OFFLINE
            code == 403 && ("1010" in b || "cloudflare" in b) -> Status.OTHER
            code in 200..299 -> Status.WORKING
            code == 401 || code == 402 || code == 403 -> Status.REJECTED
            code == 429 -> Status.LIMITED
            code == 400 && CREDIT_WORDS.any { it in b } -> Status.REJECTED
            code == 400 -> Status.OTHER
            code == 404 -> Status.OTHER
            code >= 500 -> Status.OTHER
            else -> Status.OTHER
        }
    }
}

/** One credential. [secret] is set only for Hume, where the pair is the unit. */
data class Credential(val key: String, val secret: String?, val label: String) {

    /** First six and last four, never the middle. The only form that reaches a screen or a log. */
    fun masked(): String =
        if (key.length <= 12) "*".repeat(key.length)
        else key.take(6) + "…" + key.takeLast(4)

    /** SHA-256, so the dead list is a file of fingerprints and never a file of keys. */
    fun fingerprint(): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

/**
 * THE RING. It walks forward, never backwards into a key it has buried, and it writes down where
 * it got to.
 *
 * TWO RULES THAT COST SOMEBODY AN EVENING EACH:
 *
 *   NEVER TEST SPECULATIVELY. Use the first key not known dead and let a real request find out. A
 *   dead key should cost one wasted call in its entire life. The "check all keys on startup"
 *   screen spends N calls to learn what the next real request would have said for free.
 *
 *   ONE KEY PER JOB, NEVER ONE KEY PER CALL. An AssemblyAI upload belongs to the account that
 *   made it. A ring asked for a key on every call uploaded on account A and submitted on account
 *   B, got `403 Cannot access uploaded file`, read it as a dead key, and walked one clip through
 *   six good accounts marking all six dead. Take one key at the start of a job, hold it through
 *   upload, submit, poll and download, and if it genuinely dies RESTART the job rather than
 *   continuing it on an account that cannot see the upload.
 */
class Ring(credentials: List<Credential>) {

    private val all = credentials.toList()
    private val dead = LinkedHashSet<String>()
    private val resting = LinkedHashMap<String, Long>()
    private var active = 0

    val size: Int get() = all.size
    fun deadCount(): Int = dead.size

    /** The credential to use for the next JOB, or null when the ring is finished. */
    fun current(now: Long = System.currentTimeMillis()): Credential? {
        for (offset in all.indices) {
            val i = (active + offset) % all.size
            val c = all[i]
            if (c.fingerprint() in dead) continue
            val until = resting[c.fingerprint()]
            if (until != null && until > now) continue
            active = i
            return c
        }
        return null
    }

    /** 401/402/403 with no Cloudflare marker. Condemned, and the caller retries the request. */
    fun condemn(c: Credential) {
        dead.add(c.fingerprint())
        active = (active + 1) % all.size.coerceAtLeast(1)
    }

    /** 429. Rested, NEVER condemned, and the next job takes the next key. */
    fun rest(c: Credential, forMs: Long, now: Long = System.currentTimeMillis()) {
        resting[c.fingerprint()] = now + forMs
        active = (active + 1) % all.size.coerceAtLeast(1)
    }

    /**
     * Credit gets topped up, so a permanent condemnation that cannot be undone is a bug wearing a
     * rule's clothing.
     */
    fun revive() {
        dead.clear()
        resting.clear()
        active = 0
    }

    fun report(now: Long = System.currentTimeMillis()): String {
        val live = all.count { it.fingerprint() !in dead && (resting[it.fingerprint()] ?: 0L) <= now }
        return "$live of ${all.size} usable"
    }
}
