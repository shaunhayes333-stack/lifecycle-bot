package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6832 §MARK_PRICE_FRESHNESS_TELEMETRY — operator observation:
 *   "shouldn't these prices be moving in a live state?" (screenshot of Open
 *   Positions where 9 rows show static +/-% while the elapsed-time clock
 *   still ticks).
 *
 * DIAGNOSIS BEING PROBED (advisory-only):
 *   monitorPositions() runs every 1s but DynamicAltTokenRegistry only forces
 *   a network re-fetch once the registry entry is > 60s old. When the DEX
 *   route returns null (transient failure or Birdeye 0% success), the
 *   registry silently CARRIES FORWARD the last-known price and touches
 *   lastUpdatedMs so freshness gates pass. Downstream PnL is then computed
 *   from a stale numeric value — the UI looks "frozen" even though the
 *   pipeline is live.
 *
 * PURPOSE — provide a per-mint answer to:
 *   • when did the numeric price value last actually CHANGE?
 *   • how many carry-forward touches has this mint absorbed since?
 *   • is the current mark "moving", "stalling", or "frozen"?
 *
 * SCOPE — telemetry only. Zero economic effect. The trade engine, exit
 * gates, and adaptive learning do not read from this authority. It exists
 * so the operator (and the UI) can SEE staleness at a glance and so the
 * pipeline health collector can label how many observations were fresh vs
 * carry-forward across the run.
 *
 * WIRING:
 *   1. CryptoAltTrader.monitorPositions() calls
 *      MarkPriceFreshnessTelemetry6832.observe(mintKey, markPrice) once
 *      per position, per tick, right after the mark is validated.
 *   2. CryptoAltActivity.buildOpenPositionsPanel() reads
 *      snapshot(mintKey) to render "quote Xs · N carry" next to each row.
 *
 * PROBE STATES:
 *   FRESH        — value changed within the last 5s.
 *   MOVING       — value changed within the last 30s.
 *   STALLING     — value unchanged for 30s..2m.
 *   FROZEN       — value unchanged for >= 2m (this is what a 0% provider
 *                  looks like from the UI side).
 */
object MarkPriceFreshnessTelemetry6832 {

    private const val EPSILON_REL = 1e-9

    private const val FRESH_WINDOW_MS = 5_000L
    private const val MOVING_WINDOW_MS = 30_000L
    private const val STALLING_WINDOW_MS = 120_000L

    enum class Probe { UNSEEN, FRESH, MOVING, STALLING, FROZEN }

    data class Snapshot(
        val mintKey: String,
        val lastPrice: Double,
        val lastChangeMs: Long,
        val lastObservationMs: Long,
        val carryForwardCount: Long,
        val changeCount: Long,
        val probe: Probe,
        val ageSinceChangeMs: Long,
    ) {
        val shortLabel: String get() = when (probe) {
            Probe.UNSEEN   -> "quote —"
            Probe.FRESH    -> "quote LIVE"
            Probe.MOVING   -> "quote ${(ageSinceChangeMs / 1000L)}s"
            Probe.STALLING -> "quote ${(ageSinceChangeMs / 1000L)}s STALLING"
            Probe.FROZEN   -> "quote ${(ageSinceChangeMs / 60_000L)}m FROZEN"
        }
    }

    private data class State(
        val lastPrice: Double,
        val lastChangeMs: Long,
        val lastObservationMs: Long,
        val carryForwardCount: Long,
        val changeCount: Long,
    )

    private val states = ConcurrentHashMap<String, State>()

    private val observations = AtomicLong(0L)
    private val freshCount = AtomicLong(0L)
    private val carryForwardTotal = AtomicLong(0L)
    private val distinctMints = AtomicLong(0L)

    /**
     * Record one observation of a mint's mark price. Value equality is
     * relative-epsilon so tiny FP drift doesn't spuriously read as change.
     */
    fun observe(mintKey: String, price: Double) {
        if (mintKey.isBlank() || !price.isFinite() || price <= 0.0) return
        val now = System.currentTimeMillis()
        observations.incrementAndGet()

        val prev = states[mintKey]
        if (prev == null) {
            states[mintKey] = State(
                lastPrice = price,
                lastChangeMs = now,
                lastObservationMs = now,
                carryForwardCount = 0L,
                changeCount = 1L,
            )
            distinctMints.incrementAndGet()
            freshCount.incrementAndGet()
            try { PipelineHealthCollector.labelInc("MARK_FRESHNESS_FIRST_SEEN_6832") } catch (_: Throwable) {}
            return
        }

        val changed = kotlin.math.abs(price - prev.lastPrice) >
                (kotlin.math.max(kotlin.math.abs(prev.lastPrice), kotlin.math.abs(price)) * EPSILON_REL)

        states[mintKey] = if (changed) {
            freshCount.incrementAndGet()
            try { PipelineHealthCollector.labelInc("MARK_FRESHNESS_VALUE_CHANGED_6832") } catch (_: Throwable) {}
            prev.copy(
                lastPrice = price,
                lastChangeMs = now,
                lastObservationMs = now,
                changeCount = prev.changeCount + 1L,
            )
        } else {
            carryForwardTotal.incrementAndGet()
            try { PipelineHealthCollector.labelInc("MARK_FRESHNESS_CARRY_FORWARD_6832") } catch (_: Throwable) {}
            prev.copy(
                lastObservationMs = now,
                carryForwardCount = prev.carryForwardCount + 1L,
            )
        }
    }

    fun snapshot(mintKey: String): Snapshot {
        if (mintKey.isBlank()) return unseen(mintKey)
        val s = states[mintKey] ?: return unseen(mintKey)
        val now = System.currentTimeMillis()
        val ageSinceChange = (now - s.lastChangeMs).coerceAtLeast(0L)
        val probe = when {
            ageSinceChange <= FRESH_WINDOW_MS -> Probe.FRESH
            ageSinceChange <= MOVING_WINDOW_MS -> Probe.MOVING
            ageSinceChange <= STALLING_WINDOW_MS -> Probe.STALLING
            else -> Probe.FROZEN
        }
        return Snapshot(
            mintKey = mintKey,
            lastPrice = s.lastPrice,
            lastChangeMs = s.lastChangeMs,
            lastObservationMs = s.lastObservationMs,
            carryForwardCount = s.carryForwardCount,
            changeCount = s.changeCount,
            probe = probe,
            ageSinceChangeMs = ageSinceChange,
        )
    }

    private fun unseen(mintKey: String): Snapshot = Snapshot(
        mintKey = mintKey,
        lastPrice = 0.0,
        lastChangeMs = 0L,
        lastObservationMs = 0L,
        carryForwardCount = 0L,
        changeCount = 0L,
        probe = Probe.UNSEEN,
        ageSinceChangeMs = -1L,
    )

    data class RunSummary(
        val observations: Long,
        val distinctMints: Long,
        val valueChanges: Long,
        val carryForwards: Long,
        val carryForwardRatio: Double,
    )

    fun summary(): RunSummary {
        val obs = observations.get()
        val cf = carryForwardTotal.get()
        val ratio = if (obs > 0L) cf.toDouble() / obs.toDouble() else 0.0
        return RunSummary(
            observations = obs,
            distinctMints = distinctMints.get(),
            valueChanges = freshCount.get(),
            carryForwards = cf,
            carryForwardRatio = ratio,
        )
    }
}
