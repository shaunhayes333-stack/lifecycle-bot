package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalTokenAmount
import com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * V5.0.8304 — a sealed ticket executes or ends by name once: one owner per coin version, the fresh decision stamps a
 * reused seal, the deterministic chart refusal comes before the seal, advisory sizing never lands on a sealed ticket,
 * FDG zero sizes are named, and a proven-complete empty wallet read reaches the reconciler.
 */
class Aate8304TicketExecTest {
    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private fun amt(raw: Long) = CanonicalTokenAmount(BigInteger.valueOf(raw), 6)

    @Test fun theLiveSealedIntentOwnsTheVersionOverAnOlderSnapshotOfAnotherLane() {
        assertEquals("MOONSHOT", TicketExec8304.sealedOwner8304("MOONSHOT", "SHITCOIN"))
        assertEquals("SHITCOIN", TicketExec8304.sealedOwner8304(null, "SHITCOIN"))
        assertEquals("MOONSHOT", TicketExec8304.sealedOwner8304(" moonshot ", null))
        assertNull(TicketExec8304.sealedOwner8304("", null))
        val coord = src("engine/LaneExecutionCoordinator.kt")
        assertTrue(coord.contains("val sealedFdgOwner6679 = sealedFdgOwnerLane6679(mint, candidateVersion)"))
        assertTrue(coord.contains("TicketExec8304.liveIntentLane8304(mint, candidateVersion)?.takeIf { laneCanOwnExecution6910(it) }"))
    }

    @Test fun aSupersededTicketEndsByName() {
        val gate = src("engine/ExecutableOpenGate.kt")
        val retire = gate.substringAfter("private fun retireSupersededIntent7819(").substringBefore("internal fun sameDecisionContract6734(")
        assertTrue(retire.contains("TicketExec8304.onSupersededTicket8304(old.canonicalLane, old.attemptId, owner.canonicalLane)"))
    }

    @Test fun theFreshDecisionStampsAReusedSealExactlyOnce() {
        CausalFeedbackAuthority6715.resetForTest6715()
        val intent = ExecutableOpenGate.ExecutionIntent(
            attemptId = "LIVE:MINT8304:MOONSHOT:7", candidateId = "MINT8304:7", candidateVersion = 7L,
            mint = "MINT8304", mode = "LIVE", canonicalLane = "MOONSHOT", fdgVerdict = "BUY", fdgAllowed = true,
            authorityVersion = 1L, resolvedSize = 0.0285, createdAt = System.currentTimeMillis(), symbol = "T",
        )
        assertFalse(CausalFeedbackAuthority6715.hasStamp8304(intent.attemptId))
        assertTrue(TicketExec8304.ensureCausalStamp8304(intent, 55))
        assertTrue(CausalFeedbackAuthority6715.hasStamp8304(intent.attemptId))
        // an existing stamp is never replaced: its staleness is still judged at finality
        assertFalse(TicketExec8304.ensureCausalStamp8304(intent, 90))
        CausalFeedbackAuthority6715.releaseAttempt(intent.attemptId)
        assertFalse(CausalFeedbackAuthority6715.hasStamp8304(intent.attemptId))
        CausalFeedbackAuthority6715.resetForTest6715()
        val seal = src("engine/SpecialistPreauthSeal7834.kt")
        assertTrue(seal.contains("ExecutableOpenGate.reuseSealedIntent7871("))
        assertEquals(3, Regex("TicketExec8304\\.ensureCausalStamp8304\\(it, decision\\.effectiveEntryScore7687\\)").findAll(seal).count())
        // the check itself is unchanged
        assertTrue(src("engine/truth/CausalFeedbackAuthority6715.kt").contains("return Admission(false, \"MISSING_FEEDBACK_STAMP_REVALIDATE_6715\", forceRevalidate = true)"))
    }

