package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8757 §LIVE_MOONSHOT_MIRRORS_THE_PAPER_BOOK — owner, 8756 log: "it does fuck all. no live trades no profits nothing".
 * FDG allowed 0 of 163 live candidates (admission: EDGE_MODEL_NEGATIVE 48, LIVE_ORIGIN_COHORT_LOSES 36, DATA_INCOMPLETE 34,
 * EXECUTION_ECONOMICS 25, SAFETY 19). The one profitable book was the paper MOONSHOT book beside live: n176, +7.9 % ±15.3,
 * PF 1.46, +1.389 SOL — runner-carried and not statistically proven. The owner chose (normal sizing): a LIVE MOONSHOT
 * candidate the live admission refused on a LEARNED door is admitted when the paper book's own decision admits it
 * (CandidateAdmission8136.decide8136 the paper way, exactly what MemePaperTrader8544.consider8544 opens on).
 *
 * Kept binding: safety, structure, liquidity, wallet / ledger integrity, the kill switch, missing or stale data, the
 * executable cost check and this bot's own loss lockouts ([hard8757]). Automatic stop: the mirror switches itself off once
 * live MOONSHOT closes since its first mirrored admit realise -[LOSS_STOP_SOL_8757] SOL. The stop window starts at the first
 * mirrored admit of the process.
 */
object PaperMirror8757 {
    const val REASON_8757 = "PAPER_MIRROR_MOONSHOT_8757"
    const val LOSS_STOP_SOL_8757 = 0.1
    @Volatile var enabled8757: Boolean = true
    @Volatile private var startMs8757 = 0L
    @Volatile private var tripped8757 = false
    private val mirrored8757 = AtomicLong(0L)
    private val bookLosing8865 = AtomicLong(0L)

    private val HARD_8757 = listOf(
        "SAFETY", "STRUCTURAL", "RUG", "FREEZE", "MINT_AUTH", "TOKEN_2022", "TOKEN2022", "T22_", "HOSTILE", "HONEYPOT", "SELLAB",
        "UNSELLABLE", "MAYHEM", "BLACKLIST", "HARD_BLOCK", "LIQ_TOO_LOW", "LIQUIDITY_FLOOR", "LIQUIDITY_COLLAPSE", "LANE_LIQ",
        "EXIT_SAFE", "IDENTITY", "PRICE_BASIS", "BASIS_PROOF", "UNTRUSTED_MARK", "WALLET", "BLIND", "BALANCE", "LEDGER",
        "DUPLICATE", "SINGLE_FLIGHT", "KILL_SWITCH", "HALT", "DEAD_LAUNCH", "EMPTY_CURVE", "ROUTE_MIN", "SIZE_NOT_EXECUTABLE",
        "QUARANT", "REBUY", "BIG_LOSER", "REPEAT_LOSER", "DATA_", "STALE", "EXECUTION_ECONOMICS", "EXECUTION_NET_NONPOSITIVE",
        "COST_", "_FAILED",
        // V5.0.8758 — code audit: structural / executional refusals the mirror must never override.
        "CURVE_NOBODY_BOUGHT", "AT_LAUNCH_CAP", "NET_EDGE", "PRICE_COLLAPSED", "BUY_DOOR_PROOF",
    )
    /** V5.0.8758 — at most this many mirrored admits per rolling hour (open exposure bound while the stop reads closes). */
    const val MAX_PER_HOUR_8758 = 4
    private val admitTimes8758 = java.util.ArrayDeque<Long>()
    @Volatile private var stopCheckedAtMs8758 = 0L

    /**
     * Is [reason] a refusal the mirror never overrides?
     * V5.0.8763 §ONE_HARD_LIST — code audit (buy 4, 14): the mirror kept a third copy of the hard list, so at the gate it
     * passed refusals the final gate treats as hard (FdgGate8365's markers, e.g. AUTHORITY, COST_REQUOTE, the eval-failed
     * marker) and learned doors whose own graded record proves the coins they refuse lose (GateOutcome8418.doorProtects8715,
     * e.g. CURVE_NOBODY_BOUGHT_8046 n61 -38.9 %). It now refuses the union: its own list, every FdgGate8365 hard marker, and
     * any door that protects by record in [lane] (else in all lanes). What the owner chose to mirror past stays mirrored:
     * learned doors whose record does not (yet) prove they protect - the edge model, cohort, setup and cell opinions.
     */
    fun hard8757(reason: String?, lane: String = ""): Boolean {
        if (reason.isNullOrBlank()) return true
        val r = reason.uppercase()
        if (HARD_8757.any { r.contains(it) } || FdgGate8365.hardSafety8392(r)) return true
        return try { GateOutcome8418.doorProtects8715(r.removePrefix("CANDIDATE_ADMISSION_8136_"), lane) } catch (_: Throwable) { true }
    }

