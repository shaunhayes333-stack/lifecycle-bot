package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AuthoritySnapshotVersion6464
import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import com.lifecyclebot.engine.truth.CanonicalIdentityModel6464
import com.lifecyclebot.engine.truth.CanonicalLotQuantity6464
import com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464
import com.lifecyclebot.engine.truth.EconomicEventSchema6464
import com.lifecyclebot.engine.truth.RootCauseTtl6464
import com.lifecyclebot.engine.truth.StopLatencyClasses6464
import com.lifecyclebot.engine.truth.TerminalSellIdempotency6464
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * V5.0.6464 §P0/§P1 — HARD ACCEPTANCE ASSERTIONS.
 *
 * Locks the 16 operator acceptance points that don't require a running
 * Android context. The runtime smoke test covers the wiring points
 * (BotService.startBot registering consumers, SellFinalizationCoordinator
 * publishing to the bus).
 */
class CanonicalCorrectnessAcceptanceTest6464 {

    // ─── §P0-#1  MINT OCCUPANCY ADMISSION ORDER ─────────────────────────

    @Test
    fun `first observation admits as PASS_NONE and marks CANDIDATE`() {
        CanonicalMintOccupancyRegistry6464.resetForTest()
        val res = CanonicalMintOccupancyRegistry6464.admit("paper", "MINT_X", "SYM", "V3_EXEC")
        assertEquals(CanonicalMintOccupancyRegistry6464.Admission.PASS_NONE, res)
        assertEquals(
            CanonicalMintOccupancyRegistry6464.Occupancy.CANDIDATE,
            CanonicalMintOccupancyRegistry6464.occupancyOf("paper", "MINT_X"),
        )
    }

    @Test
    fun `open mint yields BLOCK_OPEN on subsequent admit`() {
        CanonicalMintOccupancyRegistry6464.resetForTest()
        CanonicalMintOccupancyRegistry6464.admit("paper", "MINT_Y", "SYM", "V3_EXEC")
        CanonicalMintOccupancyRegistry6464.markOpen("paper", "MINT_Y", "SYM")
        val res = CanonicalMintOccupancyRegistry6464.admit("paper", "MINT_Y", "SYM", "V3_EXEC")
        assertEquals(CanonicalMintOccupancyRegistry6464.Admission.BLOCK_OPEN, res)
    }

    @Test
    fun `paper and live occupancy are independent`() {
        CanonicalMintOccupancyRegistry6464.resetForTest()
        CanonicalMintOccupancyRegistry6464.markOpen("paper", "MINT_Z", "SYM")
        // Live must NOT be blocked by paper open — the live route stays functional.
        val res = CanonicalMintOccupancyRegistry6464.admit("live", "MINT_Z", "SYM", "V3_EXEC")
        assertEquals(CanonicalMintOccupancyRegistry6464.Admission.PASS_NONE, res)
    }

    @Test
    fun `candidate coalesces on second observation`() {
        CanonicalMintOccupancyRegistry6464.resetForTest()
        CanonicalMintOccupancyRegistry6464.admit("paper", "MINT_A", "SYM", "V3_EXEC")
        val res = CanonicalMintOccupancyRegistry6464.admit("paper", "MINT_A", "SYM", "V3_EXEC")
        assertEquals(CanonicalMintOccupancyRegistry6464.Admission.PASS_CANDIDATE, res)
    }

    // ─── §P0-#3  CANONICAL LOT QUANTITY ─────────────────────────────────

    @Test
    fun `overSell request is rejected when nothing bought`() {
        CanonicalLotQuantity6464.resetForTest()
        val g = CanonicalLotQuantity6464.reserveForSell("PID_1", "MINT_L", BigInteger.valueOf(1000L))
        assertEquals(CanonicalLotQuantity6464.GuardResult.REJECTED_NO_LOT, g.result)
    }

