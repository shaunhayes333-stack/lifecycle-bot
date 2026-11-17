package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401
import com.lifecyclebot.engine.truth.OnChainDepth8038
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 8845 forensic: nine live buy failures called stale or cross-pool pricing a chain collapse. */
class Aate8847TemporalPoolAndLaunchProofTest {
    private val depth = OnChainDepth8038

    @Test fun a90SecondOldChainPriceCannotProveTodaysRunnerCollapsed() {
        val now = 1_000_000L
        val stale = depth.priceProof8847(now - 1_000L, now - 85_000L, now, "POOL_A", "POOL_A")
        assertEquals(OnChainDepth8038.PriceProof8847.STALE_OR_UNKNOWN, stale)
        assertTrue("the raw magnitude alone still looks like a drop, but is not a temporal proof",
            depth.priceCollapsed8038(7.5e-6, 4.12e-6))
        assertEquals(OnChainDepth8038.PriceProof8847.DIFFERENT_POOL,
            depth.priceProof8847(now - 200L, now - 100L, now, "POOL_A", "POOL_B"))
        assertEquals(OnChainDepth8038.PriceProof8847.STALE_OR_UNKNOWN,
            depth.priceProof8847(0L, now - 100L, now, "", "POOL_A"))
        assertEquals(OnChainDepth8038.PriceProof8847.STALE_OR_UNKNOWN,
            depth.priceProof8847(now - 100L, now + 50L, now, "", "POOL_A"))
    }

    @Test fun alignedSamePoolPriceMayStillRefuseARealCollapse() {
        val now = 2_000_000L
        assertEquals(OnChainDepth8038.PriceProof8847.ALIGNED,
            depth.priceProof8847(now - 300L, now - 100L, now, "POOL_A", "POOL_A"))
        assertTrue(depth.priceCollapsed8038(7.5e-6, 4.12e-6))
        assertFalse(depth.priceCollapsed8038(7.5e-6, 7.25e-6))
    }

    @Test fun misalignedPriceIsASealedTicketDataWaitNotATerminalStop() {
        val reason = OnChainDepth8038.PRICE_CHAIN_REPRICE_PENDING_8847
        val preflight = depth.classifyPreflight8385("mint", reason, 3_000_000L)
        assertTrue(preflight is OnChainDepth8038.Preflight8385.DataDefer)
        assertEquals(reason, TicketRetry8327.transientCause8327("LIVE_BUY_NOT_OPENED_7948:$reason"))
        assertNull(depth.reserveCause8385(reason)) // never incorrectly request curve reserves for a pool reprice
        assertTrue(depth.classifyPreflight8385("mint", "PRICE_COLLAPSED_ONCHAIN_8038", 3_000_000L) is
            OnChainDepth8038.Preflight8385.HardRefusal)
        val executor = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(executor.contains("if (reserveWait8385) TicketRetry8327.noteReserveWait8385"))
        assertTrue(executor.contains("else com.lifecyclebot.engine.truth.OnChainDepth8038.request8038(ts,"))
    }

    @Test fun witnessedBirthGetsRealLaunchPhaseNotHydrating() {
        val now = 4_000_000L
        val (age, known) = LaunchPhaseAuthority7401.birthRead8535(null, 40_000L, now)
        assertEquals(40_000L, age)
        assertTrue(known)
        assertFalse(LaunchPhaseAuthority7401.birthHydrating8847(known))
        assertTrue(LaunchPhaseAuthority7401.birthHydrating8847(
            LaunchPhaseAuthority7401.birthRead8535(null, null, now).second))
        val launch = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaunchPhaseAuthority7401.kt").readText()
        assertTrue(launch.contains("birthHydrating8847(birthKnown8535) -> Phase.METADATA_HYDRATING"))
        assertTrue(launch.contains("if (birthKnown8535) age else null, flow, multiple, phase"))
    }

