package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.truth.TradeTape8100
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8293 — THE RUN TAPE HUNTER: see a run start on the trades themselves.
 *
 * 8286: runner grab heldAdmits=17 grabs=0 record30m=none; tail hunter runners=1; re-entry runsConfirmed=1. Every runner
 * path waited for a coin to already be on the watchlist, already in a proven cell, already +100% over a decision price.
 * Nothing hunted for the START of a run, while the PumpPortal / Helius executed-trade tape (TradeTape8100, one venue per
 * series, signature-deduplicated across feeds since 8285) shows it directly: a burst of real buys taking the price up
 * off its low and holding near the high.
 *
 * DETECT (pure, [detect8293]) over a mint's executed prints in the last 3 minutes: at least 10 prints, the newest within
 * 20 s, 6+ buys (3+ in the last minute), 1+ SOL bought, buyers >= 60% of SOL volume and >= 55% of prints, price +15% to
 * +150% off the window low (rising, not already vertical), within 10% of the window high, and up 5%+ over the last
 * minute (still going). Nothing is inferred: price-only curve snapshots are not prints.
 *
 * HAND OFF ([scan8293], every 10 s from ResidentHunterWorker7807): the coin is claimed for MOONSHOT and SHITCOIN (and
 * PROJECT_SNIPER while its known launch age is inside the sniper window), published to their resident candidate books
 * with the evidence, and requeued to the ordinary intake when it is not watched. RunnerGrab7967.formingNative8260 reads
 * a fresh detection as the observed forming read. Hard safety, Mayhem, a proven-losing runner cell, critical facts,
 * fresh price, net economics, FDG and sizing all still decide. A coin with an open position, a HARD_BLOCK safety tier or
 * a Mayhem refusal is never handed off.
 *
 * LEARN: every detection is graded 30 minutes later on the same tape (no print near the horizon = ungraded, never
 * invented). Once 20 grades read mean + SE < 0 the hand-off stands down (detections keep being graded) and re-arms when
 * the record recovers. The record persists across restarts.
 */
object RunTape8293 {
    private const val WINDOW_MS_8293 = 180_000L
    private const val FRESH_PRINT_MS_8293 = 20_000L
    private const val MIN_PRINTS_8293 = 10
    private const val MIN_BUYS_8293 = 6
    private const val MIN_RECENT_BUYS_8293 = 3
    private const val MIN_BUY_SOL_8293 = 1.0
    private const val MIN_BP_SOL_8293 = 60.0
    private const val MIN_BP_TX_8293 = 55.0
    private const val MIN_RISE_PCT_8293 = 15.0
    private const val MAX_RISE_PCT_8293 = 150.0
    private const val MIN_VS_PEAK_8293 = 0.90
    private const val MIN_LAST_MINUTE_PCT_8293 = 5.0
    private const val COOLDOWN_MS_8293 = 10L * 60_000L
    const val EVIDENCE_TTL_MS_8293 = 5L * 60_000L
    const val GRADE_AFTER_MS_8293 = 30L * 60_000L
    private const val GRADE_TOLERANCE_MS_8293 = 90_000L
    private const val MAX_TRACKED_8293 = 2_000
    private const val SNIPER_HEADROOM_MS_8293 = 30_000L

    data class Run8293(
        val prints: Int, val buys: Int, val recentBuys: Int, val buySol: Double, val bpSolPct: Double, val bpTxPct: Double,
        val risePct: Double, val vsPeak: Double, val lastMinutePct: Double, val pxSol: Double, val atMs: Long,
        /** V5.0.8729 — [KIND_RUN_8293] (a run already under way) or [KIND_BREAK_8729] (a base breaking out). */
        val kind: String = KIND_RUN_8293,
    ) {
        fun evidence(): String = "$kind prints=$prints buys=$buys/${recentBuys}1m buySol=${"%.2f".format(buySol)} " +
            "bpSol=${bpSolPct.toInt()}% bpTx=${bpTxPct.toInt()}% rise=+${risePct.toInt()}% vsPeak=${"%.2f".format(vsPeak)} " +
            "1m=+${lastMinutePct.toInt()}%"
    }