    @Test
    fun `overSell of confirmed bought clamps to sellable`() {
        CanonicalLotQuantity6464.resetForTest()
        CanonicalLotQuantity6464.onBuyFilled("PID_2", "MINT_L", BigInteger.valueOf(35_140_000_000L)) // 35.140 * 1e9
        // Try to sell 57.632 (>> bought). Must clamp to bought quantity.
        val g = CanonicalLotQuantity6464.reserveForSell("PID_2", "MINT_L", BigInteger.valueOf(57_632_000_000L))
        assertEquals(CanonicalLotQuantity6464.GuardResult.CLAMPED_TO_SELLABLE, g.result)
        assertEquals(BigInteger.valueOf(35_140_000_000L), g.allowedQty)
    }

    @Test
    fun `sellableQty = bought − sold − reserved invariant`() {
        CanonicalLotQuantity6464.resetForTest()
        CanonicalLotQuantity6464.onBuyFilled("PID_3", "MINT_L", BigInteger.valueOf(100L))
        CanonicalLotQuantity6464.onSellFilled("PID_3", "MINT_L", BigInteger.valueOf(30L))
        val g = CanonicalLotQuantity6464.reserveForSell("PID_3", "MINT_L", BigInteger.valueOf(20L))
        assertEquals(CanonicalLotQuantity6464.GuardResult.OK, g.result)
        assertEquals(BigInteger.valueOf(70L), g.sellable)
        // Reserved 20; sellable now 50. Another 60 → clamp to 50.
        val g2 = CanonicalLotQuantity6464.reserveForSell("PID_3", "MINT_L", BigInteger.valueOf(60L))
        assertEquals(CanonicalLotQuantity6464.GuardResult.CLAMPED_TO_SELLABLE, g2.result)
        assertEquals(BigInteger.valueOf(50L), g2.allowedQty)
    }

    // ─── §P0-#4  DUPLICATE CLOSE CONFIRMATION ───────────────────────────

    @Test
    fun `first observation PROCEEDs then second DUPLICATE_IGNORED`() {
        TerminalSellIdempotency6464.resetForTest()
        val key = "sig_ABC_${System.nanoTime()}"
        val a = TerminalSellIdempotency6464.beginTerminal(key, "PID", "site")
        val b = TerminalSellIdempotency6464.beginTerminal(key, "PID", "site")
        assertEquals(TerminalSellIdempotency6464.Consume.PROCEED, a)
        assertEquals(TerminalSellIdempotency6464.Consume.DUPLICATE_IGNORED, b)
    }

    @Test
    fun `blank key returns BLANK_KEY`() {
        TerminalSellIdempotency6464.resetForTest()
        val res = TerminalSellIdempotency6464.beginTerminal("", "PID", "site")
        assertEquals(TerminalSellIdempotency6464.Consume.BLANK_KEY, res)
    }

    // ─── §P0-#5  ECONOMIC EVENT SCHEMA ──────────────────────────────────

    @Test
    fun `sell event computes allocatedCostBasis proportionally`() {
        EconomicEventSchema6464.resetForTest()
        EconomicEventSchema6464.recordSell(
            mode = "paper", positionId = "PID", mint = "MINT", symbol = "T",
            idempotencyKey = "sell_1", partial = true,
            soldQty = BigInteger.valueOf(50L),
            preRemainingQty = BigInteger.valueOf(100L),
            preRemainingCostBasisSol = 0.20,
            grossProceedsSol = 0.15, exitFeesSol = 0.005,
        )
        val events = EconomicEventSchema6464.snapshot()
        assertEquals(1, events.size)
        val e = events.first() as EconomicEventSchema6464.Sell
        assertEquals(0.10, e.allocatedCostBasisSol, 1e-9)      // 0.20 * 50/100
        assertEquals(0.145, e.netProceedsSol, 1e-9)             // gross - fee
        assertEquals(0.045, e.realizedPnlSol, 1e-9)             // net - allocated
        assertEquals(45.0, e.realizedReturnPct, 1e-6)           // realized / allocated * 100
        assertEquals(BigInteger.valueOf(50L), e.remainingQty)
        assertEquals(0.10, e.remainingCostBasisSol, 1e-9)
    }

    // ─── §P0-#7  FINALIZED TRADE BUS PARITY ─────────────────────────────