    @Test fun negativeDecisionIsReassessedOnlyOnFreshMaterialMovementOrSignedNewDemand() {
        val now = 8_000_000L
        fun due(price: Double, markAt: Long = now, liq: Double = 3_000.0, mcap: Double = 30_000.0,
                fiveMin: Double = 0.0, anchor: Double? = 1.0, buyers: Int? = null, share: Double? = null,
                net: Double? = null, previous: Long = 0L): Boolean = MovingTokenRecheck8847.due8847(
                    price, markAt, liq, mcap, fiveMin, anchor, buyers, share, net, previous, now)
        assertTrue("a coin up 6 % since its previous scored observation needs a fresh decision", due(1.06))
        assertTrue("a fresh independent 5m climb is enough to fetch and grade demand", due(1.0, fiveMin = 9.0))
        assertTrue("signed new wallets should wake a flat pre-ignition coin before the candle forms",
            due(1.0, buyers = 3, share = 80.0, net = 1.2))
        assertFalse("a declining coin without new independent demand is not force-bought", due(0.8, fiveMin = -20.0))
        assertFalse("a stale chart is not a new observation", due(1.2, markAt = now - 40_000L))
        assertFalse("unexitable dust must not take scarce subscription priority", due(1.5, liq = 60.0))
        assertFalse("old priority cannot be abused to re-evaluate on every tick", due(1.5, previous = now - 1_000L))
        assertFalse("buyer counts without net SOL demand prove nothing", due(1.0, buyers = 3, share = 70.0, net = -0.4))
    }

    @Test fun movingCoinHasARealPathBackToTheSupervisorWithoutFdgBypass() {
        val admission = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        val scheduler = File("src/main/kotlin/com/lifecyclebot/engine/HunterWindow8293.kt").readText()
        val source = File("src/main/kotlin/com/lifecyclebot/engine/MovingTokenRecheck8847.kt").readText()
        val promote = admission.indexOf("MovingTokenRecheck8847.onEvidence8847")
        val earlyReturn = admission.indexOf("if (held8144.isEmpty() && !namedResumed8397) return")
        assertTrue("terminal model/FDG declines can still enter moving priority", promote >= 0 && promote < earlyReturn)
        assertTrue(scheduler.contains("MovingTokenRecheck8847.targets8847(all, nowMs)"))
        assertTrue(scheduler.contains("MovingTokenRecheck8847.targets8847(otherMints, nowMs)"))
        assertTrue(source.contains("!BannedTokens.isBanned(mint)"))
        assertFalse(source.contains("ExecutionDecisionSnapshot6510.seal"))
    }

    @Test fun probationPriceWatchHydratesItsBaselineThenObservesANewMove() {
        val registry = GlobalTradeRegistry
        val entry = GlobalTradeRegistry.ProbationEntry(
            mint = "TEST_MINT", symbol = "TEST", addedAt = 500L, addedBy = "TEST", source = "TEST",
            initialMcap = 5_000.0, initialLiquidity = 1_200.0, initialConfidence = 20,
            isEstimatedLiquidity = false, isSingleSource = true,
        )
        val now = 1_000_000L
        assertFalse("restored or unknown price is not probation promotion evidence",
            registry.acceptProbationPrice8847(entry, 0.001, 0L, now))
        assertTrue(registry.acceptProbationPrice8847(entry, 0.001, now - 3_000L, now))
        assertEquals("first genuine observation becomes the baseline", 0.001, entry.priceAtAdd, 1e-12)
        assertEquals(0.001, entry.currentPrice, 1e-12)
        assertTrue(registry.acceptProbationPrice8847(entry, 0.00108, now - 1_000L, now))
        assertEquals(0.00108, entry.currentPrice, 1e-12)
        assertTrue("5% fresh observed move may promote from probation before a 30s timer",
            registry.probationReadyForReview8847(entry.copy(addedAt = now - 5_000L), now))
        assertFalse("late/older provider replies must not un-promote a moved mint",
            registry.acceptProbationPrice8847(entry, 0.0003, now - 2_000L, now))
        assertFalse(registry.acceptProbationPrice8847(entry, 0.5, now - 30_000L, now))
        assertEquals(0.00108, entry.currentPrice, 1e-12)
        val tokenFacts = File("src/main/kotlin/com/lifecyclebot/engine/truth/TokenFacts.kt").readText()
        assertTrue("canonical accepted price writes must actually update probation",
            tokenFacts.contains("GlobalTradeRegistry.updateProbationPrice(ts.mint, usd, offered.observedAtMs, nowMs)"))
    }
}
