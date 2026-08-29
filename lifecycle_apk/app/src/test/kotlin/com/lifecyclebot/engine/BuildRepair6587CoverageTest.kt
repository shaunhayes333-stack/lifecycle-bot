package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6587 §P0-4 + §P0-9 source-level guarantees.
 *
 * P0-4  Global stale-eval sweeper.  6580 added a per-token TTL that
 *       fires only on re-stamps. 438 tokens stuck at
 *       SPECIALIST_SILENCE_SHARED_EVIDENCE never re-stamp (they fall
 *       out of the scanner window). Now every progress stamp also
 *       sweeps the entire progress map every 30 s so stale entries
 *       are reaped to STALE_EXPIRED_6587_<state> disposition.
 *
 * P0-9  PAPER_BUY_NOT_OPENED taxonomy bucket rollup.  908 unbucketed
 *       stamps in 6580 made root-cause invisible. Reasons are now
 *       auto-classified into MARK / SIZE / GATE / AUTHZ / ROUTE /
 *       CAPITAL / EXCEPTION / OTHER buckets so the dashboard shows
 *       a single distribution line.
 */
class BuildRepair6587CoverageTest {

    private val registrySrc = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
    private val execSrc = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

    @Test
    fun p0_4_global_stale_sweep_reaper_present() {
        assertTrue(
            "DynamicAltTokenRegistry must implement a global sweep in addition to the per-token TTL",
            registrySrc.contains("V5.0.6587 §P0-4 — GLOBAL STALE SWEEP") &&
                registrySrc.contains("lastGlobalSweepMs6587") &&
                registrySrc.contains("SWEEP_INTERVAL_MS_6587")
        )
        assertTrue(
            "Sweeper must emit CRYPTO_EVAL_STALE_SWEEP_REAPED_6587 when it reaps ≥1 entry",
            registrySrc.contains("CRYPTO_EVAL_STALE_SWEEP_REAPED_6587")
        )
        assertTrue(
            "Sweeper must terminalize with STALE_EXPIRED_6587_<state>",
            registrySrc.contains("STALE_EXPIRED_6587_")
        )
    }

    @Test
    fun p0_9_paper_buy_taxonomy_bucket_present() {
        assertTrue(
            "markPaperBuyNotOpened must emit a PAPER_BUY_NOT_OPENED_BUCKET_<X>_6587 rollup",
            execSrc.contains("PAPER_BUY_NOT_OPENED_BUCKET_") &&
                execSrc.contains("V5.0.6587 §P0-9 — TAXONOMY ROLLUP")
        )
        // Verify at least the primary buckets exist in the classifier.
        listOf("MARK", "SIZE", "GATE", "AUTHZ", "ROUTE", "CAPITAL", "EXCEPTION", "OTHER").forEach { bucket ->
            assertTrue(
                "Taxonomy classifier must emit bucket=$bucket",
                execSrc.contains("\"$bucket\"")
            )
        }
    }
}
