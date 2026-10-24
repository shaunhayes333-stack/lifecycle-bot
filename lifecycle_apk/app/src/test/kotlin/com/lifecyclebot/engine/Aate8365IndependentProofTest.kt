package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8365: co-derived market cap cannot authenticate its own price.
 * Migration removes only the obsolete witness claim; every original raw return
 * and decision remains available to re-grade from genuinely independent proof.
 */
class Aate8365IndependentProofTest {
    @Test fun capOnlyMarkCannotBecomeChainWitness() {
        assertFalse(ForwardReturnLabeler7731.witnessed8324(0.003, 0.0, true))
        assertFalse(ForwardReturnLabeler7731.witnessed8324(0.003, 0.001, true))
        assertTrue(ForwardReturnLabeler7731.witnessed8324(0.003, 0.0028, false))
    }

    @Test fun legacyLedgerWitnessIsRequalifiedButRawGainSurvives() {
        val original = CortexLedger7885.labelStat8187()
        original.add(6_500.0, true, witnessed = true)
        original.add(-20.0, false)
        val rawSum = original.sum
        val oldWire = original.encode().substringBeforeLast(",")
        val migrated = CortexLedger7885.labelStat8187().also { it.decode(oldWire) }
        assertEquals(0.0, migrated.witnessed8324, 0.0)
        assertEquals(rawSum, migrated.sum, 0.0)
        assertEquals(original.n, migrated.n, 0.0)
        val current = CortexLedger7885.labelStat8187().also { it.decode(original.encode()) }
        assertEquals(1.0, current.witnessed8324, 0.0)
        assertEquals(rawSum, current.sum, 0.0)
    }

    @Test fun olderGradeRecordCannotCarryOldWitnessAcrossRestart() {
        val original = CortexGradeBook8144()
        val r = CortexGradeRecord8144('D', "first", "mint|MOONSHOT", 1_234_567L, "MOONSHOT", "RUN",
            "UP", "BREAKOUT", "LOW", "MC_LT10K", 2, 12.0, false, true, false)
        r.executablePct = 6_000.0
        r.observedNetPct = 6_000.0
        r.witnessed8324 = true
        assertTrue(original.addDecision(r))
        val wire = original.encode()
        val current = CortexGradeBook8144().also { it.decode(wire) }
        assertTrue(current.decision("first")!!.witnessed8324)
        assertEquals(6_000.0, current.decision("first")!!.executablePct, 0.0)
        // Pre-8365 rows ended at the witness flag (field 32), without field 33's independent-proof marker.
        // New append-only metadata means removing only the current last field no longer makes a legacy row.
        val legacyFields = wire.split('\u001F').take(33)
        assertEquals(33, legacyFields.size)
        assertEquals("the obsolete witness flag is still present in the fixture", "1", legacyFields.last())
        val legacy = CortexGradeBook8144().also { it.decode(legacyFields.joinToString("\u001F")) }
        assertFalse(legacy.decision("first")!!.witnessed8324)
        assertEquals(6_000.0, legacy.decision("first")!!.executablePct, 0.0)
        val currentWithoutLastAppend = CortexGradeBook8144().also { it.decode(wire.substringBeforeLast('\u001F')) }
        assertTrue("an absent newer optional field cannot erase independent proof", currentWithoutLastAppend.decision("first")!!.witnessed8324)
        assertEquals(6_000.0, currentWithoutLastAppend.decision("first")!!.executablePct, 0.0)
    }
}