    /** Pure: the lane the mirror serves. */
    fun mirrorLane8757(lane: String?): Boolean = lane?.trim()?.uppercase()?.startsWith("MOONSHOT") == true

    /** Pure: has [realisedSol] since the first mirrored admit reached the automatic stop? */
    fun lossStopHit8757(realisedSol: Double): Boolean = realisedSol.isFinite() && realisedSol <= -LOSS_STOP_SOL_8757

    /**
     * V5.0.8761 §THE_MIRROR_STOP_HOLDS_ACROSS_RESTARTS — code audit (buy 3, accounting 9): the stop window and the tripped
     * flag lived in memory, so every restart (the 8756 log's last crash was an OutOfMemoryError) re-armed the stop from
     * zero; and only realised closes counted, so a book of open mirrored positions under water did not. Both are now
     * persisted ([load8761], read at boot), and the stop counts open live MOONSHOT positions opened inside the window at
     * their fresh executable mark ([openMarkSol8761]; no fresh mark adds nothing — no price is made up).
     */
    private const val PREFS_8761 = "aate_paper_mirror_8761"
    @Volatile private var prefs8761: android.content.SharedPreferences? = null

    /** Boot (LearningResetSweep7781.applyPendingAtBoot): read the persisted stop window and tripped flag. */
    fun load8761(ctx: android.content.Context) {
        val p = try { ctx.applicationContext.getSharedPreferences(PREFS_8761, android.content.Context.MODE_PRIVATE) } catch (_: Throwable) { return }
        prefs8761 = p
        startMs8757 = maxOf(startMs8757, p.getLong("start_ms", 0L))
        tripped8757 = tripped8757 || p.getBoolean("tripped", false)
    }

    private fun persist8761() {
        try { prefs8761?.edit()?.putLong("start_ms", startMs8757)?.putBoolean("tripped", tripped8757)?.apply() } catch (_: Throwable) {}
    }

    /** Pure: an open position's SOL gain or loss at [markPriceUsd]; 0 when the mark or the basis is unknown. */
    fun openMarkSol8761(entryPriceUsd: Double, markPriceUsd: Double?, openCostSol: Double): Double =
        if (markPriceUsd == null || !markPriceUsd.isFinite() || markPriceUsd <= 0.0 || !entryPriceUsd.isFinite() ||
            entryPriceUsd <= 0.0 || !openCostSol.isFinite() || openCostSol <= 0.0) 0.0
        else (markPriceUsd / entryPriceUsd - 1.0) * openCostSol

    private fun openMirrorLaneSol8761(sinceMs: Long): Double = try {
        CanonicalPositionAuthority6441.openPositions()
            .filter { it.mode == "live" && mirrorLane8757(it.lane) && it.openedAtMs >= sinceMs }
            .sumOf { openMarkSol8761(it.entryPriceUsd, MarkIdentityRepairAuthority7236.getExecutablePriceIfFresh7301(it.mint),
                it.entryCostSol - it.soldCostBasisSol) }
    } catch (_: Throwable) { 0.0 }

    private fun stopped8757(nowMs: Long): Boolean {
        if (tripped8757) return true
        if (startMs8757 <= 0L || nowMs - stopCheckedAtMs8758 < 60_000L) return false
        stopCheckedAtMs8758 = nowMs
        val realised = try {
            com.lifecyclebot.engine.LiveEntrySafetyHold.LiveConfidenceStats.byLane8280(startMs8757)
                .filterKeys { mirrorLane8757(it) }.values.sumOf { it.expectancySol * it.canonicalN }
        } catch (_: Throwable) { 0.0 }
        if (lossStopHit8757(realised + openMirrorLaneSol8761(startMs8757))) {
            tripped8757 = true
            persist8761()
            try { PipelineHealthCollector.labelInc("PAPER_MIRROR_LOSS_STOP_8757") } catch (_: Throwable) {}
        }
        return tripped8757
    }

