package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AgeProof9001
import com.lifecyclebot.engine.truth.LanePocket8934
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9003 — a lane-pocket cell that names no age is not served LIVE to a coin whose age band the lane is measured to lose
 * in; a cell that names the coin's age always is. No refusal is added.
 */
class Aate9003PocketProofWhereEarnedTest {
    @Test fun aBandLosesOnlyOnEnoughLabelsWithItsUpperMeanUnderZero() {
        assertTrue(AgeProof9001.loses9003(1_200, -2.7, 0.8))      // MOONSHOT, an hour old or more, 25 Nov export
        assertFalse(AgeProof9001.loses9003(851, 14.7, 3.7))       // MOONSHOT under 15 minutes
        assertFalse(AgeProof9001.loses9003(99, -9.0, 1.0))        // too few labels to claim
        assertFalse(AgeProof9001.loses9003(500, -1.0, 1.5))       // inside one SE of zero
    }

    @Test fun theLaneReadFollowsItsLabels() {
        AgeProof9001.resetForTest9001()
        repeat(200) { AgeProof9001.note9001("EXPRESS", 3.0, if (it % 2 == 0) 50.0 else -10.0, true, true) }
        repeat(200) { AgeProof9001.note9001("EXPRESS", 3_000.0, if (it % 4 == 0) 4.0 else -6.0, true, true) }
        assertFalse(AgeProof9001.bandLoses9003("EXPRESS", 3.0))
        assertTrue(AgeProof9001.bandLoses9003("express", 3_000.0))
        assertFalse(AgeProof9001.bandLoses9003("EXPRESS", 100.0))        // a band with no labels claims nothing
        assertFalse(AgeProof9001.bandLoses9003("EXPRESS", Double.NaN))   // unknown age claims nothing
        assertFalse(AgeProof9001.bandLoses9003("CORE", 3_000.0))
        repeat(400) { AgeProof9001.note9001("EXPRESS", 3_000.0, 30.0, true, true) }   // the band starts paying
        assertFalse(AgeProof9001.bandLoses9003("EXPRESS", 3_000.0))
        AgeProof9001.resetForTest9001()
    }

    @Test fun anAgeBlindCellIsWithheldInALosingBandAndAnAgeNamedCellIsNot() {
        val coin = setOf("origin=OTHER", "liq=5-20k", "age=OVER_24H")
        assertTrue(LanePocket8934.fits9003(listOf("origin=OTHER"), coin, false))
        assertFalse(LanePocket8934.fits9003(listOf("origin=OTHER"), coin, true))
        assertFalse(LanePocket8934.fits9003(listOf("origin=OTHER", "liq=5-20k"), coin, true))
        assertTrue(LanePocket8934.fits9003(listOf("age=OVER_24H", "liq=5-20k"), coin, true))
        assertFalse(LanePocket8934.fits9003(listOf("age=UNDER_15M"), coin, false))     // still has to be the coin's own cell
    }

    @Test fun wired() {
        val p = File("src/main/kotlin/com/lifecyclebot/engine/truth/LanePocket8934.kt").readText()
        assertTrue(p.contains("val c = cells.orEmpty().firstOrNull { fits9003(it.conds, feats, blind9003) } ?: run {"))
        assertTrue(p.contains("val (e, h) = ext.firstOrNull { fits9003(it.first.conds, feats, blind9003) } ?: run {"))
        assertTrue(p.contains("!com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() && AgeProof9001.bandLoses9003(lane, ageMin)"))
        assertTrue(p.contains("LANE_POCKET_AGE_BLIND_NOT_SERVED_9003_"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 188. A pocket is proof only where it was earned (9003)"))
    }
}
