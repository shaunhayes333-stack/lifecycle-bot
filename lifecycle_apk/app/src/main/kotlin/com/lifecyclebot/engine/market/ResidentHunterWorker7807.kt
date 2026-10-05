package com.lifecyclebot.engine.market

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7807 — RESIDENT MARKET SWEEP + LANE HUNTERS.
 *
 * Root cause of 5.0.7805 "Market sweep: no sweep yet", every lane
 * "hunted=0 claims=0" and "OPPORTUNITY_INTELLIGENCE candidates=0 rebuilds=0":
 * MarketSweep7297.sweep() (seven providers, 26 s per-provider ceiling, 25 s
 * OkHttp callTimeout, then a Jupiter/DexScreener cap-enrichment pass) was
 * invoked ONLY as the "scanMarketSweep7297" source inside
 * SolanaMarketScanner.runScanBatch, where every source is wrapped in
 * SOURCE_SCAN_TIMEOUT_MS = 5 s and the whole batch in SCAN_BATCH_BUDGET_MS =
 * 8 s. A sweep that needs 1-25 s per provider is cancelled every cycle before
 * `last` is assigned, so the snapshot stayed null, LaneHunter7297.hunt() was
 * never reached (no claims, no SpecialistCandidateBooks7803 hunts), and the
 * opportunity rebuild that lives at the tail of sweep() never ran.
 *
 * This worker owns the sweep instead: its own coroutine started with the bot
 * and cancelled with it, its own cadence (independent of the scanner batch
 * wall clock), single-flight, a hard budget around the sweep and around the
 * hand-off, and exactly one caller of sweep() (the scanner no longer calls
 * it), so there is no duplicate provider fan-out. It has no entry authority:
 * it produces lane claims/candidate books and hands hunted rows to the
 * existing intake (scanner emit -> watchlist -> processTokenCycle -> election)
 * and to the Crypto Universe discovery registry.
 *
 * Field Manual L337 — one shared evidence snapshot per candidate, each
 * specialist interprets it. Field Manual L412 — discovery must not compete
 * with the hot loop for its time budget.
 */
object ResidentHunterWorker7807 {

    private const val CADENCE_MS_7807 = 45_000L
    private const val FIRST_RUN_DELAY_MS_7807 = 3_000L
    /** Hard ceiling for one whole sweep (providers run in parallel, each already capped). */
    const val SWEEP_BUDGET_MS_7807 = 70_000L
    /** Hard ceiling for handing one hunt to the scanner intake. */
    const val EMIT_BUDGET_MS_7807 = 40_000L
    /** Crypto desk overlays receive at most this many hunted rows per lane per sweep. */
    private const val CRYPTO_HANDOFF_PER_LANE_7807 = 4

    /** Lanes the Crypto Universe desk (CryptoLaneDesk7391) can elect. CASHGEN folds into TREASURY there. */
    internal val CRYPTO_DESK_LANES_7807 = setOf(
        "CORE", "EXPRESS", "DIP_HUNTER", "TREASURY", "CASHGEN", "QUALITY", "BLUECHIP", "SHITCOIN", "MOONSHOT",
    )

    @Volatile private var job: Job? = null
    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastHuntedSnapshotAtMs = 0L
    @Volatile private var lastRunAtMs = 0L
    @Volatile private var lastDurMs = 0L
    @Volatile private var lastEmitted = 0
    @Volatile private var lastError = ""
    private val runs = AtomicLong(0L)
    private val hunts = AtomicLong(0L)
    private val sweepEmpty = AtomicLong(0L)
    private val emitTimeouts = AtomicLong(0L)
    private val skippedInFlight = AtomicLong(0L)
    private val cryptoHandoffs = AtomicLong(0L)
    private val errors = AtomicLong(0L)

    /**
     * Start (or restart) the resident worker on [scope]. [keys] returns
     * (heliusKey, jupiterKey) on every run so config edits apply without a
     * restart. [emitter] hands a hunt to the meme intake; it is read lazily so
     * a self-healed scanner instance is picked up.
     */
    fun start(
        scope: CoroutineScope,
        keys: () -> Pair<String, String>,
        emitter: suspend (MarketSweep7297.Snapshot, Map<String, List<MarketSweep7297.Row>>) -> Int,
    ) {
        synchronized(this) {
            job?.cancel()
            job = scope.launch(Dispatchers.IO + CoroutineName("resident-hunters-7807")) {
                delay(FIRST_RUN_DELAY_MS_7807)
                while (isActive) {
                    runOnce(keys, emitter)
                    delay(CADENCE_MS_7807)
                }
            }
        }
        try { PipelineHealthCollector.labelInc("RESIDENT_HUNTER_WORKER_STARTED_7807") } catch (_: Throwable) {}
        ErrorLogger.info("ResidentHunter7807", "started cadence=${CADENCE_MS_7807}ms sweepBudget=${SWEEP_BUDGET_MS_7807}ms")
    }

    fun stop(reason: String) {
        val j = synchronized(this) { val cur = job; job = null; cur }
        if (j != null) {
            j.cancel()
            try { PipelineHealthCollector.labelInc("RESIDENT_HUNTER_WORKER_STOPPED_7807") } catch (_: Throwable) {}
            ErrorLogger.info("ResidentHunter7807", "stopped reason=${reason.take(60)}")
        }
    }

