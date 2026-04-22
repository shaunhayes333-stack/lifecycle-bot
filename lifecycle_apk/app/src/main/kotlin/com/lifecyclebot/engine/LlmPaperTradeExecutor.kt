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

    private val BLOCK_REGEX = Regex(
        "<<\\s*TRADE\\s*>>\\s*(\\{[\\s\\S]*?\\})\\s*<<\\s*ENDTRADE\\s*>>",
        RegexOption.IGNORE_CASE
    )

    data class Applied(
        val cleanedReply: String,
        val outcome: String?,    // short human-readable summary, or null if no block
        val rejected: String?,   // reason (if parsed but gated off)
    )

    fun extractAndExecute(llmReply: String): Applied {
        if (llmReply.isBlank()) {
            return Applied(cleanedReply = llmReply, outcome = null, rejected = null)
        }

        val match = BLOCK_REGEX.find(llmReply)
            ?: return Applied(cleanedReply = llmReply, outcome = null, rejected = null)

        val jsonPayload = match.groupValues.getOrNull(1).orEmpty().trim()
        val cleaned = llmReply.removeRange(match.range).trim()

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
            is CryptoAltTrader.LlmTradeResult.Success ->
                Applied(cleanedReply = cleaned, outcome = result.summary, rejected = null)
            is CryptoAltTrader.LlmTradeResult.Rejected ->
                Applied(cleanedReply = cleaned, outcome = null, rejected = result.reason)
        }
    }
}
