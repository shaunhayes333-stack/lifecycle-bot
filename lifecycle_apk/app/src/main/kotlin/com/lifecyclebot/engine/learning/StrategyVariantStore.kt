package com.lifecyclebot.engine.learning

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1324 — Hypothesis / Variant Engine (Build D).
 *
 * Operator directive §7: bad variants MUST be RETRAINING / MUTATED /
 * DOWNSIZED / SHADOWED / PAPER_MICRO. Only delete/retire variants that
 * are structurally duplicate, corrupt, or statistically hopeless after
 * enough samples. Generate replacement variants from failure reason.
 *
 * Variant policy is encoded as a struct, not a string:
 *   slPct, tpPct, trailMode, entryAfterBurstAllowed, postBondingOnly,
 *   minHolderVelocity, etc.
 *
 * State machine:
 *   ACTIVE → DEMOTED → RETRAINING ─┬─→ MUTATED → ACTIVE (resampled)
 *                                  └─→ RETIRED (only if duplicate/corrupt
 *                                               or after maxSamples with
 *                                               consistently bad expectancy)
 *   ACTIVE → PROMOTED (passes promotion criteria)
 *   ACTIVE → SHADOW_ONLY (paper-only confidence)
 */
object StrategyVariantStore {

    enum class State { ACTIVE, RETRAINING, MUTATED, PROMOTED, RETIRED, SHADOW_ONLY }

    enum class TrailMode { NONE, FIXED_FLOOR, LET_RUN, BREAK_EVEN }

    data class Policy(
        val slPct: Double,
        val tpPct: Double,
        val trailMode: TrailMode,
        val entryAfterBurstAllowed: Boolean,
        val postBondingOnly: Boolean,
        val minHolderVelocity: Double,         // holders/min — gate for fresh tokens
        val minLiqUsd: Double,
        val migrationOnly: Boolean,
        val symbolFamilySuppressed: List<String> = emptyList(),
    )

    data class Variant(
        val id: String,
        val parentId: String?,
        val lane: String,
        val policy: Policy,
        var state: State,
        val samples: AtomicInteger,
        val wins: AtomicInteger,
        val losses: AtomicInteger,
        val sumPnlPctX100: AtomicLong,
        val createdAt: Long,
        var lastImprovedAt: Long,
    ) {
        fun expectancy(): Double = if (samples.get() == 0) 0.0
            else sumPnlPctX100.get() / 100.0 / samples.get().toDouble()
        fun winRate(): Double {
            val d = wins.get() + losses.get()
            return if (d == 0) 0.0 else wins.get().toDouble() / d * 100.0
        }
    }

    private val variants = ConcurrentHashMap<String, Variant>()
    private val variantsByLane = ConcurrentHashMap<String, MutableList<String>>()

    // Seed defaults per lane (operator §11 preferred replay outcomes).
    init {
        seed("MANIPULATED", Policy(slPct = -5.0,  tpPct = 8.0,  trailMode = TrailMode.FIXED_FLOOR,
                                   entryAfterBurstAllowed = false, postBondingOnly = false, minHolderVelocity = 0.0, minLiqUsd = 5_000.0, migrationOnly = false))
        seed("MOONSHOT",    Policy(slPct = -15.0, tpPct = 80.0, trailMode = TrailMode.LET_RUN,
                                   entryAfterBurstAllowed = true,  postBondingOnly = false, minHolderVelocity = 0.0, minLiqUsd = 3_000.0, migrationOnly = false))
        seed("SHITCOIN",    Policy(slPct = -10.0, tpPct = 25.0, trailMode = TrailMode.BREAK_EVEN,
                                   entryAfterBurstAllowed = false, postBondingOnly = true,  minHolderVelocity = 1.5, minLiqUsd = 2_000.0, migrationOnly = false))
        seed("UNKNOWN",     Policy(slPct = -8.0,  tpPct = 15.0, trailMode = TrailMode.BREAK_EVEN,
                                   entryAfterBurstAllowed = false, postBondingOnly = true,  minHolderVelocity = 1.0, minLiqUsd = 4_000.0, migrationOnly = false))
        seed("QUALITY",     Policy(slPct = -10.0, tpPct = 30.0, trailMode = TrailMode.LET_RUN,
                                   entryAfterBurstAllowed = false, postBondingOnly = false, minHolderVelocity = 0.0, minLiqUsd = 10_000.0, migrationOnly = false))
        seed("BLUECHIP",    Policy(slPct = -8.0,  tpPct = 25.0, trailMode = TrailMode.LET_RUN,
                                   entryAfterBurstAllowed = false, postBondingOnly = false, minHolderVelocity = 0.0, minLiqUsd = 25_000.0, migrationOnly = false))
        seed("TREASURY",    Policy(slPct = -6.0,  tpPct = 12.0, trailMode = TrailMode.BREAK_EVEN,
                                   entryAfterBurstAllowed = false, postBondingOnly = false, minHolderVelocity = 0.0, minLiqUsd = 50_000.0, migrationOnly = false))
    }

