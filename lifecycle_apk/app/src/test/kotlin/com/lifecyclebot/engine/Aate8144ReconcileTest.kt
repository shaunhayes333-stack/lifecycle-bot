package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.LearningResetSweep7781
import com.lifecyclebot.engine.truth.ReconcileClassifier8144
import com.lifecyclebot.engine.truth.ReconcileClassifier8144.Cause8144
import com.lifecyclebot.engine.truth.ReconcileClassifier8144.Inputs8144
import com.lifecyclebot.engine.truth.ReconcileClassifier8144.Lot8144
import com.lifecyclebot.engine.truth.ReconcileClassifier8144.Row8144
import com.lifecyclebot.engine.truth.ReconcileClassifier8144.State8144
import com.lifecyclebot.engine.truth.ReconcileClassifier8144.Tx8144
import com.lifecyclebot.engine.truth.ReconcileEvidenceStores8144
import com.lifecyclebot.engine.truth.ReconcileLineageLedger8144
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8144 — P1-E: every trade reconciled against the blockchain. Orphan-sell classes (restart, adoption, manual
 * holding) reproduced and resolved by durable lineage; one signature = one accounting movement across a restart;
 * manual holdings are never adopted; the pure classifier names every exception; a learning reset keeps evidence.
 */
class Aate8144ReconcileTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val suffix = System.nanoTime().toString()
    private fun big(v: Long) = BigInteger.valueOf(v)

    @Before fun setUp() {
        ReconcileLineageLedger8144.resetForTest8144()
        ReconcileSignatureGuard8144.journalLookup8144 = null
        AccountingIdempotencyRegistry.reset()
    }

    @After fun tearDown() {
        ReconcileLineageLedger8144.resetForTest8144()
        ReconcileSignatureGuard8144.journalLookup8144 = null
        AccountingIdempotencyRegistry.reset()
    }

    private fun sell(mint: String, pid: String = "", reason: String = "TP", sig: String = "") =
        Row8144(side = "SELL", mint = mint, positionId = pid, sig = sig, soldQty = 10.0, costSol = 0.1, reason = reason)

    private fun inputs(journal: List<Row8144>, parentMints: Set<String> = emptySet(), parentPids: Set<String> = emptySet(),
                       manual: Set<String> = emptySet()) = Inputs8144(
        lots = emptyList(), chain = null, chainComplete = false, txs = emptyList(), journal = journal,
        parentMints = parentMints, parentPositionIds = parentPids, ownerManualMints = manual,
    )

    // ── orphan-sell classes: reproduced, then resolved by the lineage fix ──

    @Test fun restartOrphanSellIsResolvedByDurableLotBirth() {
        val pid = "RESTART8144#r1"
        val row = sell("RESTARTMINT8144", pid)
        // before: canonical is memory-only, the restart emptied it, the BUY row left the bounded window
        val before = ReconcileClassifier8144.classify8144(inputs(listOf(row)))
        assertEquals(1, before.count(Cause8144.ORPHAN_SELL_NO_LINEAGE))
        // the lot's birth was recorded durably when it opened
        assertTrue(ReconcileLineageLedger8144.note8144(pid, "RESTARTMINT8144", "live", big(1_000), 0.1, "JUPITER", "run1", "k1", promoted = false))
        val after = ReconcileClassifier8144.classify8144(inputs(listOf(row), ReconcileLineageLedger8144.mints8144(), setOf(pid)))
        assertEquals(0, after.exceptions.size)
        assertEquals(1, after.lineageResolvedSells)
    }

    @Test fun adoptionOrphanSellIsNamedThenResolvedByAdoptionLineage() {
        val row = sell("ADOPTMINT8144", reason = "WALLET_RECOVERED_STOP")
        val before = ReconcileClassifier8144.classify8144(inputs(listOf(row)))
        assertEquals(1, before.count(Cause8144.ORPHAN_SELL_ADOPTED_NO_BUY))
        assertTrue(ReconcileLineageLedger8144.note8144("LIVE:ADOPTMINT8144:rec", "ADOPTMINT8144", "live", big(5_000), 0.02,
            "OBSERVED_MARK_ADOPTION_7706", "RECOVERY_6686", "LIVE_WALLET_CANONICAL_RECOVERY_6686:ADOPTMINT8144:x", promoted = false))
        assertEquals("OBSERVED_MARK_ADOPTION", ReconcileLineageLedger8144.recordFor8144("LIVE:ADOPTMINT8144:rec")?.origin)
        val after = ReconcileClassifier8144.classify8144(inputs(listOf(row), ReconcileLineageLedger8144.mints8144()))
        assertEquals(0, after.exceptions.size)
        assertEquals(1, after.lineageResolvedSells)
    }

    @Test fun manualHoldingSellIsNamedAndNotHiddenByLineage() {
        val r = ReconcileClassifier8144.classify8144(inputs(listOf(sell("HANDMINT8144", reason = "WALLET_RECOVERED_STOP")), manual = setOf("HANDMINT8144")))
        assertEquals(1, r.count(Cause8144.ORPHAN_SELL_MANUAL_HOLDING))
        assertEquals(0, r.count(Cause8144.ORPHAN_SELL_ADOPTED_NO_BUY))
    }

    @Test fun lotBirthIsImmutableAndOriginIsNamed() {
        assertTrue(ReconcileLineageLedger8144.note8144("P1", "M1", "live", big(1_000), 0.1, "JUP", "run", "k", promoted = false))
        assertFalse(ReconcileLineageLedger8144.note8144("P1", "M1", "live", big(9_999), 9.0, "JUP", "run", "k", promoted = false))
        assertEquals(big(1_000), ReconcileLineageLedger8144.recordFor8144("P1")?.originalRaw)
        assertFalse(ReconcileLineageLedger8144.note8144("P2", "M2", "paper", big(1_000), 0.1, "JUP", "run", "k", promoted = false))
        assertFalse(ReconcileLineageLedger8144.note8144("P3", "M3", "live", BigInteger.ZERO, 0.1, "JUP", "run", "k", promoted = false))
        assertEquals(ReconcileLineageLedger8144.Origin8144.WALLET_RECOVERY, ReconcileLineageLedger8144.originOf8144("x", "RECOVERY_6686", "JOURNAL", false))
        assertEquals(ReconcileLineageLedger8144.Origin8144.PENDING_PROMOTION, ReconcileLineageLedger8144.originOf8144("", "run", "JUP", true))
        assertEquals(ReconcileLineageLedger8144.Origin8144.EXECUTOR_FILL, ReconcileLineageLedger8144.originOf8144("", "run", "JUP", false))
        val rec = ReconcileLineageLedger8144.recordFor8144("P1")
        assertNotNull(rec)
        assertEquals(rec, ReconcileLineageLedger8144.decode8144(ReconcileLineageLedger8144.encode8144(rec!!)))
        assertTrue(ReconcileLineageLedger8144.decode8144("garbage") == null)
    }

    @Test fun canonicalLiveOpenWritesTheBirthRecordAtTheSource() {
        val mint = "LINEAGE8144Mint$suffix"
        val pid = "LIVE:$mint:l8144"
        val r = CanonicalPositionAuthority6441.openPosition(
            idempotencyKey = "OPEN8144:$pid", positionId = pid, mint = mint, symbol = "L8144", lane = "SHITCOIN", runId = suffix,
            entryCostSol = 0.5, openedQtyRaw = big(5_000_000), tokenDecimals = 6, feesSol = 0.0, paperMode = false,
            modeOverride = "live", entryPriceUsd = 0.001, entryPriceSource = "TEST_8144",
        )
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED, r)
        val rec = ReconcileLineageLedger8144.recordFor8144(pid)
        assertNotNull(rec)
        assertEquals("EXECUTOR_FILL", rec!!.origin)
        assertEquals(big(5_000_000), rec.originalRaw)
        assertEquals(0.5, rec.entryCostSol, 1e-12)
        assertTrue(ReconcileLineageLedger8144.hasLineage8144(mint))
    }

    @Test fun forensicReconcilerSplitsOrphansByCauseAndCountsLineageParents() {
        val sells = listOf(
            Trade(side = "SELL", mode = "live", sol = 0.1, price = 1.0, ts = 1L, mint = "DUR8144", reason = "TP"),
            Trade(side = "SELL", mode = "live", sol = 0.1, price = 1.0, ts = 2L, mint = "NOL8144", reason = "TP"),
            Trade(side = "SELL", mode = "live", sol = 0.1, price = 1.0, ts = 3L, mint = "ADO8144", reason = "WALLET_RECOVERED_STOP"),
            Trade(side = "SELL", mode = "live", sol = 0.1, price = 1.0, ts = 4L, mint = "BUY8144", reason = "TP"),
        )
        val (causes, resolved) = ForensicReconciler6377.orphanCauses8144(sells, setOf("BUY8144"), emptySet(), setOf("DUR8144"))
        assertEquals(1, resolved)
        assertEquals(mapOf("ORPHAN_SELL_NO_LINEAGE" to 1, "ORPHAN_SELL_ADOPTED_NO_BUY" to 1), causes)
    }

    @Test fun forensicOrphanCheckIsParentedByDurableLineageAfterRestart() {
        val lineaged = "FRL8144$suffix"
        val bare = "FRB8144$suffix"
        ReconcileLineageLedger8144.note8144("LIVE:$lineaged:r", lineaged, "live", big(1_000), 0.1, "JUP", "run", "k", promoted = false)
        ForensicReconciler6377.resetForTest()
        val r = ForensicReconciler6377.runAll(
            allTrades = listOf(
                Trade(side = "SELL", mode = "live", sol = 0.1, price = 1.0, ts = 10L, mint = lineaged, reason = "TP", soldQtyToken = 5.0),
                Trade(side = "SELL", mode = "live", sol = 0.1, price = 1.0, ts = 11L, mint = bare, reason = "TP", soldQtyToken = 5.0),
            ),
            paperMode = false, paperWalletSol = 0.0, startCapitalSol = 0.0, canonicalLiveOpenCount = 0, registryLiveOpenCount = 0,
        )
        val chk = r.checks.first { it.name == "ORPHAN_SELL" }
        assertFalse(chk.ok)                                   // the bare sell is still a genuine orphan — never hidden
        assertTrue(chk.summary.contains("orphan sells=1"))
        assertTrue(chk.summary.contains("NO_LINEAGE=1"))
        assertTrue(chk.summary.contains("lineageResolved=1"))
        ForensicReconciler6377.resetForTest()
    }

    // ── duplicate signature → one accounting movement, across a restart ──

    @Test fun duplicateSignatureProducesOneAccountingMovementAcrossRestart() {
        val journal = mutableListOf<Pair<String, String>>()
        var rewards = 0
        ReconcileSignatureGuard8144.journalLookup8144 = { m -> if (m == "DUPMINT8144") journal.toList() else emptyList() }
        fun deliver(sig: String, side: String) {
            if (AccountingIdempotencyRegistry.claim(sig, "DUPMINT8144", side, "test8144")) { journal += sig to side; rewards++ }
        }
        deliver("SIG8144A", "SELL")          // websocket
        deliver("SIG8144A", "SELL")          // polling, same process
        AccountingIdempotencyRegistry.reset() // process restart: the in-memory registry is empty
        deliver("SIG8144A", "SELL")          // finality resume after restart
        assertEquals(1, journal.size)
        assertEquals(1, rewards)
        deliver("SIG8144B", "SELL")          // a different transaction is its own movement
        assertEquals(2, rewards)
        assertTrue(ReconcileSignatureGuard8144.duplicateOf8144(listOf("S" to "SELL"), "S", "sell"))
        assertFalse(ReconcileSignatureGuard8144.duplicateOf8144(listOf("S" to "BUY"), "S", "SELL"))
        assertFalse(ReconcileSignatureGuard8144.duplicateOf8144(listOf("S" to "SELL"), "", "SELL"))
        // without the runtime lookup the registry behaves exactly as before (first writer wins)
        ReconcileSignatureGuard8144.journalLookup8144 = null
        AccountingIdempotencyRegistry.reset()
        assertTrue(AccountingIdempotencyRegistry.claim("SIG8144A", "DUPMINT8144", "SELL"))
        assertFalse(AccountingIdempotencyRegistry.claim("SIG8144A", "DUPMINT8144", "SELL"))
    }

    // ── manual holdings are never adopted as bot inventory ──

    @Test fun manualHoldingIsRepresentedNotAdopted() {
        assertFalse(ReconcileClassifier8144.adoptWalletHolding8144(botEvidence = false, ownerManual = false))
        assertFalse(ReconcileClassifier8144.adoptWalletHolding8144(botEvidence = true, ownerManual = true))
        assertTrue(ReconcileClassifier8144.adoptWalletHolding8144(botEvidence = true, ownerManual = false))
        val base = Inputs8144(lots = emptyList(), chain = mapOf("HAND8144" to big(5_000_000)), chainComplete = true,
            txs = emptyList(), journal = emptyList())
        val quiet = ReconcileClassifier8144.classify8144(base)
        assertEquals(0, quiet.exceptions.size)
        assertEquals(1, quiet.states[State8144.OWNER_UNOWNED])
        assertEquals("MATCH", quiet.inventory)
        // the host projection counting it is the host/canonical mismatch, named — not an adoption
        val hosted = ReconcileClassifier8144.classify8144(base.copy(hostOpenMints = setOf("HAND8144")))
        assertEquals(1, hosted.count(Cause8144.HOST_TRACKS_UNOWNED_HOLDING))
        // the same balance with bot-buy evidence is a bot holding the canonical book must own
        val bot = ReconcileClassifier8144.classify8144(base.copy(botEvidenceMints = setOf("HAND8144")))
        assertEquals(1, bot.count(Cause8144.CHAIN_BOT_HOLDING_NOT_CANONICAL))
        assertTrue(src("engine/WalletReconciler.kt").contains("if (!LiveCanonicalRecovery6686.botOwnedEvidence8144(mint)) continue"))
        assertTrue(src("engine/StartupReconciler.kt").contains("if (!LiveCanonicalRecovery6686.botOwnedEvidence8144(mint)) {"))
        assertTrue(src("engine/HostWalletTokenTracker.kt").contains("if (!ownedByBot7137 && !LiveCanonicalRecovery6686.botOwnedEvidence8144(mint)) {"))
        assertTrue(src("engine/LiveCanonicalRecovery6686.kt").contains("ReconcileClassifier8144.adoptWalletHolding8144(evidence, manual)"))
    }

    // ── classifier: canonical == chain + explicit states, every exception named ──

    @Test fun classifierNamesEveryExceptionAndEveryExplicitState() {
        fun lot(pid: String, mint: String, orig: Long, rem: Long, life: String = "OPEN") =
            Lot8144(pid, mint, life, big(orig), big(rem), 0.1)
        val i = Inputs8144(
            lots = listOf(
                lot("pA", "A", 1_000, 1_000), lot("pB", "B", 1_000, 1_000), lot("pC", "C", 1_000, 1_000),
                lot("pD", "D", 1_000, 1_500), lot("pE", "E", 500, 500, "QUARANTINED"), lot("pG", "G", 100, 100),
            ),
            chain = mapOf("A" to big(1_000), "C" to big(800), "D" to big(1_500), "E" to big(500), "F" to big(700),
                "H" to big(50), "I" to big(9_000), "J" to big(4_000)),
            chainComplete = true,
            txs = listOf(Tx8144("SL_FAIL", "L", "BUY", "FAILED_ON_CHAIN"), Tx8144("SY_EXP", "Y", "SELL", "EXPIRED_PROVEN")),
            journal = listOf(
                Row8144(side = "BUY", mint = "A", sig = "SA", fillQty = 1.0),
                Row8144(side = "BUY", mint = "K"),
                Row8144(side = "BUY", mint = "L", sig = "SL_FAIL", fillQty = 5.0),
                Row8144(side = "SELL", mint = "A", sig = "SX"),
                Row8144(side = "SELL", mint = "A", sig = "SA2", soldQty = 10.0, costSol = 0.05),
                Row8144(side = "PARTIAL_SELL", mint = "A", sig = "SA2", soldQty = 5.0, costSol = 0.02),
                Row8144(side = "SELL", mint = "Z", soldQty = 10.0, costSol = 0.1, reason = "TP"),
                Row8144(side = "SELL", mint = "Y", sig = "SY_EXP", soldQty = 10.0, costSol = 0.1),
            ),
            parentMints = setOf("Y"),
            botEvidenceMints = setOf("I"),
            frozenMints = setOf("F"),
            dustMints = setOf("H"),
            pendingMints = setOf("G"),
        )
        val r = ReconcileClassifier8144.classify8144(i)
        val expected = mapOf(
            Cause8144.ORPHAN_SELL_NO_LINEAGE to 1, Cause8144.LIVE_BUY_NO_SIGNATURE to 1, Cause8144.LIVE_BUY_NO_CONFIRMED_FILL to 1,
            Cause8144.LIVE_BUY_TX_NOT_CONFIRMED to 1, Cause8144.LIVE_SELL_NO_CONFIRMED_QTY to 1, Cause8144.LIVE_SELL_NO_PROCEEDS to 1,
            Cause8144.LIVE_SELL_TX_NOT_CONFIRMED to 1, Cause8144.DUPLICATE_SIGNATURE_MOVEMENT to 1, Cause8144.ENTRY_BASIS_INVALID to 1,
            Cause8144.CANONICAL_NOT_ON_CHAIN to 1, Cause8144.QUANTITY_MISMATCH to 1, Cause8144.CHAIN_BOT_HOLDING_NOT_CANONICAL to 1,
        )
        assertEquals(expected, r.byCause)
        assertEquals(12, r.exceptions.size)
        assertEquals(3, r.states[State8144.MATCHED])
        assertEquals(1, r.states[State8144.QUARANTINED])
        assertEquals(1, r.states[State8144.FROZEN_HELD])
        assertEquals(1, r.states[State8144.PENDING_TX])
        assertEquals(1, r.states[State8144.DUST])
        assertEquals(1, r.states[State8144.OWNER_UNOWNED])
        assertEquals(1, r.lineageResolvedSells)
        assertEquals("MISMATCH", r.inventory)
        assertEquals(3, r.liveBuys)
        assertEquals(5, r.liveSells)
        assertFalse(r.ok)
        val line = ReconcileClassifier8144.statusLine8144(r, 30_000L, 2L)
        assertTrue(line.contains("inventory=MISMATCH"))
        assertTrue(line.contains("QUANTITY_MISMATCH=1"))
        assertTrue(line.contains("FROZEN_HELD=1"))
    }

    @Test fun cleanBookMatchesAndPartialReadsProveNothing() {
        val lotA = Lot8144("pA", "A", "OPEN", big(1_000), big(1_000), 0.1)
        val clean = Inputs8144(lots = listOf(lotA), chain = mapOf("A" to big(1_005)), chainComplete = true, txs = emptyList(),
            journal = listOf(Row8144(side = "BUY", mint = "A", sig = "SA", fillQty = 1.0)), hostOpenMints = setOf("A"))
        val ok = ReconcileClassifier8144.classify8144(clean)
        assertTrue(ok.ok)
        assertEquals("MATCH", ok.inventory)
        // absence in a partial read is a lower bound, never a zero balance
        val partial = ReconcileClassifier8144.classify8144(clean.copy(chain = emptyMap(), chainComplete = false))
        assertEquals(0, partial.count(Cause8144.CANONICAL_NOT_ON_CHAIN))
        assertEquals("UNVERIFIED_CHAIN_PARTIAL", partial.inventory)
        for (observed in listOf(1L, 500L, 1_000L, 1_005L)) {
            val lowerBound = ReconcileClassifier8144.classify8144(clean.copy(chain = mapOf("A" to big(observed)), chainComplete = false))
            assertEquals("UNVERIFIED_CHAIN_PARTIAL", lowerBound.inventory)
            assertEquals(0, lowerBound.count(Cause8144.QUANTITY_MISMATCH))
            assertEquals(0, lowerBound.states[State8144.MATCHED] ?: 0)
            assertEquals(1, lowerBound.states[State8144.PARTIAL_CHAIN_OBSERVED])
            assertFalse(lowerBound.ok)
        }
        val excess = ReconcileClassifier8144.classify8144(clean.copy(chain = mapOf("A" to big(2_000)), chainComplete = false))
        assertEquals(1, excess.count(Cause8144.QUANTITY_MISMATCH))
        val realMissing = ReconcileClassifier8144.classify8144(clean.copy(chain = mapOf("A" to big(500))))
        assertEquals(1, realMissing.count(Cause8144.QUANTITY_MISMATCH))
        assertEquals("UNVERIFIED_NO_CHAIN", ReconcileClassifier8144.classify8144(clean.copy(chain = null)).inventory)
        // canonical owns a held lot the host projection dropped
        val dropped = ReconcileClassifier8144.classify8144(clean.copy(hostOpenMints = emptySet()))
        assertEquals(1, dropped.count(Cause8144.HOST_MISSING_CANONICAL_HELD))
        assertTrue(ReconcileClassifier8144.qtyMatches8144(big(1_000), big(1_009)))
        assertFalse(ReconcileClassifier8144.qtyMatches8144(big(1_000), big(1_011)))
        assertTrue(ReconcileClassifier8144.isSellLike8144("PARTIAL_SELL"))
        assertFalse(ReconcileClassifier8144.isSellLike8144("BUY"))
        assertTrue(ReconcileClassifier8144.adoptedMarker8144("OBSERVED_MARK_EXIT", ""))
        assertFalse(ReconcileClassifier8144.adoptedMarker8144("TP", "LIVE:mint:abc"))
    }

    // ── a learning reset never erases confirmed transaction evidence ──

    @Test fun learningResetKeepsTransactionEvidence() {
        assertEquals(emptyList<String>(), ReconcileEvidenceStores8144.violations8144(
            LearningResetSweep7781.LEARNING_PREFS_7781, LearningResetSweep7781.LEARNING_DBS_7975, LearningResetSweep7781.LEARNING_FILES_7975))
        assertEquals(listOf("fill_lot_ledger_6344", "trade_history.db", ReconcileLineageLedger8144.FILE_NAME_8144),
            ReconcileEvidenceStores8144.violations8144(listOf("token_win_memory", "fill_lot_ledger_6344"), listOf("trade_history.db"),
                listOf(ReconcileLineageLedger8144.FILE_NAME_8144)))
        assertFalse(ReconcileEvidenceStores8144.resettable8144("aate_signed_tx_8085"))
        assertFalse(ReconcileEvidenceStores8144.resettable8144("economic_events_6486.ndjson"))
        assertTrue(ReconcileEvidenceStores8144.resettable8144("token_win_memory"))
        assertTrue(ReconcileEvidenceStores8144.PROTECTED_PREFS_8144.contains("canonical_economic_events_6486"))
        assertTrue(ReconcileEvidenceStores8144.PROTECTED_DBS_8144.contains("trade_history.db"))
        assertTrue(ReconcileEvidenceStores8144.PROTECTED_FILES_8144.contains("host_wallet_token_tracker.json"))
        val reset = src("engine/truth/LearningResetSweep7781.kt")
        assertTrue(reset.contains("if (!ReconcileEvidenceStores8144.resettable8144(name)) continue"))
        assertTrue(reset.contains("if (ReconcileEvidenceStores8144.resettable8144(db))"))
    }

    // ── wiring: periodic, off the main thread, on the status line ──

    @Test fun reconciliationRunsPeriodicallyAndIsOnTheStatusLine() {
        val runner = src("engine/ReconcileRunner8144.kt")
        assertTrue(runner.contains("newSingleThreadScheduledExecutor"))
        assertTrue(runner.contains("scheduleWithFixedDelay({ runSafely8144() }"))
        assertTrue(src("engine/WalletReconciler.kt").contains("ReconcileRunner8144.ensureStarted8144()"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("ReconcileRunner8144.statusLine8144()"))
        assertTrue(src("engine/AccountingIdempotencyRegistry.kt").contains("ReconcileSignatureGuard8144.alreadyJournaled8144(signature, mint, action)"))
        assertTrue(src("engine/truth/CanonicalPositionAuthority6441.kt").contains("noteLineage8144(promoted, promoted.runId"))
        assertTrue(ReconcileClassifier8144.statusLine8144(null, 0L, 0L).contains("not run yet"))
    }
}
