package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * V5.0.6606 — Bot-loop main-thread ANR relief (operator directive on the
 * post-V5.0.6604 forensic dump).
 *
 * Operator's dump captured:
 *   * Avg cycle ms: 9470 (target ~5000)
 *   * Max cycle ms: 24970
 *   * Recent 10 cycles including 17845ms and 16097ms
 *   * Sentinel: "MECHANICAL_FAULT/ui/reporting: Main-thread stalls/ANR
 *     hints active while runtime is trading; UI/report rendering can
 *     steal cycles and distort performance diagnosis."
 *   * "EXIT sweep starts but never completes/timeouts — worker may be
 *     wedged before watchdog ownership logs."
 *
 * REPAIR §BOT_LOOP_TOP_SIDE_EFFECT_OFFLOAD — syncPaperCapitalAuthority6448
 * fires on every bot-loop iteration. Its per-cycle SharedPreferences write
 * (PaperWalletStore.persist) and ForensicLogger.lifecycle disk emit were
 * running on the loop coroutine. Both moved to AppDispatchers.sideEffect
 * so the loop never blocks on XML flush or forensic file I/O. Reads
 * (cashSol) remain in-line (cheap atomic).
 */
class Aate6606BotLoopAnrReliefCoverageTest {

    @Test
    fun aate6606_sync_paper_capital_offloads_side_effects() {
        val bot = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/BotService.kt"
        ).readText()
        val start = bot.indexOf("private fun syncPaperCapitalAuthority6448(source: String)")
        assertTrue("syncPaperCapitalAuthority6448 must exist", start >= 0)
        val end = bot.indexOf("private fun", start + 10).let { if (it < 0) bot.length else it }
        val body = bot.substring(start, end)
        assertTrue(
            "V5.0.6606: syncPaperCapitalAuthority6448 must offload PaperWalletStore.persist to AppDispatchers.sideEffect",
            body.contains("GlobalScope.launch(com.lifecyclebot.util.AppDispatchers.sideEffect)") &&
                body.contains("PaperWalletStore.persist(applicationContext, ledgerCash)")
        )
        assertTrue(
            "V5.0.6606: per-cycle ForensicLogger emit must be inside the sideEffect launch, not on the loop coroutine",
            body.indexOf("GlobalScope.launch") < body.indexOf("PAPER_CAPITAL_AUTHORITY_SYNCED_6448")
        )
        // Cheap read (cashSol) must still be in-line so status.paperWalletSol
        // and CanonicalPositionAuthority6441.setPaperCash reflect the ledger
        // BEFORE the bot loop makes its next capital decision.
        assertTrue(
            "V5.0.6606: PaperCapitalAuthority6577.cashSol() read must stay in-line",
            body.indexOf("PaperCapitalAuthority6577.cashSol()") < body.indexOf("GlobalScope.launch")
        )
    }
}
