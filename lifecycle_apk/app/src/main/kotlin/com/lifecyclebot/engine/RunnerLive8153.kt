package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.chart.StructureTracker7962
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8153 — the live doors of RunnerPlan8153 (the one runner plan) and RunnerRungBook8153 (the rung lifecycle).
 *
 *  - [basis8153] / [plan8153]     TradeExitContract8077.runnerBasis8136, ExitTruthArbiterLive8144.inputsFor8144 and
 *                                 RunnerGrab7967.holding7967 read the plan from here: one answer per position.
 *  - [promotion8153]              RunnerPlay8018.onMark8018 (every fresh mark, before the ladder is chosen): the verdict
 *                                 on the verified peak, the print and the live structure; [latchPromotion8153] makes it
 *                                 stick for the position (persisted, restored after a restart).
 *  - [onMark8153]                 continuation / give-back / distribution / structure-break state per position and the
 *                                 gross opportunity; [continuationConfirmed8153] feeds the arbiter.
 *  - rung doors                   SpikeCapture7943 (request / settle / revert / dispatched), Executor
 *                                 commitVerifiedLiveSlice7835 ([bookSlice8153]: the confirmed fill and its proceeds).
 */
object RunnerLive8153 {
    private const val FILE_8153 = "runner_rungs_8153.txt"
    private const val MAX_STATE_KEYS_8153 = 2_000
    private const val MAX_COUNTER_KEYS_8153 = 48
    private val SPIKE_RUNG_8153 = Regex("SPIKE_CAPTURE_7943_T([0-9]+)")

    private val book = RunnerRungBook8153()
    private val continuation = ConcurrentHashMap<String, RunnerPlan8153.Continuation8153>()
    private val lastVerdict = ConcurrentHashMap<String, RunnerPlan8153.Promotion8153>()
    private val counts = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    @Volatile private var loaded = false

    private fun count8153(label: String) {
        if (counts.size < MAX_COUNTER_KEYS_8153 || counts.containsKey(label)) {
            counts.computeIfAbsent(label) { java.util.concurrent.atomic.AtomicLong(0) }.incrementAndGet()
        }
        try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {}
    }

    private fun key8153(ts: TokenState): String = "${ts.mint}|${ts.position.entryTime}"

    // ── durable store (restart restores rungs and promotions idempotently) ──

    private fun file8153(): java.io.File? = try {
        com.lifecyclebot.AATEApp.appContextOrNull()?.let { java.io.File(it.filesDir, FILE_8153) }
    } catch (_: Throwable) { null }

    private fun ensureLoaded8153() {
        if (loaded) return
        // No store yet (early boot / JVM test): latching now would let the next save replace the file with empty state.
        val f = file8153() ?: return
        synchronized(this) {
            if (loaded) return
            loaded = true
            val n = try { if (f.exists()) book.restore8153(f.readText()) else 0 } catch (_: Throwable) { 0 }
            if (n > 0) count8153("RUNNER_RUNGS_RESTORED_8153")
        }
    }

    private fun save8153() {
        try { file8153()?.writeTextAtomic8086(book.encode8153()) } catch (_: Throwable) {}
    }

    // ── the one plan ──

    /** What [ts]'s plan is decided from: sealed style / strategy, lane hosting, entry-time admit, latch, verified peak. */
    private fun evidence8153(ts: TokenState): RunnerPlan8153.Evidence8153 {
        val pos = ts.position
        val snap = try {
            pos.positionId.takeIf { it.isNotBlank() }?.let { com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.snapshot(it) }
        } catch (_: Throwable) { null }
        val strategy = snap?.takeIf { it.mint == ts.mint }?.let { s -> s.entryTactic.ifBlank { s.entryStrategyPid } }.orEmpty()
        val style = try { TradeExitContract8077.sealedStyle8136(ts) } catch (_: Throwable) { "" }
        // Paper decides as live will: the lane question ignores the paper flag (paper never sells anything real).
        val hosts = try { TradeExitContract8077.runnerFromEntryLane8104(pos.tradingMode, false) } catch (_: Throwable) { false }
        val grabbed = try { RunnerGrab7967.grabbedAtEntry8153(ts) } catch (_: Throwable) { false }
        val runnerAdmit = try { com.lifecyclebot.engine.truth.CandidateAdmission8136.runnerEntryAdmitted8252(ts.mint, pos.entryTime) } catch (_: Throwable) { false }
        ensureLoaded8153()
        return RunnerPlan8153.Evidence8153(
            laneHostsRunners = hosts, sealedStyle = style, sealedStrategy = strategy, grabbedAtEntry = grabbed,
            promotedLatched = book.promoted8153(key8153(ts)), verifiedPeakPct = pos.peakGainPct,
            runnerEntryAdmitted = runnerAdmit,
        )
    }

