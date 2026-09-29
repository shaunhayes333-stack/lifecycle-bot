package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.LearningPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.7429 — exact strategy performance ledger.
 *
 * Lane-level performance cannot distinguish materially different playbooks
 * inside the same lane. This ledger keys canonical terminal outcomes by the
 * immutable strategy identity sealed at entry:
 *
 * mode | lane | tradeType | setup | style | tactic | strategyVariantId
 *
 * It is telemetry/learning evidence only. It never dispatches a trade and
 * never changes a gate directly.
 */
object ExactStrategyPerformance7429 {
    private const val MAX_KEYS = 2048

    data class Snapshot(
        val key: String,
        val mode: String,
        val lane: String,
        val tradeType: String,
        val setup: String,
        val style: String,
        val tactic: String,
        val variantId: String,
        val n: Long,
        val wins: Long,
        val losses: Long,
        val meanPnlPct: Double,
        val pnlSol: Double,
        val meanMfePct: Double,
        val meanMaePct: Double,
        val meanHoldMinutes: Double,
    ) {
        val winRatePct: Double get() = if (wins + losses > 0L) wins * 100.0 / (wins + losses) else 0.0
    }

    private class Cell(
        val mode: String,
        val lane: String,
        val tradeType: String,
        val setup: String,
        val style: String,
        val tactic: String,
        val variantId: String,
    ) {
        val n = AtomicLong(0L)
        val wins = AtomicLong(0L)
        val losses = AtomicLong(0L)
        val pnlPctX1000 = AtomicLong(0L)
        val pnlSolLamports = AtomicLong(0L)
        val mfePctX1000 = AtomicLong(0L)
        val maePctX1000 = AtomicLong(0L)
        val holdMs = AtomicLong(0L)
    }

    private val cells = ConcurrentHashMap<String, Cell>()
    // V5.0.7431 — O(1) admission index. Exact terminal rows still retain
    // variant identity in [cells], while this sibling aggregates across variant
    // IDs for the underlying playbook. Updated only on terminal close; admission
    // never scans the full strategy scoreboard.
    private val playbookCells7431 = ConcurrentHashMap<String, Cell>()

    private val restored7431 = AtomicBoolean(false)
    private const val PLAYBOOK_INDEX_KEY_7431 = "exact_strategy_playbook_index_7431"
    private fun playbookStorageKey7431(key: String) = "exact_strategy_playbook_7431_" + key

