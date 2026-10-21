package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.CortexVoters7885
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandleCoverage8284
import com.lifecyclebot.engine.truth.FeatureFunnel8283
import com.lifecyclebot.engine.truth.FeatureKind8283
import com.lifecyclebot.engine.truth.FeatureRevision8283
import com.lifecyclebot.engine.truth.MemoryBounds8289
import com.lifecyclebot.engine.truth.TradeTape8100
import com.lifecyclebot.network.DexPools8285
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8289 — 8286 reached 511/512 MB in 19 minutes with 448 MB the memory census could not attribute. Every store the
 * 8283-8286 data repair added is driven past its cap here and must stay bounded (hard cap + TTL on write), trim under
 * pressure without touching protected mints, and be registered in the MemoryGuard7977 census.
 */
class Aate8289MemoryBoundsTest {
    private val t0 = 1_800_000_000_000L
    private fun mint(i: Int, tag: String) = "$tag$i".padEnd(44, 'M')

    // ── the shared bound ──

    @Test fun capByAgeHoldsTheCapWhenEveryKeyIsFresh() {
        val m = ConcurrentHashMap<String, Long>()
        for (i in 0 until 20_000) { m["k$i"] = t0; MemoryBounds8289.capByAge8289(m, 1_000, 60_000L, t0) { it } }
        assertTrue("size ${m.size}", m.size <= 1_000)
        // expired entries go first
        m.clear()
        for (i in 0 until 1_500) m["old$i"] = t0 - 120_000L
        m["fresh"] = t0
        MemoryBounds8289.capByAge8289(m, 1_000, 60_000L, t0) { it }
        assertEquals(setOf("fresh"), m.keys)
        // nothing expired: the oldest go, down to 90% of the cap
        m.clear()
        for (i in 0 until 1_200) m["k$i"] = t0 + i
        MemoryBounds8289.capByAge8289(m, 1_000, 60_000L, t0 + 1_200L) { it }
        assertTrue(m.size <= 900 && m.containsKey("k1199") && !m.containsKey("k0"))
        // at or under the cap nothing is touched
        assertEquals(0, MemoryBounds8289.capByAge8289(m, 1_000, 1L, t0 + 999_999L) { it })
    }

    @Test fun internSharesOneInstanceAndStaysBounded() {
        val a = StringBuilder("pool-8289").toString()
        val b = StringBuilder("pool-8289").toString()
        assertSame(MemoryBounds8289.intern8289(a), MemoryBounds8289.intern8289(b))
        for (i in 0 until 10_000) MemoryBounds8289.intern8289("venue$i")
        assertTrue(MemoryBounds8289.internCount8289() <= MemoryBounds8289.INTERN_CAP_8289)
        assertEquals("", MemoryBounds8289.intern8289(""))
    }

    @Test fun trimEldestKeepsTheNewest() {
        val m: MutableMap<String, Int> = java.util.Collections.synchronizedMap(LinkedHashMap())
        for (i in 0 until 100) m["k$i"] = i
        assertEquals(90, MemoryBounds8289.trimEldest8289(m, 10))
        assertEquals(10, m.size)
        assertTrue(m.containsKey("k99") && !m.containsKey("k0"))
    }

    // ── TradeTape8100: tapes, prints per tape, signature window, signed stamps ──

