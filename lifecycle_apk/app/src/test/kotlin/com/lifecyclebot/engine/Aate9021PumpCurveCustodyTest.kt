package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.network.PumpCurveKeys7269
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9021 — a pump.fun curve the program was seen trading stays in the program's custody between trades: the LP proof
 * no longer lapses 30 s after the last print (5.0.9020: LP_OWNERSHIP_PROOF_PENDING_8522=344, the gate allowed 0 of 137).
 * The exit depth, the price and the send keep the fresh reading.
 */
class Aate9021PumpCurveCustodyTest {
    private val now = 1_795_620_000_000L

    @Test fun aQuietCurveIsStillInTheProgramsCustody() {
        val id = "QuietCurve9021AAAAAAAAAAAAAAAAAAAAAAAAAApump"
        CurveReserves7951.noteFrame7951(id, 34.0, 1_000_000.0, now - 5L * 60_000L)     // 4 SOL real, last print 5 minutes ago
        assertFalse("not a fresh curve: exit depth and the send still wait for a read", LiquidityOwnership8522.pumpCurveLive9007(id, now))
        assertFalse(LiquidityOwnership8522.activeCurve8881(id, false, now).first)
        val (held, at) = LiquidityOwnership8522.pumpCurveCustody9021(id, false, now)
        assertTrue("the LP is in the pump.fun program's custody", held)
        assertEquals(PumpCurveKeys7269.canonicalCurveKey7392(id).orEmpty(), at)
        assertFalse("never for a migrated coin", LiquidityOwnership8522.pumpCurveCustody9021(id, true, now).first)
        assertFalse("past the window", LiquidityOwnership8522.pumpCurveCustody9021(id, false, now + LiquidityOwnership8522.PUMP_CUSTODY_MAX_AGE_MS_9021).first)
        PumpCurveKeys7269.markGraduated7392(id)
        assertFalse("a graduated coin's proof is its pool's burned LP, read elsewhere", LiquidityOwnership8522.pumpCurveCustody9021(id, false, now).first)
    }

    @Test fun whatProvedNothingBeforeStillProvesNothing() {
        assertFalse(LiquidityOwnership8522.pumpCurveCustody9021("NeverSeen9021AAAAAAAAAAAAAAAAAAAAAAAAAAApump", false, now).first)
        val thin = "ThinCurve9021AAAAAAAAAAAAAAAAAAAAAAAAAAApump"
        CurveReserves7951.noteFrame7951(thin, 30.3, 1_000_000.0, now - 60_000L)        // 0.3 SOL real: under the exit floor
        assertFalse(LiquidityOwnership8522.pumpCurveCustody9021(thin, false, now).first)
        assertFalse("the suffix is the pump.fun program's, not a name", LiquidityOwnership8522.pumpCurveCustody9021("NotAPumpMint9021AAAAAAAAAAAAAAAAAAAAAAAAAAA", false, now).first)
        assertFalse("the 30 s reading is unchanged", LiquidityOwnership8522.pumpCurveByStream9007(thin, now))
    }

    @Test fun theDoorTakesCustodyForTheLpQuestionAndAsksTheChainForTheRest() {
        val g = File("src/main/kotlin/com/lifecyclebot/engine/PreTradeHardGate.kt").readText()
        assertTrue(g.contains("else LiquidityOwnership8522.pumpCurveCustody9021(mint, ts.tokenMap.migratedOrGraduated, now)"))
        assertTrue(g.contains("ChainConfirm8950.confirm8950(ts, listOf(\"DEPTH\"), \"DECISION\", now)"))
        assertTrue("exit depth still needs the fresh curve", g.contains("else tokenMapLiquidity.executable || curveExitSafe8319"))
        val send = File("src/main/kotlin/com/lifecyclebot/engine/BuyRouteSafety8522.kt").readText()
        assertTrue("the send's own check is unchanged", send.contains("LiquidityOwnership8522.activeCurve8881(ts.mint, ts.tokenMap.migratedOrGraduated, now)"))
        assertEquals(6L * 60L * 60_000L, LiquidityOwnership8522.PUMP_CUSTODY_MAX_AGE_MS_9021)
    }
}
