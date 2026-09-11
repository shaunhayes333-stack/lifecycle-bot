package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigDecimal

class Aate6735EntryAndWitnessRecoveryTest {
    private val now = 1_789_200_000_000L
    private fun mark(at: Long = now - 500L) = CanonicalPriceMark6522(
        mint = "Entry6735", pairId = "pool6735", baseMint = "Entry6735",
        quoteMint = "USD", source = "DEXSCREENER_PAIR_POLL", timestampMs = at,
        priceUsd = PriceUsd(BigDecimal("1.234567")), liquidityUsd = BigDecimal("24000"),
        purpose = CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE,
    )
    private fun snapshot(m: CanonicalPriceMark6522) = MintEntryMarketSnapshot.fromCanonicalMark6735(
        "Entry6735", m, 50000.0, "testDex", now,
    )

    @Before fun reset() {
        CausalFeedbackAuthority6715.resetForTest6715()
        ExitTelemetryStamper6732.resetForTest()
        StopLatencyClasses6464.resetForTest()
    }

    @Test fun `entry preserves the same provider price pool source and observation time`() {
        val m = mark()
        val s = requireNotNull(snapshot(m))
        assertEquals(m.priceUsd.value.toDouble(), s.priceUsd, 0.0)
        assertEquals(m.pairId, s.poolAddress)
        assertEquals(m.source, s.priceSource)
        assertEquals(m.timestampMs, s.capturedAtMs)
        assertEquals(m.liquidityUsd!!.toDouble(), s.liquidityUsd, 0.0)
    }

    @Test fun `optional market cap and mint route do not erase a fresh provider tuple`() {
        val m = mark().copy(pairId = "MINT_ROUTE:Entry6735")
        val s = requireNotNull(MintEntryMarketSnapshot.fromCanonicalMark6735(
            "Entry6735", m, 0.0, "MINT_ROUTE", now,
        ))
        assertTrue(s.valid)
        assertEquals(m.pairId, s.poolAddress)
        assertEquals(m.timestampMs, s.capturedAtMs)
        assertEquals(0.0, s.marketCapUsd, 0.0)
    }

    @Test fun `stale future and unstamped prices cannot become entry snapshots`() {
        assertNull(snapshot(mark(now - 120_001L)))
        assertNull(snapshot(mark(now + 5_001L)))
        assertNull(snapshot(mark(0L)))
        assertNotNull(snapshot(mark(now - 120_000L)))
    }

    @Test fun `wrong mint and exit purpose cannot supply entry economics`() {
        assertNull(snapshot(mark().copy(mint = "other")))
        assertNull(snapshot(mark().copy(baseMint = "other")))
        assertNull(snapshot(mark().copy(purpose = CanonicalMarkPurpose6570.EXIT_ECONOMIC)))
    }

    @Test fun `unknown depth and sentinel price stay outside canonical entry economics`() {
        assertNull(snapshot(mark().copy(liquidityUsd = null)))
        assertNull(snapshot(mark().copy(priceUsd = PriceUsd(BigDecimal("0.050250000")))))
    }

    @Test fun `feedback ticket cannot be reused by another mint`() {
        assertTrue(CausalFeedbackAuthority6715.stampDecision("mint-check", "mint-A", "PAPER", "QUALITY", 30))
        val wrong = CausalFeedbackAuthority6715.admit("mint-check", "mint-B", "PAPER", "QUALITY", 30)
        assertFalse(wrong.allowed)
        assertTrue(wrong.forceRevalidate)
    }

    @Test fun `ordinary score drift still preserves the stamped owner scope`() {
        assertTrue(CausalFeedbackAuthority6715.stampDecision("score-check", "mint-A", "PAPER", "QUALITY", 30))
        assertTrue(CausalFeedbackAuthority6715.admit("score-check", "mint-A", "PAPER", "QUALITY", 80).allowed)
    }

    @Test fun `missing intent timestamp does not fabricate perfect exit timing`() {
        ExitTelemetryStamper6732.noteExitCompleted("no-stamp", "HARD_FLOOR")
        assertEquals(0L, StopLatencyClasses6464.snapshot().getValue(StopLatencyClasses6464.Class.HARD_STOP).first)
    }

    @Test fun `real intent timestamp still contributes exactly one latency sample`() {
        ExitTelemetryStamper6732.noteExitIntent("stamp", StopLatencyClasses6464.Class.HARD_STOP)
        ExitTelemetryStamper6732.noteExitCompleted("stamp", "HARD_FLOOR")
        ExitTelemetryStamper6732.noteExitCompleted("stamp", "HARD_FLOOR")
        assertEquals(1L, StopLatencyClasses6464.snapshot().getValue(StopLatencyClasses6464.Class.HARD_STOP).first)
    }

    @Test(timeout = 10000L) fun `completed runtime witness returns even without a trading bootstrap`() {
        val t = System.currentTimeMillis()
        ExecutionSpineAcceptanceWindow6647.beginWindow6662(t - ExecutionSpineAcceptance6647.MIN_WINDOW_MS - 1L)
        assertNotNull(ExecutionSpineAcceptanceWindow6647.closeCompletedWindow(t))
    }

    @Test fun `runtime witness is read only and retains strict economic checks`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutionSpineAcceptance6647.kt").readText()
        assertFalse(src.contains("CanonicalPaperTransaction6486.reconcileForensicBoundary6666()"))
        assertFalse(src.contains("PipelineHealthCollector.snapshot()"))
        assertTrue(src.contains("ForensicReconciliation6635.deltas6647()"))
        assertTrue(src.contains("Double.NaN"))
        assertTrue(src.contains("DISPATCH_TERMINAL_CARDINALITY"))
        assertTrue(src.contains("PHANTOM_SIZED_ONLY"))
    }

    @Test fun `entry provenance has no observation only override`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(src.contains("if (entryProvenance6658 != com.lifecyclebot.engine.truth.MarketDataProvenance6471.Provenance.AUTHORITATIVE)"))
        assertFalse(src.contains("!exactPaperObservation6734"))
        assertTrue(src.contains("val entryMarketSnapshot = mintEntryMarketSnapshot(ts)"))
    }
}
