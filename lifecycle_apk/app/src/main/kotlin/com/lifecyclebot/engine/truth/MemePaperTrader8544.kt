package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8544 §MEME_PAPER_BOOK_BESIDE_LIVE — owner: "build the separate paper book beside live, properly".
 *
 * In LIVE the meme executor's own paperBuy is blocked by design (ExecutionRouteGuard, V5.0.3725): one TokenState carries one
 * position, and a paper row in the live journal contaminates live accounting. So no LIVE session ever paper-traded a meme
 * lane. This is a second, fully separate book that trades the meme lanes on paper WHILE the runtime is LIVE:
 *
 *  ENTRY  — CandidateAdmission8136.assess8136 hands every LIVE meme-lane candidate here. The same candidate is decided the
 *           way PAPER mode decides it (live = false, record doors advisory: the 8540 paper-explore rules; safety, structure,
 *           stale price and critical facts still refuse). An ADMIT opens a paper position at the coin's OBSERVED mark
 *           (ShadowMark8360: tick / labeler / curve / tape / pool vaults, at most [ENTRY_MARK_MAX_AGE_MS_8544] old) that at
 *           least one independent read corroborates (PaperFillTruth8539). Nothing is opened on an unknown price or cost.
 *  SIZE   — [STAKE_SOL_8544] per trade from the book's own paper balance (starts at [START_BALANCE_SOL_8544]); at most
 *           [MAX_OPEN_8544] open, [MAX_OPEN_PER_LANE_8544] per lane, one per mint, [REOPEN_GAP_MS_8544] between entries.
 *  EXITS  — the live levers of that lane: the stop live holds (ShadowAlign8366.liveStopLevel8366 — the lane stop widened
 *           past the round trip, the cost-armed break-even, a runner's green floor), the runner ladder on born-runner lanes
 *           (RunnerGrab7967.TIERS_7967) else the scalp ladder (SpikeCapture7943.TIERS), and the lane clock (10 min on a
 *           scalp-exit lane, CortexLabelHygiene8144.executableHorizonMs8392 otherwise). A triggered exit fills at the first
 *           observed mark after the measured sell latency (ShadowAlign8366.sellLatencyMs8366), like a live sell. A coin with
 *           no mark for [LOST_MARK_MS_8544] closes at its last mark flagged LOST_MARK.
 *  MONEY  — net = banked slices + remainder at the exit mark, less the round-trip cost the admission priced at entry.
 *  BOOKS  — its own ledger (LearningPersistence key [KEY_8544]): open positions survive a restart, closes are kept with their
 *           reason. It never touches a TokenState, the live / paper journals, the canonical ledger, the wallet, a live slot or
 *           any live authority; its results are paper, never reported as realised money.
 */
object MemePaperTrader8544 {
    const val STAKE_SOL_8544 = 0.1
    const val START_BALANCE_SOL_8544 = 10.0
    const val MAX_OPEN_8544 = 40
    /** Family paper entries used to starve behind 40 ordinary paper slots (8632: BOOK_FULL=2812).
     * Keep the existing ordinary book budget and give independent event-family research a small bounded allocation. */
    const val MAX_FAMILY_OPEN_8644 = 8
    const val MAX_OPEN_PER_LANE_8544 = 8
    const val REOPEN_GAP_MS_8544 = 15L * 60_000L
    const val ENTRY_MARK_MAX_AGE_MS_8544 = 20_000L
    private const val LOST_MARK_MS_8544 = 10L * 60_000L
    private const val SCALP_CLOCK_MS_8544 = 10L * 60_000L
    private const val KEY_8544 = "MEME_PAPER_TRADER_8544"
    private const val RECENT_MAX_8544 = 60

    private class Pos8544(
        val id: String, val mint: String, val symbol: String, val lane: String, val setup: String,
        val entryPx: Double, val entryAtMs: Long, val costPct: Double, val sizeSol: Double, val runner: Boolean, val clockMs: Long,
        /** V5.0.8546 — the lane whose exit levers this position rides (a strategy family books under FAM_<family>). */
        val exitLane: String = lane,
    ) {
        var held = 1.0; var banked = 0.0; var peak = 0.0; var tiers = 0
        var lastPx = entryPx; var lastAtMs = entryAtMs
        var exitWhy: String? = null; var exitAtMs = 0L; var exitFraction = 1.0; var exitTier = 0
        /** V5.0.8683 — the observed-mark time of the first read at or below the stop level (0 = none pending). */
        var stopSeenAtMs8683 = 0L
        /** V5.0.8756 — the pending stop's level and trigger gross (%), and whether a tape print (not the sweep) triggered it. */
        var slipLevel8756 = Double.NaN; var slipTrig8756 = Double.NaN; var slipByPrint8756 = false
    }

    /**
     * Pure (V5.0.8683): may a paper stop fire? Live no longer sells on one bad mark (StopMarkConfirm8418: a venue read or an
     * executable quote must agree), but this book did: a lone wick print at or below the level triggered the stop, which then
     * filled at the first mark after the live sell latency. 8680: paper STOP closes averaged -23 % to -36 % on 15-25 % stops,
     * shadow 24W/156L, live beat shadow by +6.4 pts on the same coins. The stop fires when a SECOND observed mark (a later
     * observation than the first breach) still reads at or below the level, or at once at [PAPER_STOP_BYPASS_PCT_8683].
     */
    fun paperStopConfirmed8683(firstBreachObsMs: Long, obsMs: Long, grossPct: Double): Boolean =
        grossPct <= PAPER_STOP_BYPASS_PCT_8683 || (firstBreachObsMs > 0L && obsMs > firstBreachObsMs)

    private const val PAPER_STOP_BYPASS_PCT_8683 = -60.0
    private data class Closed8544(val lane: String, val mint: String, val symbol: String, val setup: String, val entryAtMs: Long,
                          val exitAtMs: Long, val netPct: Double, val pnlSol: Double, val why: String)

    private val open = ConcurrentHashMap<String, Pos8544>()          // mint -> position
    private val lastEntryAt = ConcurrentHashMap<String, Long>()
    private val recent = ArrayDeque<Closed8544>()
    private val skips = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    @Volatile private var balanceSol = START_BALANCE_SOL_8544
    @Volatile private var realisedSol = 0.0
    @Volatile private var restored = false
    @Volatile private var dirty = false
    @Volatile private var lastSaveMs = 0L

    private fun skip(why: String) {
        skips.computeIfAbsent(why) { java.util.concurrent.atomic.AtomicLong(0) }.incrementAndGet()
    }

    /** Pure: is [lane] a Solana meme lane this book trades? (crypto desks keep their own paper books) */
    fun memeLane8544(lane: String): Boolean {
        val l = lane.trim().uppercase()
        return l.isNotBlank() && !l.startsWith("CRYPTO") && !l.startsWith("CHASEMISS") && !l.contains("PERPS")
    }

    /** Pure: may a new paper position open now? null = yes, else why not. */
    fun roomFor8544(openTotal: Int, openInLane: Int, mintOpen: Boolean, sinceLastEntryMs: Long, balanceSol: Double): String? = when {
        mintOpen -> "MINT_OPEN"
        openTotal >= MAX_OPEN_8544 -> "BOOK_FULL"
        openInLane >= MAX_OPEN_PER_LANE_8544 -> "LANE_FULL"
        sinceLastEntryMs in 0L until REOPEN_GAP_MS_8544 -> "REOPEN_GAP"
        balanceSol < STAKE_SOL_8544 -> "NO_PAPER_BALANCE"
        else -> null
    }

    /** Separate bounded family capacity, which never consumes the 40 ordinary paper strategy slots. */
    fun roomForFamily8644(openTotal: Int, openFamilies: Int, openInFamily: Int, mintOpen: Boolean,
                           sinceLastEntryMs: Long, balanceSol: Double): String? = when {
        mintOpen -> "MINT_OPEN"
        openTotal >= MAX_OPEN_8544 + MAX_FAMILY_OPEN_8644 || openFamilies >= MAX_FAMILY_OPEN_8644 -> "FAMILY_BOOK_FULL"
        openInFamily >= MAX_OPEN_PER_LANE_8544 -> "LANE_FULL"
        sinceLastEntryMs in 0L until REOPEN_GAP_MS_8544 -> "REOPEN_GAP"
        balanceSol < STAKE_SOL_8544 -> "NO_PAPER_BALANCE"
        else -> null
    }

    private fun familyOpen8644() = open.values.count { it.lane.startsWith(FAMILY_PREFIX_8546) }
    private fun ordinaryOpen8644() = open.size - familyOpen8644()

    /**
     * CandidateAdmission8136.assess8136, LIVE runtime only: decide [candidate] the PAPER way and, on ADMIT, open a paper position.
     */
    fun consider8544(ts: TokenState, candidate: CandidateAdmission8136.Candidate8136, nowMs: Long) {
        if (!candidate.live || !memeLane8544(candidate.lane)) return
        if (!com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(ts.mint)) return
        restore8544()
        val lane = candidate.lane.trim().uppercase()
        val why = roomFor8544(ordinaryOpen8644(), open.values.count { it.lane == lane }, open.containsKey(ts.mint),
            nowMs - (lastEntryAt[ts.mint] ?: Long.MIN_VALUE / 2), balanceSol)
        if (why != null) { if (why != "MINT_OPEN") skip(why); return }
        val paper = CandidateAdmission8136.decide8136(candidate.copy(live = false, liveEdge8114 = null)) { false }
        if (paper.terminal != CandidateAdmission8136.Terminal8136.ADMIT) { skip("PAPER_REFUSED"); return }
        if (!candidate.costPct.isFinite() || candidate.costPct < 0.0) { skip("COST_UNKNOWN"); return }
        val mark = ShadowMark8360.observedTimed8372(ts, ts.mint, nowMs)
        if (mark == null || nowMs - mark.observedAtMs !in 0L..ENTRY_MARK_MAX_AGE_MS_8544 || !(mark.priceUsd > 0.0)) { skip("NO_FRESH_MARK"); return }
        if (PaperFillTruth8539.refusal(ts.mint, mark.priceUsd, nowMs) != null) { skip("FILL_UNCORROBORATED"); return }
        val runner = try { com.lifecyclebot.engine.RunnerGrab7967.bornRunnerLadder8538(lane) } catch (_: Throwable) { false }
        val scalp = try { ExploreShadow8347.liveExitStyle8351(lane, EvidenceHold8336.startMs8336) == ExploreShadow8347.Exit8347.SCALP } catch (_: Throwable) { false }
        val clock = if (scalp && !runner) SCALP_CLOCK_MS_8544
            else com.lifecyclebot.engine.cortex.CortexLabelHygiene8144.executableHorizonMs8392(runner)
        val p = Pos8544("$lane|${ts.mint}|$nowMs", ts.mint, ts.symbol, lane, paper.chosenSetup ?: candidate.selectedSetup ?: "",
            mark.priceUsd, mark.observedAtMs, candidate.costPct, STAKE_SOL_8544, runner, clock)
        if (!reserve(p, nowMs)) return
        try { PipelineHealthCollector.labelInc("MEME_PAPER_OPEN_8544_$lane") } catch (_: Throwable) {}
        try {   // V5.0.8618 — the tuning report's paper-vs-live and entry-state cuts
            val f8618 = com.lifecyclebot.engine.cortex.LanePlaybook7907.features(ts, nowMs)
            TuningReport8618.notePaperOpen8618(ts.mint, lane, TuningReport8618.liveVerdictFor8618(ts.mint, lane, nowMs), f8618.launchPhase, f8618.age, nowMs)
        } catch (_: Throwable) {}
        save8544(nowMs, force = true)
    }

    @Synchronized private fun reserve(p: Pos8544, nowMs: Long): Boolean {
        val since = nowMs - (lastEntryAt[p.mint] ?: Long.MIN_VALUE / 2)
        val inLane = open.values.count { it.lane == p.lane }
        val why = if (p.lane.startsWith(FAMILY_PREFIX_8546))
            roomForFamily8644(open.size, familyOpen8644(), inLane, open.containsKey(p.mint), since, balanceSol)
        else roomFor8544(ordinaryOpen8644(), inLane, open.containsKey(p.mint), since, balanceSol)
        if (why != null) return false
        open[p.mint] = p
        lastEntryAt[p.mint] = nowMs
        balanceSol -= p.sizeSol
        dirty = true
        return true
    }

    /** Pure: the gross % of [px] over [entry]. */
    fun grossPct8544(entry: Double, px: Double): Double = if (entry > 0.0 && px > 0.0) (px / entry - 1.0) * 100.0 else Double.NaN

    /** Pure: the next ladder rung reached at [gross] after [tiersTaken] rungs, as (tier index + 1, fraction of the remainder), or null. */
    fun rung8544(ladder: List<Pair<Double, Double>>, tiersTaken: Int, gross: Double): Pair<Int, Double>? {
        val reached = com.lifecyclebot.engine.SpikeCapture7943.tierReached(gross, ladder)
        if (tiersTaken < 0 || reached <= tiersTaken) return null
        val fraction = if (reached == tiersTaken + 1) ladder[tiersTaken].second.coerceIn(0.0, 1.0)
            else com.lifecyclebot.engine.SpikeCapture7943.crossedTierFraction8079(ladder, tiersTaken, reached)
        return reached to fraction
    }

    /** Pure: the position's net % — banked slices plus the remainder at [gross], less the round trip [costPct]. */
    fun netPct8544(banked: Double, held: Double, gross: Double, costPct: Double): Double =
        banked + held * gross - (if (costPct.isFinite()) costPct else 0.0)

    /**
     * Pure (V5.0.8559): is [px] within 20x of the position's last mark [lastPx] (the ForwardReturnLabeler7731 7738 basis rule)?
     * Nothing is capped: a bigger jump is held for confirmation ([acceptMark8559]), never discarded as a win.
     */
    fun markPlausible8559(lastPx: Double, px: Double): Boolean =
        !(lastPx > 0.0 && lastPx.isFinite()) || !ForwardReturnLabeler7731.basisSuspect7738(lastPx, px)

    /**
     * Pure (V5.0.8559; 8560 independent): a mark from a DIFFERENT source than the one that proposed the held jump agrees with
     * it (within 2x either way): the jump was real. 8559 let the next mark of the same feed confirm it, so a feed stuck on a
     * wrong pool confirmed itself (48 confirmations in 5 min).
     */
    fun jumpConfirmed8559(heldJumpPx: Double?, heldSource: String?, px: Double, source: String): Boolean =
        heldJumpPx != null && heldJumpPx > 0.0 && px > 0.0 && (px / heldJumpPx) in 0.5..2.0 &&
            !heldSource.isNullOrBlank() && source.isNotBlank() && heldSource != source

    private val heldJump8559 = ConcurrentHashMap<String, Pair<Double, String>>()
    private val jumpsHeld8559 = java.util.concurrent.atomic.AtomicLong(0)
    private val jumpsConfirmed8559 = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * V5.0.8559 — 8557's book closed EXPRESS at +5,139,752 % mean on single off-basis marks. A > 20x jump from the last mark is
     * held, not used, until an independent source agrees with it ([jumpConfirmed8559]); then it counts in full (a real runner
     * keeps every % of its win — nothing is capped). A feed priced off the wrong pool never meets another source and is dropped.
     * [source] names the feed: the observation kind plus, for a tick, the provider that wrote it.
     */
    fun acceptMark8559(key: String, lastPx: Double, px: Double, source: String): Boolean {
        if (markPlausible8559(lastPx, px)) {
            heldJump8559.remove(key)
            return true
        }
        val held = heldJump8559[key]
        if (jumpConfirmed8559(held?.first, held?.second, px, source)) { heldJump8559.remove(key); jumpsConfirmed8559.incrementAndGet(); return true }
        if (held == null || held.second == source) heldJump8559[key] = px to source
        if (heldJump8559.size > 4_096) heldJump8559.clear()
        jumpsHeld8559.incrementAndGet()
        return false
    }

    /** The feed behind an observation: its kind, and for a tick the provider that wrote the row's price. */
    fun markSource8560(obsSource: String, ts: TokenState?): String =
        if (obsSource.startsWith("TICK")) obsSource + ":" + (ts?.lastPriceSource ?: "") else obsSource

    /** Brief: off-basis jumps held / later confirmed as real moves. */
    fun jumpLine8559(): String = "markJumps8559{held=${jumpsHeld8559.get()} confirmed=${jumpsConfirmed8559.get()}}"

    /** BotService loop (beside ExploreShadow8347.tick8347): mark, exit and close every open paper position. */
    fun tick8544(tokens: Map<String, TokenState>, nowMs: Long = System.currentTimeMillis()) {
        restore8544()
        if (open.isEmpty()) { save8544(nowMs); return }
        // V5.0.8699 — a paper stop waits the latency LIVE's stops are measured at (the protective class), a take-profit the
        // normal class's. Every exit waited the normal class's never-measured 16 s prior, so a paper stop on a dumping coin
        // filled 16 s late: paper STOP -23 .. -37 % against live -5 .. -17 % on the same candidates (8689 tuning report).
        val stopLatency8699 = try { ShadowAlign8366.sellLatencyMs8366(true) } catch (_: Throwable) { 6_000L }
        val normalLatency8699 = try { ShadowAlign8366.sellLatencyMs8366(false) } catch (_: Throwable) { 16_000L }
        for (p in open.values.toList()) try {
            wantMark8623(p.mint, nowMs)
            val obs = ShadowMark8360.observedTimed8372(tokens[p.mint], p.mint, nowMs)
            step8756(p, obs, markSource8560(obs?.source.orEmpty(), tokens[p.mint]), nowMs, stopLatency8699, normalLatency8699, byPrint = false)
        } catch (_: Throwable) {}
        save8544(nowMs)
    }

    /**
     * V5.0.8756 §THE_STOP_RUNS_ON_THE_PRINT — the book's exits ran on the shadow-evidence sweep, which runs after two shadow
     * books on one worker: a dumping coin was looked at once per sweep, its stop then needed a second sweep's mark, then the
     * sell latency. 8755 paper STOP closes: FAM_FOLLOW_SIZE -60.8 % after 1.9 min, SHITCOIN -36.4 %, EXPRESS -30.2 % on
     * 15-25 % stops. Every witnessed print of an open coin (TradeTape8100.launchEvidence8750) now runs the same exit step at
     * once: the second-mark rule and the measured live sell latency are unchanged, so the fill is what a live stop gets, and
     * [slipLine8756] records level vs trigger vs fill per lane so the gap is measured, not guessed.
     */
    fun onPrint8756(mint: String, priceSol: Double, eventMs: Long) {
        val p = open[mint] ?: return
        val solUsd = ShadowMark8360.solUsd8756(eventMs)
        if (!(solUsd > 0.0) || !(priceSol > 0.0)) return
        val now = System.currentTimeMillis()
        val stopLatency = try { ShadowAlign8366.sellLatencyMs8366(true) } catch (_: Throwable) { 6_000L }
        val normalLatency = try { ShadowAlign8366.sellLatencyMs8366(false) } catch (_: Throwable) { 16_000L }
        try {
            step8756(p, ShadowMark8360.Observation8372(priceSol * solUsd, eventMs, ShadowMark8360.SRC_TAPE_8360), ShadowMark8360.SRC_TAPE_8360,
                now, stopLatency, normalLatency, byPrint = true)
        } catch (_: Throwable) {}
    }

    /** One exit step of [p] on the observation [seen] (the sweep's mark, or a tape print). */
    private fun step8756(p: Pos8544, seen: ShadowMark8360.Observation8372?, source: String, nowMs: Long,
                         stopLatency8699: Long, normalLatency8699: Long, byPrint: Boolean) { synchronized(p) {
        if (open[p.mint] !== p) return
        val obs = seen?.takeIf { it.observedAtMs >= p.lastAtMs && it.priceUsd > 0.0 }
            ?.takeIf { acceptMark8559("book:" + p.mint, p.lastPx, it.priceUsd, source) }
        if (obs != null) { p.lastPx = obs.priceUsd; p.lastAtMs = obs.observedAtMs }
        val g = grossPct8544(p.entryPx, p.lastPx)
        if (!g.isFinite()) return
        if (g > p.peak) p.peak = g
        val pending = p.exitWhy
        if (pending != null) {
            // a triggered exit fills at the first observed mark after the live sell latency
            val latency = if (stopExit8699(pending)) stopLatency8699 else normalLatency8699
            if (obs != null && obs.observedAtMs >= p.exitAtMs + latency) fill8544(p, g, nowMs)
            else if (nowMs - p.lastAtMs > LOST_MARK_MS_8544) { p.exitFraction = 1.0; p.exitWhy = "LOST_MARK"; fill8544(p, g, nowMs) }
            return
        }
        if (byPrint && obs == null) return
        if (nowMs - p.lastAtMs > LOST_MARK_MS_8544) { p.exitFraction = 1.0; p.exitWhy = "LOST_MARK"; fill8544(p, g, nowMs); return }
        val stopPct = try { FieldManual7715.stopPct8392(p.exitLane, p.costPct) } catch (_: Throwable) { 15.0 }
        // V5.0.8584 — the paper meme book runs the live doctrine: the 2x / 5x / 10x ladder and the doctrine trail.
        val doctrine8584 = com.lifecyclebot.engine.RunnerDoctrine8584.rides8584(p.exitLane)
        val level = if (doctrine8584) com.lifecyclebot.engine.RunnerDoctrine8584.paperStopLevel8584(stopPct, p.peak)
            else try { ShadowAlign8366.liveStopLevel8366(p.runner, stopPct, p.peak, p.costPct) } catch (_: Throwable) { -stopPct }
        val ladder = if (doctrine8584) com.lifecyclebot.engine.RunnerGrab7967.TIERS_7967 else if (p.runner) com.lifecyclebot.engine.RunnerGrab7967.TIERS_7967 else com.lifecyclebot.engine.SpikeCapture7943.TIERS
        val rung = rung8544(ladder, p.tiers, g)
        // V5.0.8683 — a breach must be seen on two observed marks (a wick is one print); a recovery clears it.
        val breached8683 = level.isFinite() && g <= level
        if (!breached8683) p.stopSeenAtMs8683 = 0L
        else if (p.stopSeenAtMs8683 == 0L) p.stopSeenAtMs8683 = p.lastAtMs
        when {
            breached8683 && paperStopConfirmed8683(p.stopSeenAtMs8683, p.lastAtMs, g) -> {
                p.slipLevel8756 = level; p.slipTrig8756 = g; p.slipByPrint8756 = byPrint
                trigger8544(p, if (p.peak > 0.0 && level > -stopPct) "TRAIL_STOP" else "STOP", 1.0, 0, nowMs)
            }
            breached8683 -> try { PipelineHealthCollector.labelInc("MEME_PAPER_STOP_AWAITS_SECOND_MARK_8683") } catch (_: Throwable) {}
            rung != null -> trigger8544(p, "TAKE_PROFIT_T${rung.first}", rung.second, rung.first, nowMs)
            nowMs - p.entryAtMs >= p.clockMs && doctrine8584 &&
                com.lifecyclebot.engine.RunnerDoctrine8584.holds8584(p.exitLane, "CLOCK_TIME", g, p.peak) -> Unit
            nowMs - p.entryAtMs >= p.clockMs -> trigger8544(p, "CLOCK", 1.0, 0, nowMs)
        }
    } }

    /**
     * V5.0.8623 §PAPER_ASKS_FOR_ITS_OWN_MARKS — the book never asked the data side to keep pricing its open coins: once a coin
     * left the watchlist nothing priced it and it closed LOST_MARK at its last mark. 8622 (14:12 dump): LOST_MARK was 216 of 263
     * paper closes, and they carried the whole book's profit (CORE LOST_MARK n11 +403.6 %, SHITCOIN n36 +57.1 %) while every
     * priced exit lost. Each open position now asks the keyless pair feed and the labeler's off-watch batch, like the shadow
     * book's tickets (ExploreShadow8347.want8360), at most once per [WANT_EVERY_MS_8623].
     */
    private fun wantMark8623(mint: String, nowMs: Long) {
        val last = wantedAt8623[mint]
        if (last != null && nowMs - last in 0L until WANT_EVERY_MS_8623) return
        wantedAt8623[mint] = nowMs
        if (wantedAt8623.size > 512) wantedAt8623.keys.retainAll(open.keys)
        val until = nowMs + LOST_MARK_MS_8544
        try { KeylessMarks8359.noteWanted8359(mint, until) } catch (_: Throwable) {}
        try { ForwardReturnLabeler7731.noteShadowWanted8442(mint, until) } catch (_: Throwable) {}
    }
    private const val WANT_EVERY_MS_8623 = 30_000L
    private val wantedAt8623 = ConcurrentHashMap<String, Long>()

    /** Pure (V5.0.8699): is a paper exit a stop (live sends it as a protective exit)? */
    fun stopExit8699(why: String?): Boolean = why == "STOP" || why == "TRAIL_STOP"

    private fun trigger8544(p: Pos8544, why: String, fraction: Double, tier: Int, nowMs: Long) {
        p.exitWhy = why; p.exitAtMs = nowMs; p.exitFraction = fraction; p.exitTier = tier
        dirty = true
    }

    private fun fill8544(p: Pos8544, g: Double, nowMs: Long) {
        val why = p.exitWhy ?: return
        if (stopExit8699(why) && p.slipTrig8756.isFinite()) StopSlip8756.notePaper8756(p.exitLane, p.slipLevel8756, p.slipTrig8756, g, p.slipByPrint8756)
        p.slipLevel8756 = Double.NaN; p.slipTrig8756 = Double.NaN
        val sold = p.held * p.exitFraction.coerceIn(0.0, 1.0)
        // V5.0.8623 — a lost mark is no exit price: the unsold remainder is valued flat (entry), never at a stale last mark.
        p.banked += sold * (if (why == LOST_MARK_8623) 0.0 else g); p.held -= sold
        if (p.exitTier > 0) p.tiers = maxOf(p.tiers, p.exitTier)
        p.exitWhy = null; p.exitAtMs = 0L; p.exitFraction = 1.0; p.exitTier = 0
        dirty = true
        if (p.held > 0.0001) { try { PipelineHealthCollector.labelInc("MEME_PAPER_PARTIAL_8544_${p.lane}") } catch (_: Throwable) {}; return }
        val net = netPct8544(p.banked, 0.0, g, p.costPct)
        val pnl = p.sizeSol * net / 100.0
        synchronized(this) {
            if (open.remove(p.mint, p)) {
                balanceSol += p.sizeSol + pnl
                realisedSol += pnl
                val c = Closed8544(p.lane, p.mint, p.symbol, p.setup, p.entryAtMs, nowMs, net, pnl, why)
                recent.addLast(c)
                while (recent.size > RECENT_MAX_8544) recent.removeFirst()
                noteLane8544(c)
                try { TuningReport8618.notePaperClose8618(c.mint, c.lane, c.netPct, c.pnlSol, c.why, c.exitAtMs - c.entryAtMs) } catch (_: Throwable) {}   // V5.0.8618
            }
        }
        try { PipelineHealthCollector.labelInc("MEME_PAPER_CLOSE_8544_${p.lane}_${why.substringBefore("_T")}") } catch (_: Throwable) {}
        save8544(nowMs, force = true)
    }

    // ── persistence ──

    private fun posJson(p: Pos8544) = JSONArray().put(p.id).put(p.mint).put(p.symbol).put(p.lane).put(p.setup).put(p.entryPx)
        .put(p.entryAtMs).put(p.costPct).put(p.sizeSol).put(p.runner).put(p.clockMs).put(p.held).put(p.banked).put(p.peak)
        .put(p.tiers).put(p.lastPx).put(p.lastAtMs).put(p.exitLane)
        .put(p.exitWhy.orEmpty()).put(p.exitAtMs).put(p.exitFraction).put(p.exitTier)

    @Synchronized private fun save8544(nowMs: Long, force: Boolean = false) {
        if (!dirty || (!force && nowMs - lastSaveMs < 10_000L) || !LearningPersistence.ready()) return
        val raw = synchronized(this) {
            dirty = false; lastSaveMs = nowMs
            JSONObject().put("balance", balanceSol).put("realised", realisedSol)
                .put("open", JSONArray().also { a -> open.values.forEach { a.put(posJson(it)) } })
                .put("closed", JSONArray().also { a -> recent.forEach { c ->
                    a.put(JSONArray().put(c.lane).put(c.mint).put(c.symbol).put(c.setup).put(c.entryAtMs).put(c.exitAtMs)
                        .put(c.netPct).put(c.pnlSol).put(c.why)) } })
                .put("laneStats", JSONObject().also { o -> laneStats.forEach { (k, v) -> o.put(k, JSONArray().also { a -> v.forEach { x -> a.put(x) } }) } })
                .toString()
        }
        try { LearningPersistence.save(KEY_8544, raw) } catch (_: Throwable) { dirty = true }
    }

    /** lane -> [n, wins, sumNet, sumPnlSol, sumNetSq, sumWinNet, sumLossNet] over every close since the book began. */
    private val laneStats = ConcurrentHashMap<String, DoubleArray>()

    private fun restore8544() {
        if (restored || !LearningPersistence.ready()) return
        val raw = try { LearningPersistence.load(KEY_8544) } catch (_: Throwable) { null }
        synchronized(this) {
            if (restored) return
            restored = true
            if (raw == null) return
            try {
                val j = JSONObject(raw)
                balanceSol = j.optDouble("balance", START_BALANCE_SOL_8544)
                realisedSol = j.optDouble("realised", 0.0)
                val o = j.optJSONArray("open")
                if (o != null) for (i in 0 until o.length()) {
                    val a = o.optJSONArray(i) ?: continue
                    val p = Pos8544(a.optString(0), a.optString(1), a.optString(2), a.optString(3), a.optString(4), a.optDouble(5),
                        a.optLong(6), a.optDouble(7), a.optDouble(8), a.optBoolean(9), a.optLong(10),
                        a.optString(17, "").ifBlank { a.optString(3) })
                    p.held = a.optDouble(11, 1.0); p.banked = a.optDouble(12, 0.0); p.peak = a.optDouble(13, 0.0)
                    p.tiers = a.optInt(14); p.lastPx = a.optDouble(15, p.entryPx); p.lastAtMs = a.optLong(16, p.entryAtMs)
                    val pending = a.optString(18, "")
                    val at = a.optLong(19, 0L)
                    val fraction = a.optDouble(20, 1.0)
                    if (pending.isNotBlank() && at >= p.entryAtMs && fraction > 0.0 && fraction <= 1.0) {
                        p.exitWhy = pending
                        p.exitAtMs = at
                        p.exitFraction = fraction
                        p.exitTier = a.optInt(21, 0)
                    }
                    if (p.mint.isNotBlank() && p.entryPx > 0.0) open[p.mint] = p
                }
                val c = j.optJSONArray("closed")
                if (c != null) for (i in 0 until c.length()) {
                    val a = c.optJSONArray(i) ?: continue
                    recent.addLast(Closed8544(a.optString(0), a.optString(1), a.optString(2), a.optString(3), a.optLong(4), a.optLong(5),
                        a.optDouble(6), a.optDouble(7), a.optString(8)))
                }
                val s = j.optJSONObject("laneStats")
                s?.keys()?.forEach { k -> s.optJSONArray(k)?.let { a -> laneStats[k] = DoubleArray(STAT_FIELDS_8546) { i -> a.optDouble(i, 0.0).takeIf { it.isFinite() } ?: 0.0 } } }
            } catch (_: Throwable) {}
        }
    }

    private fun noteLane8544(c: Closed8544) {
        // V5.0.8623 — an unpriced close proves nothing about the lane: it is counted on its own row, outside every lane record.
        if (c.why == LOST_MARK_8623) { addStat8572("$UNPRICED_PREFIX_8623${c.lane}", c); return }
        addStat8572(c.lane, c)
        addStat8572("$PRICED_PREFIX_8623${c.lane}", c)
        // V5.0.8572 — the lane's post-guard proof ledger: closes booked after the 8559 mark guard only (a pre-guard phantom,
        // e.g. the EXPRESS +503,695 SOL row, can never prove a lane). Families keep their own FAM_ rows.
        if (!c.lane.startsWith(FAMILY_PREFIX_8546)) addStat8572("$PROOF_PREFIX_8572${c.lane}", c)
    }

    private fun addStat8572(key: String, c: Closed8544) {
        val v = laneStats.getOrPut(key) { DoubleArray(STAT_FIELDS_8546) }
        synchronized(v) {
            v[0] += 1.0; if (c.netPct > 0.0) v[1] += 1.0; v[2] += c.netPct; v[3] += c.pnlSol
            v[4] += c.netPct * c.netPct; if (c.netPct > 0.0) v[5] += c.netPct else v[6] += -c.netPct
        }
    }

    const val PROOF_PREFIX_8572 = "P8572|"
    /**
     * V5.0.8623 — the lane proof counted on priced closes only. P8572 rows hold LOST_MARK closes booked at stale marks (8622:
     * CORE n17 +259.5 % PF 51 was 11 lost marks), so they can no longer prove a lane earns; they can still bench one (below).
     */
    const val PRICED_PREFIX_8623 = "P8623|"
    private const val UNPRICED_PREFIX_8623 = "U8623|"
    private const val LOST_MARK_8623 = "LOST_MARK"

    /** The (n, mean, se, pf) of [lane]'s priced paper record, or null. */
    private fun pricedProof8623(lane: String): DoubleArray? =
        laneStats["$PRICED_PREFIX_8623${lane.trim().uppercase()}"]?.let { v -> synchronized(v) { proofOf8546(v) } }

    /**
     * V5.0.8608 §LIVE_TRADES_WHAT_PAPER_PROVES — has [lane]'s post-guard paper record
     * PROVEN it loses (n >= 30, mean + se < 0, PF < 1)? 01:42 dump (8607): paper EXPRESS n44 -3.5 %+-2.6 PF 0.54 and MOONSHOT
     * n40 -4.1 %+-2.7 PF 0.48 while both bled live too and took the 0.12 SOL wallet's route-minimum slots from SHITCOIN
     * (paper n33 +29.7 % PF 3.20, live FDG_SIZE_NOT_EXECUTABLE_7835 25). Such a lane keeps trading in paper and earns its way
     * back the moment its record stops proving a loss.
     */
    fun laneProvenLosing8608(lane: String): Boolean {
        restore8544()
        val p = pricedProof8623(lane) ?: return false
        return provenLosing8608(p[0].toInt(), p[1], p[2], p[3])
    }

    /** Pure (V5.0.8608): n >= 30 closes whose mean is below zero by more than its standard error, profit factor < 1. */
    fun provenLosing8608(n: Int, mean: Double, se: Double, pf: Double): Boolean =
        n >= 30 && mean.isFinite() && se.isFinite() && mean + se < 0.0 && pf.isFinite() && pf < 1.0

    private const val STAT_FIELDS_8546 = 7
    const val FAMILY_PREFIX_8546 = "FAM_"

    /** Pure (V5.0.8546): (n, mean, se, profit factor) of a [v] stats row. */
    fun proofOf8546(v: DoubleArray): DoubleArray {
        val n = v.getOrElse(0) { 0.0 }
        if (n < 1.0) return doubleArrayOf(0.0, Double.NaN, Double.NaN, Double.NaN)
        val mean = v[2] / n
        val variance = (v.getOrElse(4) { 0.0 } / n - mean * mean).coerceAtLeast(0.0)
        val se = if (n > 1.0) kotlin.math.sqrt(variance / n) else Double.NaN
        val loss = v.getOrElse(6) { 0.0 }
        val pf = if (loss > 0.0) v.getOrElse(5) { 0.0 } / loss else if (v.getOrElse(5) { 0.0 } > 0.0) Double.POSITIVE_INFINITY else Double.NaN
        return doubleArrayOf(n, mean, se, pf)
    }

    /** V5.0.8546 — has [family]'s paper record earned LIVE money (MemeStrategies8546.provenRecord8546)? */
    fun familyProven8546(family: String): Boolean {
        restore8544()
        val p = pricedProof8623("$FAMILY_PREFIX_8546$family") ?: return false
        return MemeStrategies8546.provenRecord8546(p[0].toInt(), p[1], p[2], p[3])
    }

    /**
     * V5.0.8546 — MemeStrategies8546: a family signalled on [ts]; open a paper position booked under FAM_<family>, riding the
     * signal's exit lane. Same fill rules as a lane entry: fresh corroborated observed mark, priced round trip, room in the book.
     */
    fun openFamily8546(ts: TokenState, signal: MemeStrategies8546.Signal8546, nowMs: Long) {
        restore8544()
        val book = "$FAMILY_PREFIX_8546${signal.family.name}"
        val why = roomForFamily8644(open.size, familyOpen8644(), open.values.count { it.lane == book }, open.containsKey(ts.mint),
            nowMs - (lastEntryAt[ts.mint] ?: Long.MIN_VALUE / 2), balanceSol)
        if (why != null) { if (why != "MINT_OPEN") skip("FAMILY_$why"); return }
        val cost = try { NetEdge8030.entryCost8209(ts, signal.exitLane, STAKE_SOL_8544).costPct } catch (_: Throwable) { Double.NaN }
        if (!cost.isFinite() || cost < 0.0) { skip("FAMILY_COST_UNKNOWN"); return }
        val mark = ShadowMark8360.observedTimed8372(ts, ts.mint, nowMs)
        if (mark == null || nowMs - mark.observedAtMs !in 0L..ENTRY_MARK_MAX_AGE_MS_8544 || !(mark.priceUsd > 0.0)) { skip("FAMILY_NO_FRESH_MARK"); return }
        if (PaperFillTruth8539.refusal(ts.mint, mark.priceUsd, nowMs) != null) { skip("FAMILY_FILL_UNCORROBORATED"); return }
        val runner = try { com.lifecyclebot.engine.RunnerGrab7967.bornRunnerLadder8538(signal.exitLane) } catch (_: Throwable) { false }
        val clock = if (runner) com.lifecyclebot.engine.cortex.CortexLabelHygiene8144.executableHorizonMs8392(true) else SCALP_CLOCK_MS_8544 * 3
        val p = Pos8544("$book|${ts.mint}|$nowMs", ts.mint, ts.symbol, book, signal.why.take(60), mark.priceUsd, mark.observedAtMs,
            cost, STAKE_SOL_8544, runner, clock, signal.exitLane)
        if (!reserve(p, nowMs)) return
        try { PipelineHealthCollector.labelInc("MEME_FAMILY_PAPER_OPEN_8546_${signal.family}") } catch (_: Throwable) {}
        save8544(nowMs, force = true)
    }

    /** Brief / report: balance, open book, per-lane closed results, last closes, and why candidates were not opened. */
    fun statusLine8544(): String {
        restore8544()
        val lanes = laneStats.entries.sortedByDescending { it.value[0] }.joinToString(" ") { (k, v) ->
            val n = v[0].toInt()
            val p = proofOf8546(v)
            if (n == 0) "$k[n0]" else "$k[n$n wr${(v[1] * 100 / v[0]).toInt()}% ${"%+.1f".format(p[1])}%${if (p[2].isFinite()) "±${"%.1f".format(p[2])}" else ""} " +
                "pf${if (p[3].isFinite()) "%.2f".format(p[3]) else "-"} ${"%+.3f".format(v[3])}SOL]"
        }.ifBlank { "none yet" }
        val openLine = open.values.groupBy { it.lane }.entries.sortedByDescending { it.value.size }
            .joinToString(",") { "${it.key}=${it.value.size}" }.ifBlank { "-" }
        val last = synchronized(this) { recent.toList().takeLast(6).reversed() }
            .joinToString(" · ") { "${it.lane}:${it.symbol.ifBlank { it.mint.take(6) }} ${"%+.1f".format(it.netPct)}% ${it.why}" }.ifBlank { "-" }
        val sk = skips.entries.sortedByDescending { it.value.get() }.take(6).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "memePaper8544 (paper beside LIVE, separate book) balance=${"%.3f".format(balanceSol)}SOL realised=${"%+.3f".format(realisedSol)}SOL " +
            "open{$openLine} closed: $lanes | last: $last | notOpened{$sk}"
    }
}
