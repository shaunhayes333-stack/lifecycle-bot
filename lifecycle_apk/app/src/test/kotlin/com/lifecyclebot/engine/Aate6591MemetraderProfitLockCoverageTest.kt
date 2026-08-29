package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

/**
 * V5.0.6591 — regression coverage for the three source-of-truth patches
 * that landed together to restore MemeTrader profitability and clear the
 * bogus fanout warnings surfaced by V5.0.6590's pipeline snapshot.
 *
 * (1) ProfitabilityLayer.checkTrailingStop widened + fee-aware min-lock
 *     Prior thresholds (meme activate=12%/give=8%, bluechip 4%/3%) let
 *     the trail fire at effective +4% (meme) or +1% (bluechip), which is
 *     eaten by round-trip fees + slippage. Snapshot showed every winner
 *     scratching to pnl=+0.000 via trail_stop_peak6..14. New defaults:
 *     meme 25/12, bluechip 8/4, and a hard MIN_LOCKED_NET_PCT floor of
 *     6% so the trail can never fire on a scratch.
 *
 * (2) §H PositionStateLedger key alignment
 *     Executor.paperBuy.atomic6485 previously called registerOpen(pid6485)
 *     ("PAPER:mint:runIdHash:seq") while ExecutorCanonicalMirror6442
 *     used registerOpen(canonicalMint(mint)) and confirmTerminalSell(
 *     canonicalMint(mint)) on the sell side. Different keys created two
 *     ledger slots per buy; only the mirror slot ever closed. Snapshot
 *     showed §H states={OPEN=25, CLOSED=13} while canonical open=5 — the
 *     20 phantom OPENs were the unaligned Executor-side slots. Align to
 *     canonicalMint on register + abort.
 *
 * (3) InvariantGuardian productive-fanout signal
 *     LANE_FANOUT_EXPLOSION and FDG_FANOUT_EXPLOSION were tripping while
 *     the pipeline was demonstrably productive (24 paper BUYs, 32 journal
 *     rows) because "productive" required exec >= intake/2 (50% intake→
 *     exec conversion) — unrealistic in a filter bot. Use the presence
 *     of TRADEJRNL_REC rows as the productivity signal, plus a 5% exec/
 *     intake proxy for pipelines that haven't journaled yet. Widen the
 *     FDG threshold from 3.0 to 4.0 with a journalRows>0 escape hatch.
 */
class Aate6591MemetraderProfitLockCoverageTest {

    @Test
    fun aate6591TrailingStopActivationWidenedAndFeeAware() {
        val prof = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/ProfitabilityLayer.kt"
        ).readText()

        // Widened activation/giveback per lane class.
        assertTrue(
            "V5.0.6591: meme trail activation must be 25% (was 12%)",
            prof.contains("val activate = if (isBluechip) 8.0 else 25.0")
        )
        assertTrue(
            "V5.0.6591: meme trail giveback must be 12% (was 8%)",
            prof.contains("val giveback = if (isBluechip) 4.0 else 12.0")
        )
        // Fee-aware minimum-lock floor — trail may not fire on a scratch.
        assertTrue(
            "V5.0.6591: trailing stop must enforce MIN_LOCKED_NET_PCT so " +
                "fees + slippage can't turn a locked win into a scratch",
            prof.contains("MIN_LOCKED_NET_PCT") && prof.contains("effectiveLockedPct")
        )
        assertTrue(
            "V5.0.6591: MIN_LOCKED_NET_PCT default must be at least 6%",
            prof.contains("val MIN_LOCKED_NET_PCT = 6.0")
        )
        // The old narrow defaults must be gone (must never re-regress).
        assertFalse(
            "V5.0.6591: old 12/8 meme trail must be removed",
            prof.contains("val activate = if (isBluechip) 4.0 else 12.0")
        )
        assertFalse(
            "V5.0.6591: old 4/3 bluechip trail must be removed",
            prof.contains("val giveback = if (isBluechip) 3.0 else 8.0")
        )
    }

    @Test
    fun aate6591PositionLedgerKeysAlignedToCanonicalMint() {
        val exec = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/Executor.kt"
        ).readText()
        // Register + abort must both use canonicalMint(mint), matching the
        // sell path's confirmTerminalSell(canonicalMint(mint)) key.
        assertTrue(
            "V5.0.6591: paperBuy.atomic6485 must register the §H ledger slot " +
                "under canonicalMint(mint), not pid6485",
            exec.contains("val ledgerKey6591 = com.lifecyclebot.engine.truth.ExecutorCanonicalMirror6442.canonicalMint(tradeId.mint)") &&
                exec.contains("PositionStateLedger6427.registerOpen(ledgerKey6591)")
        )
        assertTrue(
            "V5.0.6591: rollback path must abortOpen6485 the same " +
                "canonicalMint-keyed slot",
            exec.contains("val abortKey6591 = com.lifecyclebot.engine.truth.ExecutorCanonicalMirror6442.canonicalMint(tradeId.mint)") &&
                exec.contains("PositionStateLedger6427.abortOpen6485(abortKey6591)")
        )
        // The old pid6485-keyed calls must be gone so we don't reintroduce
        // the phantom-OPEN drift.
        assertFalse(
            "V5.0.6591: pid6485-keyed registerOpen must be removed",
            exec.contains("PositionStateLedger6427.registerOpen(pid6485)")
        )
        assertFalse(
            "V5.0.6591: pid6485-keyed abortOpen6485 must be removed",
            exec.contains("PositionStateLedger6427.abortOpen6485(pid)")
        )
    }

    @Test
    fun aate6591InvariantGuardianProductiveFanoutUsesJournalRows() {
        val guardian = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/InvariantGuardian.kt"
        ).readText()
        // Productivity now recognised via journal rows OR a 5% exec/intake
        // proxy, not the unrealistic 50% (intake/2) bar.
        assertTrue(
            "V5.0.6591: productive-fanout must consider TRADEJRNL_REC as " +
                "the primary productivity signal",
            guardian.contains("journalRows6591") &&
                guardian.contains("TRADEJRNL_REC") &&
                guardian.contains("s.exec >= (s.intake / 20L).coerceAtLeast(1L)")
        )
        // Old 50% threshold must be gone.
        assertFalse(
            "V5.0.6591: old exec >= intake/2 productivity gate must be removed",
            guardian.contains("s.exec >= (s.intake / 2L).coerceAtLeast(1L)")
        )
        // FDG threshold widened to 4.0 with journal-rows escape hatch.
        assertTrue(
            "V5.0.6591: FDG_FANOUT_EXPLOSION must only trip when fdgRatio > 4.0 " +
                "OR fdgRatio > 3.0 with zero journal rows",
            guardian.contains("fdgRatio > 4.0") &&
                guardian.contains("fdgRatio > 3.0 && journalRows6591 == 0L")
        )
        // Old blanket >3.0 rule must be gone.
        assertFalse(
            "V5.0.6591: old bare fdgRatio > 3.0 must be replaced",
            Regex("""fdgRatio\s*>\s*3\.0\)\s*out\s*\+=\s*Fault""").containsMatchIn(guardian)
        )
    }
}
