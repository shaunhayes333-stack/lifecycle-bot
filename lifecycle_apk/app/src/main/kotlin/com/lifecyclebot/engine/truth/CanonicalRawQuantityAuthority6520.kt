package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** V5.0.6520 — raw token quantity authority. UI amounts are presentation only. */
object CanonicalRawQuantityAuthority6520 {
    val LEGACY_ROUNDING_EPSILON_RAW: BigInteger = BigInteger.ONE

    data class JournalVerdict(
        val accepted: Boolean,
        val normalizedRaw: BigInteger,
        val reason: String,
        val quarantine: Boolean,
    )

    fun normalizeLegacyJournalRaw(journalRaw: BigInteger, canonicalConsumedRaw: BigInteger): JournalVerdict {
        if (canonicalConsumedRaw <= BigInteger.ZERO) {
            return JournalVerdict(false, BigInteger.ZERO, "CANONICAL_RAW_MISSING", true)
        }
        if (journalRaw == canonicalConsumedRaw) {
            return JournalVerdict(true, canonicalConsumedRaw, "EXACT", false)
        }
        val delta = journalRaw.subtract(canonicalConsumedRaw).abs()
        if (journalRaw > BigInteger.ZERO && delta <= LEGACY_ROUNDING_EPSILON_RAW) {
            try { PipelineHealthCollector.labelInc("JOURNAL_RAW_LEGACY_EPSILON_NORMALIZED_6520") } catch (_: Throwable) {}
            return JournalVerdict(true, canonicalConsumedRaw, "LEGACY_EPSILON_NORMALIZED", false)
        }
        try {
            PipelineHealthCollector.labelInc("QUARANTINE_ECONOMIC_EVENT_DECIMAL_SCALE_MISMATCH_6520")
            ForensicLogger.lifecycle(
                "QUARANTINE_ECONOMIC_EVENT_DECIMAL_SCALE_MISMATCH_6520",
                "journalRaw=$journalRaw canonicalRaw=$canonicalConsumedRaw delta=$delta action=reconstruct_from_canonical_lot_retry_close learning=false abandon=false",
            )
        } catch (_: Throwable) {}
        return JournalVerdict(false, canonicalConsumedRaw, "DECIMAL_SCALE_MISMATCH", true)
    }

    /** PAPER simulation authority: derive raw directly from economic decimal strings, never from uiAmount. */
    fun paperRawFromEconomics(costSol: String, solUsd: String, tokenPriceUsd: String, quantityScale: Int): BigInteger {
        require(quantityScale in 0..18) { "INVALID_QUANTITY_SCALE:$quantityScale" }
        val cost = BigDecimal(costSol)
        val solPrice = BigDecimal(solUsd)
        val tokenPrice = BigDecimal(tokenPriceUsd)
        require(cost.signum() > 0 && solPrice.signum() > 0 && tokenPrice.signum() > 0) { "INVALID_PAPER_ECONOMICS" }
        return cost.multiply(solPrice).divide(tokenPrice, quantityScale + 18, RoundingMode.HALF_UP)
            .movePointRight(quantityScale).setScale(0, RoundingMode.HALF_UP).toBigIntegerExact()
    }

    fun parseStoredRaw(value: String?): BigInteger = try {
        value?.trim()?.takeIf { it.isNotEmpty() }?.let(::BigInteger) ?: BigInteger.ZERO
    } catch (_: Throwable) { BigInteger.ZERO }
}
