package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7951 §THE_CURVE_IS_THE_POOL.
 *
 * 5.0.7949 at 146 s: V3 REJECTED_FATAL_V3 ZERO_LIQUIDITY=166, its largest reject,
 * and each one also quarantined the symbol (ForensicLogger). A pump.fun bonding
 * curve has no DEX pair, so its liquidity has to come from the curve itself.
 * TokenMapAuthority.observedLiquidityUsd read tokenMap.realSolReserves, whose only
 * writer is the PumpPortal trade callback, and only for a mint already in
 * status.tokens: the create frame's reserves, every frame that arrived before the
 * token row existed, and every chain read of the curve account
 * (ParallelMarkFanout7088) were dropped, so a fresh curve read $0.
 *
 * This keeps the last observed curve reserves per mint, keyed by mint (not by
 * TokenState), from every source that sees them: PumpPortal create and trade
 * frames (virtual SOL / virtual tokens) and the RPC curve read (virtual and real
 * reserves). Liquidity is the curve's REAL SOL (what a seller can be paid), never
 * the 30 SOL of virtual reserves. A graduated curve's last real SOL is what
 * migrated into its PumpSwap pool, used only until the pool itself is hydrated
 * and only for a bounded time. Nothing is inferred beyond pump.fun's own curve
 * arithmetic.
 */
object CurveReserves7951 {
    /** pump.fun curves start with 30 SOL and 1.073e9 tokens of virtual reserves. */
    private const val INITIAL_VIRTUAL_SOL_7951 = 30.0
    private const val INITIAL_VIRTUAL_TOKENS_7951 = 1_073_000_000.0
    private const val MAX_MINTS = 6_000
    private const val CURVE_TTL_MS = 60L * 60_000L
    private const val GRADUATED_TTL_MS = 30L * 60_000L

    private class Row(
        @Volatile var virtualSol: Double,
        @Volatile var virtualTokens: Double,
        @Volatile var realSol: Double,
        @Volatile var atMs: Long,
        @Volatile var accountObserved: Boolean,
        @Volatile var version8385: Long = 0L,
    )

    private val rows = ConcurrentHashMap<String, Row>()
    private val notes = AtomicLong(0)
    private val liquidityServed = AtomicLong(0)

    /** Pure: the SOL a curve has actually raised (sellable) from its virtual SOL. */
    fun realSolFromVirtual7951(virtualSol: Double): Double =
        if (!virtualSol.isFinite() || virtualSol <= 0.0) 0.0 else (virtualSol - INITIAL_VIRTUAL_SOL_7951).coerceAtLeast(0.0)

    /** Pure: spot price in SOL per token from virtual reserves (k/vSol when the token side is unknown). */
    fun priceSol7951(virtualSol: Double, virtualTokens: Double): Double {
        if (!virtualSol.isFinite() || virtualSol <= 0.0) return 0.0
        val vTok = if (virtualTokens.isFinite() && virtualTokens > 0.0) virtualTokens
            else INITIAL_VIRTUAL_SOL_7951 * INITIAL_VIRTUAL_TOKENS_7951 / virtualSol
        return virtualSol / vTok
    }

    /** A PumpPortal create/trade frame: virtual SOL and virtual tokens (whole units). */
    fun noteFrame7951(mint: String, virtualSol: Double, virtualTokens: Double, atMs: Long = System.currentTimeMillis()) {
        if (!virtualSol.isFinite() || virtualSol <= 0.0) return
        val real8346 = realSolFromVirtual7951(virtualSol)
        if (put(mint, virtualSol, virtualTokens, real8346, atMs, false) == null) return
        try { LiquidityTruth8037.onCurveObserved8346(mint, real8346, fromAccount = false) } catch (_: Throwable) {}   // V5.0.8346 — the frame writes the tick
    }

    /** The RPC curve read: virtual and real reserves, in SOL and whole tokens. */
    fun noteAccount7951(mint: String, virtualSol: Double, virtualTokens: Double, realSol: Double, atMs: Long = System.currentTimeMillis()) {
        if (!virtualSol.isFinite() || virtualSol <= 0.0) return
        if (!realSol.isFinite() || realSol < 0.0) return
        if (put(mint, virtualSol, virtualTokens, realSol, atMs, true) == null) return
        try { LiquidityTruth8037.onCurveObserved8346(mint, realSol, fromAccount = true) } catch (_: Throwable) {}   // V5.0.8346 — the curve read writes the tick
    }

