package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import com.lifecyclebot.engine.truth.AdaptiveVetoConsensusAuthority6728.Signal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

/** Behavioural regressions for the 6733 operator incident; no network or funded orders. */
class Aate6734RecoveryIntegrityTest {
    @Before fun reset() {
        AdaptiveVetoConsensusAuthority6728.resetForTest6728()
        CanonicalPriceMarkRegistry6522.resetForTest()
        CanonicalFinalizedTradeBus6464.resetForTest()
        CanonicalPaperReplay6464.resetForTest()
        EconomicEventSchema6464.resetForTest()
        ProviderInferenceHealth6727.resetForTest6734()
    }
    private fun raise(s: Signal, mode: String = "PAPER", lane: String = "QUALITY", mint: String = "M",
                      id: String = s.name, at: Long = System.currentTimeMillis()) =
        AdaptiveVetoConsensusAuthority6728.raise(s, mode, lane, mint, id, at)

    @Test fun correlated_advisories_do_not_form_three_independent_votes() {
        raise(Signal.LLM_BLOCK_ADVISORY); raise(Signal.SENTIENCE_VETO_ADVISORY)
        raise(Signal.LOSING_STREAK_COHORT)
        val v = AdaptiveVetoConsensusAuthority6728.evaluate("PAPER", "QUALITY", "M")
        assertEquals(2, v.quorum); assertFalse(v.hardVeto)
    }
    @Test fun three_independent_scoped_families_retain_hard_protection() {
        raise(Signal.LLM_BLOCK_ADVISORY); raise(Signal.LOSING_STREAK_COHORT)
        raise(Signal.BRAIN_CONSENSUS_SOFT_BLOCK)
        assertTrue(AdaptiveVetoConsensusAuthority6728.evaluate("PAPER", "QUALITY", "M").hardVeto)
        assertFalse(AdaptiveVetoConsensusAuthority6728.evaluate("LIVE", "QUALITY", "M").hardVeto)
        assertFalse(AdaptiveVetoConsensusAuthority6728.evaluate("PAPER", "CORE", "M").hardVeto)
        assertFalse(AdaptiveVetoConsensusAuthority6728.evaluate("PAPER", "QUALITY", "OTHER").hardVeto)
    }
    @Test fun unscoped_legacy_votes_cannot_freeze_scoped_execution() {
        Signal.values().forEach { AdaptiveVetoConsensusAuthority6728.raise(it) }
        assertFalse(AdaptiveVetoConsensusAuthority6728.evaluate("PAPER", "QUALITY", "M").hardVeto)
    }
    @Test fun reading_same_evidence_does_not_extend_its_veto() {
        val now = System.currentTimeMillis()
        raise(Signal.LLM_BLOCK_ADVISORY, id = "unchanged", at = now - 299_000L)
        raise(Signal.LLM_BLOCK_ADVISORY, id = "unchanged", at = now)
        val v = AdaptiveVetoConsensusAuthority6728.evaluate("PAPER", "QUALITY", "M", now + 2_000L)
        assertTrue(v.activeSignals.isEmpty())
    }
    @Test fun alias_scopes_match_and_clear_exactly() {
        raise(Signal.LLM_BLOCK_ADVISORY, lane = "BLUE_CHIP")
        assertEquals(1, AdaptiveVetoConsensusAuthority6728.evaluate("paper", "BLUECHIP", "M").quorum)
        AdaptiveVetoConsensusAuthority6728.clear(Signal.LLM_BLOCK_ADVISORY, "paper", "BLUECHIP", "M")
        assertEquals(0, AdaptiveVetoConsensusAuthority6728.evaluate("PAPER", "BLUE_CHIP", "M").quorum)
    }

    private fun mark(mint: String, at: Long, price: Double = 0.1234,
                     purpose: CanonicalMarkPurpose6570 = CanonicalMarkPurpose6570.OBSERVATION_SCORING) =
        CanonicalPriceMark6522(mint, "pair6734", mint, "USD", "DEXSCREENER_PAIR_POLL", at,
            PriceUsd(BigDecimal.valueOf(price)), BigDecimal.valueOf(12_345.0), purpose)

