package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchEvidence8136
import com.lifecyclebot.engine.truth.LaunchEvidence8136.Provenance8136
import com.lifecyclebot.engine.truth.LaunchLifecycle8136
import com.lifecyclebot.engine.truth.LaunchLifecycle8136.Stage8136
import com.lifecyclebot.engine.truth.PumpBandEntry8118
import com.lifecyclebot.engine.truth.PumpBandEntry8118.ClaimState8136
import com.lifecyclebot.engine.truth.PumpBandEntry8118.Event8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8136 — launch-observation pipeline and PROJECT_SNIPER phase eligibility: swap evidence vs price-only
 * provenance, band crossing without a trade stream, the band claim state machine, the ordered launch lifecycle and
 * the sniper's named handoffs. Pure functions with explicit clocks; no Android, no network.
 */
class Aate8136LaunchObservationTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private val t0 = 1_800_000_000_000L
    private fun swap(sec: Long, signer: String, buy: Boolean = true, sol: Double = 0.5) =
        LaunchEvidence8136.Swap8136(t0 + sec * 1_000L, signer, buy, sol)

    private fun ev(
        atMs: Long,
        ageMs: Long?,
        prov: Provenance8136 = Provenance8136.SWAP_RECORDS,
        buyTx: Int? = 6,
        buyers: Int? = 5,
        share: Double? = 80.0,
        accel: Boolean? = true,
        mult: Double? = 1.1,
        migrated: Boolean = false,
        fade: Boolean = false,
        devSold: Boolean = false,
    ) = LaunchLifecycle8136.Evidence8136(atMs, ageMs, migrated, prov, buyTx, buyers, share, accel, devSold, mult, fade)

    private fun priceOnly(atMs: Long, ageMs: Long, mult: Double? = null) =
        ev(atMs, ageMs, Provenance8136.PRICE_ONLY, null, null, null, null, mult)

    // ── provenance: a price is never a buyer ─────────────────────────────────

    @Test fun coldLaunchHasUnknownFlowNotZeroBuyers() {
        // Create frame seen, prices observed, no swap feed covering the mint (PumpPortal AUTH_DENIED).
        val read = LaunchEvidence8136.flowFrom8136(emptyList(), swapFeedCovers = false, priceObserved = true, devWallet = null, nowMs = t0)
        assertEquals(Provenance8136.PRICE_ONLY, read.provenance)
        assertFalse(read.known)
        assertNull(read.buyers60s); assertNull(read.buyTx60s); assertNull(read.buyersPerMin); assertNull(read.accelerationRising)
        assertEquals(Provenance8136.NONE, LaunchEvidence8136.flowFrom8136(emptyList(), false, false, null, t0).provenance)
        // Any number of price updates is still PRICE_ONLY; one swap record (or a covering feed) is swap evidence.
        assertEquals(Provenance8136.PRICE_ONLY, LaunchEvidence8136.provenance8136(0, swapFeedCovers = false, priceObserved = true))
        assertEquals(Provenance8136.SWAP_RECORDS, LaunchEvidence8136.provenance8136(1, swapFeedCovers = false, priceObserved = false))
        assertEquals(Provenance8136.SWAP_RECORDS, LaunchEvidence8136.provenance8136(0, swapFeedCovers = true, priceObserved = false))
        // The same empty window under a feed that demonstrably covers the mint is an observed zero.
        val covered = LaunchEvidence8136.flowFrom8136(emptyList(), swapFeedCovers = true, priceObserved = true, devWallet = null, nowMs = t0)
        assertEquals(0, covered.buyers60s)
        assertEquals(false, covered.accelerationRising)
        // WhaleDetector's legacy zeros restated: unknown without coverage, kept with it.
        assertNull(LaunchEvidence8136.restate8136(0, false, true, 0, 0, 0, 50.0, false).buyers60s)
        assertEquals(0, LaunchEvidence8136.restate8136(0, true, true, 0, 0, 0, 50.0, false).buyers60s)
        assertNull(LaunchEvidence8136.restate8136(0, true, true, 0, 0, 0, 50.0, false).buySharePct)   // no trades: no share
        assertEquals(3, LaunchEvidence8136.restate8136(4, false, true, 3, 4, 0, 100.0, true).buyers60s) // swaps prove themselves
        // The lifecycle keeps a flowless launch at BIRTH (not EXPANDING), and the sniper hands it on by name.
        val s = LaunchLifecycle8136.advance8136(null, priceOnly(t0, 5_000L), t0)
        assertEquals(Stage8136.BIRTH, s.stage)
        assertEquals("FLOW_UNKNOWN_PRICE_ONLY", s.reason)
        val r = LaunchLifecycle8136.sniperRoute8136(s, capUsd = 4_800.0, provenSetup = false, moonshotFloorUsd = 10_000.0)
        assertFalse(r.eligible)
        assertEquals("SHITCOIN", r.handoffLane)
        assertTrue(r.reason.startsWith("PHASE_HANDOFF_8136_BIRTH_TO_SHITCOIN"))
        // And a $4.8k create is not in the band.
        assertFalse(PumpBandEntry8118.inBand8118(4_800.0, 5_000L))
    }

    @Test fun repeatedFramesNeverAddBuyers() {
        val k = LaunchEvidence8136.dedupKey8136("RPT8136mint", "sigA", "walletA", true, 0.5, 31.0, 1.0e9)
        assertEquals(k, LaunchEvidence8136.dedupKey8136("RPT8136mint", "sigA", "walletA", true, 0.5, 31.0, 1.0e9))
        assertTrue(LaunchEvidence8136.noteSwap8136("RPT8136mint", k, t0))
        assertFalse(LaunchEvidence8136.noteSwap8136("RPT8136mint", k, t0 + 200L))           // the same frame again
        assertFalse(LaunchEvidence8136.noteSwap8136("RPT8136mint", k, t0 + 5_000L))         // re-sent after a hiccup
        // Without a signature the post-trade reserves identify the swap; a different swap differs.
        val n1 = LaunchEvidence8136.dedupKey8136("RPT8136mint", "", "walletB", true, 0.5, 31.5, 1.0e9)
        val n2 = LaunchEvidence8136.dedupKey8136("RPT8136mint", "", "walletB", true, 0.5, 32.0, 0.99e9)
        assertTrue(n1 != n2)
        assertTrue(LaunchEvidence8136.noteSwap8136("RPT8136mint", n1, t0 + 6_000L))
        assertTrue(LaunchEvidence8136.noteSwap8136("RPT8136mint", n2, t0 + 7_000L))
        assertTrue(LaunchEvidence8136.swapSeenRecently8136("RPT8136mint", t0 + 8_000L))
        // Buyers are distinct signers (creator excluded): one wallet buying five times is one buyer.
        val recs = listOf(swap(1, "dev"), swap(2, "w1"), swap(3, "w1"), swap(4, "w1"), swap(5, "w1"), swap(6, "w2"))
        val read = LaunchEvidence8136.flowFrom8136(recs, false, true, devWallet = "dev", nowMs = t0 + 10_000L)
        assertEquals(Provenance8136.SWAP_RECORDS, read.provenance)
        assertEquals(2, read.buyers60s)
        assertEquals(6, read.buyTx60s)
        assertEquals(2.0, read.buyersPerMin!!, 1e-9)
        assertTrue(LaunchEvidence8136.statusLine8136().contains("duplicateFrames="))
    }

    @Test fun delayedProviderEvidenceIsReadOnlyAtDecisionTime() {
        // A record stamped after the decision time is not yet known; one older than the window has aged out.
        val recs = listOf(swap(-90, "old"), swap(2, "w1"), swap(3, "w2"), swap(30, "future"))
        val read = LaunchEvidence8136.flowFrom8136(recs, false, true, null, nowMs = t0 + 10_000L)
        assertEquals(2, read.buyers60s)
        // A stale band observation (35 s old) is no crossing; a fresh one is.
        val born = t0
        val now = t0 + 4 * 60_000L
        val px = 2.0e-5     // USD per token -> $20k cap on the fixed 1B curve supply
        fun obs(at: Long) = PumpBandEntry8118.Observation8136("DLY8136", born, px, at, PumpBandEntry8118.BandSource8136.EXECUTED_MARK)
        assertFalse(PumpBandEntry8118.crossing8136(obs(now - 35_000L), null, now))
        assertFalse(PumpBandEntry8118.crossing8136(obs(now + 1_000L), null, now))        // from the future
        assertTrue(PumpBandEntry8118.crossing8136(obs(now - 5_000L), null, now))
        // Out-of-order lifecycle evidence (older than the last applied) leaves the state untouched.
        val s1 = LaunchLifecycle8136.advance8136(null, ev(t0 + 20_000L, 20_000L), t0 + 20_000L)
        assertEquals(Stage8136.PRE_IGNITION, s1.stage)
        val late = LaunchLifecycle8136.advance8136(s1, ev(t0 + 10_000L, 10_000L, mult = 1.9), t0 + 21_000L)
        assertEquals(s1, late)
    }

    @Test fun subscriptionDenialStillDetectsAPriceSupplyBandCrossing() {
        val born = t0
        val now = t0 + 3 * 60_000L
        // An executed-trade mark (Helius-decoded print) at $1.5e-5 -> $15k: a genuine crossing with no trade stream.
        val mark = PumpBandEntry8118.Observation8136("DENY8136", born, 1.5e-5, now - 2_000L, PumpBandEntry8118.BandSource8136.EXECUTED_MARK)
        assertTrue(PumpBandEntry8118.crossing8136(mark, null, now))
        // The curve's own reserves read inside the window count the same.
        val curve = mark.copy(source = PumpBandEntry8118.BandSource8136.CURVE_RESERVES, observedAtMs = now)
        assertTrue(PumpBandEntry8118.crossing8136(curve, null, now))
        // Under $10k, past $100k, a launch older than 15 minutes, a non-price or a pre-launch reading: no crossing.
        assertFalse(PumpBandEntry8118.crossing8136(mark.copy(priceUsd = 6.0e-6), null, now))
        assertFalse(PumpBandEntry8118.crossing8136(mark.copy(priceUsd = 1.4e-4), null, now))
        assertFalse(PumpBandEntry8118.crossing8136(mark, null, born + 16 * 60_000L))
        assertFalse(PumpBandEntry8118.crossing8136(mark.copy(priceUsd = Double.NaN), null, now))
        assertFalse(PumpBandEntry8118.crossing8136(mark.copy(observedAtMs = born - 1_000L), null, now))
        assertFalse(PumpBandEntry8118.crossing8136(mark.copy(createdAtMs = 0L), null, now))
        // An open claim is required: a fresh PENDING or an ACKNOWLEDGED launch is not handed on again.
        val pending = PumpBandEntry8118.Claim8136(ClaimState8136.PENDING, 1, now - 1_000L, "FIRST_CROSSING")
        assertFalse(PumpBandEntry8118.crossing8136(mark, pending, now))
        assertFalse(PumpBandEntry8118.crossing8136(mark, pending.copy(state = ClaimState8136.ACKNOWLEDGED), now))
        // With AUTH_DENIED / no socket the stream covers no mint, so its empty window is unknown, not zero.
        assertFalse(com.lifecyclebot.network.PumpFunWS.tradeStreamCoversMint8136("DENY8136"))
        // Wiring: the sweep is clocked by every PumpPortal frame and hands crossings to the same band entry.
        assertTrue(src("network/PumpFunWS.kt").contains("PumpBandEntry8118.maybeSweep8136()"))
        assertTrue(src("engine/BotService.kt").contains("PumpBandEntry8118.setOnBandPrice8136 { mint, priceSol, mcapSol ->"))
    }

    @Test fun reconnectRecoveryKeepsDedupAndUnknownCoverage() {
        val m = "RECON8136mint"
        val before = (1..3).map { LaunchEvidence8136.dedupKey8136(m, "sig$it", "w$it", true, 0.2, 30.0 + it, 1.0e9) }
        before.forEachIndexed { i, k -> assertTrue(LaunchEvidence8136.noteSwap8136(m, k, t0 + i * 1_000L)) }
        // The socket drops and re-subscribes; the server re-sends the last frames, then new ones.
        before.forEach { k -> assertFalse(LaunchEvidence8136.noteSwap8136(m, k, t0 + 40_000L)) }
        assertTrue(LaunchEvidence8136.noteSwap8136(m, LaunchEvidence8136.dedupKey8136(m, "sig4", "w4", true, 0.2, 34.0, 1.0e9), t0 + 41_000L))
        // While the feed is down and nothing arrived, the flow is unknown; once swaps arrive again it is known.
        assertNull(LaunchEvidence8136.flowFrom8136(emptyList(), false, true, null, t0 + 200_000L).buyers60s)
        val back = LaunchEvidence8136.flowFrom8136(listOf(swap(199, "w4"), swap(200, "w5")), false, true, null, t0 + 200_000L)
        assertEquals(2, back.buyers60s)
    }

    // ── band claim state machine ─────────────────────────────────────────────

    @Test fun claimRetriesAfterAFailedIntakeAndAcknowledgesOnce() {
        val b = PumpBandEntry8118
        val mint = "RETRY8136mint"
        val now = System.currentTimeMillis()
        b.noteCreate8118(mint, "RTY", "Retry Coin", atMs = now - 3 * 60_000L)
        assertNotNull(b.claim8118(mint, 15_000.0, 3 * 60_000L, nowMs = now))
        assertNull(b.claim8118(mint, 16_000.0, 3 * 60_000L, nowMs = now + 1_000L))         // in flight: idempotent
        b.settleIntake8136(mint, admitted = false, rowCreated = false, hardRejected = false, nowMs = now + 2_000L)
        assertEquals(ClaimState8136.RETRYABLE, b.claimState8136(mint)!!.state)
        assertEquals("INTAKE_REFUSED", b.claimState8136(mint)!!.reason)
        assertNull(b.claim8118(mint, 16_000.0, 3 * 60_000L, nowMs = now + 10_000L))        // backoff not passed
        assertNotNull(b.claim8118(mint, 17_000.0, 4 * 60_000L, nowMs = now + 33_000L))     // second attempt granted
        assertEquals(2, b.claimState8136(mint)!!.attempts)
        b.settleIntake8136(mint, admitted = true, rowCreated = true, hardRejected = false, nowMs = now + 34_000L)
        assertEquals(ClaimState8136.ACKNOWLEDGED, b.claimState8136(mint)!!.state)
        assertNull(b.claim8118(mint, 20_000.0, 5 * 60_000L, nowMs = now + 10 * 60_000L))   // admitted: never again
        // A repeated / late settle changes nothing.
        b.settleIntake8136(mint, admitted = false, rowCreated = false, hardRejected = false, nowMs = now + 35_000L)
        assertEquals(ClaimState8136.ACKNOWLEDGED, b.claimState8136(mint)!!.state)
        assertTrue(src("engine/BotService.kt").contains("PumpBandEntry8118.settleIntake8136(mint, admitted, status.tokens.containsKey(mint), ScannerHardRejectStore.isRejected(mint))"))
    }

    @Test fun bandCrossingWhileInProbationIsRetryableNotAdmitted() {
        // admitProtectedMemeIntake answers true for ALREADY_IN_PROBATION with no token row: not an admission.
        val (outcome, why) = PumpBandEntry8118.outcome8136(admitted = true, rowCreated = false, hardRejected = false)
        assertEquals(PumpBandEntry8118.Outcome8136.RETRYABLE, outcome)
        assertEquals("PARKED_NO_TOKEN_ROW", why)
        assertEquals(PumpBandEntry8118.Outcome8136.ACKNOWLEDGED, PumpBandEntry8118.outcome8136(true, true, false).first)
        assertEquals(PumpBandEntry8118.Outcome8136.TERMINAL, PumpBandEntry8118.outcome8136(false, false, true).first)
        val p = PumpBandEntry8118.step8136(null, Event8136.CLAIM, t0)
        assertTrue(p.grant)
        val parked = PumpBandEntry8118.step8136(p.next, Event8136.INTAKE_RETRYABLE, t0 + 500L, why).next!!
        assertEquals(ClaimState8136.RETRYABLE, parked.state)
        assertFalse(PumpBandEntry8118.claimOpen8136(parked, t0 + 20_000L))
        assertTrue(PumpBandEntry8118.claimOpen8136(parked, t0 + 31_000L))
    }

    @Test fun terminalAfterMaxRetriesAndOnHardReject() {
        var c: PumpBandEntry8118.Claim8136? = null
        var now = t0
        repeat(PumpBandEntry8118.MAX_ATTEMPTS_8136) {
            val s = PumpBandEntry8118.step8136(c, Event8136.CLAIM, now)
            assertTrue("attempt ${it + 1} granted", s.grant)
            c = PumpBandEntry8118.step8136(s.next, Event8136.INTAKE_RETRYABLE, now + 100L, "INTAKE_REFUSED").next
            now += 10 * 60_000L
        }
        assertEquals(ClaimState8136.TERMINAL, c!!.state)
        assertTrue(c!!.reason.startsWith("MAX_ATTEMPTS_"))
        assertFalse(PumpBandEntry8118.claimOpen8136(c, now + 60 * 60_000L))
        // A hard reject ends it at once.
        val p = PumpBandEntry8118.step8136(null, Event8136.CLAIM, t0).next
        val hard = PumpBandEntry8118.step8136(p, Event8136.INTAKE_TERMINAL, t0 + 1L, "HARD_REJECT").next!!
        assertEquals(ClaimState8136.TERMINAL, hard.state)
        assertFalse(PumpBandEntry8118.claimOpen8136(hard, t0 + 60 * 60_000L))
        // An outcome with no claim creates none.
        assertNull(PumpBandEntry8118.step8136(null, Event8136.INTAKE_ACKNOWLEDGED, t0).next)
    }

    @Test fun anUnsettledClaimExpiresAndCountsAsAnAttempt() {
        val p = PumpBandEntry8118.step8136(null, Event8136.CLAIM, t0).next!!
        assertFalse(PumpBandEntry8118.step8136(p, Event8136.CLAIM, t0 + PumpBandEntry8118.PENDING_TTL_MS_8136 - 1L).grant)
        val re = PumpBandEntry8118.step8136(p, Event8136.CLAIM, t0 + PumpBandEntry8118.PENDING_TTL_MS_8136)
        assertTrue(re.grant)
        assertEquals(2, re.next!!.attempts)
        assertEquals("RETRY_PENDING_TTL_EXPIRED", re.next!!.reason)
        val last = p.copy(attempts = PumpBandEntry8118.MAX_ATTEMPTS_8136)
        val dead = PumpBandEntry8118.step8136(last, Event8136.CLAIM, t0 + PumpBandEntry8118.PENDING_TTL_MS_8136)
        assertFalse(dead.grant)
        assertEquals(ClaimState8136.TERMINAL, dead.next!!.state)
    }

    // ── ordered launch lifecycle ─────────────────────────────────────────────

    @Test fun phaseSequenceIsOrderedAndUsesDecisionTimeInputsOnly() {
        var s = LaunchLifecycle8136.advance8136(null, priceOnly(t0, 3_000L), t0)
        assertEquals(Stage8136.BIRTH, s.stage)
        s = LaunchLifecycle8136.advance8136(s, ev(t0 + 20_000L, 23_000L, mult = 1.15), t0 + 20_000L)
        assertEquals(Stage8136.PRE_IGNITION, s.stage)
        assertEquals(t0 + 20_000L, s.enteredAt8136(Stage8136.PRE_IGNITION))
        s = LaunchLifecycle8136.advance8136(s, ev(t0 + 40_000L, 43_000L, mult = 1.6), t0 + 40_000L)
        assertEquals(Stage8136.IGNITION, s.stage)
        assertEquals("SWAP_FLOW_MOVED_PRICE", s.reason)
        // The price slips back under 1.5x: never backwards.
        s = LaunchLifecycle8136.advance8136(s, ev(t0 + 50_000L, 53_000L, mult = 1.3), t0 + 50_000L)
        assertEquals(Stage8136.IGNITION, s.stage)
        assertTrue(s.reason.startsWith("HELD_IGNITION"))
        // Evidence from the future is ignored.
        assertEquals(s, LaunchLifecycle8136.advance8136(s, ev(t0 + 90_000L, 93_000L, mult = 2.5), t0 + 55_000L))
        s = LaunchLifecycle8136.advance8136(s, ev(t0 + 100_000L, 103_000L, mult = 1.6), t0 + 100_000L)
        assertEquals(Stage8136.EXPANDING, s.stage)
        assertEquals("IGNITION_AGED", s.reason)
        s = LaunchLifecycle8136.advance8136(s, ev(t0 + 400_000L, 403_000L, migrated = true), t0 + 400_000L)
        assertEquals(Stage8136.MIGRATION, s.stage)
        s = LaunchLifecycle8136.advance8136(s, ev(t0 + 430_000L, 433_000L, migrated = true), t0 + 430_000L)
        assertEquals(Stage8136.MIGRATION, s.stage)
        s = LaunchLifecycle8136.advance8136(s, ev(t0 + 470_000L, 473_000L, migrated = true), t0 + 470_000L)
        assertEquals(Stage8136.CONTINUATION, s.stage)
        s = LaunchLifecycle8136.advance8136(s, ev(t0 + 500_000L, 503_000L, migrated = true, fade = true), t0 + 500_000L)
        assertEquals(Stage8136.DISTRIBUTION, s.stage)
        // Every entered stage carries the evidence time it was entered at; the order is the enum order.
        val order = Stage8136.values().toList()
        val entered = s.enteredAtMs.entries.sortedBy { it.value }.map { it.key }
        assertEquals(entered, entered.sortedBy { order.indexOf(it) })
        // A stage that was never observed carries no timestamp.
        val jump = LaunchLifecycle8136.advance8136(null, ev(t0, 30_000L, mult = 2.0), t0)
        assertEquals(Stage8136.EXPANDING, jump.stage)
        assertNull(jump.enteredAt8136(Stage8136.PRE_IGNITION))
        // The stateful observer applies the same rules per mint.
        val o1 = LaunchLifecycle8136.observe8136("SEQ8136mint", ev(t0, 20_000L), t0)
        val o2 = LaunchLifecycle8136.observe8136("SEQ8136mint", ev(t0 + 1_000L, 21_000L, mult = 1.6), t0 + 1_000L)
        assertEquals(Stage8136.PRE_IGNITION, o1.stage)
        assertEquals(Stage8136.IGNITION, o2.stage)
        assertTrue(LaunchLifecycle8136.statusLine8136().contains("launches="))
    }

    @Test fun realEarlyBuyingIsPreIgnitionNotAHandoff() {
        // 8118's defect: 3+ buyers, 60%+ buy share and rising acceleration satisfied the legacy IGNITION test, so the
        // sniper saw IGNITION at the very moment its thesis became observable. In the ordered lifecycle real early
        // buying before the price has moved 1.5x is PRE_IGNITION, and the sniper assesses it.
        val s = LaunchLifecycle8136.advance8136(null, ev(t0, 40_000L, buyTx = 6, buyers = 5, share = 82.0, accel = true, mult = 1.2), t0)
        assertEquals(Stage8136.PRE_IGNITION, s.stage)
        val r = LaunchLifecycle8136.sniperRoute8136(s, capUsd = 7_000.0, provenSetup = false, moonshotFloorUsd = 10_000.0)
        assertTrue(r.eligible)
        assertNull(r.handoffLane)
        // Not every fresh launch is a snipe: weak/unknown flow, a dev sell, or a consumed impulse is not PRE_IGNITION.
        assertEquals(Stage8136.BIRTH, LaunchLifecycle8136.advance8136(null, ev(t0, 40_000L, buyTx = 1, share = 90.0), t0).stage)
        assertEquals(Stage8136.BIRTH, LaunchLifecycle8136.advance8136(null, ev(t0, 40_000L, share = 40.0), t0).stage)
        assertEquals(Stage8136.BIRTH, LaunchLifecycle8136.advance8136(null, ev(t0, 40_000L, devSold = true), t0).stage)
        assertEquals(Stage8136.BIRTH, LaunchLifecycle8136.advance8136(null, priceOnly(t0, 40_000L), t0).stage)
        assertEquals(Stage8136.BIRTH, LaunchLifecycle8136.advance8136(null, ev(t0, 150_000L), t0).stage)    // past 120 s
        assertEquals(Stage8136.EXPANDING, LaunchLifecycle8136.advance8136(null, ev(t0, 40_000L, mult = 1.9), t0).stage)
        assertEquals(Stage8136.BIRTH, LaunchLifecycle8136.advance8136(null, ev(t0, null), t0).stage)          // birth unresolved
        assertEquals(Stage8136.BIRTH to "BIRTH_UNRESOLVED", LaunchLifecycle8136.target8136(ev(t0, null), null))
        assertEquals(Stage8136.IGNITION to "PRICE_IMPULSE_PRICE_ONLY", LaunchLifecycle8136.target8136(priceOnly(t0, 40_000L, mult = 1.6), null))
        // The sniper and election read the same route; the legacy test is the fallback only.
        val sniper = src("v3/scoring/ProjectSniperAI.kt")
        assertTrue(sniper.contains("val route8136 = phaseRoute8136(ts, launch7449, record = true)"))
        assertTrue(sniper.contains("if (route8136 == null && launch7449.phase != com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.PRE_IGNITION"))
        assertTrue(src("engine/LaneEntryContract6342.kt").contains("ProjectSniperAI.phaseRoute8136(ts, launch)"))
        assertTrue(src("engine/truth/LaunchPhaseAuthority7401.kt").contains("lifecycle8136 = ext8136.state,"))
    }

    @Test fun aFailedSniperPhaseIsANamedHandoffOrTerminalRefusal() {
        fun state(stage: Stage8136, prov: Provenance8136 = Provenance8136.SWAP_RECORDS) =
            LaunchLifecycle8136.State8136(stage, mapOf(stage to t0), t0, "TEST", prov)
        fun route(stage: Stage8136, cap: Double = 25_000.0, proven: Boolean = false, prov: Provenance8136 = Provenance8136.SWAP_RECORDS) =
            LaunchLifecycle8136.sniperRoute8136(state(stage, prov), cap, proven, moonshotFloorUsd = 10_000.0)
        val o = SpecialistOwnership7951
        // IGNITION: proven setup + swap-proven flow is assessed; otherwise handed to the runner lane by name.
        assertTrue(route(Stage8136.IGNITION, proven = true).eligible)
        assertFalse(route(Stage8136.IGNITION, proven = true, prov = Provenance8136.PRICE_ONLY).eligible)
        val ign = route(Stage8136.IGNITION)
        assertFalse(ign.eligible)
        assertEquals("MOONSHOT", ign.handoffLane)
        assertEquals("SHITCOIN", route(Stage8136.IGNITION, cap = 6_000.0).handoffLane)
        assertEquals("MOONSHOT", route(Stage8136.EXPANDING).handoffLane)
        assertEquals("EXPRESS", route(Stage8136.MIGRATION).handoffLane)
        assertEquals("MOONSHOT", route(Stage8136.CONTINUATION).handoffLane)
        for (s in listOf(Stage8136.BIRTH, Stage8136.IGNITION, Stage8136.EXPANDING, Stage8136.MIGRATION, Stage8136.CONTINUATION)) {
            val r = route(s)
            assertFalse(s.name, r.eligible)
            assertNotNull(s.name, r.handoffLane)
            assertTrue(r.reason, o.laneFitRefusal8132(r.reason))       // SpecialistOwnership hands it to the lane that wants it
        }
        // DISTRIBUTION is a terminal refusal: no lane is handed a fading launch by the sniper.
        val dist = route(Stage8136.DISTRIBUTION)
        assertFalse(dist.eligible)
        assertNull(dist.handoffLane)
        assertEquals("SNIPER_TERMINAL_8136_DISTRIBUTION", dist.reason)
        assertFalse(o.laneFitRefusal8132(dist.reason))
        // Every refusal is recorded by name.
        LaunchLifecycle8136.noteRoute8136("HAND8136mint", ign)
        assertEquals(ign, LaunchLifecycle8136.lastHandoff8136("HAND8136mint"))
        // The 8118 proven-setup rule is unchanged.
        assertFalse(com.lifecyclebot.v3.scoring.ProjectSniperAI.ignitionAdmissible8118(
            com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.IGNITION, provenSetup = false))
    }
}
