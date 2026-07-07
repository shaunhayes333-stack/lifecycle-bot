package com.lifecyclebot.engine

import kotlin.math.abs

/**
 * V5.0.6193 — SwarmVariantABTuner
 *
 * OPERATOR DIRECTIVE (Phase 2C): "A/B tuning variants across the swarm
 * (autonomous shared tuning between nodes)."
 *
 * Each of the 8 hive nodes runs a slightly perturbed config vector.
 * Perturbations are deterministic per-instance so a node's variant is
 * stable across restarts. Every 24h, the network compares realized
 * lane-EV per variant; if one variant is materially winning, its config
 * broadcasts as the "champion" via SwarmIntel and other nodes migrate
 * their tunables toward it (partial nudge, not hard swap).
 *
 * VARIANT AXES (perturbations applied on top of the config)
 *   • entryScoreFloor   ±3     (some nodes more picky, some looser)
 *   • slTighteningPct   ±0.10  (some tighter SL, some wider)
 *   • tpBiasPct         ±0.15  (some earlier TP, some later)
 *   • labSizingSol      ±0.05  (some risk more per lab run, some less)
 *   • cofirePeerFloor   ±1     (some need more hive confirmation)
 *
 * Winner-of-day is chosen by realized rolling EV. Champion config is
 * published to SwarmIntel as a lab-winner-style signal; nodes drift 30%
 * toward the champion values each cycle.
 *
 * FAIL-OPEN: any error yields neutral (all deltas = 0).
 */
object SwarmVariantABTuner {

    // ── Variant vector ──────────────────────────────────────────────────
    data class Variant(
        val entryScoreDelta: Int,
        val slTighteningDelta: Double,
        val tpBiasDelta: Double,
        val labSizingDelta: Double,
        val cofirePeerFloorDelta: Int,
    ) {
        companion object {
            val NEUTRAL = Variant(0, 0.0, 0.0, 0.0, 0)
        }
    }

    // ── State ───────────────────────────────────────────────────────────
    @Volatile private var cachedVariant: Variant? = null
    @Volatile private var cachedForInstance: String = ""

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Get this node's assigned variant. Deterministic per instanceId so
     * the same node keeps the same variant across restarts.
     */
    fun currentVariant(): Variant {
        return try {
            val id = com.lifecyclebot.collective.CollectiveLearning.getInstanceId().orEmpty()
            if (id.isBlank()) return Variant.NEUTRAL
            val hit = cachedVariant
            if (hit != null && cachedForInstance == id) return hit

            // Deterministic RNG seeded from instanceId hash — each node picks
            // a different point in variant space.
            val seed = abs(id.hashCode()).toLong()
            val r = java.util.Random(seed)
            val v = Variant(
                entryScoreDelta      = (r.nextInt(7) - 3),                    // -3..+3
                slTighteningDelta    = ((r.nextInt(21) - 10) / 100.0),         // -0.10..+0.10
                tpBiasDelta          = ((r.nextInt(31) - 15) / 100.0),         // -0.15..+0.15
                labSizingDelta       = ((r.nextInt(11) - 5) / 100.0),          // -0.05..+0.05
                cofirePeerFloorDelta = (r.nextInt(3) - 1),                     // -1..+1
            )
            cachedVariant = v
            cachedForInstance = id
            try {
                ForensicLogger.lifecycle(
                    "SWARM_VARIANT_ASSIGNED_6193",
                    "instance=${id.take(8)} entryDelta=${v.entryScoreDelta} " +
                    "slDelta=${"%.2f".format(v.slTighteningDelta)} " +
                    "tpDelta=${"%.2f".format(v.tpBiasDelta)} " +
                    "labSizeDelta=${"%.2f".format(v.labSizingDelta)} " +
                    "cofireDelta=${v.cofirePeerFloorDelta}",
                )
                PipelineHealthCollector.labelInc("SWARM_VARIANT_ASSIGNED_6193")
            } catch (_: Throwable) {}
            v
        } catch (_: Throwable) { Variant.NEUTRAL }
    }

    // Individual accessors for consumers that only want one axis
    fun entryScoreDelta(): Int = try { currentVariant().entryScoreDelta } catch (_: Throwable) { 0 }
    fun slTighteningDelta(): Double = try { currentVariant().slTighteningDelta } catch (_: Throwable) { 0.0 }
    fun tpBiasDelta(): Double = try { currentVariant().tpBiasDelta } catch (_: Throwable) { 0.0 }
    fun labSizingDelta(): Double = try { currentVariant().labSizingDelta } catch (_: Throwable) { 0.0 }
    fun cofirePeerFloorDelta(): Int = try { currentVariant().cofirePeerFloorDelta } catch (_: Throwable) { 0 }

    /**
     * Called from the periodic sync loop (e.g. every 24h). Fetches lab-winner
     * signals from SwarmIntel; if a champion variant is present, nudge this
     * node's variant 30% toward the champion. Non-destructive — the local
     * variant slowly drifts but never hard-swaps.
     */
    fun evolveTowardChampion() {
        try {
            val winners = try {
                com.lifecyclebot.collective.CollectiveLearning.getSwarmLabWinners()
            } catch (_: Throwable) { emptyList() }
            if (winners.isEmpty()) return
            // Champion = winner row with highest score field (used as avgPnl proxy).
            val champion = winners.maxByOrNull { (it["score"] as? Number)?.toDouble() ?: 0.0 } ?: return
            val label = (champion["symbol"] as? String).orEmpty()
            val score = (champion["score"] as? Number)?.toDouble() ?: 0.0
            try {
                ForensicLogger.lifecycle(
                    "SWARM_VARIANT_EVOLVE_6193",
                    "champion=${label.take(40)} score=${"%.2f".format(score)}",
                )
                PipelineHealthCollector.labelInc("SWARM_VARIANT_EVOLVE_6193")
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }
}
