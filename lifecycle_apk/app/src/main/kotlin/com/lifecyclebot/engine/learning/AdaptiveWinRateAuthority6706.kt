package com.lifecyclebot.engine.learning

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.StrategyTruthLedger
import com.lifecyclebot.engine.TradeHistoryStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * V5.0.6706 — canonical self-adjusting win-rate execution authority.
 *
 * Runtime 5.0.6705 proved the old loop could learn outcomes without making the
 * result binding at the next entry: LanePolicy's owner update was bypassed on
 * canonical Executor closes and most low-WR reactions only changed size. Size
 * protects PnL but cannot, by itself, repair win rate.
 *
 * This authority is deliberately narrow:
 *  - consumes only canonical finalized terminal outcomes going forward;
 *  - bootstraps once from StrategyTruthLedger's clean terminal population so an
 *    upgrade does not forget already-proven bad/good lane evidence;
 *  - maintains a persisted, exponentially-decayed lane outcome posterior;
 *  - targets >=50% WR as the execution doctrine;
 *  - below target, ordinary entries are withheld and only bounded re-probes are
 *    admitted so the lane can re-prove a changed strategy without continuously
 *    adding known-low-quality losses;
 *  - >=50% automatically releases the constraint; >=58% gets full authority.
 *
 * Hard rug/liquidity/finality gates remain upstream/downstream and are untouched.
 */
object AdaptiveWinRateAuthority6706 {
    const val TARGET_WR = 0.50
    private const val FULL_RELEASE_WR = 0.58
    private const val PRIOR_ALPHA = 2.0
    private const val PRIOR_BETA = 2.0
    private const val DECAY = 0.94
    private const val MIN_BINDING_EVIDENCE = 5.0
    private const val HISTORY_RAW_LIMIT = 5_000
    private const val HISTORY_CLEAN_LIMIT = 1_000

    private data class Cell(
        val loaded: AtomicBoolean = AtomicBoolean(false),
        val winMass: AtomicReference<Double> = AtomicReference(0.0),
        val lossMass: AtomicReference<Double> = AtomicReference(0.0),
        val lastAtMs: AtomicLong = AtomicLong(0L),
        val executionSeq: AtomicLong = AtomicLong(0L),
    )

    private data class HistoricalSeed(
        val winMass: Double,
        val lossMass: Double,
        val lastAtMs: Long,
        val decisiveRows: Int,
    )

    data class Decision(
        val execute: Boolean,
        val sizeMultiplier: Double,
        val probe: Boolean,
        val posteriorWr: Double,
        val evidence: Double,
        val reason: String,
    )

    private val cells = ConcurrentHashMap<String, Cell>()
    private val seenCanonical = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, String>()
    private val drains = ConcurrentHashMap<String, Boolean>()

    private val memeLanes = setOf(
        "QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS",
        "CORE", "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED",
        "TREASURY", "CASHGEN",
    )
    private val historyLoaded = AtomicBoolean(false)
    private val historySeeds = ConcurrentHashMap<String, HistoricalSeed>()
    private val historyLoadLock = Any()

    private fun laneKey(lane: String): String = lane.uppercase().trim()
        .replace("BLUE_CHIP", "BLUECHIP")
        .replace("SHITCOIN_EXPRESS", "EXPRESS")
        .take(32)

    fun isMemeLane(lane: String): Boolean = laneKey(lane) in memeLanes

