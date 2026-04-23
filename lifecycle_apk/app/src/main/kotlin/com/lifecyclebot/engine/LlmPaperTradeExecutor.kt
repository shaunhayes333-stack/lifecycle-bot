package com.lifecyclebot.engine

import com.lifecyclebot.perps.CryptoAltTrader
import org.json.JSONObject

/**
 * V5.9.135 — LLM paper-trade executor.
 *
 * Parses a single `<<TRADE>>{json}<<ENDTRADE>>` block at the end of an LLM
 * chat reply and routes it to the CryptoAlt paper desk.
 *
 * Hard gates enforced here (belt + suspenders on top of the trader's own
 * checks):
 *   - Only executes in paper mode. Live mode → reject.
 *   - Only one TRADE block per reply (extras ignored).
 *   - Only "buy" and "sell" actions. Size is clamped inside the trader.
 *
 * Returned `Applied` mirrors LlmParameterTuner.Applied so SentientPersonality
 * can echo a human-friendly summary line to the user.
 */
object LlmPaperTradeExecutor {

    private const val TAG = "LlmPaperTrade"

    // V5.9.141 — plain-English intent regexes so user commands like
    // "buy 0.5 sol of ETH" or "sell BTC" still fire even when the LLM
    // narrates instead of emitting a proper block.
    private val BUY_INTENT = Regex(
        "(?:\\b(?:buy|long|bid|accumulate|add|enter|scoop|go long on)\\s+" +
        "(?:(\\d+(?:\\.\\d+)?)\\s*(?:sol|◎)?\\s*(?:of|in)?\\s*)?" +
        "([A-Za-z]{2,10}))",
        RegexOption.IGNORE_CASE
    )
    private val SELL_INTENT = Regex(
        "(?:\\b(?:sell|dump|exit|close|short|cut|bail(?:\\s+on)?)\\s+" +
        "([A-Za-z]{2,10}))",
        RegexOption.IGNORE_CASE
    )

    /**
     * V5.9.141 — fallback: if the LLM reply had no block AND the user's
     * own message looks like a direct trading command, execute that.
     * Returns null when the message is not a trade command.
     */
    private fun intentFromUserMessage(userMessage: String?): JSONObject? {
        if (userMessage.isNullOrBlank()) return null
        val m = userMessage.trim()
        if (m.length > 400) return null  // guard against giant pastes
        // Prefer sell first — "sell XYZ and buy ABC" would otherwise trigger both.
        SELL_INTENT.find(m)?.let { sm ->
            val symbol = sm.groupValues.getOrNull(1)?.uppercase()
            if (!symbol.isNullOrBlank()) return JSONObject()
                .put("action", "sell")
                .put("symbol", symbol)
                .put("reason", "user-command:${m.take(80)}")
        }
        BUY_INTENT.find(m)?.let { bm ->
            val sizeStr = bm.groupValues.getOrNull(1)
            val symbol  = bm.groupValues.getOrNull(2)?.uppercase()
            if (!symbol.isNullOrBlank()) return JSONObject()
                .put("action", "buy")
                .put("symbol", symbol)
                .put("sizeSol", sizeStr?.toDoubleOrNull() ?: 0.1)
                .put("reason", "user-command:${m.take(80)}")
        }
        return null
    }
    //   * `<<TRADE>>{...}<<ENDTRADE>>`   (canonical)
    //   * ```<<TRADE>>{...}<<ENDTRADE>>```   (triple-backtick code fence)
    //   * **<<TRADE>>{...}<<ENDTRADE>>**     (bold markdown)
    //   * <TRADE>{...}</TRADE>               (single-bracket HTML style)
    //   * [TRADE]{...}[/TRADE]               (ini-style)
    //   * [[TRADE]]{...}[[ENDTRADE]]         (doubled-bracket style)
    //
    // The JSON payload is captured once; everything else is wrapper noise.
    private val BLOCK_REGEXES: List<Regex> = listOf(
        // canonical: <<TRADE>>{...}<<ENDTRADE>>
        Regex("<<\\s*TRADE\\s*>>\\s*`{0,3}\\s*(\\{[\\s\\S]*?\\})\\s*`{0,3}\\s*<<\\s*ENDTRADE\\s*>>", RegexOption.IGNORE_CASE),
        // single brackets: <TRADE>{...}</TRADE>
        Regex("<\\s*TRADE\\s*>\\s*`{0,3}\\s*(\\{[\\s\\S]*?\\})\\s*`{0,3}\\s*<\\s*/?\\s*(?:END)?TRADE\\s*>", RegexOption.IGNORE_CASE),
        // doubled square: [[TRADE]]{...}[[ENDTRADE]]
        Regex("\\[\\[\\s*TRADE\\s*\\]\\]\\s*`{0,3}\\s*(\\{[\\s\\S]*?\\})\\s*`{0,3}\\s*\\[\\[\\s*ENDTRADE\\s*\\]\\]", RegexOption.IGNORE_CASE),
        // single square: [TRADE]{...}[/TRADE]
        Regex("\\[\\s*TRADE\\s*\\]\\s*`{0,3}\\s*(\\{[\\s\\S]*?\\})\\s*`{0,3}\\s*\\[\\s*/?\\s*(?:END)?TRADE\\s*\\]", RegexOption.IGNORE_CASE),
    )

