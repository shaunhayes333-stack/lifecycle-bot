package com.lifecyclebot.engine

import com.lifecyclebot.engine.NetworkVerdict8894.Host8894
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8898 — read the data right: (1) a few providers timing out while the rest answer is those providers, not the phone;
 * (2) a lost label is filed like a booked one (same keys, horizon, once per coin), and coverage is read now, not over the
 * lifetime tally.
 */
class Aate8898ReadTheDataRightTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    // the 20:53 report's host table on 5.0.8894 (successes, 4xx, 5xx, transport failures)
    private val at2053 = listOf(
        Host8894("coingecko", 56, 1, 0, 1), Host8894("damm-v2.datapi.meteora.ag", 43, 0, 0, 0), Host8894("defillama", 69, 0, 0, 41),
        Host8894("dexscreener", 150, 0, 0, 4), Host8894("dexscreener_feeds", 170, 0, 0, 0), Host8894("geckoterminal", 69, 1, 0, 3),
        Host8894("groq", 195, 0, 0, 0), Host8894("helius", 180, 0, 0, 20), Host8894("helius-enhanced", 66, 0, 0, 81),
        Host8894("helius_curve", 200, 0, 0, 0), Host8894("jupiter", 33, 19, 41, 30), Host8894("jupiter_price_keyed", 50, 1, 0, 2),
        Host8894("jupiter_quote", 23, 1, 0, 1), Host8894("jupiter_quote_obs", 107, 17, 0, 0),
        Host8894("rpc_api.mainnet-beta.solana.com", 83, 0, 0, 32))
    private val at1757 = listOf(
        Host8894("jupiter", 11, 8, 30, 150), Host8894("jupiter_price_keyed", 14, 4, 0, 112),
        Host8894("dexscreener", 123, 0, 0, 58), Host8894("raydium", 118, 0, 0, 55), Host8894("helius", 105, 0, 0, 44),
        Host8894("helius_curve", 113, 0, 0, 0), Host8894("helius-enhanced", 5, 0, 0, 20), Host8894("coingecko", 3, 2, 0, 1))

    @Test fun threeProvidersOfTenTimingOutAreThoseProviders() {
        assertEquals(3, NetworkVerdict8894.failing8894(at2053).map { NetworkVerdict8894.provider8894(it.host) }.distinct().size)
        assertEquals(9, NetworkVerdict8894.active8898(at2053))
        assertFalse(NetworkVerdict8894.degraded8894(at2053))
        val line = NetworkVerdict8894.line8894(at2053)
        assertTrue(line, line.startsWith("network8894[PROVIDER failingProviders=3 helius-enhanced net81/147 55%"))
        assertTrue(line, line.contains("active8898=9 "))
        // every provider in use failing (17:57 on 5.0.8893) stays the phone
        assertEquals(4, NetworkVerdict8894.active8898(at1757))
        assertTrue(NetworkVerdict8894.degraded8894(at1757))
        assertTrue(NetworkVerdict8894.line8894(at1757).startsWith("network8894[DEGRADED failingProviders=4 "))
    }

    @Test fun coverageIsTheRecentPricedShare() {
        val c = ForwardReturnLabeler7731.Coverage8898()
        repeat(99) { c.add(it % 3 != 0) }
        assertNull("too few events: the lifetime tally decides", c.share())
        c.add(true)
        assertEquals(0.67, c.share()!!, 0.01)
        // a run of lost reads lowers it; priced reads lift it back: it follows the recent window
        repeat(600) { c.add(false) }
        assertTrue(c.share()!! < 0.5)
        repeat(1_200) { c.add(true) }
        assertTrue(c.share()!! > 0.8)
        assertTrue(DiagnosisBrief8438.coveredNow8898(0.67, null))
        assertFalse(DiagnosisBrief8438.coveredNow8898(0.3, null))
        assertFalse("unknown recent share and no tally: not covered, as before", DiagnosisBrief8438.coveredNow8898(null, null))
    }

    @Test fun aLostMarkIsFiledLikeABookedLabel() {
        val l = src("engine/truth/ForwardReturnLabeler7731.kt")
        val lost = l.substringAfter("private fun markLost(o: Obs) {").substringBefore("lostMark.incrementAndGet()")
        assertTrue(lost.contains("for (k in horizonScoped8766(keysOf7928(o), 60, o.targetHorizon8521)) {"))
        assertTrue(lost.contains("if (crossLaneKey8776(k) && !firstLost8898(k, o.mint, o.atMs)) continue"))
        assertTrue(ForwardReturnLabeler7731.firstLost8898("AGG|REFUSED", "MINT8898", 1L))
        assertFalse("the second lane's loss of the same coin decision is not a second loss",
            ForwardReturnLabeler7731.firstLost8898("AGG|REFUSED", "MINT8898", 1L))
        assertTrue(ForwardReturnLabeler7731.firstLost8898("AGG|ADMITTED", "MINT8898", 1L))
        val book = l.substringAfter("private fun book(o: Obs, horizon: Int").substringBefore("private fun bookFresh8169")
        assertTrue(book.contains("if (horizon == 60 && (k == AGG_ADMITTED || k == AGG_REFUSED)) noteCoverage8898(k == AGG_ADMITTED, booked = true)"))
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains(
            "com.lifecyclebot.engine.DiagnosisBrief8438.coveredNow8898(true, ForwardReturnLabeler7731.aggregate8438(true))"))
        val brief = src("engine/DiagnosisBrief8438.kt")
        assertTrue(brief.contains("val coveredBoth8474 = coveredNow8898(true, adm) && coveredNow8898(false, ref)"))
        assertTrue(brief.contains("append(coverageNote8898())"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 87. Read the data right (8898)"))
    }
}
