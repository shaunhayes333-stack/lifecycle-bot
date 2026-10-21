package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.chart.StructureTracker7962
import com.lifecyclebot.engine.cortex.CortexLedger7885
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8018 — THE RUNNER PLAY: hold the run that forms under an ordinary buy, size it on
 * confirmation, and buy the run the bot missed once the run is confirmed.
 *
 * Owner, 5.0.8017: "do 1 2 3 5 now". The runner-capture audit (§8017) splits a missed fortune
 * into entries, holds and exits; this builds the hold, the size and the second entry.
 *
 * PROMOTE (exits). Only grabs, proven-cell admits and tickets were held on the runner ladder.
 * Every other buy sold 60% at its first +40% print (SpikeCapture7943 prior) — on a +800% coin
 * that banks about a tenth of the run. A held position whose own chart is FIRMING (1m or 15s:
 * 2+ higher swings, a higher low, unbroken, buyers >= 55%) at +[PROMOTE_MIN_PCT_8018]% or
 * better within [PROMOTE_WINDOW_MS] of entry joins the runner hold from its entry: half off at
 * 2x, 35% at 5x and 11x, soft exits deferred while the structure stands, out on the structure
 * break, 35% below a 3x+ peak, or -35%.
 *   Closed loop: every promoted close is graded against the scalp ladder it replaced, on its own
 *   peak and last mark (SpikeCapture7943.realisableGrossPct). Once [MIN_N] promotions show the
 *   runner hold banking less (mean + SE < 0), promotion stands down until the record recovers.
 *
 * SIZE (on confirmation, not prediction). The Cortex already sizes STRONG reads toward a
 * quarter-Kelly stake; on a ~0.13 SOL wallet that stake is below the route minimum, so the
 * entry stays at the minimum — betting more before the coin proves itself is how small
 * wallets die. The size goes in when the run confirms: a promoted runner gets the structure adds
 * (RunnerGrab7967.structureAdd7973, one per new confirmed swing, in profit only), and runner adds
 * are sized like a diamond-hands pyramid (Executor.topUpSizeSol: up to 25% of the wallet per add,
 * inside the 70% exposure ceiling). Losers die at the starter size; runners end up the biggest.
 *
 * RE-ENTER (the second entry). A coin the bot judged (refused, or bought and already sold) that
 * the forward labeler sees +[RUN_CONFIRM_PCT_8018]% over its decision price inside
 * [RUN_MAX_AGE_MS], not held, chart firming now, not Mayhem, not hard-blocked, is a re-entry
 * ticket for [TICKET_TTL_MS]: it clears watch-first, rides the chart-admit path past the soft
 * refusals, opens at the executable minimum and is held on the runner ladder. At most
 * [MAX_OPEN_REENTRIES] open at once; one ticket per coin per [SEEN_MS].
 *   Closed loop: every confirmed run (ticketed or not) is graded [GRADE_AFTER_MS] later on the
 *   same tape; once [MIN_N] grades show mean + SE < 0, tickets stand down (grading continues and
 *   they re-arm when it recovers).
 */
object RunnerPlay8018 {
    const val PROMOTE_MIN_PCT_8018 = 25.0
    const val RUN_CONFIRM_PCT_8018 = 100.0
    private const val PROMOTE_WINDOW_MS = 30L * 60_000L
    // V5.0.8025 — runners refused at first sight often confirm later than the hour (QubitCat, brigitte): 4 hours.
    private const val RUN_MAX_AGE_MS = 4L * 60L * 60_000L
    private const val RUN_MAX_PCT = 3_000.0
    private const val TICKET_TTL_MS = 3L * 60_000L
    private const val MAX_OPEN_REENTRIES = 2
    private const val SEEN_MS = 6L * 3_600_000L
    private const val RECHECK_MS = 15_000L
    private const val GRADE_AFTER_MS = 30L * 60_000L
    private const val MIN_N = 15

    // ── pure ──

