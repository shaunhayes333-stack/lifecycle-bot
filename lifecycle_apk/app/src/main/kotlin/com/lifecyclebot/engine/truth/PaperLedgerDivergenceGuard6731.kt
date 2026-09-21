package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6743 — PAPER economic-integrity admission guard.
 *
 * New PAPER exposure requires a current, revision-consistent parity proof.
 * Missing, stale, or mutation-raced parity is INCONCLUSIVE and therefore
 * blocks NEW admissions. This guard is not consulted by exits, so recovery
 * and inventory drainage remain available while evidence reconverges.
 *
 * Never mutates ledger, journal, replay carry, positions, or capital.
 */
object PaperLedgerDivergenceGuard6731 {
    private const val CASH_DELTA_HARD_STOP_SOL = 3.0
    private const val OPEN_COST_DELTA_HARD_STOP_SOL = 5.0
    private const val POSITION_COUNT_GAP_HARD_STOP = 10
    private const val PARITY_MAX_AGE_MS = 15_000L

    data class Verdict(
        val allow: Boolean,
        val reason: String,
        val cashDelta: Double,
        val openCostDelta: Double,
        val realizedDelta: Double,
        val positionCountGap: Int,
        val divergenceTag: String,
        val eventSchemaRevision: Long = 0L,
        val journalRevisionAtStart: Long = 0L,
        val journalRevisionAtEnd: Long = 0L,
        val revisionRaceObserved: Boolean = false,
    )

    fun evaluate(): Verdict {
        val parity = try { CanonicalPaperReplay6464.lastParity() } catch (_: Throwable) { null }
        if (parity == null) {
            try { PipelineHealthCollector.labelInc("PAPER_LEDGER_PARITY_INCONCLUSIVE_NO_SNAPSHOT_6743") } catch (_: Throwable) {}
            return Verdict(false, "PAPER_LEDGER_PARITY_UNAVAILABLE_6743", 0.0, 0.0, 0.0, 0, "INCONCLUSIVE_NO_PARITY")
        }

        fun stamped(
            allow: Boolean, reason: String, cashDelta: Double, openCostDelta: Double,
            realizedDelta: Double, positionCountGap: Int, tag: String,
            race: Boolean = parity.revisionRaceObserved,
        ) = Verdict(
            allow = allow, reason = reason, cashDelta = cashDelta,
            openCostDelta = openCostDelta, realizedDelta = realizedDelta,
            positionCountGap = positionCountGap, divergenceTag = tag,
            eventSchemaRevision = parity.eventSchemaRevision,
            journalRevisionAtStart = parity.journalRevisionAtStart,
            journalRevisionAtEnd = parity.journalRevisionAtEnd,
            revisionRaceObserved = race,
        )

        if (parity.revisionRaceObserved) {
            try { PipelineHealthCollector.labelInc("PAPER_LEDGER_PARITY_INCONCLUSIVE_REVISION_RACE_6743") } catch (_: Throwable) {}
            return stamped(
                false, "PAPER_LEDGER_PARITY_REVISION_RACE_6743",
                parity.cashDelta, parity.openCostDelta, parity.realizedDelta,
                parity.orphanLotCount,
                "INCONCLUSIVE_REVISION_RACE_${parity.journalRevisionAtStart}_${parity.journalRevisionAtEnd}",
                race = true,
            )
        }

        val parityAge = try { CanonicalPaperReplay6464.lastParityAgeMs() } catch (_: Throwable) { Long.MAX_VALUE }
        if (parityAge > PARITY_MAX_AGE_MS) {
            try { PipelineHealthCollector.labelInc("PAPER_LEDGER_PARITY_INCONCLUSIVE_STALE_6743") } catch (_: Throwable) {}
            return stamped(
                false, "PAPER_LEDGER_PARITY_STALE_6743",
                parity.cashDelta, parity.openCostDelta, parity.realizedDelta,
                parity.orphanLotCount, "INCONCLUSIVE_STALE_${parityAge}ms", race = false,
            )
        }

        val cashDeltaAbs = kotlin.math.abs(parity.cashDelta)
        val openDeltaAbs = kotlin.math.abs(parity.openCostDelta)
        val posGap = parity.orphanLotCount
        val tag = "orphans=${parity.orphanLotCount}_qtyMM=${parity.qtyMismatchCount}"

        if (cashDeltaAbs >= CASH_DELTA_HARD_STOP_SOL) {
            try {
                PipelineHealthCollector.labelInc("PAPER_LEDGER_DIVERGENCE_HARD_STOP_CASH_6731")
                PipelineHealthCollector.labelInc("PAPER_LEDGER_DIVERGENCE_HARD_STOP_CASH_6731_${bucketFor(cashDeltaAbs)}")
            } catch (_: Throwable) {}
            return stamped(false, "PAPER_LEDGER_DIVERGENCE_CASH_6731",
                parity.cashDelta, parity.openCostDelta, parity.realizedDelta, posGap, tag, race = false)
        }
        if (openDeltaAbs >= OPEN_COST_DELTA_HARD_STOP_SOL) {
            try {
                PipelineHealthCollector.labelInc("PAPER_LEDGER_DIVERGENCE_HARD_STOP_OPEN_COST_6731")
                PipelineHealthCollector.labelInc("PAPER_LEDGER_DIVERGENCE_HARD_STOP_OPEN_COST_6731_${bucketFor(openDeltaAbs)}")
            } catch (_: Throwable) {}
            return stamped(false, "PAPER_LEDGER_DIVERGENCE_OPEN_COST_6731",
                parity.cashDelta, parity.openCostDelta, parity.realizedDelta, posGap, tag, race = false)
        }
        if (posGap >= POSITION_COUNT_GAP_HARD_STOP) {
            try { PipelineHealthCollector.labelInc("PAPER_LEDGER_DIVERGENCE_HARD_STOP_POS_GAP_6731") } catch (_: Throwable) {}
            return stamped(false, "PAPER_LEDGER_DIVERGENCE_POS_GAP_6731",
                parity.cashDelta, parity.openCostDelta, parity.realizedDelta, posGap, tag, race = false)
        }
        return stamped(true, "OK", parity.cashDelta, parity.openCostDelta, parity.realizedDelta, posGap, tag, race = false)
    }

    private fun bucketFor(delta: Double): String = when {
        delta < 5.0 -> "LT_5SOL"
        delta < 10.0 -> "LT_10SOL"
        delta < 20.0 -> "LT_20SOL"
        delta < 50.0 -> "LT_50SOL"
        else -> "GT_50SOL"
    }

    fun diagnosticLine(): String {
        val v = evaluate()
        return "PAPER_LEDGER_GUARD_6731 allow=${v.allow} reason=${v.reason} cashΔ=${"%.4f".format(v.cashDelta)} openCostΔ=${"%.4f".format(v.openCostDelta)} realizedΔ=${"%.4f".format(v.realizedDelta)} posGap=${v.positionCountGap} tag=${v.divergenceTag}"
    }
}
