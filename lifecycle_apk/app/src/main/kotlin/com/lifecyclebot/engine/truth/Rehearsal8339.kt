package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.8339 §REHEARSAL_HAS_NO_WALLET — 8336 rehearsal decides PAPER meme entries with the LIVE (8114) decision so the
 * paper record measures the live entry policy. Two of the doors that decision runs are not policy but LIVE ACCOUNT
 * proofs, and in a PAPER runtime they refused every paper entry (5.0.8336 runtime smoke: paperBuyTickets=0):
 *   - Entry8114Gate8326.liveVerdict8114 runs LiveBootRecovery8074.blind8074 — "the wallet cannot be read, refuse the live
 *     buy". A paper book has no wallet; in the smoke JVM / a paper device the wallet is never readable, so the door
 *     refused paper forever — and with it the evidence the hold needs before any lane can go live (a deadlock of the
 *     owner's own 8336 rule).
 *   - Entry8114Mode8326.decide8326 computed the shadow verdict with paper = false in a paper runtime, which runs the live
 *     chain / wallet doors (OnChainDepth live requests, CandidateAdmission live prefetch, WALLET_BLIND) and binds their
 *     hard markers; before 8336 the paper runtime never ran them.
 * The rule: the live wallet door binds a LIVE runtime only; the shadow is computed for the runtime's own book. Policy
 * doors (proven setup, timing / tactic records, 8329 realised closes, hard token safety) are untouched: rehearsal stays
 * on, and a paper admit is still never a live admit (Entry8114Mode8326.fileFinal8326, 8336).
 */
object Rehearsal8339 {
    const val WALLET_DOOR_SKIPPED_8339 = "ENTRY8114_REHEARSAL_WALLET_DOOR_SKIPPED_8339"

    /** Pure: does the live wallet door (LiveBootRecovery8074.blind8074) bind an entry decided in a runtime that is [live]? */
    fun walletDoorBinds8339(live: Boolean): Boolean = live

    /** [walletDoorBinds8339] for the current runtime; a skipped door is counted. Unknown mode binds (safe side). */
    fun walletDoorBindsNow8339(): Boolean {
        val live = try { com.lifecyclebot.engine.RuntimeModeAuthority.isLive() } catch (_: Throwable) { true }
        val binds = walletDoorBinds8339(live)
        if (!binds) try { PipelineHealthCollector.labelInc(WALLET_DOOR_SKIPPED_8339) } catch (_: Throwable) {}
        return binds
    }

    /** Pure: the book the shadow (current full) verdict is computed for — the runtime's own ([paperRuntime]). */
    fun shadowPaper8339(paperRuntime: Boolean): Boolean = paperRuntime
}
