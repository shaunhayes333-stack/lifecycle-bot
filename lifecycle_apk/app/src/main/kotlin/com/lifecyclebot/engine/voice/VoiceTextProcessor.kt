package com.lifecyclebot.engine.voice

import com.lifecyclebot.engine.voice.PersonalityVoiceRegistry.VoiceProfile

/**
 * V5.9.495z18 — Voice text pre-processor.
 *
 * Run on EVERY string before it hits ElevenLabs. Goals:
 *   • Strip emojis (or convert to readable words)
 *   • Redact mint addresses & wallet pubkeys
 *   • Redact private-key-like tokens
 *   • Pronounce common Solana tickers correctly via a built-in dictionary
 *   • Read percentages / SOL amounts naturally
 *   • Cap live alerts at <= 20 words where possible
 *   • Inject v3 emotion tags ([shouts], [sighs], [whispers], [laughs]) when
 *     the model supports it (eleven_v3 / multilingual)
 *   • Block forbidden phrases registered against the persona
 *
 * Pure function, no IO.
 */
object VoiceTextProcessor {

    /**
     * Tickers → spoken form. Conservative — only adds entries that the engine
     * mispronounces in the wild. Operator can extend at runtime.
     */
    val TICKER_DICTIONARY: Map<String, String> = mapOf(
        "BONK"  to "bonk",
        "WIF"   to "wiff",
        "JUP"   to "jupe",
        "USDC"  to "U S D C",
        "USDT"  to "U S D T",
        "SOL"   to "sol",
        "PYTH"  to "pith",
        "JTO"   to "jay tee oh",
        "TRUMP" to "T R U M P",   // read as letters, not as the name
        "BIDEN" to "B I D E N",   // read as letters, not as the name
        "PEPE"  to "peppy",
        "SHIB"  to "shib",
        "MEW"   to "mew",
        "POPCAT" to "pop cat",
        "MICHI" to "mee chee",
        "BOOK"  to "book",
        "MOTHER" to "mother",
        "BODEN" to "B O D E N",
        "DOGE"  to "dohj",
        "ETH"   to "ether",
        "BTC"   to "bitcoin",
        "AATE"  to "ay ay tee ee",
    )

    /** Mint addresses look like base58, length 32-44. Conservative regex. */
    private val MINT_RX = Regex("\\b[1-9A-HJ-NP-Za-km-z]{32,44}\\b")
    /** Plausible private-key b58 (longer) — never speak these. */
    private val PK_RX = Regex("\\b[1-9A-HJ-NP-Za-km-z]{60,}\\b")
    /** Numeric-with-decimals SOL amount. */
    private val SOL_AMT_RX = Regex("(?<![A-Za-z])(-?\\d+\\.\\d+)\\s*◎|(-?\\d+\\.\\d+)\\s*SOL\\b", RegexOption.IGNORE_CASE)
    /** Percentages. */
    private val PCT_RX = Regex("([+-]?)(\\d+(?:\\.\\d+)?)%")
    /** Emoji range — broad. */
    private val EMOJI_RX = Regex("[\\p{So}\\p{Cn}\\p{Sk}\\u200d\\ufe0f\\p{Cs}]+")

    data class ProcessResult(
        val text: String,
        val wasRedacted: Boolean,
        val emojiStripped: Int,
        val tickerHits: Int,
        val originalLen: Int,
        val processedLen: Int,
    )

