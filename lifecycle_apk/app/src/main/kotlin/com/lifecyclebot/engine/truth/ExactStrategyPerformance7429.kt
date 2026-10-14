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
        /** V5.0.8082 — closes whose excursion was observed; mean MFE/MAE divide by this, not by n. */
        val excursionN8082 = AtomicLong(0L)
        val holdMs = AtomicLong(0L)
    }

    private val cells = ConcurrentHashMap<String, Cell>()
    // V5.0.7431 — O(1) admission index. Exact terminal rows still retain
    // variant identity in [cells], while this sibling aggregates across variant
    // IDs for the underlying playbook. Updated only on terminal close; admission
    // never scans the full strategy scoreboard.
    private val playbookCells7431 = ConcurrentHashMap<String, Cell>()

    private val restored7431 = AtomicBoolean(false)
    private val legacyUnstampedSkipped7432 = AtomicLong(0L)
    private const val PLAYBOOK_INDEX_KEY_7431 = "exact_strategy_playbook_index_7431"
    private fun playbookStorageKey7431(key: String) = "exact_strategy_playbook_7431_" + key

    @Synchronized private fun ensureRestored7431() {
        if (!LearningPersistence.ready() || !restored7431.compareAndSet(false, true)) return
        try {
            val raw = LearningPersistence.load(PLAYBOOK_INDEX_KEY_7431) ?: "[]"
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val k = arr.optString(i, "")
                if (k.isBlank()) continue
                val rowRaw = LearningPersistence.load(playbookStorageKey7431(k)) ?: continue
                val j = JSONObject(rowRaw)
                val parts = k.split('|')
                // V5.0.7432 — pre-7427 rows never had exact tradeType/setup/style
                // identity. They remain valid lane/tactic history, but are not an
                // exact playbook and must never seed current exact-strategy EV.
                if (parts.any { it.startsWith("UNSTAMPED_") }) {
                    legacyUnstampedSkipped7432.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("EXACT_STRATEGY_LEGACY_UNSTAMPED_SKIPPED_7432") } catch (_: Throwable) {}
                    continue
                }
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
                cell.excursionN8082.set(j.optLong("exN8082", 0L))
                cell.holdMs.set(j.optLong("hold", 0L))
                if (cell.n.get() > 0L) playbookCells7431[k] = cell
            }
            if (playbookCells7431.isNotEmpty()) {
                try { PipelineHealthCollector.labelInc("EXACT_STRATEGY_EV_RESTORED_7431") } catch (_: Throwable) {}
            }
            val exactIndex = JSONArray(LearningPersistence.load(EXACT_INDEX_8090) ?: "[]")
            for (i in 0 until exactIndex.length()) {
                val k = exactIndex.getString(i)
                val payload = LearningPersistence.load(exactStorage8090(k)) ?: error("Missing exact cell $k")
                cells[k] = decodeCell8090(k, JSONObject(payload))
            }
            if (LearningPersistence.readFailed8086(PLAYBOOK_INDEX_KEY_7431) || LearningPersistence.readFailed8086(EXACT_INDEX_8090))
                restored7431.set(false)
        } catch (_: Throwable) { restored7431.set(false) }
    }

    private const val EXACT_INDEX_8090 = "exact_strategy_index_8090"
    private fun exactStorage8090(key: String) = "exact_strategy_cell_8090_$key"
    private fun encodeCell8090(c: Cell): String = JSONObject()
        .put("n", c.n.get()).put("w", c.wins.get()).put("l", c.losses.get())
        .put("p", c.pnlPctX1000.get()).put("sol", c.pnlSolLamports.get())
        .put("mfe", c.mfePctX1000.get()).put("mae", c.maePctX1000.get())
        .put("exN8082", c.excursionN8082.get()).put("hold", c.holdMs.get()).toString()

    private fun decodeCell8090(key: String, j: JSONObject): Cell {
        val p = key.split('|')
        require(p.size == 6 || p.size == 7)
        return Cell(p[0], p[1], p[2], p[3], p[4], p[5], p.getOrElse(6) { "ALL_VARIANTS" }).also {
            it.n.set(j.optLong("n")); it.wins.set(j.optLong("w")); it.losses.set(j.optLong("l"))
            it.pnlPctX1000.set(j.optLong("p")); it.pnlSolLamports.set(j.optLong("sol"))
            it.mfePctX1000.set(j.optLong("mfe")); it.maePctX1000.set(j.optLong("mae"))
            it.excursionN8082.set(j.optLong("exN8082")); it.holdMs.set(j.optLong("hold"))
        }
    }

    private fun addOutcome8090(c: Cell, e: CanonicalFinalizedTradeBus6464.Envelope) {
        c.n.incrementAndGet()
        if (e.realizedPnlSol > 0.0) c.wins.incrementAndGet() else if (e.realizedPnlSol < 0.0) c.losses.incrementAndGet()
        c.pnlPctX1000.addAndGet((e.realizedReturnPct.coerceIn(-100.0, 100_000.0) * 1000.0).toLong())
        c.pnlSolLamports.addAndGet((e.realizedPnlSol * 1_000_000_000.0).toLong())
        if (e.mfePct.isFinite() && e.maePct.isFinite()) {
            c.excursionN8082.incrementAndGet()
            c.mfePctX1000.addAndGet((e.mfePct.coerceIn(-100.0, 100_000.0) * 1000.0).toLong())
            c.maePctX1000.addAndGet((e.maePct.coerceIn(-100_000.0, 100_000.0) * 1000.0).toLong())
        }
        c.holdMs.addAndGet(e.holdingTimeMs.coerceAtLeast(0L))
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

    /** Exact live strategy economic rewards require verified terminal blockchain settlement. */
    internal fun terminalEconomicsEligible8081(mode: String, proof: String): Boolean =
        if (mode.equals("LIVE", true))
            proof.equals("LIVE_FINALIZED", true) || proof.equals("LIVE_RECONCILED", true)
        else mode.equals("PAPER", true) && proof.equals("PAPER_SIMULATED", true)

    private const val SEEN_KEY_8082 = "exact_strategy_seen_positions_8082"
    /** Incomplete identity stays retryable; only the terminal transaction consumes economic credit. */
    @Synchronized fun record(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        if (!env.terminal || !env.learningEligible || env.positionId.isBlank()) return false
        if (!terminalEconomicsEligible8081(env.mode, env.proofState)) {
            CanonicalFinalizedTradeBus6464.exclude("ExactStrategyPerformance7429", env.tradeId, "UNVERIFIED_ECONOMICS")
            return true
        }
        if (!env.realizedReturnPct.isFinite() || !env.realizedPnlSol.isFinite()) return false
        // Recovery may only use the immutable snapshot of THIS position, never today's mint evaluation.
        val entry = EntryStrategySnapshot6450.snapshot(env.positionId)
        val e = if (entry != null && entry.mint == env.mint && entry.entryLane.equals(env.lane, true)) env.copy(
            entryTradeType = env.entryTradeType.ifBlank { entry.entryTradeType },
            entrySetup = env.entrySetup.ifBlank { entry.entrySetup },
            entryStyle = env.entryStyle.ifBlank { entry.entryStyle },
            entryTactic = env.entryTactic.ifBlank { entry.entryTactic },
            entryStrategyVariantId = env.entryStrategyVariantId.ifBlank { entry.entryStrategyVariantId },
        ) else env
        if (listOf(e.entryTradeType, e.entrySetup, e.entryStyle, e.entryTactic).any { it.isBlank() }) {
            PipelineHealthCollector.labelInc("EXACT_STRATEGY_ATTRIBUTION_INCOMPLETE_7429")
            return false
        }
        ensureRestored7431()
        if (!restored7431.get()) return false
        val k = key(e)
        val pk = playbookKey7431(e.mode, e.lane, e.entryTradeType, e.entrySetup, e.entryStyle, e.entryTactic)
        if (!cells.containsKey(k) && cells.size >= MAX_KEYS) return false
        val cell = decodeCell8090(k, JSONObject(cells[k]?.let(::encodeCell8090) ?: "{}"))
        val playbook = decodeCell8090(pk, JSONObject(playbookCells7431[pk]?.let(::encodeCell8090) ?: "{}"))
        val receipt = "exact_strategy_terminal_8090_${e.mode.uppercase()}_${e.positionId}"
        // The 8082 receipt alone cannot prove that a reward happened: it was consumed before validation.
        // Preserve old aggregates and explicitly exclude ambiguous legacy credit instead of doubling them.
        val oldSeen = JSONArray(LearningPersistence.load(SEEN_KEY_8082) ?: "[]")
        if (LearningPersistence.readFailed8086(SEEN_KEY_8082)) return false
        if ((0 until oldSeen.length()).any { oldSeen.optString(it) == e.positionId }) {
            CanonicalFinalizedTradeBus6464.exclude("ExactStrategyPerformance7429", e.tradeId, "LEGACY_8082_CREDIT_UNPROVABLE")
            return true
        }
        addOutcome8090(cell, e); addOutcome8090(playbook, e)
        val writes = linkedMapOf(
            exactStorage8090(k) to encodeCell8090(cell),
            playbookStorageKey7431(pk) to encodeCell8090(playbook),
            EXACT_INDEX_8090 to JSONArray((cells.keys + k).sorted()).toString(),
            PLAYBOOK_INDEX_KEY_7431 to JSONArray((playbookCells7431.keys + pk).sorted()).toString(),
            "exact_strategy_outcome_8090_${e.mode.uppercase()}_${e.positionId}" to JSONObject()
                .put("positionId", e.positionId).put("eventId", e.economicEventId).put("tradeId", e.tradeId)
                .put("key", k).put("proof", e.proofState).put("atMs", e.atMs)
                .put("netSol", e.realizedPnlSol).put("netReturnPct", e.realizedReturnPct).toString(),
        )
        when (LearningPersistence.commitOnce8090(receipt, writes)) {
            LearningPersistence.Commit8090.RETRY -> return false
            LearningPersistence.Commit8090.ALREADY_COMMITTED -> return true
            LearningPersistence.Commit8090.COMMITTED -> { cells[k] = cell; playbookCells7431[pk] = playbook }
        }
        PipelineHealthCollector.labelInc("EXACT_STRATEGY_OUTCOME_7429")
        PipelineHealthCollector.labelInc("EXACT_STRATEGY_ATTRIBUTION_COMPLETE_7429")
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

    fun snapshots(): List<Snapshot> {
        ensureRestored7431()
        return cells.entries.map { (k, c) ->
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
            meanMfePct = c.excursionN8082.get().let { en -> if (en > 0L) c.mfePctX1000.get().toDouble() / 1000.0 / en else Double.NaN },
            meanMaePct = c.excursionN8082.get().let { en -> if (en > 0L) c.maePctX1000.get().toDouble() / 1000.0 / en else Double.NaN },
            meanHoldMinutes = c.holdMs.get().toDouble() / 60_000.0 / n,
        )
    }

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
        return "keys=${rows.size} outcomes=$total complete=$complete/$total legacyUnstampedSkipped=${legacyUnstampedSkipped7432.get()} top=$top"
    }

    internal fun clearForTest7429() { cells.clear(); playbookCells7431.clear(); restored7431.set(true) }
}
