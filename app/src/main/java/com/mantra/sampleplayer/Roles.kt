package com.mantra.sampleplayer

/**
 * WHAT A VOICE IS FOR, TAKEN OUT OF ITS NAME.
 *
 * Hume publishes four tags and none of them is a role: `LANGUAGE`, `ACCENT`, `GENDER`, `AGE`. I
 * checked every field on all 160 voices and said there was no role data. That was wrong — **it is
 * in the name**. "Nature Documentary Narrator", "Male English Actor", "Wrestling Announcer",
 * "Female Meditation Guide". Baba read the list and I had only read the tags.
 *
 * 77 of the 160 carry a role word. The other 83 are personal names — Anna, Colton Rivers, Taro —
 * and those get NO role rather than a guessed one. A blank is a fact; a guess is not.
 *
 * THE ORDER IS THE DESIGN. First match wins, so the specific word must come before the general
 * one: "Nature Documentary Narrator" is a documentary voice rather than merely a narrator, and
 * "Movie Trailer Narrator" is a trailer. Sorting this list by hand is most of the work in it.
 */
object Roles {

    private val TABLE: List<Pair<String, List<String>>> = listOf(
        "trailer" to listOf("trailer"),
        "documentary" to listOf("documentar", "naturalist"),
        "audiobook" to listOf("children's book", "storyteller", "campfire", "folk"),
        "narrator" to listOf("narrator", "narrador", "cronista", "narration"),
        "actor" to listOf("actor", "actress", "protagonist", "lead "),
        "announcer" to listOf("announcer", "town crier", "radio"),
        "podcast" to listOf("podcast", " host", "anfitriona"),
        "journalist" to listOf("journalist", "reporter"),
        "comedian" to listOf("comedian", "comic", "sitcom", "unserious"),
        "influencer" to listOf("influencer", "tiktok"),
        "teacher" to listOf("professor", "instructor", "teacher", "archivist",
            "aprendiz", "consulente", "consultant"),
        "meditation" to listOf("asmr", "meditation", "guru", "serene", "relaxing",
            "silêncio", "silencio"),
        "assistant" to listOf("assistant", "butler", "robo", " agent"),
        "conversational" to listOf("conversational"),
        "character" to listOf("dungeon master", "troll", "wizard", "pirate", "vampire",
            "ghost", "peasant", "medieval", "priest", "cowgirl", "cowboy",
            "knight", "frat bro", "tough guy", "wrestl"),
        "politician" to listOf("politician", "president"),
        "chef" to listOf("chef"),
        "director" to listOf("director"),
        "musician" to listOf("musician"),
        "philosopher" to listOf("philosopher", "intellectual", "viajante"),
        "explorer" to listOf("explorer"),
        "doctor" to listOf("dr. ", "doctor"),
        "parent" to listOf("mother", "father", "aunt ", "uncle", "grandma"),
    )

    /** The role in this name, or null when the name is only a name. */
    fun of(name: String): String? {
        val low = name.lowercase()
        for ((role, words) in TABLE) {
            if (words.any { low.contains(it) }) return role
        }
        return null
    }

    /**
     * The role as a facet tag, so the chips, the counts and the search all pick it up with no
     * further plumbing. Everything else in the browser already works on `facet:value` strings.
     */
    fun tagFor(name: String): String? = of(name)?.let { "role:$it" }
}

/**
 * A VOICE, WRITTEN OUT IN FULL, FOR PASTING SOMEWHERE ELSE.
 *
 * Baba works with several chats at once, and the one making an audiobook needs to know how to
 * reach a voice: which engine, which id, which endpoint, which header, what the body looks like,
 * what comes back, and what it can be asked to do. Without this he retypes it, or the other chat
 * researches it again — and it has already been researched, at the cost of three releases.
 *
 * SO THE CARD CARRIES ITS OWN SPECIFICATION AND A BUTTON THAT COPIES IT. Everything measured
 * rather than remembered: the endpoints, the header shapes, the field the audio comes back in, the
 * billing unit, and the pacing.
 */
object Spec {

    /**
     * Initials from the name, for the glyph.
     *
     * A PLACEHOLDER, AND HONEST ABOUT BEING ONE. Two letters is not a portrait. What it does buy
     * is that a list of a hundred cards has something in the same place on every one of them, so
     * the eye learns where to look before anything is drawn there properly.
     *
     * "Nature Documentary Narrator" gives ND rather than NDN: two letters at this size, and the
     * first two words carry the sense.
     */
    fun initials(name: String): String {
        val words = name.split(Regex("[^A-Za-z]+")).filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> "??"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }

    fun text(v: VoiceInfo): String {
        val b = StringBuilder()
        val hume = v.engine == Engines.HUME

        b.appendLine("VOICE SPECIFICATION — ${v.name}")
        b.appendLine()
        b.appendLine("engine        ${if (hume) "Hume" else "Speechify"}")
        b.appendLine("voice id      ${v.id}")
        if (!hume) b.appendLine("model         ${Engines.modelFor(v.id)}")

        b.appendLine()
        b.appendLine("HOW TO REACH IT")
        if (hume) {
            b.appendLine("  endpoint    POST https://api.hume.ai/v0/tts")
            b.appendLine("  auth        X-Hume-Api-Key: <api key>")
            b.appendLine("              an account is the API KEY AND ITS SECRET; the key alone")
            b.appendLine("              authenticates this call but proves nothing about the account")
            b.appendLine("  body        {\"utterances\":[{\"text\":\"…\",")
            b.appendLine("                              \"voice\":{\"id\":\"${v.id}\"},")
            b.appendLine("                              \"description\":\"<delivery, as prose>\"}],")
            b.appendLine("               \"format\":{\"type\":\"wav\"},\"num_generations\":1}")
            b.appendLine("  returns     base64 WAV in generations[0].audio")
            b.appendLine("  billing     characters. Roughly 1000 characters per minute of speech")
            b.appendLine("  pacing      about 12 seconds between calls; 429 means throttled, not dead")
            b.appendLine("  out of credit  400 with code E0300 / \"Exhausted credit balance\".")
            b.appendLine("              A free account's credit is granted ONCE and does not reset")
        } else {
            b.appendLine("  endpoint    POST https://api.sws.speechify.com/v1/audio/speech")
            b.appendLine("  auth        Authorization: Bearer <sk_ key>")
            b.appendLine("  body        {\"input\":\"…\",\"voice_id\":\"${v.id}\",")
            b.appendLine("               \"audio_format\":\"wav\",\"model\":\"${Engines.modelFor(v.id)}\"}")
            b.appendLine("  returns     base64 audio in audio_data, plus billable_characters_count")
            b.appendLine("  billing     characters, and the reply states how many it charged for")
            b.appendLine("  model rule  simba-3.2 answers HTTP 400 for any voice whose id does not")
            b.appendLine("              end _32 — that is 984 of the 992 voices")
            b.appendLine("  no delivery field. Emotion cannot be directed; the voice is fixed")
        }
        b.appendLine("  user-agent  required. api.hume.ai is behind Cloudflare and answers")
        b.appendLine("              403 code 1010 without one")

        b.appendLine()
        b.appendLine("WHAT IT IS")
        for ((label, value) in listOf(
            "gender" to v.gender, "age" to v.age,
            "language" to v.language, "accent" to v.accent,
        )) {
            if (value.isNotBlank()) b.appendLine("  ${label.padEnd(11)} $value")
        }
        Roles.of(v.name)?.let { b.appendLine("  ${"role".padEnd(11)} $it   (read from the name)") }
        val rest = v.tags.filter { it.contains(':') && !it.startsWith("role:") }
        if (rest.isNotEmpty()) {
            b.appendLine("  ${"tags".padEnd(11)} ${rest.joinToString(", ")}")
        }

        if (hume) {
            b.appendLine()
            b.appendLine("HOW IT CAN BE DIRECTED")
            b.appendLine("  The description field is read as PROSE, not matched against a list, so")
            b.appendLine("  anything sayable to an actor works. These are starting points:")
            b.appendLine()
            for (group in Emotions.GROUPS) {
                val labels = Emotions.of(group).joinToString(", ") { it.label }
                b.appendLine("    ${group.lowercase().padEnd(6)} $labels")
            }
            b.appendLine()
            b.appendLine("  Example: \"furious, barely holding it together\"")
            b.appendLine()
            b.appendLine("  A LINE CAN TURN PART WAY THROUGH. Send several utterances in ONE")
            b.appendLine("  request, each with its own description, and Hume joins them itself —")
            b.appendLine("  one seamless piece of audio rather than files stitched together.")
        }

        b.appendLine()
        b.appendLine("Measured from the live API, not from documentation. Sample Player.")
        return b.toString()
    }
}