    private const val KIND_RUN_8293 = "RUN_TAPE_8293"
    const val KIND_BREAK_8729 = "BASE_BREAK_8729"

    /*
     * V5.0.8729 §BUY_THE_BREAK_NOT_THE_RUN — owner: "we need to identify the coins as they break out". The run detector
     * above fires on a coin already +15 % to +150 % off its low: by construction it buys the move after it happened. 8727
     * live: marsoperator handed at +28 % and stopped at -14 % ten seconds after the buy, EBreGK handed at +35 % and stopped
     * at -12.8 %; runnerAdmits8297 live: 78 of 113 admits bought the top. The 8720 edge dataset says the same by stage: a
     * coin at its high that has run 0-5 % loses the round trip (-2.8 %), 30-60 % loses -7.9 %, 60-150 % loses -17.4 %.
     *
     * A breakout is a coin that has been holding a range and is now leaving it upward on real buying:
     *   BASE  the prints older than [BREAK_LEG_MS_8729] span at least [BASE_MIN_MS_8729] with [BASE_MIN_PRINTS_8729]+
     *         observations, and their whole range is at most [BASE_MAX_RANGE_PCT_8729] (a meme coin's quiet range);
     *   BREAK the newest price is above the base's high by no more than [BREAK_MAX_PCT_8729] (the first leg, not the
     *         run), and the prints of the break leg are executed buying: [BREAK_MIN_BUYS_8729]+ buys, [BREAK_MIN_SOL_8729]+
     *         SOL bought and at least [BREAK_MIN_BP_SOL_8729] % of the leg's SOL on the buy side.
     * Price-only observations (curve reads) count toward the base's levels; only executed prints count as buying.
     * Every break is graded on the tape 30 minutes later into its OWN record ([breakRecord]) and stands down on that record
     * exactly as the run detector does on its own; the two records never mix. The numbers are the definition of the
     * pattern; whether it pays is the record's to say.
     */
    internal const val BREAK_WINDOW_MS_8729 = 6L * 60_000L
    internal const val BREAK_LEG_MS_8729 = 45_000L
    internal const val BASE_MIN_MS_8729 = 90_000L
    internal const val BASE_MIN_PRINTS_8729 = 8
    internal const val BASE_MAX_RANGE_PCT_8729 = 25.0
    internal const val BREAK_MAX_PCT_8729 = 12.0
    internal const val BREAK_MIN_BUYS_8729 = 3
    internal const val BREAK_MIN_SOL_8729 = 0.5
    internal const val BREAK_MIN_BP_SOL_8729 = 65.0

    /** Pure: is [prints] (one mint's prints, oldest first) a base breaking out at [nowMs]? */
    fun detectBreak8729(prints: List<TradeTape8100.Print8128>, nowMs: Long): Run8293? {
        val w = prints.filter { nowMs - it.atMs in 0L..BREAK_WINDOW_MS_8729 && it.priceSol.isFinite() && it.priceSol > 0.0 }
        val newest = w.lastOrNull() ?: return null
        if (nowMs - newest.atMs > FRESH_PRINT_MS_8293) return null
        val cut = nowMs - BREAK_LEG_MS_8729
        val base = w.filter { it.atMs < cut }
        val leg = w.filter { it.atMs >= cut }
        if (base.size < BASE_MIN_PRINTS_8729 || base.last().atMs - base.first().atMs < BASE_MIN_MS_8729) return null
        val hi = base.maxOf { it.priceSol }
        val lo = base.minOf { it.priceSol }
        val rangePct = (hi / lo - 1.0) * 100.0
        if (!rangePct.isFinite() || rangePct > BASE_MAX_RANGE_PCT_8729) return null
        val last = newest.priceSol
        val breakPct = (last / hi - 1.0) * 100.0
        if (!(breakPct > 0.0) || breakPct > BREAK_MAX_PCT_8729) return null
        val exec = leg.filter { !it.priceOnly }
        val buys = exec.filter { it.isBuy }
        val buySol = buys.sumOf { if (it.sol.isFinite() && it.sol > 0.0) it.sol else 0.0 }
        val totSol = exec.sumOf { if (it.sol.isFinite() && it.sol > 0.0) it.sol else 0.0 }
        if (buys.size < BREAK_MIN_BUYS_8729 || buySol < BREAK_MIN_SOL_8729 || !(totSol > 0.0)) return null
        val bpSol = buySol / totSol * 100.0
        if (bpSol < BREAK_MIN_BP_SOL_8729) return null
        val legPeak = leg.maxOf { it.priceSol }
        return Run8293(w.size, buys.size, buys.size, buySol, bpSol, if (exec.isEmpty()) 0.0 else buys.size * 100.0 / exec.size,
            breakPct, last / maxOf(legPeak, hi), breakPct, last, nowMs, KIND_BREAK_8729)
    }

