package com.lifecyclebot.engine

import com.lifecyclebot.engine.lab.StrategyLab8755
import com.lifecyclebot.engine.lab.StrategyLab8755.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8831 — the miner counts each coin once per box without copying the rows or hashing their mints again. */
class Aate8831MinerAllocationTest {
    private fun rows(): List<Row> {
        val r = java.util.Random(8831)
        return (0 until 3_000).map { k ->
            val mint = if (k % 17 == 0) "" else "M${r.nextInt(400)}"
            Row(k.toLong(), if (k % 2 == 0) "MOONSHOT" else "EXPRESS", "SRC${k % 3}", r.nextGaussian() * 30.0, doubleArrayOf(r.nextDouble() * 100.0), mint)
        }
    }

    @Test fun eachBoxCountsTheSameCoinsAsTheCopyingCount() {
        val all = rows()
        val ix = StrategyLab8755.CoinIndex8831(all)
        val every = all.indices.toList().toIntArray()
        for ((lo, hi) in listOf(0.0 to 20.0, 20.0 to 60.0, 55.0 to 100.0, 0.0 to 100.0)) {
            val keep: (Row) -> Boolean = { it.f[0] in lo..hi }
            val ref = StrategyLab8755.perCoin8811(all.filter(keep))
            val acc = StrategyLab8755.accOver8831(all, ix, every, keep)
            assertEquals(ref.size, acc.n); assertEquals(ref.sum(), acc.sum, 1e-6)
            // a scope (one lane) as indices
            val lane = all.indices.filter { all[it].lane == "EXPRESS" }.toIntArray()
            val refLane = StrategyLab8755.perCoin8811(all.filter { it.lane == "EXPRESS" && keep(it) })
            val accLane = StrategyLab8755.accOver8831(all, ix, lane, keep)
            assertEquals(refLane.size, accLane.n); assertEquals(refLane.sum(), accLane.sum, 1e-6)
        }
    }

    @Test fun theMinerNoLongerFiltersPerBox() {
        val lab = File("src/main/kotlin/com/lifecyclebot/engine/lab/StrategyLab8755.kt").readText()
        val miner = lab.substringAfter("private fun mine(disc: List<Row>").substringBefore("private fun sourceFactors(")
        assertTrue(miner.contains("accOver8831(disc, ix, idx)"))
        assertTrue(!miner.contains("perCoin8811(scope.filter"))
    }
}