    @Test fun tradeTapeStaysBoundedAndTrimsUnderPressure() {
        val tape = TradeTape8100
        tape.resetForTest8100()
        // 9,000 mints with one signed print each: <= 400 tapes, <= 8,000 per-feed signed stamps
        for (i in 0 until 9_000) tape.note8285(mint(i, "T"), 1e-6, 0.1, 100.0, i % 2 == 0, TradeTape8100.SRC_PUMPPORTAL_8285,
            pool = TradeTape8100.VENUE_CURVE_8285, signature = "S$i", recvMs = t0 + i)
        assertTrue("tapes ${tape.tapeMints8289()}", tape.tapeMints8289() <= 400)
        assertTrue("signed ${tape.lastSignedCount8289()}", tape.lastSignedCount8289() <= 8_000)
        // one busy mint, 25,000 signed prints: <= 800 prints on its tape, <= 30,000 signatures remembered
        val busy = mint(1, "BUSY")
        val busyAt = t0 + 10_000L
        for (j in 0 until 25_000) tape.note8285(busy, 1e-6 * (1.0 + j % 7), 0.1, 100.0, j % 3 != 0, TradeTape8100.SRC_PUMPPORTAL_8285,
            pool = TradeTape8100.VENUE_CURVE_8285, signature = "B$j", recvMs = busyAt + j)
        val now = busyAt + 25_000L
        assertTrue(tape.prints8128(busy, now, 65L * 60_000L).size <= 800)
        assertTrue("sigs ${tape.seenSigCount8289()}", tape.seenSigCount8289() <= 30_000)
        assertTrue(tape.tradeCount8289() <= 400 * 800)
        assertTrue(tape.estBytes8289() >= tape.tradeCount8289() * TradeTape8100.TRADE_BYTES_8289)
        // HARD: unprotected tapes keep their newest 400 prints, the signature window its newest 10,000
        tape.trim8289(hard = true, critical = false, nowMs = now) { false }
        assertTrue(tape.prints8128(busy, now, 65L * 60_000L).size <= 400)
        assertTrue(tape.seenSigCount8289() <= 10_000)
        assertTrue(tape.lastSignedCount8289() <= 2_000)
        // CRITICAL: at most 150 unprotected tapes; the protected mint keeps its tape
        tape.trim8289(hard = true, critical = true, nowMs = now) { it == busy }
        assertTrue(tape.tapeMints8289() <= 151)
        assertTrue(tape.prints8128(busy, now, 65L * 60_000L).isNotEmpty())
        assertTrue(tape.seenSigCount8289() <= 5_000)
        // a tape past the 65 min window always goes
        tape.trim8289(hard = false, critical = false, nowMs = now + 66L * 60_000L) { true }
        assertEquals(0, tape.tapeMints8289())
        tape.resetForTest8100()
    }

    @Test fun tapeKeepsNoPerTradeSignatureCopy() {
        val tape = src("engine/truth/TradeTape8100.kt")
        assertTrue(tape.contains("pool = MemoryBounds8289.intern8289(poolKey8285(src, pool)), signature = \"\", recvMs = recvMs,"))
        assertTrue(tape.contains("MemoryBounds8289.capByAge8289(lastSigned8285, 8_000, 10L * 60_000L, recvMs) { it }"))
    }

    // ── CandleCoverage8284: compatible 1 m store and the time-to-feature clock ──

    @Test fun candleStoreIsBoundedAndTrimsToProtected() {
        CandleCoverage8284.resetForTest8284()
        val now = t0
        for (i in 0 until 500) {
            val ts = TokenState(mint = mint(i, "C"))
            ts.candleTimeframeMinutes = 240
            val bar = Candle(ts = now - 600_000L + i * 10L, priceUsd = 1.0, marketCap = 0.0, volumeH1 = 0.0, volume24h = 10.0,
                buysH1 = 3, sellsH1 = 1, highUsd = 1.1, lowUsd = 0.9, openUsd = 1.0)
            CandleCoverage8284.merge8284(ts, listOf(bar), CandleCoverage8284.Quality8284.TRADE_OHLCV, "helius_enhanced_tx", "P", now)
        }
        assertTrue(CandleCoverage8284.storeMints8289() <= 400)
        assertTrue(CandleCoverage8284.storeBarCount8289() <= 400 * 180)
        assertTrue(CandleCoverage8284.tagCount8289() > 0)   // the stored bars keep their (weak) quality tags
        assertTrue(CandleCoverage8284.estBytes8289() >= CandleCoverage8284.storeBarCount8289() * CandleCoverage8284.BAR_BYTES_8289)
        val keep = mint(499, "C")
        CandleCoverage8284.trim8289(hard = true, critical = true, nowMs = now) { it == keep }
        assertEquals(1, CandleCoverage8284.storeMints8289())
        assertEquals(1, CandleCoverage8284.oneMinuteBars8284(keep).size)
        // a store whose newest bar is older than 2 h goes at any pressure
        CandleCoverage8284.trim8289(hard = false, critical = false, nowMs = now + 3L * 3_600_000L) { true }
        assertEquals(0, CandleCoverage8284.storeMints8289())
        CandleCoverage8284.resetForTest8284()
    }

