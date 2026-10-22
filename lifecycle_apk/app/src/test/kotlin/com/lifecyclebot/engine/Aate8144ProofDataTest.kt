package com.lifecyclebot.engine

import com.lifecyclebot.engine.ProofDataFeatureCoverage8144.Feature8144
import com.lifecyclebot.engine.chart.ChartSources7955
import com.lifecyclebot.engine.chart.LocalTapeBars8128
import com.lifecyclebot.engine.truth.AuthorityProofPrefetch8136
import com.lifecyclebot.engine.truth.AuthorityProofState8136
import com.lifecyclebot.engine.truth.ProofDataAuthorityQueue8144
import com.lifecyclebot.engine.truth.ProofDataAuthorityQueue8144.Answer8144
import com.lifecyclebot.engine.truth.ProofDataAuthorityQueue8144.Flight8144
import com.lifecyclebot.engine.truth.ProofDataAuthorityQueue8144.Priority8144
import com.lifecyclebot.engine.truth.ProofDataAuthorityQueue8144.Submit8144
import com.lifecyclebot.engine.truth.ProofDataSwapTape8144
import com.lifecyclebot.engine.truth.TradeTape8100
import com.lifecyclebot.network.GradTicks8018
import com.lifecyclebot.network.HostCircuitInterceptor
import com.lifecyclebot.network.ProofDataLanes8144
import com.lifecyclebot.network.ProofDataLanes8144.Lane8144
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.0.8144 — ProofData: the authority-proof queue never drops a proof silently (QUEUE_REJECTED, bounded retry, live
 * lane first, single flight, unanswered never approves); provider calls are coalesced and capped; execution RPCs cool
 * down apart from speculative intelligence; Helius swaps fill the trade tape without synthetic bars; deferrals are
 * bounded; each missing feature is counted on its own.
 */
