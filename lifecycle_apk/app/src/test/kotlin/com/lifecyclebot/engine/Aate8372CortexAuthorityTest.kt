package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexAuthority8366
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8372 — the single authority test must evaluate the same frozen token verdicts that exercise its power. */
class Aate8372CortexAuthorityTest {
    private val strong = CortexScoreboard7885.Bucket.STRONG.ordinal
    private val neutral = CortexScoreboard7885.Bucket.NEUTRAL.ordinal

    private fun rows(
        n: Int = 500,
        outcome: (Int) -> Double = { x -> ((x - 50.0) / 5.0) * 2.0 + 1.0 },
    ): List<CortexAuthority8366.Row8366> = (0 until n).map { i ->
        val x = (i * 37) % 100
        val score = (x - 50.0) / 5.0
        val at = 1_000_000L + i * 60_000L
        CortexAuthority8366.Row8366(at, score, outcome(x), CortexScoreboard7885.bucketOf(score, 0.0, false).ordinal, at + 300_000L)
    }

    private fun after(rows: List<CortexAuthority8366.Row8366>): Long = rows.maxOf { it.outcomeAvailableAtMs } + 1L

    @Test fun profitableScoreQuintilesCannotAuthoriseLosingActualStrongDecisions() {
        val sample = rows { x -> when {
            x >= 80 -> 30.0
            x >= 66 -> -60.0
            else -> -20.0
        } }
        // The highest fifth did win, but the broader set actually stamped STRONG lost overall.
        val old = CortexAuthority8366.test8366(sample, 1.5)
        assertTrue(old.granted)
        assertTrue(old.topMean > 20.0)
        val current = CortexAuthority8366.test8372(sample, 1.5, after(sample))
        assertTrue(current.ic > 0.0)
        assertTrue(current.spread > 1.5)
        assertEquals(-7.0588235294117645, current.topMean, 1e-12)
        assertEquals(CortexAuthority8366.Verdict8366.TOP_NOT_POSITIVE, current.verdict)
        assertFalse(current.granted)
        assertEquals(0.0, current.fraction, 0.0)
    }

    @Test fun profitableActualStrongDecisionsEarnTheSameGraduatedAuthority() {
        val sample = rows()
        val result = CortexAuthority8366.test8372(sample, 1.5, after(sample))
        assertTrue(result.granted)
        assertEquals(CortexAuthority8366.Verdict8366.GRANTED, result.verdict)
        val oos = sample.drop(sample.size / CortexAuthority8366.FOLDS_8366)
        assertEquals(oos.size, result.oosN)
        assertEquals(oos.count { it.frozenBucket == strong }, result.topN)
        assertEquals(oos.count { it.frozenBucket != strong }, result.bottomN)
        assertEquals(oos.filter { it.frozenBucket == strong }.map { it.netPct }.average(), result.topMean, 1e-12)
        assertEquals(oos.filter { it.frozenBucket != strong }.map { it.netPct }.average(), result.bottomMean, 1e-12)
        assertEquals(result.oosN, result.topN + result.bottomN)
        assertEquals(CortexAuthority8366.graduated8366(result.spread, 1.5), result.fraction, 0.0)
        assertEquals("STRONG_VS_NON_STRONG_8372", result.cohort)
        assertTrue(result.compact().contains("cohort=STRONG_VS_NON_STRONG_8372"))
        // Realised closes still only revoke; there is no new prerequisite that creates a cold-start deadlock.
        assertEquals(result.fraction, CortexAuthority8366.authority8366(result, false, 0.0, Double.NaN), 0.0)
        assertEquals(0.0, CortexAuthority8366.authority8366(result, false, 20.0, -2.0), 0.0)
        assertEquals(0.0, CortexAuthority8366.authority8366(result, true, 0.0, Double.NaN), 0.0)
    }

    @Test fun neutralIsAValidBaselineWhenNoTokenWasStampedRefuse() {
        val sample = rows().mapIndexed { i, r ->
            val score = ((i * 37) % 100) / 10.0
            r.copy(score = score, netPct = score * 2.0 - 1.0,
                frozenBucket = CortexScoreboard7885.bucketOf(score, 1.0, true).ordinal)
        }
        assertTrue(sample.none { it.frozenBucket == CortexScoreboard7885.Bucket.REFUSE.ordinal })
        val result = CortexAuthority8366.test8372(sample, 1.5, after(sample))
        assertTrue(result.granted)
        assertTrue(result.bottomN >= CortexAuthority8366.MIN_BUCKET_N_8366)
        assertEquals(result.oosN, result.topN + result.bottomN)
    }

    @Test fun tiedScoresNeverCountTheSameOutcomeOnBothSides() {
        val sample = rows().map { it.copy(score = 4.0, netPct = 8.0, frozenBucket = strong) }
        val result = CortexAuthority8366.test8372(sample, 1.5, after(sample))
        assertEquals(result.oosN, result.topN)
        assertEquals(0, result.bottomN)
        assertFalse(result.granted)
        assertEquals(CortexAuthority8366.Verdict8366.INSUFFICIENT_OOS, result.verdict)
    }

    @Test fun labelsNeverReclassifyTheFrozenVerdictWithHindsight() {
        // Scores/outcomes can be high, but an observation actually stamped NEUTRAL never becomes a STRONG success.
        val sample = rows().map { it.copy(frozenBucket = neutral) }
        val result = CortexAuthority8366.test8372(sample, 1.5, after(sample))
        assertEquals(0, result.topN)
        assertEquals(result.oosN, result.bottomN)
        assertFalse(result.granted)
    }

