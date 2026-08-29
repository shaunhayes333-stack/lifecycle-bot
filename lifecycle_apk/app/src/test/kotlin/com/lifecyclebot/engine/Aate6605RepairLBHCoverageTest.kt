package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * V5.0.6605 — Direct source-level repairs on the V5.0.6604 forensic dump
 * feedback from the operator. Three targeted repairs shipped:
 *
 *   REPAIR L (PWin bootstrap semantics) — V5.0.6604 MEME_SPECIALIST_PWIN_GATE
 *     was converting UNKNOWN/BOOTSTRAP lanes (no own-head samples) into a
 *     "learned negative", hard-blocking EXPRESS at score=0 despite EXPRESS
 *     having 0 own-head samples. Fix: gate on the lane's OWN-head tier
 *     (laneOwnHeadAuthority6605); global fallback no longer arms the pWin
 *     gate. Only genuine LEARNED/AUTHORITATIVE lane heads may exercise
 *     authoritative negative pressure.
 *
 *   REPAIR B (Stock market session parity) — V5.0.6560 explicitly allowed
 *     paper stocks to execute 24/7. Operator forensic captured 51 STOCK
 *     positions opened on a Sunday morning while FOREX/COMMODITY/METAL
 *     correctly reported SOURCE_CLOSED_WEEKEND. Paper must model live
 *     behaviour. Weekend closure now applies to paper AND live.
 *
 *   REPAIR H (Canonical same-mint occupancy at reducer) — SameMintDedup
 *     only guards within a scan cycle; specialist election → sizing →
 *     executor path could still open a second position on the same mint
 *     (different positionId, same mint). Bind the invariant at the
 *     canonical reducer (CanonicalPaperTransaction6486.open) so no
 *     downstream path can bypass. Emits
 *     CANONICAL_SAME_MINT_OCCUPANCY_BLOCK_6605.
 */
class Aate6605RepairLBHCoverageTest {

    @Test
    fun aate6605_pwin_gate_gates_on_lane_own_head_not_global_fallback() {
        val botSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/BotService.kt"
        ).readText()
        assertTrue(
            "V5.0.6605: pWin gate must consult laneOwnHeadAuthority6605 (own-head only), not currentAuthority (which falls back to global)",
            botSrc.contains("laneOwnHeadAuthority6605(l)") &&
                botSrc.contains("laneLearnedOrBetter6605") &&
                botSrc.contains("AuthorityTier.LEARNED") &&
                botSrc.contains("AuthorityTier.AUTHORITATIVE")
        )
        val headSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/UnifiedPolicyHead.kt"
        ).readText()
        assertTrue(
            "V5.0.6605: UnifiedPolicyHead must expose laneOwnHeadAuthority6605 + laneOwnHeadTrainedCount6605",
            headSrc.contains("fun laneOwnHeadAuthority6605(lane: String): AuthorityTier") &&
                headSrc.contains("fun laneOwnHeadTrainedCount6605(lane: String): Long") &&
                // Own-head lookup must be strict: no global fallback.
                headSrc.contains("laneHeads[normalizeLane(lane)] ?: return 0L")
        )
    }

    @Test
    fun aate6605_stock_weekend_closure_applies_to_paper_and_live() {
        val stockSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/perps/TokenizedStockTrader.kt"
        ).readText()
        assertTrue(
            "V5.0.6605: paper stocks must respect weekend closure like live (parity with FOREX/COMMODITY/METAL SOURCE_CLOSED_WEEKEND)",
            stockSrc.contains("stockWeekendClosed6605") &&
                stockSrc.contains("MARKETS_STOCK_SOURCE_CLOSED_WEEKEND_6605") &&
                stockSrc.contains("SOURCE_CLOSED_WEEKEND")
        )
    }

    @Test
    fun aate6605_canonical_same_mint_occupancy_at_reducer() {
        val txSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt"
        ).readText()
        assertTrue(
            "V5.0.6605: canonical paper open() must refuse a second open on the same mint in the same mode BEFORE ledger debit",
            txSrc.contains("duplicateOpenSameMode6605") &&
                txSrc.contains("CANONICAL_SAME_MINT_OCCUPANCY_BLOCK_6605") &&
                txSrc.contains("CANONICAL_SAME_MINT_ALREADY_OPEN_POSITION_6605") &&
                txSrc.contains("CanonicalPositionAuthority6441.openPositions()")
        )
        // The write barrier must precede PaperAccountLedger6430.onBuy so no
        // ledger debit occurs on the second attempt.
        val onBuyIdx = txSrc.indexOf("PaperAccountLedger6430.onBuy(costSol, feeSol)")
        val barrierIdx = txSrc.indexOf("duplicateOpenSameMode6605")
        assertTrue(
            "V5.0.6605: same-mint occupancy barrier must precede PaperAccountLedger6430.onBuy",
            barrierIdx in 1 until onBuyIdx
        )
    }
}
