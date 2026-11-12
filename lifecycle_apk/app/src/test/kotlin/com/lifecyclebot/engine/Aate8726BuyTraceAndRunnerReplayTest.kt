package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8726 — (a) a failed live buy keeps the facts it failed on; (b) the runner doctrine replayed over 2x / 5x / 10x / 25x
 * paths: what share of each move the ladder and the trail bank, and the path they do not survive.
 */
class Aate8726BuyTraceAndRunnerReplayTest {
    @Test fun aFailedBuyKeepsItsFacts() {
        val line = ExecutionAttemptLease.buyTerminalLine8726(0L, "DDmGnuvroxVrb7CSyjYcezFU8gnAfhKasg4ZZezmpump", "AMD", "TREASURY",
            "BUY_TERMINAL_LP_LOCK_BELOW_REQUIRED_8522", "lpLock=12.0 pool=7RD5YALHcM liq=125984")
        assertTrue(line.contains("DDmGnuvrox AMD TREASURY BUY_TERMINAL_LP_LOCK_BELOW_REQUIRED_8522 | lpLock=12.0 pool=7RD5YALHcM liq=125984"))
        val lease = File("src/main/kotlin/com/lifecyclebot/engine/ExecutionAttemptLease.kt").readText()
        assertTrue(lease.contains("if (side.equals(\"BUY\", true)) { count8702(buyFails8702, reason); noteBuyTerminal8726(n, mint, symbol, processor, reason) }"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText().contains("ExecutionAttemptLease.buyTerminalsForReport8726()"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryEconomics8329.kt").readText().contains("lastChase8726[ts.mint] = \"why=\$why move="))
    }

    /**
     * Replays a price path (multiples of entry) through the doctrine exactly as its pure rules state them: each ladder rung
     * of RunnerGrab7967.TIERS_7967 sells its share of what remains the first time the path reaches it, and the remainder
     * is sold at the doctrine line once the armed trail is breached. Returns the proceeds as a multiple of the stake
     * (before cost and slippage).
     */
    private fun replay(path: List<Double>, stopPct: Double = 35.0): Double {
        var remaining = 1.0; var banked = 0.0; var peak = 1.0
        val rungs = RunnerGrab7967.TIERS_7967.toMutableList()
        for (px in path) {
            peak = maxOf(peak, px)
            while (rungs.isNotEmpty() && px >= 1.0 + rungs.first().first / 100.0) {
                val (gain, share) = rungs.removeAt(0)
                banked += remaining * share * (1.0 + gain / 100.0); remaining *= (1.0 - share)
            }
            if (RunnerDoctrine8584.trailBreached8630("MOONSHOT", (px - 1.0) * 100.0, (peak - 1.0) * 100.0, stopPct))
                return banked + remaining * px
            if (px <= 1.0 - stopPct / 100.0) return banked + remaining * px   // the position's own stop
        }
        return banked + remaining * path.last()
    }

    private fun ramp(from: Double, to: Double, steps: Int = 40) = (1..steps).map { from + (to - from) * it / steps }

    @Test fun whatTheDoctrineBanksOfARunner() {
        val wasEnabled = RunnerDoctrine8584.enabled8584
        RunnerDoctrine8584.enabled8584 = true
        try {
            // straight runs that then collapse: the ladder and the 45 % give-back trail bank more than half of the peak
            val two = replay(ramp(1.0, 2.0) + ramp(2.0, 0.3))
            val five = replay(ramp(1.0, 5.0) + ramp(5.0, 0.3))
            val ten = replay(ramp(1.0, 10.0) + ramp(10.0, 0.3))
            val twentyFive = replay(ramp(1.0, 25.0) + ramp(25.0, 0.3))
            assertEquals("2x peak: a quarter sold at 2x, the rest at the 1.5x floor", 1.62, two, 0.05)
            assertTrue("5x peak banks 2.7x+ (55 % of the peak): $five", five in 2.7..3.1)
            assertTrue("10x peak banks 6x+: $ten", ten in 6.0..6.8)
            // KNOWN LIMIT: the +900 % rung sells two thirds of what is left, so a fifth of the position rides past 10x and
            // a 25x move banks about 8x (a third of the peak). The ladder is not graded on anything larger than 10x.
            assertTrue("25x peak banks about 8x: $twentyFive", twentyFive in 7.5..8.5)
            // a sub-2x winner keeps the room its stop gave it (8719): a +60 % peak that dips 30 % and recovers is still held
            val dipped = replay(ramp(1.0, 1.6) + ramp(1.6, 1.15) + ramp(1.15, 2.0) + ramp(2.0, 0.3))
            assertTrue("held through the dip to the 2x rung: $dipped", dipped > 1.5)
            // KNOWN LIMIT, recorded not hidden: a runner that gives back half on its way up is sold on the way. 3x, back
            // to 1.5x, then 10x: the trail sells everything at 1.65x. The 45 % give-back (and the +25 % arming peak) are
            // hand numbers with no forward record behind them; no price-path data in the captures can set them.
            val shaken = replay(ramp(1.0, 3.0) + ramp(3.0, 1.5) + ramp(1.5, 10.0))
            assertTrue("a 50 % shake-out at 3x ends the position near 1.7x of a 10x move: $shaken", shaken in 1.6..1.85)
        } finally { RunnerDoctrine8584.enabled8584 = wasEnabled }
    }
}
