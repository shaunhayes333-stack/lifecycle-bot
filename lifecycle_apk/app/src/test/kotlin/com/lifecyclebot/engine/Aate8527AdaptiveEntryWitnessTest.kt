package com.lifecyclebot.engine

import com.lifecyclebot.data.StrategyMeta
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.AdaptiveEntryWitness8527
import com.lifecyclebot.engine.truth.EntryStrategySnapshot6450
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Aate8527AdaptiveEntryWitnessTest {
    private val now = 1_800_000_000_000L
    private fun snapshot(raw: String = "", at: Long = now + 500L) = EntryStrategySnapshot6450.Snapshot(
        positionId = "witness8527", mint = "EntryWitness8527", entryLane = "MOONSHOT",
        entryStrategyPid = "strategy", entryTactic = "HOLD", entryRiskProfile = "R", entryExitProfile = "E",
        entrySource = "DEX_TRENDING", entryScore = 70, entryLiquiditySol = 1.0, entryMarketCapUsd = 30_000.0,
        entryTimestampMs = at, entryThresholdSnapshot = "scanner=DEX_TRENDING;adaptiveEntry8527=$raw",
        mode8090 = "LIVE", adaptiveEntry8527 = raw,
    )

    @Test fun emptyTokenDefaultsDoNotBecomeMeasuredEntryInputs() {
        val raw = AdaptiveEntryWitness8527.capture(TokenState("EntryWitness8527"), now)
        val values = JSONObject(raw).getJSONObject("values")
        for (key in listOf("mcap", "liquidity", "volume24hUsd", "tokenAge", "buyRatio", "holderCount", "holderGrowth",
            "holderConc", "rugcheck", "devWallet", "emaFan", "bondingCurve", "priceFromAth")) assertFalse(key, values.has(key))
    }

    @Test fun actualVolumePressureAndRugZeroAreNotDefaultsOrProxies() {
        val token = TokenState("EntryWitness8527")
        token.tokenMap.volume24hUsd = 150_000.0
        token.tokenMap.liquidityUsd = 20_000.0
        token.tokenMap.marketCap = 70_000.0
        token.buyPressureObservedAtMs8084 = now - 100L
        token.lastBuyPressurePct = 50.0
        token.topHolderPct = 0.0
        token.safety = SafetyReport(rugcheckScore = 1, checkedAt = now - 1L)
        var values = JSONObject(AdaptiveEntryWitness8527.capture(token, now)).getJSONObject("values")
        assertEquals(150_000.0, values.getDouble("volume24hUsd"), 0.0)
        assertEquals(20_000.0, values.getDouble("liquidity"), 0.0)
        assertEquals(50.0, values.getDouble("buyRatio"), 0.0)
        assertEquals(0.0, values.getDouble("holderConc"), 0.0)
        assertFalse("pending1 is missing", values.has("rugcheck"))
        token.safety = token.safety.copy(rugcheckScore = 0)
        token.tokenMap.migratedOrGraduated = true
        token.meta = StrategyMeta(ema8 = 1.0, emafanAlignment = "BULL_FAN")
        values = JSONObject(AdaptiveEntryWitness8527.capture(token, now)).getJSONObject("values")
        assertEquals(0.0, values.getDouble("rugcheck"), 0.0)
        assertEquals(100.0, values.getDouble("bondingCurve"), 0.0)
        assertEquals("BULL_FAN", AdaptiveEntryWitness8527.emaName8527(values.getDouble("emaFan")))
        assertFalse(values.has("devWallet"))
    }

    @Test fun stalePressureCannotBeStampedAsNewEntryEvidence() {
        val token = TokenState("EntryWitness8527")
        token.lastBuyPressurePct = 72.0
        token.buyPressureObservedAtMs8084 = now - 120_001L
        val values = JSONObject(AdaptiveEntryWitness8527.capture(token, now)).getJSONObject("values")
        assertFalse(values.has("buyRatio"))
    }

    @Test fun frozenEntryIsUnaffectedByCloseTimeMutationAndRequiresExactIdentity() {
        val token = TokenState("EntryWitness8527")
        token.tokenMap.marketCap = 30_000.0
        token.tokenMap.volume24hUsd = 100_000.0
        val raw = AdaptiveEntryWitness8527.capture(token, now)
        val snap = snapshot(raw)
        token.tokenMap.marketCap = 3_000_000.0
        token.tokenMap.volume24hUsd = 1.0
        val values = AdaptiveEntryWitness8527.canonicalFeatures8527(snap, token.mint, TradeEnvironment.LIVE, snap.entryTimestampMs)
        assertEquals(30_000.0, values.getValue("ale8527.mcap"), 0.0)
        assertEquals(100_000.0, values.getValue("ale8527.volume24hUsd"), 0.0)
        assertTrue(AdaptiveEntryWitness8527.canonicalFeatures8527(snap, "OtherMint", TradeEnvironment.LIVE, snap.entryTimestampMs).isEmpty())
        assertTrue(AdaptiveEntryWitness8527.canonicalFeatures8527(snap, token.mint, TradeEnvironment.PAPER, snap.entryTimestampMs).isEmpty())
        assertTrue(AdaptiveEntryWitness8527.canonicalFeatures8527(snap, token.mint, TradeEnvironment.LIVE, snap.entryTimestampMs - 1L).isEmpty())
        assertTrue(AdaptiveEntryWitness8527.canonicalFeatures8527(null, token.mint, TradeEnvironment.LIVE, snap.entryTimestampMs).isEmpty())
    }

    @Test fun postEntryReadsAndLegacySnapshotsCannotInventPreEntryKnowledge() {
        val later = AdaptiveEntryWitness8527.encode("EntryWitness8527", now + 1L, mapOf("mcap" to 20_000.0))
        val snap = snapshot(later, now)
        assertTrue(AdaptiveEntryWitness8527.canonicalFeatures8527(snap, snap.mint, TradeEnvironment.LIVE, now).isEmpty())
        assertTrue(AdaptiveEntryWitness8527.canonicalFeatures8527(snapshot("", now), snap.mint, TradeEnvironment.LIVE, now).isEmpty())
        assertTrue(AdaptiveEntryWitness8527.decodeForEntry8527("{malformed", snap.mint, now).isEmpty())
        val wrong = JSONObject(later).put("schema", 8526).put("at", now).toString()
        assertTrue(AdaptiveEntryWitness8527.decodeForEntry8527(wrong, snap.mint, now).isEmpty())
    }

    @Test fun immutableSnapshotSerializationPreservesOptionalWitnessWithoutLegacyReconstruction() {
        val raw = AdaptiveEntryWitness8527.encode("EntryWitness8527", now, mapOf("mcap" to 20_000.0, "unknown" to Double.NaN))
        assertFalse(raw.contains("NaN"))
        val snap = snapshot(raw)
        val json = EntryStrategySnapshot6450.encodeSnapshot(snap)
        val decoded = EntryStrategySnapshot6450.decodeSnapshot(json.toString(), snap.positionId)
        assertEquals(raw, decoded.adaptiveEntry8527)
        json.remove("adaptiveEntry8527")
        assertEquals("", EntryStrategySnapshot6450.decodeSnapshot(json.toString(), snap.positionId).adaptiveEntry8527)
    }

    @Test fun sealInheritsExistingPreEntryPolicyWitnessWithoutCapturingNewMarketData() {
        val raw = AdaptiveEntryWitness8527.encode("EntryWitness8527", now, mapOf("mcap" to 20_000.0))
        val snap = snapshot(raw).copy(positionId = "policy-witness8527-" + java.util.UUID.randomUUID(), adaptiveEntry8527 = "")
        val field = EntryStrategySnapshot6450.javaClass.getDeclaredField("snapshots").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val snapshots = field.get(EntryStrategySnapshot6450) as MutableMap<String, EntryStrategySnapshot6450.Snapshot>
        val old = snapshots.remove(snap.positionId)
        try {
            EntryStrategySnapshot6450.setEntry(snap)
            assertEquals(raw, snapshots.getValue(snap.positionId).adaptiveEntry8527)
            val changed = snap.copy(entryThresholdSnapshot = "adaptiveEntry8527=" +
                AdaptiveEntryWitness8527.encode(snap.mint, now, mapOf("mcap" to 999_999.0)))
            assertFalse(EntryStrategySnapshot6450.setEntry(changed))
            assertEquals(raw, snapshots.getValue(snap.positionId).adaptiveEntry8527)
        } finally {
            snapshots.remove(snap.positionId)
            if (old != null) snapshots[snap.positionId] = old
        }
    }
}
