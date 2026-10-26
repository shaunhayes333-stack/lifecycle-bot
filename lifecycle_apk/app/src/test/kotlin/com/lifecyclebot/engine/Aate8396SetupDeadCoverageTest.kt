package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexAuthority8366
import com.lifecyclebot.engine.cortex.CortexAuthority8366.Row8366
import org.junit.Assert.*
import org.junit.Test

/** V5.0.8396 — a setup-dead negative is a scoped negative only on a covered cohort; survivors alone never refuse. */
class Aate8396SetupDeadCoverageTest {
    private fun rows(resolvedInBucket: Int, total: Int, bucket: Int): List<Row8366> = (0 until total).map { i ->
        val resolved = i < resolvedInBucket
        Row8366(atMs = 1_000L + i, score = 1.0, netPct = if (resolved) -20.0 else Double.NaN, frozenBucket = bucket,
            outcomeAvailableAtMs = if (resolved) 5_000L + i else 0L, dueAtMs8392 = 4_000L)
    }

    @Test fun survivorOnlyCohortIsNotCovered() {
        // MOONSHOT 8395: 1,216 of 1,366 matured without an outcome
        assertFalse(CortexAuthority8366.cohortCovered8392(rows(2, 20, 2), 2, 100_000L))
    }

    @Test fun resolvedCohortIsCovered() {
        assertTrue(CortexAuthority8366.cohortCovered8392(rows(18, 20, 2), 2, 100_000L))
    }

    @Test fun constitutionBindsSetupDeadOnlyOnCoverage() {
        val cx = java.io.File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        val c = cx.substringAfter("private fun constitutionRefusal(").substringBefore("// ── Cortex v9: self-consistency")
        val dead = c.substringAfter("if (jointProvenDead8082(st8082)) {").substringBefore("if (!paper && a.bucket == CortexScoreboard7885.Bucket.NEUTRAL")
        assertTrue(dead.contains("setupCovered8396(a.lane, setup8082!!, a.bucket.ordinal)"))
        assertTrue(dead.contains("C6_SETUP_DEAD_COVERAGE_INCOMPLETE_NONBINDING_8396"))
        // C3 safety unchanged
        assertTrue(c.contains("return negativeOrUnknown8395(\"C3_PROVEN_NEGATIVE_EDGE\", a.lane)"))
        val cov = cx.substringAfter("private fun setupCovered8396(").substringBefore("/** Caller holds the lock.")
        assertTrue(cov.contains("CortexAuthority8366.cohortCovered8392(authorityRows8392(lane, setup), bucket, now)"))
        assertTrue("a coverage failure is UNKNOWN, never a refusal", cov.contains("catch (_: Throwable) { false }"))
    }
}
