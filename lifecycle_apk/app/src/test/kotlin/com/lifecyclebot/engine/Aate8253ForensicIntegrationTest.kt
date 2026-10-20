package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136 as Admission
import com.lifecyclebot.engine.truth.HeldExitQuote8189
import com.lifecyclebot.engine.truth.PositionDisplayTruth8078
import com.lifecyclebot.engine.sell.StopLatencyCore8153
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

/** Regression fixtures from the 19:33 report; these are not on-chain replay evidence. */
class Aate8253ForensicIntegrationTest {
    @Test fun heldReserveRefreshUsesExitWindowInsteadOfAdmissionWindow() {
        val depth = com.lifecyclebot.engine.truth.OnChainDepth8038
        val read = com.lifecyclebot.engine.truth.OnChainDepth8038.Read("observed-pool", 10.0, 50_000.0, 100_000L)
        assertTrue(depth.usableRead(read, 90_000L, 125_000L))
        assertFalse(depth.usableRead(read, 20_000L, 125_000L))
        assertTrue(depth.usableRead(read, 20_000L, 119_000L))
        assertFalse(depth.usableRead(read.copy(quoteSol = Double.POSITIVE_INFINITY), 20_000L, 119_000L))
        assertFalse(depth.usableRead(read, 20_000L, 99_000L))
    }

    private val lanes = listOf("QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
        "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN")

    @Test fun supportedOptionalContextAndFallbackLiquidityAcrossEveryLane() {
        for (lane in lanes) {
            val missing = Admission.resolvedMissingInputs(listOf("liq:UNKNOWN", "liquidity:UNKNOWN", "shape:INVALID"), true)
            assertEquals(listOf("shape:INVALID"), missing)
            val c = Admission.Candidate8136("fixture-$lane", lane, "supported", live = true,
                missingInputs = missing, priceAgeMs = 1_000L, expectedGrossPct = 30.0, costPct = 3.0,
                cell = Admission.Evidence8136("CELL", "fixture", 60, 20.0, 2.0))
            assertEquals(lane, Admission.Terminal8136.ADMIT, Admission.decide8136(c).terminal)
            for (essential in listOf("route:UNKNOWN", "sellability:UNKNOWN", "decimals:UNKNOWN")) {
                assertEquals("$lane $essential", Admission.Terminal8136.DEFER_DATA,
                    Admission.decide8136(c.copy(missingInputs = missing + essential)).terminal)
            }
            assertEquals(Admission.Terminal8136.REFUSE, Admission.decide8136(c.copy(safetyRefusal = "HARD_BLOCK")).terminal)
            assertEquals(Admission.Terminal8136.REFUSE, Admission.decide8136(c.copy(expectedGrossPct = 1.0)).terminal)
            assertEquals(Admission.Terminal8136.REFUSE, Admission.decide8136(c.copy(
                setups = listOf(Admission.Evidence8136("SETUP", "supported", 60, -20.0, 2.0)))).terminal)
        }
    }

    @Test fun handoffResolvesBareAndSolanaStorageWithoutCrossChainAliasing() {
        val mint = "8253-alias-fixture"
        val token = com.lifecyclebot.data.TokenState(mint)
        val tokens = BotService.status.tokens
        synchronized(tokens) {
            val oldBare = tokens.remove(mint)
            val oldAlias = tokens.remove("solana|$mint")
            try {
                tokens["solana|$mint"] = token
                assertSame(token, HandoffRoute8180.tokenFor8180(mint))
                assertSame(token, HandoffRoute8180.tokenFor8180("solana|$mint"))
                assertNull(HandoffRoute8180.tokenFor8180("ethereum|$mint"))
                tokens.remove("solana|$mint")
                tokens[mint] = token
                assertSame(token, HandoffRoute8180.tokenFor8180("solana|$mint"))
            } finally {
                tokens.remove(mint)
                tokens.remove("solana|$mint")
                if (oldBare != null) tokens[mint] = oldBare
                if (oldAlias != null) tokens["solana|$mint"] = oldAlias
            }
        }
    }

