package com.mantra.sampleplayer

/**
 * WHAT A TEXT FILE HAS TO BECOME BEFORE A VOICE CAN READ IT.
 *
 * A file picked off a phone is not a line of dialogue. It has a byte-order mark, Windows line
 * endings, a trailing newline, and quite possibly two hundred kilobytes of something that was
 * never meant to be spoken. All of that reaches the engine as characters and some of it is billed.
 */
object Text {

    /**
     * Speechify truncates at two thousand characters and bills what it was sent.
     *
     * So the cut happens here, where it can be counted and reported, rather than silently at the
     * far end. Hume has no published limit and is paced at about twelve seconds a call, which
     * makes a very long passage expensive in a different currency.
     */
    const val MAX_CHARS = 2000

    /**
     * Clean a file into something speakable, or empty if there is nothing there.
     *
     * NEWLINES BECOME SPACES rather than being kept. A voice reads a line break as nothing at all,
     * so a paragraph broken across four lines arrives as four fragments run together with no
     * spaces where the breaks were — "the end ofone line" — which is a fault that sounds like a
     * bad voice rather than like a bad import.
     */
    fun forSpeaking(raw: String): String {
        val cleaned = raw
            // A UTF-8 byte-order mark reaches the engine as a character and is read as one.
            .removePrefix("\uFEFF")
            .replace('\u00A0', ' ')
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()
        return if (cleaned.length <= MAX_CHARS) cleaned else cut(cleaned)
    }

    /**
     * Cut at the last sentence end before the limit, or the last space, or the limit.
     *
     * Cutting mid-word gives a voice half a word to pronounce and it will try. A sentence boundary
     * is the only cut that sounds deliberate.
     */
    private fun cut(text: String): String {
        val window = text.take(MAX_CHARS)
        val sentence = window.lastIndexOfAny(charArrayOf('.', '!', '?', '\u2026'))
        if (sentence > MAX_CHARS / 2) return window.take(sentence + 1)
        val space = window.lastIndexOf(' ')
        if (space > MAX_CHARS / 2) return window.take(space).trim()
        return window
    }

    /** What to tell the person, so a silent truncation is never a surprise. */
    fun report(raw: String): String {
        val spoken = forSpeaking(raw)
        val whole = raw.trim().length
        return if (spoken.length < whole) {
            "${spoken.length} of $whole characters — the rest is past the engine's limit"
        } else {
            "${spoken.length} characters"
        }
    }
}