    // ── pure ──

    /** Pure: is [prints] (one mint's executed prints, oldest first) a run starting at [nowMs]? */
    fun detect8293(prints: List<TradeTape8100.Print8128>, nowMs: Long): Run8293? {
        val w = prints.filter { nowMs - it.atMs in 0L..WINDOW_MS_8293 && it.priceSol.isFinite() && it.priceSol > 0.0 }
        if (w.size < MIN_PRINTS_8293) return null
        val newest = w.last()
        if (nowMs - newest.atMs > FRESH_PRINT_MS_8293) return null
        val buys = w.count { it.isBuy }
        val recentBuys = w.count { it.isBuy && nowMs - it.atMs <= 60_000L }
        if (buys < MIN_BUYS_8293 || recentBuys < MIN_RECENT_BUYS_8293) return null
        val buySol = w.filter { it.isBuy }.sumOf { if (it.sol.isFinite() && it.sol > 0.0) it.sol else 0.0 }
        val totSol = w.sumOf { if (it.sol.isFinite() && it.sol > 0.0) it.sol else 0.0 }
        if (buySol < MIN_BUY_SOL_8293 || !(totSol > 0.0)) return null
        val bpSol = buySol / totSol * 100.0
        val bpTx = buys * 100.0 / w.size
        if (bpSol < MIN_BP_SOL_8293 || bpTx < MIN_BP_TX_8293) return null
        val last = newest.priceSol
        val peak = w.maxOf { it.priceSol }
        // the low the run started from: the lowest print at or before the window's high
        val peakIdx = w.indexOfLast { it.priceSol >= peak }
        val low = w.subList(0, peakIdx + 1).minOf { it.priceSol }
        val rise = (last / low - 1.0) * 100.0
        if (rise < MIN_RISE_PCT_8293 || rise > MAX_RISE_PCT_8293) return null
        val vsPeak = last / peak
        if (vsPeak < MIN_VS_PEAK_8293) return null
        val minuteAgo = w.lastOrNull { it.atMs <= nowMs - 60_000L }?.priceSol ?: w.first().priceSol
        val lastMinute = (last / minuteAgo - 1.0) * 100.0
        if (lastMinute < MIN_LAST_MINUTE_PCT_8293) return null
        return Run8293(w.size, buys, recentBuys, buySol, bpSol, bpTx, rise, vsPeak, lastMinute, last, nowMs)
    }

    /** Pure: the tape price nearest [targetMs] within the grade tolerance, or null (no print there: ungraded). */
    fun priceNear8293(prints: List<TradeTape8100.Print8128>, targetMs: Long): Double? =
        prints.filter { kotlin.math.abs(it.atMs - targetMs) <= GRADE_TOLERANCE_MS_8293 && it.priceSol.isFinite() && it.priceSol > 0.0 }
            .minByOrNull { kotlin.math.abs(it.atMs - targetMs) }?.priceSol

    /**
     * V5.0.8733 — 8732: record30m read n86/+76.8 % while 11 of ~17 detections matured that session were never graded — the
     * trade feed drops a coin after ~6 minutes and a coin that died leaves no print at +30 min, so the record was the
     * survivors. The 5-minute grade reads while the coin is still on the feed: the print nearest +5 min, else the last
     * print after detection (the coin went quiet; that is its last traded price), else silent (counted, never graded).
     * Pure: the grade price of a detection at [atMs], or null when no print followed it.
     */
    fun price5m8733(prints: List<TradeTape8100.Print8128>, atMs: Long): Double? =
        priceNear8293(prints, atMs + GRADE_5M_MS_8733)
            ?: prints.filter { it.atMs > atMs && it.atMs <= atMs + GRADE_5M_MS_8733 + GRADE_TOLERANCE_MS_8293 &&
                it.priceSol.isFinite() && it.priceSol > 0.0 }.maxByOrNull { it.atMs }?.priceSol

