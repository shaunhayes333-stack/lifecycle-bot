package com.lifecyclebot.engine

import com.lifecyclebot.network.PumpFunWS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8657 — the 8655 edge export held ~96 PumpPortal launches that all read ~+2,9xx % at 5 min from a ~$2,450 cap,
 * none a pump.fun mint and none fillable: other launchpads' frames were priced with pump.fun curve math and their keys
 * decoded with pump.fun's account layout. Pump.fun curve math is now applied to pump.fun curve frames only.
 */
class Aate8657PumpCurveOnlyTest {
    @Test fun only_pump_fun_curve_frames_are_priced_as_pump_curves() {
        assertTrue(PumpFunWS.pumpCurveFrame8657("", "AbCdEfpump"))
        assertTrue(PumpFunWS.pumpCurveFrame8657("pump", "AbCdEfpump"))
        assertFalse("another launchpad", PumpFunWS.pumpCurveFrame8657("bonk", "AbCdEfbonk"))
        assertFalse("another launchpad on a pump-suffixed mint", PumpFunWS.pumpCurveFrame8657("launchlab", "AbCdEfpump"))
        assertFalse("the export's mints: no pool named, not a pump.fun mint", PumpFunWS.pumpCurveFrame8657("", "5vDHkZrsVZGX4ZrGcxauUmDLvURNB29jCmeX3CQC2W4K"))
        assertFalse("PumpSwap frames are not curve frames", PumpFunWS.pumpCurveFrame8657("pump-amm", "AbCdEfpump"))
    }

    @Test fun the_create_and_trade_frames_and_the_curve_reader_are_gated() {
        val ws = File("src/main/kotlin/com/lifecyclebot/network/PumpFunWS.kt").readText()
        assertTrue(ws.contains("if (!pumpCurveFrame8657(j.optString(\"pool\", \"\"), mint)) { nonPumpCurveFrame8657(\"TRADE\"); return }"))
        assertTrue(ws.contains("val priceSol0 = if (curveFrame8657 && vSol0.isFinite()"))
        assertTrue(ws.contains("if (curveFrame8657) try {\n                            PumpCurveKeys7269.remember(mint, j.optString(\"bondingCurveKey\", \"\"))"))
        val fan = File("src/main/kotlin/com/lifecyclebot/network/ParallelMarkFanout7088.kt").readText()
        assertTrue(fan.contains("val pumpLike7392 = explicitCurve7392 || m.endsWith(\"pump\", ignoreCase = true)"))
        assertEquals(0, Regex("PumpCurveKeys7269\\.keyFor\\(m\\) != null \\|\\|\\n").findAll(fan).count())
    }
}
