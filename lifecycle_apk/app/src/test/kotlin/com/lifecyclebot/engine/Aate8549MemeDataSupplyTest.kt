package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LaneCheatSheet8323
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.HeliusSwapCandles7819
import com.lifecyclebot.engine.truth.PriceAge8549
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8549 — owner: "fix all of the data supply issues on the meme trader, properly". The 8542 data audit traced the big
 * "missing data" counts to our own code, not the providers:
 *  1. crypto-desk identities ("solana|<mint>") ran every Solana reader and every meme lane (the same 1,807 counted as
 *     MOONSHOT / MANIPULATED mcap-unhydrated, 1,608 as sniper birth-hydrating);
 *  2. SHITCOIN's 72 h age ceiling could never be decided — the birth walk proves to 6 h (2,291 age-unobserved defers);
 *  3. the DexScreener pair was fetched only when a decision first needed it (bp / chg missing 15k, NOT_FETCHED 13k);
 *  4. a cached pair (45 s / 135 s) was stamped "now", and LIVE accepted a meme price 5 minutes old;
 *  5. the Helius swap-candle "errors" (147) were our own budget's synthetic 429s;
 *  6. Jupiter price ran on the keyless host (sr 63 %) with the app's key unused.
 */
class Aate8549MemeDataSupplyTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun cryptoDeskIdentitiesNeverRunSolanaReadersOrLaunchLanes() {
        assertTrue(SpecialistBrainBridge7542.cryptoDeskIdentity8549("solana|7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU", ""))
        assertTrue(SpecialistBrainBridge7542.cryptoDeskIdentity8549("So11111111111111111111111111111111111111112", NativeDecisionEnrollment8372.CRYPTO_DESK_PRICE_SOURCE))
        assertFalse(SpecialistBrainBridge7542.cryptoDeskIdentity8549("So11111111111111111111111111111111111111112", "DEXSCREENER_PAIR_POLL"))
        val all = setOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "CORE")
        assertEquals(setOf("SHITCOIN", "MOONSHOT", "EXPRESS", "CORE"), SpecialistBrainBridge7542.cryptoDeskScope8549(true, all))
        assertEquals(all, SpecialistBrainBridge7542.cryptoDeskScope8549(false, all))
        assertFalse(SpecialistBrainBridge7542.CRYPTO_DESK_SCOPE_8549.contains("PROJECT_SNIPER"))
        val b = src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(b.contains("if (!cryptoDesk8549) try { com.lifecyclebot.engine.truth.DiscoveryCapture8400.captureMcap8400(ts, \"BRIDGE\") }"))
        assertTrue(b.contains("if (!cryptoDesk8549) try { com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts) }"))
        assertTrue(src("engine/DecisionReasons8031.kt").contains("if (!NativeDecisionEnrollment8372.isCryptoDesk(ts.lastPriceSource)) try { SpecialistOwnership7951.notePreIntent7951(o.lane, reason) }"))
    }

    @Test fun shitcoinsAgeCeilingIsDecidable() {
        assertEquals(360.0, LaneCheatSheet8323.shitcoinMaxAgeMin8549(72.0 * 60.0, 2_880.0, 360.0), 0.0)
        assertEquals(360.0, LaneCheatSheet8323.shitcoinMaxAgeMin8549(72.0 * 60.0, 360.0, 360.0), 0.0)
        assertEquals("unknown window: the provable age still bounds it", 360.0, LaneCheatSheet8323.shitcoinMaxAgeMin8549(4_320.0, Double.NaN, 360.0), 0.0)
        assertTrue(LaneCheatSheet8323.ageBandMin8323("SHITCOIN").second <= com.lifecyclebot.engine.truth.TokenBirthHydrator7441.BIRTH_PROVABLE_MINUTES_8549)
    }

    @Test fun thePairIsAskedAtIntake() {
        val d = src("engine/truth/DiscoveryCapture8400.kt").substringAfter("fun captureAtIntake8400(").substringBefore("// ── V5.0.8407")
        assertTrue(d.contains("com.lifecyclebot.engine.DexBatchLane8287.enqueue8287(ts.mint)"))
        assertTrue(d.indexOf("enqueue8287(ts.mint)") < d.indexOf("LiquidityTruth8037.hydrate8037(ts)"))
    }

    @Test fun aPriceIsAsOldAsItsFetchAndLiveNeedsAFreshOne() {
        assertEquals(100_000L - 45_000L, PriceAge8549.pairEvidenceAt8549(100_000L, 45_000L))
        assertEquals("unknown cache age: now (never older)", 100_000L, PriceAge8549.pairEvidenceAt8549(100_000L, Long.MAX_VALUE))
        assertFalse(CandidateAdmission8136.priceStale8136(20_000L, hotLaunch = false, live = true))
        assertTrue(CandidateAdmission8136.priceStale8136(45_000L, hotLaunch = false, live = true))
        assertTrue(CandidateAdmission8136.priceStale8136(20_000L, hotLaunch = true, live = true))
        assertFalse("paper keeps its bars", CandidateAdmission8136.priceStale8136(90_000L, hotLaunch = false, live = false))
        assertEquals(0, PriceAge8549.bucket8549(3_000L)); assertEquals(2, PriceAge8549.bucket8549(25_000L))
        assertEquals(5, PriceAge8549.bucket8549(Long.MAX_VALUE)); assertEquals(5, PriceAge8549.bucket8549(-1L))
        assertTrue(src("engine/BotService.kt").contains("PriceAge8549.pairEvidenceAt8549(nowMs6575, try { dex.pairAgeMs7884(mint) }"))
        assertTrue(PriceAge8549.statusLine8549().startsWith("decisionPriceAge8549["))
    }

    @Test fun heliusBudgetAnswersAreSkipsAndNearDecisionHasABoundedLease() {
        val h = src("engine/truth/HeliusSwapCandles7819.kt")
        assertTrue(h.contains("resp.header(com.lifecyclebot.network.HostCircuitInterceptor.SYNTHETIC_HEADER_6969) != null"))
        assertTrue(h.contains("HELIUS_SWAP_CANDLES_LOCAL_BUDGET_"))
        assertTrue(HeliusSwapCandles7819.nearLeaseAvailable8549(HeliusSwapCandles7819.NEAR_LEASES_PER_HOUR_8549 - 1))
        assertFalse(HeliusSwapCandles7819.nearLeaseAvailable8549(HeliusSwapCandles7819.NEAR_LEASES_PER_HOUR_8549))
        assertTrue("a near-decision lease is one page", h.contains("runJob(ts, marketAddress, key, credits, if (nearLease8549) 1 else MAX_PAGES)"))
    }

    @Test fun jupiterPriceUsesTheKeyedHostFirst() {
        val k = src("network/KeylessPriceSources6996.kt")
        assertTrue(k.indexOf("https://api.jup.ag/price/v3?ids=") in 0 until k.indexOf("https://lite-api.jup.ag/price/v3?ids="))
        assertTrue(k.contains(".also { if (apiKey.isNotBlank()) it.header(\"x-api-key\", apiKey) }"))
    }
}
