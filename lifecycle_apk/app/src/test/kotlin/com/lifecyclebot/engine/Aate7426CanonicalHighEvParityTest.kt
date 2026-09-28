package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7426CanonicalHighEvParityTest {
    @Test fun canonicalPaperAndLiveRequirePositiveOracleAdmission() {
        val learned = File("src/main/kotlin/com/lifecyclebot/engine/truth/LearnedAdmissionAuthority6846.kt").readText()
        assertTrue(learned.contains("CANONICAL_HIGH_EV_ORACLE_NOT_ADMIT_7426"))
        assertTrue(learned.contains("inputs.oracleVerdict6915 != PredictiveEntryOracle6915.Verdict.ADMIT"))
        assertTrue(learned.contains("action=shadow_replay_lab_only"))
        assertTrue(learned.contains("CANONICAL_HIGH_EV_ORACLE_ADMIT_7426"))
    }

    @Test fun probesNeverSpendCanonicalCapital() {
        val entry = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutableEntryAuthority6450.kt").readText()
        val probe = entry.substringAfter("LearnedAdmissionAuthority6846.Verdict.PROBE_ONLY -> {")
            .substringBefore("else -> {")
        assertTrue(probe.contains("CANONICAL_HIGH_EV_PROBE_SHADOW_ONLY_7426"))
        assertTrue(probe.contains("Verdict.DENY_LEARNED_NEGATIVE_6846"))
        assertTrue(probe.contains("0.0"))
        assertFalse(probe.contains("Verdict.ALLOW_PROBE"))
    }

    @Test fun learnedAuthorityFailureCannotFailOpenCanonicalCapital() {
        val entry = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutableEntryAuthority6450.kt").readText()
        val region = entry.substringAfter("val learned = try {")
            .substringBefore("return when (learned?.verdict)")
        assertTrue(region.contains("CANONICAL_HIGH_EV_AUTHORITY_UNAVAILABLE_7426"))
        assertTrue(region.contains("Verdict.DENY_LEARNED_NEGATIVE_6846"))
        assertFalse(region.contains("EXECUTABLE_ENTRY_ORACLE_ERROR_FAIL_OPEN_7263"))
    }

    @Test fun diagnosticsExposeHighEvContract() {
        val health = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(health.contains("CANONICAL_HIGH_EV_ORACLE_ADMIT_7426"))
        assertTrue(health.contains("CANONICAL_HIGH_EV_SHADOW_ONLY_7426"))
        assertTrue(health.contains("CANONICAL_HIGH_EV_PROBE_SHADOW_ONLY_7426"))
        assertTrue(health.contains("CANONICAL_HIGH_EV_AUTHORITY_UNAVAILABLE_7426"))
    }
}