    @Test fun receiverKeepsItsOwnSupportedAdmissionAcrossOptionalClassifierGaps() {
        for (lane in lanes) {
            val decision = Admission.decide8136(Admission.Candidate8136("receiver-$lane", lane, "supported",
                live = true, missingInputs = listOf("shape:UNKNOWN"), priceAgeMs = 1_000L,
                expectedGrossPct = 30.0, costPct = 3.0,
                cell = Admission.Evidence8136("CELL", "fixture", 60, 20.0, 2.0)))
            assertTrue(lane, ReceiverRecord8190.supportedAdmission8253(decision))
            assertNull(ReceiverRecord8190.verdict8190(true, ReceiverRecord8190.supportedAdmission8253(decision),
                "NO_TRIGGER", listOf("shape:UNKNOWN"), null, 1_000L))
            assertFalse(ReceiverRecord8190.supportedAdmission8253(decision.copy(terminal = Admission.Terminal8136.DEFER_DATA)))
            assertFalse(ReceiverRecord8190.supportedAdmission8253(decision.copy(expectedNetPct = -1.0)))
            assertFalse(ReceiverRecord8190.supportedAdmission8253(decision.copy(specific = false)))
        }
    }

    @Test fun readyLaneCannotHideAnotherQualifiedProvenStrategy() {
        val election = com.lifecyclebot.engine.truth.StrategyElection8243
        for (lane in lanes.filter { it != "SHITCOIN" }) {
            val verdicts = mapOf(
                "SHITCOIN" to com.lifecyclebot.engine.truth.StrategyElection8243.Verdict8243("SHITCOIN", Admission.Terminal8136.DEFER_DATA, false, Double.NaN, null, "missing"),
                lane to com.lifecyclebot.engine.truth.StrategyElection8243.Verdict8243(lane, Admission.Terminal8136.ADMIT, true, 12.0, "proven", "SPECIFIC_EDGE_PROVEN_8136"))
            val contest = election.contestLanes(setOf("SHITCOIN"), setOf(lane, "SHITCOIN"), verdicts)
            assertTrue(lane, lane in contest)
            assertEquals(lane, election.ownerOrder8243(contest.associateWith { if (it == lane) 40.0 else 90.0 }, verdicts))
            assertEquals(setOf("SHITCOIN"), election.contestLanes(setOf("SHITCOIN"), setOf(lane), emptyMap()))
        }
    }

    @Test fun rescueCompetitionDoesNotBecomePermanentIneligibility() {
        val election = SpecialistElection8136
        election.resetForTests8136()
        try {
            val now = 1_000_000L
            election.registerNative8136("competition-mint", 1L, now,
                mapOf("QUALITY" to SpecialistElection8136.NativeView8136(true, 70.0, "READY")), now)
            election.noteRescueCompetition("competition-mint", "QUALITY", now)
            assertTrue(election.mayOwn8136("competition-mint", "QUALITY"))
            assertEquals(SpecialistElection8136.Terminal8136.CONTRIBUTOR_SHADOW,
                election.terminalOf8136("competition-mint", "QUALITY")?.first)
            election.markIneligible8136("competition-mint", "QUALITY", "HARD_BLOCK", now)
            election.noteRescueCompetition("competition-mint", "QUALITY", now)
            assertFalse(election.mayOwn8136("competition-mint", "QUALITY"))
        } finally { election.resetForTests8136() }
    }

    @Test fun unknownLiquidityIsNotErasedWithoutAnObservation() {
        val missing = listOf("liq:UNKNOWN", "shape:INVALID", "sellability:UNKNOWN")
        assertEquals(missing, Admission.resolvedMissingInputs(missing, false))
        assertEquals(listOf("shape:INVALID", "sellability:UNKNOWN"), Admission.resolvedMissingInputs(missing, true))
    }

