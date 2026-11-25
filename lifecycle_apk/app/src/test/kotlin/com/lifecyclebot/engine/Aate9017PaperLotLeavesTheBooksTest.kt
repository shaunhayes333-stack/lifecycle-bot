package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.PaperRetire9017
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.9017 — a paper lot nobody can sell (its terminal sale refused by the books every time, or never attemptable under a
 * close request) leaves the books: quarantined, nothing remaining, no cash, no learning, and it stays gone across restarts.
 */
class Aate9017PaperLotLeavesTheBooksTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun onlyARefusalTheLotMeetsEveryTimeCounts() {
        val r = PaperRetire9017
        assertTrue(r.structural9017("LEDGER_PRECHECK_8576_BASIS_EXCEEDS_OPEN"))
        assertTrue(r.structural9017("LEDGER_PRECHECK_8576_BASIS_IMPLAUSIBLE"))
        assertTrue(r.structural9017("LEDGER_PRECHECK_8576_QUARANTINED_HISTORICAL"))
        assertTrue(r.structural9017("ECONOMIC_UNIT_NOTIONAL_MISMATCH_7061"))
        assertTrue(r.structural9017("CANONICAL_QTY_QTY_DECIMAL_SKEW"))
        assertTrue(r.structural9017("SELL_QTY_REQUEST_EXCEEDS_CANONICAL_REMAINING"))
        assertFalse("nothing to retire", r.structural9017("CANONICAL_QTY_UNKNOWN_POSITION"))
        assertFalse("already terminal", r.structural9017("CANONICAL_QTY_LIFECYCLE_CLOSED"))
        assertFalse("a store that failed mid-commit has its own retry", r.structural9017("SELL_QTY_COMMIT_FAILED_6498"))
        assertFalse(r.structural9017("POSITION_MUTATION_REJECTED")); assertFalse(r.structural9017("LEDGER_COMMIT_FAILED"))
        assertFalse("a claim another close holds passes", r.structural9017("TERMINAL_ALREADY_CLAIMED")); assertFalse(r.structural9017(""))
    }

    @Test fun neverOnAFirstRefusal() {
        val r = PaperRetire9017
        val min = 60_000L
        assertFalse(r.persisted9017(1_000L, 1, 1_000L + 11 * min, r.REFUSED_PERSIST_MS_9017, r.REFUSED_MIN_9017))
        assertFalse("three refusals in a minute are one moment", r.persisted9017(1_000L, 3, 1_000L + min, r.REFUSED_PERSIST_MS_9017, r.REFUSED_MIN_9017))
        assertTrue(r.persisted9017(1_000L, 3, 1_000L + 10 * min, r.REFUSED_PERSIST_MS_9017, r.REFUSED_MIN_9017))
        assertFalse(r.persisted9017(0L, 9, 99 * min, r.REFUSED_PERSIST_MS_9017, r.REFUSED_MIN_9017))
        assertEquals(10 * min, r.REFUSED_PERSIST_MS_9017); assertEquals(60 * min, r.UNSOLD_PERSIST_MS_9017)
        assertEquals(3, r.REFUSED_MIN_9017); assertEquals(6, r.UNSOLD_MIN_PASSES_9017)
    }

    @Test fun whatWasRetiredOfACarryIsNotRestored() {
        val r = PaperRetire9017
        val q = BigInteger.valueOf(1_000L)
        assertEquals(q to 0.5, r.netCarry9017(q, 0.5, BigInteger.ZERO, 0.0))
        assertNull("all of it was retired", r.netCarry9017(q, 0.5, q, 0.5))
        assertNull(r.netCarry9017(q, 0.5, BigInteger.valueOf(2_000L), 0.9))
        val left = r.netCarry9017(q, 0.5, BigInteger.valueOf(400L), 0.2)
        assertNotNull("new carry on the same coin is still restored, less what was retired", left)
        assertEquals(BigInteger.valueOf(600L), left!!.first); assertEquals(0.3, left.second, 1e-9)
        assertNull("quantity with no cost left is not a lot", r.netCarry9017(q, 0.5, BigInteger.valueOf(400L), 0.5))
        assertTrue(r.carryId9017("PAPER:CARRY6492:So11111111111111111111111111111111111111112")); assertFalse(r.carryId9017("PAPER:So111:1:2"))
    }

    @Test fun theRetiredSetSurvivesItsOwnEncoding() {
        val rows = listOf(
            PaperRetire9017.Retired("PAPER:mintA:53793:14", "mintA", 1_793_950_850_487L, BigInteger("10076028585"), 0.1073, "LEDGER_PRECHECK_8576_BASIS_EXCEEDS_OPEN", 5L),
            PaperRetire9017.Retired("PAPER:CARRY6492:mintB", "mintB", 7L, BigInteger("123456789012345678901234567890"), 2.5, "NO_OWNER_9017", 6L),
        )
        val back = PaperRetire9017.decode9017(PaperRetire9017.encode9017(rows))
        assertEquals(2, back.size)
        assertEquals("PAPER:mintA:53793:14", back[0].positionId); assertEquals(1_793_950_850_487L, back[0].openedAtMs)
        assertEquals(BigInteger("123456789012345678901234567890"), back[1].qtyRaw); assertEquals(2.5, back[1].costSol, 1e-12)
        assertEquals("NO_OWNER_9017", back[1].why)
        assertTrue(PaperRetire9017.decode9017(null).isEmpty()); assertTrue(PaperRetire9017.decode9017("not json").isEmpty())
    }

    @Test fun aLotRefusedEveryTimeLeavesTheBooksAndAPassingRefusalDoesNot() {
        PaperRetire9017.resetForTest9017()
        val sfx = System.nanoTime().toString()
        val id = "PAPER:RETIRE9017_$sfx"; val mint = "Retire9017Mint$sfx"
        val opened = CanonicalPositionAuthority6441.openPosition(
            idempotencyKey = "RETIRE9017_$sfx", positionId = id, mint = mint, symbol = "R17", lane = "MOONSHOT", runId = "TEST_9017",
            entryCostSol = 0.1073, openedQtyRaw = BigInteger.valueOf(1_000_000L), tokenDecimals = 6, feesSol = 0.0,
            // the book's own cash is not this test's subject: the mode is named, the cash door is not asked
            paperMode = false, modeOverride = "paper", entryPriceUsd = 0.001, entryPriceSource = "TEST_9017",
        )
        val p0 = CanonicalPositionAuthority6441.getPosition(id)
        // another test in this JVM may have left the runtime LIVE with paper off (8216): then no paper lot can be opened here
        assumeTrue(opened == CanonicalPositionAuthority6441.MutateResult.APPLIED && p0 != null &&
            p0.lifecycle == CanonicalPositionAuthority6441.Lifecycle.OPEN && p0.mode == "paper")
        val t0 = 1_000_000L; val min = 60_000L
        val why = "LEDGER_PRECHECK_8576_BASIS_EXCEEDS_OPEN"
        assertFalse(PaperRetire9017.onTerminalRefused9017(id, "TERMINAL_ALREADY_CLAIMED", t0))
        assertFalse(PaperRetire9017.onTerminalRefused9017(id, why, t0))
        assertFalse(PaperRetire9017.onTerminalRefused9017(id, why, t0 + min))
        assertFalse("three refusals, but not yet ten minutes of them", PaperRetire9017.onTerminalRefused9017(id, why, t0 + 9 * min))
        assertFalse(PaperStopLifecycle8521.confirmedClosed(id))
        assertTrue(PaperRetire9017.onTerminalRefused9017(id, why, t0 + 10 * min))

        val p = CanonicalPositionAuthority6441.getPosition(id)!!
        assertEquals(CanonicalPositionAuthority6441.Lifecycle.QUARANTINED, p.lifecycle)
        assertEquals(BigInteger.ZERO, p.remainingQtyRaw)
        assertTrue(p.quarantineReason.startsWith(PaperRetire9017.REASON_PREFIX_9017))
        assertEquals("no proceeds and no realised figure are written", 0.0, p.realizedProceedsSol, 0.0); assertEquals(0.0, p.realizedPnlSol, 0.0)
        assertTrue(PaperRetire9017.isRetired9017(p))
        assertTrue("a Stop's request for it is met", PaperStopLifecycle8521.confirmedClosed(id))
        assertFalse(CanonicalPositionAuthority6441.openPositions().any { it.positionId == id })
        assertFalse("it is not a closed trade: nothing audits or learns from it", CanonicalPositionAuthority6441.closedPositions().any { it.positionId == id })
        assertTrue(PaperRetire9017.retiredSnapshot9017().any { it.positionId == id && it.openedAtMs == p.openedAtMs && it.qtyRaw == BigInteger.valueOf(1_000_000L) })
        assertFalse("already off the books: nothing more to retire", PaperRetire9017.retire9017(id, why))
        assertEquals("the rebuild's second pass finds nothing open to retire", 0, CanonicalPositionAuthority6441.applyRetired9017(PaperRetire9017.retiredSnapshot9017()))
        assertTrue(PaperRetire9017.statusLine9017().startsWith("paperRetire9017[retired{everKept="))
        PaperRetire9017.resetForTest9017()
    }

    @Test fun aLotACloseRequestCannotAttemptLeavesAfterAnHourOfTrying() {
        PaperRetire9017.resetForTest9017()
        val t0 = 5_000_000L; val min = 60_000L
        val ghost = "PAPER:NOSUCH9017_${System.nanoTime()}"
        // an unknown id is never retired, however long it is asked for; an attempted id restarts its clock
        repeat(8) { i -> assertTrue(PaperRetire9017.noteUnsold9017(listOf(ghost), emptyList(), "NO_OWNER_9017", t0 + i * 10 * min).isEmpty()) }
        assertTrue(PaperRetire9017.noteUnsold9017(emptyList(), listOf(ghost), "NO_OWNER_9017", t0).isEmpty())
        val s = src("engine/truth/PaperRetire9017.kt")
        assertTrue(s.contains("persisted9017(s.firstAtMs, s.count, nowMs, UNSOLD_PERSIST_MS_9017, UNSOLD_MIN_PASSES_9017) && retire9017(id, why, nowMs)"))
        assertTrue(s.contains("attempted.forEach { unsold.remove(it) }"))
        PaperRetire9017.resetForTest9017()
    }

    @Test fun everyDoorThatCanBeRefusedReportsItAndEveryOwnerDropsItsCopy() {
        val exec = src("engine/Executor.kt")
        val refused = exec.substringAfter("PipelineHealthCollector.labelInc(\"PAPER_CLOSE_REFUSED_8872_").substringBefore("// V5.0.6628 §5 CANONICAL_CLOSE_JOURNAL_ATOMICITY")
        assertTrue(refused.contains("if (retiredOnRefusal9017(ts, terminalPid6455, paperBridgeWhy8872)) return SellResult.ALREADY_CLOSED"))
        val helper = exec.substringAfter("private fun retiredOnRefusal9017(").substringBefore("fun dropRetiredPaper9017(")
        assertTrue(helper.contains("PaperRetire9017.onTerminalRefused9017(positionId, why)"))
        assertTrue(helper.contains("PositionStateLedger6454.confirmTerminalSell(positionId)") && helper.contains("dropRetiredPaper9017(ts, positionId)"))
        assertTrue("a refused sale that is not retired still fails as before", refused.contains("return SellResult.FAILED_FATAL"))
        val drop = exec.substringAfter("fun dropRetiredPaper9017(").substringBefore("private fun reconcileCanonicalClosedPaper7836(")
        for (c in listOf("ts.position = Position()", "PositionPersistence.removePosition(ts.mint, positionId, expectedPaper8521 = true)",
            "PaperPositionCloseAuthority.markClosed(\"PAPER\"", "PaperStopLaneRegistry8521.retire(positionId)")) assertTrue(c, drop.contains(c))
        assertFalse("nothing is recorded as a trade or taught", drop.contains("recordTrade") || drop.contains("tradeId.closed"))

        val tx = src("engine/truth/CanonicalPaperTransaction6486.kt")
        assertTrue(tx.contains("if (terminal) try { PaperRetire9017.onTerminalRefused9017(positionId, r.reason) } catch (_: Throwable) {}"))

        val stop = src("engine/BotService.kt").substringAfter("private fun closePaperStopIntent8521(").substringBefore("private fun closeLivePositionsOnStop8521(")
        assertTrue(stop.contains("val noOwner9017 = targets - tokens.keys - markets.requestedIds - confirmed"))
        assertTrue(stop.contains("val noPrice9017 = (tokens.keys + markets.requestedIds) - attempted9017 - confirmed"))
        assertTrue(stop.contains("PaperRetire9017.noteUnsold9017(noOwner9017, attempted9017, \"NO_OWNER_9017\")"))
        assertTrue(stop.contains("CryptoAltTrader.pruneTerminalPaper9017()"))

        val life = src("engine/PaperStopLifecycle8521.kt")
        assertTrue(life.contains("com.lifecyclebot.engine.truth.PaperRetire9017.isRetired9017(p)"))
        val alt = src("perps/CryptoAltTrader.kt")
        assertTrue(alt.contains("if (pos.isPaper && com.lifecyclebot.engine.PaperStopLifecycle8521.confirmedClosed(pos.id)) {"))
        for (f in listOf("perps/CommoditiesTrader.kt", "perps/MetalsTrader.kt", "perps/ForexTrader.kt"))
            assertTrue(f, src(f).contains("if (com.lifecyclebot.engine.PaperStopLifecycle8521.confirmedClosed(position.id)) {"))

        val canon = src("engine/truth/CanonicalPositionAuthority6441.kt")
        val rebuild = canon.substringAfter("fun rebuildPaperFromEvents6486(").substringBefore("fun pendingEntryPositions6461(")
        assertTrue("retired carry is netted where the carry is restored", rebuild.contains("PaperRetire9017.netCarry9017(carryQtyRaw9017, carryCostRaw9017, gone.first, gone.second)"))
        assertTrue("retired lots are retired again before any projection is built from the open set",
            rebuild.indexOf("applyRetired9017(PaperRetire9017.retiredSnapshot9017())") in 1 until rebuild.indexOf("val canonicalOpen6519 = positions.values.filter {"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("PaperRetire9017.statusLine9017()"))
    }
}
