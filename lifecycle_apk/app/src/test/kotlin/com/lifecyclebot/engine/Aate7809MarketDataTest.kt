package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.LocalCandleSynthesis7055
import com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.7809 MarketData — OHLCV starvation (TOO_FEW_BARS), SCORE_TOO_LOW evidence,
 * on-chain supply resolution, pump-curve RPC rotation, provider health routing.
 */
class Aate7809MarketDataTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private val now = 1_800_000_000_000L

    // ── item 3: local bars are no longer silenced by a provider snapshot ──

    @Test fun dexScreenerSnapshotDoesNotSilenceLocalBars() {
        // pair.candle: priceUsd + 24h volume, no open/high/low.
        val snapshot = Candle(ts = now, priceUsd = 1.0, marketCap = 0.0, volumeH1 = 10.0, volume24h = 50_000.0)
        assertFalse(LocalCandleSynthesis7055.fetchedKlineCovers7809(snapshot, now))
    }

    @Test fun freshFetchedKlineStillOwnsTheMinute() {
        val kline = Candle(ts = now - 30_000L, priceUsd = 1.0, marketCap = 0.0, volumeH1 = 0.0, volume24h = 900.0,
            highUsd = 1.1, lowUsd = 0.9, openUsd = 0.95)
        assertTrue(LocalCandleSynthesis7055.fetchedKlineCovers7809(kline, now))
    }

    @Test fun oldSeededKlineAndSyntheticBarsDoNotSilenceLocalBars() {
        val old = Candle(ts = now - 10 * 60_000L, priceUsd = 1.0, marketCap = 0.0, volumeH1 = 0.0, volume24h = 900.0,
            highUsd = 1.1, lowUsd = 0.9, openUsd = 0.95)
        assertFalse(LocalCandleSynthesis7055.fetchedKlineCovers7809(old, now))
        val synth = Candle(ts = now, priceUsd = 1.0, marketCap = 0.0, volumeH1 = 5.0, volume24h = 0.0,
            highUsd = 1.1, lowUsd = 0.9, openUsd = 0.95, synthetic = true)
        assertFalse(LocalCandleSynthesis7055.fetchedKlineCovers7809(synth, now))
        assertFalse(LocalCandleSynthesis7055.fetchedKlineCovers7809(null, now))
    }

    @Test fun candidateWithSnapshotHistoryBinsObservedTicks() {
        LocalCandleSynthesis7055.resetForTest()
        val ts = TokenState(mint = "So1aTestMint7809xxxxxxxxxxxxxxxxxxxxxxxxx")
        ts.history.addLast(Candle(ts = System.currentTimeMillis(), priceUsd = 1.0, marketCap = 0.0, volumeH1 = 3.0, volume24h = 75_000.0))
        LocalCandleSynthesis7055.note(ts, 1.01, 0.0)
        LocalCandleSynthesis7055.note(ts, 1.02, 0.0)
        val line = LocalCandleSynthesis7055.statusLine7055()
        assertTrue(line, line.contains("ticksBinned=2"))
        assertTrue(line, line.contains("deferredToFetched=0"))
        LocalCandleSynthesis7055.resetForTest()
    }

    @Test fun tradePlanBarRequirementIsUnchanged() {
        val plan = src("engine/truth/TradePlan7739.kt")
        assertTrue(plan.contains("private const val MIN_BARS_7739 = 5"))
    }

    @Test fun seedWaitsForTheProviderSlotWithBoundedRetries() {
        val d = src("engine/DataOrchestrator.kt")
        assertTrue(d.contains("keylessFetch7809(mint, \"1m\", 120, poolHint6916, attempts = 3)"))
        assertTrue(d.contains("attempts = if (k1mOk7809) 1 else 0"))
        assertTrue(d.contains("providerSlotWait7809()"))
        assertTrue(d.contains("OHLCV_SEED_SKIPPED_PROVIDER_COOLDOWN_7809"))
        assertTrue(d.contains("SEED_RETRY_FLOOR_MS_7809 = 2_600L"))
        val f = src("network/SolanaOhlcvFeed6916.kt")
        assertTrue(f.contains("SAME_KEY_COALESCE_MS_7484 = 2_500L"))
        assertTrue(f.contains("fun providerSlotWait7809("))
    }

    // ── item 15: fresh-launch evidence reaches UNIFIED; unknowns are not negative ──

    @Test fun unifiedScoreCarriesLaunchTimingEvidence() {
        val s = src("v3/scoring/UnifiedScorer.kt")
        val unified = s.substringAfter("private fun unifiedScore(").substringBefore("private fun launchTimingComponent7809(")
        assertTrue(unified.contains("launchTimingComponent7809(candidate)"))
        val classic = s.substringAfter("private fun classicScore(").substringBefore("private fun modernScore(")
        assertTrue(classic.contains("launchTimingComponent7809(candidate)"))
        assertTrue(s.contains("POST_PUMP_FADE — launch impulse already spent"))
    }

    @Test fun unreadLiquidityAndHolderCountAreNotNegativeVotes() {
        val m = src("v3/scoring/ScoringModules.kt")
        assertTrue(m.contains("candidate.liquidityUsd <= 0.0 -> { reasons += \"Liquidity NO_DATA\" }"))
        val holders = m.substringAfter("class HolderSafetyAI").substringBefore("class NarrativeAI")
        assertFalse(holders.contains("score -= 2  // Was -10, now -2"))
        assertTrue(holders.contains("topHolder >= 20 -> { score -= 12"))
    }

    // ── item 16: supply resolver ──

    @Test fun rpcErrorsAreClassifiedNotAllNoSupply() {
        assertEquals("RATE_LIMITED", OnChainSupplyAuthority7075.rpcErrorClass7809(-32429, "rate limited"))
        assertEquals("RATE_LIMITED", OnChainSupplyAuthority7075.rpcErrorClass7809(-32005, ""))
        assertEquals("NOT_FOUND_YET", OnChainSupplyAuthority7075.rpcErrorClass7809(-32602, "Invalid param: could not find account"))
        assertEquals("NO_SUPPLY", OnChainSupplyAuthority7075.rpcErrorClass7809(-32602, "Invalid param: not a Token mint"))
        assertEquals("TRANSPORT", OnChainSupplyAuthority7075.rpcErrorClass7809(-32603, "Internal error"))
        assertEquals("TRANSPORT", OnChainSupplyAuthority7075.rpcErrorClass7809(-32004, "Block not available for slot"))
    }

    @Test fun supplyReadUsesConfirmedCommitmentAndTheRpcLadder() {
        val s = src("engine/truth/OnChainSupplyAuthority7075.kt")
        assertTrue(s.contains("{\"commitment\":\"confirmed\"}"))
        assertTrue(s.contains("RuntimeProviderAuthority6685.rpcCandidates(url).take(SUPPLY_RUNGS_7809)"))
        assertTrue(s.contains("NOT_FOUND_RETRY_MS_7809 = 20_000L"))
        assertTrue(s.contains("catch (_: Throwable) { return Outcome7116.TRANSPORT to null }"))
    }

    // ── item 17: pump curve RPC ──

    @Test fun deadPublicCurveRungsAreBenchedAndPassesBounded() {
        val p = src("network/ParallelMarkFanout7088.kt")
        assertTrue(p.contains("noteCurveRungFailure7809(hostLabel)"))
        assertTrue(p.contains("if (hostLabel == \"helius\") return"))
        assertTrue(p.contains("CURVE_MAX_TRANSPORT_FAILS_7809 = 2"))
        assertTrue(p.contains("ApiBackoff.markFailure(hostLabel, 504)"))
    }

    // ── item 18: provider health routing / no synchronous LLM ──

    @Test fun terminalProvidersStayOutOfHistoryAndDiscovery() {
        assertTrue(src("network/BirdeyeApi.kt").substringAfter("private fun get(url: String)").substringBefore("recordCalls(1)")
            .contains("isAuthTerminal"))
        assertTrue(src("engine/DataOrchestrator.kt").contains("BIRDEYE_MTF_REFRESH_SKIPPED_AUTH_DEAD_7809"))
        assertTrue(src("engine/SolanaMarketScanner.kt").contains("BIRDEYE_SCANNER_SKIPPED_AUTH_DEAD_7809"))
        assertTrue(src("engine/HistoricalChartScanner.kt").contains("birdeyeDead7809"))
        assertTrue(src("network/DexscreenerApi.kt").contains("SolanaOhlcvFeed6916.paprikaTerminal7809()"))
    }

    @Test fun narrativeDetectorNeverBlocksTheEntryOnGroq() {
        val n = src("engine/NarrativeDetector.kt")
        val analyze = n.substringAfter("fun analyze(").substringBefore("private val groqInFlight7809")
        assertFalse(analyze.contains("callGroq("))
        assertTrue(analyze.contains("scheduleGroq7809("))
        assertTrue(analyze.contains("return fallbackAnalysis(symbol, name, description)"))
    }
}