    @Test fun confirmedPoolLiquiditySurvivesPrimaryFailureButStaleReservesDoNot() {
        val resolver = com.lifecyclebot.engine.truth.SetupFeatureResolver8169
        val read = com.lifecyclebot.engine.truth.OnChainDepth8038.Read("verified-pool", 10.0, 100_000.0, 100_000L)
        val chain = resolver.chainLiquidity(read, 100.0, 110_000L, 60_000L)
        val result = resolver.first8169(listOf(com.lifecyclebot.engine.truth.SetupFeatureResolver8169.Cand8169<Double>(resolver.PRIMARY, null, "FETCH_FAILED"), chain))
        assertEquals(2000.0, result.value!!, 0.0)
        assertEquals(resolver.CHAIN, result.source)
        assertNull(resolver.chainLiquidity(read, 100.0, 161_000L, 60_000L).value)
        assertNull(resolver.chainLiquidity(read, Double.NaN, 110_000L, 60_000L).value)
    }

    @Test fun admittedAlternativeKeepsItsIdentityThroughLiveLossChecksAndReporting() {
        for (lane in lanes) {
            val decision = Admission.Decision8136(Admission.Terminal8136.ADMIT, "SPECIFIC_EDGE_PROVEN_8136",
                true, "SUPPORTED_ALTERNATIVE", 12.0, emptyList())
            assertEquals(lane, "SUPPORTED_ALTERNATIVE",
                com.lifecyclebot.engine.truth.LiveEdgeGate7877.admittedSetup(decision) {
                    fail("An admitted strategy must not be replaced by a fresh classifier read")
                    "LOSING_DEFAULT"
                })
            assertNull(com.lifecyclebot.engine.truth.LiveEdgeGate7877.admittedSetup(decision.copy(chosenSetup = null)) { "LOSING_DEFAULT" })
            assertEquals("CLASSIFIED", com.lifecyclebot.engine.truth.LiveEdgeGate7877.admittedSetup(null) { "CLASSIFIED" })
        }
    }

    @Test fun supportedStrategyResolvesItsStagePriorOnceWithoutClearingOtherRefusals() {
        val gate = com.lifecyclebot.engine.truth.LiveEdgeGate7877
        for (lane in lanes) {
            val c = Admission.Candidate8136("stage-$lane", lane, "supported", live = true,
                stage = "GROWTH", priceAgeMs = 1_000L, expectedGrossPct = 30.0, costPct = 3.0,
                setups = listOf(Admission.Evidence8136("SETUP", "supported", 60, 20.0, 2.0)),
                stageRecord = Admission.Evidence8136("STAGE", "GROWTH", 60, -20.0, 2.0))
            val decision = Admission.decide8136(c)
            assertEquals(lane, Admission.Terminal8136.ADMIT, decision.terminal)
            assertTrue(gate.stagePriorAlreadyResolved(decision, lane, "STAGE_PROVEN_LOSING_7928_${lane}_GROWTH"))
            assertFalse(gate.stagePriorAlreadyResolved(decision, lane, "STAGE_PROVEN_LOSING_7928_${lane}_DECLINE"))
            assertFalse(gate.stagePriorAlreadyResolved(decision, lane, "PLAYBOOK_SETUP_PROVEN_LOSING_7907_${lane}_supported"))
            assertFalse(gate.stagePriorAlreadyResolved(decision, lane, "COST_ABOVE_GROSS"))
            val negative = Admission.decide8136(c.copy(setups = emptyList()))
            assertEquals(Admission.Terminal8136.REFUSE, negative.terminal)
            assertFalse(gate.stagePriorAlreadyResolved(negative, lane, "STAGE_PROVEN_LOSING_7928_${lane}_GROWTH"))
        }
    }

    @Test fun quoteForPrePartialQuantityCannotPriceRemainingPosition() {
        val before = BigInteger("158329976511")
        val after = BigInteger("63331990605")
        val quote = HeldExitQuote8189.Quote8189(0.00004283, 100_000L, before)
        assertTrue(HeldExitQuote8189.matchesQuantity(quote, before, 101_000L))
        assertFalse(HeldExitQuote8189.matchesQuantity(quote, after, 101_000L))
        assertFalse(HeldExitQuote8189.matchesQuantity(quote, before, 105_000L))
    }

