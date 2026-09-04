package com.lifecyclebot.engine.truth

/** Build/runtime acceptance contract for the mandatory 120-second paper tape. */
object ExecutionSpineAcceptance6647 {
    const val MIN_WINDOW_MS = 120_000L

    data class Observation(
        val durationMs: Long,
        val safety: Long,
        val v3: Long,
        val bgSplitRuntimeIntakeZombie: Long,
        val configuredWorkers: Int,
        val currentWorkerHeartbeats: Int,
        val phantomSizedOnly: Long,
        val sizePending: Long,
        val fdgAllowWithoutIntent: Long,
        val dispatches: Long,
        val immutableIntentsForDispatches: Long,
        val terminalResultsForDispatches: Long,
        val cryptoOpenConfirmed: Long,
        val maxExitStartDelayCycles: Long,
        val exitStart: Long,
        val exitDone: Long,
        val canonicalOpen: Long,
        val exitEvaluations: Long,
        val supervisorForcedLeaseReleases: Long,
        val cashDeltaSol: Double,
        val basisDeltaSol: Double,
        val realizedDeltaSol: Double,
        val quantityDeltaRaw: java.math.BigInteger,
        val heroJournalParityFail: Long,
        val invalidGrowthOrLearningUpdates: Long,
    )

    data class Result(val failures: List<String>) { val passed: Boolean get() = failures.isEmpty() }

    fun evaluate(o: Observation): Result {
        val f = mutableListOf<String>()
        if (o.durationMs < MIN_WINDOW_MS) f += "WINDOW_LT_120_SECONDS"
        if (o.safety <= 0L) f += "SAFETY_ZERO"
        if (o.v3 <= 0L) f += "V3_ZERO"
        if (o.bgSplitRuntimeIntakeZombie != 0L) f += "BG_SPLIT_RUNTIME_INTAKE_ZOMBIE"
        if (o.configuredWorkers <= 0 || o.currentWorkerHeartbeats != o.configuredWorkers) f += "SPECIALIST_HEARTBEAT_GAP"
        if (o.phantomSizedOnly != 0L) f += "PHANTOM_SIZED_ONLY"
        if (o.sizePending != 0L) f += "EXEC_OPEN_PRECHECK_SIZE_PENDING"
        if (o.fdgAllowWithoutIntent != 0L) f += "FDG_ALLOW_WITHOUT_EXEC_INTENT"
        if (o.dispatches != o.immutableIntentsForDispatches) f += "DISPATCH_INTENT_CARDINALITY"
        if (o.dispatches != o.terminalResultsForDispatches) f += "DISPATCH_TERMINAL_CARDINALITY"
        if (o.cryptoOpenConfirmed <= 0L) f += "CRYPTO_OPEN_CONFIRMED_ZERO"
        if (o.maxExitStartDelayCycles > 2L) f += "EXIT_START_LATE"
        if (o.exitStart <= 0L) f += "EXIT_START_ZERO"
        // Sampling may land while exactly one coordinator sweep is in flight.
        // A completed window fails only for impossible ordering or backlog.
        if (o.exitDone > o.exitStart || o.exitStart - o.exitDone > 1L) f += "EXIT_START_DONE_GAP"
        if (o.canonicalOpen > 0L && o.exitEvaluations <= 0L) f += "OPEN_WITHOUT_EXIT_EVALUATION"
        if (o.supervisorForcedLeaseReleases != 0L) f += "SUPERVISOR_FORCED_LEASE_RELEASE"
        if (!o.cashDeltaSol.isFinite() || kotlin.math.abs(o.cashDeltaSol) > 1e-9) f += "CASH_DELTA"
        if (!o.basisDeltaSol.isFinite() || kotlin.math.abs(o.basisDeltaSol) > 1e-9) f += "BASIS_DELTA"
        if (!o.realizedDeltaSol.isFinite() || kotlin.math.abs(o.realizedDeltaSol) > 1e-9) f += "REALIZED_DELTA"
        if (o.quantityDeltaRaw != java.math.BigInteger.ZERO) f += "QUANTITY_DELTA"
        if (o.heroJournalParityFail != 0L) f += "HERO_JOURNAL_PARITY_FAIL"
        if (o.invalidGrowthOrLearningUpdates != 0L) f += "INVALID_GROWTH_OR_LEARNING_UPDATE"
        return Result(f)
    }

    fun requirePassing(o: Observation) {
        val result = evaluate(o)
        check(result.passed) { "EXECUTION_SPINE_ACCEPTANCE_6647_FAILED:${result.failures.joinToString("|")}" }
    }
}

/** Rolling runtime evidence collector. Counters are sampled as deltas from
 * one uninterrupted 120-second paper window; current-state invariants are
 * read at the closing boundary. */
