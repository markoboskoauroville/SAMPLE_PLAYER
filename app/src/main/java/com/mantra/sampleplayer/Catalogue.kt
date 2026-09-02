package com.mantra.sampleplayer

import org.json.JSONObject

/**
 * ONE VOICE, HOWEVER THE PROVIDER DESCRIBES IT.
 *
 * The two catalogues are shaped differently and both are already well organised — the mistake
 * would be to invent a third taxonomy. So this keeps every facet each provider publishes and maps
 * the two vocabularies onto the same four axes where they agree.
 *
 * MEASURED FROM THE LIVE APIS on 29.8.2026, not from documentation:
 *
 *   HUME — 160 voices. Tags are `LANGUAGE` (11 values), `ACCENT` (35), `GENDER` (Male, Female),
 *   `AGE` (Young, Middle-Aged, Old). Accents run from Received Pronunciation and Cockney through
 *   Texas and Nigerian to Pirate and Transylvanian, because a lot of these are character voices.
 *
 *   SPEECHIFY — 992 voices across 36 locales, an even 497 male to 495 female. Tags are namespaced:
 *   `use-case:` (audiobook-long-form, podcast, gaming, meditation, e-learning, and more),
 *   `age:` (teen, young-adult, middle-aged, senior), `timbre:` (bright, deep, warm, soft, crisp,
 *   textured), `pitch:` (low, mid, high), `style:` (friendly, professional, confident, gentle…),
 *   `accent:`, `content-type:`, `fictiongenre:` and `label:`.
 */
data class VoiceInfo(
    val engine: String,
    val id: String,
    val name: String,
    val model: String = "",
    val gender: String = "",
    val age: String = "",
    val language: String = "",
    val accent: String = "",
    /** Everything else the provider says, as `facet:value`, lower case. */
    val tags: Set<String> = emptySet(),
    val preview: String? = null,
) {
    /** One string to search. Built once per voice rather than per keystroke. */
    val haystack: String =
        (name + " " + gender + " " + age + " " + language + " " + accent + " " +
            tags.joinToString(" ")).lowercase()

    /**
     * The flagship set, which is as close to "popular" as either provider actually publishes.
     *
     * Speechify's eight `_32` seats are the ones on its newest model and the ones it puts forward;
     * every other voice returns HTTP 400 for that model. Hume publishes no popularity or featured
     * field at all — no `popular`, no rank, no ordering other than alphabetical — so rather than
     * invent one, Hume voices reach the top of the list by being starred.
     */
    val flagship: Boolean get() = engine == Engines.SPEECHIFY && id.endsWith("_32")
}

/**
 * THE FACETS THE CHOOSER FILTERS ON.
 *
 * Derived from what is in the catalogue rather than hard-coded, so a value the provider adds next
 * month appears without anybody editing a list here. The ORDER is fixed, because a filter row that
 * rearranges itself between sessions is a filter row that has to be read every time.
 */
object Facets {

    const val GENDER = "gender"
    const val AGE = "age"
    const val LANGUAGE = "language"
    const val ACCENT = "accent"
    const val USE = "use-case"
    const val TIMBRE = "timbre"
    const val STYLE = "style"
    const val PITCH = "pitch"

    const val ROLE = "role"

    // ROLE COMES SECOND, after gender. "An actor with a British accent" is the question actually
    // being asked, and the facets should be in the order somebody narrows by rather than the order
    // the providers happen to publish.
    val ORDER = listOf(GENDER, ROLE, AGE, LANGUAGE, ACCENT, USE, TIMBRE, STYLE, PITCH)

    /** Every value present for a facet, sorted, so the chips are stable between openings. */
    fun values(voices: List<VoiceInfo>, facet: String): List<String> {
        val out = sortedSetOf<String>()
        for (v in voices) {
            when (facet) {
                GENDER -> if (v.gender.isNotBlank()) out.add(v.gender)
                AGE -> if (v.age.isNotBlank()) out.add(v.age)
                LANGUAGE -> if (v.language.isNotBlank()) out.add(v.language)
                ACCENT -> if (v.accent.isNotBlank()) out.add(v.accent)
                else -> v.tags.filter { it.startsWith("$facet:") }
                    .forEach { out.add(it.removePrefix("$facet:")) }
            }
        }
        return out.toList()
    }

    /** Does this voice carry [value] on [facet]? */
    fun has(v: VoiceInfo, facet: String, value: String): Boolean = when (facet) {
        GENDER -> v.gender.equals(value, true)
        AGE -> v.age.equals(value, true)
        LANGUAGE -> v.language.equals(value, true)
        ACCENT -> v.accent.equals(value, true)
        else -> "$facet:$value" in v.tags
    }
}