    fun isRunning(): Boolean = job?.isActive == true

    /**
     * One sweep -> hunt -> hand-off pass. Single-flight: a call that finds a
     * pass already running returns false without touching any provider.
     */
    suspend fun runOnce(
        keys: () -> Pair<String, String>,
        emitter: suspend (MarketSweep7297.Snapshot, Map<String, List<MarketSweep7297.Row>>) -> Int,
    ): Boolean {
        if (!inFlight.compareAndSet(false, true)) {
            skippedInFlight.incrementAndGet()
            try { PipelineHealthCollector.labelInc("RESIDENT_HUNTER_SKIPPED_IN_FLIGHT_7807") } catch (_: Throwable) {}
            return false
        }
        val started = System.currentTimeMillis()
        try {
            runs.incrementAndGet()
            val k = try { keys() } catch (_: Throwable) { "" to "" }
            val snap = withTimeoutOrNull(SWEEP_BUDGET_MS_7807) { MarketSweep7297.sweep(k.first, k.second) }
            if (snap == null) {
                sweepEmpty.incrementAndGet()
                try { PipelineHealthCollector.labelInc("RESIDENT_HUNTER_SWEEP_EMPTY_OR_TIMEOUT_7807") } catch (_: Throwable) {}
                return true
            }
            if (snap.atMs == lastHuntedSnapshotAtMs) {
                try { PipelineHealthCollector.labelInc("RESIDENT_HUNTER_SNAPSHOT_UNCHANGED_7807") } catch (_: Throwable) {}
                return true
            }
            lastHuntedSnapshotAtMs = snap.atMs
            val picks = LaneHunter7297.hunt(snap)
            hunts.incrementAndGet()
            try { LaneHunter7297.claimMomentum7298() } catch (_: Throwable) {}
            handoffCrypto7807(picks)
            val emitted = withTimeoutOrNull(EMIT_BUDGET_MS_7807) { emitter(snap, picks) }
            if (emitted == null) {
                emitTimeouts.incrementAndGet()
                try { PipelineHealthCollector.labelInc("RESIDENT_HUNTER_EMIT_BUDGET_EXCEEDED_7807") } catch (_: Throwable) {}
            } else {
                lastEmitted = emitted
            }
            try { PipelineHealthCollector.labelInc("RESIDENT_HUNTER_HUNT_DONE_7807") } catch (_: Throwable) {}
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            errors.incrementAndGet()
            lastError = "${t.javaClass.simpleName}:${t.message?.take(60)}"
            ErrorLogger.debug("ResidentHunter7807", "run failed: $lastError")
            return true
        } finally {
            lastRunAtMs = System.currentTimeMillis()
            lastDurMs = lastRunAtMs - started
            inFlight.set(false)
        }
    }

    /**
     * Crypto Universe equivalent of the meme hand-off: rows hunted for a lane
     * the crypto desk can elect are offered to DynamicAltTokenRegistry (its own
     * discovery gate applies) and watched as DESK_<lane> in the resident
     * crypto strategy books. Discovery only; CryptoAltTrader's own cycle still
     * qualifies and readies them.
     */
    internal fun handoffCrypto7807(picks: Map<String, List<MarketSweep7297.Row>>): Int {
        var n = 0
        for ((lane, rows) in picks) {
            if (lane !in CRYPTO_DESK_LANES_7807) continue
            val deskLane = if (lane == "CASHGEN") "TREASURY" else lane
            for (r in rows.take(CRYPTO_HANDOFF_PER_LANE_7807)) {
                val admitted = try {
                    com.lifecyclebot.perps.DynamicAltTokenRegistry.ingestMarketHunt7807(
                        mint = r.mint,
                        symbol = r.symbol,
                        name = r.name,
                        priceUsd = r.priceUsd,
                        liquidityUsd = r.liquidityUsd,
                        volume24hUsd = r.volumeH24Usd,
                        ageHours = r.ageHours,
                    )
                } catch (_: Throwable) { null }
                if (admitted == null) continue
                try {
                    com.lifecyclebot.perps.CryptoStrategyCandidateBooks7803.watch(
                        admitted, r.symbol, "DESK_$deskLane", "MARKET_HUNT_7807",
                    )
                } catch (_: Throwable) {}
                n++
            }
        }
        if (n > 0) {
            cryptoHandoffs.addAndGet(n.toLong())
            try { PipelineHealthCollector.labelInc("RESIDENT_HUNTER_CRYPTO_HANDOFF_7807") } catch (_: Throwable) {}
        }
        return n
    }

    fun statusLine(): String {
        val age = if (lastRunAtMs > 0L) "${(System.currentTimeMillis() - lastRunAtMs) / 1000}s" else "never"
        return "RESIDENT_HUNTER_7807 running=${isRunning()} runs=${runs.get()} hunts=${hunts.get()} " +
            "lastRun=$age durMs=$lastDurMs emitted=$lastEmitted sweepEmpty=${sweepEmpty.get()} " +
            "emitTimeouts=${emitTimeouts.get()} skippedInFlight=${skippedInFlight.get()} " +
            "cryptoHandoffs=${cryptoHandoffs.get()} errors=${errors.get()}" +
            (if (lastError.isNotBlank()) " lastErr=$lastError" else "") + " authority=candidates_only"
    }
}
