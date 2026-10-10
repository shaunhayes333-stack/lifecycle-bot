package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.network.GradTicks8018
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8018RunnerPlayTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aFormingRunIsPromotedBeforeTheScalpRung() {
        assertEquals(25.0, RunnerPlay8018.PROMOTE_MIN_PCT_8018, 0.0)
        assertTrue(RunnerPlay8018.promotes8018(30.0, 5 * 60_000L, firming = true, alreadyRunner = false, standingDown = false))
        assertFalse(RunnerPlay8018.promotes8018(20.0, 5 * 60_000L, true, false, false))          // below +25%
        assertFalse(RunnerPlay8018.promotes8018(30.0, 5 * 60_000L, false, false, false))         // chart not firming
        assertFalse(RunnerPlay8018.promotes8018(30.0, 45 * 60_000L, true, false, false))         // too late
        assertFalse(RunnerPlay8018.promotes8018(30.0, 5 * 60_000L, true, true, false))           // already a runner
        assertFalse(RunnerPlay8018.promotes8018(30.0, 5 * 60_000L, true, false, true))           // record stood down
        val s = src("engine/SpikeCapture7943.kt")
        assertTrue(s.indexOf("RunnerPlay8018.onMark8018(ts, gross, now7955)") in 1 until s.indexOf("RunnerGrab7967.tiersFor7967(ts)"))
    }

    @Test fun aConfirmedRunEarnsASecondEntry() {
        assertTrue(RunnerPlay8018.confirmsRun8018(150.0, 20 * 60_000L, firming = true, held = false))
        assertFalse(RunnerPlay8018.confirmsRun8018(80.0, 20 * 60_000L, true, false))
        assertFalse(RunnerPlay8018.confirmsRun8018(150.0, 5 * 60 * 60_000L, true, false))   // V5.0.8025: window 4 h
        assertFalse(RunnerPlay8018.confirmsRun8018(150.0, 20 * 60_000L, true, held = true))
        assertFalse(RunnerPlay8018.confirmsRun8018(150.0, 20 * 60_000L, firming = false, held = false))
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("RunnerPlay8018.onRun8018(o.mint, gross, nowMs - o.atMs, nowMs)"))
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("RunnerPlay8018.ticketActive8018(ts.mint, nowMs)"))
        assertTrue(src("engine/chart/ChartReader7950.kt").split("RunnerPlay8018.ticketActive8018(mint, nowMs)").size == 3)
        assertTrue(src("engine/FinalDecisionGate.kt").contains("RunnerPlay8018.ticketActive8018(mint)"))
        assertTrue(src("engine/truth/TraderSizingBridge6444.kt").contains("RunnerPlay8018.sizeMult8018(mintForSeal,"))
    }

    @Test fun bothPlaysStandDownOnTheirOwnRecord() {
        val st = CortexLedger7885.Stat()
        repeat(14) { st.add(-10.0, false) }
        assertFalse(RunnerPlay8018.standsDown8018(st))       // under 15 outcomes
        st.add(-10.0, false); st.add(5.0, false)
        assertTrue(RunnerPlay8018.standsDown8018(st))
        val good = CortexLedger7885.Stat()
        repeat(20) { good.add(if (it % 2 == 0) 40.0 else -20.0, false) }
        assertFalse(RunnerPlay8018.standsDown8018(good))
        assertTrue(src("engine/truth/CanonicalFinalizedTradeBus6464.kt").contains("RunnerPlay8018.onClose8018(env)"))
        RunnerPlay8018.resetForTest8018()
        assertTrue(RunnerPlay8018.statusLine().contains("promoted="))
    }

    @Test fun runnersPyramidOnConfirmation() {
        val e = src("engine/Executor.kt")
        assertTrue(e.contains("pos.isLongHold || runner8018"))
        assertTrue(e.contains("runner8018 = try { RunnerGrab7967.holdingRunner8018(ts) }"))
    }

    @Test fun pumpSwapVaultsPriceAGraduatedCoin() {
        // 1,000,000 tokens (6 dp) against 10 SOL -> 0.00001 SOL per token
        assertEquals(1e-5, GradTicks8018.price8018(1e12, 1e10, 6), 1e-15)
        assertTrue(GradTicks8018.price8018(0.0, 1e10, 6).isNaN())
        val acct = ByteArray(165); acct[64] = 0x10; acct[65] = 0x27   // 10,000
        assertEquals(10_000.0, GradTicks8018.tokenAmount8018(acct)!!, 0.0)
        assertTrue(GradTicks8018.basisAgrees8018(1.0, 0.5))
        assertFalse(GradTicks8018.basisAgrees8018(1.0, 0.2))
        assertTrue(GradTicks8018.basisAgrees8018(1.0, Double.NaN))
        fun b58(x: String): ByteArray = io.github.novacrypto.base58.Base58.base58Decode(x)
        val mint = "EcZndAERfzNhLirHwfsy44dAyPSZazjDU83pa6RApump"
        val wsol = "So11111111111111111111111111111111111111112"
        val bv = "AtSusre5nBcwbXP4epKAvULMAVTzzBFiabMkMvmNLAEY"
        val qv = "9Pnb3uEWtssrec9hLZ1Ftb7YYWk3GDDQNHtY6Yhux3DH"
        val pool = ByteArray(300)
        b58(mint).copyInto(pool, 43); b58(wsol).copyInto(pool, 75); b58(bv).copyInto(pool, 139); b58(qv).copyInto(pool, 171)
        assertEquals(bv to qv, GradTicks8018.vaults8018(pool, mint))
        assertNull(GradTicks8018.vaults8018(pool, wsol))
        val b = src("engine/BotService.kt")
        assertTrue(b.contains("GradTicks8018.sync8018(openMints.take(40).toSet())"))
        assertTrue(b.contains("applyPumpTradeMark7278(mint, priceSol, 0.0, \"PUMPSWAP_VAULT_WS_8018\", pool)"))
    }
}