    /**
     * Sanitize a free-form input string for TTS.
     *
     * @param input         raw text from anywhere
     * @param liveAlertCap  if > 0, cap output to this many words (live alerts)
     * @param emotionTagged if true, eligible model — allow [shouts]/[laughs] injection
     * @param profile       used to apply forbidden phrase filter (case-insensitive)
     */
    fun process(
        input: String,
        liveAlertCap: Int = 0,
        emotionTagged: Boolean = false,
        profile: VoiceProfile? = null,
    ): ProcessResult {
        val original = input.trim()
        if (original.isEmpty()) {
            return ProcessResult("", false, 0, 0, 0, 0)
        }

        var s = original
        var redacted = false
        var emojiCount = 0
        var tickerCount = 0

        // 1) Drop private-key-like tokens entirely (security).
        val pkMatches = PK_RX.findAll(s).count()
        if (pkMatches > 0) { redacted = true }
        s = s.replace(PK_RX, "[redacted secret]")

        // 2) Replace mint addresses with a friendly placeholder.
        val mintMatches = MINT_RX.findAll(s).count()
        if (mintMatches > 0) { redacted = true }
        s = s.replace(MINT_RX, "[token mint]")

        // 3) Strip emojis. Count for diagnostics.
        emojiCount = EMOJI_RX.findAll(s).sumOf { it.value.length }
        s = s.replace(EMOJI_RX, " ")

        // 4) Common symbols → spoken.
        s = s
            .replace("◎", " sol ")
            .replace("→", " then ")
            .replace("≥", " at least ")
            .replace("≤", " at most ")
            .replace("&", " and ")
            .replace("/", " per ")
            .replace("×", " by ")
            .replace("$", " ")
            // dashes/bullets that punctuate UI strings
            .replace("·", ". ")
            .replace("—", ", ")
            .replace("–", ", ")

        // 5) SOL amount natural-language.
        s = SOL_AMT_RX.replace(s) { mr ->
            val raw = (mr.groupValues[1].ifBlank { mr.groupValues[2] }).toDoubleOrNull() ?: return@replace mr.value
            "${formatSolAmount(raw)} sol"
        }

        // 6) Percentages.
        s = PCT_RX.replace(s) { mr ->
            val sign = if (mr.groupValues[1] == "-") "down " else if (mr.groupValues[1] == "+") "up " else ""
            val n = mr.groupValues[2].toDoubleOrNull() ?: return@replace mr.value
            sign + formatPct(n)
        }

        // 7) Ticker dictionary — whole-word match, case-insensitive.
        for ((k, v) in TICKER_DICTIONARY) {
            val rx = Regex("\\b" + Regex.escape(k) + "\\b", RegexOption.IGNORE_CASE)
            val before = s
            s = s.replace(rx, v)
            if (s !== before) tickerCount++
        }

        // 8) Forbidden phrase filter (per persona) — case-insensitive substring drop.
        profile?.forbiddenPhraseBank?.forEach { fp ->
            if (fp.isNotBlank()) {
                val rx = Regex(Regex.escape(fp), RegexOption.IGNORE_CASE)
                s = s.replace(rx, " ")
            }
        }

        // 9) Whitespace tidy.
        s = s.replace(Regex("\\s+"), " ").trim()

        // 10) Live-alert word cap.
        if (liveAlertCap > 0) {
            val words = s.split(" ")
            if (words.size > liveAlertCap) {
                s = words.take(liveAlertCap).joinToString(" ")
                if (!s.endsWith(".") && !s.endsWith("!") && !s.endsWith("?")) s = "$s."
            }
        }

        // 11) Emotion-tag injection — only on supported models.
        if (emotionTagged && profile != null) {
            s = injectEmotionTags(s, profile)
        }

        return ProcessResult(
            text = s,
            wasRedacted = redacted,
            emojiStripped = emojiCount,
            tickerHits = tickerCount,
            originalLen = original.length,
            processedLen = s.length,
        )
    }

    private fun formatSolAmount(v: Double): String {
        val abs = kotlin.math.abs(v)
        return when {
            abs == 0.0      -> "zero"
            abs < 0.001     -> "less than a thousandth of a"
            abs < 0.01      -> "%.4f".format(v).trimEnd('0').trimEnd('.')
            abs < 1.0       -> "%.3f".format(v).trimEnd('0').trimEnd('.')
            abs < 10.0      -> "%.2f".format(v)
            else            -> "%.1f".format(v)
        }
    }

    private fun formatPct(v: Double): String {
        val abs = kotlin.math.abs(v)
        return when {
            abs == 0.0  -> "flat"
            abs < 1.0   -> "%.1f percent".format(v)
            abs < 1000  -> "%.0f percent".format(v)
            else        -> "%.0fx".format(v / 100.0)
        }
    }

    /**
     * Inject v3 emotion tags based on persona emotional range.
     * eleven_v3 / multilingual_v2 understand `[shouts]`, `[laughs]`, `[whispers]`,
     * `[sighs]`, `[excited]`, `[serious]` etc. — Flash ignores them safely.
     */
    private fun injectEmotionTags(text: String, profile: VoiceProfile): String {
        if (text.isBlank()) return text
        val tag = when (profile.emotionalRange) {
            PersonalityVoiceRegistry.EmotionalRange.EXCITED    -> "[excited]"
            PersonalityVoiceRegistry.EmotionalRange.BOLD       -> "[shouts]"
            PersonalityVoiceRegistry.EmotionalRange.SERIOUS    -> "[serious]"
            PersonalityVoiceRegistry.EmotionalRange.GROUNDING  -> "[sighs]"
            PersonalityVoiceRegistry.EmotionalRange.REASSURING -> "[warm]"
            PersonalityVoiceRegistry.EmotionalRange.ROBOTIC    -> "[robotic]"
            PersonalityVoiceRegistry.EmotionalRange.CALM,
            PersonalityVoiceRegistry.EmotionalRange.NEUTRAL    -> return text  // no tag
        }
        // Only prepend; double-tagging gets noisy.
        return if (text.startsWith("[")) text else "$tag $text"
    }
}