    fun plan8153(ts: TokenState): RunnerPlan8153.Plan8153 {
        if (!ts.position.isOpen || ts.position.entryTime <= 0L) return RunnerPlan8153.Plan8153.SCALP
        return RunnerPlan8153.plan8153(evidence8153(ts))
    }

    fun basis8153(ts: TokenState): RunnerExitContract8136.RunnerBasis8136 = RunnerPlan8153.basis8153(plan8153(ts))

    // ── promotion ──

    /**
     * RunnerPlay8018.onMark8018: the promotion verdict for [ts] at the print [gross]. The live structure is read here
     * (StructureTracker7962: firming = 2+ higher swings, a higher low, unbroken, buyers; broken = the 1m structure break).
     */
    fun promotion8153(ts: TokenState, gross: Double, standingDown: Boolean, nowMs: Long): RunnerPlan8153.Promotion8153 {
        val e = evidence8153(ts)
        val (r15, r60) = StructureTracker7962.reads7967(ts.mint, nowMs)
        val firming = RunnerGrab7967.firming7967(r15) || RunnerGrab7967.firming7967(r60)
        val v = RunnerPlan8153.promotion8153(RunnerPlan8153.PromotionInput8153(
            plan = RunnerPlan8153.plan8153(e), laneHostsRunners = e.laneHostsRunners, latched = e.promotedLatched,
            verifiedPeakPct = e.verifiedPeakPct, grossPct = gross, firming = firming,
            structureBroken = r60?.brokeStructure == true, ageMs = nowMs - ts.position.entryTime, standingDown = standingDown,
        ))
        // A refusal is counted when the verdict changes for the position (not on every mark).
        val key = key8153(ts)
        if (v.name.startsWith("REFUSE_") && lastVerdict.put(key, v) != v) count8153("RUNNER_PROMOTION_${v.name}_8153")
        if (lastVerdict.size > MAX_STATE_KEYS_8153) lastVerdict.clear()
        return v
    }

    /** RunnerGrab7967.promote8018: latch the proven promotion of THIS position (persisted). True when newly latched. */
    fun latchPromotion8153(ts: TokenState): Boolean {
        if (!ts.position.isOpen || ts.position.entryTime <= 0L) return false
        ensureLoaded8153()
        val fresh = book.latchPromotion8153(key8153(ts))
        if (fresh) { count8153("RUNNER_PROMOTION_LATCHED_8153"); save8153() }
        return fresh
    }

    // ── continuation and opportunity ──

    /** RunnerPlay8018.onMark8018: the runner's continuation state on this mark and the gross opportunity (entry cost x peak). */
    fun onMark8153(ts: TokenState, gross: Double, nowMs: Long) {
        val pos = ts.position
        if (!pos.isOpen || pos.entryTime <= 0L) return
        val key = key8153(ts)
        val plan = plan8153(ts)
        // Live economics only: the opportunity is read against the SOL the live slices really banked.
        if (!pos.isPaperPosition) book.noteOpportunity8153(key, ladderOf8153(plan), pos.costSol, pos.peakGainPct)
        if (plan == RunnerPlan8153.Plan8153.SCALP) { continuation.remove(key); return }
        val (r15, r60) = StructureTracker7962.reads7967(ts.mint, nowMs)
        val c = RunnerPlan8153.continuation8153(plan, pos.peakGainPct, gross,
            firming = RunnerGrab7967.firming7967(r15) || RunnerGrab7967.firming7967(r60),
            standing = RunnerGrab7967.runStanding8019(r60) || RunnerGrab7967.runStanding8019(r15),
            structureBroken = r60?.brokeStructure == true)
        if (continuation.size > MAX_STATE_KEYS_8153 && !continuation.containsKey(key)) continuation.clear()
        if (continuation.put(key, c) != c) count8153("RUNNER_CONTINUATION_${c.name}_8153")
    }

    /** ExitTruthArbiterLive8144.inputsFor8144: a runner whose structure stands and is not distributing. */
    fun continuationConfirmed8153(ts: TokenState): Boolean =
        ts.position.isOpen && continuation[key8153(ts)]?.let { RunnerPlan8153.continuationConfirmed8153(it) } == true

    // ── rung lifecycle ──

