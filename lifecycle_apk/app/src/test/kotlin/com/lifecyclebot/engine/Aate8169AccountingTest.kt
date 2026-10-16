package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AccountingIntegrity8169
import com.lifecyclebot.engine.truth.AccountingIntegrity8169.SignedBuy8169
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import com.lifecyclebot.engine.truth.ReconcileClassifier8144
import com.lifecyclebot.engine.truth.ReconcileClassifier8144.Cause8144
import com.lifecyclebot.engine.truth.ReconcileClassifier8144.Inputs8144
import com.lifecyclebot.engine.truth.ReconcileClassifier8144.Row8144
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8169 — accounting integrity repairs from the 5.0.8168 live report:
 * LIVE_BUY_NO_SIGNATURE=12/17 (journal never persisted `sig`), paper cash -5.29 vs 9.69 (unfunded open-cost
 * override drove the identity rebuild negative), multi-chain paper mean +3.75e36 % (no mark plausibility bound),
 * mark repair fail=146 (unnamed failures, prefixed mint handed to the cascade, hot-cache null counted as failure).
 */
class Aate8169AccountingTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    // ── 1. LIVE BUY signatures ──

    @Test fun journalPersistsTheSignatureAcrossRestart() {
        val store = src("engine/TradeHistoryStore.kt")
        assertTrue(store.contains("const val DB_VERSION = 10"))
        assertTrue(store.contains("ADD COLUMN sig TEXT NOT NULL DEFAULT ''"))
        assertTrue(store.contains("put(\"sig\",           t.sig)"))
        // every SQLite reader of a Trade row reads it back
        assertEquals(3, Regex("sig = c\\.stringOrBlank\\(\"sig\"\\)").findAll(store).count())
    }

    @Test fun anUnsignedBuyTakesTheOneConfirmedLocalSignatureNearIt() {
        val t0 = 1_000_000L
        val journal = listOf(
            Row8144(side = "BUY", mint = "M1", ts = t0),
            Row8144(side = "BUY", mint = "M2", sig = "S_M2", ts = t0),
            Row8144(side = "SELL", mint = "M1", ts = t0 + 5_000),
        )
        val ledger = listOf(
            SignedBuy8169("S_M1", "M1", t0 - 20_000, confirmed = true),
            SignedBuy8169("S_M2", "M2", t0, confirmed = true),
        )
        val r = AccountingIntegrity8169.resolveJournalBuySignatures8169(journal, ledger)
        assertEquals(1, r.recovered)
        assertEquals("S_M1", r.journal[0].sig)
        assertEquals("S_M2", r.journal[1].sig)
        assertEquals("", r.journal[2].sig)
    }

    @Test fun ambiguousFailedOrDistantSignaturesAreNeverGuessed() {
        val t0 = 5_000_000L
        val row = listOf(Row8144(side = "BUY", mint = "M", ts = t0))
        val two = listOf(SignedBuy8169("A", "M", t0, true), SignedBuy8169("B", "M", t0 + 1, true))
        val r2 = AccountingIntegrity8169.resolveJournalBuySignatures8169(row, two)
        assertEquals(0, r2.recovered); assertEquals(1, r2.ambiguous); assertEquals("", r2.journal[0].sig)
        val failed = listOf(SignedBuy8169("F", "M", t0, confirmed = false))
        assertEquals("", AccountingIntegrity8169.resolveJournalBuySignatures8169(row, failed).journal[0].sig)
        val far = listOf(SignedBuy8169("X", "M", t0 + AccountingIntegrity8169.SIG_MATCH_WINDOW_MS_8169 + 1, true))
        assertEquals("", AccountingIntegrity8169.resolveJournalBuySignatures8169(row, far).journal[0].sig)
        // a signature another row already carries is not reused
        val used = listOf(Row8144(side = "BUY", mint = "M", ts = t0), Row8144(side = "BUY", mint = "M", sig = "A", ts = t0))
        assertEquals("", AccountingIntegrity8169.resolveJournalBuySignatures8169(used, listOf(SignedBuy8169("A", "M", t0, true))).journal[0].sig)
    }

    @Test fun aChainProvenRowWithALostSignatureIsNamedApartFromAnUnsignedBuy() {
        val r = ReconcileClassifier8144.classify8144(Inputs8144(
            lots = emptyList(), chain = null, chainComplete = false, txs = emptyList(),
            journal = listOf(
                Row8144(side = "BUY", mint = "A", fillQty = 1.0, proofState = "LIVE_FINALIZED"),
                Row8144(side = "BUY", mint = "B", fillQty = 1.0, proofState = "LIVE_BALANCE_CONFIRMED"),
            ),
        ))
        assertEquals(1, r.count(Cause8144.LIVE_BUY_SIG_NOT_PERSISTED))
        assertEquals(1, r.count(Cause8144.LIVE_BUY_NO_SIGNATURE))
        assertTrue(src("engine/ReconcileRunner8144.kt").contains("sigRecovered8169="))
        assertTrue(src("engine/ReconcileRunner8144.kt").contains("proofState = t.proofState"))
    }

    // ── 2. Paper cash ──

    @Test fun openCostOverrideNeverRaisesPastFundedCapital() {
        assertEquals("OPEN_COST_EXCEEDS_FUNDED_CAPITAL_8169",
            AccountingIntegrity8169.openCostOverrideRefusal8169(projectionSol = 18.25, priorSol = 3.0, fundableSol = 12.96))
        // lowering a drifted scalar (the 6508 repair) is unchanged
        assertNull(AccountingIntegrity8169.openCostOverrideRefusal8169(projectionSol = 1.0, priorSol = 3.9, fundableSol = 10.0))
        assertNull(AccountingIntegrity8169.openCostOverrideRefusal8169(projectionSol = 9.0, priorSol = 3.0, fundableSol = 10.0))
        assertEquals("OPEN_COST_PROJECTION_NOT_FINITE_8169",
            AccountingIntegrity8169.openCostOverrideRefusal8169(Double.NaN, 1.0, 10.0))
        assertEquals("IDENTITY_CASH_NEGATIVE_8169", AccountingIntegrity8169.identityCashRefusal8169(-5.29))
        assertNull(AccountingIntegrity8169.identityCashRefusal8169(0.0))
        assertEquals("AGREE", AccountingIntegrity8169.cashAgreement8169(9.6896, 9.6896))
        assertTrue(AccountingIntegrity8169.cashAgreement8169(9.69, -5.29).startsWith("DIVERGE"))
    }

    @Test fun ledgerKeepsNonNegativeCashAndTheCanonicalProjectionFollowsIt() {
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(10.0)
        PaperAccountLedger6430.overrideOpenCostFromProjections6508(18.25)
        assertEquals(0.0, PaperAccountLedger6430.openCostBasisSol(), 1e-9)
        assertEquals(10.0, PaperAccountLedger6430.rebuildPaperCashFromIdentity6505(), 1e-9)
        assertEquals(10.0, PaperAccountLedger6430.cashSol(), 1e-9)
        assertEquals(10.0, CanonicalPositionAuthority6441.paperCashSol(), 1e-9)
        assertTrue(PaperAccountLedger6430.assertInvariant() == null)
        // a balanced journal replay that needs negative cash is refused
        assertFalse(PaperAccountLedger6430.reconcileFromJournal6663(cashSol = -1.0, openCostSol = 11.0, realizedSol = 0.0, feesSol = 0.0))
        assertTrue(PaperAccountLedger6430.statusLine().contains("cashGuard8169[refused="))
        assertTrue(src("engine/truth/CanonicalPositionAuthority6441.kt").contains("cashAgree8169="))
        PaperAccountLedger6430.resetForTest()
    }

    // ── 3. Multi-chain marks ──

    @Test fun implausibleUpsideMarksAreRefusedButACollapseIsNot() {
        assertEquals("MARK_MULTIPLE_IMPLAUSIBLE_8169", AccountingIntegrity8169.markMultipleRefusal8169(1e-30, 0.002))
        assertNull(AccountingIntegrity8169.markMultipleRefusal8169(0.001, 0.05))     // a real 50x runner
        assertNull(AccountingIntegrity8169.markMultipleRefusal8169(0.001, 1e-12))    // a rug keeps driving the stop
        assertEquals("MARK_NOT_POSITIVE_FINITE_8169", AccountingIntegrity8169.markMultipleRefusal8169(0.001, Double.NaN))
        assertFalse(AccountingIntegrity8169.bookablePct8169(3.75e36))
        assertFalse(AccountingIntegrity8169.bookablePct8169(Double.POSITIVE_INFINITY))
        assertTrue(AccountingIntegrity8169.bookablePct8169(-35.0))
        assertTrue(AccountingIntegrity8169.bookablePct8169(4_200.0))
        val lane = src("perps/crypto/MultiChainMemeLane7962.kt")
        assertTrue(lane.contains("AccountingIntegrity8169.bookablePct8169(realizedPct)"))
        assertTrue(lane.contains("markMultipleRefusal8169(p.entryPriceUsd, mark)"))
        assertTrue(lane.contains("markGuard8169[implausible="))
    }

    // ── 4. Mark repair ──

    @Test fun repairsPriceOnlySolanaMintsAndNameEveryFailure() {
        val m = "So11111111111111111111111111111111111111112"
        assertEquals(m, AccountingIntegrity8169.repairIdentity8169(m))
        assertEquals(m, AccountingIntegrity8169.repairIdentity8169("solana|$m"))
        assertNull(AccountingIntegrity8169.repairIdentity8169("bsc|0x55d398326f99059ff775485246999027b3197955"))
        assertNull(AccountingIntegrity8169.repairIdentity8169("BTC"))
        assertEquals("NO_NEW_OBSERVATION_HOT_CACHE", AccountingIntegrity8169.repairFailureReason8169(true, false, false, true))
        assertEquals("FEEDS_CONTESTED_CASCADE_EMPTY", AccountingIntegrity8169.repairFailureReason8169(true, false, true, false))
        assertEquals("NO_FEED_ANSWERED", AccountingIntegrity8169.repairFailureReason8169(true, false, false, false))
        assertEquals("EXCEPTION", AccountingIntegrity8169.repairFailureReason8169(true, true, false, false))
        val repair = src("engine/truth/MarkIdentityRepairAuthority7236.kt")
        assertTrue(repair.contains("PriceResolverFallback.resolve(cascadeMint8169, solUsd)"))
        assertTrue(repair.contains("REPAIR_DEBOUNCE_MS: Long = 6_000L"))
        assertTrue(src("engine/truth/RuntimeHealthPanel7234.kt").contains("failBy="))
    }
}
