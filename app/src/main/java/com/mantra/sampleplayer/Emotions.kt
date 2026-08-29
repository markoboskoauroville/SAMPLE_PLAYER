package com.mantra.sampleplayer

/**
 * HOW THE LINE IS DELIVERED, WHICH IS THE WHOLE REASON HUME IS HERE.
 *
 * Hume's TTS takes a `description` beside each utterance and it is an ACTING DIRECTION rather than
 * a preset: it is read as prose, so "quietly, as if telling a secret" works and so does "furious,
 * barely holding it together". There is no fixed list on their side and no enum to conform to.
 *
 * WHICH IS EXACTLY WHY THERE IS A LIST HERE. A free text box is a blank page, and a blank page in
 * the middle of choosing a voice is the moment somebody gives up and takes the default. These are
 * starting points, grouped the way a director would ask for them, and the box underneath stays
 * editable — pick one and change three words of it.
 *
 * SPEECHIFY IGNORES THIS. Its voices are fixed performances and there is no direction field on
 * `/v1/audio/speech`; sending one would be silently dropped. The chooser hides the whole section
 * for Speechify rather than offering a control that does nothing.
 */
object Emotions {

    data class Direction(val group: String, val label: String, val text: String)

    val ALL: List<Direction> = listOf(
        // ── plain delivery ───────────────────────────────────────────────────────────────────
        Direction("Plain", "Neutral", "even and unhurried, no particular emotion"),
        Direction("Plain", "Clear", "clear and articulate, like reading a notice aloud"),
        Direction("Plain", "Conversational", "relaxed and conversational, as if talking to a friend"),
        Direction("Plain", "Narration", "steady narration, warm but not performed"),

        // ── warm ─────────────────────────────────────────────────────────────────────────────
        Direction("Warm", "Kind", "gentle and kind, unhurried"),
        Direction("Warm", "Affectionate", "affectionate, a smile in the voice"),
        Direction("Warm", "Reassuring", "calm and reassuring, steadying someone"),
        Direction("Warm", "Grateful", "quietly grateful, sincere"),
        Direction("Warm", "Tender", "tender and low, almost private"),

        // ── bright ───────────────────────────────────────────────────────────────────────────
        Direction("Bright", "Happy", "genuinely happy, light and quick"),
        Direction("Bright", "Excited", "excited, can hardly get the words out fast enough"),
        Direction("Bright", "Playful", "playful and teasing"),
        Direction("Bright", "Amused", "amused, on the edge of laughing"),
        Direction("Bright", "Triumphant", "triumphant, delighted with itself"),

        // ── low ──────────────────────────────────────────────────────────────────────────────
        Direction("Low", "Sad", "sad and quiet, slowing at the ends of phrases"),
        Direction("Low", "Grieving", "grieving, barely holding the voice together"),
        Direction("Low", "Weary", "weary, worn out, no energy left for emphasis"),
        Direction("Low", "Disappointed", "disappointed, flat where it should have lifted"),
        Direction("Low", "Regretful", "regretful, admitting something"),

        // ── sharp ────────────────────────────────────────────────────────────────────────────
        Direction("Sharp", "Angry", "angry, clipped and hard on the consonants"),
        Direction("Sharp", "Furious", "furious, barely holding it together"),
        Direction("Sharp", "Firm", "firm and final, leaving no room to argue"),
        Direction("Sharp", "Impatient", "impatient, pushing to get to the end"),
        Direction("Sharp", "Sarcastic", "dry and sarcastic, meaning the opposite"),

        // ── tense ────────────────────────────────────────────────────────────────────────────
        Direction("Tense", "Anxious", "anxious, breath high and shallow"),
        Direction("Tense", "Afraid", "afraid, voice unsteady"),
        Direction("Tense", "Urgent", "urgent, needs to be understood immediately"),
        Direction("Tense", "Suspicious", "suspicious, weighing every word"),
        Direction("Tense", "Whispered", "whispered, as if someone might hear"),

        // ── still ────────────────────────────────────────────────────────────────────────────
        Direction("Still", "Calm", "calm and slow, plenty of space between phrases"),
        Direction("Still", "Meditative", "meditative, soft, guiding a breath"),
        Direction("Still", "Reverent", "reverent, careful with the words"),
        Direction("Still", "Sleepy", "quiet and drowsy, winding down"),

        // ── work ─────────────────────────────────────────────────────────────────────────────
        Direction("Work", "Announcer", "confident announcer, projecting to a room"),
        Direction("Work", "Documentary", "measured documentary narration, authoritative"),
        Direction("Work", "Teaching", "explaining patiently to someone learning"),
        Direction("Work", "Advertising", "upbeat and persuasive, selling something"),
        Direction("Work", "Storytelling", "telling a story to a child, colours in the voice"),
    )

    val GROUPS: List<String> = ALL.map { it.group }.distinct()

    fun of(group: String): List<Direction> = ALL.filter { it.group == group }

    /** Only Hume takes a direction. Offering the control for Speechify would be a lie. */
    fun availableFor(engine: String): Boolean = engine == Engines.HUME
}
