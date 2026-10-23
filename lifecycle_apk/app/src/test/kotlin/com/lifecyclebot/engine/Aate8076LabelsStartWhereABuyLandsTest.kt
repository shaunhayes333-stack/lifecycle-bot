package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8076 — labels start at the first reachable mark; fresh books; the lifecycle hold at the lowest sell door. */
class Aate8076LabelsStartWhereABuyLandsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun labelEntryIsRebased() {
        val f = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(f.contains("REBASE_MIN_MS_8076 = 5_000L"))
        assertTrue(f.contains("if (!o.rebased8076 && age >= REBASE_MIN_MS_8076) {"))
        // superseded by V5.0.8350 — grades price on the depth a curve trades against (allInCostPct8350); fresh books cells_8350
        assertTrue(f.contains("CELLS_KEY_7946 = \"cells_8350\""))
        assertTrue(src("engine/truth/HiveEdge8000.kt").contains("\"hive_edge_8076\""))
    }

    @Test fun doSellHoldsLifecyclePositions() {
        val e = src("engine/Executor.kt")
        val door = e.substringAfter("internal fun doSell(").substringBefore("val paperCloseAuthorityActive")
        assertTrue(door.contains("LifecycleHold8065.defer8065(ts, reason)"))
    }

    @Test fun bootRecoveryOnlyAsksForHeldCoins() {
        assertTrue(src("engine/truth/LiveBootRecovery8074.kt").contains("WalletAccountCache.snapshot(ttlMs = 180_000L)"))
    }
}
