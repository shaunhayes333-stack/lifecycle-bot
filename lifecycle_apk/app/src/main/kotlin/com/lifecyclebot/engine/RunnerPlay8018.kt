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

    /** Does a held position at [gross]% [ageMs] after entry, with its chart [firming], join the runner hold? */
    fun promotes8018(gross: Double, ageMs: Long, firming: Boolean, alreadyRunner: Boolean, standingDown: Boolean): Boolean =
        !alreadyRunner && !standingDown && firming && gross.isFinite() && gross >= PROMOTE_MIN_PCT_8018 && ageMs in 0L..PROMOTE_WINDOW_MS

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
    private val promoteRecord = CortexLedger7885.Stat()                      // realised minus scalp-ladder counterfactual
    private val promoted = AtomicLong(0)
    private val promoteShadow = AtomicLong(0)

    /** SpikeCapture7943.onMark, every fresh mark of a held position, before its ladder is chosen. */
    fun onMark8018(ts: TokenState, gross: Double, nowMs: Long = System.currentTimeMillis()) {
        val pos = ts.position
        if (!pos.isOpen || pos.entryTime <= 0L || !gross.isFinite()) return
        val key = "${ts.mint}|${pos.entryTime}"
        promos[key]?.let { p -> if (gross > p.peak) p.peak = gross; p.last = gross; return }
        if (gross < PROMOTE_MIN_PCT_8018 || nowMs - pos.entryTime > PROMOTE_WINDOW_MS) return
        if (RunnerGrab7967.holdingRunner8018(ts, nowMs)) return
        ensureLoaded()
        val firming = RunnerGrab7967.firmingNow8018(ts.mint, nowMs)
        val down = synchronized(promoteRecord) { standsDown8018(promoteRecord) }
        if (!promotes8018(gross, nowMs - pos.entryTime, firming, false, false)) return
        if (down) { promoteShadow.incrementAndGet(); return }
        if (!RunnerGrab7967.promote8018(ts)) return
        try { SpikeCapture7943.resetTiers8018(ts) } catch (_: Throwable) {}
        if (promos.size > 2_000) promos.entries.removeIf { nowMs - it.value.atMs > SEEN_MS }
        promos[key] = Promo(nowMs, gross, gross)
        promoted.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("RUNNER_PROMOTED_8018")
            ForensicLogger.lifecycle("RUNNER_PROMOTED_8018", "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=${pos.tradingMode} gross=${"%.1f".format(gross)}% ageS=${(nowMs - pos.entryTime) / 1000} action=runner_hold")
        } catch (_: Throwable) {}
    }

    /** CanonicalFinalizedTradeBus6464: a promoted position closed — grade the hold against the ladder it replaced. */
    fun onClose8018(env: com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope) {
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
    private val reentryRecord = CortexLedger7885.Stat()
    private val runsConfirmed = AtomicLong(0)
    private val tickets = AtomicLong(0)
    private val notFirming = AtomicLong(0)
    private val ticketShadow = AtomicLong(0)

    /** ForwardReturnLabeler7731: a followed coin is [gross]% over its decision price, [runAgeMs] after it. */
    fun onRun8018(mint: String, gross: Double, runAgeMs: Long, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || !gross.isFinite() || gross < RUN_CONFIRM_PCT_8018 || runAgeMs > RUN_MAX_AGE_MS) return
        armed[mint]?.let { if (nowMs - it < SEEN_MS) return }
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
        if (armed.size > 2_000) armed.entries.removeIf { nowMs - it.value > SEEN_MS }
        armed[mint] = nowMs
        runsConfirmed.incrementAndGet()
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
        loaded = true
        try {
            val lines = file()?.takeIf { it.exists() }?.readLines() ?: return
            lines.getOrNull(0)?.let { synchronized(promoteRecord) { promoteRecord.decode(it.trim()) } }
            lines.getOrNull(1)?.let { synchronized(reentryRecord) { reentryRecord.decode(it.trim()) } }
        } catch (_: Throwable) {}
    }

    private fun save() {
        try {
            file()?.writeText(synchronized(promoteRecord) { promoteRecord.encode() } + "\n" + synchronized(reentryRecord) { reentryRecord.encode() } + "\n")
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        fun rec(st: CortexLedger7885.Stat): String = synchronized(st) {
            if (st.n < 1.0) "-" else "n${st.n.toInt()}/${"%+.1f".format(st.mean())}%${if (standsDown8018(st)) " STOOD_DOWN" else ""}"
        }
        return "promoted=${promoted.get()} holding=${promos.size} shadow=${promoteShadow.get()} vsLadder=${rec(promoteRecord)} | " +
            "reentry: runsConfirmed=${runsConfirmed.get()} tickets=${tickets.get()} shadow=${ticketShadow.get()} notFirming=${notFirming.get()} " +
            "pendingGrades=${signals.size} record30m=${rec(reentryRecord)}"
    }

    internal fun resetForTest8018() {
        promos.clear(); considered.clear(); armed.clear(); signals.clear()
        synchronized(promoteRecord) { promoteRecord.scale(0.0) }
        synchronized(reentryRecord) { reentryRecord.scale(0.0) }
    }
}