class Aate8144ProofDataTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val t0 = 2_000_000_000_000L

    // ── Area A: authority-proof queue ──

    @Test fun saturationIsQueueRejectedNotSubmittedAndARetryIsScheduled() {
        val q = ProofDataAuthorityQueue8144(liveCap = 1, backgroundCap = 1)
        assertEquals(Submit8144.ACCEPTED, q.request8144("A", Priority8144.LIVE, t0))
        // The live lane is full: an explicit rejection, not a silent drop.
        assertEquals(Submit8144.QUEUE_REJECTED, q.request8144("B", Priority8144.LIVE, t0))
        assertEquals(Flight8144.QUEUE_REJECTED, q.state8144("B"))
        assertEquals(1, q.liveDepth8144())                                   // only A is on the queue
        assertEquals(t0 + q.rejectDelayMs8144(1), q.slot8144("B")!!.retryAtMs)
        // Inside its back-off the mint is not re-offered; once due, the bounded retry is handed back (live priority).
        assertEquals(Submit8144.BACKOFF, q.request8144("B", Priority8144.LIVE, t0 + 1))
        assertTrue(q.due8144(t0 + 1, 10).isEmpty())
        val due = q.due8144(t0 + 2_000L, 10)
        assertEquals(listOf(ProofDataAuthorityQueue8144.Taken8144("B", Priority8144.LIVE)), due)
        // A worker drains A; B's retry is now accepted.
        assertEquals("A", q.take8144(t0 + 2_000L)?.mint)
        assertEquals(Submit8144.ACCEPTED, q.request8144("B", Priority8144.LIVE, t0 + 2_000L))
        assertEquals(Flight8144.QUEUED, q.state8144("B"))
        // Rejections are bounded: past the cap the mint holds the named QUEUE_REJECTED terminal for the window.
        val r = ProofDataAuthorityQueue8144(liveCap = 0, backgroundCap = 0, maxRejects = 2)
        assertEquals(Submit8144.QUEUE_REJECTED, r.request8144("C", Priority8144.LIVE, t0))
        assertEquals(Submit8144.QUEUE_REJECTED, r.request8144("C", Priority8144.LIVE, t0 + 60_000L))
        assertEquals(Submit8144.EXHAUSTED, r.request8144("C", Priority8144.LIVE, t0 + 120_000L))
        assertEquals(Flight8144.QUEUE_REJECTED, r.state8144("C"))
        assertTrue(r.due8144(t0 + 180_000L, 10).isEmpty())
        // The singleton counts "submitted" only on acceptance and names rejections.
        assertFalse(AuthorityProofPrefetch8136.prefetch8136(""))
        val line = AuthorityProofPrefetch8136.statusLine8136()
        assertTrue(line.contains("submitted=") && line.contains("queueRejected8144=") && line.contains("skippedBackoff="))
        val pf = src("engine/truth/AuthorityProofPrefetch8136.kt")
        assertFalse(pf.contains("ThreadPoolExecutor.DiscardPolicy()"))
        assertTrue(pf.contains("ProofDataAuthorityQueue8144.Submit8144.ACCEPTED -> {\n                submitted.incrementAndGet()"))
    }

    @Test fun oneMintIsNeverQueuedOrRunningTwice() {
        val q = ProofDataAuthorityQueue8144()
        assertEquals(Submit8144.ACCEPTED, q.request8144("M", Priority8144.BACKGROUND, t0))
        assertEquals(Submit8144.ALREADY_QUEUED, q.request8144("M", Priority8144.BACKGROUND, t0))
        assertEquals(1, q.backgroundDepth8144())
        val t = q.take8144(t0)
        assertEquals("M", t?.mint)
        assertEquals(Flight8144.RUNNING, q.state8144("M"))
        assertEquals(Submit8144.ALREADY_RUNNING, q.request8144("M", Priority8144.LIVE, t0))
        assertNull(q.take8144(t0))                                            // nothing was queued a second time
        assertEquals(Flight8144.PROVEN_CLEAN, q.complete8144("M", Answer8144.CLEAN, t0 + 100L))
        assertEquals(1, q.census8144()[Flight8144.PROVEN_CLEAN])
    }

    @Test fun aQualifiedLiveCandidateIsServedBeforeBackgroundEnrichment() {
        val q = ProofDataAuthorityQueue8144()
        q.request8144("bg1", Priority8144.BACKGROUND, t0)
        q.request8144("bg2", Priority8144.BACKGROUND, t0)
        q.request8144("bg3", Priority8144.BACKGROUND, t0)
        q.request8144("live1", Priority8144.LIVE, t0 + 1)
        // A queued background mint asked for by a gate / FDG caller is promoted into the live lane.
        assertEquals(Submit8144.PROMOTED, q.request8144("bg3", Priority8144.LIVE, t0 + 2))
        val order = generateSequence { q.take8144(t0 + 3) }.map { it.mint to it.priority }.toList()
        assertEquals(
            listOf("live1" to Priority8144.LIVE, "bg3" to Priority8144.LIVE, "bg1" to Priority8144.BACKGROUND, "bg2" to Priority8144.BACKGROUND),
            order,
        )
        // A full background lane never takes live capacity, and live still gets in.
        val s = ProofDataAuthorityQueue8144(liveCap = 1, backgroundCap = 1)
        assertEquals(Submit8144.ACCEPTED, s.request8144("b", Priority8144.BACKGROUND, t0))
        assertEquals(Submit8144.QUEUE_REJECTED, s.request8144("b2", Priority8144.BACKGROUND, t0))
        assertEquals(Submit8144.ACCEPTED, s.request8144("l", Priority8144.LIVE, t0))
        assertEquals("l", s.take8144(t0)?.mint)
        // An unanswered scan read is speculative: a spaced, shorter background ladder (never inside the 20 s negative cache).
        val b = ProofDataAuthorityQueue8144()
        assertEquals(Flight8144.RETRY_SCHEDULED, b.noteUnanswered8144("scan", Priority8144.BACKGROUND, t0))
        assertEquals(t0 + ProofDataAuthorityQueue8144.BACKGROUND_MIN_RETRY_MS_8144, b.slot8144("scan")!!.retryAtMs)
        var at = t0 + ProofDataAuthorityQueue8144.BACKGROUND_MIN_RETRY_MS_8144
        var last = Flight8144.RETRY_SCHEDULED
        repeat(ProofDataAuthorityQueue8144.BACKGROUND_MAX_ATTEMPTS_8144 - 1) {
            assertEquals(Submit8144.ACCEPTED, b.request8144("scan", Priority8144.BACKGROUND, at))
            b.take8144(at)
            last = b.complete8144("scan", Answer8144.UNANSWERED, at)
            at = b.slot8144("scan")!!.retryAtMs
        }
        assertEquals(Flight8144.UNANSWERED, last)                                   // named terminal after 3 asks
        assertEquals(Submit8144.EXHAUSTED, b.request8144("scan", Priority8144.BACKGROUND, t0 + 120_000L))   // same window
        assertTrue(src("engine/truth/OnChainMintAuthorityProof7248.kt").contains("AuthorityProofPrefetch8136.noteScanUnanswered8144(mint)"))
        // Callers: gate / FDG use the live lane.
        assertTrue(src("engine/FdgReEvalThrottle.kt").contains("AuthorityProofPrefetch8136.prefetchIfLive8136(mint)"))
    }

    @Test fun anUnansweredProofNeverBecomesApprovalAndEveryDeferralIsBounded() {
        val q = ProofDataAuthorityQueue8144(maxAttempts = 3)
        q.request8144("U", Priority8144.LIVE, t0)
        q.take8144(t0)
        assertEquals(Flight8144.RETRY_SCHEDULED, q.complete8144("U", Answer8144.UNANSWERED, t0 + 10L))
        assertEquals(t0 + 10L + q.retryDelayMs8144(1), q.slot8144("U")!!.retryAtMs)
        assertEquals(Submit8144.BACKOFF, q.request8144("U", Priority8144.LIVE, t0 + 20L))
        assertEquals(Submit8144.ACCEPTED, q.request8144("U", Priority8144.LIVE, t0 + 3_010L))
        q.take8144(t0 + 3_010L)
        assertEquals(Flight8144.RETRY_SCHEDULED, q.complete8144("U", Answer8144.UNANSWERED, t0 + 3_020L))
        assertEquals(Submit8144.ACCEPTED, q.request8144("U", Priority8144.LIVE, t0 + 13_020L))
        q.take8144(t0 + 13_020L)
        // Third unanswered ask of the window: the named UNANSWERED terminal, never a clean state.
        assertEquals(Flight8144.UNANSWERED, q.complete8144("U", Answer8144.UNANSWERED, t0 + 13_030L))
        assertEquals(Submit8144.EXHAUSTED, q.request8144("U", Priority8144.LIVE, t0 + 60_000L))
        assertTrue(q.due8144(t0 + 60_000L, 10).isEmpty())
        // The window reopens: a fresh bounded episode.
        assertEquals(Submit8144.ACCEPTED, q.request8144("U", Priority8144.LIVE, t0 + AuthorityProofState8136.RETRY_WINDOW_MS_8136 + 1L))
        // The gate's own unanswered ask schedules a LIVE retry on the ladder.
        assertEquals(Flight8144.RETRY_SCHEDULED, q.noteGateUnanswered8144("G", t0))
        assertEquals(Priority8144.LIVE, q.slot8144("G")!!.priority)
        assertEquals(listOf(ProofDataAuthorityQueue8144.Taken8144("G", Priority8144.LIVE)), q.due8144(t0 + 3_000L, 10))
        // The queue never writes the executable record: an unanswered mint stays non-executable.
        AuthorityProofState8136.clearForTest8136()
        AuthorityProofState8136.observeUnanswered8136("U8144")
        assertFalse(AuthorityProofState8136.executable8136(AuthorityProofState8136.stateFor8136("U8144")))
        AuthorityProofPrefetch8136.noteGateUnanswered8136("G8144")
        assertEquals(Flight8144.RETRY_SCHEDULED, AuthorityProofPrefetch8136.flightState8144("G8144"))
        assertFalse(AuthorityProofState8136.executable8136(AuthorityProofState8136.stateFor8136("G8144")))
        val gate = src("engine/truth/FreezeAuthorityHardBlock7238.kt")
        val tail = gate.substringAfter("// V5.0.8136 — UNANSWERED stays non-executable")
        assertTrue(tail.indexOf("noteUnverifiedFlight8144(mint)") in 0 until tail.indexOf("return Decision(Verdict.BLOCK_FREEZE_UNVERIFIED, \"FREEZE_AUTHORITY_UNVERIFIED\")"))
    }

    // ── Area B: coalescing, lanes, tape, deferrals, features ──

    @Test fun coalescingCapsRepeatedProviderCalls() {
        val c = ProofDataCoalescer8144(windowMs = 60_000L, maxAttemptsPerWindow = 3, baseRetryMs = 1_000L, maxRetryMs = 8_000L)
        assertEquals(ProofDataCoalescer8144.Admit8144.RUN, c.admit8144("k", 0L))
        assertEquals(ProofDataCoalescer8144.Admit8144.IN_FLIGHT, c.admit8144("k", 10L))        // same mint+feature in flight
        c.done8144("k", useful = false, nowMs = 100L)
        assertEquals(ProofDataCoalescer8144.Admit8144.BACKOFF, c.admit8144("k", 500L))
        assertEquals(ProofDataCoalescer8144.Admit8144.RUN, c.admit8144("k", 1_100L))
        c.done8144("k", false, 1_200L)                                                         // next at 1_200 + 2_000
        assertEquals(ProofDataCoalescer8144.Admit8144.BACKOFF, c.peek8144("k", 3_199L))
        assertEquals(ProofDataCoalescer8144.Admit8144.RUN, c.admit8144("k", 3_200L))
        c.done8144("k", false, 3_300L)
        assertEquals(ProofDataCoalescer8144.Admit8144.CAPPED, c.admit8144("k", 8_000L))         // 3 attempts this window
        assertEquals(ProofDataCoalescer8144.Admit8144.RUN, c.admit8144("k", 60_000L))           // the window reopens
        assertEquals(8_000L, c.retryDelayMs8144(10))
        // A poll every second for a minute reaches the provider three times, not sixty.
        val d = ProofDataCoalescer8144(60_000L, 3, 1_000L, 8_000L)
        var runs = 0
        for (t in 0L until 60_000L step 1_000L) {
            if (d.admit8144("mint|candles", t) == ProofDataCoalescer8144.Admit8144.RUN) { runs++; d.done8144("mint|candles", false, t) }
        }
        assertEquals(3, runs)
        // A local circuit refusal is not a provider strike and does not spend a job's tries.
        assertEquals("requeue_local", ChartSources7955.jobRetry8144(ChartSources7955.LOCAL_CIRCUIT_8144, 0, 1))
        assertEquals("fallback", ChartSources7955.jobRetry8144(ChartSources7955.LOCAL_CIRCUIT_8144, 0, 21))
        assertEquals("requeue", ChartSources7955.jobRetry8144(429, 1, 0))
        assertEquals("fallback", ChartSources7955.jobRetry8144(429, 3, 0))
        assertEquals("fallback", ChartSources7955.jobRetry8144(404, 1, 0))
        val cs = src("engine/chart/ChartSources7955.kt")
        assertTrue(cs.contains("if (backfillGate8144.admit8144(mint, now) != com.lifecyclebot.engine.ProofDataCoalescer8144.Admit8144.RUN) return"))
        assertTrue(cs.contains("HostCircuitInterceptor.isSyntheticBlock(resp)) Res(LOCAL_CIRCUIT_8144, null)"))
    }

    private fun rpc(host: String, method: String): Request =
        Request.Builder().url("https://$host/").post("""{"jsonrpc":"2.0","id":1,"method":"$method","params":[]}""".toRequestBody("application/json".toMediaType())).build()

    private fun chain(req: Request, code: Int, calls: AtomicInteger): Interceptor.Chain =
        java.lang.reflect.Proxy.newProxyInstance(Interceptor.Chain::class.java.classLoader, arrayOf<Class<*>>(Interceptor.Chain::class.java)) { _, m, args ->
            when (m.name) {
                "request" -> req
                "proceed" -> {
                    calls.incrementAndGet()
                    Response.Builder().request(args!![0] as Request).protocol(Protocol.HTTP_1_1).code(code).message("t")
                        .body("{}".toResponseBody(null)).build()
                }
                else -> null
            }
        } as Interceptor.Chain

    @Test fun theExecutionLaneIsUnaffectedByIntelligenceBackoff() {
        assertEquals("sendTransaction", ProofDataLanes8144.rpcMethod8144("""{"jsonrpc":"2.0","method":"sendTransaction"}"""))
        assertEquals(Lane8144.EXEC, ProofDataLanes8144.laneFor8144(null, false, "h", "/", "sendTransaction"))
        assertEquals(Lane8144.EXEC, ProofDataLanes8144.laneFor8144(null, false, "h", "/", "getTokenAccountsByOwner"))
        assertEquals(Lane8144.INTEL, ProofDataLanes8144.laneFor8144(null, false, "h", "/", "getSignaturesForAddress"))
        assertEquals(Lane8144.SHARED, ProofDataLanes8144.laneFor8144(null, false, "h", "/", "getAccountInfo"))
        assertEquals(Lane8144.EXEC, ProofDataLanes8144.laneFor8144(null, true, "h", "/", "getSignaturesForAddress"))  // an exit
        assertEquals(Lane8144.BULK, ProofDataLanes8144.laneFor8144("chart", false, "h", "/", null))
        assertEquals(Lane8144.EXEC, ProofDataLanes8144.laneFor8144(null, false, "pumpportal.fun", "/api/trade-local", null))
        assertEquals(Lane8144.INTEL, ProofDataLanes8144.laneFor8144(null, false, "pumpportal.fun", "/api/data", null))
        assertEquals("h", ProofDataLanes8144.circuitHost8144("h", "dexscreener", Lane8144.INTEL))      // mapped: label splits it
        assertEquals("h", ProofDataLanes8144.circuitHost8144("h", "", Lane8144.SHARED))
        assertEquals("h#exec", ProofDataLanes8144.circuitHost8144("h", "", Lane8144.EXEC))

        // Through the real interceptor: an intelligence 429 cools only the intelligence lane of the host.
        val host = "rpc8144.test"
        val calls = AtomicInteger(0)
        val first = HostCircuitInterceptor.intercept(chain(rpc(host, "getSignaturesForAddress"), 429, calls))
        assertEquals(429, first.code)
        assertTrue(HostCircuitInterceptor.coolingDown8144("$host#intel"))
        assertFalse(HostCircuitInterceptor.coolingDown8144("$host#exec"))
        assertFalse(HostCircuitInterceptor.coolingDown8144(host))
        val blocked = HostCircuitInterceptor.intercept(chain(rpc(host, "getTransaction"), 200, calls))
        assertTrue(HostCircuitInterceptor.isSyntheticBlock(blocked))                 // intelligence waits out its own window
        assertEquals(1, calls.get())
        val send = HostCircuitInterceptor.intercept(chain(rpc(host, "sendTransaction"), 200, calls))
        assertFalse(HostCircuitInterceptor.isSyntheticBlock(send))
        assertEquals(200, send.code)
        val mark = HostCircuitInterceptor.intercept(chain(rpc(host, "getAccountInfo"), 200, calls))   // held-position read
        assertFalse(HostCircuitInterceptor.isSyntheticBlock(mark))
        assertEquals(3, calls.get())
        assertTrue(ProofDataLanes8144.bypassed8144(Lane8144.INTEL) >= 1L)
        val hc = src("network/HostCircuitInterceptor.kt")
        assertTrue(hc.contains("val stateKey7719 = if (provider.isNotBlank()) \"\$host|\$provider\" else host"))
        assertTrue(hc.contains("val host = ProofDataLanes8144.circuitHost8144(wireHost8144, provider, lane8144)"))
    }

    @Test fun heliusSwapsFillTheTradeTapeWithoutSyntheticBars() {
        TradeTape8100.resetForTest8100()
        ProofDataSwapTape8144.clearForTest8144()
        val m = "HeliusTape8144Mint"
        assertTrue(ProofDataSwapTape8144.noteHelius8144(m, "sig1", 0.5, 1_000.0, true, pumpPortalLastMs = 0L, nowMs = t0))
        assertFalse(ProofDataSwapTape8144.noteHelius8144(m, "sig1", 0.5, 1_000.0, true, 0L, t0 + 1L))          // same swap twice
        assertFalse(ProofDataSwapTape8144.noteHelius8144(m, "sig2", 0.5, 1_000.0, true, t0 - 1_000L, t0 + 2L)) // PumpPortal delivers it
        assertFalse(ProofDataSwapTape8144.noteHelius8144(m, "sig3", 0.5, 0.0, false, 0L, t0 + 3L))            // no executed price
        val prints = TradeTape8100.prints8128(m, t0 + 10L, 60_000L)
        assertEquals(1, prints.size)
        assertEquals(0.0005, prints[0].priceSol, 1e-12)
        // Minutes 0..9 and 15 traded: 11 bars, and nothing is invented for minutes 10..14.
        val minutes = (0..9).toList() + 15
        for (k in minutes) ProofDataSwapTape8144.noteHelius8144(m, "s$k", 0.1, 100.0 + k, k % 2 == 0, 0L, t0 + 60_000L + k * 60_000L)
        val bars = LocalTapeBars8128.fromPrints8128(TradeTape8100.prints8128(m, t0 + 60_000L + 16 * 60_000L, 30 * 60_000L), 150.0)
        assertNotNull(bars)
        assertEquals(12, bars!!.size)                                               // the first print's minute + 11
        val bucket = bars.map { it.t / 60_000L }.toSet()
        for (gap in 10..14) assertFalse(bucket.contains((t0 + 60_000L + gap * 60_000L) / 60_000L))
        assertTrue(ProofDataSwapTape8144.statusLine8144().contains("taped="))
        assertTrue(src("engine/DataOrchestrator.kt").contains("ProofDataSwapTape8144.noteHelius8144(mint, sig, safeSol, tokenAmt, isBuy, lastPumpPortalTradeMs7773[mint] ?: 0L)"))
        TradeTape8100.resetForTest8100()
    }

    @Test fun deferralsAreBoundedAndEachMissingFeatureIsNamed() {
        val d = ProofDataDeferral8144(budgetMs = 1_000L, restMs = 5_000L)
        assertEquals(ProofDataDeferral8144.Step8144.DEFER, d.step8144("m", 0L))
        assertEquals(ProofDataDeferral8144.Step8144.DEFER, d.step8144("m", 500L))
        assertEquals(ProofDataDeferral8144.Step8144.TERMINAL, d.step8144("m", 1_000L))
        assertEquals(ProofDataDeferral8144.Step8144.TERMINAL, d.step8144("m", 3_000L))
        assertEquals(ProofDataDeferral8144.Step8144.DEFER, d.step8144("m", 6_000L))         // a fresh bounded episode
        d.resolved8144("m")
        assertEquals(ProofDataDeferral8144.Step8144.DEFER, d.step8144("m", 6_001L))
        assertEquals(1L, d.terminals8144())
        val t = LaneAgeLiquidityTruth8140.v3LiquidityReason8144(ProofDataDeferral8144.Step8144.TERMINAL)
        assertEquals("LIQ_UNHYDRATED_TERMINAL_8144", t)
        assertFalse(t.contains("ZERO_LIQUIDITY"))                                          // never the quarantine reason
        assertEquals("LIQ_UNHYDRATED_DEFER_8140", LaneAgeLiquidityTruth8140.v3LiquidityReason8144(ProofDataDeferral8144.Step8144.DEFER))

        val F = ProofDataFeatureCoverage8144
        F.clearForTest8144()
        // superseded by V5.0.8318: the launch-tape input is named launchTape (it was "tape", read as the local trade tape)
        assertEquals(
            listOf(Feature8144.BUY_PRESSURE, Feature8144.LIQUIDITY, Feature8144.SWAP_PROVENANCE),
            F.missing8144(bp = false, chg = true, launchTape = true, structure = true, context = true, liquidity = false, birth = true, swaps = false),
        )
        assertTrue(F.missing8144(true, true, true, true, true, true, true, true).isEmpty())
        F.observe8144("f1", t0, bp = false, chg = true, launchTape = true, structure = true, context = true, liquidity = false, birth = true, swaps = true)
        F.observe8144("f1", t0 + 1L, bp = false, chg = true, launchTape = true, structure = true, context = true, liquidity = true, birth = false, swaps = true)
        F.observe8144("f2", t0 + 2L, bp = true, chg = true, launchTape = true, structure = true, context = true, liquidity = true, birth = true, swaps = true)
        assertEquals(2L, F.missingCount8144(Feature8144.BUY_PRESSURE))
        assertEquals(1L, F.missingCount8144(Feature8144.LIQUIDITY))
        assertEquals(1L, F.missingCount8144(Feature8144.BIRTH_TIME))
        assertEquals(0L, F.missingCount8144(Feature8144.PRICE_CHANGE))
        assertEquals(1, F.missingMints8144(Feature8144.BUY_PRESSURE, t0 + 3L))           // one distinct mint, two evaluations
        assertTrue(F.statusLine8144(t0 + 3L).contains("bp=2/1"))
        assertTrue(src("engine/cortex/LanePlaybook7907.kt").contains("ProofDataFeatureCoverage8144.observe8144(ts.mint, nowMs"))
        F.clearForTest8144()
    }

    @Test fun graduatedCoinsResolveTheirCanonicalPumpSwapPool() {
        val mint = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P"
        val pool = GradTicks8018.canonicalPool8144(mint)
        assertNotNull(pool)
        assertTrue(pool!!.length in 32..44)
        assertTrue(pool != mint)
        assertEquals(pool, GradTicks8018.canonicalPool8144(mint))                         // deterministic
        assertNull(GradTicks8018.canonicalPool8144("not-a-key"))
        assertEquals("NO_CANDIDATE", GradTicks8018.unresolvedReason8144(0, false, false))
        assertEquals("NOT_PUMPSWAP", GradTicks8018.unresolvedReason8144(2, true, false))
        assertEquals("RPC_UNANSWERED", GradTicks8018.unresolvedReason8144(2, false, false))
        assertNull(GradTicks8018.unresolvedReason8144(2, true, true))
        assertTrue(GradTicks8018.statusLine().contains("rpcUnanswered8144="))
    }
}