    /**
     * Build one bounded clean historical seed for all meme lanes. This is a
     * migration/bootstrap read only; live adaptation after that is exclusively fed
     * by the canonical finalized bus. StrategyTruthLedger removes recovery rows,
     * duplicate terminals, bad entry basis, quantity quarantines and forensic
     * contamination before any row reaches this seed.
     */
    private fun ensureHistoricalSeeds6706() {
        if (historyLoaded.get()) return
        synchronized(historyLoadLock) {
            if (historyLoaded.get()) return
            try {
                val raw = TradeHistoryStore.getRecentValidClosedTradesRaw(
                    limit = HISTORY_RAW_LIMIT,
                    includePartials = false,
                )
                val clean = StrategyTruthLedger.clean(raw, HISTORY_CLEAN_LIMIT).rows
                    .filter { it.mode.equals("paper", true) || it.mode.equals("live", true) }
                    .sortedBy { it.ts }
                data class MutableSeed(var w: Double = 0.0, var l: Double = 0.0, var at: Long = 0L, var n: Int = 0)
                val byLane = mutableMapOf<String, MutableSeed>()
                for (row in clean) {
                    val lane = laneKey(row.tradingMode)
                    if (lane !in memeLanes) continue
                    val pnlPct = row.pnlPct.takeIf { it.isFinite() } ?: continue
                    val isWin = pnlPct > 0.5
                    val isLoss = pnlPct < -0.5
                    if (!isWin && !isLoss) continue
                    val s = byLane.getOrPut(lane) { MutableSeed() }
                    s.w = (s.w * DECAY) + if (isWin) 1.0 else 0.0
                    s.l = (s.l * DECAY) + if (isLoss) 1.0 else 0.0
                    s.at = maxOf(s.at, row.ts)
                    s.n++
                }
                byLane.forEach { (lane, s) ->
                    historySeeds[lane] = HistoricalSeed(s.w, s.l, s.at, s.n)
                }
                try {
                    PipelineHealthCollector.labelInc("ADAPTIVE_WR_HISTORY_BOOTSTRAP_6706")
                    ForensicLogger.lifecycle(
                        "ADAPTIVE_WR_HISTORY_BOOTSTRAP_6706",
                        "cleanRows=${clean.size} lanes=${historySeeds.size} " +
                            historySeeds.entries.sortedBy { it.key }.joinToString(",") { (lane, s) -> "$lane:${s.decisiveRows}" },
                    )
                } catch (_: Throwable) {}
            } catch (t: Throwable) {
                try {
                    PipelineHealthCollector.labelInc("ADAPTIVE_WR_HISTORY_BOOTSTRAP_FAILED_6706")
                    ForensicLogger.lifecycle("ADAPTIVE_WR_HISTORY_BOOTSTRAP_FAILED_6706", "err=${t.javaClass.simpleName}:${t.message?.take(100)}")
                } catch (_: Throwable) {}
            } finally {
                historyLoaded.set(true)
            }
        }
    }

