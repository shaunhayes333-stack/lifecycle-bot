package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OnChainDepth8038
import com.lifecyclebot.engine.truth.RunnerCapture8017
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8047 — launch-cap coins refused on every launchpad; admitted runners name what stopped them; positive admits are sized. */
class Aate8047GoodTradesTest {
    @Test fun launchCapAnyLaunchpad() {
        // PUMPTg: $3,218 cap at $109.67 SOL = 29.3 SOL — the opening cap of a launch curve.
        assertTrue(OnChainDepth8038.atLaunchCap8047(3_218.0 / 109.67))
        // A coin buyers have taken to $23,652 (MINERS) is a market.
        assertFalse(OnChainDepth8038.atLaunchCap8047(23_652.0 / 109.67))
        // Unknown cap is not judged here.
        assertFalse(OnChainDepth8038.atLaunchCap8047(Double.NaN))
        assertFalse(OnChainDepth8038.atLaunchCap8047(0.0))
    }

    @Test fun missedRunnerNamesItsStop() {
        assertEquals("WATCH_FIRST_8019_BAND_NEGATIVE", RunnerCapture8017.missedWhy8047("WATCH_FIRST_8019_BAND_NEGATIVE", "SIZE_CONVICTION_6909"))
        assertEquals("ADMITTED>SIZE_NO_EDGE_7877", RunnerCapture8017.missedWhy8047("", "SIZE_NO_EDGE_7877"))
        assertEquals("ADMITTED>NO_BUY_ATTEMPT", RunnerCapture8017.missedWhy8047("", null))
    }

    @Test fun wiring() {
        val sizer = File("src/main/kotlin/com/lifecyclebot/engine/truth/OrderSizeResolver6441.kt").readText()
        assertTrue(sizer.contains("LiveEdgeGate7877.positiveAdmit7975(mint)"))
        assertTrue(sizer.contains("RunnerCapture8017.noteDownstream8047(mint"))
        val exec = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("RunnerCapture8017.noteDownstream8047(ts.mint, reason)"))
        val lt = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiquidityTruth8037.kt").readText()
        assertTrue(lt.contains("OnChainDepth8038.needDepth8047(ts)"))
    }
}

/** V5.0.8055 — a missed runner names the gate that stopped it, not another lane's native opinion. */
class Aate8055RunnerMissReasonTest {
    @Test fun gateRefusalBeatsNativeOpinion() {
        assertTrue(RunnerCapture8017.betterMissReason8055("NATIVE_TREASURY_ROLE_FLOOR", "WATCH_FIRST_7994_THIN_CELL"))
        assertTrue(RunnerCapture8017.betterMissReason8055("", "CORTEX_7885_C3_PROVEN_NEGATIVE_EDGE_SHITCOIN"))
        assertFalse(RunnerCapture8017.betterMissReason8055("WATCH_FIRST_7994_THIN_CELL", "NATIVE_MCAP_TOO_LOW"))
        assertFalse(RunnerCapture8017.betterMissReason8055("WATCH_FIRST_7994_THIN_CELL", "NET_EDGE_COIN_TOO_SLOW_8035"))
    }
}
