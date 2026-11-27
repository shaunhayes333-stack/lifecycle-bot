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
    private val SPIKE_RUNG_8153 = Regex("(?:SPIKE_CAPTURE_7943|SCALP_TAKE_PROFIT_8351)_T([0-9]+)")

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

    internal fun positionKey9043(ts: TokenState): String = RunnerIdentity9043.positionKey(
        ts.mint, ts.position.entryTime, ts.position.isPaperPosition, ts.position.positionId)

    private fun key8153(ts: TokenState): String {
        val key = positionKey9043(ts)
        val legacy = "${ts.mint}|${ts.position.entryTime}"
        if (book.containsKey9043(legacy) && ts.position.positionId.isNotBlank()) {
            val unique = try {
                com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions()
                    .filter { it.mint == ts.mint && it.openedAtMs == ts.position.entryTime }
                    .singleOrNull()?.let { it.positionId == ts.position.positionId && it.mode.equals(if (ts.position.isPaperPosition) "PAPER" else "LIVE", true) } == true
            } catch (_: Throwable) { false }
            if (book.migrateLegacy9043(legacy, key, unique)) save8153()
        }
        return key
    }

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
        // Capability includes lanes that can earn a promotion after entry. The narrower born-runner entry contract
        // excluded MANIPULATED, DIP_HUNTER and CORE here, so even a verified peak or durable promotion stayed SCALP.
        // The plan still requires the sealed admission or existing promotion evidence; capability alone admits nothing.
        val hosts = RunnerExitProfile7277.isRunnerLane(com.lifecyclebot.engine.truth.LiveRiskPolicy7807.canonicalLane(pos.tradingMode))
        val grabbed = try { RunnerGrab7967.grabbedAtEntry8153(ts) } catch (_: Throwable) { false }
        val runnerAdmit = snap?.takeIf { it.mint == ts.mint }?.runnerEntry == true
        ensureLoaded8153()
        return RunnerPlan8153.Evidence8153(
            laneHostsRunners = hosts, sealedStyle = style, sealedStrategy = strategy, grabbedAtEntry = grabbed,
            promotedLatched = book.promoted8153(key8153(ts)), verifiedPeakPct = pos.peakGainPct,
            runnerEntryAdmitted = runnerAdmit,
            contractLane8468 = RunnerExitProfile7277.contractLane8468(pos.tradingMode),
        )
    }

    fun plan8153(ts: TokenState): RunnerPlan8153.Plan8153 {
        if (!ts.position.isOpen || ts.position.entryTime <= 0L) return RunnerPlan8153.Plan8153.SCALP
        val e = evidence8153(ts)
        val plan = RunnerPlan8153.plan8153(e)
        // V5.0.8254 — runner funnel: the admitted runner entry became a live runner plan (once per position).
        if (plan == RunnerPlan8153.Plan8153.RUNNER_SEALED && e.runnerEntryAdmitted && planActive8254.putIfAbsent(key8153(ts), true) == null) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("RUNNER_FUNNEL_8254_PLAN_ACTIVE") } catch (_: Throwable) {}
        }
        // V5.0.8331 — the runner funnel (once per live position): held in a lane that can host a runner; its plan sealed.
        if (!ts.position.isPaperPosition && e.laneHostsRunners) {
            RunnerFunnel8331.stage8331(RunnerFunnel8331.Stage8331.HELD, key8153(ts))
            if (plan != RunnerPlan8153.Plan8153.SCALP) RunnerFunnel8331.stage8331(RunnerFunnel8331.Stage8331.PLAN_SEALED, key8153(ts))
        }
        return plan
    }

    private val planActive8254 = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

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
        if (settled) { count8153("SPIKE_TRANCHE_EXPIRED_RETRYABLE_8153"); save8153() }
        return settled
    }

    fun request8153(ts: TokenState, ladder: String, tier: Int, reason: String, nowMs: Long): Boolean {
        ensureLoaded8153()
        // V5.0.8507 — the funnel's RUNG_TRIGGERED / RUNG_ARMED stages had no writer (8499-8506: always 0, the ladder read
        // as dead). Triggered = the price reached a runner tier and asked for its sale; armed = the book accepted it.
        val runner8507 = !ts.position.isPaperPosition && ladder == RunnerRungBook8153.LADDER_RUNNER_8153 && tier > 0
        if (runner8507) RunnerFunnel8331.stage8331(RunnerFunnel8331.Stage8331.RUNG_TRIGGERED, "${key8153(ts)}|T$tier")
        val ok = book.request8153(key8153(ts), ladder, tier, reason, nowMs)
        if (runner8507 && ok) RunnerFunnel8331.stage8331(RunnerFunnel8331.Stage8331.RUNG_ARMED, "${key8153(ts)}|T$tier")
        if (ok) save8153()
        return ok
    }

    fun advance8153(ts: TokenState, stage: RunnerRungBook8153.Stage8153, nowMs: Long, expectedReason8397: String? = null, expectedRequestAtMs8397: Long? = null): Boolean {
        val key = key8153(ts)
        val advanced = book.advance8153(key, stage, nowMs, expectedReason8397, expectedRequestAtMs8397)
        // A ticket is local ownership, not evidence that a transaction reached the network.
        if (advanced && stage == RunnerRungBook8153.Stage8153.TICKET_ACCEPTED && !ts.position.isPaperPosition) {
            val p = book.pending8153(key)
            if (p != null && p.ladder == RunnerRungBook8153.LADDER_RUNNER_8153) RunnerFunnel8331.stage8331(RunnerFunnel8331.Stage8331.TICKET_ACCEPTED, "$key|T${p.tier}")
        }
        if (advanced) save8153()
        return advanced
    }

    fun revert8153(ts: TokenState, why: RunnerRungBook8153.Revert8153, nowMs: Long, slowAfter: Int = 16): Boolean {
        val reverted = book.revert8153(key8153(ts), why, nowMs, slowAfter) != null
        if (reverted) { count8153("SPIKE_TRANCHE_${why.name}_NOT_CONSUMED_8153"); save8153() }
        return reverted
    }

    fun pendingRequest8397(ts: TokenState, reason: String): RunnerRungBook8153.Pending8153? {
        ensureLoaded8153()
        return book.pending8153(key8153(ts))?.takeIf { it.reason == reason }
    }

    fun acceptDispatch8397(ts: TokenState, reason: String, requestedAtMs: Long, nowMs: Long, expectedLadder8397: String? = null): Boolean {
        val key = key8153(ts)
        val accepted = book.acceptDispatch8397(key, reason, requestedAtMs, nowMs, expectedLadder8397)
        if (accepted && !ts.position.isPaperPosition) book.pending8153(key)?.let { p ->
            if (p.ladder == RunnerRungBook8153.LADDER_RUNNER_8153)
                RunnerFunnel8331.stage8331(RunnerFunnel8331.Stage8331.TICKET_ACCEPTED, "$key|T${p.tier}")
        }
        if (accepted) save8153()
        return accepted
    }

    fun revertMatching8397(ts: TokenState, reason: String, requestedAtMs: Long, nowMs: Long, slowAfter: Int = 16,
                           expectedLadder8397: String? = null): Boolean {
        val reverted = book.revertMatching8397(key8153(ts), reason, requestedAtMs, nowMs, slowAfter, expectedLadder8397)
        if (reverted) { count8153("SPIKE_TRANCHE_REJECTED_NOT_CONSUMED_8153"); save8153() }
        return reverted
    }

    fun remainingTier9043(ts: TokenState, ladder: String): Int? = book.remainder9043(key8153(ts), ladder)?.tier

    fun capRequestedRaw9043(ts: TokenState, reason: String, raw: Long): Long {
        val p = pendingRequest8397(ts, reason) ?: return raw
        return if (p.requestedRaw.signum() > 0) java.math.BigInteger.valueOf(raw).min(p.requestedRaw).longValueExact() else raw
    }

    /** Called at the exact live send boundary, after sizing, on the same request the dispatch adapter accepted. */
    fun sellIntent9043(ts: TokenState, reason: String, raw: Long, route: String): com.lifecyclebot.network.SignedTxLedger8085.Intent {
        val p = pendingRequest8397(ts, reason)
        val rung = rungOf8153(reason) > 0
        if (rung) check(p != null && p.stage == RunnerRungBook8153.Stage8153.TICKET_ACCEPTED && !ts.position.isPaperPosition && raw > 0L) {
            "RUNG_REQUEST_NOT_OWNED_9043"
        }
        // Unrelated protective/ordinary exits retain the ledger's existing canonical owner resolver.
        if (!rung) return com.lifecyclebot.network.SignedTxLedger8085.Intent(ts.mint, "SELL", rawQty = raw, route = route)
        val id = if (p != null && rung) RunnerIdentity9043.Request(key8153(ts), p.ladder, p.tier, p.atMs, p.reason).attemptId else ""
        return com.lifecyclebot.network.SignedTxLedger8085.Intent(ts.mint, "SELL", raw, ts.position.positionId, id, route)
    }

    /** SignedTxLedger has already persisted the exact identity; this must succeed before it permits network submission. */
    fun signed9043(e: com.lifecyclebot.network.SignedTxLedger8085.Entry): Boolean {
        val request = RunnerIdentity9043.parse(e.intentId) ?: return !e.intentId.startsWith("RUNG9043:")
        if (e.side != "SELL" || RunnerIdentity9043.mode(request.positionKey) != "LIVE") return false
        ensureLoaded8153()
        val ok = book.signed9043(request, e.signature, java.math.BigInteger.valueOf(e.rawQty), e.createdAtMs)
        if (ok) save8153()
        return ok
    }

    /** Recovery reads the persisted request, never whichever position happens to reuse this mint now. */
    fun recovered9043(e: com.lifecyclebot.network.SignedTxLedger8085.Entry) {
        val request = RunnerIdentity9043.parse(e.intentId) ?: return
        if (e.side != "SELL" || com.lifecyclebot.network.SignedTxLedger8085.isBooked8418(e)) return
        ensureLoaded8153()
        book.signed9043(request, e.signature, java.math.BigInteger.valueOf(e.rawQty), e.createdAtMs, recovering = true)
        ledgerState9043(e)
        save8153()
    }

    fun ledgerState9043(e: com.lifecyclebot.network.SignedTxLedger8085.Entry) {
        val request = RunnerIdentity9043.parse(e.intentId) ?: return
        if (e.side != "SELL") return
        ensureLoaded8153()
        val sent = e.submittedAtMs8087 > 0L && book.submitted9043(request, e.signature, e.submittedAtMs8087)
        if (sent && request.ladder == RunnerRungBook8153.LADDER_RUNNER_8153)
            RunnerFunnel8331.stage8331(RunnerFunnel8331.Stage8331.SELL_SENT, "${request.positionKey}|T${request.tier}")
        if (e.state in setOf(com.lifecyclebot.network.SignedTxLedger8085.State.FAILED_ON_CHAIN,
                com.lifecyclebot.network.SignedTxLedger8085.State.EXPIRED_PROVEN,
                com.lifecyclebot.network.SignedTxLedger8085.State.SUBMIT_REJECTED)) {
            if (book.failed9043(request, e.signature, e.updatedAtMs)) {
                try { ExitTruthArbiterLive8144.releaseTranche8144(e.mint, request.reason) } catch (_: Throwable) {}
                count8153("SPIKE_SIGNED_FAILURE_RETRYABLE_9043")
            }
        }
        save8153()
    }

    fun rungReasonForSignature9043(signature: String): String? =
        com.lifecyclebot.network.SignedTxLedger8085.get(signature)?.let { RunnerIdentity9043.parse(it.intentId)?.reason }

    /** Pure: the spike / runner rung a reason sells (1-based), or 0. */
    fun rungOf8153(reason: String): Int = SPIKE_RUNG_8153.find(reason)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..16 } ?: 0

    /** SpikeCapture7943.onConfirmedPartial8081 / paper receipt: the tier's sale is confirmed (no proceeds read here). */
    fun confirmTier8153(ts: TokenState, tier: Int, reason: String = "", expectedLadder8397: String? = null) {
        if (tier <= 0 || !ts.position.isOpen) return
        ensureLoaded8153()
        val ladder = expectedLadder8397 ?: if (reason.startsWith("SCALP_TAKE_PROFIT_8351_T")) RunnerRungBook8153.LADDER_SCALP_EXIT_8394
            else book.pending8153(key8153(ts))?.takeIf { it.tier == tier && it.ladder != RunnerRungBook8153.LADDER_SCALP_EXIT_8394 }?.ladder ?: ladderOf8153(plan8153(ts))
        if (book.remainder9043(key8153(ts), ladder)?.tier == tier) return // an applied underfill is not a completed rung
        book.confirmAppliedTier8198(key8153(ts), ladder, tier)
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
        val signedOwner = com.lifecyclebot.network.SignedTxLedger8085.get(signature)?.takeIf { it.side == "SELL" && it.mint == ts.mint }
            ?.let { RunnerIdentity9043.parse(it.intentId) }
        val key = signedOwner?.positionKey ?: key8153(ts)
        val tier = signedOwner?.tier ?: rungOf8153(reason)
        // V5.0.8331 — the fill confirms the ladder its tranche was REQUESTED on (the pending tranche's), else the plan's:
        // the request ladder and the plan ladder could differ (RunnerGrab7967.tiersFor7967 vs RunnerPlan8153).
        val pending8331 = book.pending8153(key)
        val ladder8331 = signedOwner?.ladder ?: if (reason.startsWith("SCALP_TAKE_PROFIT_8351_T")) RunnerRungBook8153.LADDER_SCALP_EXIT_8394
            else if (pending8331 != null && pending8331.tier == tier && pending8331.ladder != RunnerRungBook8153.LADDER_SCALP_EXIT_8394) pending8331.ladder else ladderOf8153(plan8153(ts))
        if (book.confirm8153(key, ladder8331, tier, signature, proceedsSol, soldCostSol, consumedRaw)) {
            count8153("SLICE_PROCEEDS_BOOKED_8153")
            if (RunnerIdentity9043.mode(key) == "LIVE" && ladder8331 == RunnerRungBook8153.LADDER_RUNNER_8153 && tier > 0) RunnerFunnel8331.stage8331(RunnerFunnel8331.Stage8331.PROCEEDS_BOOKED, "$key|T$tier")
        }
        if (RunnerIdentity9043.mode(key) == "LIVE" && ladder8331 == RunnerRungBook8153.LADDER_RUNNER_8153 && tier > 0) RunnerFunnel8331.stage8331(RunnerFunnel8331.Stage8331.SELL_LANDED, "$key|T$tier")
        save8153()
    }

    /** Retire the closed lot's exact entry key; nearby re-entries retain their earned runner state. */
    fun retire8153(mint: String, entryMs: Long, positionId: String? = null) {
        if (mint.isBlank()) return
        ensureLoaded8153()
        val key = if (positionId != null) {
            val lot = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.getPosition(positionId) ?: return
            if (lot.mint != mint || lot.lifecycle != com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.Lifecycle.CLOSED ||
                lot.remainingQtyRaw.signum() != 0) return
            val exact = RunnerIdentity9043.positionKey(mint, lot.openedAtMs, lot.mode.equals("PAPER", true), positionId)
            if (book.containsKey9043(exact)) exact else {
                // An old unattributed key can be retired only without another owner of the same clock.
                if (com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions().any {
                        it.mint == mint && it.openedAtMs == lot.openedAtMs && it.positionId != positionId }) return
                "$mint|${lot.openedAtMs}"
            }
        } else {
            if (entryMs <= 0L) return
            book.keys8153().filter { it == "$mint|$entryMs" || it.startsWith("$mint|$entryMs|") }.singleOrNull() ?: return
        }
        val retired = book.retire8153(key)
        continuation.remove(key)
        lastVerdict.remove(key)
        planActive8254.remove(key)
        if (retired) save8153()
    }

    fun statusLine8153(): String {
        ensureLoaded8153()
        val top = counts.entries.sortedByDescending { it.value.get() }.take(6).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return book.statusLine8153() + " continuing=${continuation.values.count { RunnerPlan8153.continuationConfirmed8153(it) }} · [$top] · " +
            RunnerFunnel8331.statusLine8331()  // V5.0.8331 — where a held runner stops
    }
}
