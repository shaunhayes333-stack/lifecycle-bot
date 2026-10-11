package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.StopLearner8040
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8042 — the crypto lane faces net edge; the stop learner respects the runner floor. */
class Aate8042CryptoNetEdgeTest {
    @Test fun runnerFloorBoundsTheLearnedStop() {
        val c = StopLearner8040.CANDIDATES_8040
        // Bleeding paths would pick the tightest stop — but a runner lane's floor is 15%.
        val rows = List(StopLearner8040.MIN_N_8040 + 5) { DoubleArray(c.size) { i -> -c[i] } }
        val pick = StopLearner8040.decide8040(rows, StopLearner8040.nearest8040(18.0), 15.0)
        assertTrue(pick == null || c[pick.first] >= 15.0)
    }

    @Test fun cryptoEntriesFaceNetEdge() {
        val ct = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(ct.contains("NetEdge8030.cryptoEntryRefusal8042(signal.priceChange24h, finalSize, candidate.liquidityUsd)"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/NetEdge8030.kt").readText().contains("NET_EDGE_CRYPTO_TOO_SLOW_8042"))
    }
}
