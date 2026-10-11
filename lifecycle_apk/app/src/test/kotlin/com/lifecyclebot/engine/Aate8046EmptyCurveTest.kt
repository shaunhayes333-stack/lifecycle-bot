package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.engine.truth.OnChainDepth8038
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8046 — a pump.fun curve nobody bought is not a market: real SOL, not the 30 SOL of virtual reserves. */
class Aate8046EmptyCurveTest {
    @Test fun realSolNotVirtual() {
        CurveReserves7951.resetForTest7951()
        val mint = "Dogo1111111111111111111111111111111111pump"
        // DOGO-like: the create frame's 30.2 SOL of virtual reserves = 0.2 SOL actually bought.
        CurveReserves7951.noteFrame7951(mint, 30.2, 1_066_000_000.0)
        assertEquals(0.2, CurveReserves7951.realSolIfActive8046(mint)!!, 1e-9)
        assertTrue(OnChainDepth8038.curveNobodyBought8046(0.2))
        // A curve buyers have put 12 SOL into is a market.
        assertFalse(OnChainDepth8038.curveNobodyBought8046(12.0))
        // Unknown is not judged here.
        assertFalse(OnChainDepth8038.curveNobodyBought8046(null))
        assertNull(CurveReserves7951.realSolIfActive8046("Unknown11111111111111111111111111111111pump"))
        CurveReserves7951.resetForTest7951()
        val lt = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiquidityTruth8037.kt").readText()
        assertTrue(lt.contains("CurveReserves7951.realSolIfActive8046(ts.mint)"))
    }
}
