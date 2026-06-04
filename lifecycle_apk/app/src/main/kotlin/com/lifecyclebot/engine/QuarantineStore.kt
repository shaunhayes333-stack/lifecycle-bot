package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1100-pre — scanner/watchlist hard quarantine.
 *
 * Quarantine is earlier than watchlist, V3, FDG, lane eval, and executor.
 * It is for factual poison only: explicit blacklist/ban, confirmed rug,
 * zero executable liquidity, or unsafe registry restore. It is NOT a
 * strategy threshold tuner.
 */
object QuarantineStore {
    data class Entry(
        val mint: String,
        val symbol: String,
        val reason: String,
        val firstSeenMs: Long = System.currentTimeMillis(),
        var lastSeenMs: Long = System.currentTimeMillis(),
        var suppressed: Long = 0L,
        var lastTelemetryMs: Long = 0L,
    )

    data class Verdict(
        val quarantined: Boolean,
        val reason: String = "",
        val telemetryDue: Boolean = false,
    )

    private const val TELEMETRY_TTL_MS = 60_000L
    private val entries = ConcurrentHashMap<String, Entry>()
    private val suppressedTotal = AtomicLong(0L)
    // V5.9.1339 — WINDOWED suppressed counter. The lifetime suppressedTotal grows
    // forever within a session; comparing it against the WINDOWED pipeline INTAKE
    // counter (phaseCounts) in InvariantGuardian's SCANNER_RESTORE_POISONING check
    // guaranteed a false-positive once the session aged — cumulative will always
    // overtake a window. That false fault throttled a HEALTHY scanner (concurrency
    // forced to 2 + MEME_REGISTRY_RESTORE quarantined), which is the "parked, not
    // trading" stall the operator hit. We keep a 60s ring of suppress timestamps so
    // the guardian can compare like-for-like (recent quarantines vs recent intake).
    private val suppressWindow = java.util.concurrent.ConcurrentLinkedDeque<Long>()
    private const val SUPPRESS_WINDOW_MS = 60_000L

    fun quarantine(mint: String, symbol: String = "", reason: String): Verdict {
        if (mint.isBlank()) return Verdict(false)
        val now = System.currentTimeMillis()
        val e = entries.compute(mint) { _, old ->
            (old ?: Entry(mint, symbol, reason)).also {
                it.lastSeenMs = now
                it.suppressed++
                if (symbol.isNotBlank() && it.symbol.isBlank()) {
                    // Entry is a data class with val symbol; leave immutable for safety.
                }
            }
        }!!
        suppressedTotal.incrementAndGet()
        // V5.9.1339 — feed the windowed ring and prune anything older than the window.
        run {
            val nowW = now
            suppressWindow.addLast(nowW)
            val cutoff = nowW - SUPPRESS_WINDOW_MS
            while (true) {
                val head = suppressWindow.peekFirst() ?: break
                if (head < cutoff) suppressWindow.pollFirst() else break
            }
        }
        val due = now - e.lastTelemetryMs >= TELEMETRY_TTL_MS
        if (due) e.lastTelemetryMs = now
        return Verdict(true, e.reason, due)
    }

    fun evaluate(
        mint: String,
        symbol: String = "",
        source: String = "",
        liquidityUsd: Double = Double.NaN,
        marketCapUsd: Double = Double.NaN,
        rugcheckScore: Int? = null,
        priceDropPct: Double? = null,
        restoredLosses: Int = 0,
        hasExternalLiquidityProof: Boolean = true,
    ): Verdict {
        if (mint.isBlank()) return Verdict(false)
        entries[mint]?.let { return quarantine(mint, symbol, it.reason) }

        val src = source.uppercase()
        val liqKnown = !liquidityUsd.isNaN()
        val mcapKnown = !marketCapUsd.isNaN()
        val blacklisted = try { BannedTokens.isBanned(mint) } catch (_: Throwable) { false } ||
            try { TokenBlacklist.isBlocked(mint) } catch (_: Throwable) { false }
        if (blacklisted) return quarantine(mint, symbol, "BLACKLISTED_TOKEN")

        if (liqKnown && liquidityUsd <= 0.0) return quarantine(mint, symbol, "ZERO_LIQUIDITY")

        if (rugcheckScore != null && rugcheckScore in 0..10) {
            return quarantine(mint, symbol, "RUGCHECK_${rugcheckScore}")
        }

        val isRestore = src.contains("MEME_REGISTRY_RESTORE") || src.contains("RESTORE")
        if (isRestore) {
            if (priceDropPct != null && priceDropPct <= -60.0) return quarantine(mint, symbol, "RESTORE_RUG_DROP_${priceDropPct.toInt()}")
            if (liqKnown && liquidityUsd <= 0.0) return quarantine(mint, symbol, "RESTORE_ZERO_LIQUIDITY")
            if (mcapKnown && marketCapUsd <= 0.0 && liqKnown && liquidityUsd <= 0.0) return quarantine(mint, symbol, "RESTORE_NO_MARKET")
            if (restoredLosses >= 2 && !hasExternalLiquidityProof) return quarantine(mint, symbol, "RESTORE_LOSSY_NO_LIQ_PROOF")
        }

        return Verdict(false)
    }

    fun isQuarantined(mint: String): Boolean = entries.containsKey(mint)
    fun reason(mint: String): String? = entries[mint]?.reason
    fun suppressedCount(): Long = suppressedTotal.get()
    /** V5.9.1339 — suppressions in the trailing SUPPRESS_WINDOW_MS, pruned lazily.
     *  Use THIS (not the lifetime total) for any rate comparison against windowed
     *  pipeline counters such as INTAKE. */
    fun suppressedCountWindowed(): Long {
        val cutoff = System.currentTimeMillis() - SUPPRESS_WINDOW_MS
        while (true) {
            val head = suppressWindow.peekFirst() ?: break
            if (head < cutoff) suppressWindow.pollFirst() else break
        }
        return suppressWindow.size.toLong()
    }
    fun snapshot(): List<Entry> = entries.values.toList()
    fun resetForTests() { entries.clear(); suppressedTotal.set(0L); suppressWindow.clear() }
}
