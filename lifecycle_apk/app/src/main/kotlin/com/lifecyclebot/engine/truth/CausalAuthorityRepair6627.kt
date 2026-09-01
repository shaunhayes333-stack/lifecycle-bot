package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6627 §2 SPECIALIST_CAUSAL_INVARIANTS.
 *
 * Operator (Feb 2026):
 *   "Current counters are impossible:
 *      CORE fdgAllow=0 exec=14
 *      PROJECT_SNIPER fdgAllow=0 exec=45
 *      SHITCOIN buyIntent=1 fdgAllow=22 exec=16
 *      MOONSHOT sellConfirmed=0 finalized=3
 *
 *   Add hard diagnostic invariants:
 *     EXEC > 0 && FDG_ALLOW == 0 => CAUSAL_COUNTER_CORRUPTION
 *     FINALIZED > SELL_CONFIRMED => CAUSAL_COUNTER_CORRUPTION
 *   These invariants are telemetry alarms only; never block trading."
 *
 * The V5.0.6625 SpecialistCausalFunnel6625 already stamps ONE record per
 * (runId, mode, mint, lane, authorityVersion, intentId). This module
 * reads the current stage counts per lane from that authority and fires
 * a CAUSAL_COUNTER_CORRUPTION_* label + lifecycle event whenever an
 * impossible combination is observed. Called from the pipeline dump
 * cadence — no hot-path overhead.
 *
 * Invariants (all telemetry-only, never block):
 *   §I1 EXEC > 0 && FDG allow-record == 0
 *   §I2 FINALIZE > SELL
 *   §I3 EXEC > INTENT
 *   §I4 TICKET > SIZE
 */
object SpecialistCausalInvariants6627 {

    private val corruptionAlarms = AtomicLong(0L)
    private val cleanScans = AtomicLong(0L)
    private val scans = AtomicLong(0L)

    private val lanesToScan6627 = listOf(
        "CORE", "SHITCOIN", "MOONSHOT", "PROJECT_SNIPER", "EXPRESS",
        "BLUECHIP", "QUALITY", "DIP_HUNTER", "MANIPULATED", "TREASURY",
        "CASHGEN", "CYCLIC",
    )

    /**
     * Runs a single scan across every meme specialist lane, firing an
     * alarm counter for each impossible combination. Returns the total
     * number of alarms raised this scan. Safe to call from any thread
     * (reads only).
     */
    fun scan6627(): Long {
        scans.incrementAndGet()
        var alarms = 0L
        for (lane in lanesToScan6627) {
            val counts = try {
                SpecialistCausalFunnel6625.stageCounts6625(lane)
            } catch (_: Throwable) { emptyMap() }
            val intent = (counts[SpecialistCausalFunnel6625.Stage.INTENT] ?: 0).toLong()
            val fdg = (counts[SpecialistCausalFunnel6625.Stage.FDG] ?: 0).toLong()
            val size = (counts[SpecialistCausalFunnel6625.Stage.SIZE] ?: 0).toLong()
            val ticket = (counts[SpecialistCausalFunnel6625.Stage.TICKET] ?: 0).toLong()
            val exec = (counts[SpecialistCausalFunnel6625.Stage.EXEC] ?: 0).toLong()
            val sell = (counts[SpecialistCausalFunnel6625.Stage.SELL] ?: 0).toLong()
            val finalize = (counts[SpecialistCausalFunnel6625.Stage.FINALIZE] ?: 0).toLong()

            if (exec > 0L && fdg == 0L) alarms += fireAlarm6627(
                lane, "EXEC_WITHOUT_FDG_RECORD", "exec=$exec fdg=$fdg intent=$intent",
            )
            if (finalize > sell) alarms += fireAlarm6627(
                lane, "FINALIZE_EXCEEDS_SELL", "finalize=$finalize sell=$sell",
            )
            if (exec > intent && intent > 0L) alarms += fireAlarm6627(
                lane, "EXEC_EXCEEDS_INTENT", "exec=$exec intent=$intent",
            )
            if (ticket > size && size > 0L) alarms += fireAlarm6627(
                lane, "TICKET_EXCEEDS_SIZE", "ticket=$ticket size=$size",
            )
        }
        if (alarms == 0L) cleanScans.incrementAndGet()
        else corruptionAlarms.addAndGet(alarms)
        return alarms
    }