    /**
     * Does a held position at [gross]% [ageMs] after entry, with its chart [firming], join the runner hold?
     * V5.0.8153 — retired from the live path: RunnerPlan8153.promotion8153 is the one promotion rule (verified peak and
     * print, structure, no 30-minute window). Kept for the 8018 record of what the old rule decided.
     */
    fun promotes8018(gross: Double, ageMs: Long, firming: Boolean, alreadyRunner: Boolean, standingDown: Boolean): Boolean =
        !alreadyRunner && !standingDown && firming && gross.isFinite() && gross >= PROMOTE_MIN_PCT_8018 && ageMs in 0L..PROMOTE_WINDOW_MS

    /** Pure (V5.0.8136): the verified position peak [peakGainPct] has reached the promotion level. */
    fun verifiedPeakReached8136(peakGainPct: Double): Boolean = peakGainPct.isFinite() && peakGainPct >= PROMOTE_MIN_PCT_8018

    /** Is a coin [gross]% over its decision price [runAgeMs] after the decision a confirmed run worth a second entry? */
    fun confirmsRun8018(gross: Double, runAgeMs: Long, firming: Boolean, held: Boolean): Boolean =
        !held && firming && gross.isFinite() && gross >= RUN_CONFIRM_PCT_8018 && gross <= RUN_MAX_PCT && runAgeMs in 0L..RUN_MAX_AGE_MS

    /** A record stands its play down once [MIN_N] outcomes show mean + SE below zero. */
    fun standsDown8018(st: CortexLedger7885.Stat): Boolean {
        if (st.n < MIN_N) return false
        val se = kotlin.math.sqrt(st.variance() / st.n)
        return st.mean() + se < 0.0
    }

    // ── promotion ──

    private class Promo(val atMs: Long, @Volatile var peak: Double, @Volatile var last: Double)
    private val promos = ConcurrentHashMap<String, Promo>()                  // mint|entryTime
    private val promoteRecord = CortexLedger7885.realisedStat8187()                      // realised minus scalp-ladder counterfactual
    private val promoted = AtomicLong(0)
    private val promoteShadow = AtomicLong(0)

