package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.SpecialistObjective7801
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7801SpecialistObjectiveTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test fun sameReturnHasDifferentMeaningByLane() {
        val moon = SpecialistObjective7801.evaluate("MOONSHOT", 35.0, 30L * 60_000L, "test")
        val cash = SpecialistObjective7801.evaluate("CASHGEN", 35.0, 30L * 60_000L, "test")
        val treasury = SpecialistObjective7801.evaluate("TREASURY", 35.0, 30L * 60_000L, "test")
        assertFalse(moon.mandateSuccess)
        assertTrue(cash.mandateSuccess)
        assertTrue(treasury.mandateSuccess)
        assertTrue(cash.utility > moon.utility)
    }

    @Test fun runnerTaxonomyIsNotGenericMemeTaxonomy() {
        val r = src("engine/RunnerExitProfile7277.kt")
        val keys = r.substringAfter("RUNNER_LANE_KEYS = arrayOf(").substringBefore(")")
        assertTrue(keys.contains("MOONSHOT"))
        assertTrue(keys.contains("SHITCOIN"))
        assertTrue(keys.contains("PROJECT_SNIPER"))
        assertFalse(keys.contains("EXPRESS"))
        assertFalse(keys.contains("MANIPULATED"))
        assertFalse(keys.contains("DIP_HUNTER"))
        assertFalse(keys.contains("CORE"))
    }

    @Test fun coreYieldsStrongNativeButEvaluatesWeakAmbiguity() {
        val b = src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(b.contains("val strongNative7801=av>=70"))
        assertTrue(b.contains("val weakSingle7801="))
        assertTrue(b.contains("CORE_GENERALIST_WEAK_NATIVE_"))
        assertTrue(b.contains("CORE_YIELD_CLEAR_SPECIALIST_OWNER"))
    }

    @Test fun nativeEvidenceCannotManufactureEligibility() {
        val b = src("engine/SpecialistBrainBridge7542.kt")
        val fn = b.substringAfter("private fun shapeNative7801").substringBefore("fun cachedSnapshot7650")
        assertTrue(fn.contains("if (!o.authoritative || !o.eligible) return o"))
        assertTrue(b.contains("NATIVE_BRAIN_EVIDENCE_SHAPED_7801_"))
    }

    @Test fun probationNeedsNegativeUtilityNotLowHitRateAlone() {
        val p = src("engine/truth/BleederLaneProbation6747.kt")
        assertTrue(p.contains("wr < WR_THRESHOLD && meanUtility < 0.0"))
        assertTrue(p.contains("wr >= WR_RECOVERY || meanUtility >= 0.15"))
    }
}
