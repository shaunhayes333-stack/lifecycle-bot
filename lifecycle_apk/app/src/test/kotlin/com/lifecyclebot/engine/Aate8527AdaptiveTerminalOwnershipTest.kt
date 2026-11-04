package com.lifecyclebot.engine

import android.content.SharedPreferences
import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class Aate8527AdaptiveTerminalOwnershipTest {
    private var oldPrefs: Any? = null
    private var saveAllowed = true
    private var commitThrows = false
    private val disk = linkedMapOf<String, Any?>()
    private val pending = linkedMapOf<String, Any?>()
    private fun field(name: String) = AdaptiveLearningEngine.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun buffer() = field("featureBuffer").get(AdaptiveLearningEngine) as List<AdaptiveLearningEngine.TradeFeatures>

    @Before fun before() {
        oldPrefs = field("prefs").get(AdaptiveLearningEngine)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(prefs.getString(anyString(), nullable(String::class.java))).thenAnswer { disk[it.getArgument<String>(0)] as? String ?: it.getArgument<String?>(1) }
        `when`(prefs.getInt(anyString(), anyInt())).thenAnswer { disk[it.getArgument<String>(0)] as? Int ?: it.getArgument<Int>(1) }
        `when`(prefs.getFloat(anyString(), anyFloat())).thenAnswer { disk[it.getArgument<String>(0)] as? Float ?: it.getArgument<Float>(1) }
        `when`(editor.putString(anyString(), nullable(String::class.java))).thenAnswer { pending[it.getArgument<String>(0)] = it.getArgument<String?>(1); editor }
        `when`(editor.putInt(anyString(), anyInt())).thenAnswer { pending[it.getArgument<String>(0)] = it.getArgument<Int>(1); editor }
        `when`(editor.putFloat(anyString(), anyFloat())).thenAnswer { pending[it.getArgument<String>(0)] = it.getArgument<Float>(1); editor }
        `when`(editor.putLong(anyString(), anyLong())).thenAnswer { pending[it.getArgument<String>(0)] = it.getArgument<Long>(1); editor }
        `when`(editor.commit()).thenAnswer { if (commitThrows) throw IllegalStateException("fixture commit failure"); if (saveAllowed) { disk.putAll(pending); pending.clear(); true } else false }
        field("prefs").set(AdaptiveLearningEngine, prefs)
        AdaptiveLearningEngine.reset()
    }

    @After fun after() {
        saveAllowed = true
        field("prefs").set(AdaptiveLearningEngine, null)
        AdaptiveLearningEngine.reset()
        field("prefs").set(AdaptiveLearningEngine, oldPrefs)
    }

    private fun terminal(id: String = "terminal8527") = CanonicalFinalizedTradeBus6464.Envelope(
        tradeId = id, positionId = "position8527", mint = "Mint8527", lane = "MOONSHOT",
        atMs = 1_000_000L, realizedPnlSol = -0.08, realizedReturnPct = -8.0, mode = "LIVE",
        proofState = "LIVE_FINALIZED", holdingTimeMs = 600_000L, exitReason = "STOP_LOSS",
        entrySource = "DEX", entrySetup = "RUNNER", terminal = true, learningEligible = true,
    )

    @Test fun twoPartialsAndFinalRemainderCannotTeachThreeIndependentEntryOutcomes() {
        val before = AdaptiveLearningEngine.getDetailedWeights()
        val first = terminal("partial-a").copy(terminal = false, realizedPnlSol = 0.30, realizedReturnPct = 30.0)
        val second = terminal("partial-b").copy(terminal = false, realizedPnlSol = -0.38, realizedReturnPct = -38.0)
        assertFalse(AdaptiveLearningEngine.onCanonicalTerminal8527(first))
        assertFalse(AdaptiveLearningEngine.onCanonicalTerminal8527(second))
        assertEquals(before, AdaptiveLearningEngine.getDetailedWeights())
        assertTrue(buffer().isEmpty())
        assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(terminal()))
        assertEquals(1, AdaptiveLearningEngine.getTradeCount())
        assertEquals(-8.0, buffer().single().pnlPct, 0.0)
        assertEquals("ALE8527|LIVE|position8527", buffer().single().stableTradeKey)
        val economics = AdaptiveLearningEngine.transferModel8527().terminalEconomics!!
        assertEquals(1, economics.samples)
        assertEquals(-0.08, economics.netPnlSol, 0.0)
        assertEquals(0.08, economics.losingPnlSolAbs, 0.0)
    }

    @Test fun duplicateEventAndDifferentEventForSamePositionDoNotCountOrApplyTwiceAcrossRestart() {
        val env = terminal()
        assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(env))
        val learned = AdaptiveLearningEngine.getDetailedWeights()
        assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(env))
        assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(env.copy(tradeId = "other-terminal-id")))
        assertEquals(1, AdaptiveLearningEngine.getTradeCount())
        assertEquals(1, buffer().size)
        assertEquals(learned, AdaptiveLearningEngine.getDetailedWeights())
        AdaptiveLearningEngine.javaClass.getDeclaredMethod("loadState").apply { isAccessible = true }.invoke(AdaptiveLearningEngine)
        assertEquals(1, AdaptiveLearningEngine.getTradeCount())
        assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(env.copy(tradeId = "recovered-terminal-id")))
        assertEquals(1, buffer().size)
        assertEquals(learned, AdaptiveLearningEngine.getDetailedWeights())
        assertEquals(1, AdaptiveLearningEngine.getTradeCount())
    }

    @Test fun failedDurabilityRetriesCheckpointWithoutApplyingOrClaimingMaturityAgain() {
        saveAllowed = false
        assertFalse(AdaptiveLearningEngine.onCanonicalTerminal8527(terminal()))
        val pendingWeights = AdaptiveLearningEngine.getDetailedWeights()
        assertEquals(0, AdaptiveLearningEngine.getTradeCount())
        assertTrue(AdaptiveLearningEngine.observedFeatureCounts8527().values.all { it == 0 })
        assertEquals(1, buffer().size)
        assertNull(AdaptiveLearningEngine.transferModel8527().terminalEconomics)
        val second = terminal("new-terminal-while-dirty").copy(positionId = "second-position8527")
        assertFalse(AdaptiveLearningEngine.onCanonicalTerminal8527(second))
        assertEquals(pendingWeights, AdaptiveLearningEngine.getDetailedWeights())
        assertEquals(1, buffer().size)
        saveAllowed = true
        assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(terminal()))
        assertEquals(pendingWeights, AdaptiveLearningEngine.getDetailedWeights())
        assertEquals(1, AdaptiveLearningEngine.getTradeCount())
        assertEquals(1, buffer().size)
        assertEquals(1, AdaptiveLearningEngine.observedFeatureCounts8527().getValue("holdTime"))
        assertEquals(0, AdaptiveLearningEngine.observedFeatureCounts8527().getValue("age"))
        assertEquals(1, AdaptiveLearningEngine.transferModel8527().terminalEconomics!!.samples)
        assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(second))
        assertEquals(2, AdaptiveLearningEngine.getTradeCount())
        assertEquals(2, buffer().size)
        assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(second))
        assertEquals(2, AdaptiveLearningEngine.getTradeCount())
    }

    @Test fun modeProofEligibilityAndDomainCannotBeWaivedByAnOtherwiseFiniteOutcome() {
        for (env in listOf(
            terminal().copy(mode = "PAPER", proofState = "PAPER_SIMULATED"),
            terminal().copy(mode = "SHADOW"), terminal().copy(proofState = "LIVE_UNVERIFIED"),
            terminal().copy(learningEligible = false), terminal().copy(assetClassTag = "FOREX"),
            terminal().copy(realizedPnlSol = 0.08, realizedReturnPct = -8.0),
            terminal().copy(realizedPnlSol = 0.0), terminal().copy(realizedReturnPct = 0.0),
            terminal().copy(exitReason = "DEAD_TOKEN_NO_PRICE_EXIT")
        )) assertFalse(AdaptiveLearningEngine.onCanonicalTerminal8527(env))
        assertEquals(0, AdaptiveLearningEngine.getTradeCount())
        assertTrue(buffer().isEmpty())
    }

    @Test fun thrownCommitRollsBackWeightsBufferAndReceiptBeforeRetry() {
        val before = AdaptiveLearningEngine.getDetailedWeights()
        commitThrows = true
        assertTrue(runCatching { AdaptiveLearningEngine.onCanonicalTerminal8527(terminal()) }.isFailure)
        assertEquals(before, AdaptiveLearningEngine.getDetailedWeights())
        assertEquals(0, AdaptiveLearningEngine.getTradeCount())
        assertTrue(buffer().isEmpty())
        commitThrows = false
        assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(terminal()))
        assertEquals(1, AdaptiveLearningEngine.getTradeCount())
        assertEquals(1, buffer().size)
    }

    @Test fun defaultZeroDurationIsMissingUntilOriginalEntryIdentityProvesIt() {
        val legacy = terminal().copy(positionId = "legacy-duration8527", holdingTimeMs = 0L)
        assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(legacy))
        assertFalse("holdTime" in buffer().last().observedFeatures8527)

        val env = terminal().copy(positionId = "known-zero-duration8527", holdingTimeMs = 0L)
        val raw = com.lifecyclebot.engine.truth.AdaptiveEntryWitness8527.encode(env.mint, env.atMs - 1L, mapOf("mcap" to 50_000.0))
        val snap = com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.Snapshot(
            positionId = env.positionId, mint = env.mint, entryLane = env.lane,
            entryStrategyPid = "strategy", entryTactic = "HOLD", entryRiskProfile = "R", entryExitProfile = "E",
            entrySource = "DEX", entryScore = 70, entryLiquiditySol = 1.0, entryMarketCapUsd = 50_000.0,
            entryTimestampMs = env.atMs, entryThresholdSnapshot = "", mode8090 = "LIVE", adaptiveEntry8527 = raw)
        val cacheField = com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.javaClass.getDeclaredField("snapshots").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val snapshots = cacheField.get(com.lifecyclebot.engine.truth.EntryStrategySnapshot6450) as MutableMap<String, com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.Snapshot>
        val previous = snapshots.put(env.positionId, snap)
        val wrongSlot = "wrong-position-slot8527"
        val wrongPrevious = snapshots.put(wrongSlot, snap)
        try {
            assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(env))
            assertTrue("holdTime" in buffer().last().observedFeatures8527)
            assertEquals(0.0, buffer().last().holdTimeMins, 0.0)
            assertTrue("mcap" in buffer().last().observedFeatures8527)
            assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(env.copy(positionId = wrongSlot)))
            assertFalse("mcap" in buffer().last().observedFeatures8527)
            assertFalse("holdTime" in buffer().last().observedFeatures8527)
        } finally {
            if (previous == null) snapshots.remove(env.positionId) else snapshots[env.positionId] = previous
            if (wrongPrevious == null) snapshots.remove(wrongSlot) else snapshots[wrongSlot] = wrongPrevious
        }
    }

    @Test fun legacyWeightsAndGlobalCountsCannotReactivateIncompatibleModels() {
        val before = AdaptiveLearningEngine.getDetailedWeights()
        AdaptiveLearningEngine.applyCommunityWeights(mapOf("mcap" to 2.5), Int.MAX_VALUE)
        AdaptiveLearningEngine.applyHiveGenomeNudge(mapOf("mcap" to 2.5), 100.0, 25, Int.MAX_VALUE, 0.0, 0)
        assertEquals(before, AdaptiveLearningEngine.getDetailedWeights())
        assertEquals(0, AdaptiveLearningEngine.getTradeCount())
        assertTrue(AdaptiveLearningEngine.transferModel8527().weights.isEmpty())
    }

    @Test fun compatibleImportsUsePerFeatureSupportWithoutBorrowingModelMaturity() {
        val before = AdaptiveLearningEngine.getDetailedWeights()
        val compatible = AdaptiveWeightTransfer8527.CompatibleModel(mapOf(
            "mcap" to AdaptiveWeightTransfer8527.FeatureEvidence(2.4, 100, 1),
            "age" to AdaptiveWeightTransfer8527.FeatureEvidence(2.4, 24, 1),
        ))
        AdaptiveLearningEngine.applyCommunityWeights(emptyMap(), 0, compatible)
        val afterCommunity = AdaptiveLearningEngine.getDetailedWeights()
        assertTrue(afterCommunity.getValue("mcap") > before.getValue("mcap"))
        assertEquals(before.getValue("age"), afterCommunity.getValue("age"), 0.0)
        AdaptiveLearningEngine.applyHiveGenomeNudge(emptyMap(), 0.0, 0, 0, 0.0, Int.MAX_VALUE, compatible)
        val afterHive = AdaptiveLearningEngine.getDetailedWeights()
        assertTrue(afterHive.getValue("mcap") > afterCommunity.getValue("mcap"))
        assertEquals(before.getValue("age"), afterHive.getValue("age"), 0.0)
        assertEquals(0, AdaptiveLearningEngine.getTradeCount())
        assertTrue(AdaptiveLearningEngine.transferModel8527().weights.isEmpty())
    }


    @Test fun settledCurrencyStatsCannotBeInferredFromLargeReturnPercentages() {
        val winner = terminal("currency-win").copy(positionId = "currency-win", realizedPnlSol = 0.01, realizedReturnPct = 100.0)
        val loser = terminal("currency-loss").copy(positionId = "currency-loss", realizedPnlSol = -0.02, realizedReturnPct = -1.0)
        assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(winner))
        assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(loser))
        val economics = AdaptiveLearningEngine.transferModel8527().terminalEconomics!!
        assertEquals(2, economics.samples)
        assertEquals(0.01, economics.winningPnlSol, 1e-12)
        assertEquals(0.02, economics.losingPnlSolAbs, 1e-12)
        assertEquals(-0.01, economics.netPnlSol, 1e-12)
        AdaptiveLearningEngine.javaClass.getDeclaredMethod("loadState").apply { isAccessible = true }.invoke(AdaptiveLearningEngine)
        assertEquals(economics, AdaptiveLearningEngine.transferModel8527().terminalEconomics)
    }

    @Test fun absentCurrencyHistoryCannotLendItsMaturityToTwoNewProfitableRows() {
        assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(terminal()))
        val rows = JSONArray(disk.getValue("feature_buffer_observed_8527") as String)
        rows.getJSONObject(0).remove("realizedPnlSol8527")
        disk["feature_buffer_observed_8527"] = rows.toString()
        disk["terminal_count_observed_8527"] = 100
        AdaptiveLearningEngine.javaClass.getDeclaredMethod("loadState").apply { isAccessible = true }.invoke(AdaptiveLearningEngine)
        assertEquals(100, AdaptiveLearningEngine.getTradeCount())
        assertNull(AdaptiveLearningEngine.transferModel8527().terminalEconomics)
        repeat(2) { index ->
            val env = terminal("new-currency-$index").copy(positionId = "new-currency-$index",
                realizedPnlSol = 0.10, realizedReturnPct = 10.0)
            assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(env))
        }
        val model = AdaptiveLearningEngine.transferModel8527()
        assertEquals(102, model.trainedPositions)
        assertEquals(2, model.terminalEconomics!!.samples)
        assertEquals(0.20, model.terminalEconomics!!.netPnlSol, 1e-12)
        assertFalse(AdaptiveWeightTransfer8527.hiveEligible8527(model))
    }

    @Test fun failedNewCheckpointCannotExportNewCurrencyStatsOverLastCommittedModel() {
        assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(terminal()))
        val before = AdaptiveLearningEngine.transferModel8527()
        val next = terminal("pending-currency").copy(positionId = "pending-currency",
            realizedPnlSol = 0.10, realizedReturnPct = 10.0)
        saveAllowed = false
        assertFalse(AdaptiveLearningEngine.onCanonicalTerminal8527(next))
        assertEquals(before, AdaptiveLearningEngine.transferModel8527())
        saveAllowed = true
        assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(next))
        val saved = AdaptiveLearningEngine.transferModel8527()
        assertEquals(2, saved.terminalEconomics!!.samples)
        assertEquals(0.02, saved.terminalEconomics!!.netPnlSol, 1e-12)
        assertEquals(2, saved.trainedPositions)
    }


    @Test fun receiptCapNeverEvictsCurrentUncheckpointedPositionEvenAfterClockRollback() {
        @Suppress("UNCHECKED_CAST")
        val receipts = field("aleSeenKeys").get(AdaptiveLearningEngine) as MutableMap<String, Long>
        repeat(2_000) { receipts["ALE8527|LIVE|archived-$it"] = Long.MAX_VALUE - it }
        saveAllowed = false
        assertFalse(AdaptiveLearningEngine.onCanonicalTerminal8527(terminal()))
        assertTrue(receipts.containsKey("ALE8527|LIVE|position8527"))
        assertEquals(2_000, receipts.size)
        val pendingKeys = receipts.keys.toSet()
        val pendingWeights = AdaptiveLearningEngine.getDetailedWeights()
        val other = terminal("new-cap-terminal").copy(positionId = "new-cap-position")
        assertFalse(AdaptiveLearningEngine.onCanonicalTerminal8527(other))
        assertEquals(pendingKeys, receipts.keys.toSet())
        assertEquals(pendingWeights, AdaptiveLearningEngine.getDetailedWeights())
        assertEquals(1, buffer().size)
        saveAllowed = true
        assertTrue(AdaptiveLearningEngine.onCanonicalTerminal8527(terminal()))
        assertEquals(1, AdaptiveLearningEngine.getTradeCount())
        assertEquals(1, buffer().size)
    }

}