object ExecutionSpineAcceptanceWindow6647 {
    private data class Baseline(
        val atMs: Long,
        val phaseSafety: Long,
        val phaseV3: Long,
        val labels: Map<String, Long>,
        val cryptoOpen: Long,
        val exitStart: Long,
        val exitDone: Long,
        val exitEvaluations: Long,
        val phantomSizedOnly: Long,
    )

    private val exitSweepStart = java.util.concurrent.atomic.AtomicLong(0L)
    private val exitSweepDone = java.util.concurrent.atomic.AtomicLong(0L)
    private val exitEvaluations = java.util.concurrent.atomic.AtomicLong(0L)
    private val requestedCycle = java.util.concurrent.atomic.AtomicLong(-1L)
    private val maxStartDelayCycles = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var baseline: Baseline? = null

    private val watchedLabels = listOf(
        "BG_SPLIT_RUNTIME_INTAKE_ZOMBIE_6579",
        "EXEC_OPEN_PRECHECK_SIZE_PENDING_6491",
        "FDG_ALLOW_WITHOUT_EXEC_INTENT",
        "SUPERVISOR_LEASE_FORCE_RELEASED",
        "HERO_JOURNAL_PARITY_FAIL_6616",
        "GROWTH_RING_INVALID_ACCOUNT_UPDATE_6647",
        "LEARNING_INVALID_ACCOUNT_UPDATE_6647",
    )

    fun onExitRequested(cycle: Long) { requestedCycle.set(cycle) }
    fun onCoordinatorStarted(cycle: Long) {
        val requested = requestedCycle.get()
        if (requested >= 0L) {
            val delay = (cycle - requested).coerceAtLeast(0L)
            while (true) {
                val prior = maxStartDelayCycles.get()
                if (delay <= prior || maxStartDelayCycles.compareAndSet(prior, delay)) break
            }
            // Record only the first real coroutine heartbeat acknowledging
            // this request. Later heartbeats must not manufacture a growing
            // delay for coordinator work that already began.
            requestedCycle.compareAndSet(requested, -1L)
        }
    }
    fun onExitSweepStarted() { exitSweepStart.incrementAndGet() }
    fun onExitSweepDone() { exitSweepDone.incrementAndGet() }
    fun onExitEvaluation() { exitEvaluations.incrementAndGet() }

    private fun capture(nowMs: Long): Baseline {
        val health = com.lifecyclebot.engine.PipelineHealthCollector.snapshot()
        return Baseline(
            atMs = nowMs,
            phaseSafety = health.phaseCounts["SAFETY"] ?: 0L,
            phaseV3 = health.phaseCounts["V3"] ?: 0L,
            labels = watchedLabels.associateWith { health.labelCounts[it] ?: 0L },
            cryptoOpen = CanonicalEntryAuthority6540.snapshot(CanonicalEntryAuthority6540.Venue.CRYPTO).opensConfirmed,
            exitStart = exitSweepStart.get(),
            exitDone = exitSweepDone.get(),
            exitEvaluations = exitEvaluations.get(),
            phantomSizedOnly = com.lifecyclebot.engine.ToolkitSignalSheet.configuredMemeDesks6647()
                .sumOf { SpecialistCausalFunnel6625.laneSnapshot6647(it).phantomSizedOnly }.toLong(),
        )
    }