/**
 * SEARCHING AND FILTERING, AND IT IS PURE.
 *
 * Nine hundred and ninety-two voices is a list nobody can scroll, so this is the part that has to
 * be right. Test 1 can walk every rule of it without a network, a screen or a key.
 */
object VoiceSearch {

    /**
     * Words, not a substring.
     *
     * "brit female" should find a British female voice, and a plain `contains` would find nothing
     * because those two words are never adjacent. Every term must appear somewhere in the voice,
     * and each term matches on a prefix so "brit" finds "british" and "med" finds "meditation".
     */
    fun matches(v: VoiceInfo, query: String): Boolean {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return true
        return terms.all { term -> v.haystack.split(Regex("[^a-z0-9]+")).any { it.startsWith(term) } }
    }

    /**
     * The list as it should appear: filtered, searched, and ordered with the useful things first.
     *
     * STARRED FIRST, THEN FLAGSHIP, THEN ALPHABETICAL. A star is the only signal that came from
     * this person rather than from a provider, so it outranks everything.
     */
    fun apply(
        voices: List<VoiceInfo>,
        query: String,
        filters: Map<String, Set<String>>,
        starred: Set<String>,
    ): List<VoiceInfo> {
        return voices
            .filter { v ->
                filters.all { (facet, wanted) ->
                    // WITHIN a facet the values are an OR — "male or female" is everything, which
                    // is what tapping both chips should mean. ACROSS facets they are an AND.
                    wanted.isEmpty() || wanted.any { Facets.has(v, facet, it) }
                }
            }
            .filter { matches(it, query) }
            .sortedWith(
                compareByDescending<VoiceInfo> { key(it) in starred }
                    .thenByDescending { it.flagship }
                    .thenBy { it.name.lowercase() },
            )
    }

    /** Engine and id together, because two providers can and do use the same short name. */
    fun key(v: VoiceInfo): String = "${v.engine}/${v.id}"
}

/** Parsing, kept beside the shapes it parses. */
object Catalogue {

