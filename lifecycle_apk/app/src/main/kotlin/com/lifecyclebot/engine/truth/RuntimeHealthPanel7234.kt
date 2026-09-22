package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.7234 §RUNTIME_HEALTH_PANEL — operator directive Feb 2026.
 *
 *   "Surface CANONICAL_FILL_BASIS_EXIT_VETO_7229 + SELL_FINALITY_UNIQUE_7231
 *    + WALLET_INVENTORY_CLASSIFIED_7230_* + MARK_IDENTITY_SUPPRESSED_*_7230
 *    + FDG_SUPPRESSED_FANOUT_CAP_7232 as a compact Bot-page panel so the
 *    recovery is visible without a full log pull."
 *
 * PURPOSE — one immutable snapshot builder that any UI can call and
 * render as a fixed-width panel.  Zero UI dependencies here so this
 * authority stays in engine/truth/.  The Bot page just calls
 * RuntimeHealthPanel7234.render() and pastes the returned string.
 *
 * FIELDS surfaced:
 *   basisSeal.sealed / overwriteRefused / exitBasisMismatch / exitBasisAgree
 *   sellFinality.unique / redispatch / alreadyClosing / reconciler
 *   walletInventory.pnlSuppressed / pnlAllowed / bucketCounts
 *   markIdentity.identityBroken / uncorroborated / venueMissing /
 *                executionSuppressed / executionAllowed
 *   fanout.fdgCapped / laneCapped / advisoryUngoverned
 *
 * Zero economic effect - pure read.
 */
object RuntimeHealthPanel7234 {

    /** Compact multi-line render suitable for a monospace panel. */
    fun render(): String {
        val bs = CanonicalFillBasisSeal7229.summary()
        val sf = SellFinalityUniqueCounter7231.summary()
        val wi = WalletCanonicalInventoryClassifier7230.summary()
        val mi = MarkIdentityExecutionGate7230.summary()
        val fo = IntakeFanoutGovernor6835.summary()
        val mr = MarkIdentityRepairAuthority7236.summary()
        val rg = RoutableMinRiskGuard7236.summary()
        val lt = LiveTerminalSemanticsAuthority7236.summary()
        val sb = StringBuilder()
        sb.append("┌─ RUNTIME HEALTH ─────────────────────────────────┐\n")
        sb.append("│ BASIS  sealed=${pad(bs.sealsCreated)} refused=${pad(bs.overwriteRefused)} veto=${pad(bs.exitBasisMismatch)}\n")
        sb.append("│        agree=${pad(bs.exitBasisAgree)}  idempotent=${pad(bs.idempotentAccept)}\n")
        sb.append("│ SELL   unique=${pad(sf.uniqueSells)} redispatch=${pad(sf.redispatches)}\n")
        sb.append("│        alreadyClosing=${pad(sf.alreadyClosing)} reconciler=${pad(sf.reconcilerObservations)}\n")
        sb.append("│ WALLET pnlAllowed=${pad(wi.pnlAllowed)} pnlSuppressed=${pad(wi.pnlSuppressed)}\n")
        sb.append("│        buckets=${wi.bucketCounts.entries.joinToString(",") { "${it.key.name.take(3)}=${it.value}" }}\n")
        sb.append("│ MARK   broken=${pad(mi.identityBroken)} uncorroborated=${pad(mi.uncorroborated)}\n")
        sb.append("│        venueMissing=${pad(mi.venueMissing)} suppressed=${pad(mi.executionSuppressed)}\n")
        sb.append("│ REPAIR requested=${pad(mr.requested)} ok=${pad(mr.succeeded)} fail=${pad(mr.failed)}\n")
        sb.append("│        cacheHits=${pad(mr.cacheHits)} stale=${pad(mr.cacheMissesStale)} size=${mr.cacheSize}\n")
        sb.append("│ LIFT   allowed=${pad(rg.liftAllowed)} refusedWeak=${pad(rg.totalRefused)}\n")
        sb.append("│        (score=${rg.refusedWeakScore},regime=${rg.refusedWeakRegime},proof=${rg.refusedPendingProof},cmpst=${rg.refusedComposite})\n")
        sb.append("│ LIVE   terminal=${pad(lt.totalTerminal)} exclBroadcast=${pad(lt.excludedBroadcast)}\n")
        sb.append("│        exclUnknown=${pad(lt.excludedUnknown)}\n")
        sb.append("│ FANOUT fdgCapped=${pad(fo.fdgCappedEvents)} laneCapped=${pad(fo.laneCappedEvents)}\n")
        sb.append("│        advisoryUngoverned=${pad(fo.advisoryUngoverned)} chains=${fo.activeCausalChains}\n")
        sb.append("└──────────────────────────────────────────────────┘")
        return sb.toString()
    }

    /** Single-line variant for status headers / log emitters. */
    fun renderOneLine(): String {
        val bs = CanonicalFillBasisSeal7229.summary()
        val sf = SellFinalityUniqueCounter7231.summary()
        val wi = WalletCanonicalInventoryClassifier7230.summary()
        val mi = MarkIdentityExecutionGate7230.summary()
        val fo = IntakeFanoutGovernor6835.summary()
        val mr = MarkIdentityRepairAuthority7236.summary()
        val rg = RoutableMinRiskGuard7236.summary()
        val lt = LiveTerminalSemanticsAuthority7236.summary()
        return "HEALTH7234 basisVeto=${bs.exitBasisMismatch} " +
            "sellUnique=${sf.uniqueSells}/redispatch=${sf.redispatches} " +
            "walletPnlSup=${wi.pnlSuppressed} " +
            "markSup=${mi.executionSuppressed} " +
            "repairOk=${mr.succeeded}/${mr.requested} " +
            "liftRefWeak=${rg.totalRefused} " +
            "liveExclBroadcast=${lt.excludedBroadcast} " +
            "fanoutFdgCap=${fo.fdgCappedEvents}"
    }

    private fun pad(v: Long): String = v.toString().padEnd(6)
}