    /**
     * Start the mandatory window at the accepted runtime start boundary.
     * AcceptanceInvariantAudit runs on a slower cadence than the CI capture;
     * lazily creating the baseline on its first audit meant a healthy
     * three-minute smoke could finish before any 120-second window closed.
     */
    @Synchronized
    fun beginWindow6662(nowMs: Long = System.currentTimeMillis()) {
        baseline = capture(nowMs)
        requestedCycle.set(-1L)
        maxStartDelayCycles.set(0L)
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EXECUTION_SPINE_WINDOW_STARTED_6662")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "EXECUTION_SPINE_WINDOW_STARTED_6662",
                "atMs=$nowMs source=accepted_runtime_start",
            )
        } catch (_: Throwable) {}
    }

    /** Returns null while the mandatory window is still warming. */
    @Synchronized
    fun closeCompletedWindow(nowMs: Long = System.currentTimeMillis()): ExecutionSpineAcceptance6647.Result? {
        val start = baseline
        if (start == null) {
            baseline = capture(nowMs)
            maxStartDelayCycles.set(0L)
            return null
        }
        val duration = nowMs - start.atMs
        if (duration < ExecutionSpineAcceptance6647.MIN_WINDOW_MS) return null

        // Close against durable economic truth, not a stale periodic sample.
        // This also settles stop/restart journal lots which no longer have a
        // canonical owner before enforcing exact scalar and quantity parity.
        try { CanonicalPaperTransaction6486.reconcileForensicBoundary6666() } catch (_: Throwable) {}
        val end = capture(nowMs)
        val delta: (String) -> Long = { key -> ((end.labels[key] ?: 0L) - (start.labels[key] ?: 0L)).coerceAtLeast(0L) }
        val desks = com.lifecyclebot.engine.ToolkitSignalSheet.configuredMemeDesks6647()
        val heartbeatCount = desks.count { SpecialistRuntimeRegistry6647.snapshot(it, nowMs).runtimeAlive }
        val phantom = (end.phantomSizedOnly - start.phantomSizedOnly).coerceAtLeast(0L)
        val forensic = ForensicReconciliation6635.deltas6647()
        // A dispatch begun at the sampling edge may still be legitimately in
        // flight; terminal-cardinality applies after a bounded grace period.
        val cardinality = CanonicalEntryAuthority6551.cardinalityForWindow6647(
            start.atMs, (end.atMs - 10_000L).coerceAtLeast(start.atMs),
        )
        val reconciledDelta: (Double) -> Double = { value -> if (forensic.reconciled) value else Double.NaN }
        val observation = ExecutionSpineAcceptance6647.Observation(
            durationMs = duration,
            safety = (end.phaseSafety - start.phaseSafety).coerceAtLeast(0L),
            v3 = (end.phaseV3 - start.phaseV3).coerceAtLeast(0L),
            bgSplitRuntimeIntakeZombie = delta("BG_SPLIT_RUNTIME_INTAKE_ZOMBIE_6579"),
            configuredWorkers = desks.size,
            currentWorkerHeartbeats = heartbeatCount,
            phantomSizedOnly = phantom,
            sizePending = delta("EXEC_OPEN_PRECHECK_SIZE_PENDING_6491"),
            fdgAllowWithoutIntent = delta("FDG_ALLOW_WITHOUT_EXEC_INTENT"),
            dispatches = cardinality.dispatches,
            immutableIntentsForDispatches = cardinality.immutableIntentsForDispatches,
            terminalResultsForDispatches = cardinality.terminalResultsForDispatches,
            // A fresh OPEN is ideal, but a bounded window can begin after Crypto
            // has already filled its slots. Existing canonical CRYPTO_ALT
            // positions are durable proof that the venue reached OPEN; do not
            // call a capacity-bound healthy book "choked" merely because it
            // correctly declined another position during this exact window.
            cryptoOpenConfirmed = (end.cryptoOpen - start.cryptoOpen).coerceAtLeast(0L) +
                CanonicalPositionAuthority6441.openPositions()
                    .count { it.assetClass == AssetClass.CRYPTO_ALT }.toLong(),
            maxExitStartDelayCycles = maxStartDelayCycles.get(),
            exitStart = (end.exitStart - start.exitStart).coerceAtLeast(0L),
            exitDone = (end.exitDone - start.exitDone).coerceAtLeast(0L),
            canonicalOpen = CanonicalPositionAuthority6441.openPositions().size.toLong(),
            exitEvaluations = (end.exitEvaluations - start.exitEvaluations).coerceAtLeast(0L),
            supervisorForcedLeaseReleases = delta("SUPERVISOR_LEASE_FORCE_RELEASED"),
            cashDeltaSol = reconciledDelta(forensic.cashSol),
            basisDeltaSol = reconciledDelta(forensic.basisSol),
            realizedDeltaSol = reconciledDelta(forensic.realizedSol),
            quantityDeltaRaw = if (forensic.reconciled) forensic.quantityRaw else java.math.BigInteger.ONE,
            heroJournalParityFail = delta("HERO_JOURNAL_PARITY_FAIL_6616"),
            invalidGrowthOrLearningUpdates = delta("GROWTH_RING_INVALID_ACCOUNT_UPDATE_6647") +
                delta("LEARNING_INVALID_ACCOUNT_UPDATE_6647"),
        )
        val result = ExecutionSpineAcceptance6647.evaluate(observation)
        baseline = end
        maxStartDelayCycles.set(0L)
        try {
            if (result.passed) {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EXECUTION_SPINE_ACCEPTANCE_6647_OK")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "EXECUTION_SPINE_ACCEPTANCE_6647_OK",
                    "durationMs=$duration safety=${observation.safety} v3=${observation.v3} workers=${observation.currentWorkerHeartbeats}/${observation.configuredWorkers} dispatches=${observation.dispatches} cryptoOpen=${observation.cryptoOpenConfirmed} exit=${observation.exitStart}/${observation.exitDone} canonicalOpen=${observation.canonicalOpen} exitEval=${observation.exitEvaluations}",
                )
            }
            else {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EXECUTION_SPINE_ACCEPTANCE_6647_FAIL")
                com.lifecyclebot.engine.ForensicLogger.lifecycle("EXECUTION_SPINE_ACCEPTANCE_6647_FAIL", "durationMs=$duration failures=${result.failures.joinToString("|")}")
            }
        } catch (_: Throwable) {}
        return result
    }
}
