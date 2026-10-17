package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8173 §THE_LAUNCH_COHORT_IS_STARVED_BY_MISSING_DATA_NOT_BAD_DATA.
 *
 * Device 5.0.8172 (505 s, 0 trades, FDG allow=0 block=1,238). The only strongly positive cohort in the forward labels is
 * the fresh PumpPortal launch (PUMP_PORTAL_WS|SHITCOIN|MC_10K_100K|AGE_LT15M n243 +488%, PROJECT_SNIPER n64 +783%, CORE
 * n34 +1,033%; setup LAUNCH_LADDER_PROVEN n210 +236% auth 1.00). Causes read in code:
 *  - Curve depth went stale. LiquidityTruth8037 / TokenMapAuthority read the curve's last REAL SOL for up to an hour
 *    (CurveReserves7951 CURVE_TTL_MS). A launch whose trades the stream does not carry kept its create-frame reading (the
 *    dev's seed buy, ~0.5-2 SOL) while its executed price walked it into the $10k-$100k band: a ~$2.4k+ curve read as a
 *    ~$150 pool, V3 LOW_LIQUIDITY, lane floors and a 20%+ impact cost. [curveRead8173]: a reading older than
 *    [CURVE_STALE_MS_8173] that a NEWER independent executed price contradicts by more than [REPRICE_DISAGREE_8173] is
 *    replaced by the real SOL that price implies through pump.fun's own curve invariant (the same arithmetic as
 *    LiquidityTruth8037.curveRealSolFromCap8048), and a fresh chain read of the curve account is requested (single-flight,
 *    OnChainDepth8038.needCurve8039). A non-curve token is untouched: no curve row, no reading — unknown stays unknown.
 *  - Launch buys were priced as ORDINARY entries (CostTrim8151): a 25,000 uL/CU medium at PumpPortal's 200k CU is
 *    0.000005 SOL, floored to 0.00002 SOL, with a 5,000-lamport-class Sender tip, a 900 ms preflight before broadcast and
 *    impact-sized slippage on a curve that moves every slot: BUY|PUMPPORTAL landed 5 of 15 (6 expired, 4 failed) while
 *    BUY|JUPITER landed 17 of 17. A launch-cohort buy ([launchCohort8173]: an ACTIVE curve born under 15 minutes ago, the
 *    proven AGE_LT15M band) is latency-critical: it takes the URGENT tier (Executor.entryOrdinary8151), and a mint whose
 *    PumpPortal buy expired or failed goes to the aggregator next ([pumpRouteFailing8173]) instead of retrying it.
 *  - The trade tape could not state a 5 m change for a launch younger than 5 minutes: there is no trade "5 minutes ago"
 *    because the coin did not exist. Its launch price is that base ([windowBase8173]), as the tape already did for 1 h.
 * Nothing here estimates depth, birth, trades or outcomes; every reading comes from a frame, a chain read or a fill.
 */
object LaunchCohort8173 {

    /** The proven cohort's age band (ForwardReturnLabeler7731.ageBand AGE_LT15M). */
    private const val LAUNCH_WINDOW_MS_8173 = 15L * 60_000L
    /** A curve reading older than this is checked against a newer executed price. */
    private const val CURVE_STALE_MS_8173 = 30_000L
    /** The price must be at least this much newer than the curve reading to contradict it. */
    private const val PRICE_NEWER_SLACK_MS_8173 = 2_000L
    /** Relative disagreement (of the reading, floored at 1 SOL) above which the newer price's curve state wins. */
    private const val REPRICE_DISAGREE_8173 = 0.25
    /** A failed / expired PumpPortal buy of a mint sends its next buy to the aggregator for this long. */
    const val ROUTE_FAIL_WINDOW_MS_8173 = 10L * 60_000L

    private const val PUMP_SUPPLY_8173 = 1_000_000_000.0
    /** The cap of a pump.fun curve with no SOL bought: (30)^2 / 32.19 SOL. A price below it is not an active curve's. */
    private const val CURVE_START_CAP_SOL_8173 = 27.9
    /** (30 + 85)^2 / 32.19: the curve completes near 85 real SOL (LiquidityTruth8037.MAX_ACTIVE_CURVE_CAP_SOL_8164). */
    private const val CURVE_END_CAP_SOL_8173 = 411.0
    private const val CLOCK_SKEW_MS_8173 = 60_000L

    enum class CurveSource8173 { RESERVES, PRICE_IMPLIED }

    data class CurveRead8173(val realSol: Double, val source: CurveSource8173)

    // ── pure ──

    /** Pure: a launch-cohort coin — its curve is active and its pump birth is inside [LAUNCH_WINDOW_MS_8173]. */
    fun launchCohort8173(curveActive: Boolean, pumpBirthMs: Long?, nowMs: Long): Boolean =
        curveActive && pumpBirthMs != null && pumpBirthMs > 0L &&
            nowMs - pumpBirthMs in -CLOCK_SKEW_MS_8173..LAUNCH_WINDOW_MS_8173