    @Test
    fun `bus reports zero-consumers when none has acked`() {
        CanonicalFinalizedTradeBus6464.resetForTest()
        CanonicalFinalizedTradeBus6464.registerConsumer("LearnerRewardBridge")
        CanonicalFinalizedTradeBus6464.registerConsumer("LosingStreakReflex")
        CanonicalFinalizedTradeBus6464.publish(
            CanonicalFinalizedTradeBus6464.Envelope(
                tradeId = "T1", atMs = 0L, realizedPnlSol = 0.05,
                realizedReturnPct = 25.0, mint = "M", lane = "L",
            )
        )
        val p = CanonicalFinalizedTradeBus6464.parity()
        assertEquals(1, p.canonicalUnique)
        assertTrue("expected both consumers in zeroConsumers: ${p.zeroConsumers}",
            p.zeroConsumers.containsAll(listOf("LearnerRewardBridge", "LosingStreakReflex")))
    }

    @Test
    fun `duplicate publish is deduped`() {
        CanonicalFinalizedTradeBus6464.resetForTest()
        val env = CanonicalFinalizedTradeBus6464.Envelope("T2", 0L, 0.01, 5.0, "M", "L")
        assertTrue(CanonicalFinalizedTradeBus6464.publish(env))
        assertEquals(false, CanonicalFinalizedTradeBus6464.publish(env))
        assertEquals(1, CanonicalFinalizedTradeBus6464.canonicalUnique())
    }

    // ─── §P1  IDENTITY MODEL ────────────────────────────────────────────

    @Test
    fun `alias normalization maps PRESALE_SNIPE to RESALE_SNIPE`() {
        assertEquals("RESALE_SNIPE", CanonicalIdentityModel6464.normalizeLane("presale_snipe"))
        assertEquals("BLUE_CHIP", CanonicalIdentityModel6464.normalizeLane("bluechip"))
        assertEquals("MOMENTUM_SWING", CanonicalIdentityModel6464.normalizeLane("momentumswing"))
    }

    @Test
    fun `identity rewrite is refused`() {
        CanonicalIdentityModel6464.resetForTest()
        val pid = "PID_ID_${System.nanoTime()}"
        CanonicalIdentityModel6464.record(pid, "RESALE_SNIPE", "s1", "r1", "MEME", "STANDARD")
        // Try to overwrite with a different origin — must be refused.
        CanonicalIdentityModel6464.record(pid, "BLUE_CHIP", "s2", "r2", "MARKETS", "AGGRESSIVE")
        val id = CanonicalIdentityModel6464.getIdentity(pid)!!
        assertEquals("RESALE_SNIPE", id.canonicalOriginLane)
        assertEquals("s1", id.strategyId)
    }

    // ─── §P1  STOP LATENCY CLASSES ──────────────────────────────────────

    @Test
    fun `catastrophic latency over 1000ms fires alert`() {
        StopLatencyClasses6464.resetForTest()
        StopLatencyClasses6464.record(StopLatencyClasses6464.Class.CATASTROPHIC_EXIT, 1500L)
        val s = StopLatencyClasses6464.statusLine()
        assertTrue("expected catastrophicAlerts=1: $s", s.contains("catastrophicAlerts=1"))
    }

    // ─── §P1  ROOT CAUSE TTL ────────────────────────────────────────────

    @Test
    fun `expired root cause returns null`() {
        RootCauseTtl6464.resetForTest()
        RootCauseTtl6464.classify("MECHANICAL_FAULT/ui/reporting", "high", activeWindowMs = 1L)
        Thread.sleep(30)
        assertEquals(null, RootCauseTtl6464.current())
    }

    // ─── §P1  AUTHORITY SNAPSHOT VERSIONING ─────────────────────────────

    @Test
    fun `bumped version stales earlier snapshot`() {
        AuthoritySnapshotVersion6464.resetForTest()
        val snap = AuthoritySnapshotVersion6464.snapshotVersion()
        AuthoritySnapshotVersion6464.bump("test")
        assertEquals(false, AuthoritySnapshotVersion6464.validate(snap, "test_site"))
    }

    @Test
    fun `unbumped snapshot validates OK`() {
        AuthoritySnapshotVersion6464.resetForTest()
        val snap = AuthoritySnapshotVersion6464.snapshotVersion()
        assertEquals(true, AuthoritySnapshotVersion6464.validate(snap, "test_site"))
    }
}