    /**
     * Hume's list, walked to the end.
     *
     * `org.json` rather than reading substrings out of the body. The tag block is nested arrays and
     * the earlier hand-rolled reader could only see flat string fields, which is how v6 shipped a
     * voice list with no gender, no age and no accent on any of it.
     */
    fun hume(ring: Ring): Pair<List<VoiceInfo>, String> {
        val c = ring.current() ?: return emptyList<VoiceInfo>() to "no Hume key"
        val out = ArrayList<VoiceInfo>()
        var page = 0
        var total = 1
        while (page < total && page < 20) {
            val r = Net.get(
                "https://api.hume.ai/v0/tts/voices?provider=HUME_AI&page_size=100&page_number=$page",
                mapOf("X-Hume-Api-Key" to c.key),
            )
            if (!r.ok) {
                if (r.status == Status.REJECTED) ring.condemn(c)
                if (r.status == Status.LIMITED) ring.rest(c, 60_000)
                return out to "Hume: ${Providers.explain(r.code, r.body)}"
            }
            val root = runCatching { JSONObject(r.body) }.getOrNull()
                ?: return out to "Hume sent something that is not JSON"
            total = root.optInt("total_pages", 1)
            val arr = root.optJSONArray("voices_page") ?: break
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                val tags = v.optJSONObject("tags")
                fun first(key: String): String =
                    tags?.optJSONArray(key)?.optString(0).orEmpty()
                val all = sortedSetOf<String>()
                if (tags != null) {
                    for (key in tags.keys()) {
                        val a = tags.optJSONArray(key) ?: continue
                        for (j in 0 until a.length()) {
                            all.add("${key.lowercase()}:${a.optString(j).lowercase()}")
                        }
                    }
                }
                // THE ROLE IS IN THE NAME AND NOWHERE ELSE. Hume publishes LANGUAGE,
                // ACCENT, GENDER and AGE, and none of them says whether a voice is an actor or a
                // narrator — but "Nature Documentary Narrator" and "Male English Actor" do.
                // Added as a tag so the chips, the counts and the search pick it up with no
                // further plumbing: everything downstream already works on facet:value strings.
                val name = v.optString("name")
                Roles.tagFor(name)?.let { all.add(it) }
                out.add(
                    VoiceInfo(
                        engine = Engines.HUME,
                        id = v.optString("id"),
                        name = name,
                        gender = first("GENDER"),
                        age = first("AGE"),
                        language = first("LANGUAGE"),
                        accent = first("ACCENT"),
                        tags = all,
                    ),
                )
            }
            page++
        }
        return out to if (out.isEmpty()) "Hume returned no voices" else ""
    }

    /**
     * Speechify's list, walked by cursor to the end.
     *
     * THE TRAP: `/v1/voices` returns fifty at a time, alphabetically. One call gives fifty names
     * beginning with A and looks like the whole catalogue. Five pages of two hundred gives 992.
     */
    fun speechify(ring: Ring): Pair<List<VoiceInfo>, String> {
        val c = ring.current() ?: return emptyList<VoiceInfo>() to "no Speechify key"
        val out = ArrayList<VoiceInfo>()
        var cursor: String? = null
        var pages = 0
        while (pages < 20) {
            val url = StringBuilder("https://api.sws.speechify.com/v1/voices?limit=200")
            if (cursor != null) url.append("&cursor=").append(cursor)
            val r = Net.get(url.toString(), mapOf("Authorization" to "Bearer ${c.key}"))
            if (!r.ok) {
                if (r.status == Status.REJECTED) ring.condemn(c)
                if (r.status == Status.LIMITED) ring.rest(c, 60_000)
                return out to "Speechify: ${Providers.explain(r.code, r.body)}"
            }
            val root = runCatching { JSONObject(r.body) }.getOrNull()
                ?: return out to "Speechify sent something that is not JSON"
            val arr = root.optJSONArray("voices") ?: break
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                val id = v.optString("id")
                val tags = sortedSetOf<String>()
                v.optJSONArray("tags")?.let { t ->
                    for (j in 0 until t.length()) tags.add(t.optString(j).lowercase())
                }
                // Speechify's locale is the only place the language lives, and it is a code.
                val locale = v.optString("locale")
                out.add(
                    VoiceInfo(
                        engine = Engines.SPEECHIFY,
                        id = id,
                        name = v.optString("display_name").ifBlank { id },
                        model = Engines.modelFor(id),
                        gender = v.optString("gender").replaceFirstChar { it.uppercase() },
                        age = tags.firstOrNull { it.startsWith("age:") }
                            ?.removePrefix("age:").orEmpty(),
                        language = Locales.name(locale),
                        accent = tags.firstOrNull { it.startsWith("accent:") }
                            ?.removePrefix("accent:").orEmpty().ifBlank { locale },
                        // Speechify names are plain — none of its 992 carries a role word,
                        // checked — so its role comes from its own `use-case` tag instead, which
                        // is what that facet has always been.
                        tags = tags + ("locale:" + locale.lowercase()) +
                            tags.filter { it.startsWith("use-case:") }
                                .map { "role:" + it.removePrefix("use-case:") },
                        preview = v.optString("preview_audio").takeIf { it.isNotBlank() },
                    ),
                )
            }
            pages++
            if (!root.optBoolean("has_more") || root.optString("next_cursor").isBlank()) break
            cursor = root.optString("next_cursor")
        }
        return out to if (out.isEmpty()) "Speechify returned no voices" else ""
    }
}

/**
 * Locale codes to language names, for the thirty-six Speechify publishes.
 *
 * Hume names its languages in words and Speechify gives `en-US`. Filtering by "English" has to
 * find both or the facet is two facets wearing one label.
 */
object Locales {

    private val NAMES = mapOf(
        "en" to "English", "fr" to "French", "ru" to "Russian", "es" to "Spanish",
        "it" to "Italian", "ta" to "Tamil", "ja" to "Japanese", "tr" to "Turkish",
        "de" to "German", "sv" to "Swedish", "nl" to "Dutch", "da" to "Danish",
        "hi" to "Hindi", "ur" to "Urdu", "pt" to "Portuguese", "bn" to "Bengali",
        "mr" to "Marathi", "gu" to "Gujarati", "ko" to "Korean", "zh" to "Chinese",
        "ar" to "Arabic", "pl" to "Polish", "nb" to "Norwegian", "no" to "Norwegian",
        "fi" to "Finnish", "cs" to "Czech", "el" to "Greek", "he" to "Hebrew",
        "id" to "Indonesian", "ms" to "Malay", "ro" to "Romanian", "sk" to "Slovak",
        "uk" to "Ukrainian", "vi" to "Vietnamese", "th" to "Thai", "hu" to "Hungarian",
        "ca" to "Catalan", "te" to "Telugu", "kn" to "Kannada", "ml" to "Malayalam",
        "fa" to "Persian", "bg" to "Bulgarian", "hr" to "Croatian", "sr" to "Serbian",
    )

    fun name(locale: String): String {
        if (locale.isBlank()) return ""
        return NAMES[locale.substringBefore('-').lowercase()] ?: locale
    }
}