    private const val GRADE_5M_MS_8733 = 5L * 60_000L

    /** Pure: a detection record that has proven it loses (n >= 20, mean + SE < 0). */
    fun standsDown8293(st: CortexLedger7885.Stat): Boolean {
        if (st.n < 20.0) return false
        val se = kotlin.math.sqrt(st.variance() / st.n)
        return st.mean() + se < 0.0
    }

    /** Pure: the lanes a detection is handed to — MOONSHOT and SHITCOIN, plus PROJECT_SNIPER inside its launch window. */
    fun lanesFor8293(launchAgeMs: Long?): List<String> {
        val sniperWindowMs = com.lifecyclebot.v3.scoring.ProjectSniperAI.SNIPER_WINDOW_SECS_8169 * 1_000L - SNIPER_HEADROOM_MS_8293
        return if (launchAgeMs != null && launchAgeMs in 0L..sniperWindowMs) listOf("MOONSHOT", "SHITCOIN", "PROJECT_SNIPER")
        else listOf("MOONSHOT", "SHITCOIN")
    }

    // ── state ──

    private val detectedAt = ConcurrentHashMap<String, Long>()
    private val evidence = ConcurrentHashMap<String, Run8293>()
    private val signals = ConcurrentHashMap<String, Run8293>()
    private val signals5m8733 = ConcurrentHashMap<String, Run8293>()
    private val record5m8733 = CortexLedger7885.realisedStat8187()
    private val silent5m8733 = AtomicLong(0)
    private val quiet5m8733 = AtomicLong(0)
    private val record = CortexLedger7885.realisedStat8187()
    /** V5.0.8729 — the breakout detector's own 30-minute record (never mixed with the run record). */
    private val breakRecord = CortexLedger7885.realisedStat8187()
    private val breaks8729 = AtomicLong(0)
    private fun recordOf8729(kind: String): CortexLedger7885.Stat = if (kind == KIND_BREAK_8729) breakRecord else record
    private val scans = AtomicLong(0); private val detections = AtomicLong(0); private val handed = AtomicLong(0)
    private val requeued = AtomicLong(0); private val refusedSafety = AtomicLong(0); private val shadow = AtomicLong(0)
    private val ungraded = AtomicLong(0)
    @Volatile private var lastRecent = ""

    /** A detection of [mint] within the evidence window while the record stands (RunnerGrab7967.formingNative8260). */
    fun fresh8293(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val e = evidence[mint] ?: return false
        if (nowMs - e.atMs !in 0L..EVIDENCE_TTL_MS_8293) return false
        ensureRecord8293()
        val rec = recordOf8729(e.kind)
        return !synchronized(rec) { standsDown8293(rec) }
    }

    /**
     * V5.0.8520 — a fresh, unusually strong EXECUTED-PRINT momentum witness, not a price-feed estimate.
     * A phase/reclaim wait may consume this witness; safety, sellability and sized net economics remain binding.
     * Deliberately do not treat a five-minute-old detection as a live fast-track.
     */
    internal fun highVelocity8520(r: Run8293): Boolean =
        r.prints >= 15 && r.recentBuys >= 5 && r.buySol >= 2.0 &&
            r.bpSolPct >= 75.0 && r.bpTxPct >= 65.0 &&
            r.lastMinutePct in 20.0..40.0 && r.vsPeak >= 0.95 && r.risePct <= 65.0

    fun freshHighVelocity8520(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val e = evidence[mint] ?: return false
        if (nowMs - e.atMs !in 0L..FRESH_PRINT_MS_8293) return false
        return highVelocity8520(e) && fresh8293(mint, nowMs)
    }

