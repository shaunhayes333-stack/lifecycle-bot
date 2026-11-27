package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.ResearchRows8877
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9013 — (1) the hour's research row carries its path (the low before the peak, when the peak came, the give-back
 * after it), so a stop and a target can be put in order; (2) a crypto lane's row carries its desk's own strategy and trend
 * cell as its setup, so crypto setups can be graded and mined like the meme lanes'.
 */
class Aate9013RowsCarryPathAndDeskSetupTest {
    private fun row(path: String) = JSONObject(ResearchRows8877.line8877(1L, 3_600_000L, "MintAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump", "MOONSHOT", true,
        "BASE_START", "IGNITION_5M", 4.0, 30.0, 15_000.0, 25_000.0, 3.0, 12.0, 15.0, 80.0, 60.0, -22.0, "NATIVE_READY", "", "", 3.0, path))

    @Test fun theRowCarriesItsPathWhenThePeakWasDated() {
        val p = ResearchRows8877.pathJson9013(-22.0, 754_000L, 31.5)
        val o = row(p).getJSONObject("path9013")
        assertEquals(-22.0, o.getDouble("dip"), 1e-9); assertEquals(754L, o.getLong("tPeakS")); assertEquals(31.5, o.getDouble("gb5"), 1e-9)
        assertEquals("a peak never dated writes no path", "", ResearchRows8877.pathJson9013(-22.0, 0L, 31.5))
        assertFalse(row("").has("path9013"))
        assertTrue("an unread dip is null, not zero", JSONObject(ResearchRows8877.pathJson9013(Double.NaN, 60_000L, Double.NaN)).isNull("dip"))
        assertEquals("the age and every earlier field are where they were", 3.0, row(p).getDouble("age"), 1e-9)
        val lab = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        val rowCall = lab.substringAfter("ResearchRows8877.note8877(ResearchRows8877.line8877(").substringBefore("// V5.0.8891")
        assertTrue(rowCall.contains("ResearchRows8877.pathJson9013(o.dipBeforePeak7962, if (o.peakAtMs7955 > o.atMs) o.peakAtMs7955 - o.atMs else 0L, o.giveback5_7955),"))
        assertTrue("the same path row carries the marks needed to validate its price basis",
            rowCall.contains("priceProof9043 = ResearchEvidence9043.priceProof9043(o.basis8386.mark, o.lastAcceptedMark8395,"))
        assertTrue("the same path row carries the decision's exit recipe",
            rowCall.contains("labelRecipe9043 = o.labelRecipe9043?.json9043()?.toString().orEmpty()"))
    }

    @Test fun aStopAndATargetCanBePutInOrder() {
        val r = ResearchRows8877
        // dipped 22 % on the way to a +80 % peak: a 20 % stop came first, a 25 % stop did not
        assertEquals(true, r.stopFirst9013(-22.0, 80.0, -22.0, 20.0, 50.0))
        assertEquals(false, r.stopFirst9013(-22.0, 80.0, -22.0, 25.0, 50.0))
        // never reached the target: the stop only if the low reached it
        assertEquals(true, r.stopFirst9013(-5.0, 30.0, -40.0, 25.0, 50.0))
        assertEquals(false, r.stopFirst9013(-5.0, 30.0, -10.0, 25.0, 50.0))
        // a row without the dip: decided only when one of the two was never touched
        assertEquals(false, r.stopFirst9013(Double.NaN, 80.0, -10.0, 25.0, 50.0))
        assertEquals(true, r.stopFirst9013(Double.NaN, 30.0, -40.0, 25.0, 50.0))
        assertNull("both touched and no order: the row cannot say", r.stopFirst9013(Double.NaN, 80.0, -40.0, 25.0, 50.0))
        assertNull(r.stopFirst9013(-22.0, 80.0, -22.0, 0.0, 50.0))
    }

    @Test fun aCryptoRowCarriesItsDesksOwnSetup() {
        val l = ForwardReturnLabeler7731
        assertEquals("CX_TREND_FOLLOW@UP", l.deskSetup9013("trend-follow", "up"))
        assertEquals("CX_BREAKOUT", l.deskSetup9013(" Breakout ", null))
        assertEquals("", l.deskSetup9013("", "UP")); assertEquals("", l.deskSetup9013(null, null))
        assertEquals("CX_BREAKOUT", l.rowSetup9013("CRYPTO_CORE", "NONE", "CX_BREAKOUT"))
        assertEquals("a meme lane keeps its playbook setup", "IGNITION_5M", l.rowSetup9013("MOONSHOT", "IGNITION_5M", "CX_BREAKOUT"))
        assertEquals("NONE", l.rowSetup9013("CRYPTO_CORE", "NONE", ""))
        assertTrue(l.deskSetupLine9013().startsWith("cryptoDeskSetups9013="))
        l.noteDeskSetup9013("", "CRYPTO_CORE", "x", null); l.noteDeskSetup9013("NoSuchObservation9013", "MOONSHOT", "x", null)   // nothing pending: no-ops
        val cx = File("src/main/kotlin/com/lifecyclebot/engine/cortex/CrossAssetCortex7931.kt").readText()
        assertTrue(cx.contains("ForwardReturnLabeler7731.deskSetup9013(c.evidence[\"strategy7803\"],c.evidence[\"trend8886\"])"))
        assertTrue(cx.contains("ForwardReturnLabeler7731.observe(ts,lane,admitted,reason,at9058,deskSetup9058=setup9058,observationMode9059=c.mode)"))
        val lab = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(lab.contains("o.stage, rowSetup9013(o.lane, o.setup7955, o.deskSetup9013),"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("ForwardReturnLabeler7731.deskSetupLine9013()"))
    }
}
