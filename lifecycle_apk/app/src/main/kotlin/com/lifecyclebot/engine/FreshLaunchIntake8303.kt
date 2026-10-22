package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AdmissionBirth8144
import com.lifecyclebot.network.PumpCurveKeys7269
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8303 §A_WITNESSED_LAUNCH_REACHES_THE_LANES_THAT_TRADE_LAUNCHES.
 *
 * Live 5.0.8302: 874 PumpPortal create frames (PUMP_CREATE_MARK_EMITTED_7279=868 passed the create throttle), yet no
 * PUMP_PORTAL_WS row in intake-by-source and PROJECT_SNIPER candidateN=0 (NATIVE_BIRTH_METADATA_HYDRATING=1099). The
 * birth was never lost: AdmissionBirth8144 / PumpCurveKeys7269 / CanonicalTokenBirthTime7440 all hold the create frame's
 * time. The MINT was lost. BotService.admitProtectedMemeIntake parks a live PumpPortal create in probation as "cold pump
 * base" when its liquidity estimate is under $1,500 — a cut calibrated by 7384 on the old estimate (cap x 0.85, ~$4.8k for
 * a 28 SOL create: "only dust launches wait"). 8048 replaced that estimate with the curve's REAL SOL from the cap, which
 * is ~0.02 SOL (~$3) at create, so since 8048 EVERY live create is parked: no token row, no INTAKE phase event (so no
 * PUMP_PORTAL_WS intake row), no lane evaluation and no sniper candidate; the probation exits that could bring it back
 * (timeout, 5 min; withheld by 8292 while the PROBATION pathway loses) all fire after the sniper's 180 s window. The only
 * create-framed mints any lane saw were PumpBandEntry8118 crossings ($10k+, usually past the window); the sniper's 1,099
 * "hydrating" reads were other feeds' launches (Codex / livestream / chain) whose births were still being walked.
 *
 * Rule: a PumpPortal create whose birth is WITNESSED (the create frame, read here from the same maps the sniper reads)
 * and still inside the sniper window is not parked by the depth estimate or the learned source multiplier. The supervisor
 * pressure diversion, the hot-watchlist cap / pump share and every intake, safety, liquidity, FDG and buy-door gate still
 * apply; only the routing changes. A per-minute budget ([HOT_PER_MIN_8303], about the observed create rate) clips bursts;
 * a create over budget is parked exactly as before.
 *
 * Also: the band entry treated ANY token row as "already admitted", but a row the hot watchlist evicted (LRU, it is not
 * removed from status.tokens) is never evaluated again, so a launch evicted before it crossed $10k could not be
 * re-admitted when it did ([rowBlocksBandEntry8303]); and its birth read only the curve-key create time (frames with
 * reserves), not the create frame itself ([createBirthMs8303]).
 */
object FreshLaunchIntake8303 {
    const val SOURCE_8303 = "PUMP_PORTAL_WS"
    /** The launch tape's promotion tag (BotService.promoteHotLaunchNow7921): a pathway, not a discovery feed. */
    private const val HEAT_TAG_8303 = "LAUNCH_HEAT_7921"
    /** ProjectSniperAI.MAX_TOKEN_AGE_SECONDS (180 s): the window a create must reach the sniper inside. */
    const val HOT_WINDOW_MS_8303 = 180_000L
    /** At most this many fresh creates go hot per rolling minute (5.0.8302: 868 creates in 37 min, ~23/min). */
    private const val HOT_PER_MIN_8303 = 24
    private const val MINUTE_MS_8303 = 60_000L
    private const val CLOCK_SKEW_MS_8303 = 60_000L

    // ── pure ──

    /** Pure: a PumpPortal create whose witnessed birth ([createBirthMs]) is inside the sniper window at [nowMs]. */
    fun freshCreate8303(source: String, createBirthMs: Long?, nowMs: Long): Boolean =
        source.trim().equals(SOURCE_8303, ignoreCase = true) && createBirthMs != null && createBirthMs > 0L &&
            nowMs - createBirthMs in -CLOCK_SKEW_MS_8303..HOT_WINDOW_MS_8303

    /** Pure: fewer than [perMin] fresh creates went hot in the minute before [nowMs]. */
    fun budgetOpen8303(recentHotMs: Collection<Long>, nowMs: Long, perMin: Int = HOT_PER_MIN_8303): Boolean =
        recentHotMs.count { nowMs - it in 0L until MINUTE_MS_8303 } < perMin

    /**
     * Pure: does intake park the row in probation? [pressurePark] (supervisor pressure) always parks; the cold-pump /
     * source-brain park ([wouldPark]) does not apply to an [exempt] fresh witnessed create.
     */
    fun parkDecision8303(wouldPark: Boolean, pressurePark: Boolean, exempt: Boolean): Boolean =
        pressurePark || (wouldPark && !exempt)