    /**
     * V5.0.8520 EV filter — the 30-minute executed-tape cohort is a GROSS
     * forward outcome, not realised SOL. Its conservative mean minus one SE
     * needs to clear actual sized costs and a substantial additional margin.
     * Cold or ungraded books give no fast-track authority.
     */
    fun conservativeForwardGross8520(): Double {
        ensureRecord8293()
        return synchronized(record) {
            if (record.n < 20.0) Double.NaN else {
                val se = kotlin.math.sqrt(record.variance() / record.n)
                val lower = record.mean() - se
                if (lower.isFinite()) lower else Double.NaN
            }
        }
    }

    /** Pure: require material positive forward edge after a modelled round trip, never a tiny gross uptick. */
    internal fun conservativeNetFastTrack8520(lowerGrossPct: Double, roundTripCostPct: Double): Boolean =
        lowerGrossPct.isFinite() && roundTripCostPct.isFinite() && roundTripCostPct > 0.0 &&
            lowerGrossPct - roundTripCostPct >= maxOf(10.0, 2.0 * roundTripCostPct)

    /** The latest evidence line for [mint] inside the evidence window, or null. */
    fun evidence8293(mint: String, nowMs: Long = System.currentTimeMillis()): String? =
        evidence[mint]?.takeIf { nowMs - it.atMs in 0L..EVIDENCE_TTL_MS_8293 }?.evidence()

    /** One pass over the live tape (ResidentHunterWorker7807, every 10 s). Returns the coins handed off. */
    fun scan8293(nowMs: Long = System.currentTimeMillis()): Int {
        scans.incrementAndGet()
        ensureRecord8293()
        gradeDue8293(nowMs)
        val mints = try { TradeTape8100.activeMints8293(nowMs, FRESH_PRINT_MS_8293) } catch (_: Throwable) { emptyList() }
        var n = 0
        for (mint in mints) {
            val prior = detectedAt[mint]
            if (prior != null && nowMs - prior < COOLDOWN_MS_8293) continue
            // V5.0.8729 — a base breaking out is read first: it is the earlier entry on the same coin.
            val prints = try { TradeTape8100.prints8128(mint, nowMs, BREAK_WINDOW_MS_8729) } catch (_: Throwable) { emptyList() }
            val run = (try { detectBreak8729(prints, nowMs) } catch (_: Throwable) { null })?.also { breaks8729.incrementAndGet() }
                ?: (try { detect8293(prints, nowMs) } catch (_: Throwable) { null }) ?: continue
            if (handOff8293(mint, run, nowMs)) n++
        }
        if (detectedAt.size > MAX_TRACKED_8293) detectedAt.entries.removeIf { nowMs - it.value > COOLDOWN_MS_8293 }
        if (evidence.size > MAX_TRACKED_8293) evidence.entries.removeIf { nowMs - it.value.atMs > EVIDENCE_TTL_MS_8293 }
        return n
    }

