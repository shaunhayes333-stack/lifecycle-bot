package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6427 §M — STALE-PRICE FILL GATE.
 *
 * OPERATOR (V5.0.6424 spec):
 * "Journal currently contains QUALITY_STALE_PRICE +69.1%. This is
 *  dangerous. STALE PRICE is a data-quality state. It is NOT an
 *  executable market price. A stale quote must not crystallize
 *  fictional profit."
 *
 * DESIGN
 * ──────
 * Simple synchronous gate. Callers ask "can I use this quote to
 * realise a paper/live fill?" and receive an allow/deny + reason.
 * The default policy is:
 *   - if quoteAgeMs is missing or -1, deny (unknown freshness)
 *   - if quoteAgeMs > MAX_QUOTE_AGE_MS_TERMINAL for a terminal
 *     realising sell, deny
 *   - partial exits + risk stops still respect the gate but with a
 *     softer ceiling so a slow-quote provider doesn't strand a
 *     losing position — see MAX_QUOTE_AGE_MS_RISK
 *   - stale-labelled sources ("STALE", "CACHED_MARK", etc.) are
 *     denied outright regardless of age
 *
 * Denials do NOT stop the exit intent — they force the caller to
 * defer to EXIT_PRICE_PENDING (per spec §M) until a fresh quote
 * arrives.
 */
object StalePriceFillGate6427 {

    private const val MAX_QUOTE_AGE_MS_TERMINAL = 15_000L  // 15s
    private const val MAX_QUOTE_AGE_MS_RISK = 45_000L      // 45s
    private const val MAX_QUOTE_AGE_MS_BUY = 20_000L       // 20s
    private val STALE_SOURCE_SUBSTRINGS = listOf(
        "stale", "cached_mark", "phantom", "unavailable", "unknown_price",
    )

    data class Decision(val allow: Boolean, val reason: String)

    enum class FillKind { PAPER_BUY, PAPER_TERMINAL_SELL, PAPER_PARTIAL_SELL, PAPER_RISK_STOP,
        LIVE_BUY, LIVE_TERMINAL_SELL, LIVE_PARTIAL_SELL, LIVE_RISK_STOP }

    fun canRealize(
        kind: FillKind,
        quoteAgeMs: Long?,
        priceSource: String?,
        mint: String,
        symbol: String,
    ): Decision {
        val srcLower = priceSource?.lowercase().orEmpty()
        if (STALE_SOURCE_SUBSTRINGS.any { it in srcLower }) {
            emit("STALE_PRICE_GATE_DENY_STALE_SOURCE_6427", kind, quoteAgeMs, priceSource, mint, symbol)
            return Decision(false, "STALE_SOURCE:$priceSource")
        }
        val age = quoteAgeMs ?: -1L
        if (age < 0L) {
            emit("STALE_PRICE_GATE_DENY_UNKNOWN_AGE_6427", kind, quoteAgeMs, priceSource, mint, symbol)
            return Decision(false, "UNKNOWN_QUOTE_AGE")
        }
        val ceiling = when (kind) {
            FillKind.PAPER_TERMINAL_SELL, FillKind.LIVE_TERMINAL_SELL -> MAX_QUOTE_AGE_MS_TERMINAL
            FillKind.PAPER_RISK_STOP, FillKind.LIVE_RISK_STOP -> MAX_QUOTE_AGE_MS_RISK
            FillKind.PAPER_PARTIAL_SELL, FillKind.LIVE_PARTIAL_SELL -> MAX_QUOTE_AGE_MS_TERMINAL
            FillKind.PAPER_BUY, FillKind.LIVE_BUY -> MAX_QUOTE_AGE_MS_BUY
        }
        if (age > ceiling) {
            emit("STALE_PRICE_GATE_DENY_TOO_OLD_6427", kind, quoteAgeMs, priceSource, mint, symbol)
            return Decision(false, "QUOTE_TOO_OLD ${age}ms > ${ceiling}ms")
        }
        return Decision(true, "OK ${age}ms")
    }

    private fun emit(label: String, kind: FillKind, ageMs: Long?, src: String?, mint: String, symbol: String) {
        try {
            ForensicLogger.lifecycle(
                label,
                "kind=$kind ageMs=$ageMs src=$src mint=${mint.take(10)} sym=$symbol",
            )
            PipelineHealthCollector.labelInc(label)
        } catch (_: Throwable) {}
    }
}