    @Test fun fresher_canonical_mark_wins_without_false_missing_mark() {
        val now = System.currentTimeMillis()
        assertTrue(CanonicalPriceMarkRegistry6522.publish(mark("FRESH6734", now)))
        val r = CanonicalPriceMarkRegistry6522.resolveExecutableFromSourceEvidence6616(
            "FRESH6734", "FRESH6734", "pair6734", "USD", "DEXSCREENER_PAIR_POLL",
            0.1230, 12_345.0, now - 1_000L, now,
        )
        assertTrue(r.reason, r.promoted)
        assertEquals(now, r.mark!!.timestampMs)
        assertEquals(0.1234, r.mark!!.priceUsd.value.toDouble(), 1e-12)
    }
    @Test fun stale_strict_mark_cannot_be_reused_for_execution() {
        val now = System.currentTimeMillis()
        assertTrue(CanonicalPriceMarkRegistry6522.publish(mark("STALE6734", now - 121_000L,
            purpose = CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE)))
        assertNull(CanonicalPriceMarkRegistry6522.getFresh6734("STALE6734", CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE, now))
    }
    @Test fun source_price_and_timestamp_are_not_spliced_between_providers() {
        val now = System.currentTimeMillis()
        val r = CanonicalPriceMarkRegistry6522.resolveBestSourceEvidence6734("TUPLE6734", listOf(
            CanonicalPriceMarkRegistry6522.SourceEvidence6734("TUPLE6734", "pair6734", "USD", "DEXSCREENER_PAIR_POLL", 0.1234, 12_345.0, now - 150_000L),
            CanonicalPriceMarkRegistry6522.SourceEvidence6734("TUPLE6734", "pair6734", "USD", "", 0.1240, 12_345.0, now),
        ), now)
        assertFalse(r.promoted)
        assertNull(CanonicalPriceMarkRegistry6522.get("TUPLE6734"))
    }
    @Test fun healthy_secondary_source_can_supply_its_own_complete_tuple() {
        val now = System.currentTimeMillis()
        val r = CanonicalPriceMarkRegistry6522.resolveBestSourceEvidence6734("SECOND6734", listOf(
            CanonicalPriceMarkRegistry6522.SourceEvidence6734("SECOND6734", "pair6734", "USD", "UNKNOWN", 0.12, 12_345.0, now),
            CanonicalPriceMarkRegistry6522.SourceEvidence6734("SECOND6734", "pair6734", "USD", "DEXSCREENER_PAIR_POLL", 0.1234, 12_345.0, now - 1_000L),
        ), now)
        assertTrue(r.reason, r.promoted)
        assertEquals("DEXSCREENER_PAIR_POLL", r.mark!!.source)
        assertEquals(now - 1_000L, r.mark!!.timestampMs)
    }
    @Test fun zero_liquidity_observation_never_claims_strict_liquidity() {
        val now = System.currentTimeMillis()
        val r = CanonicalPriceMarkRegistry6522.resolveExecutableFromSourceEvidence6616(
            "OBS6734", "OBS6734", "pair6734", "USD", "DEXSCREENER_PAIR_POLL", 0.1234, 0.0, now, now)
        assertTrue(r.reason, r.promoted)
        assertEquals(CanonicalMarkPurpose6570.OBSERVATION_SCORING, r.mark!!.purpose)
        assertNull(CanonicalPriceMarkRegistry6522.get("OBS6734", CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE))
    }

    private fun buy(id: String, qty: Long, cost: Double) = EconomicEventSchema6464.recordBuy(
        mode = "paper", positionId = id, mint = "SAME6734", symbol = "SAME", idempotencyKey = "buy:$id",
        executedCostSol = cost, filledQty = BigInteger.valueOf(qty), fillPrice = 0.01,
    )
    private fun sell(id: String, qty: Long, preQty: Long, basis: Double, proceeds: Double, partial: Boolean = false, key: String = "sell:$id") =
        EconomicEventSchema6464.recordSell(mode = "paper", positionId = id, mint = "SAME6734", symbol = "SAME",
            idempotencyKey = key, partial = partial, soldQty = BigInteger.valueOf(qty), preRemainingQty = BigInteger.valueOf(preQty),
            preRemainingCostBasisSol = basis, grossProceedsSol = proceeds, exitFeesSol = 0.0)

    @Test fun one_full_close_does_not_need_to_sell_other_positions_of_same_mint() {
        buy("LOT1", 100, 1.0); buy("LOT2", 200, 2.0)
        sell("LOT1", 100, 100, 1.0, 1.2)
        val s = CanonicalPaperReplay6464.replay(10.0)
        assertEquals(0, s.invalidRowsQuarantined); assertEquals(1, s.fullSells)
        assertEquals(8.2, s.cashSol, 1e-9); assertEquals(2.0, s.openCostBasisSol, 1e-9)
        assertEquals(BigInteger.valueOf(200), s.perMintRemainingQty["SAME6734"])
    }
    @Test fun another_lot_cannot_cover_an_oversell() {
        buy("LOT1", 100, 1.0); buy("LOT2", 200, 2.0)
        sell("LOT1", 150, 150, 1.0, 1.2)
        val s = CanonicalPaperReplay6464.replay(10.0)
        assertEquals(1, s.invalidRowsQuarantined); assertEquals(0, s.fullSells)
        assertEquals(7.0, s.cashSol, 1e-9)
    }
    @Test fun partial_and_final_close_consume_only_their_own_position() {
        buy("LOT1", 100, 1.0); buy("LOT2", 200, 2.0)
        sell("LOT1", 40, 100, 1.0, 0.5, true, "partial:LOT1")
        sell("LOT1", 60, 60, 0.6, 0.7)
        val s = CanonicalPaperReplay6464.replay(10.0)
        assertEquals(0, s.invalidRowsQuarantined); assertEquals(1, s.partialSells); assertEquals(1, s.fullSells)
        assertEquals(8.2, s.cashSol, 1e-9); assertEquals(2.0, s.openCostBasisSol, 1e-9)
    }