    private fun handOff8293(mint: String, run: Run8293, nowMs: Long): Boolean {
        val ts = try { BotService.status.tokens[mint] } catch (_: Throwable) { null }
        if (ts != null && ts.position.isOpen) return false
        detectedAt[mint] = nowMs
        detections.incrementAndGet()
        if (signals.size < MAX_TRACKED_8293) signals.putIfAbsent(mint, run)
        if (signals5m8733.size < MAX_TRACKED_8293) signals5m8733.putIfAbsent(mint, run)
        try { PipelineHealthCollector.labelInc("RUN_TAPE_DETECTED_8293") } catch (_: Throwable) {}
        if (ts != null && (ts.safety.tier == SafetyTier.HARD_BLOCK ||
                (try { MayhemMode7943.liveRefusal(ts, nowMs) } catch (_: Throwable) { null }) != null)) {
            refusedSafety.incrementAndGet()
            try { PipelineHealthCollector.labelInc("RUN_TAPE_SAFETY_REFUSED_8293") } catch (_: Throwable) {}
            return false
        }
        val rec8729 = recordOf8729(run.kind)
        if (synchronized(rec8729) { standsDown8293(rec8729) }) {
            shadow.incrementAndGet()
            try { PipelineHealthCollector.labelInc("RUN_TAPE_STOOD_DOWN_8293") } catch (_: Throwable) {}
            return false
        }
        evidence[mint] = run
        val symbol = ts?.symbol?.takeIf { it.isNotBlank() } ?: mint.take(6)
        val mcap = ts?.lastMcap?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val launchAge = try { com.lifecyclebot.network.PumpCurveKeys7269.createdAtMs7280(mint)?.let { nowMs - it } } catch (_: Throwable) { null }
        val lanes = lanesFor8293(launchAge)
        for (lane in lanes) {
            try { com.lifecyclebot.engine.market.LaneHunter7297.claimRun8293(lane, mint, mcap, nowMs) } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.publishHunt(lane, mint, symbol, "RUN_TAPE_8293") } catch (_: Throwable) {}
        }
        if (requestWatch8293(mint, "RUN_TAPE_8293", lanes.toSet(), symbol, mcap, ts?.lastLiquidityUsd ?: 0.0, nowMs)) requeued.incrementAndGet()
        handed.incrementAndGet()
        synchronized(this) { lastRecent = "${symbol.take(10)}:${if (run.kind == KIND_BREAK_8729) "break" else ""}+${run.risePct.toInt()}%/bp${run.bpSolPct.toInt()}"
            .let { r -> (listOf(r) + lastRecent.split(" · ").filter { it.isNotBlank() }).take(5).joinToString(" · ") } }
        try {
            PipelineHealthCollector.labelInc("RUN_TAPE_HANDED_8293")
            ForensicLogger.lifecycle("RUN_TAPE_HANDED_8293", "mint=${mint.take(10)} sym=$symbol lanes=${lanes.joinToString(",")} ${run.evidence()}")
        } catch (_: Throwable) {}
        return true
    }

    /**
     * Put [mint] back through the ordinary intake (TokenMergeQueue -> watchlist -> safety -> lanes) when it is not
     * watched, under the hunters' escalating requeue cooldown. Also RunnerPlay8018: a confirmed run whose coin had left
     * the watchlist. Never an entry: the intake and every gate behind it still run. Returns true when it was requeued.
     */
    fun requestWatch8293(
        mint: String, why: String, lanes: Set<String> = setOf("MOONSHOT", "SHITCOIN"), symbol: String = "",
        mcapUsd: Double = 0.0, liquidityUsd: Double = 0.0, nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (mint.isBlank()) return false
        val watching = try { GlobalTradeRegistry.isWatching(mint) } catch (_: Throwable) { true }
        if (watching) return false
        if (!com.lifecyclebot.engine.market.HuntDelivery8293.requeueDue8293(mint, nowMs)) return false
        com.lifecyclebot.engine.market.HuntDelivery8293.noteRequeued8293(mint, nowMs)
        return try {
            TokenMergeQueue.enqueue(
                mint = mint,
                symbol = symbol.ifBlank { mint.take(6) },
                scanner = "MARKET_HUNT_MOONSHOT",
                marketCapUsd = if (mcapUsd.isFinite() && mcapUsd > 0.0) mcapUsd else 0.0,
                liquidityUsd = if (liquidityUsd.isFinite() && liquidityUsd > 0.0) liquidityUsd else 0.0,
                laneAffinity = lanes,
            )
            PipelineHealthCollector.labelInc("RUN_TAPE_REQUEUED_8293_${why.take(24)}")
            true
        } catch (_: Throwable) { false }
    }

    /** Grade every detection whose 30-minute horizon has passed, on the same tape; no print there = ungraded. */
    private fun gradeDue8293(nowMs: Long) {
        for ((mint, sig) in signals5m8733.entries.toList()) {
            if (nowMs < sig.atMs + GRADE_5M_MS_8733 + GRADE_TOLERANCE_MS_8293) continue
            signals5m8733.remove(mint, sig)
            val prints = try { TradeTape8100.prints8128(mint, nowMs, nowMs - sig.atMs + 1_000L) } catch (_: Throwable) { emptyList() }
            val px = price5m8733(prints, sig.atMs)
            if (px == null || !(sig.pxSol > 0.0)) { silent5m8733.incrementAndGet(); continue }
            if (priceNear8293(prints, sig.atMs + GRADE_5M_MS_8733) == null) quiet5m8733.incrementAndGet()
            val g = ((px / sig.pxSol - 1.0) * 100.0).coerceIn(-100.0, 1_000.0)
            synchronized(record5m8733) { record5m8733.add(g, g >= 100.0) }
        }
        for ((mint, sig) in signals.entries.toList()) {
            val target = sig.atMs + GRADE_AFTER_MS_8293
            if (nowMs < target + GRADE_TOLERANCE_MS_8293) continue
            signals.remove(mint, sig)
            val later = try { priceNear8293(TradeTape8100.prints8128(mint, nowMs, nowMs - target + GRADE_TOLERANCE_MS_8293), target) } catch (_: Throwable) { null }
            if (later == null || !(sig.pxSol > 0.0)) { ungraded.incrementAndGet(); continue }
            val r = later / sig.pxSol
            if (r < 0.01 || r > 200.0) { ungraded.incrementAndGet(); continue }
            val g = ((r - 1.0) * 100.0).coerceIn(-100.0, 1_000.0)
            val rec = recordOf8729(sig.kind)
            synchronized(rec) { rec.add(g, g >= 100.0) }
            saveRecord8293()
        }
    }

    // ── persistence (the record survives installs, like RunnerGrab7967's) ──

    @Volatile private var loaded8293 = false

    private fun file8293(): java.io.File? =
        com.lifecyclebot.AATEApp.appContextOrNull()?.let { java.io.File(it.filesDir, "run_tape_8293.txt") }

    private fun breakFile8729(): java.io.File? =
        com.lifecyclebot.AATEApp.appContextOrNull()?.let { java.io.File(it.filesDir, "run_tape_break_8729.txt") }

    private fun ensureRecord8293() {
        if (loaded8293) return
        loaded8293 = true
        try { file8293()?.takeIf { it.exists() }?.readText()?.trim()?.let { t -> synchronized(record) { record.decode(t) } } } catch (_: Throwable) {}
        try { breakFile8729()?.takeIf { it.exists() }?.readText()?.trim()?.let { t -> synchronized(breakRecord) { breakRecord.decode(t) } } } catch (_: Throwable) {}
    }

    private fun saveRecord8293() {
        try { file8293()?.writeTextAtomic8086(synchronized(record) { record.encode() }) } catch (_: Throwable) {}
        try { breakFile8729()?.writeTextAtomic8086(synchronized(breakRecord) { breakRecord.encode() }) } catch (_: Throwable) {}
    }

    fun statusLine8293(): String {
        val st = synchronized(record) { if (record.n >= 1.0) "n${record.n.toInt()}/${"%+.1f".format(record.mean())}%" else "-" }
        val down = synchronized(record) { standsDown8293(record) }
        return "runTape8293: scans=${scans.get()} detected=${detections.get()} handed=${handed.get()} requeued=${requeued.get()} " +
            "safetyRefused=${refusedSafety.get()} shadow=${shadow.get()} record30m=$st${if (down) " STOOD_DOWN" else ""} " +
            "pendingGrades=${signals.size} ungraded=${ungraded.get()} recent=[${lastRecent.ifBlank { "-" }}] " +
            "record5m8733=${synchronized(record5m8733) { if (record5m8733.n >= 1.0) "n${record5m8733.n.toInt()}/${"%+.1f".format(record5m8733.mean())}%" else "-" }}" +
            "(quietLastPrint=${quiet5m8733.get()} silent=${silent5m8733.get()} gross,noCost) " +
            "breaks8729=${breaks8729.get()} breakRecord30m=${synchronized(breakRecord) { if (breakRecord.n >= 1.0) "n${breakRecord.n.toInt()}/${"%+.1f".format(breakRecord.mean())}%" else "-" }}" +
            (if (synchronized(breakRecord) { standsDown8293(breakRecord) }) " BREAK_STOOD_DOWN" else "")
    }

    internal fun resetForTest8293() {
        detectedAt.clear(); evidence.clear(); signals.clear(); loaded8293 = true
        signals5m8733.clear(); synchronized(record5m8733) { record5m8733.scale(0.0) }; silent5m8733.set(0); quiet5m8733.set(0)
        synchronized(record) { record.scale(0.0) }
        synchronized(breakRecord) { breakRecord.scale(0.0) }
    }

    /** Test seam: a detection as [scan8293] records it (evidence only; no hand-off side effects). */
    internal fun noteEvidenceForTest8293(mint: String, run: Run8293) { evidence[mint] = run }
}