    private fun fireAlarm6627(lane: String, invariant: String, detail: String): Long {
        try {
            PipelineHealthCollector.labelInc("CAUSAL_COUNTER_CORRUPTION_6627")
            PipelineHealthCollector.labelInc("CAUSAL_COUNTER_CORRUPTION_${lane}_${invariant}_6627")
            ForensicLogger.lifecycle(
                "CAUSAL_COUNTER_CORRUPTION_6627",
                "lane=$lane invariant=$invariant $detail action=telemetry_alarm_only",
            )
        } catch (_: Throwable) {}
        return 1L
    }

    fun statusLine6627(): String =
        "scans=${scans.get()} clean=${cleanScans.get()} alarms=${corruptionAlarms.get()}"

    /** V5.0.6627 test-only reset. */
    fun resetForTest() {
        corruptionAlarms.set(0L)
        cleanScans.set(0L)
        scans.set(0L)
    }
}

/**
 * V5.0.6627 §7 OPEN_POSITION_ENTRY_BASIS_INVARIANT.
 *
 * Operator (Feb 2026):
 *   "A position cannot transition to OPEN unless:
 *      entryPrice > 0, entryQty > 0, entryNotional > 0
 *
 *    If legacy/open projection has entryPrice <= 0:
 *      recover from canonical BUY event / fill lot.
 *      Do not fabricate 0.0.
 *
 *    Acceptance: OPEN_PNL_BASIS_REJECTED = 0 for newly created positions."
 *
 * This module is called from the canonical position OPEN transition
 * paths (paperBuy + liveBuy admission) to record whether the entry
 * basis is authoritative. Zero-basis creations bump an alarm counter
 * so the operator can grep the exact count from the pipeline dump.
 *
 * The heal path in OpenPnlSanity is the REACTIVE safety net; this
 * module is the PROACTIVE alarm so the source of zero-basis
 * positions is visible without waiting for the first inspect() cycle.
 */
object OpenPositionBasisInvariant6627 {

    private val proactive0BasisAlarms = AtomicLong(0L)
    private val proactive0QtyAlarms = AtomicLong(0L)
    private val cleanOpens = AtomicLong(0L)
    private val totalOpens = AtomicLong(0L)

    /**
     * Called at canonical position OPEN. Records whether the entry
     * basis is authoritative. `mint` is used for the ForensicLogger row;
     * the counter itself is aggregate.
     */
    fun onCanonicalOpen6627(
        mint: String,
        lane: String,
        entryPrice: Double,
        entryQty: Double,
        entryNotionalSol: Double,
    ) {
        totalOpens.incrementAndGet()
        val basisAuthoritative6627 =
            entryPrice.isFinite() && entryPrice > 0.0 &&
                entryQty.isFinite() && entryQty > 0.0 &&
                entryNotionalSol.isFinite() && entryNotionalSol > 0.0
        if (basisAuthoritative6627) {
            cleanOpens.incrementAndGet()
            try { PipelineHealthCollector.labelInc("OPEN_POSITION_BASIS_AUTHORITATIVE_6627") } catch (_: Throwable) {}
            return
        }
        if (!entryPrice.isFinite() || entryPrice <= 0.0) {
            proactive0BasisAlarms.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("OPEN_POSITION_ZERO_ENTRY_PRICE_6627")
                ForensicLogger.lifecycle(
                    "OPEN_POSITION_ZERO_ENTRY_PRICE_6627",
                    "mint=${mint.take(10)} lane=$lane entryPrice=$entryPrice " +
                        "entryQty=$entryQty entryNotionalSol=$entryNotionalSol " +
                        "action=alarm_and_defer_to_reactive_heal",
                )
            } catch (_: Throwable) {}
        }
        if (!entryQty.isFinite() || entryQty <= 0.0) {
            proactive0QtyAlarms.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("OPEN_POSITION_ZERO_ENTRY_QTY_6627")
                ForensicLogger.lifecycle(
                    "OPEN_POSITION_ZERO_ENTRY_QTY_6627",
                    "mint=${mint.take(10)} lane=$lane entryPrice=$entryPrice " +
                        "entryQty=$entryQty entryNotionalSol=$entryNotionalSol",
                )
            } catch (_: Throwable) {}
        }
    }

    fun statusLine6627(): String =
        "opens=${totalOpens.get()} clean=${cleanOpens.get()} " +
            "zeroPrice=${proactive0BasisAlarms.get()} zeroQty=${proactive0QtyAlarms.get()}"

    /** V5.0.6627 test-only reset. */
    fun resetForTest() {
        proactive0BasisAlarms.set(0L)
        proactive0QtyAlarms.set(0L)
        cleanOpens.set(0L)
        totalOpens.set(0L)
    }
}
