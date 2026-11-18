package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Decision8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.CryptoSignalEngine8857
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8857 — the crypto trader is the second, simple engine: integrity refusals of the meme live edge gate bind it; the
 * meme stack's learned doors are counted and graded but do not refuse a LIVE crypto entry its own lane record backs.
 */
class Aate8857CryptoSignalEngineTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun decision(terminal: Terminal8136, reason: String, cost: Double, vararg trace: String) =
        Decision8136(terminal, reason, false, null, Double.NaN, trace.toList(), costPct = cost)

    @Test fun integrityBindsBothEngines() {
        for (r in listOf("CANDIDATE_ADMISSION_8136_SAFETY_REFUSED_8136", "CANDIDATE_ADMISSION_8136_STRUCTURAL_RISK_8136", "CURVE_NOBODY_BOUGHT_8046",
            "BIG_LOSER_8130", "LIVE_ROUTE_UNAVAILABLE", "PRETRADE_HARD_BLOCK_LP_LOCK_BELOW_REQUIRED_8522", "LIVE_LANE_REALISED_LOSING_8325",
            "CANONICAL_ENTRY_EVAL_FAILED_8392", "WALLET_BLIND_8074", "MAYHEM_MODE_7943", "INVALID_OR_STALE_PRICE", ""))
            assertTrue(r, CryptoSignalEngine8857.integrityReason8857(r))
        assertTrue("an unreadable reason binds", CryptoSignalEngine8857.integrityReason8857(null))
    }

    @Test fun theMemeStacksLearnedDoorsAreNotIntegrity() {
        // the doors that refused the crypto trader's candidates in 5.0.8853, and the meme entry-state / forecast doors
        for (r in listOf("CANDIDATE_ADMISSION_8136_EXECUTION_ECONOMICS_8136", "EDGE_7877_SHAPE_7883_MCAP_USD_50k-250k_n67_-7.9PCT",
            "LIVE_SOURCE_PROVEN_LOSING_8536", "CANDIDATE_ADMISSION_8136_MEME_BUYS_THE_HIGH_8582", "CANDIDATE_ADMISSION_8136_EDGE_MODEL_NEGATIVE_8537",
            "CANDIDATE_ADMISSION_8136_LIVE_STAGE_UNREAD_8711", "EVIDENCE_HOLD_8336_NO_PROOF", "LIVE_RUNNER_STALE_COIN_8627", "NET_EDGE_BELOW_2X_COST_8030",
            "CHASING_SPIKE_8065", "WATCH_FIRST_7994", "COST_LEDGER_7962_ABOVE_GROSS"))
            assertFalse(r, CryptoSignalEngine8857.integrityReason8857(r))
    }

    @Test fun aDataRefusalBindsOnlyAsTheExecutableMinimum() {
        val defer = "DATA_INCOMPLETE_DEFER_8136"
        fun binds(d: Decision8136?) = CryptoSignalEngine8857.binds8857(defer) { d }
        assertFalse("a wait for move history is a learned door", binds(decision(Terminal8136.DEFER_DATA, defer, 3.0, "missing=shape,chg,bp")))
        assertTrue("no fresh price", binds(decision(Terminal8136.DEFER_DATA, defer, 3.0, "missing=tape,price")))
        assertTrue("a critical fact unobserved", binds(decision(Terminal8136.DEFER_DATA, defer, 3.0, "missing=liq", "criticalUnobserved8170=liq")))
        assertTrue("an unknown cost", binds(decision(Terminal8136.DEFER_DATA, defer, Double.NaN, "missing=shape")))
        assertTrue("no decision to read", binds(null))
        assertTrue("a read that throws", CryptoSignalEngine8857.binds8857("DATA_DEFER_FALLING_HOUR_8418") { throw IllegalStateException("x") })
        assertFalse("the admission has since admitted", binds(decision(Terminal8136.ADMIT, "ADMIT", 3.0)))
        assertTrue(CryptoSignalEngine8857.dataIntegrity8857(decision(Terminal8136.REFUSE, "SAFETY_REFUSED_8136", 3.0)))
        assertFalse(CryptoSignalEngine8857.dataIntegrity8857(decision(Terminal8136.REFUSE, "EXECUTION_ECONOMICS_8136", 3.0)))
        // a learned door needs no admission read; an integrity door never consults one
        assertFalse(CryptoSignalEngine8857.binds8857("LIVE_SOURCE_PROVEN_LOSING_8536") { throw IllegalStateException("not read") })
        assertTrue(CryptoSignalEngine8857.binds8857("BIG_LOSER_8130") { throw IllegalStateException("not read") })
    }

    @Test fun onlyABackedLiveEntryPassesALearnedDoor() {
        assertFalse("paper is untouched", CryptoSignalEngine8857.passes8857(false, "CRYPTO_ALT", false, 0L))
        assertFalse("integrity never passes", CryptoSignalEngine8857.passes8857(true, "CRYPTO_ALT", true, 0L))
        assertFalse("a leveraged lane is never backed", CryptoSignalEngine8857.passes8857(true, "CRYPTO_LEV", false, 0L))
        assertFalse(CryptoSignalEngine8857.passes8857(true, null, false, 0L))
        val (crypto, meme) = CryptoSignalEngine8857.split8857(
            mapOf("CRYPTO_ALT" to (3 to -0.010), "MOONSHOT" to (5 to 0.020), "crypto_spot" to (1 to 0.005))) { it.trim().uppercase().startsWith("CRYPTO") }
        assertEquals(4, crypto.first); assertEquals(-0.005, crypto.second, 1e-12)
        assertEquals(5, meme.first); assertEquals(0.020, meme.second, 1e-12)
        assertTrue(CryptoSignalEngine8857.statusLine8857().startsWith("cryptoSignalEngine8857[on=true"))
    }

    @Test fun theContractAsksTheEngineAndReservesThePermit() {
        val c = src("engine/truth/CanonicalAssetEntryContract6551.kt")
        assertTrue(c.contains("CryptoSignalEngine8857.passes8857(candidate.mode.equals(\"LIVE\", true), candidate.evidence[\"lane8788\"], integrity8857, now8857)"))
        assertTrue(c.contains("val backingReserved8832 = if (paperBacked8788 || signalEngine8857) try {"))
        assertTrue(c.contains("if ((paperBacked8788 || signalEngine8857) && !backingReserved8832)"))
        assertTrue("an unreadable integrity check binds", c.contains("refusalBinds8857(candidate, why, now8857) } catch (_: Throwable) { true }"))
        assertTrue(src("engine/cortex/CrossAssetCortex7931.kt").contains("fun refusalBinds8857(c: CanonicalAssetEntryCandidate6551, reason: String"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("Crypto signal engine (§8857): "))
        assertTrue(CandidateAdmission8136.feedPeriodMs8855("CRYPTO_ALT") > 0L)
    }
}
