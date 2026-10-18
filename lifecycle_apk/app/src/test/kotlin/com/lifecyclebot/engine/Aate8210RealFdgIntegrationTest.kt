package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.data.*
import com.lifecyclebot.engine.truth.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.mockito.Mockito

/** Runs the production FDG on simulated observed inputs. No private key, signer, network send or pre-approved FDG. */
class Aate8210RealFdgIntegrationTest {
    @After fun restoreMode() { RuntimeModeAuthority.publishRuntimeStart(true, false) }

    @Suppress("UNCHECKED_CAST")
    private fun fixture(): TokenState {
        val now = System.currentTimeMillis()
        RuntimeModeAuthority.publishRuntimeStart(false, true)
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        val manager = WalletManager.getInstance(context)
        val state = WalletManager::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(manager) as MutableStateFlow<WalletState>
        state.value = WalletState(connectionState = WalletConnectionState.CONNECTED, solBalance = 0.3758,
            solPriceUsd = 104.08, lastRefreshed = now)
        WalletManager.noteObservedSolPrice7042(104.08, now)
        LiveBootRecovery8074.noteTokenRead8074(true, now)
        val ts = TokenState(mint = "11111111111111111111111111111113", symbol = "FIXTURE8210", source = "RAYDIUM_NEW_POOL",
            pairAddress = "11111111111111111111111111111114", phase = "expansion", signal = "BUY", entryScore = 90.0,
            lastV3Score = 90, lastV3Confidence = 90, addedToWatchlistAt = now - 3_600_000L,
            lastPrice = 0.0001, lastPriceUpdate = now, lastPriceSource = "DEXSCREENER_POLL", lastPriceDex = "RAYDIUM",
            lastMcap = 100_000.0, lastLiquidityUsd = 50_000.0, lastPriceChange1h = 18.0, lastPriceChange5m = 3.0,
            lastBuyPressurePct = 70.0, lastSellPressurePct = 30.0, holderDataResolved = true, peakHolderCount = 300,
            lastSafetyCheck = now, safety = SafetyReport(tier = SafetyTier.SAFE, rugcheckScore = 90,
                mintAuthorityDisabled = true, freezeAuthorityDisabled = true, lpLockPct = 100.0,
                topHolderPct = 5.0, tokenAgeMinutes = 60.0, checkedAt = now, rugcheckStatus = "PASS", liqConfirmed = true),
            meta = StrategyMeta(pressScore = 70.0, volScore = 80.0, momScore = 75.0, setupQuality = "A", rsi = 55.0))
        ts.buyPressureObservedAtMs8084 = now
        ts.priceChangeObservedAtMs8084 = now
        ts.tokenMap.apply { canonicalTargetMint = ts.mint; decimals = 6; poolAddress = ts.pairAddress;
            dexId = "raydium"; venue = "RAYDIUM"; dexRouteOk = true; routeStatus = "READY";
            liquidityUsd = 50_000.0; marketCap = 100_000.0; priceUsd = ts.lastPrice;
            priceObservedAtMs7858 = now; hydrationComplete = true; updatedAtMs = now }
        repeat(30) { i ->
            val price = 0.000085 + i * 0.0000005
            ts.history.addLast(Candle(now - (30-i)*60_000L, price, price*1_000_000_000.0,
                20_000.0, 100_000.0, 70, 30, 700, 300, 300, price*1.01, price*0.99, price*0.999))
        }
        BotService.status.tokens[ts.mint] = ts
        val cells = ForwardReturnLabeler7731::class.java.getDeclaredField("cells").apply { isAccessible = true }
            .get(ForwardReturnLabeler7731) as MutableMap<String, ForwardReturnLabeler7731.Tally>
        val age = ForwardReturnLabeler7731.cellAgeMs8208(ts, now)
        val key = ForwardReturnLabeler7731.cellKey(ts.source, "SHITCOIN", ts.lastMcap, age)
        cells[key] = ForwardReturnLabeler7731.Tally().apply {
            n60 = 80; sum60 = 1600.0; sumSq60 = 32_080.0; win60 = 80; runner60 = 16
        }
        return ts
    }

    @Test fun supportedSafeBuyMustProduceRealExecutableFdgVerdict() {
        val ts = fixture()
        val c = CandidateDecision(90.0, 0.0, "expansion", "BUY", "A", "A", "A", "EXPANSION",
            90.0, true, false, true, "BUY", "", 1.0, 90.0, ts.meta)
        val admission = CandidateAdmission8136.assess8136(ts, "SHITCOIN", genericScore = 90.0,
            genericFloor = 15.0, proposedSizeSol = 0.04)
        println("REAL_ADMISSION: ${admission?.compact}")
        val d = FinalDecisionGate.evaluate(ts, c, BotConfig(paperMode = false, autoTrade = true),
            0.04, tradingModeTag = ModeSpecificGates.TradingModeTag.SHITCOIN, laneScore = 90.0, specialistLane = "SHITCOIN")
        println("REAL_FDG: ${d.summary()}\n${d.gateChecks.joinToString("\n")}")
        assertEquals(FinalDecisionGate.TradeMode.LIVE, d.mode)
        assertTrue("REAL FDG refused: ${d.summary()} ${d.gateChecks.filter { !it.passed }}", d.canExecute())
        assertTrue(d.sizeSol > 0.0)
        assertEquals("SHITCOIN", d.canonicalLane7835)
    }
}
