package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.BalanceProofWaitState
import com.lifecyclebot.engine.sell.SellAmountAuthority
import com.lifecyclebot.engine.sell.SellRouteEndpoint8144
import com.lifecyclebot.engine.sell.SellRouteEndpoint8144.Admit8144
import com.lifecyclebot.engine.sell.SellRouteEndpoint8144.Failure8144
import com.lifecyclebot.engine.sell.SellRouteEndpoint8144.Route8144
import com.lifecyclebot.engine.sell.SellRouteEndpoint8144.Venue8144
import com.lifecyclebot.engine.sell.SellRouteFinality8144
import com.lifecyclebot.engine.sell.SellRouteFinality8144.Balance8144
import com.lifecyclebot.engine.sell.SellRouteFinality8144.ChainStatus8144
import com.lifecyclebot.engine.sell.SellRouteFinality8144.ProviderRead8144
import com.lifecyclebot.engine.sell.SellRouteFinality8144.Read8144
import com.lifecyclebot.engine.sell.SellRouteFinality8144.SigDecision8144
import com.lifecyclebot.engine.sell.SellRouteFinality8144.Work8144
import com.lifecyclebot.engine.sell.SellRouteFinality8144.WorkItem8144
import com.lifecyclebot.engine.sell.SellRouteProbe8144
import com.lifecyclebot.engine.sell.SellRouteSpine8144
import com.lifecyclebot.engine.sell.SellRouteSpine8144.Stage8144
import com.lifecyclebot.engine.sell.SellRouteSpine8144.Timer8144
import com.lifecyclebot.engine.sell.SellRouteVenueProbe8144
import com.lifecyclebot.network.SignedTxLedger8085
import com.lifecyclebot.network.SolanaWallet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8144 — P0-E catastrophic sell routing and confirmation: PumpPortal 4xx classification and route switch, no
 * resubmission to a hard-failed route, duplicate-sign prevention with a pending signature, RPC failure vs confirmed
 * absence, catastrophic priority ordering, and sell-spine stage accounting.
 */
