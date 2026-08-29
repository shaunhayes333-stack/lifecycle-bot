package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

/**
 * V5.0.6597 — REPAIR 6 (mark authority accepts MINT_ROUTE for known open
 * positions) + REPAIR 7 (Treasury reads canonical PaperCapitalAuthority6577
 * for paper-mode tier).
 *
 * Operator directive Feb 2026 (V5.0.6595 execution-liveness):
 *
 *   REPAIR 6: 'DEXSCREENER_PAIR_POLL is returning valid price/liquidity
 *   but is being rejected because pool=MINT_ROUTE:* becomes
 *   NON_AUTHORITATIVE_SENTINEL ... If mint, quote asset and pair identity
 *   match canonical provenance, promote the mark to authoritative rather
 *   than rejecting it solely because the route key has MINT_ROUTE prefix.'
 *
 *   REPAIR 7: 'PAPER: Treasury uses canonical PaperCapitalAuthority /
 *   CapitalAuthority. All meme lanes, Treasury, sizing and UI must consume
 *   the same mode-specific capital snapshot.'
 *
 * Snapshot 6595 evidence:
 *   - 55 canonical open positions, 51 missing marks (MARK_AUTHORITY_GATE
 *     _BLOCKED_6496 rejecting MINT_ROUTE:* on the exit-mark path)
 *   - Treasury card: Tier=None, Next=\$100 while paper equity was
 *     11.5057 SOL / \$1,191.06 (Treasury reading the allocated sub-account
 *     TreasuryManager.treasurySol instead of canonical total equity)
 */
class Aate6597MarkAuthorityAndTreasuryCoverageTest {

    @Test
    fun aate6597_mark_authority_accepts_mint_route_for_known_open() {
        val gate = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/MarkAuthorityIntegrityGate6496.kt"
        ).readText()
        assertTrue(
            "V5.0.6597: evaluate() must accept an isKnownOpenMint6596 flag",
            gate.contains("isKnownOpenMint6596: Boolean") &&
                gate.contains("realPoolIdentity = poolAddress.isNotBlank() &&") &&
                gate.contains("(isKnownOpenMint6596 || !poolAddress.startsWith(\"MINT_ROUTE:\", ignoreCase = true))")
        )
        assertTrue(
            "V5.0.6597: isAuthoritative overload with isKnownOpenMint6596 must exist",
            gate.contains("isAuthoritative(") &&
                gate.contains("isKnownOpenMint6596 = isKnownOpenMint6596")
        )
    }

    @Test
    fun aate6597_bot_exit_mark_path_passes_known_open_true() {
        val botSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/BotService.kt"
        ).readText()
        assertTrue(
            "V5.0.6597: the openMV recompute path must pass isKnownOpenMint6596=true " +
                "(the mint identity is proven by the canonical open position)",
            botSrc.contains("§MARK_AUTHORITY_MINT_ROUTE_FOR_KNOWN_OPEN") &&
                botSrc.contains("isKnownOpenMint6596 = true")
        )
    }

    @Test
    fun aate6597_paper_treasury_reads_canonical_equity() {
        val ui = java.io.File(
            "src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt"
        ).readText()
        assertTrue(
            "V5.0.6597: paper Treasury tier must read PaperCapitalAuthority6577.totalEquitySol()",
            ui.contains("canonicalPaperEquitySol6596") &&
                ui.contains("com.lifecyclebot.engine.truth.PaperCapitalAuthority6577.totalEquitySol()") &&
                ui.contains("§TREASURY_CAPITAL_AUTHORITY")
        )
        assertFalse(
            "V5.0.6597: pre-6597 legacy TreasuryManager.treasurySol as primary source " +
                "must be replaced (fallback only)",
            ui.contains("trs = com.lifecyclebot.engine.TreasuryManager.treasurySol\n" +
                "                trsUsd = if (ws.treasuryUsd > 0) ws.treasuryUsd else trs * solPrice\n" +
                "            } else {")
        )
    }
}