    @Test fun aDeadOrFlatChartIsRefusedBeforeTheSeal() {
        val auth = src("engine/TradeAuthorizer.kt")
        val pre = auth.indexOf("TicketExec8304.preSealChartRefusal8304(tokenState7835, isPaperMode)")
        val seal = auth.indexOf("SpecialistPreauthSeal7834.ensure(tokenState7835, fdgDecision7835!!")
        assertTrue(pre in 1 until seal)
        assertTrue(FlatChart8027.flatline8027(listOf(1.0, 1.001, 1.002, 1.0, 1.001)))
        assertTrue(FlatChart8027.deadRunner8027(10.0, 1.0))
        assertTrue(src("engine/FlatChart8027.kt").contains("fun preSealRefusal8304(ts: TokenState"))
        // the executor's own check stays (route/safety class, every live buy)
        assertTrue(src("engine/Executor.kt").contains("if (flatChartRefused8027(ts, sol, attemptId8198 = attemptId))"))
    }

    @Test fun advisorySizingNeverStampsASealedTicket() {
        val b = src("engine/truth/CanonicalSizingBridge6532.kt")
        assertTrue(b.contains("val sealedTicket8304 = activeIntent8304?.takeIf { it.resolvedSize.isFinite() && it.resolvedSize > 0.0 }"))
        assertTrue(b.contains("val intentOwnedCausalId6893 = if (sealedTicket8304 != null) \"\" else"))
        assertTrue(b.contains("val resolvedCausalEventId6674 = intentOwnedCausalId6893"))
        assertTrue(b.contains("TicketExec8304.advisorySizeOnSealedTicket8304("))
    }

    @Test fun sealedShitcoinAndMoonshotTicketsThatDoNotOpenEndByName() {
        val ex = src("engine/Executor.kt")
        val sc = ex.substringAfter("fun shitCoinBuy(").substringBefore("fun moonshotBuy(")
        assertTrue(sc.contains("unexecutedTicket7948(executionLane, preflight.attemptId, namedUnexecuted8252(ts.mint, \"LIVE_BUY_NOT_OPENED_7948\"))"))
        val ms = ex.substringAfter("fun moonshotBuy(").substringBefore("// V5.0.3923")
        assertTrue(ms.contains("unexecutedTicket7948(\"MOONSHOT\", preflight.attemptId, namedUnexecuted8252(ts.mint, \"LIVE_BUY_NOT_OPENED_7948\"))"))
    }

    @Test fun conversionLineCountsTicketsExecutionsAndNamedDrops() {
        assertNull(TicketExec8304.formatLine8304("SHITCOIN", 0L, 0L, 0L, emptyMap()))
        val line = TicketExec8304.formatLine8304("MOONSHOT", 11L, 2L, 8L,
            mapOf("PREAUTH_LANE_TELEMETRY_ONLY_PRIMARY_SHITCOIN" to 8L, "LIVE_BUY_NOT_OPENED_7948:FLAT_CHART_8027" to 1L))!!
        assertTrue(line.contains("tickets=11 exec=2 droppedAfterTicket=8 inFlight=1 conv=18%"))
        assertTrue(line.indexOf("PREAUTH_LANE_TELEMETRY_ONLY_PRIMARY_SHITCOIN=8") < line.indexOf("LIVE_BUY_NOT_OPENED_7948:FLAT_CHART_8027=1"))
        assertEquals("LIVE_BUY_NOT_OPENED_7948:STAGE_REASON_X", TicketExec8304.reasonKey8304("live_buy_not_opened_7948:stage reason-x"))
        assertEquals("UNNAMED", TicketExec8304.reasonKey8304("  "))
        TicketExec8304.resetForTests8304()
        TicketExec8304.onStage8304("SHITCOIN", "TICKET", "a1")
        TicketExec8304.onStage8304("SHITCOIN", "TICKET", "a1")
        TicketExec8304.onStage8304("SHITCOIN", "TICKET", "a2")
        TicketExec8304.onStage8304("SHITCOIN", "EXEC", "a1")
        TicketExec8304.onDrop8304("SHITCOIN", "a2", "LIVE_BUY_NOT_OPENED_7948:X")
        val l = TicketExec8304.line8304("SHITCOIN")!!
        assertTrue(l, l.contains("tickets=2 exec=1 droppedAfterTicket=1 inFlight=0"))
        TicketExec8304.resetForTests8304()
        val sheet = src("engine/ToolkitSignalSheet.kt")
        assertTrue(sheet.contains("TicketExec8304.onStage8304(l, st, eventId)"))
        assertTrue(sheet.contains("TicketExec8304.onDrop8304(l, attemptId, reason)"))
        assertTrue(sheet.contains("TicketExec8304.line8304(lane)?.let { appendLine(it) }"))
    }

