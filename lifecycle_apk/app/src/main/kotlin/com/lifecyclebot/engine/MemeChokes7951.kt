package com.lifecyclebot.engine

import com.lifecyclebot.data.CandidateDecision
import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7951 §THE_MEME_PIPELINE_WAS_CHOKED_BEFORE_CAPITAL_MATTERED.
 *
 * 5.0.7949 at 146 s uptime, read against the code. Each helper here is the
 * single-line replacement for one defect (the call sites live in pinned methods
 * that may only shrink):
 *
 *  - V3 SIZE_ZERO (24) is SmartSizerV3 returning 0 on a 0.04 SOL wallet after V3
 *    had already scored the token EXECUTE. It hard-stopped the ShitCoin lane like a
 *    rug verdict. It is a capital artefact: the lane decides and the canonical
 *    sizing / capital rotation answers for capital ([v3RejectIsLaneRouting7951]).
 *    V3 TOO_OLD (79) is V3's fixed 6 h trunk window; the lane's own age check rules.
 *  - INTAKE/PAIR_PENDING_HYDRATION (53) / PAIR_HARD_UNAVAILABLE (5): a curve mint
 *    without the "pump" suffix, or one whose first mark had not landed, waited 45 s
 *    on DEX pair hydration and was then demoted, although the curve is the venue
 *    and its reserves were in hand ([curveVenueSeeded7951]).
 *  - FDG/ZERO_CONFIDENCE_LIVE_DEFER_7403 (19): TREASURY, MANIPULATED,
 *    PROJECT_SNIPER and the V3 trunk hand the gate the legacy strategy decision,
 *    whose aiConfidence is 0 until that field is populated, beside the lane's own
 *    measured confidence ([laneConfidence7951]).
 *  - FDG/LIVE_CONVICTION_7697:SPECIALIST_REJECTS (8): CASHGEN score=90 rejected
 *    with LIVE_WALLET_TOO_SMALL, a statement about the wallet, not the token
 *    ([isCapitalArtefactReason7951]).
 *  - FDG/SIZE_NOT_EXECUTABLE_7835 (19): an approved entry the wallet cannot fund.
 *    When the chart reader says BUY it is now recorded as real demand that
 *    CAPITAL_ROTATION_7948 may free dead money for ([provenDemandWaiting7951]).
 */
object MemeChokes7951 {
    private const val DEMAND_WINDOW_MS = 3L * 60_000L
    private const val CURVE_PRICE_MAX_AGE_MS = 10L * 60_000L

    private val counters = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var lastChartDemandAtMs = 0L
    @Volatile private var lastChartDemand = ""

