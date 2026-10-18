package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 8198 — paper rides the runner plan live would. RunnerGrab7967.deferSell7967 returned null for every paper position, so a
 * paper runner took each profit lock / trail / early-rug backstop live defers and the Cortex learned that runners fail.
 */
class Aate8198PaperRunnerParityTest {
    private val entryMs = System.currentTimeMillis() - 60_000L

    private fun token(mint: String, style: String, paper: Boolean, peakPct: Double = 0.0, lane: String = "MOONSHOT", price: Double = 2.0) =
        TokenState(mint = mint, lastPrice = price, position = Position(qtyToken = 1_000_000.0, entryPrice = 1.0, entryTime = entryMs,
            tradingMode = lane, isPaperPosition = paper, peakGainPct = peakPct, costSol = 0.1,
            entryPolicySnapshot = "style=$style;entryTactic=BREAKOUT"))

    @Test fun paperSealedRunnerIsRecognisedExactlyWhereLiveIs() {
        val paper = token("pr8198a", "BREAKOUT_RUNNER", paper = true)
        val live = token("pr8198b", "BREAKOUT_RUNNER", paper = false)
        assertTrue(RunnerGrab7967.pairedPaperRunner8198(paper))
        assertFalse(RunnerGrab7967.pairedPaperRunner8198(live))                 // the paper-only door
        assertTrue(TradeExitContract8077.runnerFromEntry8104(live))
        assertEquals(RunnerLive8153.basis8153(live), RunnerLive8153.basis8153(paper))
    }

    @Test fun aPaperScalpIsNeverHeld() {
        val scalp = token("pr8198c", "QUICK_FLIP", paper = true)
        assertFalse(RunnerGrab7967.pairedPaperRunner8198(scalp))
        assertNull(RunnerGrab7967.deferSell7967(scalp, "PROFIT_LOCK_TRAIL"))
        val cash = token("pr8198d", "BREAKOUT_RUNNER", paper = true, lane = "CASHGEN")
        assertFalse(RunnerGrab7967.pairedPaperRunner8198(cash))                 // a scalp lane never hosts a runner
    }

    @Test fun integrityAndCapitalExitsStillPassForPaperRunners() {
        val paper = token("pr8198e", "BREAKOUT_RUNNER", paper = true)
        for (reason in listOf("RUG_DETECTED", "DEV_SOLD", "LIQUIDITY_DRAIN", "HONEYPOT", "FREEZE_AUTHORITY", "MANUAL_SELL", "REFLEX_LIQ_DRAIN", "LP_PULL")) {
            assertNull(reason, RunnerGrab7967.deferSell7967(paper, reason))
        }
    }

    @Test fun theSoftExitDoorNoLongerExcludesPaperAndTheTopUpDoorStaysLiveOnly() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/RunnerGrab7967.kt").readText()
        assertFalse(src.contains("if (ts.position.isPaperPosition || !holding7967(ts, nowMs)) return null"))
        assertTrue(src.contains("!pairedPaperRunner8198(ts)) return null"))
        // the buying door places a real top-up through live wallet/exposure checks: unchanged, live only
        assertTrue(src.contains("if (ts.position.isPaperPosition || !holding7967(ts, nowMs)) return false"))
    }
}