    @Test fun runnerIntentSurvivesTheActualEntrySerializationAndPromotion() {
        val snapshots = com.lifecyclebot.engine.truth.EntryStrategySnapshot6450
        val entry = com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.Snapshot(
            positionId = "runner-position", mint = "runner-mint", entryLane = "MOONSHOT", entryStrategyPid = "",
            entryTactic = "RUNNER", entryRiskProfile = "", entryExitProfile = "RUNNER", entrySource = "PUMP",
            entryScore = 61, entryLiquiditySol = 10.0, entryMarketCapUsd = 9000.0,
            entryTimestampMs = 1L, entryThresholdSnapshot = "", entryPriceUsd = 0.00001,
            candidateVersion8090 = 42L, mode8090 = "LIVE", runnerEntry = true)
        val encoded = snapshots.encodeSnapshot(entry)
        val restored = snapshots.decodeSnapshot(encoded.toString(), entry.positionId)
        assertEquals(entry, restored)
        assertTrue(promotedEntrySnapshot7871(restored, "confirmed-position", 0.00002)!!.runnerEntry)
        encoded.remove("runnerEntry")
        assertFalse("Legacy entries must not invent runner evidence", snapshots.decodeSnapshot(encoded.toString(), entry.positionId).runnerEntry)
    }

    @Test fun rowleyIndicativeGainDoesNotMakeNegativeSliceProceedsProfitable() {
        val indicative = (0.00004283 / 0.000019647738 - 1.0) * 100.0
        assertTrue(indicative > 117.0)
        // Reported partial: 0.017154 gross received against 0.017925 basis, before additional costs.
        assertFalse(ProfitExitTruth8163.clears(0.017925, 17_154_000L, 0, 0.0))
        val qty = 94997.985906
        val solUsd = 100.0 // Unit-conversion fixture, not an asserted historical market price.
        val quoteUsd = 0.017154 * solUsd / qty
        assertEquals(-0.000771, PositionDisplayTruth8078.executableNetPnl(0.017925, qty, quoteUsd, solUsd, 0.0)!!, 1e-12)
        assertNull(PositionDisplayTruth8078.executableNetPnl(0.017925, qty, Double.NaN, solUsd, 0.0))
        assertFalse(ProfitExitTruth8163.profitTaking("MANUAL"))
        assertFalse(ProfitExitTruth8163.profitTaking("STOP_LOSS"))
    }

    @Test fun walletProofBeforeSignedSellCannotResurrectHolding() {
        val proof = StopLatencyCore8153.HeldProof8153(BigInteger("63331990605"), 6, "fixture", 100_000L)
        assertTrue(StopLatencyCore8153.usable8153(proof, 110_000L, 99_000L, false))
        assertFalse(StopLatencyCore8153.usable8153(proof, 110_000L, 105_000L, false))
        assertFalse(StopLatencyCore8153.usable8153(proof, 110_000L, 99_000L, true))
    }

    @Test fun rowleyReconcileDiagnosticMustNotTruncateRawBalanceIntoFalseScaleMismatch() {
        val raw = BigInteger("63331990605")
        val report = com.lifecyclebot.engine.truth.ReconcileClassifier8144.classify8144(
            com.lifecyclebot.engine.truth.ReconcileClassifier8144.Inputs8144(
                lots = listOf(com.lifecyclebot.engine.truth.ReconcileClassifier8144.Lot8144(
                    "protective-row", "rowley-fixture", "QUARANTINED", raw, raw, 0.0119)),
                chain = mapOf("rowley-fixture" to raw), chainComplete = true, txs = emptyList(), journal = emptyList(),
                botEvidenceMints = setOf("rowley-fixture"), hostOpenMints = emptySet()))
        val diagnostic = com.lifecyclebot.engine.truth.ReconcileClassifier8144.statusLine8144(report, 0L, 1L)
        assertTrue(diagnostic.contains("canonicalRaw=63331990605 chainRaw=63331990605"))
        assertEquals(1, report.count(com.lifecyclebot.engine.truth.ReconcileClassifier8144.Cause8144.HOST_MISSING_CANONICAL_HELD))
        assertEquals(0, report.count(com.lifecyclebot.engine.truth.ReconcileClassifier8144.Cause8144.QUANTITY_MISMATCH))
    }
}