    /**
     * CandidateAdmission8136.assess8136 (LIVE): a refused live MOONSHOT [d] becomes an ADMIT when the paper book admits the
     * same candidate and the refusal is not [hard8757]. Anything else passes through unchanged.
     */
    fun mirror8757(c: CandidateAdmission8136.Candidate8136, d: CandidateAdmission8136.Decision8136, nowMs: Long): CandidateAdmission8136.Decision8136 {
        if (!enabled8757 || !c.live || d.admitted || !mirrorLane8757(c.lane) || hard8757(d.reason, c.lane)) return d
        if (try { FdgGate8365.shadowOnly8393() } catch (_: Throwable) { true }) return d   // V5.0.8758 — never in a shadow rehearsal
        if (!c.costPct.isFinite() || c.costPct < 0.0 || stopped8757(nowMs)) return d
        // V5.0.8865 §THE_MIRROR_FOLLOWS_A_BOOK_THAT_PAYS — the owner chose the mirror on a paper MOONSHOT book at n176 +7.9 %,
        // PF 1.46. On 19 Nov (5.0.8862) that book's priced record read n43, 11 % wins, -22.9 % +/- 3.9, PF 0.06, and the
        // mirror admitted 12 live candidates on its say-so in 127 minutes: its stop reads LIVE closes only. A book its own
        // record proves losing (MemePaperTrader8544.laneProvenLosing8608: 30+ priced closes, mean + SE < 0, PF < 1) is not
        // followed; it keeps trading on paper and is followed again the moment that record stops proving a loss.
        if (try { MemePaperTrader8544.laneProvenLosing8608(c.lane) } catch (_: Throwable) { false }) {
            bookLosing8865.incrementAndGet()
            try { PipelineHealthCollector.labelInc("PAPER_MIRROR_BOOK_PROVEN_LOSING_8865") } catch (_: Throwable) {}
            return d
        }
        // V5.0.8758 — the live price bar, not the paper book's 5-minute one.
        if (CandidateAdmission8136.priceStale8136(c.priceAgeMs, hotLaunch = false, live = true)) return d
        val paper = try { CandidateAdmission8136.decide8136(c.copy(live = false, liveEdge8114 = null)) { false } } catch (_: Throwable) { return d }
        if (paper.terminal != CandidateAdmission8136.Terminal8136.ADMIT) return d
        // V5.0.8758 — the executable cost check binds on the mirrored admit: a finite net that clears the margin.
        if (!economicsClear8758(paper.expectedNetPct, paper.costPct, paper.costMargin8391)) {
            try { PipelineHealthCollector.labelInc("PAPER_MIRROR_ECONOMICS_REFUSED_8758") } catch (_: Throwable) {}
            return d
        }
        if (!rateRoom8758(nowMs)) { try { PipelineHealthCollector.labelInc("PAPER_MIRROR_RATE_CAP_8758") } catch (_: Throwable) {}; return d }
        if (startMs8757 <= 0L) { startMs8757 = nowMs; persist8761() }
        mirrored8757.incrementAndGet()
        try { PipelineHealthCollector.labelInc("PAPER_MIRROR_ADMIT_8757_${d.reason.take(48)}") } catch (_: Throwable) {}
        return d.copy(terminal = CandidateAdmission8136.Terminal8136.ADMIT, reason = REASON_8757, specific = false,
            chosenSetup = paper.chosenSetup ?: d.chosenSetup, trace = d.trace + "paperMirror8757 liveRefusal=${d.reason}",
            expectedNetPct = paper.expectedNetPct, costPct = paper.costPct, costSizeSol = paper.costSizeSol,
            costBreakdown = paper.costBreakdown, costMargin8391 = paper.costMargin8391, laneQualified8577 = true)
    }

    /** Pure (V5.0.8758): a finite expected net whose gross clears [margin] x the round trip. */
    fun economicsClear8758(expectedNetPct: Double, costPct: Double, margin: Double): Boolean =
        expectedNetPct.isFinite() && costPct.isFinite() && costPct >= 0.0 &&
            NetEdge8030.entryClears8030(expectedNetPct + costPct, costPct, margin)

    @Synchronized private fun rateRoom8758(nowMs: Long): Boolean {
        while (admitTimes8758.isNotEmpty() && nowMs - admitTimes8758.peekFirst() > 3_600_000L) admitTimes8758.pollFirst()
        if (admitTimes8758.size >= MAX_PER_HOUR_8758) return false
        admitTimes8758.addLast(nowMs)
        return true
    }

    /** LiveEdgeGate7877 (via LaneAuthority8577.advisoryAtGate8577): a mirrored admit is held only by a hard refusal. */
    fun passesAtGate8757(reason: String?, admission: CandidateAdmission8136.Decision8136?, lane: String = "MOONSHOT"): Boolean =
        enabled8757 && admission?.reason == REASON_8757 && !hard8757(reason, lane)

    fun statusLine8757(): String =
        "paperMirror8757[on=$enabled8757 lane=MOONSHOT mirrored=${mirrored8757.get()} stop=-${LOSS_STOP_SOL_8757}SOL tripped=$tripped8757 " +
            "notFollowedBookProvenLosing8865=${bookLosing8865.get()}]"
}
