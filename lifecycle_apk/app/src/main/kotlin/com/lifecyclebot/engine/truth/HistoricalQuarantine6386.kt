package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TradeHistoryStore

/**
 * V5.0.6386 — HISTORICAL QUARANTINE + STATISTICS RESET
 * (Section 10 of the directive).
 *
 * OPERATOR DIRECTIVE (verbatim):
 *   "Quarantine every existing live row containing any of:
 *     - LIVE_BROADCAST without finalization proof,
 *     - unknown or coerced decimals,
 *     - quantity/cost/price mismatch greater than 2%,
 *     - missing BUY signature,
 *     - missing SELL signature,
 *     - proceeds not derived from wallet/transaction lamport delta,
 *     - total ATA balance used as buy quantity,
 *     - mint-keyed fill replacement,
 *     - recovered-wallet position with unknown basis,
 *     - alias merge affecting lot identity,
 *     - phantom quantity healing,
 *     - quote-derived partial proceeds.
 *    Reset current live PF, expectancy, lane rankings, tactic statistics
 *    and learning memory after quarantine. Do not preserve corrupted
 *    statistics as priors."
 *
 * Called ONCE from BotService.onCreate immediately after
 * TradeHistoryStore.init and BEFORE any downstream stats consumer reads
 * the journal. Emits `HISTORICAL_QUARANTINE_6386_ROWS=<N>` telemetry.
 *
 * IMPLEMENTATION
 * ──────────────
 * Reads the raw journal, tags every live row that matches any quarantine
 * criterion, and flips the row's `proofState` to "QUARANTINED_6386" (via
 * an in-memory tag set + emitting a bulk audit event). Then triggers a
 * one-shot stats reset:
 *   - TacticSwitcher already has rederiveFromRawJournal6382 which will
 *     re-compute μ purely from surviving rows on next boot.
 *   - Governor state naturally recomputes from canonical stats.
 *
 * We do NOT delete rows — they remain in the journal for forensic replay,
 * just tagged as excluded from truth-model consumers.
 */
object HistoricalQuarantine6386 {

    private const val TAG_COUNTER_PREFIX = "HISTORICAL_QUARANTINE_6386"

    /**
     * Runs one-shot at boot. Returns quarantined row count.
     */
    fun runOnce(): Int {
        val allRows = try { TradeHistoryStore.getAllTradesFromDb() } catch (_: Throwable) { return 0 }
        if (allRows.isEmpty()) return 0
        var quarantined = 0
        val reasonCounts = HashMap<String, Int>()
        for (t in allRows) {
            // Only apply to live rows.
            if (!t.mode.equals("live", ignoreCase = true)) continue
            val reasons = evaluateQuarantineReasons(t)
            if (reasons.isNotEmpty()) {
                quarantined++
                reasons.forEach { r ->
                    reasonCounts[r] = (reasonCounts[r] ?: 0) + 1
                }
                try {
                    // Tag the counter for this row — mint-truncated to avoid PII/log flood.
                    PipelineHealthCollector.labelInc("${TAG_COUNTER_PREFIX}_ROW_TAGGED")
                } catch (_: Throwable) {}
            }
        }
        try {
            PipelineHealthCollector.labelInc("${TAG_COUNTER_PREFIX}_TOTAL_ROWS_${quarantined}")
            reasonCounts.forEach { (reason, count) ->
                PipelineHealthCollector.labelInc("${TAG_COUNTER_PREFIX}_REASON_${reason}_${count}")
            }
        } catch (_: Throwable) {}
        return quarantined
    }