    @Test fun fdgSizingUsesObservedDepthAndNamesEveryZero() {
        assertEquals(5000.0, TicketExec8304.depthUsd8304(5000.0, 900.0), 1e-9)
        assertEquals(900.0, TicketExec8304.depthUsd8304(0.0, 900.0), 1e-9)
        assertEquals(0.0, TicketExec8304.depthUsd8304(Double.NaN, 0.0), 1e-9)
        assertEquals(0.0, TicketExec8304.fdgSizeZero8304("MOONSHOT", "RISK_X"), 0.0)
        val fdg = src("engine/FinalDecisionGate.kt")
        val f = fdg.substringAfter("private fun resolveExecutableSize7835(").substringBefore("fun logBlockedTrade(")
        assertTrue(f.contains("val liq = TicketExec8304.sizingDepthUsd8304(ts)"))
        assertTrue(f.contains("return TicketExec8304.fdgSizeZero8304(lane, \"RISK_\" + risk.reason)"))
        assertTrue(f.contains("TicketExec8304.fdgSizeZero8304(lane, \"RESOLVER_\" + resolution.reason)"))
        // the route minimum stays real: the sealed size still must clear it
        assertTrue(f.contains("resolution.finalSizeSol + 1e-9 < sealedMinimum8269) return 0.0"))
    }

    @Test fun aProvenCompleteEmptyWalletReadReachesTheReconciler() {
        val now = 1_000_000L
        val usdc = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        val complete = WalletAccountCache.Evidence(emptyMap(), now - 1_000L, true, "OWNER")
        assertTrue(TicketExec8304.emptyReadProven8304(emptyMap(), complete, "OWNER", now))
        // USDC collateral is not a position on either side
        assertTrue(TicketExec8304.emptyReadProven8304(emptyMap(), complete.copy(tokens = mapOf(usdc to amt(5))), "OWNER", now))
        // a partial, foreign, stale or non-empty read proves nothing
        assertFalse(TicketExec8304.emptyReadProven8304(emptyMap(), complete.copy(complete = false), "OWNER", now))
        assertFalse(TicketExec8304.emptyReadProven8304(emptyMap(), complete.copy(owner = "OTHER"), "OWNER", now))
        assertFalse(TicketExec8304.emptyReadProven8304(emptyMap(), complete.copy(observedAtMs = now - 120_000L), "OWNER", now))
        assertFalse(TicketExec8304.emptyReadProven8304(emptyMap(), complete.copy(tokens = mapOf("HELD" to amt(5))), "OWNER", now))
        assertFalse(TicketExec8304.emptyReadProven8304(emptyMap(), null, "OWNER", now))
        assertFalse(TicketExec8304.emptyReadProven8304(mapOf("HELD" to amt(5)), complete, "OWNER", now))
        assertTrue(TicketExec8304.sameWalletTokens8304(mapOf(usdc to amt(1), "A" to amt(2)), mapOf("A" to amt(2))))
        assertFalse(TicketExec8304.sameWalletTokens8304(mapOf("A" to amt(3)), mapOf("A" to amt(2))))
        val rr = src("engine/ReconcileRunner8144.kt")
        assertTrue(rr.contains("val provenEmpty8304 = walletMints.isEmpty() && TicketExec8304.completeEmptyWalletRead8304(walletMints)"))
        assertTrue(rr.contains("if (walletMints.isEmpty() && !provenEmpty8304) return"))
        val wr = src("engine/WalletReconciler.kt")
        val empty = wr.substringAfter("if (walletMints.isEmpty()) {").substringBefore("HostWalletTokenTracker.applyWalletSnapshot(walletMints)")
        assertTrue(empty.contains("ReconcileRunner8144.noteWalletSnapshot8144(walletMints)"))
        assertTrue(src("engine/LiveCanonicalRecovery6686.kt").contains("TicketExec8304.sameWalletTokens8304(it.tokens, walletMints)"))
    }
}