    private fun seed(lane: String, policy: Policy) {
        val v = Variant(
            id = "${lane}_BASELINE",
            parentId = null,
            lane = lane,
            policy = policy,
            state = State.ACTIVE,
            samples = AtomicInteger(0),
            wins = AtomicInteger(0),
            losses = AtomicInteger(0),
            sumPnlPctX100 = AtomicLong(0L),
            createdAt = System.currentTimeMillis(),
            lastImprovedAt = System.currentTimeMillis(),
        )
        variants[v.id] = v
        variantsByLane.computeIfAbsent(lane) { mutableListOf() }.add(v.id)
    }

    /** The active variant for a lane (highest-expectancy ACTIVE / PROMOTED). */
    @Synchronized
    fun activeFor(lane: String): Variant? {
        val ids = variantsByLane[lane.uppercase()] ?: return null
        return ids.mapNotNull { variants[it] }
            .filter { it.state == State.ACTIVE || it.state == State.PROMOTED }
            .maxByOrNull { it.expectancy() }
    }

    /** All variants for a lane (for UI / hypothesis engine introspection). */
    fun variantsFor(lane: String): List<Variant> {
        val ids = variantsByLane[lane.uppercase()] ?: return emptyList()
        return ids.mapNotNull { variants[it] }
    }

    /** Record an outcome for a variant; updates state if criteria met. */
    fun recordOutcome(variantId: String, isWin: Boolean, isLoss: Boolean, pnlPct: Double) {
        val v = variants[variantId] ?: return
        v.samples.incrementAndGet()
        if (isWin) v.wins.incrementAndGet()
        if (isLoss) v.losses.incrementAndGet()
        v.sumPnlPctX100.addAndGet((pnlPct * 100).toLong())
        if (isWin) v.lastImprovedAt = System.currentTimeMillis()

        // State-transition checks (gated by sample size).
        val budget = ExplorationBudget.budgetFor(v.lane)
        val n = v.samples.get()
        val exp = v.expectancy()
        if (n >= budget.minSamplesBeforePromotion && exp > 2.0 && v.state == State.ACTIVE) {
            v.state = State.PROMOTED
            try { PipelineHealthCollector.labelInc("VARIANT_PROMOTED|${v.lane}") } catch (_: Throwable) {}
            ErrorLogger.info("StrategyVariant", "🏅 ${v.id} PROMOTED (n=$n exp=${"%.2f".format(exp)}%)")
        } else if (n >= budget.minSamplesBeforeRetirement && exp < -10.0 && v.state in listOf(State.ACTIVE, State.RETRAINING)) {
            v.state = State.RETRAINING
            try { PipelineHealthCollector.labelInc("VARIANT_RETRAINING|${v.lane}") } catch (_: Throwable) {}
            ErrorLogger.info("StrategyVariant", "📚 ${v.id} → RETRAINING (n=$n exp=${"%.2f".format(exp)}%)")
            // Generate a mutated child to test alternative.
            mutate(v.id)
        }
        persist(v)
    }

