package com.mantra.sampleplayer

/**
 * HOW THE LINE IS DELIVERED, WHICH IS THE WHOLE REASON HUME IS HERE.
 *
 * Hume's TTS takes a `description` beside each utterance and it is an ACTING DIRECTION rather than
 * a preset: it is read as prose, so "quietly, as if telling a secret" works and so does "furious,
 * barely holding it together". There is no fixed list on their side and no enum to conform to.
 *
 * WHICH IS EXACTLY WHY THERE IS A LIST HERE. A free text box is a blank page, and a blank page in
 * the middle of choosing a voice is the moment somebody gives up and takes the default.
 *
 * SPEECHIFY IGNORES THIS. Its voices are fixed performances and there is no direction field on
 * `/v1/audio/speech`; sending one would be silently dropped. The card hides the whole section for
 * Speechify rather than offering a control that does nothing.
 */
object Emotions {

    /**
     * @param glyph  one character, so a direction can be found by shape before it is read.
     *               Nothing here is an emoji: a monospace grid of them is a mess of different
     *               widths and half of them are the same yellow circle at this size.
     * @param spoken what the preview says. "John is angry", not the cell's text — the point of
     *               the preview is the delivery, and the name makes it obvious which voice it is.
     */
    data class Direction(
        val group: String,
        val label: String,
        val glyph: String,
        val text: String,
        val spoken: String,
    )

    val ALL: List<Direction> = listOf(
        // ── plain ────────────────────────────────────────────────────────────────────────────
        Direction("Plain", "Neutral", "—", "even and unhurried, no particular emotion", "is speaking plainly"),
        Direction("Plain", "Clear", "▭", "clear and articulate, like reading a notice aloud", "is reading this clearly"),
        Direction("Plain", "Conversational", "◇", "relaxed and conversational, as if talking to a friend", "is just talking"),
        Direction("Plain", "Narration", "▤", "steady narration, warm but not performed", "is narrating"),

        // ── warm ─────────────────────────────────────────────────────────────────────────────
        Direction("Warm", "Kind", "♡", "gentle and kind, unhurried", "is being kind"),
        Direction("Warm", "Affectionate", "❥", "affectionate, a smile in the voice", "is feeling affectionate"),
        Direction("Warm", "Reassuring", "◠", "calm and reassuring, steadying someone", "is reassuring you"),
        Direction("Warm", "Grateful", "✿", "quietly grateful, sincere", "is grateful"),
        Direction("Warm", "Tender", "◡", "tender and low, almost private", "is being tender"),

        // ── bright ───────────────────────────────────────────────────────────────────────────
        Direction("Bright", "Happy", "☀", "genuinely happy, light and quick", "is happy"),
        Direction("Bright", "Excited", "⚡", "excited, can hardly get the words out fast enough", "is excited"),
        Direction("Bright", "Playful", "◔", "playful and teasing", "is being playful"),
        Direction("Bright", "Amused", "≈", "amused, on the edge of laughing", "is amused"),
        Direction("Bright", "Triumphant", "▲", "triumphant, delighted with itself", "is triumphant"),

        // ── low ──────────────────────────────────────────────────────────────────────────────
        Direction("Low", "Sad", "▽", "sad and quiet, slowing at the ends of phrases", "is sad"),
        Direction("Low", "Grieving", "☂", "grieving, barely holding the voice together", "is grieving"),
        Direction("Low", "Weary", "…", "weary, worn out, no energy left for emphasis", "is exhausted"),
        Direction("Low", "Disappointed", "↓", "disappointed, flat where it should have lifted", "is disappointed"),
        Direction("Low", "Regretful", "◟", "regretful, admitting something", "is full of regret"),

        // ── sharp ────────────────────────────────────────────────────────────────────────────
        Direction("Sharp", "Angry", "✖", "angry, clipped and hard on the consonants", "is angry"),
        Direction("Sharp", "Furious", "‼", "furious, barely holding it together", "is furious"),
        Direction("Sharp", "Firm", "▮", "firm and final, leaving no room to argue", "is being firm"),
        Direction("Sharp", "Impatient", "»", "impatient, pushing to get to the end", "is impatient"),
        Direction("Sharp", "Sarcastic", "¬", "dry and sarcastic, meaning the opposite", "is being sarcastic"),

        // ── tense ────────────────────────────────────────────────────────────────────────────
        Direction("Tense", "Anxious", "◌", "anxious, breath high and shallow", "is anxious"),
        Direction("Tense", "Afraid", "△", "afraid, voice unsteady", "is afraid"),
        Direction("Tense", "Urgent", "!", "urgent, needs to be understood immediately", "is in a hurry"),
        Direction("Tense", "Suspicious", "◐", "suspicious, weighing every word", "is suspicious"),
        Direction("Tense", "Whispered", "◦", "whispered, as if someone might hear", "is whispering"),

        // ── still ────────────────────────────────────────────────────────────────────────────
        Direction("Still", "Calm", "○", "calm and slow, plenty of space between phrases", "is calm"),
        Direction("Still", "Meditative", "◎", "meditative, soft, guiding a breath", "is meditating"),
        Direction("Still", "Reverent", "†", "reverent, careful with the words", "is being reverent"),
        Direction("Still", "Sleepy", "☾", "quiet and drowsy, winding down", "is falling asleep"),

        // ── work ─────────────────────────────────────────────────────────────────────────────
        Direction("Work", "Announcer", "◉", "confident announcer, projecting to a room", "is announcing"),
        Direction("Work", "Documentary", "▦", "measured documentary narration, authoritative", "is narrating a documentary"),
        Direction("Work", "Teaching", "✎", "explaining patiently to someone learning", "is teaching"),
        Direction("Work", "Advertising", "★", "upbeat and persuasive, selling something", "is selling something"),
        Direction("Work", "Storytelling", "❦", "telling a story to a child, colours in the voice", "is telling a story"),
    )

    val GROUPS: List<String> = ALL.map { it.group }.distinct()

    fun of(group: String): List<Direction> = ALL.filter { it.group == group }

    fun byText(text: String): Direction? = ALL.firstOrNull { it.text == text }

    /**
     * What the preview says: the voice's own name and what it is doing.
     *
     * Short on purpose. Auditioning a voice across eight emotions is eight calls, and Hume paces at
     * about twelve seconds — a long sentence would make that two minutes of waiting to hear four
     * seconds of difference.
     */
    fun previewLine(voiceName: String, d: Direction): String = "$voiceName ${d.spoken}."

    /** Only Hume takes a direction. Offering the control for Speechify would be a lie. */
    fun availableFor(engine: String): Boolean = engine == Engines.HUME
}
