package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.EntryEconomics8329
import com.lifecyclebot.engine.truth.NetEdge8030
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8343 §IF_IT_SEES_A_100X_OPPORTUNITY_TAKE_IT — the grab lane: a forming runner is admitted on ITS OWN evidence,
 * not on any lane's mcap / age / score / continuation mandate.
 *
 * 5.0.8341 report, 24 h: 37 runners >= 4x, 4 held (paper), 33 missed. The misses died at NATIVE lane mandates before any
 * admission or the evidence hold's exception door ever saw them: LUCIA +99,169 % NATIVE_CORE_YIELD_CLEAR_SPECIALIST (CORE
 * yielded to specialists who all refused on mcap / liq), JDuval +73,321 % NATIVE_MCAP_TOO_LOW, XMR +16,779 %
 * NATIVE_LANE_LIQ_BELOW_FLOOR, DARK +14,114 % / James +2,305 % NATIVE_LANE_LIQ_UNHYDRATED_DEFER (never re-evaluated after
 * the curve hydrated), Moneko +13,604 % NATIVE_THRESHOLD_FAIL_SCORE, SSP +402 % NATIVE_NO_CONTINUATION. RunTape8293
 * detected 14 / handed 13, "runners >= +400 %: caught = 0". Grab admissions existed (heldAdmits7970 = 107) only for coins
 * that had already passed a lane's mandate.
 *
 * THE LANE. Attribution tag [LANE_8343] ("RUNNER_GRAB"); the position is hosted by the detecting runner lane (MOONSHOT /
 * SHITCOIN / EXPRESS — [GRAB_LANES_8343]), because the exits already treat those lanes as runner lanes
 * (TradeExitContract8077.runnerFromEntryLane8104, the 8331 ladder) and the hold is latched through
 * RunnerGrab7967.holdAsRunner7972 (RunnerLive8153 basis -> the 7967 ladder, the soft-exit deferral, structure exits).
 *
 * THE LEGS ([refusal8343], pure) — each missing one refuses by name, all present admit:
 *   - no open position in the mint; at most [MAX_OPEN_8343] grabs open at once;
 *   - hard safety: a FULL TokenSafetyChecker verdict (checked, not HARD_BLOCK, mint AND freeze authority proven disabled,
 *     not stale) — never a cached-unknown ([safetyLeg8343]); Mayhem's live refusal binds;
 *   - venue exit-safe: an active pump.fun curve under the 8320 rule (LaneAgeLiquidityTruth8140.activeCurveExitSafe8319,
 *     >= 0.5 real SOL inside the curve TTL) or a pool whose hydrated depth (LiquidityTruth8037.hydrate8037) meets the
 *     $500 live exit floor; a curve the bot knows but cannot read as active is NOT a pool;
 *   - the forming-run evidence itself: RunnerGrab7967.grab7967 (proven cell + firming / standing) or
 *     RunnerGrab7967.formingNative8260 (an observed firming structure or a fresh RunTape8293 detection on an unlearned book);
 *   - economics at the GRAB SIZE (the route minimum): NetEdge8030.entryClears8030 (1.5x) on
 *     NetEdge8030.preTradeCostPct8329 at that size and the hydrated depth;
 *   - the 8329 chase cap for the launch-breakout tactic (EntryEconomics8329.CAP_LAUNCH_PCT_8329) against the decision
 *     price when one is recorded (the executor's pre-broadcast re-quote still binds at fill);
 *   - LIVE only: EvidenceHold8336.refusal8336(ts, lane, paper = false) must pass — it consults exceptional8336 (the
 *     owner's 8337 exception door, called, never re-implemented). PAPER trades paper: that is how the hold builds evidence.
 * No mcap band, no CORE yield-clear, no lane score threshold, no continuation test.
 *
 * WHERE IT ACTS. SpecialistBrainBridge7542.evaluate hands the native opinions to [reviewNative8343]: the first grab lane
 * whose refusal is a MANDATE (never safety / rug / dev / bundle / freeze / zero-liquidity — [mandateRefusal8343]) is
 * re-asked here and, admitted, turns eligible with reason `RUNNER_GRAB_8343:<evidence>` and setup RUNNER_FORMING_8260
 * (CandidateAdmission8136 reads it as the forming runner). The lane's own buy door (BotService.laneDoor8105) honours the
 * same admit for [ADMIT_TTL_MS_8343] ([admitted8343]) so the trader AI's re-run of the mandate does not stop it; the
 * route-minimum promotion takes the 8329 reason EARLY_RUNNER; FDG's generic score floor stands aside as it does for a
 * retired veto. Everything else on the path — CandidateAdmission, FDG, sizing, PreTradeHardGate, the executor's chase
 * check, every hard-safety wall — still decides.
 *
 * Counted: RUNNER_GRAB_ADMIT_8343 / RUNNER_GRAB_REFUSED_8343_<why>; the report line [statusLine8343].
 */
object RunnerGrabLane8343 {
    const val LANE_8343 = "RUNNER_GRAB"
    val GRAB_LANES_8343: List<String> = listOf("MOONSHOT", "SHITCOIN", "EXPRESS")
    const val MAX_OPEN_8343 = 3
    const val ADMIT_TTL_MS_8343 = 90_000L
    const val OPEN_WINDOW_MS_8343 = 6L * 3_600_000L
    const val SAFETY_MAX_AGE_MS_8343 = 10L * 60_000L
    private const val VERDICT_CACHE_MS_8343 = 5_000L
    const val ADMIT_LABEL_8343 = "RUNNER_GRAB_ADMIT_8343"
    const val REFUSED_PREFIX_8343 = "RUNNER_GRAB_REFUSED_8343_"
    const val REASON_PREFIX_8343 = "RUNNER_GRAB_8343:"
    const val SETUP_8343 = "RUNNER_FORMING_8260"
    const val VENUE_CURVE_8343 = "ACTIVE_CURVE_REAL_SOL_8319"
    const val VENUE_POOL_8343 = "POOL_EXIT_FLOOR_500"
    const val EVIDENCE_GRAB_8343 = "GRAB_7967"
    const val EVIDENCE_FORMING_8343 = "FORMING_8260"
    const val CORE_OWN_BAR_8343 = "CORE_OWN_ENSEMBLE_BELOW_BAR_8343"

    /** A refusal that is a token-wide wall, never a mandate the grab lane may look past. */
    private val BINDING_WORDS_8343 = listOf(
        "RUG", "SAFETY", "HONEYPOT", "BLOCK", "DEV", "BUNDLE", "SCAM", "FREEZE", "FROZEN", "MINT_AUTH", "BLACKLIST",
        "ERROR", "TOXIC", "QUARANTIN", "ZERO_LIQ", "NO_LIQ", "LIQ_ZERO", "IDENTITY", "SELLAB", "TOKEN_2022", "TOKEN2022",
        "MAYHEM", "CANNOT_SELL",
    )

    data class Legs8343(
        val positionOpen: Boolean,
        val safety: String?,
        val mayhem: Boolean,
        val venue: String?,
        val evidence: String?,
        val expectedGrossPct: Double,
        val costPct: Double,
        val movePct: Double,
        val openGrabs: Int,
        val liveHoldRefusal: String?,
    )

    data class Verdict8343(val admitted: Boolean, val why: String, val evidence: String, val venue: String, val atMs: Long)

    private class Admit8343(val atMs: Long, val lane: String, val paper: Boolean, val evidence: String)

    private val admits = ConcurrentHashMap<String, Admit8343>()               // mint -> the admit
    private val verdicts = ConcurrentHashMap<String, Verdict8343>()           // mint|lane -> last verdict (5 s)
    private val detectedSeen = ConcurrentHashMap<String, Long>()              // mint -> first forming read
    private val refusedBy = ConcurrentHashMap<String, AtomicLong>()
    private val closes = java.util.ArrayDeque<Triple<String, Double, Double>>() // (mode, returnPct, pnlSol)
    private val detected = AtomicLong(0)
    private val admitted = AtomicLong(0)
    private val paperAdmits = AtomicLong(0)
    private val liveAdmits = AtomicLong(0)
    private val notForming = AtomicLong(0)
    private val coreYieldFallback = AtomicLong(0)

    // ── pure ──

    /** Pure: is [reason] a lane MANDATE refusal (shape, band, floor, score, continuation, yield) and not a binding wall? */
    fun mandateRefusal8343(reason: String?): Boolean {
        val r = reason?.trim()?.uppercase()?.replace(' ', '_').orEmpty()
        if (r.isBlank()) return false
        return BINDING_WORDS_8343.none { r.contains(it) }
    }

    /**
     * Pure: the hard-safety leg. A full verdict is one the checker wrote ([checkedAtMs] > 0) that is not HARD_BLOCK, proves
     * BOTH authorities disabled, and is not stale. A cached-unknown (never checked, authority null) refuses by name.
     */
    fun safetyLeg8343(checkedAtMs: Long, tier: SafetyTier, mintDisabled: Boolean?, freezeDisabled: Boolean?, nowMs: Long): String? = when {
        checkedAtMs <= 0L -> "SAFETY_UNCHECKED"
        tier == SafetyTier.HARD_BLOCK -> "SAFETY_HARD_BLOCK"
        mintDisabled != true || freezeDisabled != true -> "SAFETY_AUTHORITY_UNPROVEN"
        nowMs - checkedAtMs > SAFETY_MAX_AGE_MS_8343 -> "SAFETY_STALE"
        else -> null
    }

    /** Pure: the 8329 chase leg for the launch-breakout tactic — null passes, else the consumed-edge why. */
    fun chaseLeg8343(movePct: Double, expectedGrossPct: Double, costPct: Double): String? =
        EntryEconomics8329.consumed8329(movePct, EntryEconomics8329.CAP_LAUNCH_PCT_8329, expectedGrossPct, costPct)

    /** Pure: why the legs refuse, else null (admit). Nothing here reads a mandate. */
    fun refusal8343(l: Legs8343): String? {
        if (l.positionOpen) return "POSITION_OPEN"
        if (l.safety != null) return l.safety
        if (l.mayhem) return "MAYHEM"
        if (l.venue.isNullOrBlank()) return "VENUE_NOT_EXIT_SAFE"
        if (l.evidence.isNullOrBlank()) return "NO_FORMING_RUN"
        if (!NetEdge8030.entryClears8030(l.expectedGrossPct, l.costPct)) return "ECONOMICS_1_5X_COST"
        val chase = chaseLeg8343(l.movePct, l.expectedGrossPct, l.costPct)
        if (chase != null) return "CHASE_$chase"
        if (l.openGrabs >= MAX_OPEN_8343) return "OPEN_BOUND_$MAX_OPEN_8343"
        if (l.liveHoldRefusal != null) return "LIVE_${l.liveHoldRefusal}"
        return null
    }

    /**
     * Pure: CORE's yield falls back to CORE when NO specialist took the coin ([eligibleSpecialists] == 0) and every
     * specialist refusal was a mandate (never safety): a clear owner that does not exist is not a clear.
     */
    fun coreYieldFalls8343(eligibleSpecialists: Int, specialistRefusals: List<String>): Boolean =
        eligibleSpecialists == 0 && specialistRefusals.isNotEmpty() && specialistRefusals.all { mandateRefusal8343(it) }

    /** Pure: the native reason a grab lane carries once admitted. */
    fun grabReason8343(evidence: String, venue: String): String = "$REASON_PREFIX_8343$evidence venue=$venue"

    /** Pure (V5.0.8345): the grab lane's legs as the evidence hold's exception reads them (null when the forming-run leg is missing). */
    fun grabLegs8345(evidence: String?, venue: String?): String? =
        if (evidence.isNullOrBlank()) null else "$evidence venue=${venue?.ifBlank { null } ?: "-"} safety=FULL economics=1.5x chase=CAP_LAUNCH"

    // ── runtime legs ──

    private fun canon(lane: String?): String = try { com.lifecyclebot.engine.truth.LiveRiskPolicy7807.canonicalLane(lane) } catch (_: Throwable) { lane.orEmpty().trim().uppercase() }

    private fun evidenceLeg(ts: TokenState, nowMs: Long): String? {
        if (try { RunnerGrab7967.grab7967(ts.mint, nowMs) } catch (_: Throwable) { false }) return EVIDENCE_GRAB_8343
        return if ((try { RunnerGrab7967.formingNative8260(ts, nowMs) } catch (_: Throwable) { null }) != null) EVIDENCE_FORMING_8343 else null
    }

    private fun venueLeg(ts: TokenState, nowMs: Long): String? {
        val mint = ts.mint
        val activeCurve = (try { com.lifecyclebot.engine.truth.CurveReserves7951.realSolIfActive8046(mint, nowMs) } catch (_: Throwable) { null }) != null
        if (activeCurve) {
            val safe = try { LaneAgeLiquidityTruth8140.activeCurveExitSafe8319(mint, nowMs) } catch (_: Throwable) { false }
            return if (safe) VENUE_CURVE_8343 else null
        }
        val graduated = try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint) } catch (_: Throwable) { false }
        val known = try { com.lifecyclebot.engine.truth.CurveReserves7951.known7951(mint) } catch (_: Throwable) { false }
        if (!graduated && known) return null
        val liq = try { com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { 0.0 }
        return if (liq.isFinite() && liq >= LaneAgeLiquidityScope8140.EXIT_SAFE_HARD_FLOOR_USD_8140) VENUE_POOL_8343 else null
    }

    /** The grab size: the route minimum of the live wallet (never a paper balance), else the default ticket. */
    fun grabSizeSol8343(): Double = try {
        val tradeable = (BotService.status.walletSol - com.lifecyclebot.engine.truth.LiveSpendReserveAuthority7255.RESERVE_SOL).coerceAtLeast(0.0)
        val solUsd = WalletManager.lastKnownSolPrice
        val min = com.lifecyclebot.v3.sizing.SmartSizerV3.routableCapacityPreflight7224(tradeable, solUsd).routableMinSol
        if (min.isFinite() && min > 0.0) min else NetEdge8030.DEFAULT_TICKET_SOL
    } catch (_: Throwable) { NetEdge8030.DEFAULT_TICKET_SOL }

    private fun expectedGross(ts: TokenState, lane: String, costPct: Double): Double {
        val g = try { NetEdge8030.expectedGrossFor8153(ts, lane, null, costPct) } catch (_: Throwable) { Double.NaN }
        if (g.isFinite()) return g
        return try { com.lifecyclebot.engine.truth.FieldManual7715.mandateFor(lane).expectedGrossPct } catch (_: Throwable) { Double.NaN }
    }

    private fun movePct(ts: TokenState, nowMs: Long): Double {
        val d = try { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.decisionPx8094(ts.mint) } catch (_: Throwable) { null } ?: return Double.NaN
        if (nowMs - d.second !in 0L..(10L * 60_000L)) return Double.NaN
        return EntryEconomics8329.quoteMovePct8329(d.first, ts.lastPrice, 0.0) ?: Double.NaN
    }

    /** Grabs admitted inside the hold window whose mint holds an open position now. */
    fun openGrabs8343(nowMs: Long = System.currentTimeMillis()): Int {
        var n = 0
        for ((mint, a) in admits) {
            if (nowMs - a.atMs > OPEN_WINDOW_MS_8343) continue
            val ts = try { BotService.status.tokens[mint] } catch (_: Throwable) { null } ?: continue
            if (ts.position.isOpen && ts.position.entryTime >= a.atMs - 5L * 60_000L) n++
        }
        return n
    }

    private fun count(why: String) {
        refusedBy.computeIfAbsent(why) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("$REFUSED_PREFIX_8343$why") } catch (_: Throwable) {}
    }

    /**
     * The grab lane's verdict on [ts] for host [lane] now. Idempotent inside [ADMIT_TTL_MS_8343] for an admit, cached
     * [VERDICT_CACHE_MS_8343] otherwise. The evidence hold is consulted only once every other leg has passed, so its
     * exception counters are spent on real candidates only.
     */
    fun admit8343(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): Verdict8343 {
        val mint = ts.mint
        val l = canon(lane)
        if (mint.isBlank()) return Verdict8343(false, "NO_MINT", "", "", nowMs)
        val key = "$mint|$l"
        verdicts[key]?.let { v ->
            val ttl = if (v.admitted) ADMIT_TTL_MS_8343 else VERDICT_CACHE_MS_8343
            if (nowMs - v.atMs in 0L..ttl) return v
        }
        if (verdicts.size > 2_000) verdicts.entries.removeIf { nowMs - it.value.atMs > ADMIT_TTL_MS_8343 }
        val v = decide(ts, l, nowMs)
        verdicts[key] = v
        return v
    }

    private fun decide(ts: TokenState, l: String, nowMs: Long): Verdict8343 {
        val mint = ts.mint
        if (ts.position.isOpen) return Verdict8343(false, "POSITION_OPEN", "", "", nowMs)
        val evidence = evidenceLeg(ts, nowMs)
        if (evidence == null) { notForming.incrementAndGet(); return Verdict8343(false, "NO_FORMING_RUN", "", "", nowMs) }
        if (detectedSeen.putIfAbsent(mint, nowMs) == null) {
            detected.incrementAndGet()
            if (detectedSeen.size > 4_000) detectedSeen.entries.removeIf { nowMs - it.value > 24L * 3_600_000L }
        }
        val s = ts.safety
        val safety = safetyLeg8343(s.checkedAt, s.tier, s.mintAuthorityDisabled, s.freezeAuthorityDisabled, nowMs)
        val mayhem = (try { MayhemMode7943.liveRefusal(ts, nowMs) } catch (_: Throwable) { null }) != null
        val venue = venueLeg(ts, nowMs)
        val size = grabSizeSol8343()
        val liq = try { com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { ts.lastLiquidityUsd }
        val cost = try { NetEdge8030.preTradeCostPct8329(l, size, liq, mint, ts.lastMcap) } catch (_: Throwable) { Double.NaN }
        val gross = expectedGross(ts, l, cost)
        val legs = Legs8343(false, safety, mayhem, venue, evidence, gross, cost, movePct(ts, nowMs), openGrabs8343(nowMs), null)
        var why = refusal8343(legs)
        val live = try { RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }
        if (why == null && live) {
            // V5.0.8345 — every grab leg passed: the hold is asked WITH the grab lane's own forming-run evidence, which satisfies
            // the exception door's legs (a)+(c) (EvidenceHold8336.exceptionalViaGrab8345; venue, lane-open and the rate bounds
            // still bind inside the hold). superseded: refusal8336(ts, l, false, nowMs) — the door only knew a proven grab cell.
            val hold = try { com.lifecyclebot.engine.truth.EvidenceHold8336.refusal8336(ts, l, false, nowMs, grabLegs8345 = grabLegs8345(evidence, venue)) } catch (_: Throwable) { "EVIDENCE_HOLD_ERROR" }
            why = refusal8343(legs.copy(liveHoldRefusal = hold))
            // V5.0.8345 — refused by the hold alone: the evidence shadow trades it on the HOST lane so the hold can unlock on it.
            if (hold != null && hold.startsWith("EVIDENCE_HOLD_8336_")) {
                try { com.lifecyclebot.engine.truth.EvidenceShadow8344.open8344(ts, l, nowMs, com.lifecyclebot.engine.truth.EvidenceShadow8344.SOURCE_GRAB_8345) } catch (_: Throwable) {}
            }
        }
        if (why != null) {
            count(why)
            try {
                ForensicLogger.lifecycle("RUNNER_GRAB_REFUSED_8343", "mint=${mint.take(10)} sym=${ts.symbol} lane=$l why=$why evidence=$evidence venue=${venue ?: "-"} " +
                    "gross=${"%.1f".format(gross)}% cost=${"%.1f".format(cost)}% size=${"%.4f".format(size)} open=${legs.openGrabs} action=refuse_by_name")
            } catch (_: Throwable) {}
            return Verdict8343(false, why, evidence, venue ?: "", nowMs)
        }
        val paper = !live
        admits[mint] = Admit8343(nowMs, l, paper, evidence)
        if (admits.size > 500) admits.entries.removeIf { nowMs - it.value.atMs > OPEN_WINDOW_MS_8343 }
        admitted.incrementAndGet()
        if (paper) paperAdmits.incrementAndGet() else liveAdmits.incrementAndGet()
        try { RunnerGrab7967.holdAsRunner7972(ts, nowMs) } catch (_: Throwable) {}
        try {
            PipelineHealthCollector.labelInc(ADMIT_LABEL_8343)
            PipelineHealthCollector.labelInc("${ADMIT_LABEL_8343}_$l")
            SpecialistOwnership7951.notePreIntent7951(l, "RUNNER_GRAB_ADMIT_8343")
            ForensicLogger.lifecycle(ADMIT_LABEL_8343, "mint=${mint.take(10)} sym=${ts.symbol} lane=$LANE_8343@$l mode=${if (paper) "paper" else "live"} evidence=$evidence venue=${venue!!} " +
                "gross=${"%.1f".format(gross)}% cost=${"%.1f".format(cost)}% size=${"%.4f".format(size)} mcap=${ts.lastMcap.toInt()} liq=${liq.toInt()} action=admit_on_own_evidence_downstream_gates_still_bind")
        } catch (_: Throwable) {}
        return Verdict8343(true, "ADMIT", evidence, venue!!, nowMs)
    }

    /** BotService.laneDoor8105 / EntryEconomics8329: was [mint] admitted by the grab lane for host [lane] inside the TTL? */
    fun admitted8343(mint: String, lane: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val a = admits[mint] ?: return false
        return a.lane == canon(lane) && nowMs - a.atMs in 0L..ADMIT_TTL_MS_8343
    }

    /**
     * SpecialistBrainBridge7542.evaluate, after the native opinions are formed: (1) every UNHYDRATED_DEFER refusal is
     * remembered for the hydration re-evaluation (HydrationReeval8343); (2) the first grab lane refused on a MANDATE is
     * re-asked on the grab's own legs and, admitted, turns eligible; (3) a hydration-queued mint counts its evaluation.
     */
    fun reviewNative8343(ts: TokenState, out: MutableMap<String, SpecialistBrainBridge7542.Opinion>, nowMs: Long) {
        var version: Long? = null
        for ((lane, o) in out) {
            if (!o.eligible && o.authoritative && ContinuationRead8348.dataDefer8348(o.reason)) {   // V5.0.8348 — the momentum deferral too
                val v: Long = version ?: (try { LaneExecutionCoordinator.candidateVersionFor(ts.mint) } catch (_: Throwable) { -1L }).also { version = it }
                HydrationReeval8343.noteDeferred8343(ts.mint, lane, o.reason, v, nowMs)
            }
        }
        val host = GRAB_LANES_8343.firstOrNull { l -> out[l]?.let { !it.eligible && it.authoritative && mandateRefusal8343(it.reason) } == true }
        if (host != null) {
            val o = out[host]!!
            val v = admit8343(ts, host, nowMs)
            if (v.admitted) out[host] = o.copy(eligible = true, reason = grabReason8343(v.evidence, v.venue), setup = SETUP_8343, gradeable = true)
        }
        HydrationReeval8343.onEvaluated8343(ts.mint, out.values.any { it.eligible && it.authoritative && it.lane != "CORE" }, nowMs)
    }

    /** SpecialistBrainBridge7542: CORE's refusal name when no specialist took the coin (counted), else the yield-clear. */
    fun coreYieldReason8343(noEligibleSpecialist: Boolean, specialists: Collection<SpecialistBrainBridge7542.Opinion>): String {
        val refusals = specialists.filter { it.lane != "CORE" && it.authoritative && !it.eligible }.map { it.reason }
        if (!coreYieldFalls8343(if (noEligibleSpecialist) 0 else 1, refusals)) return "CORE_YIELD_CLEAR_SPECIALIST_OWNER"
        coreYieldFallback.incrementAndGet()
        try { PipelineHealthCollector.labelInc("CORE_YIELD_FALLBACK_8343") } catch (_: Throwable) {}
        return CORE_OWN_BAR_8343
    }

    /** CanonicalFinalizedTradeBus6464: a terminal close of a grabbed mint joins the grab's own record. */
    fun onClose8343(env: com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope) {
        val a = admits[env.mint] ?: return
        if (!env.terminal || env.atMs < a.atMs || env.atMs - a.atMs > OPEN_WINDOW_MS_8343 + 3_600_000L) return
        synchronized(closes) {
            closes.addLast(Triple(env.mode.lowercase(), env.realizedReturnPct, env.realizedPnlSol))
            while (closes.size > 200) closes.removeFirst()
        }
        try { PipelineHealthCollector.labelInc("RUNNER_GRAB_CLOSE_8343_${env.mode.uppercase().take(8)}") } catch (_: Throwable) {}
    }

    /** Pure: "n/mean%/pf" over (mode, returnPct, pnlSol) rows, or "-" for none. */
    fun closesLine8343(rows: List<Triple<String, Double, Double>>): String {
        val r = rows.filter { it.second.isFinite() }
        if (r.isEmpty()) return "-"
        val mean = r.map { it.second.coerceIn(-100.0, 1_000.0) }.average()
        val wins = r.filter { it.third > 0.0 }.sumOf { it.third }
        val losses = -r.filter { it.third < 0.0 }.sumOf { it.third }
        val pf = if (losses > 0.0) wins / losses else if (wins > 0.0) Double.POSITIVE_INFINITY else 0.0
        val paper = r.count { it.first == "paper" }
        return "n${r.size}/${"%+.1f".format(mean)}%/pf${if (pf.isInfinite()) "inf" else "%.2f".format(pf)} (paper $paper, live ${r.size - paper})"
    }

    fun coreYieldFallbackCount8343(): Long = coreYieldFallback.get()

    fun statusLine8343(nowMs: Long = System.currentTimeMillis()): String {
        val refused = refusedBy.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        val rows = synchronized(closes) { closes.toList() }
        return "runnerGrab8343[detected=${detected.get()} admitted=${admitted.get()} refused=[$refused] open=${openGrabs8343(nowMs)}/$MAX_OPEN_8343 " +
            "paper=${paperAdmits.get()} live=${liveAdmits.get()} closes=${closesLine8343(rows)} notForming=${notForming.get()} " +
            "coreYieldFallback8343=${coreYieldFallback.get()} lane=$LANE_8343@${GRAB_LANES_8343.joinToString("|")}] ${HydrationReeval8343.statusLine8343(nowMs)}" +
            ContinuationRead8348.statusLine8348()   // V5.0.8348
    }

    internal fun resetForTests8343() {
        admits.clear(); verdicts.clear(); detectedSeen.clear(); refusedBy.clear(); synchronized(closes) { closes.clear() }
        detected.set(0); admitted.set(0); paperAdmits.set(0); liveAdmits.set(0); notForming.set(0); coreYieldFallback.set(0)
    }
}
