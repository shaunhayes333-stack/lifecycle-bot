package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8189 — A PROFIT THE SLICE CANNOT BANK IS BANKED WHOLE.
 *
 * 5.0.8186 live: positions of ~0.029 SOL. A 60% profit rung is ~0.017 SOL; the exit leg's fixed fee and venue cost
 * consume its net (PARTIAL_EXIT_NET_UNECONOMIC, COST_PARTIAL_FIXED_FEE_HELD_8151), so every rung was skipped and the
 * inventory kept — at this size the ladder could never bank anything (bank8153 banked / opportunity = 6%).
 *
 * When a DISCRETIONARY profit slice is uneconomic, the whole remaining position is evaluated instead: one exit leg
 * pays the fixed costs once on the full proceeds. If the full exit nets a profit after its costs, the take-profit
 * sells the whole position ([Verdict8189.ESCALATE_FULL]); otherwise nothing is sold ([Verdict8189.KEEP]). A position
 * with runner evidence (a lifecycle hold) or a confirmed runner continuation keeps its residual: no escalation.
 * The full exit still passes ProfitExitTruth8163 (executable SOL over the exact remaining cost) on its own quote, so a
 * mark can never bank a loss.
 */
object ProfitEscalation8189 {

    enum class Verdict8189 { SLICE, ESCALATE_FULL, KEEP }

    const val REASON_PREFIX_8189 = "PROFIT_FULL_HARVEST_8189_"

    private val escalated = AtomicLong(0)
    private val kept = AtomicLong(0)

    /**
     * Pure. [fullProceedsSol] = the whole remaining position at the executable mark; [remainingCostSol] = its cost basis;
     * [exitCostPct] = the exit leg's variable cost; [fixedFeeSol] = its fixed fee.
     */
    fun verdict8189(sliceUneconomic: Boolean, fullProceedsSol: Double, remainingCostSol: Double, exitCostPct: Double,
                    fixedFeeSol: Double, runnerProtected: Boolean): Verdict8189 {
        if (!sliceUneconomic) return Verdict8189.SLICE
        if (runnerProtected) return Verdict8189.KEEP
        if (!fullProceedsSol.isFinite() || fullProceedsSol <= 0.0 || !remainingCostSol.isFinite() || remainingCostSol <= 0.0) return Verdict8189.KEEP
        val fixed = if (fixedFeeSol.isFinite() && fixedFeeSol > 0.0) fixedFeeSol else 0.0
        val net = PartialNetEconomics8094.sliceNetSol8094(fullProceedsSol, remainingCostSol, exitCostPct) - fixed
        val economic = net > 0.0 && !PartialNetEconomics8094.uneconomic8094(fullProceedsSol, remainingCostSol + fixed, exitCostPct)
        return if (economic) Verdict8189.ESCALATE_FULL else Verdict8189.KEEP
    }

    /** Pure: the full exit's reason (a profit harvest: ProfitExitTruth8163 still checks it on its own quote). */
    fun reason8189(sliceReason: String): String = REASON_PREFIX_8189 + sliceReason.take(60)

    fun count8189(v: Verdict8189) {
        when (v) {
            Verdict8189.ESCALATE_FULL -> { escalated.incrementAndGet(); try { PipelineHealthCollector.labelInc("PROFIT_SLICE_ESCALATED_FULL_8189") } catch (_: Throwable) {} }
            Verdict8189.KEEP -> { kept.incrementAndGet(); try { PipelineHealthCollector.labelInc("PROFIT_SLICE_UNECONOMIC_KEPT_8189") } catch (_: Throwable) {} }
            Verdict8189.SLICE -> {}
        }
    }

    fun statusLine8189(): String = "profitEscalation8189[full=${escalated.get()} kept=${kept.get()}]"
}