    @Test fun aLaterRunnerOutcomeCannotEnterAnEarlierAsOfReplay() {
        val sample = rows().mapIndexed { i, r ->
            val at = 1_000_000L + i * 1_000L
            r.copy(atMs = at, outcomeAvailableAtMs = at + 4L * 3_600_000L)
        }
        val early = CortexAuthority8366.test8372(sample, 1.5, 1_500_000L)
        assertEquals(0, early.oosN)
        assertEquals(sample.size, early.excludedRows8372)
        assertFalse(early.granted)
        // The same frozen predictions can be evaluated once their actual four-hour outcomes are available.
        val mature = CortexAuthority8366.test8372(sample, 1.5, after(sample))
        assertEquals(400, mature.oosN)
        assertTrue(mature.granted)
        assertEquals(0, mature.excludedRows8372)
    }

    @Test fun unseenFutureWinnersDoNotChangeTodaysEvidenceOrWarmup() {
        val sample = rows()
        val now = after(sample)
        val future = sample.take(100).map { it.copy(atMs = it.atMs + 1L, netPct = 100_000.0,
            frozenBucket = strong, outcomeAvailableAtMs = now + 4L * 3_600_000L) }
        val expected = CortexAuthority8366.test8372(sample, 1.5, now)
        val actual = CortexAuthority8366.test8372(sample + future, 1.5, now)
        assertEquals(100, actual.excludedRows8372)
        assertEquals(expected, actual.copy(excludedRows8372 = 0))
    }

    @Test fun missingOrImpossibleProvenanceIsCountedAndNotInvented() {
        val sample = rows()
        val now = after(sample)
        val r = sample.first()
        val invalid = listOf(
            r.copy(frozenBucket = null), r.copy(frozenBucket = 99),
            r.copy(outcomeAvailableAtMs = 0L), r.copy(outcomeAvailableAtMs = r.atMs - 1L),
            r.copy(atMs = now + 1L, outcomeAvailableAtMs = now + 2L),
            r.copy(score = Double.NaN), r.copy(netPct = Double.POSITIVE_INFINITY),
        )
        val expected = CortexAuthority8366.test8372(sample, 1.5, now)
        val actual = CortexAuthority8366.test8372(sample + invalid, 1.5, now)
        assertEquals(invalid.size, actual.excludedRows8372)
        assertEquals(expected, actual.copy(excludedRows8372 = 0))
    }

    @Test fun runtimeRowsAreChronologicalAndLegacyDiagnosticRowsStaySeparate() {
        val sample = rows()
        val expected = CortexAuthority8366.test8372(sample, 1.5, after(sample))
        assertEquals(expected, CortexAuthority8366.test8372(sample.reversed(), 1.5, after(sample)))
        val legacy = sample.map { CortexAuthority8366.Row8366(it.atMs, it.score, it.netPct) }
        val diagnostic = CortexAuthority8366.test8366(legacy, 1.5)
        assertTrue(diagnostic.granted)
        assertEquals("RANK_QUANTILES_8366", diagnostic.cohort)
        val current = CortexAuthority8366.test8372(legacy, 1.5, after(sample))
        assertEquals(0, current.oosN)
        assertEquals(legacy.size, current.excludedRows8372)
        assertFalse(current.granted)
    }

    @Test fun rankAndSampleEvidenceAreNotRelaxedByUsingActualVerdicts() {
        val inverted = rows { x -> -(((x - 50.0) / 5.0) * 2.0 + 1.0) }
        val bad = CortexAuthority8366.test8372(inverted, 1.5, after(inverted))
        assertEquals(CortexAuthority8366.Verdict8366.NO_RANK, bad.verdict)
        assertFalse(bad.granted)
        val thin = rows(80)
        val insufficient = CortexAuthority8366.test8372(thin, 1.5, after(thin))
        assertEquals(CortexAuthority8366.Verdict8366.INSUFFICIENT_OOS, insufficient.verdict)
        assertFalse(insufficient.granted)
    }

    @Test fun runtimeSizingAndAuthorityCacheAreWiredToTheSameCurrentCostCohort() {
        val source = java.io.File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        val sizing = source.substringAfter("private fun cortexStake7893(").substringBefore("private fun provenEvidenceStake7948(")
        assertTrue(sizing.contains("gradeBook8144.bucketStats8372(a.lane)[CortexScoreboard7885.Bucket.STRONG.ordinal]"))
        assertTrue(sizing.contains("val proof = authorityTest8366(a.lane)"))
        assertTrue(sizing.contains("!proof.granted || proof.topN < 20 || !proof.topSe.isFinite()"))
        assertTrue(sizing.contains("kellyStakeSol(proof.topMean, variance, equitySol)"))
        assertFalse(sizing.contains("board.books"))
        assertFalse(sizing.contains("validatedEvidence8187("))
        val authority = source.substringAfter("private fun authorityTest8366(").substringBefore("private fun realisedStrong8366(")
        assertTrue(authority.contains("|offset=\$offset|cost=\$cost"))
        assertTrue(authority.contains("it.gradeRevision == gradeBook8144.revision"))
        assertTrue(authority.contains("scoreVersion = 1, setup = setup"))
    }
}