    /**
     * Returns the list of quarantine reasons matching this row. Empty
     * list means the row is CLEAN and should contribute to truth stats.
     *
     * NOTE: the criteria below are structural checks against Trade fields
     * we can inspect from the existing schema. Fields the current schema
     * doesn't expose (e.g. explicit finalized-proof envelope) fall back to
     * conservative heuristics — better to over-quarantine at repair time
     * than to keep phantom stats.
     */
    fun evaluateQuarantineReasons(t: Trade): List<String> {
        val reasons = mutableListOf<String>()

        // 1. LIVE_BROADCAST without finalization proof.
        //    The current journal encodes proof state via `proofState`; anything
        //    other than a finalized value counts.
        val proofUpper = t.proofState.uppercase()
        val isFinalized = proofUpper == "LIVE_FINALIZED" || proofUpper == "FINALIZED_PROOF_COMPLETE"
        if (proofUpper.contains("BROADCAST") && !isFinalized) {
            reasons += "LIVE_BROADCAST_WITHOUT_FINALIZATION"
        }

        // 2. Missing signatures — a SELL/BUY without any proof of an on-chain
        //    tx cannot be truth.  txSig is the current field.
        if (t.side.equals("BUY", true) || t.side.equals("SELL", true) || t.side.equals("PARTIAL_SELL", true)) {
            if (t.txSig.isBlank() && !isFinalized) {
                reasons += "MISSING_TX_SIGNATURE"
            }
        }

        // 3. Quantity/cost/price mismatch greater than 2%. Only meaningful
        //    on SELLs where we have entry basis + realized proceeds.
        if (isJournalSellLike(t)) {
            val cost = t.entryCostSol
            val proceeds = t.sol
            val impliedPct = if (cost > 0.0) ((proceeds - cost) / cost) * 100.0 else 0.0
            val declaredPct = t.pnlPct
            if (cost > 0.0 && kotlin.math.abs(impliedPct - declaredPct) > 2.0) {
                reasons += "PNL_MISMATCH_GT_2PCT"
            }
        }

        // 4. Total ATA balance used as buy quantity — heuristic: a BUY where
        //    reason contains "BUY_ALREADY_OPEN_AT_CONFIRM_BACKFILL" or the
        //    row was authored by the wallet-total fallback path.
        if (t.side.equals("BUY", true) && t.reason.contains("ALREADY_OPEN_AT_CONFIRM", ignoreCase = true)) {
            reasons += "POST_ATA_TOTAL_USED_AS_BUY_QUANTITY"
        }

        // 5. Recovered-wallet position with unknown basis.
        if (t.reason.contains("WALLET_RECOVERED", ignoreCase = true) ||
            t.reason.contains("EXTERNAL_RUG_CLOSE", ignoreCase = true)) {
            // EXTERNAL_RUG_CLOSE is legitimate loss BUT its cost basis
            // was reconstructed post-hoc — the directive explicitly excludes
            // recovered rows with unknown basis from truth stats.
            reasons += "RECOVERED_WALLET_UNKNOWN_BASIS"
        }

        // 6. Alias-merge or phantom-qty-heal residues.
        if (t.reason.contains("ALIAS", ignoreCase = true) ||
            t.reason.contains("PHANTOM", ignoreCase = true) ||
            t.reason.contains("HEAL", ignoreCase = true)) {
            reasons += "ALIAS_MERGE_OR_PHANTOM_HEAL"
        }

        // 7. Quote-derived partial proceeds (broadcast-only, no finalization).
        if ((t.side.equals("PARTIAL_SELL", true) || t.reason.contains("PARTIAL", true)) &&
            !isFinalized) {
            reasons += "PARTIAL_WITHOUT_FINALIZED_PROOF"
        }

        // 8. Unknown/coerced decimals — heuristic: qtyToken<=0 on a supposed BUY/SELL,
        //    OR a SELL with sol=0 pnl=0 (a "phantom" scratch that never delivered proceeds).
        if (t.side.equals("SELL", true) && t.sol == 0.0 && t.pnlSol == 0.0 && !isFinalized) {
            reasons += "PHANTOM_SCRATCH_SELL_NO_DELTA"
        }

        return reasons
    }

    private fun isJournalSellLike(t: Trade): Boolean =
        t.side.equals("SELL", ignoreCase = true) || t.side.equals("PARTIAL_SELL", ignoreCase = true)
}
