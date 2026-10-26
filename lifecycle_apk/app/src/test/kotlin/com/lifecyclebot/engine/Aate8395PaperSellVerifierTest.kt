package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * V5.0.8395 — device P0: since 8392 ART rejected Executor.paperSell ("[0x4C95] copy1 v2<-v447 type=Undefined") so the
 * Executor class failed to verify and the service never started (runtime smoke SERVICE_BOOTSTRAP_FAILED_6516). The
 * post-close learning tail now runs in two compiled methods, each under the device-proven budget, in the same order.
 */
class Aate8395PaperSellVerifierTest {
    private val ex = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

    private fun bodyLines(start: String): Int {
        val i = ex.indexOf(start); assertTrue(start, i >= 0)
        var d = 0; var lines = 0; var seen = false
        for (k in i until ex.length) {
            val ch = ex[k]
            if (ch == '{') { d++; seen = true } else if (ch == '}') { d--; if (seen && d == 0) return lines } else if (ch == '\n') lines++
        }
        return lines
    }

    @Test fun paperSellAndItsTailsStayUnderTheVerifierBudget() {
        val outer = bodyLines("fun paperSell(ts: TokenState, reason: String, identity: TradeIdentity?") -
            bodyLines("fun paperSellLearningTail8395() {") - bodyLines("fun paperSellLearningTailB8395() {")
        assertTrue("paperSell's own body: $outer", outer < 1_000)
        assertTrue(bodyLines("fun paperSellLearningTail8395() {") < 600)
        assertTrue(bodyLines("fun paperSellLearningTailB8395() {") < 600)
    }

    @Test fun aTokenSeenDyingThenVanishingIsStillBookedDead() {
        val f = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        val ex = f.substringAfter("private fun expired8395(").substringBefore("fun tick(priceFor0")
        assertTrue(ex.indexOf("Vanished7944.DEAD") in 0 until ex.indexOf("unavailable8395(key,o,\"OBSERVATION_DEADLINE_"))
        assertTrue(ex.contains("bookSixty7944(o,-100.0,-100.0,nowMs)"))
        assertEquals(com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.Vanished7944.DEAD,
            com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.classifyVanished7944(-80.0, 600_000L, 3_600_000L))
        assertEquals(com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.Vanished7944.GAP,
            com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.classifyVanished7944(Double.NaN, 0L, 3_600_000L))
    }

    @Test fun theTailsRunInOrderBeforeTheDoneMarker() {
        val ps = ex.substringAfter("fun paperSell(ts: TokenState, reason: String, identity: TradeIdentity?")
        val a = ps.indexOf("        paperSellLearningTail8395()\n        paperSellLearningTailB8395()")
        val done = ps.indexOf("ForensicLogger.lifecycle(\"PAPER_SELL_DONE\"")
        assertTrue(a in 0 until done)
        val tailA = ps.substringAfter("fun paperSellLearningTail8395() {").substringBefore("fun paperSellLearningTailB8395() {")
        assertTrue(tailA.contains("TradeLifecycle.classified(tradeId.mint, classification,"))
        assertTrue(tailA.trimEnd().endsWith("TradeLifecycle.clearProposalTracking(tradeId.mint)\n        }") ||
            tailA.contains("TradeLifecycle.clearProposalTracking(tradeId.mint)"))
        val tailB = ps.substringAfter("fun paperSellLearningTailB8395() {").substringBefore("        paperSellLearningTail8395()")
        assertTrue(tailB.contains("BotService.recentlyClosedMs[ts.mint] = System.currentTimeMillis()"))
        assertFalse(Regex("""\breturn\b(?!@)""").containsMatchIn(tailA + tailB))
    }
}