class Aate8144SellRouteTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val mint = "CATCHmint1111111111111111111111111111111pump"
    private val t0 = 1_000_000L

    @Before fun setUp() {
        SellRouteEndpoint8144.resetForTest8144()
        SellRouteSpine8144.resetForTest8144()
        SellRouteVenueProbe8144.resetForTest8144()
        SellRouteProbe8144.resetForTest8144()
        SignedTxLedger8085.persister = null
        SignedTxLedger8085.resetForTest()
    }

    @After fun tearDown() {
        SellRouteEndpoint8144.resetForTest8144()
        SellRouteSpine8144.resetForTest8144()
        SellRouteVenueProbe8144.resetForTest8144()
        SellRouteProbe8144.resetForTest8144()
        SignedTxLedger8085.persister = null
        SignedTxLedger8085.resetForTest()
    }

    // ── 1. PumpPortal 4xx classification ──

    @Test fun pumpPortal4xxSplitsHardEndpointFailuresFromSoftOnes() {
        assertEquals(Failure8144.HARD_BAD_REQUEST, SellRouteEndpoint8144.classifyHttp8144(400, "PumpPortal HTTP 400: Bad Request"))
        assertEquals(Failure8144.HARD_UNSUPPORTED_MINT, SellRouteEndpoint8144.classifyHttp8144(400, "{\"error\":\"Bonding curve complete, token migrated\"}"))
        assertEquals(Failure8144.HARD_UNSUPPORTED_MINT, SellRouteEndpoint8144.classifyHttp8144(404, ""))
        assertEquals(Failure8144.HARD_AUTH, SellRouteEndpoint8144.classifyHttp8144(401, ""))
        assertEquals(Failure8144.HARD_AUTH, SellRouteEndpoint8144.classifyHttp8144(403, "forbidden"))
        assertTrue(Failure8144.HARD_AUTH.global && Failure8144.HARD_AUTH.hard)
        assertEquals(Failure8144.SOFT_RATE_LIMIT, SellRouteEndpoint8144.classifyHttp8144(429, "Too Many Requests"))
        assertEquals(Failure8144.SOFT_SERVER, SellRouteEndpoint8144.classifyHttp8144(503, ""))
        assertEquals(Failure8144.OK, SellRouteEndpoint8144.classifyHttp8144(200, ""))
        assertEquals(400, SellRouteEndpoint8144.httpCodeFrom8144("PumpPortal HTTP 400: Bad Request"))
        assertNull(SellRouteEndpoint8144.httpCodeFrom8144("PumpPortal returned empty body"))
        // A response we generated ourselves never reached PumpPortal and says nothing about it.
        assertNull(SellRouteEndpoint8144.classifyMessage8144("LOCAL_LOCKOUT (not sent to PumpPortal): lockout"))
        // An HTTP 200 carrying a JSON error is a refusal, not a success.
        assertTrue(SellRouteEndpoint8144.classifyMessage8144("PumpPortal JSON error (HTTP 200): {\"error\":\"bad\"}")!!.hard)
        assertEquals(Failure8144.SOFT_TRANSPORT, SellRouteEndpoint8144.classifyMessage8144("PumpPortal returned tx too small (12 bytes)"))
        assertEquals(Failure8144.SOFT_SERVER, SellRouteEndpoint8144.classifyMessage8144("PumpPortal HTTP 502: gateway"))
    }

    // ── 2. no resubmission to a hard-failed route ──

    @Test fun hardFailedRouteIsNeverResubmittedUntilTheConfirmedVenueChanges() {
        assertEquals(Admit8144.ALLOWED, SellRouteEndpoint8144.admit8144(mint, Route8144.PUMPPORTAL, t0, "PUMP_CURVE"))
        SellRouteEndpoint8144.record8144(mint, Route8144.PUMPPORTAL, Failure8144.HARD_BAD_REQUEST, t0 + 10, "PUMP_CURVE")
        // Same venue: blocked on every later attempt inside the block window.
        assertEquals(Admit8144.HARD_BLOCKED, SellRouteEndpoint8144.admit8144(mint, Route8144.PUMPPORTAL, t0 + 1_000, "PUMP_CURVE"))
        assertEquals(Admit8144.HARD_BLOCKED, SellRouteEndpoint8144.admit8144(mint, Route8144.PUMPPORTAL, t0 + 300_000, "PUMP_CURVE"))
        assertEquals(Admit8144.HARD_BLOCKED, SellRouteEndpoint8144.admit8144(mint, Route8144.PUMPPORTAL, t0 + 300_000, ""))
        assertEquals(Failure8144.HARD_BAD_REQUEST, SellRouteEndpoint8144.hardCause8144(mint, Route8144.PUMPPORTAL, t0 + 300_000))
        // The block is per (mint, route): Jupiter and other mints are unaffected.
        assertEquals(Admit8144.ALLOWED, SellRouteEndpoint8144.peek8144(mint, Route8144.JUPITER, t0 + 1_000))
        assertEquals(Admit8144.ALLOWED, SellRouteEndpoint8144.peek8144("otherMint", Route8144.PUMPPORTAL, t0 + 1_000))
        // The curve completed (migrated): the endpoint may answer differently, one re-check is admitted.
        assertEquals(Admit8144.ALLOWED, SellRouteEndpoint8144.admit8144(mint, Route8144.PUMPPORTAL, t0 + 301_000, "PUMPSWAP"))
        assertNull(SellRouteEndpoint8144.hardCause8144(mint, Route8144.PUMPPORTAL, t0 + 301_000))
    }

    @Test fun hardBlockExpiresAndAuthFailureBlocksTheRouteForEveryMint() {
        SellRouteEndpoint8144.record8144(mint, Route8144.PUMPPORTAL, Failure8144.HARD_UNSUPPORTED_MINT, t0, "PUMP_CURVE")
        assertEquals(Admit8144.HARD_BLOCKED, SellRouteEndpoint8144.peek8144(mint, Route8144.PUMPPORTAL, t0 + SellRouteEndpoint8144.HARD_BLOCK_MS_8144 - 1, "PUMP_CURVE"))
        assertEquals(Admit8144.ALLOWED, SellRouteEndpoint8144.peek8144(mint, Route8144.PUMPPORTAL, t0 + SellRouteEndpoint8144.HARD_BLOCK_MS_8144 + 1, "PUMP_CURVE"))
        SellRouteEndpoint8144.record8144(mint, Route8144.PUMPPORTAL, Failure8144.HARD_AUTH, t0, "PUMP_CURVE")
        assertEquals(Admit8144.AUTH_BLOCKED, SellRouteEndpoint8144.admit8144("anotherMint", Route8144.PUMPPORTAL, t0 + 1_000))
        assertEquals(Admit8144.ALLOWED, SellRouteEndpoint8144.admit8144("anotherMint", Route8144.RAYDIUM, t0 + 1_000))
    }

    @Test fun softFailuresBackOffAndAreBoundedPerRoute() {
        assertEquals(2_000L, SellRouteEndpoint8144.softBackoffMs8144(1))
        assertEquals(4_000L, SellRouteEndpoint8144.softBackoffMs8144(2))
        assertEquals(8_000L, SellRouteEndpoint8144.softBackoffMs8144(3))
        assertEquals(15_000L, SellRouteEndpoint8144.softBackoffMs8144(4))
        assertEquals(15_000L, SellRouteEndpoint8144.softBackoffMs8144(9))
        assertEquals(Admit8144.ALLOWED, SellRouteEndpoint8144.admit8144(mint, Route8144.PUMPPORTAL, t0))
        SellRouteEndpoint8144.record8144(mint, Route8144.PUMPPORTAL, Failure8144.SOFT_SERVER, t0)
        assertEquals(Admit8144.BACKOFF, SellRouteEndpoint8144.admit8144(mint, Route8144.PUMPPORTAL, t0 + 1_999))
        assertEquals(Admit8144.ALLOWED, SellRouteEndpoint8144.admit8144(mint, Route8144.PUMPPORTAL, t0 + 2_001))
        SellRouteEndpoint8144.record8144(mint, Route8144.PUMPPORTAL, Failure8144.SOFT_RATE_LIMIT, t0 + 2_001)
        assertEquals(Admit8144.ALLOWED, SellRouteEndpoint8144.admit8144(mint, Route8144.PUMPPORTAL, t0 + 6_002))
        SellRouteEndpoint8144.record8144(mint, Route8144.PUMPPORTAL, Failure8144.SOFT_SERVER, t0 + 6_002)
        // Three endpoint failures inside the window: the route's budget is spent even after the backoff.
        assertEquals(Admit8144.BUDGET_EXHAUSTED, SellRouteEndpoint8144.peek8144(mint, Route8144.PUMPPORTAL, t0 + 14_003))
        assertEquals(Admit8144.ALLOWED, SellRouteEndpoint8144.peek8144(mint, Route8144.PUMPPORTAL, t0 + SellRouteEndpoint8144.ATTEMPT_WINDOW_MS_8144 + 1))
        // V5.0.8164 — an endpoint that answered with a transaction has not landed anything: the budget stays spent.
        SellRouteEndpoint8144.record8144(mint, Route8144.PUMPPORTAL, Failure8144.OK, t0 + 14_004)
        assertEquals(Admit8144.BUDGET_EXHAUSTED, SellRouteEndpoint8144.peek8144(mint, Route8144.PUMPPORTAL, t0 + 14_005))
        // Only a chain-confirmed landing resets it.
        SellRouteEndpoint8144.landed8164(mint, Route8144.PUMPPORTAL)
        assertEquals(Admit8144.ALLOWED, SellRouteEndpoint8144.peek8144(mint, Route8144.PUMPPORTAL, t0 + 14_006))
    }

    // ── V5.0.8164: accepted-but-never-landed sells hand the emergency to the aggregator ──

    @Test fun unlandedPumpPortalSellsBackOffAndStopTheEmergencyFromSkippingJupiter() {
        assertEquals(45_000L, SellRouteEndpoint8144.unlandedBackoffMs8164(1))
        assertEquals(90_000L, SellRouteEndpoint8144.unlandedBackoffMs8164(2))
        assertEquals(300_000L, SellRouteEndpoint8144.unlandedBackoffMs8164(9))
        assertTrue(SellRouteEndpoint8144.directRouteViable8144(mint, Venue8144.PUMP_CURVE, t0))
        // The build answered (OK), the send was accepted, and the signature expired unseen.
        SellRouteEndpoint8144.admit8144(mint, Route8144.PUMPPORTAL, t0, Venue8144.PUMP_CURVE.name)
        SellRouteEndpoint8144.record8144(mint, Route8144.PUMPPORTAL, Failure8144.OK, t0 + 1, Venue8144.PUMP_CURVE.name)
        SellRouteEndpoint8144.record8144(mint, Route8144.PUMPPORTAL, Failure8144.UNLANDED, t0 + 60_000, Venue8144.PUMP_CURVE.name)
        assertEquals(1, SellRouteEndpoint8144.unlanded8164(mint, Route8144.PUMPPORTAL))
        // PumpPortal backs off; an escalated emergency may no longer skip the aggregator, and Jupiter goes first.
        assertEquals(Admit8144.BACKOFF, SellRouteEndpoint8144.peek8144(mint, Route8144.PUMPPORTAL, t0 + 60_001, Venue8144.PUMP_CURVE.name))
        assertFalse(SellRouteEndpoint8144.directRouteViable8144(mint, Venue8144.PUMP_CURVE, t0 + 60_001))
        assertFalse(SellRouteEndpoint8144.pumpPortalFirst8144(mint, Venue8144.PUMP_CURVE, t0 + 60_001))
        assertEquals(Route8144.JUPITER, SellRouteEndpoint8144.nextRoute8144(mint, Venue8144.PUMP_CURVE, t0 + 60_001))
        // A later build that merely answers does not clear the backoff.
        SellRouteEndpoint8144.record8144(mint, Route8144.PUMPPORTAL, Failure8144.OK, t0 + 60_002, Venue8144.PUMP_CURVE.name)
        assertEquals(Admit8144.BACKOFF, SellRouteEndpoint8144.peek8144(mint, Route8144.PUMPPORTAL, t0 + 60_003, Venue8144.PUMP_CURVE.name))
        // After the backoff it may be tried again; a landing clears the streak.
        assertEquals(Admit8144.ALLOWED, SellRouteEndpoint8144.peek8144(mint, Route8144.PUMPPORTAL, t0 + 60_000 + 45_001, Venue8144.PUMP_CURVE.name))
        SellRouteEndpoint8144.landed8164(mint, Route8144.PUMPPORTAL)
        assertEquals(0, SellRouteEndpoint8144.unlanded8164(mint, Route8144.PUMPPORTAL))
    }

    // ── 3. route switch from confirmed venue metadata ──

    @Test fun confirmedVenueDrivesRouteOrderAndAHardFailedPumpPortalSwitchesRoute() {
        assertEquals(Venue8144.PUMP_CURVE, SellRouteEndpoint8144.venueFrom8144(true, false, null, null))
        assertEquals(Venue8144.PUMPSWAP, SellRouteEndpoint8144.venueFrom8144(true, true, null, null))
        assertEquals(Venue8144.RAYDIUM, SellRouteEndpoint8144.venueFrom8144(true, true, true, null))
        assertEquals(Venue8144.RAYDIUM, SellRouteEndpoint8144.venueFrom8144(false, null, true, null))
        assertEquals(Venue8144.AGGREGATOR_ONLY, SellRouteEndpoint8144.venueFrom8144(false, null, null, null))
        assertEquals(Venue8144.UNKNOWN, SellRouteEndpoint8144.venueFrom8144(null, null, null, null))
        assertEquals(Route8144.PUMPPORTAL, SellRouteEndpoint8144.routeOrder8144(Venue8144.PUMP_CURVE).first())
        assertEquals(Route8144.RAYDIUM, SellRouteEndpoint8144.routeOrder8144(Venue8144.RAYDIUM).first())
        assertEquals(Route8144.JUPITER, SellRouteEndpoint8144.routeOrder8144(Venue8144.AGGREGATOR_ONLY).first())

        assertTrue(SellRouteEndpoint8144.directRouteViable8144(mint, Venue8144.PUMP_CURVE, t0))
        assertTrue(SellRouteEndpoint8144.pumpPortalFirst8144(mint, Venue8144.PUMP_CURVE, t0))
        assertEquals(Route8144.PUMPPORTAL, SellRouteEndpoint8144.nextRoute8144(mint, Venue8144.PUMP_CURVE, t0))
        SellRouteEndpoint8144.record8144(mint, Route8144.PUMPPORTAL, Failure8144.HARD_BAD_REQUEST, t0, Venue8144.PUMP_CURVE.name)
        // PumpPortal is out: the next route is Jupiter, and an escalated emergency may no longer skip the aggregator.
        assertEquals(Route8144.JUPITER, SellRouteEndpoint8144.nextRoute8144(mint, Venue8144.PUMP_CURVE, t0 + 1))
        assertFalse(SellRouteEndpoint8144.directRouteViable8144(mint, Venue8144.PUMP_CURVE, t0 + 1))
        assertFalse(SellRouteEndpoint8144.pumpPortalFirst8144(mint, Venue8144.PUMP_CURVE, t0 + 1))
        // A mint with no pump curve sells through the aggregator first.
        assertFalse(SellRouteEndpoint8144.pumpPortalFirst8144("noCurve", Venue8144.AGGREGATOR_ONLY, t0))
        assertFalse(SellRouteEndpoint8144.directRouteViable8144("noCurve", Venue8144.AGGREGATOR_ONLY, t0))
        assertTrue(SellRouteEndpoint8144.directRouteViable8144("rayMint", Venue8144.RAYDIUM, t0))
    }

    @Test fun venueCacheKeepsACompleteCurveAndExpiresAnActiveOne() {
        val active = SellRouteVenueProbe8144.Probe8144(Venue8144.PUMP_CURVE, true, false, null, t0)
        val complete = SellRouteVenueProbe8144.Probe8144(Venue8144.PUMPSWAP, true, true, null, t0)
        val absent = SellRouteVenueProbe8144.Probe8144(Venue8144.AGGREGATOR_ONLY, false, null, null, t0)
        val unknown = SellRouteVenueProbe8144.Probe8144(Venue8144.UNKNOWN, null, null, null, t0)
        assertTrue(SellRouteVenueProbe8144.fresh8144(active, t0 + 30_000))
        assertFalse(SellRouteVenueProbe8144.fresh8144(active, t0 + 30_001))
        assertTrue(SellRouteVenueProbe8144.fresh8144(complete, t0 + 86_400_000))
        assertTrue(SellRouteVenueProbe8144.fresh8144(absent, t0 + 600_000))
        assertFalse(SellRouteVenueProbe8144.fresh8144(absent, t0 + 600_001))
        assertFalse(SellRouteVenueProbe8144.fresh8144(unknown, t0))
        SellRouteVenueProbe8144.putForTest8144(mint, complete)
        assertEquals(Venue8144.PUMPSWAP, SellRouteVenueProbe8144.cached8144(mint, t0 + 5_000_000))
        assertEquals(Venue8144.UNKNOWN, SellRouteVenueProbe8144.cached8144("never", t0))
    }

    // ── 4. duplicate-sign prevention with a pending signature ──

    @Test fun pendingSignatureIsReusedNeverResignedUntilChainTruthSettlesIt() {
        val sig = "5PendingSig"
        assertEquals(SigDecision8144.REUSE_PENDING, SellRouteFinality8144.signatureDecision8144(sig, 10_000L, ChainStatus8144.NOT_FOUND))
        assertEquals(SigDecision8144.REUSE_PENDING, SellRouteFinality8144.signatureDecision8144(sig, 10_000L, ChainStatus8144.RPC_FAILED))
        assertEquals(SigDecision8144.REUSE_PENDING, SellRouteFinality8144.signatureDecision8144(sig, 179_999L, ChainStatus8144.RPC_FAILED))
        assertEquals(SigDecision8144.LANDED_SELL_REMAINDER, SellRouteFinality8144.signatureDecision8144(sig, 5_000L, ChainStatus8144.LANDED))
        assertEquals(SigDecision8144.SIGN_NEW, SellRouteFinality8144.signatureDecision8144(sig, 5_000L, ChainStatus8144.FAILED_ON_CHAIN))
        assertEquals(SigDecision8144.SIGN_NEW, SellRouteFinality8144.signatureDecision8144(sig, 5_000L, ChainStatus8144.EXPIRED_PROVEN))
        // Past any blockhash life an unseen signature can never land: the stale guard no longer parks the exit.
        assertEquals(SigDecision8144.SIGN_NEW, SellRouteFinality8144.signatureDecision8144(sig, SellRouteFinality8144.UNLANDABLE_AFTER_MS_8144, ChainStatus8144.NOT_FOUND))
        assertEquals(SigDecision8144.SIGN_NEW, SellRouteFinality8144.signatureDecision8144(null, 0L, ChainStatus8144.NOT_FOUND))
        assertEquals(SigDecision8144.SIGN_NEW, SellRouteFinality8144.signatureDecision8144("PHANTOM_1", 0L, ChainStatus8144.NOT_FOUND))
        assertEquals(ChainStatus8144.NOT_FOUND, SellRouteFinality8144.statusFrom8144(false, null, null))
        assertEquals(ChainStatus8144.FAILED_ON_CHAIN, SellRouteFinality8144.statusFrom8144(true, "{\"InstructionError\":[2,{\"Custom\":6001}]}", "confirmed"))
        assertEquals(ChainStatus8144.LANDED, SellRouteFinality8144.statusFrom8144(true, null, "confirmed"))
        assertEquals(ChainStatus8144.LANDED, SellRouteFinality8144.statusFrom8144(true, null, "finalized"))
        assertEquals(ChainStatus8144.NOT_FOUND, SellRouteFinality8144.statusFrom8144(true, null, "processed"))
    }

    @Test fun ledgerRowsAnswerChainStatusAndAnUnresolvedSellRefusesASecondSignature() {
        SignedTxLedger8085.record("s1", "bytes", "hash", SignedTxLedger8085.Intent(mint, "SELL", 10L), nowMs = t0)
        try {
            SignedTxLedger8085.record("s2", "other", "hash", SignedTxLedger8085.Intent(mint, "SELL", 10L), nowMs = t0 + 1)
            fail("a second sell must not be signed while the first is unresolved")
        } catch (e: SolanaWallet.DuplicateOrderPrevented8085) { assertEquals("s1", e.existingSignature) }
        // No wallet, unresolved row: unknown, never a reason to re-sign.
        assertEquals(ChainStatus8144.RPC_FAILED, SellRouteProbe8144.chainStatus8144(null, "s1"))
        assertEquals(SigDecision8144.REUSE_PENDING,
            SellRouteFinality8144.signatureDecision8144("s1", SellRouteProbe8144.pendingAgeMs8144("s1", t0 + 60_000), ChainStatus8144.RPC_FAILED))
        SignedTxLedger8085.transition("s1", SignedTxLedger8085.State.CONFIRMED, nowMs = t0 + 2)
        assertEquals(ChainStatus8144.LANDED, SellRouteProbe8144.chainStatus8144(null, "s1"))
        SignedTxLedger8085.record("s3", "b3", "hash", SignedTxLedger8085.Intent("m3", "SELL"), nowMs = t0)
        SignedTxLedger8085.transition("s3", SignedTxLedger8085.State.EXPIRED_PROVEN, nowMs = t0 + 1)
        assertEquals(ChainStatus8144.EXPIRED_PROVEN, SellRouteProbe8144.chainStatus8144(null, "s3"))
    }

    // ── 5. RPC failure vs confirmed absence ──

    @Test fun onlyDistinctProvidersAgreeingTheMintIsAbsentAllowZeroFinality() {
        fun r(p: String, k: Read8144, raw: Long = 0L) = ProviderRead8144(p, k, BigInteger.valueOf(raw), if (raw > 0L) 6 else -1)
        val allFailed = SellRouteFinality8144.classifyBalance8144(listOf(r("helius", Read8144.FAILED), r("alchemy", Read8144.FAILED)))
        assertEquals(Balance8144.RPC_FAILURE, allFailed.verdict)
        assertFalse(SellRouteFinality8144.zeroFinalityAllowed8144(allFailed))
        assertEquals(Balance8144.RPC_FAILURE, SellRouteFinality8144.classifyBalance8144(emptyList()).verdict)
        val oneSees = SellRouteFinality8144.classifyBalance8144(listOf(r("helius", Read8144.ABSENT), r("alchemy", Read8144.FAILED), r("public", Read8144.FAILED)))
        assertEquals(Balance8144.UNCORROBORATED_ABSENT, oneSees.verdict)
        assertFalse(SellRouteFinality8144.zeroFinalityAllowed8144(oneSees))
        // The same provider twice is still one provider.
        assertEquals(Balance8144.UNCORROBORATED_ABSENT,
            SellRouteFinality8144.classifyBalance8144(listOf(r("helius", Read8144.ABSENT), r("helius", Read8144.ABSENT))).verdict)
        val two = SellRouteFinality8144.classifyBalance8144(listOf(r("helius", Read8144.ABSENT), r("alchemy", Read8144.ABSENT), r("public", Read8144.FAILED)))
        assertEquals(Balance8144.CONFIRMED_ABSENT, two.verdict)
        assertTrue(SellRouteFinality8144.zeroFinalityAllowed8144(two))
        // One provider holding beats any number of absences: the position is live and sells (smallest held raw).
        val held = SellRouteFinality8144.classifyBalance8144(listOf(r("helius", Read8144.ABSENT), r("alchemy", Read8144.HELD, 1_000_000L), r("public", Read8144.HELD, 900_000L)))
        assertEquals(Balance8144.HELD, held.verdict)
        assertEquals(BigInteger.valueOf(900_000L), held.raw)
        assertEquals(6, held.decimals)
        assertTrue(SellRouteProbe8144.held8144(held))
        // A frozen account is held — never zero, never routed.
        val frozen = SellRouteFinality8144.classifyBalance8144(listOf(r("helius", Read8144.FROZEN), r("alchemy", Read8144.ABSENT), r("public", Read8144.ABSENT)))
        assertEquals(Balance8144.FROZEN_HELD, frozen.verdict)
        assertFalse(SellRouteFinality8144.zeroFinalityAllowed8144(frozen))
        assertFalse(SellRouteProbe8144.held8144(frozen))
        // Dust is not a holding.
        assertEquals(Balance8144.CONFIRMED_ABSENT,
            SellRouteFinality8144.classifyBalance8144(listOf(r("helius", Read8144.HELD, 1L), r("alchemy", Read8144.ABSENT))).verdict)
    }

    @Test fun providersAreDistinctHostsAndAMultiProviderReadIsWalletScanAuthority() {
        val eps = SellRouteProbe8144.distinctProviders8144(listOf(
            "https://mainnet.helius-rpc.com/?api-key=x", "https://mainnet.helius-rpc.com/?api-key=y",
            "https://solana-mainnet.g.alchemy.com/v2/k", "https://api.mainnet-beta.solana.com", "https://rpc.ankr.com/solana",
        ))
        assertEquals(3, eps.size)
        assertEquals(listOf("mainnet.helius-rpc.com", "solana-mainnet.g.alchemy.com", "api.mainnet-beta.solana.com"), eps.map { SellRouteProbe8144.host8144(it) })
        val c = SellAmountAuthority.Resolution.Confirmed(BigInteger.TEN, 6, SellAmountAuthority.Source.MULTI_PROVIDER_8144)
        assertEquals(SellAmountAuthority.BalanceSource.WALLET_SCAN_CONFIRMED, SellAmountAuthority.balanceSource(c))
        assertTrue(SellAmountAuthority.canBroadcastLive(c))
    }

    // ── 6. catastrophic priority ordering ──

    @Test fun catastrophicExitsRankAheadOfEveryOtherKindOfWork() {
        assertEquals(Work8144.CATASTROPHIC_EXIT, SellRouteFinality8144.workForExit8144("CATASTROPHIC_HARD_BACKSTOP_-25"))
        assertEquals(Work8144.CATASTROPHIC_EXIT, SellRouteFinality8144.workForExit8144("RAPID_CATASTROPHE_STOP"))
        assertEquals(Work8144.CATASTROPHIC_EXIT, SellRouteFinality8144.workForExit8144("RUG_DRAIN"))
        assertEquals(Work8144.PROTECTIVE_EXIT, SellRouteFinality8144.workForExit8144("STRICT_SL_-12"))
        assertEquals(Work8144.PROFIT_EXIT, SellRouteFinality8144.workForExit8144("TRAILING_STOP"))
        assertEquals(Work8144.DISCRETIONARY_EXIT, SellRouteFinality8144.workForExit8144("TIME_EXIT"))
        val ordered = SellRouteFinality8144.order8144(listOf(
            WorkItem8144("enrich", Work8144.ENRICHMENT, 0L),
            WorkItem8144("entry", Work8144.ENTRY, 1L),
            WorkItem8144("tp", Work8144.DISCRETIONARY_EXIT, 2L),
            WorkItem8144("sl", Work8144.PROTECTIVE_EXIT, 3L),
            WorkItem8144("catch-late", Work8144.CATASTROPHIC_EXIT, 9L),
            WorkItem8144("catch-early", Work8144.CATASTROPHIC_EXIT, 5L),
        ))
        assertEquals(listOf("catch-early", "catch-late", "sl", "tp", "entry", "enrich"), ordered.map { it.key })
        assertTrue(SellRouteFinality8144.bypassesQueue8144("CATASTROPHIC_HARD_BACKSTOP_-25"))
        assertTrue(SellRouteFinality8144.bypassesQueue8144("STRICT_SL_-12"))
        // V5.0.8221 — a profit stop (trail / peak giveback) is time-critical and bypasses the bot-loop queue too.
        assertTrue(SellRouteFinality8144.bypassesQueue8144("TRAILING_STOP"))
        assertFalse(SellRouteFinality8144.bypassesQueue8144("TIME_EXIT"))
        assertEquals(3_000L, SellRouteFinality8144.proofPollBackoffMs8144("TICK_CATASTROPHIC_CONFIRMED_-60PCT", 30_000L))
        assertEquals(2_000L, SellRouteFinality8144.proofPollBackoffMs8144("TICK_CATASTROPHIC_CONFIRMED_-60PCT", 2_000L))
        assertEquals(3_000L, SellRouteFinality8144.proofPollBackoffMs8144("TAKE_PROFIT", 30_000L))   // V5.0.8221 — profit stops on the fast path
        assertEquals(30_000L, SellRouteFinality8144.proofPollBackoffMs8144("TIME_EXIT", 30_000L))
        // The proof wait no longer ranks a catastrophic exit below a stop-loss (CATASTROPHIC was not CATASTROPHE).
        assertEquals(100, BalanceProofWaitState.priorityFor("CATASTROPHIC_HARD_BACKSTOP_-25"))
        assertEquals(100, BalanceProofWaitState.priorityFor("RAPID_CATASTROPHE_STOP"))
        assertEquals(80, BalanceProofWaitState.priorityFor("STRICT_SL_-12"))
    }

    // ── 7. sell spine stage accounting ──

    @Test fun stageTimerChargesEveryMillisecondToExactlyOneStage() {
        val t = Timer8144(mint, "CATASTROPHIC_HARD_BACKSTOP_-25", 1_000L, true)
        assertFalse(t.mark(Stage8144.TRIGGER, 1_100L))
        assertTrue(t.mark(Stage8144.CLOSE_LEASE, 1_500L))
        t.mark(Stage8144.TOKEN_BALANCE, 2_300L)
        t.mark(Stage8144.VENUE_RESOLVE, 2_400L)
        t.mark(Stage8144.QUOTE_BUILD, 3_000L)
        t.mark(Stage8144.SIGN, 3_050L)
        t.mark(Stage8144.BROADCAST, 3_200L)
        t.mark(Stage8144.RPC_ACK, 3_300L)
        t.mark(Stage8144.CHAIN_FINALITY, 5_300L)
        t.mark(Stage8144.WALLET_DELTA, 5_400L)
        t.mark(Stage8144.CANONICAL_CLOSE, 5_450L)
        assertEquals(4_450L, t.accountedMs())
        assertEquals(t.lastMarkAtMs - t.triggerAtMs, t.accountedMs())
        assertEquals(500L, t.queueMs())
        assertEquals(800L, t.balanceMs())
        assertEquals(700L, t.quoteMs())
        assertEquals(50L, t.signMs())
        assertEquals(250L, t.broadcastMs())
        assertEquals(2_150L, t.finalityMs())
        assertEquals(2_200L, t.triggerToBroadcastMs())
        assertEquals(Stage8144.CANONICAL_CLOSE, t.lastStage)
    }

    @Test fun failedAttemptChargesTheStageInProgressAndRetryWaitIsQueue() {
        val t = Timer8144(mint, "STRICT_SL_-12", 0L, true)
        t.mark(Stage8144.CLOSE_LEASE, 200L)
        t.mark(Stage8144.TOKEN_BALANCE, 900L)
        assertEquals(Stage8144.VENUE_RESOLVE, SellRouteSpine8144.next8144(Stage8144.TOKEN_BALANCE))
        assertEquals(Stage8144.VENUE_RESOLVE, t.fail("ROUTE_FAILED_NO_SIGNATURE", 1_400L))
        assertTrue(t.lastFailureFresh)
        assertEquals(1, t.failures)
        // An echo of the same failure (no stage closed since) is not a second failure.
        t.fail("SELL_FAILED", 1_500L)
        assertFalse(t.lastFailureFresh)
        assertEquals(1, t.failures)
        assertEquals(Stage8144.VENUE_RESOLVE, t.failedStage)
        // The retry waits for its lease: that wait is queue time.
        t.mark(Stage8144.CLOSE_LEASE, 4_500L)
        assertEquals(200L + 3_000L, t.queueMs())
        assertEquals(600L, t.durationMs(Stage8144.VENUE_RESOLVE))
        assertEquals(t.lastMarkAtMs - t.triggerAtMs, t.accountedMs())
        // A clock that steps backwards never produces a negative stage.
        t.mark(Stage8144.TOKEN_BALANCE, 4_000L)
        assertEquals(700L, t.balanceMs())
        assertEquals(-1L, t.triggerToBroadcastMs())
        assertEquals(Stage8144.CHAIN_FINALITY, t.fail("SELL_FAILED_CONFIRMED", 4_600L, Stage8144.CHAIN_FINALITY))
    }

    @Test fun spineRegistryFollowsPhasesResultsAndCanonicalClose() {
        assertEquals(Stage8144.TOKEN_BALANCE, SellRouteSpine8144.stageForPhase8144("SELL_BALANCE_CHECK"))
        assertEquals(Stage8144.QUOTE_BUILD, SellRouteSpine8144.stageForPhase8144("SELL_TX_BUILT"))
        assertEquals(Stage8144.SIGN, SellRouteSpine8144.stageForPhase8144("SIGNED"))
        assertEquals(Stage8144.BROADCAST, SellRouteSpine8144.stageForPhase8144("SUBMIT_ATTEMPTED"))
        assertEquals(Stage8144.RPC_ACK, SellRouteSpine8144.stageForPhase8144("ACCEPTED"))
        assertEquals(Stage8144.CHAIN_FINALITY, SellRouteSpine8144.stageForPhase8144("CONFIRMED"))
        assertEquals(Stage8144.WALLET_DELTA, SellRouteSpine8144.stageForPhase8144("SELL_TX_PARSE_OK"))
        assertNull(SellRouteSpine8144.stageForPhase8144("SELL_QUOTE_TRY"))
        assertEquals(Stage8144.CHAIN_FINALITY, SellRouteSpine8144.failureForPhase8144("FAILED_ON_CHAIN"))
        assertEquals(Stage8144.BROADCAST, SellRouteSpine8144.failureForPhase8144("SUBMIT_REJECTED"))
        assertEquals(Stage8144.TRIGGER, SellRouteSpine8144.failureForPhase8144("SELL_ROUTE_FAILED_NO_SIGNATURE"))
        assertNull(SellRouteSpine8144.failureForPhase8144("SELL_QUOTE_OK"))

        val pid = "pump:1:1"
        assertNotNull(SellRouteSpine8144.trigger8144(mint, "CATASTROPHIC_HARD_BACKSTOP_-25", pid, 10_000L))
        // An emergency keeps its earliest trigger across re-requests.
        assertEquals(10_000L, SellRouteSpine8144.trigger8144(mint, "CATASTROPHIC_HARD_BACKSTOP_-25", pid, 12_000L)!!.triggerAtMs)
        assertTrue(SellRouteSpine8144.mark8144(mint, Stage8144.CLOSE_LEASE, 12_500L))
        SellRouteSpine8144.onPhase8144(mint, "SELL_BALANCE_CHECK", 13_000L)
        SellRouteSpine8144.onResult8144(mint, "WAITING_BALANCE_PROOF", 15_000L)
        val waiting = SellRouteSpine8144.timer8144(mint)!!
        assertEquals(Stage8144.TOKEN_BALANCE, waiting.failedStage)
        assertEquals(500L + 2_000L, waiting.balanceMs())
        SellRouteSpine8144.mark8144(mint, Stage8144.CLOSE_LEASE, 16_000L)
        SellRouteSpine8144.onPhase8144(mint, "SELL_BALANCE_CHECK", 16_200L)
        SellRouteSpine8144.onPhase8144(mint, "SELL_TX_BUILT", 16_900L)
        SellRouteSpine8144.onPhase8144(mint, "SIGNED", 16_950L)
        SellRouteSpine8144.onPhase8144(mint, "SUBMIT_ATTEMPTED", 17_000L)
        SellRouteSpine8144.onPhase8144(mint, "CONFIRMED", 18_000L)
        assertEquals(7_000L, waiting.triggerToBroadcastMs())
        // A partial slice does not close the spine; the terminal canonical close does.
        SellRouteSpine8144.onFinalized8144(mint, canonicalMutated = true, terminal = false, nowMs = 18_100L)
        assertNotNull(SellRouteSpine8144.timer8144(mint))
        SellRouteSpine8144.onFinalized8144(mint, canonicalMutated = true, terminal = true, nowMs = 18_200L)
        assertNull(SellRouteSpine8144.timer8144(mint))
        assertEquals(18_200L - 10_000L, waiting.accountedMs())
        assertTrue(SellRouteSpine8144.statusLine8144().contains("CLOSED=1"))
        assertTrue(SellRouteSpine8144.statusLine8144().contains("TOKEN_BALANCE=1"))
    }

    @Test fun spineRestampsDeferredSoftExitsAndNeverOutlivesItsPosition() {
        SellRouteSpine8144.trigger8144(mint, "TAKE_PROFIT", "p1", 1_000L)
        // A non-emergency exit that has not reached the lease is re-stamped (a deferral is not queue time).
        assertEquals(6_000L, SellRouteSpine8144.trigger8144(mint, "TAKE_PROFIT", "p1", 6_000L)!!.triggerAtMs)
        // A catastrophic exit arriving on the soft spine opens its own.
        val emergency = SellRouteSpine8144.trigger8144(mint, "TICK_CATASTROPHIC_CONFIRMED_-70PCT", "p1", 7_000L)!!
        assertTrue(emergency.emergency)
        assertEquals(7_000L, emergency.triggerAtMs)
        // A new position on the same mint gets a fresh spine.
        assertEquals(9_000L, SellRouteSpine8144.trigger8144(mint, "TICK_CATASTROPHIC_CONFIRMED_-70PCT", "p2", 9_000L)!!.triggerAtMs)
        SellRouteSpine8144.onResult8144(mint, "ALREADY_CLOSED", 9_500L)
        assertNull(SellRouteSpine8144.timer8144(mint))
    }

    @Test fun signingIntoTheLedgerClosesTheSpinesSignStage() {
        SellRouteSpine8144.trigger8144(mint, "CATASTROPHIC_HARD_BACKSTOP_-25", "p", System.currentTimeMillis())
        SignedTxLedger8085.record("sigX", "bytes", "hash", SignedTxLedger8085.Intent(mint, "SELL", 5L))
        assertTrue(SellRouteSpine8144.timer8144(mint)!!.reached(Stage8144.SIGN))
        SignedTxLedger8085.transition("sigX", SignedTxLedger8085.State.SUBMIT_ATTEMPTED)
        assertTrue(SellRouteSpine8144.timer8144(mint)!!.reached(Stage8144.BROADCAST))
    }

    // ── wiring (supplement) ──

    @Test fun sellPathIsWiredToTheSpineTheEndpointPolicyAndTheCorroboratedBalance() {
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("pendingSellSigToReuse8144(ts, wallet)?.let { existingSig ->"))
        assertTrue(exec.contains("ACTIVE_SELL_SIG_IN_FLIGHT"))
        assertTrue(exec.contains("tokenData = corroboratedHolding8144(ts, wallet) ?: tokenData"))
        assertTrue(exec.contains("LIVESELL_ABSENT_HANDED_TO_PROOF_POLLER_7736"))
        assertTrue(exec.contains("if (!directRouteViable8144(ts.mint))"))
        assertTrue(exec.contains("if (!pumpPortalAdmitted8144(ts, venue8144, labelTag, sellTradeKey, traderTag)) return null"))
        assertTrue(exec.contains("SellRouteSpine8144.onResult8144(ts.mint, r.name)"))
        assertTrue(exec.contains("sellRouteTrigger8144(ts, reason, wallet)"))
        val poller = src("engine/sell/BalanceProofPoller.kt")
        assertTrue(poller.contains("if (!zeroCorroborated8144(entry)) return"))
        assertTrue(poller.contains("if (!emergencyResell8144(mint, reason))"))
        assertTrue(src("engine/LiveTradeLogStore.kt").contains("SellRouteSpine8144.onPhase8144(mint, phase.name)"))
        assertTrue(src("engine/sell/SellFinalizationCoordinator.kt").contains("SellRouteSpine8144.onFinalized8144("))
        assertTrue(src("engine/sell/MemeVenueRouter.kt").contains("SellRouteVenueProbe8144.cached8144(mint)"))
    }
}