    private fun cell(lane: String): Cell {
        val key = laneKey(lane)
        val c = cells.computeIfAbsent(key) { Cell() }
        if (c.loaded.compareAndSet(false, true)) {
            try {
                val raw = LearningPersistence.load("adaptive_wr_6706_$key")
                if (!raw.isNullOrBlank()) {
                    Regex("\\\"w\\\":([0-9.Ee+-]+)").find(raw)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let(c.winMass::set)
                    Regex("\\\"l\\\":([0-9.Ee+-]+)").find(raw)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let(c.lossMass::set)
                    Regex("\\\"at\\\":([0-9]+)").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let(c.lastAtMs::set)
                } else {
                    ensureHistoricalSeeds6706()
                    historySeeds[key]?.let { seed ->
                        c.winMass.set(seed.winMass)
                        c.lossMass.set(seed.lossMass)
                        c.lastAtMs.set(seed.lastAtMs)
                        persist(key, c)
                        try {
                            PipelineHealthCollector.labelInc("ADAPTIVE_WR_HISTORY_SEEDED_6706_$key")
                            ForensicLogger.lifecycle(
                                "ADAPTIVE_WR_HISTORY_SEEDED_6706",
                                "lane=$key decisive=${seed.decisiveRows} wMass=${seed.winMass} lMass=${seed.lossMass}",
                            )
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }
        return c
    }

    private fun persist(lane: String, c: Cell) {
        val key = laneKey(lane)
        val storeKey = "adaptive_wr_6706_$key"
        pending[storeKey] = "{\"w\":${c.winMass.get()},\"l\":${c.lossMass.get()},\"at\":${c.lastAtMs.get()}}"
        if (drains.putIfAbsent(storeKey, true) != null) return
        scope.launch {
            try {
                while (true) {
                    val newest = pending.remove(storeKey) ?: break
                    try { LearningPersistence.save(storeKey, newest) } catch (_: Throwable) {}
                }
            } finally {
                drains.remove(storeKey)
                if (pending.containsKey(storeKey) && drains.putIfAbsent(storeKey, true) == null) {
                    scope.launch {
                        try {
                            while (true) {
                                val newest = pending.remove(storeKey) ?: break
                                try { LearningPersistence.save(storeKey, newest) } catch (_: Throwable) {}
                            }
                        } finally { drains.remove(storeKey) }
                    }
                }
            }
        }
    }

    private fun posterior(c: Cell): Pair<Double, Double> {
        val w = c.winMass.get().coerceAtLeast(0.0)
        val l = c.lossMass.get().coerceAtLeast(0.0)
        val n = w + l
        val p = ((w + PRIOR_ALPHA) / (n + PRIOR_ALPHA + PRIOR_BETA)).coerceIn(0.0, 1.0)
        return p to n
    }

    /**
     * Called from the canonical finalized-bus consumer bridge. The event key is
     * exact terminal identity (economicEventId preferred); a replay cannot train
     * the lane twice in the same process.
     */
    fun recordCanonicalOutcome(
        canonicalEventKey: String,
        positionId: String,
        lane: String,
        scoreBand: String,
        realizedReturnPct: Double,
    ): Boolean {
        val key = laneKey(lane)
        if (key !in memeLanes) return false
        if (canonicalEventKey.isBlank() && positionId.isBlank()) return false
        val eventKey = canonicalEventKey.ifBlank { positionId }
        if (!seenCanonical.add(eventKey)) return false
        if (!realizedReturnPct.isFinite()) return false

        val isWin = realizedReturnPct > 0.5
        val isLoss = realizedReturnPct < -0.5
        if (!isWin && !isLoss) return false

        val c = cell(key)
        synchronized(c) {
            c.winMass.set((c.winMass.get() * DECAY) + if (isWin) 1.0 else 0.0)
            c.lossMass.set((c.lossMass.get() * DECAY) + if (isLoss) 1.0 else 0.0)
            c.lastAtMs.set(System.currentTimeMillis())
        }
        persist(key, c)

        // Reconnect the pre-existing adaptive siblings. This is the canonical
        // owner-lane call that the 5.0.6705 stack incorrectly delegated to the
        // V3 fallback journal path.
        try { LanePolicy.recordOutcome(key, scoreBand, isWin, isLoss) } catch (_: Throwable) {}
        try { RetrainingDecay.noteOutcome(key, scoreBand, isWin, isLoss, realizedReturnPct) } catch (_: Throwable) {}
        try { ExplorationBudget.onLaneOutcome(key, realizedReturnPct) } catch (_: Throwable) {}

        val (p, n) = posterior(c)
        try {
            PipelineHealthCollector.labelInc("ADAPTIVE_WR_CANONICAL_OUTCOME_6706")
            PipelineHealthCollector.labelInc("ADAPTIVE_WR_CANONICAL_OUTCOME_6706_$key")
            ForensicLogger.lifecycle(
                "ADAPTIVE_WR_CANONICAL_OUTCOME_6706",
                "positionId=${positionId.take(24)} lane=$key band=$scoreBand pnlPct=$realizedReturnPct posterior=${"%.3f".format(p)} evidence=${"%.2f".format(n)} target=$TARGET_WR",
            )
        } catch (_: Throwable) {}
        return true
    }

    /**
     * Binding entry decision consumed by OrderSizeResolver6441. No candidate is
     * permanently disabled: below-target lanes receive bounded re-probes whose
     * cadence tightens automatically as the posterior worsens.
     */
    fun entryDecision(lane: String): Decision {
        val key = laneKey(lane)
        if (key !in memeLanes) {
            return Decision(true, 1.0, false, 0.5, 0.0, "NON_MEME_NEUTRAL")
        }
        val c = cell(key)
        val (p, n) = posterior(c)
        if (n < 1.0) return Decision(true, 1.0, false, p, n, "COLD_NEUTRAL")

        // Trade-one adaptation: bearish early evidence changes size immediately,
        // but we do not withhold normal entries until a minimum real sample exists.
        if (n < MIN_BINDING_EVIDENCE) {
            val mult = if (p >= TARGET_WR) 1.0 else (0.55 + 0.45 * (p / TARGET_WR)).coerceIn(0.55, 1.0)
            return Decision(true, mult, false, p, n, "EARLY_SOFT_ADAPT")
        }

        if (p >= FULL_RELEASE_WR) {
            return Decision(true, 1.0, false, p, n, "ABOVE_FULL_RELEASE")
        }
        if (p >= TARGET_WR) {
            val mult = (0.85 + ((p - TARGET_WR) / (FULL_RELEASE_WR - TARGET_WR)) * 0.15).coerceIn(0.85, 1.0)
            return Decision(true, mult, false, p, n, "TARGET_HELD_RECOVERING")
        }

        val cadence = when {
            p < 0.20 -> 10L
            p < 0.30 -> 7L
            p < 0.40 -> 5L
            else -> 3L
        }
        val seq = c.executionSeq.incrementAndGet()
        val probe = seq % cadence == 0L
        val probeMult = when {
            p < 0.20 -> 0.12
            p < 0.30 -> 0.18
            p < 0.40 -> 0.25
            else -> 0.35
        }
        val reason = if (probe) "BELOW_TARGET_REPROBE_1_IN_$cadence" else "BELOW_TARGET_RETRAINING_HOLD_1_IN_$cadence"
        try {
            PipelineHealthCollector.labelInc(if (probe) "ADAPTIVE_WR_REPROBE_6706_$key" else "ADAPTIVE_WR_ENTRY_HELD_6706_$key")
        } catch (_: Throwable) {}
        return Decision(probe, probeMult, probe, p, n, reason)
    }

    fun statusLine(): String = cells.keys.sorted().joinToString(" · ") { lane ->
        val c = cell(lane)
        val (p, n) = posterior(c)
        "$lane=${"%.1f".format(p * 100.0)}%(n≈${"%.1f".format(n)})"
    }.ifBlank { "no-canonical-outcomes" }
}