    /**
     * Pure: the curve's real SOL to read. [rowRealSol] is the last observed reading (null = no curve row: unknown, and it
     * stays unknown). A reading fresher than [CURVE_STALE_MS_8173] stands. An older one stands unless an independent
     * price in SOL per token ([priceSol]) observed after it ([priceAtMs] > [rowAtMs] + slack) puts the curve at a state
     * whose real SOL differs by more than [REPRICE_DISAGREE_8173]; then that state is the reading (pump.fun's invariant:
     * cap = price x 1e9 = (30 + real)^2 / 32.19). A price outside an active curve's range proves nothing.
     */
    fun curveRead8173(rowRealSol: Double?, rowAtMs: Long, priceSol: Double, priceAtMs: Long, nowMs: Long): CurveRead8173? {
        val row = rowRealSol?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        val keep = CurveRead8173(row, CurveSource8173.RESERVES)
        if (nowMs - rowAtMs <= CURVE_STALE_MS_8173) return keep
        if (!(priceSol.isFinite() && priceSol > 0.0)) return keep
        if (priceAtMs <= rowAtMs + PRICE_NEWER_SLACK_MS_8173 || priceAtMs > nowMs + CLOCK_SKEW_MS_8173) return keep
        val capSol = priceSol * PUMP_SUPPLY_8173
        if (capSol < CURVE_START_CAP_SOL_8173 || capSol > CURVE_END_CAP_SOL_8173) return keep
        val implied = LiquidityTruth8037.curveRealSolFromCap8048(capSol)
        val disagree = kotlin.math.abs(implied - row) / maxOf(row, 1.0)
        return if (disagree > REPRICE_DISAGREE_8173) CurveRead8173(implied, CurveSource8173.PRICE_IMPLIED) else keep
    }

    /**
     * Pure: the base price of a [windowMs] change. The tape's last price at/before now - window; else, for a coin born
     * inside the window, its launch price (it did not exist before its launch); else null (not stated).
     */
    fun windowBase8173(atOrBeforePx: Double?, launchPx: Double?, launchAtMs: Long?, nowMs: Long, windowMs: Long): Double? {
        atOrBeforePx?.takeIf { it.isFinite() && it > 0.0 }?.let { return it }
        val px = launchPx?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val at = launchAtMs ?: return null
        return if (nowMs - at in 0L..windowMs) px else null
    }

    /** One mint's PumpPortal buy record: failures (expired / failed on chain), the last failure and the last landing. */
    data class RouteMemo8173(val fails: Int, val lastFailMs: Long, val lastLandedMs: Long)

    /** Pure: the record after one PumpPortal buy outcome ([landed] true = CONFIRMED). */
    fun nextMemo8173(prev: RouteMemo8173?, landed: Boolean, nowMs: Long): RouteMemo8173 {
        val p = prev ?: RouteMemo8173(0, 0L, 0L)
        return if (landed) p.copy(lastLandedMs = nowMs) else p.copy(fails = p.fails + 1, lastFailMs = nowMs)
    }

    /** Pure: the mint's PumpPortal buy failed inside the window and nothing landed since — buy through the aggregator. */
    fun pumpRouteFailing8173(m: RouteMemo8173?, nowMs: Long): Boolean =
        m != null && m.fails >= 1 && nowMs - m.lastFailMs in 0L..ROUTE_FAIL_WINDOW_MS_8173 && m.lastLandedMs < m.lastFailMs

    /** Pure: the launch-cohort landing table, "ROUTE landed=a expired=b failed=c landRate=x%" per route. */
    fun landingLine8173(table: Map<String, IntArray>): String = table.entries.sortedBy { it.key }.joinToString(" · ") { (route, v) ->
        val done = v[0] + v[1] + v[2]
        "$route landed=${v[0]} expired=${v[1]} failed=${v[2]} landRate=${if (done > 0) "${v[0] * 100 / done}%" else "-"}"
    }.ifBlank { "none" }

    // ── runtime ──

    private val routeMemos = ConcurrentHashMap<String, RouteMemo8173>()
    private val launchLanding = ConcurrentHashMap<String, IntArray>()
    private val urgentMints = ConcurrentHashMap.newKeySet<String>()
    private val priceImplied = AtomicLong(0)
    private val staleAsked = AtomicLong(0)
    private val intakeAsked = AtomicLong(0)
    private val urgentBuys = AtomicLong(0)
    private val pumpFallbacks = AtomicLong(0)

    private fun count8173(label: String) {
        try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {}
    }

    /** The mint's pump birth from direct create witnesses only (create frame, pump.fun record, curve create, curve pair). */
    private fun pumpBirthMs8173(mint: String): Long? = listOfNotNull(
        try { AdmissionBirth8144.pumpCreateMs8144(mint) } catch (_: Throwable) { null },
        try { com.lifecyclebot.network.PumpCurveKeys7269.createdAtMs7280(mint) } catch (_: Throwable) { null },
        try { AdmissionBirth8144.curvePairMs8169(mint) } catch (_: Throwable) { null },
    ).minOrNull()