    /** SpikeCapture7943.onMark, every fresh mark of a held position, before its ladder is chosen. */
    fun onMark8018(ts: TokenState, gross: Double, nowMs: Long = System.currentTimeMillis()) {
        val pos = ts.position
        if (!pos.isOpen || pos.entryTime <= 0L || !gross.isFinite()) return
        // V5.0.8153 — every mark keeps the position's continuation / give-back / distribution / structure state and its
        // gross opportunity (RunnerLive8153), promoted or not.
        try { RunnerLive8153.onMark8153(ts, gross, nowMs) } catch (_: Throwable) {}
        val key = "${ts.mint}|${pos.entryTime}"
        promos[key]?.let { p -> if (gross > p.peak) p.peak = gross; p.last = gross; return }
        // V5.0.8144 — runner eligibility has one authority (ExitTruthArbiter8144.runnerBasis8144): the immutable sealed
        // entry style / strategy, or a PROVEN peak (+100% corroborated, RunnerExitContract8136). A position sealed as a
        // runner needs no promotion; the lane is not the question here (TradeExitContract8077.runnerBasis8136 agrees).
        val basis8144 = try { ExitTruthArbiter8144.runnerBasis8144(ExitTruthArbiterLive8144.inputsFor8144(ts, null).copy(laneHostsRunners = true)) }
            catch (_: Throwable) { RunnerExitContract8136.RunnerBasis8136.NONE }
        if (basis8144 == RunnerExitContract8136.RunnerBasis8136.SEALED_SETUP) return
        // V5.0.8153 — ONE promotion rule (RunnerPlan8153.promotion8153). 8136/8144 stacked a +25% print, a +25% verified
        // peak, a +100% corroborated peak, a firming chart and a 30-minute window here, so nothing was promoted before
        // +100% (the scalp rung had sold 60% at +40%) and a late continuation never was. Now: the chain-verified peak AND
        // the print at +25% with the chart firming promotes (before the +40% rung); a corroborated +100% peak latches what
        // the plan already reads; an uncorroborated print, bad units, a broken structure or distribution is refused.
        ensureLoaded()
        val down = synchronized(promoteRecord) { standsDown8018(promoteRecord) }
        val verdict = try { RunnerLive8153.promotion8153(ts, gross, down, nowMs) } catch (_: Throwable) { return }
        if (verdict == RunnerPlan8153.Promotion8153.STOOD_DOWN) { promoteShadow.incrementAndGet(); return }
        if (!RunnerPlan8153.promotes8153(verdict)) return
        if (!RunnerGrab7967.promote8018(ts)) return
        if (promos.size > 2_000) promos.entries.removeIf { nowMs - it.value.atMs > SEEN_MS }
        promos[key] = Promo(nowMs, gross, gross)
        promoted.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("RUNNER_PROMOTED_8018")
            PipelineHealthCollector.labelInc("RUNNER_${verdict.name}_8153")
            ForensicLogger.lifecycle("RUNNER_PROMOTED_8018", "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=${pos.tradingMode} gross=${"%.1f".format(gross)}% verifiedPeak=${"%.1f".format(pos.peakGainPct)}% why=${verdict.name} ageS=${(nowMs - pos.entryTime) / 1000} action=runner_hold")
        } catch (_: Throwable) {}
    }

    /** CanonicalFinalizedTradeBus6464: a promoted position closed — grade the hold against the ladder it replaced. */
    fun onClose8018(env: com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope) {
        if (env.terminal && env.mint.isNotBlank() && armed.containsKey(env.mint)) closedAt8297[env.mint] = env.atMs  // V5.0.8297
        if (!env.terminal || env.mint.isBlank() || !env.realizedReturnPct.isFinite()) return
        val entryMs = env.atMs - env.holdingTimeMs.coerceAtLeast(0L)
        val key = promos.keys.firstOrNull { it.startsWith(env.mint + "|") && kotlin.math.abs((it.substringAfter('|').toLongOrNull() ?: 0L) - entryMs) < 120_000L } ?: return
        val p = promos.remove(key) ?: return
        val peak = maxOf(p.peak, if (env.mfePct.isFinite()) env.mfePct else 0.0)
        val ladder = SpikeCapture7943.realisableGrossPct(peak, p.last)
        if (!ladder.isFinite()) return
        val delta = (env.realizedReturnPct - ladder).coerceIn(-500.0, 2_000.0)
        synchronized(promoteRecord) { promoteRecord.add(delta, delta > 0.0) }
        save()
    }

    // ── re-entry ──

    private val considered = ConcurrentHashMap<String, Long>()               // mint -> last check
    private val armed = ConcurrentHashMap<String, Long>()                    // mint -> ticket issued
    private val signals = ConcurrentHashMap<String, Pair<Double, Long>>()    // mint -> (px, at) awaiting grade
    private val reentryRecord = CortexLedger7885.realisedStat8187()  // V5.0.8187 — realised: read raw
    private val runsConfirmed = AtomicLong(0)
    private val tickets = AtomicLong(0)
    private val notFirming = AtomicLong(0)
    private val ticketShadow = AtomicLong(0)

    /** ForwardReturnLabeler7731: a followed coin is [gross]% over its decision price, [runAgeMs] after it. */
    fun onRun8018(mint: String, gross: Double, runAgeMs: Long, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || !gross.isFinite() || gross < RUN_CONFIRM_PCT_8018 || runAgeMs > RUN_MAX_AGE_MS) return
        // V5.0.8297 — a lapsed window (not bought, coin not closed since) re-arms while the run still confirms.
        armed[mint]?.let { prev -> if (nowMs - prev < SEEN_MS && !rearms8297(windows8297[mint] ?: 1, prev, nowMs, (closedAt8297[mint] ?: 0L) >= (firstArmed8297[mint] ?: prev))) return }
        considered[mint]?.let { if (nowMs - it < RECHECK_MS) return }
        if (considered.size > 4_000) considered.entries.removeIf { nowMs - it.value > RUN_MAX_AGE_MS }
        considered[mint] = nowMs
        ensureLoaded()
        gradeDue(nowMs)
        val ts = try { BotService.status.tokens[mint] } catch (_: Throwable) { null } ?: return
        if (ts.safety.tier == SafetyTier.HARD_BLOCK) return
        if (try { MayhemMode7943.liveRefusal(ts, nowMs) } catch (_: Throwable) { null } != null) return
        val firming = RunnerGrab7967.runStandingNow8019(mint, nowMs)  // V5.0.8019 — firming or standing with buyers
        if (!confirmsRun8018(gross, runAgeMs, firming, ts.position.isOpen)) { if (!firming) notFirming.incrementAndGet(); return }
        if (armed.size > 2_000) { armed.entries.removeIf { nowMs - it.value > SEEN_MS }; windows8297.keys.retainAll(armed.keys); firstArmed8297.keys.retainAll(armed.keys); closedAt8297.keys.retainAll(armed.keys) }
        val windowsBefore8297 = if (armed.containsKey(mint)) windows8297[mint] ?: 1 else 0
        armed[mint] = nowMs
        windows8297[mint] = windowsBefore8297 + 1
        if (windowsBefore8297 == 0) { firstArmed8297[mint] = nowMs; runsConfirmed.incrementAndGet() } else rearmed8297.incrementAndGet()
        val px = ts.lastPrice
        if (px.isFinite() && px > 0.0 && signals.size < 2_000) signals.putIfAbsent(mint, px to nowMs)
        if (synchronized(reentryRecord) { standsDown8018(reentryRecord) }) { ticketShadow.incrementAndGet(); return }
        val open = armed.keys.count { m -> m != mint && try { BotService.status.tokens[m]?.position?.isOpen == true } catch (_: Throwable) { false } }
        if (open >= MAX_OPEN_REENTRIES) { ticketShadow.incrementAndGet(); return }
        tickets.incrementAndGet()
        try {
            RunnerGrab7967.holdAsRunner7972(ts, nowMs)
            PipelineHealthCollector.labelInc("RUN_REENTRY_TICKET_8018")
            ForensicLogger.lifecycle("RUN_REENTRY_TICKET_8018", "mint=${mint.take(10)} sym=${ts.symbol} gross=${"%.0f".format(gross)}% runAgeM=${runAgeMs / 60_000} action=admit_and_hold")
        } catch (_: Throwable) {}
    }

    // ── V5.0.8297 §A_TICKET_WINDOW_RE_ARMS ──
    // A ticket was one 3-minute window per coin per 6 hours: a confirmed run that spent those 3 minutes at its high
    // (CHASING_SPIKE_8065: wait for the pullback) or waiting on a data deferral was never offered again, though the
    // labeler kept confirming it. A lapsed window re-arms after [REARM_GAP_MS_8297] while the run still confirms (the same
    // confirmsRun8018 test: +100..+3,000% inside 4 h, structure standing, not held), at most [MAX_WINDOWS_8297] windows
    // per coin, never after the bot closed a position in it since the first ticket (a ticket clears the repeat-loser
    // lock), and every other bound (record stand-down, open re-entries, chase rule, gates) is unchanged.
    const val MAX_WINDOWS_8297 = 3
    const val REARM_GAP_MS_8297 = 60_000L
    private val windows8297 = ConcurrentHashMap<String, Int>()
    private val firstArmed8297 = ConcurrentHashMap<String, Long>()
    private val closedAt8297 = ConcurrentHashMap<String, Long>()
    private val rearmed8297 = AtomicLong(0)

    /** Pure: may a lapsed ticket window armed at [lastArmedAtMs] re-arm now? */
    fun rearms8297(windowsUsed: Int, lastArmedAtMs: Long, nowMs: Long, closedSinceFirstTicket: Boolean): Boolean =
        !closedSinceFirstTicket && windowsUsed < MAX_WINDOWS_8297 && nowMs - lastArmedAtMs > TICKET_TTL_MS + REARM_GAP_MS_8297

    /** HandoffPriority8190: every active ticket's mint and deadline — the coin is evaluated inside its window. */
    fun activeTicketTargets8297(nowMs: Long = System.currentTimeMillis()): List<Pair<String, Long>> =
        if (synchronized(reentryRecord) { standsDown8018(reentryRecord) }) emptyList()
        else armed.entries.filter { nowMs - it.value in 0L..TICKET_TTL_MS }.map { it.key to it.value + TICKET_TTL_MS }

    /** LiveEdgeGate7877 / ChartReader7950 / FinalDecisionGate: a re-entry ticket issued in the last few minutes. */
    fun ticketActive8018(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        armed[mint]?.let { nowMs - it in 0L..TICKET_TTL_MS } == true &&
            synchronized(reentryRecord) { !standsDown8018(reentryRecord) }

    /** TraderSizingBridge6444: a re-entry opens at the executable minimum (the run has to prove itself again). */
    fun sizeMult8018(mint: String, mult: Double): Double {
        val m = if (mult.isFinite() && mult > 0.0) mult else 1.0
        return if (ticketActive8018(mint)) minOf(m, 0.5) else m
    }

    /** Grade every confirmed run whose 30-minute horizon has passed, on the structure tape (else a fresh row mark). */
    private fun gradeDue(nowMs: Long) {
        for ((mint, sig) in signals.entries.toList()) {
            val (px, at) = sig
            if (nowMs - at < GRADE_AFTER_MS) continue
            signals.remove(mint)
            val later = try { StructureTracker7962.markAt7962(mint, at + GRADE_AFTER_MS) } catch (_: Throwable) { null }
                ?: try { BotService.status.tokens[mint]?.takeIf { nowMs - it.lastPriceUpdate in 0L..120_000L && nowMs - at <= GRADE_AFTER_MS + 120_000L }?.lastPrice } catch (_: Throwable) { null }
                ?: continue
            if (!(later > 0.0) || !(px > 0.0)) continue
            val r = later / px
            if (r < 0.01 || r > 200.0) continue
            val g = ((r - 1.0) * 100.0).coerceIn(-100.0, 1_000.0)
            synchronized(reentryRecord) { reentryRecord.add(g, g >= 100.0) }
            save()
        }
    }

    // ── persistence (both records survive installs) ──

    @Volatile private var loaded = false
    private fun file(): java.io.File? = com.lifecyclebot.AATEApp.appContextOrNull()?.let { java.io.File(it.filesDir, "runner_play_8018.txt") }

    private fun ensureLoaded() {
        if (loaded) return
        if (com.lifecyclebot.AATEApp.appContextOrNull() == null) return   // V5.0.8086 — no store yet: latching now made the next save replace the file with empty state
        loaded = true
        try {
            val lines = file()?.takeIf { it.exists() }?.readLines() ?: return
            lines.getOrNull(0)?.let { synchronized(promoteRecord) { promoteRecord.decode(it.trim()) } }
            lines.getOrNull(1)?.let { synchronized(reentryRecord) { reentryRecord.decode(it.trim()) } }
        } catch (_: Throwable) {}
    }

    private fun save() {
        try {
            file()?.writeTextAtomic8086(synchronized(promoteRecord) { promoteRecord.encode() } + "\n" + synchronized(reentryRecord) { reentryRecord.encode() } + "\n")
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        fun rec(st: CortexLedger7885.Stat): String = synchronized(st) {
            if (st.n < 1.0) "-" else "n${st.n.toInt()}/${"%+.1f".format(st.mean())}%${if (standsDown8018(st)) " STOOD_DOWN" else ""}"
        }
        return "promoted=${promoted.get()} holding=${promos.size} shadow=${promoteShadow.get()} vsLadder=${rec(promoteRecord)} | " +
            "reentry: runsConfirmed=${runsConfirmed.get()} rearmed8297=${rearmed8297.get()} tickets=${tickets.get()} shadow=${ticketShadow.get()} notFirming=${notFirming.get()} " +
            "pendingGrades=${signals.size} record30m=${rec(reentryRecord)}"
    }

    internal fun resetForTest8018() {
        promos.clear(); considered.clear(); armed.clear(); signals.clear()
        windows8297.clear(); firstArmed8297.clear(); closedAt8297.clear()
        synchronized(promoteRecord) { promoteRecord.scale(0.0) }
        synchronized(reentryRecord) { reentryRecord.scale(0.0) }
    }
}
