package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.SetupAuthority8535
import com.lifecyclebot.engine.cortex.SetupAuthority8535.Authority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8727 — a lane verdict's free text and a description of the coin are not entry events. */
class Aate8727SetupExecutabilityTest {
    private fun auth(id: String, bp: Double = Double.NaN, vol: Double = Double.NaN, tx: Double = Double.NaN, chg: Double = Double.NaN, run: Double = Double.NaN) =
        SetupAuthority8535.authorityOf8535(id, bp, vol, tx, chg, run)

    @Test fun aNativeReadyVerdictNeedsAnObservedWitness() {
        val id = SetupAuthority8535.NATIVE_READY_PREFIX_8727 + "MOONSHOT_NATIVE_ALLOW"
        assertEquals("NATIVE_READY_8270:", SetupAuthority8535.NATIVE_READY_PREFIX_8727)
        assertFalse("nothing observed: the name alone is no entry", SetupAuthority8535.executable8535(id, null))
        assertEquals("buyers unmeasured", Authority.SETUP_FORMING, auth(id, vol = 90.0, chg = 12.0))
        assertEquals("buyers measured, nothing accelerating", Authority.SETUP_FORMING, auth(id, bp = 62.0, chg = 0.5))
        assertEquals("the run already spent", Authority.SETUP_FORMING, auth(id, bp = 62.0, vol = 90.0, run = SetupAuthority8535.WITNESS_MAX_RUNUP_PCT_8535 + 1.0))
        assertEquals(Authority.SETUP_TRIGGERED, auth(id, bp = 62.0, chg = SetupAuthority8535.WITNESS_CHG5M_PCT_8535))
        assertEquals(Authority.SETUP_TRIGGERED, auth(id, bp = 62.0, tx = SetupAuthority8535.WITNESS_TX_VELOCITY_8535))
        // the forming-runner identity carries its own observed structure / tape read (RunnerGrab7967.formingNative8260)
        assertEquals(Authority.SETUP_TRIGGERED, auth("RUNNER_FORMING_8260"))
    }

    @Test fun theStructureReadsDescribeTheCoin() {
        assertEquals(Authority.TOKEN_QUALITY, auth("FRESH_DEEP_POOL_8720", bp = 70.0, vol = 90.0, chg = 10.0))
        assertEquals(Authority.TOKEN_QUALITY, auth("DISTRIBUTED_HOLDERS_8720", bp = 70.0, vol = 90.0, chg = 10.0))
        assertEquals(Authority.SETUP_FORMING, auth("EARLY_CURVE_AT_HIGH_8720"))
        assertEquals(Authority.SETUP_TRIGGERED, auth("EARLY_CURVE_AT_HIGH_8720", bp = 60.0, chg = 5.0))
        assertTrue("FRESH_DEEP_POOL_8720" in SetupAuthority8535.QUALITY_ONLY_8535 && "EARLY_CURVE_AT_HIGH_8720" in SetupAuthority8535.NEAR_HIGH_8535)
    }
}