    /** True when [mint] is a fresh launch on an active curve (see [launchCohort8173]). */
    fun launchCohortMint8173(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank()) return false
        val active = try { CurveReserves7951.realSolIfActive8046(mint, nowMs) != null } catch (_: Throwable) { false }
        return launchCohort8173(active, if (active) pumpBirthMs8173(mint) else null, nowMs)
    }

    /**
     * LiquidityTruth8037 / TokenMapAuthority: the active curve's real SOL for [ts] (null = no active curve row). A stale
     * reading asks the chain for the curve account; a newer executed price that contradicts it decides ([curveRead8173]).
     */
    fun curveRealSolFor8173(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Double? {
        val mint = ts.mint
        val row = try { CurveReserves7951.realSolIfActive8046(mint, nowMs) } catch (_: Throwable) { null } ?: return null
        val atMs = try { CurveReserves7951.readingAtMs8173(mint) } catch (_: Throwable) { null } ?: return row
        if (nowMs - atMs <= CURVE_STALE_MS_8173) return row
        try { OnChainDepth8038.needCurve8039(mint, nowMs); staleAsked.incrementAndGet() } catch (_: Throwable) {}
        val independent = try { TrustedMcap8019.independentPrice8019(ts.lastPriceSource) } catch (_: Throwable) { false }
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val priceSol = if (independent) EconomicUnitInvariant7061.usdToSol(ts.lastPrice, solUsd) else Double.NaN
        val read = curveRead8173(row, atMs, priceSol, ts.lastPriceUpdate, nowMs) ?: return row
        if (read.source == CurveSource8173.PRICE_IMPLIED) {
            priceImplied.incrementAndGet()
            count8173("CURVE_LIQ_PRICE_IMPLIED_8173")
        }
        return read.realSol
    }

    /** PumpFunWS create frame: a frame without reserves asks the chain for the curve now (single-flight batch). */
    fun noteCreateIntake8173(mint: String, hasReserves: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || hasReserves) return
        try { OnChainDepth8038.needCurve8039(mint, nowMs); intakeAsked.incrementAndGet() } catch (_: Throwable) {}
        count8173("CURVE_READ_AT_INTAKE_8173")
    }

    /** Executor.entryOrdinary8151: a launch-cohort buy is latency-critical (URGENT tier), counted once per mint. */
    fun latencyCriticalBuy8173(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val hot = launchCohortMint8173(mint, nowMs)
        if (hot && urgentMints.add(mint)) {
            urgentBuys.incrementAndGet()
            count8173("LAUNCH_BUY_URGENT_TIER_8173")
            if (urgentMints.size > 2_000) urgentMints.clear()
        }
        return hot
    }

    /** Executor PumpPortal-first route: true sends this buy to the aggregator because PumpPortal just failed for [mint]. */
    fun pumpFallback8173(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!pumpRouteFailing8173(routeMemos[mint], nowMs)) return false
        pumpFallbacks.incrementAndGet()
        count8173("LAUNCH_BUY_PUMP_FAILING_AGGREGATOR_8173")
        try {
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "LAUNCH_BUY_PUMP_FAILING_AGGREGATOR_8173",
                "mint=${mint.take(10)} memo=${routeMemos[mint]} action=jupiter_first",
            )
        } catch (_: Throwable) {}
        return true
    }

    /** SignedTxLedger8085.transition: a BUY reached chain truth ([state] CONFIRMED / FAILED_ON_CHAIN / EXPIRED_PROVEN). */
    fun noteBuyOutcome8173(mint: String, route: String, state: String, nowMs: Long = System.currentTimeMillis()) {
        val slot = when (state) { "CONFIRMED" -> 0; "EXPIRED_PROVEN" -> 1; "FAILED_ON_CHAIN" -> 2; else -> return }
        if (mint.isBlank()) return
        val r = route.trim().uppercase().ifBlank { "UNKNOWN" }
        if (r == "PUMPPORTAL") {
            routeMemos.compute(mint) { _, p -> nextMemo8173(p, slot == 0, nowMs) }
            if (routeMemos.size > 1_000) routeMemos.entries.removeIf { nowMs - maxOf(it.value.lastFailMs, it.value.lastLandedMs) > ROUTE_FAIL_WINDOW_MS_8173 }
        }
        if (!launchCohortMint8173(mint, nowMs)) return
        val row = launchLanding.computeIfAbsent(r) { IntArray(3) }
        synchronized(row) { row[slot]++ }
        count8173("LAUNCH_BUY_${r.take(16)}_${state}_8173")
    }

    fun statusLine8173(): String {
        val snap = launchLanding.entries.associate { (k, v) -> k to synchronized(v) { v.copyOf() } }
        return "launch8173[landing{${landingLine8173(snap)}} urgentBuys=${urgentBuys.get()} pumpFallbacks=${pumpFallbacks.get()} " +
            "curvePriceImplied=${priceImplied.get()} curveStaleReads=${staleAsked.get()} curveAtIntake=${intakeAsked.get()}]"
    }
}