    /** Pure: an existing row blocks a band entry only while it is still evaluated (watched) or held. */
    fun rowBlocksBandEntry8303(rowExists: Boolean, watched: Boolean, held: Boolean): Boolean =
        rowExists && (watched || held)

    /** Pure: the earliest plausible direct create witness among [witnesses] (null / non-positive ignored). */
    fun earliestBirth8303(witnesses: List<Long?>): Long? = witnesses.filterNotNull().filter { it > 0L }.minOrNull()

    /**
     * Pure: provenance of a launch-tape promotion. A mint whose PumpPortal create frame was witnessed is a PumpPortal
     * launch and is filed under that feed first (its forward-label cell is PUMP_PORTAL_WS|...); the tape is a pathway.
     */
    fun heatSources8303(createFrameWitnessed: Boolean): Set<String> =
        if (createFrameWitnessed) linkedSetOf(SOURCE_8303, HEAT_TAG_8303) else linkedSetOf(HEAT_TAG_8303, SOURCE_8303)

    // ── runtime ──

    private val recentHot = ConcurrentLinkedDeque<Long>()
    private val hot = AtomicLong(0)
    private val parkedBudget = AtomicLong(0)
    private val parkedStale = AtomicLong(0)
    private val bandReadmits = AtomicLong(0)

    private fun count8303(label: String) {
        try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {}
    }

    /** The mint's witnessed pump create time: the curve-key create (frame with reserves) or any create frame / coin record. */
    fun createBirthMs8303(mint: String): Long? = earliestBirth8303(listOf(
        try { PumpCurveKeys7269.createdAtMs7280(mint) } catch (_: Throwable) { null },
        try { AdmissionBirth8144.pumpCreateMs8144(mint) } catch (_: Throwable) { null },
    ))

    /**
     * BotService.admitProtectedMemeIntake (one line): the probation park decision. Only a park the cold-pump base or the
     * source brain would make is re-read; a fresh witnessed create inside the window and the budget goes hot.
     */
    fun park8303(wouldPark: Boolean, pressurePark: Boolean, source: String, mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (pressurePark || !wouldPark) return parkDecision8303(wouldPark, pressurePark, exempt = false)
        if (!freshCreate8303(source, createBirthMs8303(mint), nowMs)) {
            if (source.trim().equals(SOURCE_8303, ignoreCase = true)) { parkedStale.incrementAndGet(); count8303("FRESH_CREATE_PARKED_NO_WITNESS_8303") }
            return true
        }
        val open = synchronized(recentHot) {
            while (true) {
                val head = recentHot.peekFirst() ?: break
                if (nowMs - head >= MINUTE_MS_8303 || head > nowMs + CLOCK_SKEW_MS_8303) recentHot.pollFirst() else break
            }
            val ok = budgetOpen8303(recentHot, nowMs)
            if (ok) recentHot.addLast(nowMs)
            ok
        }
        if (!open) { parkedBudget.incrementAndGet(); count8303("FRESH_CREATE_PARKED_BUDGET_8303"); return true }
        hot.incrementAndGet()
        count8303("FRESH_CREATE_HOT_8303")
        return parkDecision8303(wouldPark, pressurePark, exempt = true)
    }

    /** BotService.bandEntry8118: true when an existing row is still evaluated or held (the band entry then stands down). */
    fun rowWatched8303(rowExists: Boolean, mint: String): Boolean {
        if (!rowExists) return false
        val watched = try { GlobalTradeRegistry.isWatching(mint) } catch (_: Throwable) { true }
        val held = try { HeldPositionSupervisor7246.isHeld(mint) } catch (_: Throwable) { true }
        val blocks = rowBlocksBandEntry8303(rowExists, watched, held)
        if (!blocks) { bandReadmits.incrementAndGet(); count8303("PUMP_BAND_ENTRY_STALE_ROW_READMIT_8303") }
        return blocks
    }

    /** BotService.promoteHotLaunchNow7921: the provenance of a tape promotion admitted without a row. */
    fun heatSourcesFor8303(mint: String): Set<String> =
        heatSources8303(try { AdmissionBirth8144.createFrameMs8169(mint) != null } catch (_: Throwable) { false })

    fun statusLine8303(): String =
        "freshLaunch8303[hot=${hot.get()} parkedBudget=${parkedBudget.get()} parkedNoWitness=${parkedStale.get()} " +
            "bandStaleRowReadmits=${bandReadmits.get()} budget=$HOT_PER_MIN_8303/min window=${HOT_WINDOW_MS_8303 / 1000}s]"
}