    private fun envelope(id: String) = CanonicalFinalizedTradeBus6464.Envelope(
        tradeId = id, atMs = System.currentTimeMillis(), realizedPnlSol = 0.01, realizedReturnPct = 10.0,
        mint = "BUS6734", lane = "QUALITY", mode = "paper", economicEventId = "economic:$id")

    @Test fun concurrent_terminal_delivery_mutates_each_consumer_once() {
        val env = envelope("concurrent6734")
        CanonicalFinalizedTradeBus6464.registerConsumer("test6734")
        CanonicalFinalizedTradeBus6464.publish(env)
        val calls = AtomicInteger(); val start = CountDownLatch(1); val done = CountDownLatch(20)
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(20) { pool.submit {
                start.await()
                try { CanonicalFinalizedTradeBus6464.deliverToConsumers(env) { _, _ ->
                    calls.incrementAndGet(); Thread.sleep(10L); true
                } } finally { done.countDown() }
            } }
            start.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            assertEquals(1, CanonicalFinalizedTradeBus6464.consumerUnique("test6734"))
        } finally { pool.shutdownNow() }
    }
    @Test fun refused_delivery_retries_and_uses_original_economics() {
        val env = envelope("retry6734")
        CanonicalFinalizedTradeBus6464.registerConsumer("test6734")
        CanonicalFinalizedTradeBus6464.publish(env)
        CanonicalFinalizedTradeBus6464.deliverToConsumers(env) { _, _ -> false }
        assertEquals(0, CanonicalFinalizedTradeBus6464.consumerUnique("test6734"))
        CanonicalFinalizedTradeBus6464.deliverToConsumers(env.copy(realizedPnlSol = 99.0)) { _, e ->
            assertEquals(0.01, e.realizedPnlSol, 1e-12); true
        }
        assertEquals(1, CanonicalFinalizedTradeBus6464.consumerUnique("test6734"))
    }
    @Test fun unrelated_durable_ack_is_not_counted_as_a_canonical_trade() {
        CanonicalFinalizedTradeBus6464.ack("test6734", "unrelated6734")
        CanonicalFinalizedTradeBus6464.publish(envelope("different6734"))
        assertEquals(0, CanonicalFinalizedTradeBus6464.consumerUnique("test6734"))
    }
    @Test fun excluded_outcome_is_never_reported_as_learned() {
        val env = envelope("excluded6734")
        CanonicalFinalizedTradeBus6464.registerConsumer("test6734")
        CanonicalFinalizedTradeBus6464.publish(env)
        CanonicalFinalizedTradeBus6464.deliverToConsumers(env) { c, e ->
            CanonicalFinalizedTradeBus6464.exclude(c, e.tradeId, "untrainable-test"); true
        }
        assertEquals(0, CanonicalFinalizedTradeBus6464.consumerUnique("test6734"))
        assertEquals(1, CanonicalFinalizedTradeBus6464.consumerExcludedUnique("test6734"))
    }
    @Test fun inference_capacity_is_not_equated_to_valid_credentials() {
        assertTrue(ProviderInferenceHealth6727.health("groq").isHealthy)
        ProviderInferenceHealth6727.recordFailure("groq", "HTTP_429")
        assertFalse(ProviderInferenceHealth6727.health("groq").isHealthy)
        ProviderInferenceHealth6727.recordSuccess("groq")
        assertTrue(ProviderInferenceHealth6727.health("groq").isHealthy)
    }
    @Test fun inference_history_is_bounded_and_expires() {
        repeat(150) { ProviderInferenceHealth6727.recordFailure("groq", "HTTP_500") }
        assertEquals(100L, ProviderInferenceHealth6727.health("groq").failures)
        val h = ProviderInferenceHealth6727.health("groq", System.currentTimeMillis() + 301_000L)
        assertEquals(0L, h.failures); assertTrue(h.isHealthy)
    }
    @Test fun production_wiring_keeps_safety_and_removes_fabricated_fill_bands() {
        val root = File("src/main/kotlin/com/lifecyclebot/engine")
        val executor = File(root, "Executor.kt").readText()
        val gate = File(root, "ExecutableOpenGate.kt").readText()
        val bridge = File(root, "truth/FinalizedBusConsumerBridge6465.kt").readText()
        assertFalse(executor.contains("val (clampLowPct, clampHighPct) = parsePaperExitClamp(reason)"))
        assertFalse(executor.contains("bootstrapTimestamp6616 = maxOf"))
        assertTrue(executor.contains("resolveBestSourceEvidence6734(ts.mint"))
        assertTrue(gate.contains("PaperLedgerDivergenceGuard6731.evaluate()"))
        assertTrue(gate.contains("sameDecisionContract6734(existing, intent)"))
        assertTrue(gate.contains("FinalDecisionGate.invalidateCandidate6734(mint)"))
        assertTrue(bridge.contains("CausalFeedbackAuthority6715.isMemeOwnerLane(env.lane)"))
    }
}
