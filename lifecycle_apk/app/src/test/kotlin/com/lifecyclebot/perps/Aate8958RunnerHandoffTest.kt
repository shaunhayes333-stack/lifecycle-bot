package com.lifecyclebot.perps

import com.lifecyclebot.engine.truth.LaunchSniper8748
import kotlinx.coroutines.*
import java.util.concurrent.Semaphore
import org.junit.Assert.*
import org.junit.Test

class Aate8958RunnerHandoffTest {
    @Test fun sameTickerDifferentContractStillReachesDecision() {
        val mint = "A".repeat(32)
        val p = CryptoAltTrader.AltPosition(id="test", market=PerpsMarket.DYN, dynSymbol="SOL", dynMint=mint,
            direction=PerpsDirection.LONG, isSpot=true, isPaper=false, canonicalAssetKey="solana|$mint",
            entryPrice=1.0,currentPrice=1.0,sizeSol=0.1,leverage=1.0,takeProfitPrice=2.0,stopLossPrice=0.9,
            aiScore=80,aiConfidence=80,reasons=emptyList())
        fun held(key: String, paper: Boolean=false) = CryptoAltTrader.holdsAsset8958(key,paper,listOf(p))
        assertTrue(held(mint)); assertTrue(held("solana|$mint")); assertFalse(held("solana|"+mint.lowercase()))
        assertFalse(held("bsc|$mint")); assertFalse(held(mint,true)); assertFalse(held("SOL"))
    }
    @Test fun armedRunnerRecoversAReleasedSlotWithoutAddingCapacity() = runBlocking {
        val slots=Semaphore(0)
        val request=async(start=CoroutineStart.UNDISPATCHED) { LaunchSniper8748.acquireFast8958("LAUNCH_RULE_8949",slots){true} }
        assertFalse(LaunchSniper8748.acquireFast8958("OTHER",slots){true}); slots.release()
        assertTrue(request.await()); assertEquals(0,slots.availablePermits())
        val stale=async(start=CoroutineStart.UNDISPATCHED) { LaunchSniper8748.acquireFast8958("LAUNCH_RULE_8949",slots){false} }; slots.release()
        assertFalse(stale.await()); assertEquals(1,slots.availablePermits())
    }
}