    private fun ladderOf8153(plan: RunnerPlan8153.Plan8153): String =
        if (plan == RunnerPlan8153.Plan8153.SCALP) RunnerRungBook8153.LADDER_SCALP_8153 else RunnerRungBook8153.LADDER_RUNNER_8153

    /** SpikeCapture7943: the ladder the tiers in use belong to (the runner ladder, or the scalp / learned one). */
    fun ladderFor8153(runnerTiers: Boolean): String =
        if (runnerTiers) RunnerRungBook8153.LADDER_RUNNER_8153 else RunnerRungBook8153.LADDER_SCALP_8153

    fun confirmedTier8153(ts: TokenState, ladder: String): Int { ensureLoaded8153(); return book.confirmedTier8153(key8153(ts), ladder) }

    /** True when no tranche of [ts] is pending any more (none, or it just expired without a sale in flight). */
    fun settlePending8153(ts: TokenState, inFlight: Boolean, nowMs: Long): Boolean {
        val free = book.pending8153(key8153(ts)) == null
        if (free) return true
        val settled = book.settle8153(key8153(ts), inFlight, nowMs)
        if (settled) count8153("SPIKE_TRANCHE_EXPIRED_RETRYABLE_8153")
        return settled
    }

    fun request8153(ts: TokenState, ladder: String, tier: Int, reason: String, nowMs: Long): Boolean {
        ensureLoaded8153()
        return book.request8153(key8153(ts), ladder, tier, reason, nowMs)
    }

    fun advance8153(ts: TokenState, stage: RunnerRungBook8153.Stage8153, nowMs: Long): Boolean = book.advance8153(key8153(ts), stage, nowMs)

    fun revert8153(ts: TokenState, why: RunnerRungBook8153.Revert8153, nowMs: Long, slowAfter: Int = 16) {
        if (book.revert8153(key8153(ts), why, nowMs, slowAfter) != null) count8153("SPIKE_TRANCHE_${why.name}_NOT_CONSUMED_8153")
    }

    /** Pure: the spike / runner rung a reason sells (1-based), or 0. */
    fun rungOf8153(reason: String): Int = SPIKE_RUNG_8153.find(reason)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..16 } ?: 0

    /** SpikeCapture7943.onConfirmedPartial8081 / paper receipt: the tier's sale is confirmed (no proceeds read here). */
    fun confirmTier8153(ts: TokenState, tier: Int) {
        if (tier <= 0 || !ts.position.isOpen) return
        ensureLoaded8153()
        book.confirmAppliedTier8198(key8153(ts), ladderOf8153(plan8153(ts)), tier)
        save8153()
    }

    /**
     * Executor.commitVerifiedLiveSlice7835: a slice of [ts] filled on chain in [signature], consuming [consumedRaw] for
     * [proceedsSol] against [soldCostSol] of cost basis. A spike / runner rung is CONFIRMED here (authoritative fill
     * accounting) and every slice's proceeds are booked once per signature (gross opportunity vs net banked).
     */
    fun bookSlice8153(ts: TokenState, reason: String, signature: String, consumedRaw: java.math.BigInteger, proceedsSol: Double, soldCostSol: Double) {
        if (consumedRaw.signum() <= 0) return
        ensureLoaded8153()
        val tier = rungOf8153(reason)
        if (book.confirm8153(key8153(ts), ladderOf8153(plan8153(ts)), tier, signature, proceedsSol, soldCostSol)) {
            count8153("SLICE_PROCEEDS_BOOKED_8153")
        }
        save8153()
    }

    /** TailBag8034.onClose8034: the position entered near [entryMs] of [mint] closed — its rungs retire to the closed totals. */
    fun retire8153(mint: String, entryMs: Long) {
        if (mint.isBlank()) return
        ensureLoaded8153()
        val keys = book.keys8153().filter { k ->
            k.startsWith("$mint|") && kotlin.math.abs((k.substringAfter('|').toLongOrNull() ?: Long.MIN_VALUE / 2) - entryMs) < 120_000L
        }
        var any = false
        for (k in keys) { if (book.retire8153(k)) any = true; continuation.remove(k); lastVerdict.remove(k) }
        if (any) save8153()
    }

    fun statusLine8153(): String {
        ensureLoaded8153()
        val top = counts.entries.sortedByDescending { it.value.get() }.take(6).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return book.statusLine8153() + " continuing=${continuation.values.count { RunnerPlan8153.continuationConfirmed8153(it) }} · [$top]"
    }
}