    @Test fun needClockIsHardCapped() {
        CandleCoverage8284.resetForTest8284()
        for (i in 0 until 2_600) CandleCoverage8284.noteNeed8284(TokenState(mint = mint(i, "N")), t0, ageMs = 3_600_000L)
        assertTrue("need ${CandleCoverage8284.needCount8289()}", CandleCoverage8284.needCount8289() <= 2_000)
        CandleCoverage8284.resetForTest8284()
    }

    // ── FeatureRevision8283 / FeatureFunnel8283 ──

    @Test fun featureRevisionStampsAreHardCapped() {
        FeatureRevision8283.trim8289(hard = true, nowMs = t0 + 365L * 86_400_000L)
        FeatureRevision8283.clearForTest8283()
        for (i in 0 until 9_000) FeatureRevision8283.bumpThrottled8286(mint(i, "R"), FeatureKind8283.TAPE, t0)
        // revisions, wake stamps and throttle stamps: each <= 6,000
        assertTrue("stores ${FeatureRevision8283.storeCount8289()}", FeatureRevision8283.storeCount8289() <= 3 * 6_000)
        assertTrue(FeatureRevision8283.estBytes8289() > 0L)
        // everything expired: the pressure trim empties all three
        FeatureRevision8283.trim8289(hard = true, nowMs = t0 + 31L * 60_000L)
        assertEquals(0, FeatureRevision8283.storeCount8289())
        FeatureRevision8283.clearForTest8283()
    }

    @Test fun featureFunnelIsBoundedAndClearsOnlyAtCritical() {
        FeatureFunnel8283.clearForTest8283()
        for (i in 0 until 70_000) FeatureFunnel8283.note8283("SHITCOIN", mint(i, "F"), FeatureFunnel8283.Stage8283.NATIVE_EVALUATED)
        assertTrue(FeatureFunnel8283.seenCount8289() <= 60_000)
        assertEquals(0, FeatureFunnel8283.trim8289(critical = false))
        assertTrue(FeatureFunnel8283.trim8289(critical = true) > 0)
        assertEquals(0, FeatureFunnel8283.seenCount8289())
        assertTrue(FeatureFunnel8283.count8283("SHITCOIN", FeatureFunnel8283.Stage8283.NATIVE_EVALUATED) > 0L)
        FeatureFunnel8283.clearForTest8283()
    }

    // ── ProviderLanes8285 / DexPools8285 ──

    @Test fun providerLanePendingIsHardCappedWhateverThePriority() {
        ProviderLanes8285.resetForTests8285()
        for (i in 0 until 10_000) ProviderLanes8285.enqueue8285(ProviderLanes8285.DEX_8285, ProviderLanes8285.KIND_PAIR_8285,
            mint(i, "P"), ProviderLanes8285.Prio8285.HELD, t0 + i)
        assertTrue("pending ${ProviderLanes8285.pendingTotal8289()}", ProviderLanes8285.pendingTotal8289() <= 8_000)
        assertEquals(0, ProviderLanes8285.trim8289(t0 + 60_000L))
        assertTrue(ProviderLanes8285.trim8289(t0 + 11L * 60_000L + 10_000L) > 0)
        assertEquals(0, ProviderLanes8285.pendingTotal8289())
        ProviderLanes8285.resetForTests8285()
    }

    @Test fun dexPoolBookAndChosenAreBounded() {
        DexPools8285.resetForTests8285()
        for (i in 0 until 3_000) {
            val m = mint(i, "D")
            DexPools8285.record8285(listOf(DexPools8285.PoolRow8285(m, "pair$i", "raydium", "So11111111111111111111111111111111111111112",
                1_000.0, 10.0, 1.0, t0 + i)))
            DexPools8285.markChosen8285(m, "pair$i")
        }
        assertTrue(DexPools8285.mintCount8289() <= 2_000)
        assertTrue("chosen ${DexPools8285.chosenCount8289()}", DexPools8285.chosenCount8289() <= 2_001)
        DexPools8285.trim8289(staleMs = 60_000L, nowMs = t0 + 3_000L + 120_000L)
        assertEquals(0, DexPools8285.mintCount8289())
        assertEquals(0, DexPools8285.chosenCount8289())
        DexPools8285.resetForTests8285()
    }