    private fun ensureRestored7431() {
        if (!restored7431.compareAndSet(false, true)) return
        try {
            val raw = LearningPersistence.load(PLAYBOOK_INDEX_KEY_7431) ?: return
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val k = arr.optString(i, "")
                if (k.isBlank()) continue
                val rowRaw = LearningPersistence.load(playbookStorageKey7431(k)) ?: continue
                val j = JSONObject(rowRaw)
                val parts = k.split('|')
                val cell = Cell(
                    mode = parts.getOrElse(0) { "UNKNOWN" },
                    lane = parts.getOrElse(1) { "UNKNOWN" },
                    tradeType = parts.getOrElse(2) { "UNSTAMPED_TYPE" },
                    setup = parts.getOrElse(3) { "UNSTAMPED_SETUP" },
                    style = parts.getOrElse(4) { "UNSTAMPED_STYLE" },
                    tactic = parts.getOrElse(5) { "UNSTAMPED_TACTIC" },
                    variantId = "ALL_VARIANTS",
                )
                cell.n.set(j.optLong("n", 0L))
                cell.wins.set(j.optLong("w", 0L))
                cell.losses.set(j.optLong("l", 0L))
                cell.pnlPctX1000.set(j.optLong("p", 0L))
                cell.pnlSolLamports.set(j.optLong("sol", 0L))
                cell.mfePctX1000.set(j.optLong("mfe", 0L))
                cell.maePctX1000.set(j.optLong("mae", 0L))
                cell.holdMs.set(j.optLong("hold", 0L))
                if (cell.n.get() > 0L) playbookCells7431[k] = cell
            }
            if (playbookCells7431.isNotEmpty()) {
                try { PipelineHealthCollector.labelInc("EXACT_STRATEGY_EV_RESTORED_7431") } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun persistPlaybook7431(key: String, cell: Cell, newKey: Boolean) {
        try {
            val j = JSONObject()
                .put("n", cell.n.get()).put("w", cell.wins.get()).put("l", cell.losses.get())
                .put("p", cell.pnlPctX1000.get()).put("sol", cell.pnlSolLamports.get())
                .put("mfe", cell.mfePctX1000.get()).put("mae", cell.maePctX1000.get())
                .put("hold", cell.holdMs.get())
            LearningPersistence.save(playbookStorageKey7431(key), j.toString())
            if (newKey) {
                LearningPersistence.save(
                    PLAYBOOK_INDEX_KEY_7431,
                    JSONArray(playbookCells7431.keys.sorted()).toString(),
                )
            }
        } catch (_: Throwable) {}
    }


    private fun norm(raw: String, fallback: String): String =
        raw.trim().uppercase().replace('|', '_').take(48).ifBlank { fallback }

    private fun key(env: CanonicalFinalizedTradeBus6464.Envelope): String {
        val mode = norm(env.mode, "UNKNOWN")
        val lane = norm(env.lane, "UNKNOWN")
        val type = norm(env.entryTradeType, "UNSTAMPED_TYPE")
        val setup = norm(env.entrySetup, "UNSTAMPED_SETUP")
        val style = norm(env.entryStyle, "UNSTAMPED_STYLE")
        val tactic = norm(env.entryTactic, "UNSTAMPED_TACTIC")
        val variant = norm(env.entryStrategyVariantId, "BASE")
        return listOf(mode, lane, type, setup, style, tactic, variant).joinToString("|")
    }

    private fun playbookKey7431(
        mode: String,
        lane: String,
        tradeType: String,
        setup: String,
        style: String,
        tactic: String,
    ): String = listOf(
        norm(mode, "UNKNOWN"),
        norm(lane, "UNKNOWN"),
        norm(tradeType, "UNSTAMPED_TYPE"),
        norm(setup, "UNSTAMPED_SETUP"),
        norm(style, "UNSTAMPED_STYLE"),
        norm(tactic, "UNSTAMPED_TACTIC"),
    ).joinToString("|")

    fun record(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        if (!env.terminal || !env.learningEligible || env.positionId.isBlank()) return false
        ensureRestored7431()
        if (!env.realizedReturnPct.isFinite() || !env.realizedPnlSol.isFinite()) return false

        val k = key(env)
        var cell = cells[k]
        if (cell == null) {
            if (cells.size >= MAX_KEYS) {
                try { PipelineHealthCollector.labelInc("EXACT_STRATEGY_KEY_CAP_7429") } catch (_: Throwable) {}
                return false
            }
            val parts = k.split('|')
            val fresh = Cell(
                mode = parts.getOrElse(0) { "UNKNOWN" },
                lane = parts.getOrElse(1) { "UNKNOWN" },
                tradeType = parts.getOrElse(2) { "UNSTAMPED_TYPE" },
                setup = parts.getOrElse(3) { "UNSTAMPED_SETUP" },
                style = parts.getOrElse(4) { "UNSTAMPED_STYLE" },
                tactic = parts.getOrElse(5) { "UNSTAMPED_TACTIC" },
                variantId = parts.getOrElse(6) { "BASE" },
            )
            cell = cells.putIfAbsent(k, fresh) ?: fresh
        }

        cell.n.incrementAndGet()
        if (env.realizedReturnPct > 0.0) cell.wins.incrementAndGet()
        else if (env.realizedReturnPct < 0.0) cell.losses.incrementAndGet()
        cell.pnlPctX1000.addAndGet((env.realizedReturnPct.coerceIn(-100.0, 100_000.0) * 1000.0).toLong())
        cell.pnlSolLamports.addAndGet((env.realizedPnlSol * 1_000_000_000.0).toLong())
        cell.mfePctX1000.addAndGet((env.mfePct.coerceIn(-100.0, 100_000.0) * 1000.0).toLong())
        cell.maePctX1000.addAndGet((env.maePct.coerceIn(-100_000.0, 100_000.0) * 1000.0).toLong())
        cell.holdMs.addAndGet(env.holdingTimeMs.coerceAtLeast(0L))

        // Maintain the variant-agnostic playbook aggregate for constant-time
        // predictive reads. It intentionally mirrors only terminal economics.
        try {
            val pk7431 = playbookKey7431(
                env.mode, env.lane, env.entryTradeType, env.entrySetup, env.entryStyle, env.entryTactic,
            )
            val parts7431 = pk7431.split('|')
            val wasNew7431 = !playbookCells7431.containsKey(pk7431)
            val pc7431 = playbookCells7431.computeIfAbsent(pk7431) {
                Cell(
                    mode = parts7431.getOrElse(0) { "UNKNOWN" },
                    lane = parts7431.getOrElse(1) { "UNKNOWN" },
                    tradeType = parts7431.getOrElse(2) { "UNSTAMPED_TYPE" },
                    setup = parts7431.getOrElse(3) { "UNSTAMPED_SETUP" },
                    style = parts7431.getOrElse(4) { "UNSTAMPED_STYLE" },
                    tactic = parts7431.getOrElse(5) { "UNSTAMPED_TACTIC" },
                    variantId = "ALL_VARIANTS",
                )
            }
            pc7431.n.incrementAndGet()
            if (env.realizedReturnPct > 0.0) pc7431.wins.incrementAndGet()
            else if (env.realizedReturnPct < 0.0) pc7431.losses.incrementAndGet()
            pc7431.pnlPctX1000.addAndGet((env.realizedReturnPct.coerceIn(-100.0, 100_000.0) * 1000.0).toLong())
            pc7431.pnlSolLamports.addAndGet((env.realizedPnlSol * 1_000_000_000.0).toLong())
            pc7431.mfePctX1000.addAndGet((env.mfePct.coerceIn(-100.0, 100_000.0) * 1000.0).toLong())
            pc7431.maePctX1000.addAndGet((env.maePct.coerceIn(-100_000.0, 100_000.0) * 1000.0).toLong())
            pc7431.holdMs.addAndGet(env.holdingTimeMs.coerceAtLeast(0L))
            persistPlaybook7431(pk7431, pc7431, wasNew7431)
        } catch (_: Throwable) {}

        try {
            PipelineHealthCollector.labelInc("EXACT_STRATEGY_OUTCOME_7429")
            PipelineHealthCollector.labelInc("EXACT_STRATEGY_OUTCOME_7429_" + cell.lane.take(24))
            if (env.entryTradeType.isBlank() || env.entrySetup.isBlank() || env.entryStyle.isBlank()) {
                PipelineHealthCollector.labelInc("EXACT_STRATEGY_ATTRIBUTION_INCOMPLETE_7429")
            } else {
                PipelineHealthCollector.labelInc("EXACT_STRATEGY_ATTRIBUTION_COMPLETE_7429")
            }
        } catch (_: Throwable) {}
        return true
    }

    /**
     * V5.0.7431 — bounded exact-playbook evidence for predictive admission.
     *
     * Variant ID is deliberately excluded from the lookup: the strategy
     * variant is a parameter experiment inside the playbook, while this read
     * answers whether the underlying tradeType/setup/style/tactic has earned
     * positive expectancy. LIVE prefers its own outcomes; until it has enough,
     * PAPER may seed the same playbook because canonical PAPER is the
     * deployment-quality rehearsal book.
     */
    data class Evidence7431(
        val mode: String,
        val lane: String,
        val tradeType: String,
        val setup: String,
        val style: String,
        val tactic: String,
        val n: Long,
        val wins: Long,
        val losses: Long,
        val meanPnlPct: Double,
        val winRatePct: Double,
        val source: String,
    )

    private fun aggregate7431(
        mode: String,
        lane: String,
        tradeType: String,
        setup: String,
        style: String,
        tactic: String,
    ): Evidence7431? {
        val k = playbookKey7431(mode, lane, tradeType, setup, style, tactic)
        val row = playbookCells7431[k] ?: return null
        val n = row.n.get()
        if (n <= 0L) return null
        val wins = row.wins.get()
        val losses = row.losses.get()
        val mean = row.pnlPctX1000.get().toDouble() / 1000.0 / n.toDouble()
        val wr = if (wins + losses > 0L) wins * 100.0 / (wins + losses).toDouble() else 0.0
        return Evidence7431(
            row.mode, row.lane, row.tradeType, row.setup, row.style, row.tactic,
            n, wins, losses, mean, wr, "EXACT",
        )
    }

    fun evidenceFor7431(
        liveMode: Boolean,
        lane: String,
        tradeType: String,
        setup: String,
        style: String,
        tactic: String,
    ): Evidence7431? {
        ensureRestored7431()
        if (lane.isBlank() || tradeType.isBlank() || setup.isBlank() || style.isBlank() || tactic.isBlank()) return null
        if (liveMode) {
            val live = aggregate7431("LIVE", lane, tradeType, setup, style, tactic)
            if (live != null && live.n >= 3L) {
                try { PipelineHealthCollector.labelInc("EXACT_STRATEGY_EV_LIVE_READ_7431") } catch (_: Throwable) {}
                return live.copy(source = "LIVE_EXACT")
            }
            val paper = aggregate7431("PAPER", lane, tradeType, setup, style, tactic)
            if (paper != null && paper.n >= 5L) {
                try { PipelineHealthCollector.labelInc("EXACT_STRATEGY_EV_PAPER_SEED_READ_7431") } catch (_: Throwable) {}
                return paper.copy(source = "PAPER_SEED")
            }
            return null
        }
        val paper = aggregate7431("PAPER", lane, tradeType, setup, style, tactic)
        if (paper != null && paper.n >= 3L) {
            try { PipelineHealthCollector.labelInc("EXACT_STRATEGY_EV_PAPER_READ_7431") } catch (_: Throwable) {}
            return paper.copy(source = "PAPER_EXACT")
        }
        return null
    }

    fun snapshots(): List<Snapshot> = cells.entries.map { (k, c) ->
        val n = c.n.get().coerceAtLeast(1L)
        Snapshot(
            key = k,
            mode = c.mode,
            lane = c.lane,
            tradeType = c.tradeType,
            setup = c.setup,
            style = c.style,
            tactic = c.tactic,
            variantId = c.variantId,
            n = c.n.get(),
            wins = c.wins.get(),
            losses = c.losses.get(),
            meanPnlPct = c.pnlPctX1000.get().toDouble() / 1000.0 / n,
            pnlSol = c.pnlSolLamports.get().toDouble() / 1_000_000_000.0,
            meanMfePct = c.mfePctX1000.get().toDouble() / 1000.0 / n,
            meanMaePct = c.maePctX1000.get().toDouble() / 1000.0 / n,
            meanHoldMinutes = c.holdMs.get().toDouble() / 60_000.0 / n,
        )
    }

    fun statusLine(limit: Int = 8): String {
        val rows = snapshots().sortedWith(compareByDescending<Snapshot> { it.n }.thenByDescending { it.meanPnlPct })
        if (rows.isEmpty()) return "keys=0 outcomes=0"
        val total = rows.sumOf { it.n }
        val complete = rows.filterNot {
            it.tradeType.startsWith("UNSTAMPED") ||
                it.setup.startsWith("UNSTAMPED") ||
                it.style.startsWith("UNSTAMPED")
        }.sumOf { it.n }
        val top = rows.take(limit).joinToString(" · ") {
            "${it.mode}/${it.lane}/${it.tradeType}/${it.setup}/${it.style}/${it.tactic}" +
                "[n=${it.n} wr=${"%.0f".format(it.winRatePct)}% ev=${"%+.1f".format(it.meanPnlPct)}% pnl=${"%+.3f".format(it.pnlSol)}]"
        }
        return "keys=${rows.size} outcomes=$total complete=$complete/$total top=$top"
    }

    internal fun clearForTest7429() { cells.clear(); playbookCells7431.clear(); restored7431.set(true) }
}
