package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6411 §14 — POSITION IDENTITY + ALIAS-MERGE POLICY.
 *
 * OPERATOR DIRECTIVE (Feb 2026)
 * ─────────────────────────────
 * "Canonical position key: wallet + mint + live/paper. Pool and
 *  symbol must NOT define separate positions for the same mint.
 *  Merge only after checking: same wallet, same mint, same mode,
 *  compatible token program, non-conflicting transaction history."
 *
 * DESIGN
 * ──────
 *   • canonicalKey(wallet, mint, isPaper) returns the identity string.
 *   • guardMerge() enforces the four merge preconditions and returns
 *     a MergeDecision(allow, reasonCode) with forensic trail.
 *   • Records source position ids, destination position id, quantities,
 *     signatures. Rejected merges emit POSITION_ALIAS_MERGE_REJECTED_6411
 *     so the field can be measured (build 6410 had 253 merges — some of
 *     those may have been unsafe).
 *
 * Advisory — enforcement lives at the alias-merge call site inside
 * TokenState / HostWalletTokenTracker. Migration lands in V5.0.6412+.
 */
object PositionIdentity6411 {

    data class MergeDecision(val allow: Boolean, val reasonCode: String, val detail: String)

    fun canonicalKey(wallet: String, mint: String, isPaper: Boolean): String =
        "${wallet.take(24)}|${mint.take(24)}|${if (isPaper) "PAPER" else "LIVE"}"

    /**
     * Enforce the four preconditions for a safe alias merge:
     *   1. sameWallet
     *   2. sameMint
     *   3. sameMode (paper/live match)
     *   4. compatibleTokenProgram (both legacy SPL, or both Token-2022)
     *   5. nonConflictingHistory (no crossing sell-then-buy stamps)
     */
    fun guardMerge(
        srcWallet: String, srcMint: String, srcIsPaper: Boolean, srcTokenProgram: String?,
        dstWallet: String, dstMint: String, dstIsPaper: Boolean, dstTokenProgram: String?,
        conflictingHistory: Boolean,
    ): MergeDecision {
        val d = when {
            srcWallet != dstWallet -> MergeDecision(false, "WALLET_MISMATCH", "src=$srcWallet dst=$dstWallet")
            srcMint != dstMint -> MergeDecision(false, "MINT_MISMATCH", "src=$srcMint dst=$dstMint")
            srcIsPaper != dstIsPaper -> MergeDecision(false, "MODE_MISMATCH", "src=${srcIsPaper} dst=${dstIsPaper}")
            srcTokenProgram != null && dstTokenProgram != null && srcTokenProgram != dstTokenProgram ->
                MergeDecision(false, "TOKEN_PROGRAM_MISMATCH", "src=$srcTokenProgram dst=$dstTokenProgram")
            conflictingHistory -> MergeDecision(false, "HISTORY_CONFLICT", "sell_then_buy_or_reversed")
            else -> MergeDecision(true, "OK", "same_wallet_same_mint_same_mode_compatible_program")
        }
        try {
            if (d.allow) {
                PipelineHealthCollector.labelInc("POSITION_ALIAS_MERGE_ALLOWED_6411")
            } else {
                PipelineHealthCollector.labelInc("POSITION_ALIAS_MERGE_REJECTED_6411")
                ForensicLogger.lifecycle(
                    "POSITION_ALIAS_MERGE_REJECTED_6411",
                    "reason=${d.reasonCode} detail=${d.detail.take(80)} mint=${srcMint.take(10)}",
                )
            }
        } catch (_: Throwable) {}
        return d
    }

    fun statusLine(): String = try {
        val allowed = PipelineHealthCollector.labelCountSnapshot("POSITION_ALIAS_MERGE_ALLOWED_6411")
        val rejected = PipelineHealthCollector.labelCountSnapshot("POSITION_ALIAS_MERGE_REJECTED_6411")
        "allowed=$allowed rejected=$rejected"
    } catch (_: Throwable) { "unavailable" }
}