    private fun count(key: String) {
        counters.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("MEME_CHOKE_7951_$key") } catch (_: Throwable) {}
    }

    // ── V3 verdicts that are routing / capital, not token truth ─────────────

    /**
     * Pure: a V3 Rejected reason that must not terminate a meme lane's own
     * evaluation. SHITCOIN_CANDIDATE / MCAP_TOO_LOW are routing (V5.0.4233);
     * SIZE_ZERO is the V3 sizer's capital answer on an EXECUTE-band score; TOO_OLD
     * is V3's fixed 6 h trunk window, while ShitCoinTraderAI applies its own
     * (fluid) TOKEN AGE CHECK. Treasury already releases both (V5.0.7389).
     * Liquidity, exposure, cooldown, safety and score rejects stay binding.
     */
    fun v3RejectIsLaneRouting7951(reason: String): Boolean {
        val r = reason.trim()
        return r.contains("SHITCOIN_CANDIDATE") || r.contains("MCAP_TOO_LOW") ||
            // V5.0.7951 review — TOO_OLD stays fatal: V3 reads true on-chain age, the lane's own
            // check reads watchlist age, and EligibilityGate stops at the first failure.
            r.equals("SIZE_ZERO", ignoreCase = true)
    }

    // ── the curve is the venue ──────────────────────────────────────────────

    /** Pure: is this mint traded on its pump.fun curve (not yet graduated)? */
    fun isCurveVenue7951(pumpSuffix: Boolean, curveKeyKnown: Boolean, reservesKnown: Boolean, graduated: Boolean): Boolean =
        pumpSuffix || (!graduated && (curveKeyKnown || reservesKnown))

    /**
     * processTokenCycle's no-pair branch: true when the mint's venue is its curve.
     * A curve with no mark yet is priced from its last observed reserves
     * (CurveReserves7951, at most [CURVE_PRICE_MAX_AGE_MS] old), so the cycle
     * synthesises its source-native pair now instead of waiting on DEX hydration.
     */
    fun curveVenueSeeded7951(ts: TokenState): Boolean {
        val mint = ts.mint
        if (mint.isBlank()) return false
        val curve = try {
            isCurveVenue7951(
                pumpSuffix = com.lifecyclebot.network.PumpFunDirectApi.isPumpFunMint(mint),
                curveKeyKnown = com.lifecyclebot.network.PumpCurveKeys7269.keyFor(mint) != null,
                reservesKnown = com.lifecyclebot.engine.truth.CurveReserves7951.known7951(mint),
                graduated = com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint),
            )
        } catch (_: Throwable) { false }
        if (!curve || ts.lastPrice > 0.0) return curve
        val now = System.currentTimeMillis()
        val priceSol = try {
            com.lifecyclebot.engine.truth.CurveReserves7951.priceSolFor7951(mint, now, CURVE_PRICE_MAX_AGE_MS)
        } catch (_: Throwable) { 0.0 }
        val solUsd = try { WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val px = priceSol * solUsd
        if (px.isFinite() && px > 0.0) {
            synchronized(ts) {
                if (ts.lastPrice <= 0.0) {
                    ts.lastPrice = px
                    ts.lastPriceUpdate = now
                    ts.lastPriceSource = "PUMP_CURVE_RESERVES_SEED_7951"
                    if (ts.lastMcap <= 0.0) ts.lastMcap = px * 1_000_000_000.0
                }
            }
            count("CURVE_PRICE_SEEDED")
        }
        count("CURVE_VENUE_NO_PAIR")
        return curve
    }

    // ── confidence that was simply not populated ────────────────────────────

    /**
     * Pure: a candidate whose aiConfidence is unpopulated (<= 0) carries the
     * owner lane's own measured confidence instead. A populated value is never
     * changed, and the entry score is untouched.
     */
    fun laneConfidence7951(base: CandidateDecision, laneConfidence: Double): CandidateDecision {
        if (base.aiConfidence > 0.0 || !laneConfidence.isFinite() || laneConfidence <= 0.0) return base
        count("LANE_CONFIDENCE_FILLED")
        return base.copy(aiConfidence = laneConfidence.coerceIn(0.0, 100.0))
    }

    // ── capital is not conviction ───────────────────────────────────────────

    private val CAPITAL_MARKERS = listOf(
        "WALLET_TOO_SMALL", "CAPITAL_BELOW_MIN", "INSUFFICIENT_BALANCE", "INSUFFICIENT_SOL",
        "BELOW_MIN_NOTIONAL", "SIZE_ZERO", "NO_HEADROOM",
    )

    /** Pure: is a specialist's refusal reason about the wallet rather than the token? */
    fun isCapitalArtefactReason7951(reason: String): Boolean {
        val u = reason.uppercase()
        val hit = CAPITAL_MARKERS.any { u.contains(it) }
        if (hit) count("CAPITAL_ARTEFACT_NOT_CONVICTION")
        return hit
    }

    // ── an approved entry the wallet cannot fund is demand ─────────────────

    /** Pure: is this approved live entry starved by capital (not by depth or risk)? */
    fun capitalStarved7951(paper: Boolean, freeSol: Double, minimumSol: Double): Boolean =
        !paper && minimumSol.isFinite() && minimumSol > 0.0 && freeSol.isFinite() && freeSol < minimumSol

    /**
     * FinalDecisionGate.resolveExecutableSize7835: an approved entry the wallet
     * cannot fund. When the chart reader says BUY for it, it is real demand that
     * capital rotation may serve.
     */
    fun noteCapitalStarved7951(mint: String, lane: String, freeSol: Double, minimumSol: Double, paper: Boolean) {
        if (!capitalStarved7951(paper, freeSol, minimumSol)) return
        count("APPROVED_ENTRY_CAPITAL_STARVED")
        val chartBuy = try { com.lifecyclebot.engine.chart.ChartReader7950.saysBuy(mint) } catch (_: Throwable) { false }
        if (!chartBuy) return
        lastChartDemandAtMs = System.currentTimeMillis()
        lastChartDemand = "${lane.uppercase()}:${mint.take(10)}"
        count("CHART_BUY_WAITING_ON_CAPITAL")
    }

    /** Pure: is recorded demand still current? */
    fun demandCurrent7951(lastDemandAtMs: Long, nowMs: Long): Boolean =
        lastDemandAtMs > 0L && nowMs - lastDemandAtMs in 0L..DEMAND_WINDOW_MS

    /** Capital rotation's "a proven setup is waiting": a proven live lane, or a chart BUY waiting on capital. */
    fun provenDemandWaiting7951(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (demandCurrent7951(lastChartDemandAtMs, nowMs)) {
            count("ROTATION_FOR_CHART_DEMAND")
            return true
        }
        return com.lifecyclebot.engine.truth.CapitalDrawdown7948.anyLiveLaneProven7948()
    }

    fun statusLine7951(): String =
        "memeChokes7951 ${counters.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }} " +
            "lastChartDemand=${lastChartDemand.ifBlank { "-" }} " +
            (try { com.lifecyclebot.engine.truth.CurveReserves7951.statusLine7951() } catch (_: Throwable) { "" })
}
