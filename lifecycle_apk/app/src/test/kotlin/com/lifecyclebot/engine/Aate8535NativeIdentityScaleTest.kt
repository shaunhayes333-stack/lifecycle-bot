package com.lifecyclebot.engine

import com.lifecyclebot.engine.SpecialistBrainBridge7542.Opinion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8535 — audit P0-06 (a native opinion's identity was the lane's hard-coded style label) and P0-07 (native scores
 * clipped at 100 before ranking; nativeAllow counted before the eligibility reviews).
 */
class Aate8535NativeIdentityScaleTest {
    private fun opinion(reason: String, fired: String?, style: String = "VOLUME_IGNITION_SCALP") = Opinion(
        "SHITCOIN", true, 70, 60, 0.05, reason, style, "shitcoin_native_meme_entry", "shitcoin_native_exit",
        firedSetup8535 = fired, signalAtMs8535 = 1_000L,
    )

    // ── P0-06 ──

    @Test fun the_admission_identity_is_the_fired_predicate_or_the_native_rule_never_the_style_label() {
        assertEquals("IGNITION_5M", SpecialistBrainBridge7542.nativeSetupIdentity8535(opinion("SHITCOIN_NATIVE_ALLOW", "IGNITION_5M")))
        for (status in listOf("SHITCOIN_NATIVE_ALLOW", "MOONSHOT_NATIVE_ALLOW", "ALLOW", "NO_TRIGGER", "UNKNOWN")) {
            assertNull(SpecialistBrainBridge7542.nativeSetupIdentity8535(opinion(status, null)))
        }
        assertEquals("a NO_TRIGGER classification is not a fired predicate", "NATIVE_RULE_8535:R", SpecialistBrainBridge7542.nativeSetupIdentity8535(opinion("R", "NO_TRIGGER")))
        assertNull(SpecialistBrainBridge7542.nativeSetupIdentity8535(opinion("", null)))
        assertNull(SpecialistBrainBridge7542.nativeSetupIdentity8535(opinion("NONE", "NONE")))
        for (o in listOf(opinion("X", "IGNITION_5M"), opinion("X", null), opinion("", null)))
            assertTrue(SpecialistBrainBridge7542.nativeSetupIdentity8535(o) != o.setup)
        // the admission adapter reads that identity, not the style
        val admission = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(admission.contains("SpecialistBrainBridge7542.nativeSetupIdentity8535(it)"))
        assertTrue(!admission.contains("nativeOpinion8213?.setup?.takeIf"))
    }

    // ── P0-07 ──

    @Test fun a_lane_scored_above_100_keeps_its_scale_and_its_conviction_on_its_own_bar() {
        val bar = 60
        val c101 = SpecialistExecution7948.laneRelativeConvictionOnScale8535(101, 80, bar, 40, 150)
        val c120 = SpecialistExecution7948.laneRelativeConvictionOnScale8535(120, 80, bar, 40, 150)
        val c150 = SpecialistExecution7948.laneRelativeConvictionOnScale8535(150, 80, bar, 40, 150)
        assertTrue("101 < 120 < 150 on the lane's own scale", c101 < c120 && c120 < c150)
        assertEquals("the bar reads 50", 50.0, SpecialistExecution7948.relativeToBarOnScale8535(60, 60, 150), 1e-9)
        assertEquals("the lane's maximum reads 100", 100.0, SpecialistExecution7948.relativeToBarOnScale8535(150, 60, 150), 1e-9)
        // superseded behaviour: clipped to 100 first, the three tied
        assertEquals(1, listOf(101, 120, 150).map { SpecialistExecution7948.relativeToBar7948(it, bar) }.toSet().size)
        // on a 100 scale the read is the 7948 read exactly
        for (v in listOf(0, 15, 40, 60, 85, 100))
            assertEquals(SpecialistExecution7948.relativeToBar7948(v, 15), SpecialistExecution7948.relativeToBarOnScale8535(v, 15, 100), 1e-9)
        val o = Opinion("MOONSHOT", true, 100, 70, 0.1, "MOONSHOT_NATIVE_ALLOW", "DIAMOND_HANDS_RUNNER", "e", "x", rawScore8535 = 135, scaleMax8535 = 150)
        assertEquals("the clipped score is still what 0..100 consumers read", 100, o.score)
        assertEquals(135, o.rawScore8535)
    }

    @Test fun the_native_counters_count_the_final_reviewed_opinion() {
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/SpecialistBrainBridge7542.kt").readText()
        assertTrue(bridge.contains("effective8522.values.forEach(::recordFinal8535)"))
        assertTrue(bridge.contains(".also(::recordPre8535)"))
        assertTrue("the pre-review count keeps its own name", bridge.contains("NATIVE_BRAIN_PRE_REVIEW_ALLOW_8535_"))
        assertTrue(bridge.contains("NATIVE_FINAL_ELIGIBLE_OFFERED_8535_"))
        assertTrue(!bridge.contains(".also(::record)"))
    }
}
