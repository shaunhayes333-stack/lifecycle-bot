package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CryptoSpotPaperBacking8788
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8788 — owner: CRYPTO_SPOT live follows its own paper record, past the expectancy refusal only, bounded. */
class Aate8788CryptoSpotPaperBackingTest {
    @Test fun the_paper_record_backs_live_only_when_it_is_decisive_and_positive() {
        // the console's record: W19 / L13 decisive, mean +2.3 %, +4.075 SOL
        assertTrue(CryptoSpotPaperBacking8788.paperBacks8788(19, 13, 2.3, 4.075, 1.0))
        assertFalse("fewer than 20 decisive closes", CryptoSpotPaperBacking8788.paperBacks8788(10, 5, 5.0, 1.0, 2.0))
        assertFalse("a losing mean", CryptoSpotPaperBacking8788.paperBacks8788(19, 13, -0.5, 4.0, 1.0))
        assertFalse("no profit-factor edge", CryptoSpotPaperBacking8788.paperBacks8788(19, 13, 2.3, 4.0, -0.2))
        assertFalse(CryptoSpotPaperBacking8788.paperBacks8788(19, 13, Double.NaN, 4.0, 1.0))
        assertTrue(CryptoSpotPaperBacking8788.lossStopHit8788(-0.1))
        assertFalse(CryptoSpotPaperBacking8788.lossStopHit8788(-0.09))
        // superseded by V5.0.8806 — CRYPTO_ALT (every dynamic alt) is backed by its own paper record; leverage never is
        assertFalse("leverage is never backed", CryptoSpotPaperBacking8788.backs8788("CRYPTO_LEV", 1_000L))
        assertTrue("CRYPTO_ALT" in CryptoSpotPaperBacking8788.BACKED_LANES_8806 && "CRYPTO_LEV" !in CryptoSpotPaperBacking8788.BACKED_LANES_8806)
    }

    @Test fun it_replaces_only_the_expectancy_refusal() {
        val c = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalAssetEntryContract6551.kt").readText()
        val hard = c.indexOf("if (candidate.hardSafetyReasons.isNotEmpty())")
        val backing = c.indexOf("CryptoSpotPaperBacking8788.backs8788(candidate.evidence[\"lane8788\"]")
        assertTrue("safety, route, toggle and wallet refuse first", hard in 0 until backing)
        assertTrue(c.contains("!livePositiveEv7828 && !evRefusalInverted8094 && !paperBacked8788) {"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText().contains("\"lane8788\" to candidate.selectedLane,"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/LearningResetSweep7781.kt").readText().contains("CryptoSpotPaperBacking8788.load8788(app)"))
        assertTrue(CryptoSpotPaperBacking8788.statusLine8788().startsWith("cryptoSpotPaperBacking8788[on="))
        assertTrue(CryptoSpotPaperBacking8788.MAX_PER_HOUR_8788 <= 4)
        assertTrue(CryptoSpotPaperBacking8788.REASON_8788.isNotBlank() && CryptoSpotPaperBacking8788.enabled8788)
        assertTrue(CryptoSpotPaperBacking8788.LANE_8788 == "CRYPTO_SPOT" && CryptoSpotPaperBacking8788.MIN_DECISIVE_8788 == 20)
        assertTrue(CryptoSpotPaperBacking8788.LOSS_STOP_SOL_8788 == 0.1)
    }

    @Test fun backing_is_an_exception_to_both_advisory_ev_checks_and_counted_only_on_sealed_intents() {
        val c = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalAssetEntryContract6551.kt").readText()
        val eligibility = c.indexOf("CryptoSpotPaperBacking8788.backs8788(candidate.evidence[\"lane8788\"]")
        val oracle = c.indexOf("if (!advisoryPass7263 && !provenAdmit7287 && !provenNonBindingPass7391)")
        // V5.0.8961: assess once before heuristic expectancy, preserve data waits, then apply the same refusal before sizing.
        val cortex = c.indexOf("CrossAssetCortex7931.assessEntry8871(candidate)")
        val cortexGate = c.indexOf("CrossAssetCortex7931.refusalBinds8871(why, entryAssessment8871)")
        val executable = c.indexOf("if (!sizing.executable)")
        val reserve = c.indexOf("CryptoSpotPaperBacking8788.reserve8832(")
        val registered = c.indexOf("ExecutableOpenGate.registerCanonicalIntent6554(intent)")
        val commit = c.indexOf("CryptoSpotPaperBacking8788.commit8832(attemptId)")
        assertTrue("backing eligibility is read before the other admission gates", eligibility in 0 until oracle)
        assertTrue("backed live candidates must not be refused by the redundant advisory EV check",
            c.contains("paperBacked8788 || evRefusalInverted8094"))
        assertTrue("the shared assessment precedes both admission gates", cortex in 0 until oracle && cortex < cortexGate)
        assertTrue("the Cortex assessment is read exactly once",
            c.split("CrossAssetCortex7931.assessEntry8871(candidate)").size == 2)
        assertTrue("quota is reserved only after Oracle, Cortex refusal handling and executable sizing",
            oracle in 0 until executable && cortexGate in 0 until executable && executable < reserve)
        assertTrue("quota is charged only after immutable registration", reserve < registered && registered < commit)
        assertTrue("registration failure must release the quota", c.contains("CryptoSpotPaperBacking8788.release8832(attemptId)"))
        assertTrue(CryptoSpotPaperBacking8788.room8832(0, 0))
        assertTrue(CryptoSpotPaperBacking8788.room8832(3, 0))
        assertFalse(CryptoSpotPaperBacking8788.room8832(3, 1))
        assertFalse(CryptoSpotPaperBacking8788.room8832(4, 0))
        assertFalse(CryptoSpotPaperBacking8788.room8832(0, 4))
    }
}
