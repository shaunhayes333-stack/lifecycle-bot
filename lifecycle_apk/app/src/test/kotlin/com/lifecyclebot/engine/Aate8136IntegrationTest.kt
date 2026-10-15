package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8136 — cross-workstream wiring: exit-grade due split, early authority-proof prefetch. */
class Aate8136IntegrationTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun exitGradesSplitNotYetDueFromOverdue() {
        val s = src("engine/cortex/CortexExit7897.kt")
        assertTrue(s.contains("internal fun dueSplit8136(reads: Collection<Pair<Long, Boolean>>, nowMs: Long): Triple<Int, Int, Int>"))
        assertTrue(s.contains("notYetDue=\$notDue dueInGrace=\$inGrace overdue=\$overdue"))
        assertTrue(s.contains("grades8136: \${dueLine8136(System.currentTimeMillis())}"))
    }

    @Test fun aCredibleBuyProposalPrefetchesItsAuthorityProof() {
        val bot = src("engine/BotService.kt")
        val gate = bot.indexOf("if (!ts.position.isOpen && decision.finalSignal == \"BUY\" && canProposeEarly &&")
        val prefetch = bot.indexOf("AuthorityProofPrefetch8136.prefetchIfLive8136(ts.mint)")
        assertTrue(gate in 0 until prefetch && prefetch - gate < 600)
    }
}