    data class AcceptedObservation8385(val atMs: Long, val source: String, val version: Long)
    /** pump.fun mints a fixed 1,000,000,000 supply; the curve price times that supply is the venue's own market cap. */
    const val PUMP_TOTAL_SUPPLY_8399 = 1_000_000_000.0
    private const val MCAP_FROM_CURVE_MAX_AGE_MS_8399 = 15L * 60_000L

    /** Pure: market cap in USD from the curve's virtual reserves, or NaN when the reserves cannot price it. */
    fun mcapUsdFromCurve8399(virtualSol: Double, virtualTokens: Double, solUsd: Double): Double {
        if (!solUsd.isFinite() || solUsd <= 0.0) return Double.NaN
        val px = priceSol7951(virtualSol, virtualTokens)
        if (!px.isFinite() || px <= 0.0) return Double.NaN
        return px * PUMP_TOTAL_SUPPLY_8399 * solUsd
    }

    /**
     * V5.0.8399 §MCAP_FROM_THE_CURVE — 867 MOONSHOT readies in ten minutes sat in NATIVE_MCAP_UNHYDRATED_DEFER while this
     * store already held their curve reserves (prints that landed before the token row existed never reach
     * applyPumpTradeMark7278, whose `status.tokens[mint] ?: return` drops them). The curve prices its own token: when the
     * row has no market cap and a fresh reserve observation exists, the row is hydrated from it. Returns the value written, or NaN.
     */
    fun hydrateMcap8399(ts: com.lifecyclebot.data.TokenState, solUsd: Double, nowMs: Long = System.currentTimeMillis()): Double {
        if (ts.lastMcap.isFinite() && ts.lastMcap > 0.0) return Double.NaN
        val r = rows[ts.mint] ?: return Double.NaN
        val (vSol, vTok, atMs) = synchronized(r) { Triple(r.virtualSol, r.virtualTokens, r.atMs) }
        if (atMs <= 0L || nowMs - atMs > MCAP_FROM_CURVE_MAX_AGE_MS_8399) return Double.NaN
        val mcap = mcapUsdFromCurve8399(vSol, vTok, solUsd)
        if (!mcap.isFinite() || mcap <= 0.0) return Double.NaN
        synchronized(ts) { if (!(ts.lastMcap.isFinite() && ts.lastMcap > 0.0)) ts.lastMcap = mcap }
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("MCAP_HYDRATED_FROM_CURVE_8399") } catch (_: Throwable) {}
        return mcap
    }

    fun acceptedObservation8385(mint: String): AcceptedObservation8385? {
        val r = rows[mint] ?: return null
        return synchronized(r) { AcceptedObservation8385(r.atMs, if (r.accountObserved) "ACCOUNT" else "FRAME", r.version8385) }
    }
    private fun put(mint: String, vSol: Double, vTok: Double, real: Double, atMs: Long, accountObserved: Boolean): AcceptedObservation8385? {
        val m = mint.trim()
        if (m.isBlank() || atMs <= 0L) return null
        if (rows.size >= MAX_MINTS && !rows.containsKey(m)) trim(atMs)
        val r = rows.computeIfAbsent(m) { Row(vSol, vTok, real, 0L, accountObserved) }
        val accepted = synchronized(r) {
            if (atMs <= r.atMs) return null
            r.virtualSol = vSol
            r.virtualTokens = if (vTok.isFinite() && vTok > 0.0) vTok else Double.NaN
            r.realSol = real
            r.atMs = atMs
            r.accountObserved = accountObserved
            r.version8385 = notes.incrementAndGet()
            AcceptedObservation8385(atMs, if (accountObserved) "ACCOUNT" else "FRAME", r.version8385)
        }
        try { com.lifecyclebot.engine.HydrationReeval8343.onCurveRead8343(m, real, atMs) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.TicketRetry8327.onReserveObserved8385(m, accepted.atMs, accepted.source, accepted.version) } catch (_: Throwable) {}
        return accepted
    }

    private fun trim(nowMs: Long) {
        rows.entries.removeIf { nowMs - it.value.atMs > CURVE_TTL_MS }
        if (rows.size >= MAX_MINTS) rows.entries.sortedBy { it.value.atMs }.take(MAX_MINTS / 10).forEach { rows.remove(it.key) }
    }

    /**
     * Pure: the curve's sellable SOL. [graduated] uses the last real SOL (what
     * migrated) for [GRADUATED_TTL_MS] only; an active curve's reading stays good
     * for [CURVE_TTL_MS] (reserves only move on trades, and trades refresh it).
     */
    fun liquiditySolFor7951(realSol: Double, ageMs: Long, graduated: Boolean): Double {
        if (!realSol.isFinite() || realSol <= 0.0 || ageMs < 0L) return 0.0
        val ttl = if (graduated) GRADUATED_TTL_MS else CURVE_TTL_MS
        return if (ageMs <= ttl) realSol else 0.0
    }

    /** Observed curve liquidity in SOL for [mint], or 0.0 when none is known. */
    fun liquiditySol7951(mint: String, nowMs: Long = System.currentTimeMillis()): Double {
        val r = rows[mint.trim()] ?: return 0.0
        val graduated = try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint) } catch (_: Throwable) { false }
        val sol = liquiditySolFor7951(r.realSol, nowMs - r.atMs, graduated)
        if (sol > 0.0) {
            liquidityServed.incrementAndGet()
            try { PipelineHealthCollector.labelInc(if (graduated) "CURVE_LIQ_GRADUATED_POOL_7951" else "CURVE_LIQ_FROM_RESERVES_7951") } catch (_: Throwable) {}
        }
        return sol
    }

    /** Spot price in SOL per token from reserves of an active curve observed within [maxAgeMs], or 0.0. */
    /** V5.0.8198 — an ACTIVE (not graduated) curve's virtual reserves (SOL, tokens) read within [maxAgeMs], else null. */
    data class ExitReserveObservation(val virtualSol: Double, val virtualTokens: Double,
                                      val realSol: Double, val observedAtMs: Long)

    fun activeExitObservation(mint: String, nowMs: Long, maxAgeMs: Long): ExitReserveObservation? {
        val r = rows[mint.trim()] ?: return null
        if (try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint) } catch (_: Throwable) { true }) return null
        return synchronized(r) {
            if (nowMs - r.atMs !in 0L..maxAgeMs || !r.virtualSol.isFinite() || r.virtualSol <= 0.0 ||
                !r.virtualTokens.isFinite() || r.virtualTokens <= 0.0 || !r.realSol.isFinite() || r.realSol < 0.0) null
            else ExitReserveObservation(r.virtualSol, r.virtualTokens, r.realSol, r.atMs)
        }
    }

    fun priceSolFor7951(mint: String, nowMs: Long, maxAgeMs: Long): Double {
        val r = rows[mint.trim()] ?: return 0.0
        if (nowMs - r.atMs !in 0L..maxAgeMs) return 0.0
        if (try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint) } catch (_: Throwable) { false }) return 0.0
        return priceSol7951(r.virtualSol, r.virtualTokens)
    }

    /**
     * V5.0.8046 — the SOL buyers have actually put into an ACTIVE (not graduated) curve, from a reading inside the
     * curve TTL; null when the curve is unknown, stale or graduated. 0.2 means "nobody bought": distinct from unknown.
     */
    fun realSolIfActive8046(mint: String, nowMs: Long = System.currentTimeMillis()): Double? {
        val r = rows[mint.trim()] ?: return null
        if (nowMs - r.atMs !in 0L..CURVE_TTL_MS) return null
        if (try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint) } catch (_: Throwable) { false }) return null
        return r.realSol.takeIf { it.isFinite() && it >= 0.0 }
    }

    /** A fresh account read, distinct from virtual-reserve arithmetic on a stream frame. */
    fun accountRealSol8253(mint: String, nowMs: Long): Double? {
        val r = rows[mint.trim()] ?: return null
        if (try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint) } catch (_: Throwable) { true }) return null
        return synchronized(r) {
            if (!r.accountObserved || !LaunchCohort8173.reserveFresh8174(r.atMs, nowMs)) null
            else r.realSol.takeIf { it.isFinite() && it >= 0.0 }
        }
    }

    /** V5.0.8173 — when [mint]'s curve reading was observed (epoch ms), or null when none is held (LaunchCohort8173). */
    fun readingAtMs8173(mint: String): Long? = rows[mint.trim()]?.atMs

    /** True when this mint's curve reserves have been observed. */
    fun known7951(mint: String): Boolean = rows.containsKey(mint.trim())

    fun statusLine7951(): String = "curveReserves7951 mints=${rows.size} notes=${notes.get()} liqServed=${liquidityServed.get()}"

    internal fun resetForTest7951() { rows.clear(); notes.set(0); liquidityServed.set(0) }
}