    /**
     * Generate a mutated child variant from a poorly-performing parent.
     * Operator §7 examples: tighter stop, earlier liquidity exit, no-entry-after-burst,
     * migration-only, post-bonding-only, symbol-family suppression, min-holder-velocity filter.
     */
    fun mutate(parentId: String): Variant? {
        val parent = variants[parentId] ?: return null
        if (parent.state == State.MUTATED) return null  // already a mutation

        val mutations = listOf(
            { p: Policy -> p.copy(slPct = p.slPct + 2.0) },                             // tighter stop
            { p: Policy -> p.copy(tpPct = (p.tpPct * 0.8).coerceAtLeast(5.0)) },        // earlier take-profit
            { p: Policy -> p.copy(entryAfterBurstAllowed = false) },                    // no-entry-after-burst
            { p: Policy -> p.copy(postBondingOnly = true) },                            // post-bonding-only
            { p: Policy -> p.copy(migrationOnly = true) },                              // migration-only
            { p: Policy -> p.copy(minHolderVelocity = maxOf(1.0, p.minHolderVelocity + 0.5)) },  // min-holder-velocity
            { p: Policy -> p.copy(minLiqUsd = p.minLiqUsd * 1.5) },                     // liquidity floor up
            { p: Policy -> p.copy(trailMode = when (p.trailMode) {
                TrailMode.NONE -> TrailMode.BREAK_EVEN
                TrailMode.BREAK_EVEN -> TrailMode.FIXED_FLOOR
                TrailMode.FIXED_FLOOR -> TrailMode.LET_RUN
                TrailMode.LET_RUN -> TrailMode.BREAK_EVEN
            }) },
        )
        val idx = (System.currentTimeMillis() % mutations.size).toInt().coerceIn(0, mutations.size - 1)
        val newPolicy = mutations[idx](parent.policy)
        val child = Variant(
            id = "${parent.lane}_${UUID.randomUUID().toString().take(8)}",
            parentId = parent.id,
            lane = parent.lane,
            policy = newPolicy,
            state = State.MUTATED,
            samples = AtomicInteger(0),
            wins = AtomicInteger(0),
            losses = AtomicInteger(0),
            sumPnlPctX100 = AtomicLong(0L),
            createdAt = System.currentTimeMillis(),
            lastImprovedAt = System.currentTimeMillis(),
        )
        variants[child.id] = child
        variantsByLane.computeIfAbsent(parent.lane) { mutableListOf() }.add(child.id)
        try { PipelineHealthCollector.labelInc("VARIANT_MUTATED|${parent.lane}") } catch (_: Throwable) {}
        ErrorLogger.info("StrategyVariant", "🧬 MUTATED ${parent.id} → ${child.id} (mutation #$idx)")
        persist(child)
        // Promote the mutated child to ACTIVE for evaluation.
        child.state = State.ACTIVE
        return child
    }

    /** Promote a child to retire its parent (only when child outperforms parent). */
    fun promoteChild(childId: String) {
        val child = variants[childId] ?: return
        val parent = variants[child.parentId ?: return] ?: return
        if (child.expectancy() <= parent.expectancy()) return
        child.state = State.PROMOTED
        parent.state = State.RETIRED
        try { PipelineHealthCollector.labelInc("VARIANT_RETIRED|${parent.lane}") } catch (_: Throwable) {}
        ErrorLogger.info("StrategyVariant", "🏆 ${child.id} replaces ${parent.id} (exp ${"%.2f".format(child.expectancy())} > ${"%.2f".format(parent.expectancy())})")
        persist(child); persist(parent)
    }

    /** Snapshot per acceptance criteria §7 + §12. */
    data class Snapshot(
        val active: Int,
        val retraining: Int,
        val mutated: Int,
        val promoted: Int,
        val retired: Int,
        val shadowOnly: Int,
        val byLane: Map<String, Map<String, Int>>,
    )

    fun snapshot(): Snapshot {
        val all = variants.values.toList()
        val byState = all.groupingBy { it.state }.eachCount()
        val byLane = variantsByLane.keys.associateWith { lane ->
            val laneVariants = variantsFor(lane)
            mapOf(
                "active"      to laneVariants.count { it.state == State.ACTIVE },
                "retraining"  to laneVariants.count { it.state == State.RETRAINING },
                "mutated"     to laneVariants.count { it.state == State.MUTATED },
                "promoted"    to laneVariants.count { it.state == State.PROMOTED },
                "retired"     to laneVariants.count { it.state == State.RETIRED },
                "shadowOnly"  to laneVariants.count { it.state == State.SHADOW_ONLY },
            )
        }
        return Snapshot(
            active     = byState[State.ACTIVE]      ?: 0,
            retraining = byState[State.RETRAINING]  ?: 0,
            mutated    = byState[State.MUTATED]     ?: 0,
            promoted   = byState[State.PROMOTED]    ?: 0,
            retired    = byState[State.RETIRED]     ?: 0,
            shadowOnly = byState[State.SHADOW_ONLY] ?: 0,
            byLane     = byLane,
        )
    }

    private fun persist(v: Variant) {
        try {
            val json = """{"id":"${v.id}","parent":"${v.parentId ?: ""}","lane":"${v.lane}","state":"${v.state.name}","n":${v.samples.get()},"w":${v.wins.get()},"l":${v.losses.get()},"pn":${v.sumPnlPctX100.get()},"sl":${v.policy.slPct},"tp":${v.policy.tpPct},"tm":"${v.policy.trailMode.name}","eab":${v.policy.entryAfterBurstAllowed},"pbo":${v.policy.postBondingOnly},"mhv":${v.policy.minHolderVelocity},"mlu":${v.policy.minLiqUsd},"mig":${v.policy.migrationOnly}}"""
            LearningPersistence.save("variant_${v.id}", json)
        } catch (_: Throwable) {}
    }
}