    data class Applied(
        val cleanedReply: String,
        val outcome: String?,    // short human-readable summary, or null if no block
        val rejected: String?,   // reason (if parsed but gated off)
    )

    fun extractAndExecute(llmReply: String, userMessage: String? = null): Applied {
        if (llmReply.isBlank() && userMessage.isNullOrBlank()) {
            return Applied(cleanedReply = llmReply, outcome = null, rejected = null)
        }

        // Try each variant regex until one matches. Canonical first.
        var match: MatchResult? = null
        for (regex in BLOCK_REGEXES) {
            val m = regex.find(llmReply)
            if (m != null) { match = m; break }
        }

        // V5.9.141 — FALLBACK: no block in LLM reply, but the user's own
        // message is a plain "buy/sell X" command. Honour it. The LLM is
        // allowed to waffle; the trade still fires.
        val jsonPayload: String
        val cleaned: String
        if (match == null) {
            val intent = intentFromUserMessage(userMessage)
            if (intent != null) {
                ErrorLogger.info(TAG, "🗣️ intent fallback fired from user msg: $intent")
                jsonPayload = intent.toString()
                cleaned = llmReply
            } else {
                if (llmReply.contains("TRADE", ignoreCase = true) &&
                    llmReply.contains("action", ignoreCase = true)) {
                    ErrorLogger.warn(TAG,
                        "reply mentions TRADE + action but no block matched — LLM " +
                        "likely emitted a free-form command. Last 400 chars: " +
                        llmReply.takeLast(400)
                    )
                }
                return Applied(cleanedReply = llmReply, outcome = null, rejected = null)
            }
        } else {
            jsonPayload = match.groupValues.getOrNull(1).orEmpty().trim()
            cleaned = llmReply.removeRange(match.range).trim()
            ErrorLogger.info(TAG, "🧠 TRADE block parsed: $jsonPayload")
        }

        val obj = try {
            JSONObject(jsonPayload)
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "bad JSON in TRADE block: ${t.message}")
            return Applied(cleanedReply = cleaned, outcome = null, rejected = "malformed trade payload")
        }

        val action = obj.optString("action").trim().lowercase()
        val symbol = obj.optString("symbol").trim().uppercase()
        val reason = obj.optString("reason").trim().take(120)

        if (action.isBlank() || symbol.isBlank()) {
            return Applied(cleanedReply = cleaned, outcome = null, rejected = "missing action/symbol")
        }
        if (action !in setOf("buy", "sell")) {
            return Applied(cleanedReply = cleaned, outcome = null, rejected = "unsupported action '$action'")
        }

        if (!CryptoAltTrader.isPaperMode()) {
            return Applied(
                cleanedReply = cleaned,
                outcome = null,
                rejected = "live mode — chat trading disabled"
            )
        }

        val result: CryptoAltTrader.LlmTradeResult = try {
            when (action) {
                "buy" -> {
                    val sizeSol = (obj.opt("sizeSol") as? Number)?.toDouble() ?: 0.1
                    CryptoAltTrader.llmOpenPaperBuy(symbol, sizeSol, reason.ifBlank { "llm chat" })
                }
                else -> CryptoAltTrader.llmClosePaperSell(symbol, reason.ifBlank { "llm chat" })
            }
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "TRADE exec error: ${t.message}")
            return Applied(cleanedReply = cleaned, outcome = null, rejected = "exec error: ${t.message}")
        }

        return when (result) {
            is CryptoAltTrader.LlmTradeResult.Success -> {
                ErrorLogger.info(TAG, "✅ TRADE SUCCESS: $action $symbol | ${result.summary}")
                Applied(cleanedReply = cleaned, outcome = result.summary, rejected = null)
            }
            is CryptoAltTrader.LlmTradeResult.Rejected -> {
                ErrorLogger.warn(TAG, "⛔ TRADE REJECTED: $action $symbol | ${result.reason}")
                Applied(cleanedReply = cleaned, outcome = null, rejected = result.reason)
            }
        }
    }
}
