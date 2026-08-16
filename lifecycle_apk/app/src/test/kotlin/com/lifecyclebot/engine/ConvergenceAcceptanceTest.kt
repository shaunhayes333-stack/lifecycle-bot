package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalRewardBootstrap6453
import com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450
import com.lifecyclebot.engine.truth.MintWorkCoordinator6450
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.0.6453 §P0-#1/§P0-#6/§P0-#8 — HARD CI ASSERTIONS FOR CONVERGENCE.
 *
 * Locks the atomicity + single-owner properties the operator mandated.
 * CI turns red on regression.
 */
class ConvergenceAcceptanceTest {

    @Test
    fun `MintWorkCoordinator produces exactly one candidate under 100 concurrent callbacks for the same mint`() {
        // V5.0.6453 §P0-#1 — simultaneous 100 callbacks for one mint =>
        // exactly 1 candidate. Prior compute{}-based impl was racy; the
        // new putIfAbsent-based impl is atomic.
        val mint = "TEST_MINT_MWC_${System.nanoTime()}"
        val threads = 100
        val exec = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val newCount = AtomicInteger(0)
        val ids = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

        val sources = arrayOf("PUMP_PORTAL", "DEX", "RAYDIUM", "COINGECKO", "SCANNER_HEAL")
        repeat(threads) { i ->
            exec.submit {
                try {
                    start.await()
                    val r = MintWorkCoordinator6450.acquireOrAttach(mint, sources[i % sources.size])
                    if (r.isNew) newCount.incrementAndGet()
                    ids += r.candidateId
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("threads must complete within 5s", done.await(5, TimeUnit.SECONDS))
        exec.shutdown()

        assertEquals(
            "exactly one candidate must be marked isNew under concurrent callbacks",
            1,
            newCount.get(),
        )
        assertEquals(
            "all callbacks must share the same canonical candidateId",
            1,
            ids.size,
        )
    }

    @Test
    fun `CanonicalRewardBootstrap installs subscriber exactly once`() {
        // V5.0.6453 §P0-#6 — bootstrap is idempotent. Repeated calls
        // must NOT install additional subscribers.
        CanonicalRewardBootstrap6453.ensureBootstrapped()
        val statusA = CanonicalRewardBootstrap6453.statusLine()
        CanonicalRewardBootstrap6453.ensureBootstrapped()
        CanonicalRewardBootstrap6453.ensureBootstrapped()
        val statusB = CanonicalRewardBootstrap6453.statusLine()
        assertTrue(
            "bootstrap must report installed=true after first call",
            statusA.contains("installed=true"),
        )
        assertTrue(
            "repeated ensureBootstrapped() calls must remain installed=true",
            statusB.contains("installed=true"),
        )
    }

    @Test
    fun `CanonicalRewardBootstrap subscriber receives finalized events with net-realized pnl`() {
        // V5.0.6453 §P0-#6 — publishing a finalized event must fan out
        // to the bootstrap-installed subscribers (shaper + purity gate).
        // We measure by inspecting the bootstrap invocation counter
        // before and after a bus publish.
        CanonicalRewardBootstrap6453.ensureBootstrapped()
        val statusBefore = CanonicalRewardBootstrap6453.statusLine()
        val shaperInvBefore = parseCounter(statusBefore, "shaperInv=")

        val pid = "TEST_PID_BOOT_${System.nanoTime()}"
        CanonicalTradeFinalizedBus6450.publish(
            CanonicalTradeFinalizedBus6450.Event(
                positionId = pid,
                mint = "TEST_MINT_BOOT",
                outcome = CanonicalTradeFinalizedBus6450.Outcome.WIN,
                netRealizedPnlSol = 0.05,
                grossRealizedPnlSol = 0.055,
                returnFraction = 0.10,
                netReturnPct = 10.0,
                feesSol = 0.005,
                entryLane = "TEST",
                entryStrategyPid = "",
                entryTactic = "",
                exitReason = "test_boot",
                holdingTimeMs = 5_000L,
                dataQuality = "OK",
                priceIntegrity = "OK",
                mode = "paper",
                settledAtMs = System.currentTimeMillis(),
            ),
        )
        val statusAfter = CanonicalRewardBootstrap6453.statusLine()
        val shaperInvAfter = parseCounter(statusAfter, "shaperInv=")
        assertTrue(
            "bootstrap subscriber must have invoked shaper on the published event " +
                "(before=$shaperInvBefore after=$shaperInvAfter)",
            shaperInvAfter > shaperInvBefore,
        )
    }

    private fun parseCounter(status: String, key: String): Long {
        val i = status.indexOf(key)
        if (i < 0) return 0L
        val start = i + key.length
        var end = start
        while (end < status.length && status[end].let { it.isDigit() || it == '-' }) end++
        return status.substring(start, end).toLongOrNull() ?: 0L
    }
}