    // ── CandidateAdmission8136: allocation-free invalidation and the decision caches ──

    @Test fun memoMatchIsTheSubstringRuleWithoutAllocation() {
        val m = "MintAbc"
        for (k in listOf("LIVE|MintAbc|SHITCOIN|7", "PAPER|MintAbcd|X|1", "LIVE|MintAb|X|1", "MintAbc|x", "LIVE|MintAbc",
                "LIVE|MintAbc|", "|MintAbc|L|2", "LIVE|Other|MintAbc|2", "")) {
            assertEquals(k, k.substringAfter('|').startsWith("$m|"), CandidateAdmission8136.memoForMint8289(k, m))
        }
        assertFalse(src("engine/truth/CandidateAdmission8136.kt").contains("memos8153.keys.removeIf { it.substringAfter('|')"))
        assertTrue(src("engine/truth/StrategyElection8243.kt").contains("memos8243.keys.removeIf { it.startsWith(prefix8289) }"))
    }

    @Test fun admissionDecisionCachesTrimWhatNoReaderCanBeServed() {
        for (i in 0 until 50) CandidateAdmission8136.holdDeferForTest8252(mint(i, "A"), "SHITCOIN", listOf("shape:UNKNOWN"), t0)
        val before = CandidateAdmission8136.decisionCacheCount8289()
        assertTrue(before >= 50)
        assertTrue(CandidateAdmission8136.estBytes8289() >= 50 * CandidateAdmission8136.DECISION_BYTES_8289)
        CandidateAdmission8136.trim8289(t0 + 61_000L)
        assertTrue(CandidateAdmission8136.decisionCacheCount8289() <= before - 50)
    }

    // ── WhaleDetector: launch-flow tapes and whale books (clearToken is never called) ──

    @Test fun launchTapesAndWhaleBooksAreBounded() {
        val now = System.currentTimeMillis()
        for (i in 0 until 9_000) WhaleDetector.recordTrade(mint(i, "W"), "wallet$i", 0.1, true)
        assertTrue("tapes ${WhaleDetector.launchTapeMints8289()}", WhaleDetector.launchTapeMints8289() <= WhaleDetector.LAUNCH_TAPE_CAP_8289)
        for (i in 0 until 2_600) WhaleDetector.recordTrade(mint(i, "WB"), "wallet$i", 1.0, true)
        assertTrue("books ${WhaleDetector.whaleBookMints8289()}", WhaleDetector.whaleBookMints8289() <= WhaleDetector.WHALE_BOOK_MINTS_8289)
        // queues past the 90 s window are unreadable and go
        WhaleDetector.boundLaunchTapes8289(now + 10L * 60_000L, WhaleDetector.LAUNCH_TAPE_CAP_8289)
        assertEquals(0, WhaleDetector.launchTapeMints8289())
        for (i in 0 until 2_600) WhaleDetector.clearToken(mint(i, "WB"))
    }

    // ── voter caches and the census wiring ──

    @Test fun voterCacheTrimIsSafeOnAnyState() {
        assertTrue(CortexVoters7885.trim8289(System.currentTimeMillis()) >= 0)
        assertTrue(CortexVoters7885.cacheCount8289() >= 0)
        assertTrue(CortexVoters7885.estBytes8289() >= 0L)
    }

    @Test fun newStoresAreInTheMemoryCensusWithTrims() {
        val stores = MemoryGuard7977.registeredStores8144()
        val mine = listOf("tradeTape8289", "candleCoverage8289", "featureRevision8289", "featureFunnel8289", "admissionDecisions8289",
            "providerLanes8289", "dexPools8289", "launchTapes8289", "voterCaches8289")
        for (s in mine) assertTrue(s, s in stores)
        assertEquals(stores.size, stores.toSet().size)
        assertTrue(src("engine/MemoryGuard7977.kt").contains("registerStores8289(r)  // V5.0.8289"))
    }

    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()
}
