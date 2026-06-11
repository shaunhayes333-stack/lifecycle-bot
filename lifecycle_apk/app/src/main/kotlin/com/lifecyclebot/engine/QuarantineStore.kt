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

    // V5.9.1508 — TRANSIENT-REASON TTL (root fix for "watchlist purged=803 kept=0,
    // same tokens cycling"). ZERO_LIQUIDITY is a TRANSIENT fault: fresh pump.fun /
    // Raydium mints report liq=0 for the first seconds before DexScreener indexes
    // the pool. The old isQuarantined() was permanent (entries.containsKey), so a
    // mint quarantined at intake for a momentary zero-liq read was skipped FOREVER
    // by the pre-lane purge — collapsing the entire 803-token work set to kept=0
    // and starving every lane. We expire ONLY transient reasons after a short
    // cooldown so the token re-enters and gets re-evaluated against fresh liquidity.
    // FACTUAL POISON (blacklist/ban/rug) stays PERMANENT — the veto whitelist is
    // untouched.
    private const val TRANSIENT_QUARANTINE_TTL_MS = 90_000L  // 90s — re-check once liq indexes
    private val TRANSIENT_REASON_PREFIXES = listOf("ZERO_LIQUIDITY", "RESTORE_ZERO_LIQUIDITY", "RESTORE_NO_MARKET")
    private fun isTransientReason(reason: String): Boolean =
        TRANSIENT_REASON_PREFIXES.any { reason.startsWith(it) }

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
        // V5.9.1508 — respect the transient TTL here too. Without this, a mint
        // whose zero-liq entry has aged past the TTL would be re-quarantined on
        // the spot from the STALE entry (this short-circuit runs before the fresh
        // liquidity check below), defeating the re-evaluation. Permanent poison
        // still short-circuits immediately.
        entries[mint]?.let { e ->
            if (!isTransientReason(e.reason)) return quarantine(mint, symbol, e.reason)
            val age = System.currentTimeMillis() - e.lastSeenMs
            if (age < TRANSIENT_QUARANTINE_TTL_MS) return quarantine(mint, symbol, e.reason)
            // aged out → drop stale entry and fall through to a fresh evaluation
            entries.remove(mint, e)
        }

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

    fun isQuarantined(mint: String): Boolean {
        val e = entries[mint] ?: return false
        // Factual poison (blacklist/ban/rug/etc) — permanent veto, never expires.
        if (!isTransientReason(e.reason)) return true
        // Transient (zero-liq) — expire after cooldown so the mint re-enters the
        // work set and is re-evaluated against fresh liquidity. Drop the stale
        // entry so a clean re-eval can re-quarantine ONLY if it's still poison.
        val age = System.currentTimeMillis() - e.lastSeenMs
        if (age >= TRANSIENT_QUARANTINE_TTL_MS) {
            entries.remove(mint, e)
            return false
        }
        return true
    }
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
