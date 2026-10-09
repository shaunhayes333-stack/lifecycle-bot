package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CrossAssetCortex7931
import com.lifecyclebot.perps.crypto.EvmSwapExecutor7962
import com.lifecyclebot.perps.crypto.EvmSwapExecutor7962.HoneypotVerdict7962
import com.lifecyclebot.perps.crypto.EvmSwapExecutor7962.KyberBuild7962
import com.lifecyclebot.perps.crypto.EvmSwapExecutor7962.KyberRoute7962
import com.lifecyclebot.perps.crypto.MultiChainMemeLane7962
import com.lifecyclebot.perps.crypto.MultiChainMemeLane7962.Candidate7962
import com.lifecyclebot.perps.crypto.MultiChainMemeLane7962.CellView7962
import com.lifecyclebot.perps.crypto.MultiChainMemeLane7962.ChainGate7962
import com.lifecyclebot.perps.crypto.MultiChainMemeLane7962.ExitAction7962
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/** V5.0.7962 — everything but SOL: multi-chain discovery, labels, gating and native EVM swaps. */
class Aate7962MultiChainTest {

    private val router = "0x6131B5fae19EA4f9D964eAc0408E4408b66337b5"
    private val tokenA = "0x2222222222222222222222222222222222222222"
    private val tokenB = "0x3333333333333333333333333333333333333333"

    // ── chains ──

    @Test fun chainsNormaliseEveryProviderNameAndNeverSolana() {
        assertEquals("eth", EvmSwapExecutor7962.chainFor7962("ethereum")?.key)
        assertEquals("eth", EvmSwapExecutor7962.chainFor7962("eth")?.key)
        assertEquals("polygon", EvmSwapExecutor7962.chainFor7962("polygon_pos")?.key)
        assertEquals("arbitrum", EvmSwapExecutor7962.chainFor7962("arbitrum-one")?.key)
        assertEquals("avax", EvmSwapExecutor7962.chainFor7962("avalanche")?.key)
        assertEquals(56L, EvmSwapExecutor7962.chainFor7962("bsc")?.chainId)
        assertEquals(8453L, EvmSwapExecutor7962.chainFor7962("base")?.chainId)
        assertNull(EvmSwapExecutor7962.chainFor7962("solana"))
        assertNull(EvmSwapExecutor7962.chainFor7962(""))
        assertTrue(EvmSwapExecutor7962.CHAINS_7962.none { it.key == "solana" })
        // The owner's RPCs come first.
        assertEquals("https://bsc-dataseed.binance.org", EvmSwapExecutor7962.chainFor7962("bsc")?.rpcs?.first())
        assertEquals("https://mainnet.base.org", EvmSwapExecutor7962.chainFor7962("base")?.rpcs?.first())
        assertEquals("https://arb1.arbitrum.io/rpc", EvmSwapExecutor7962.chainFor7962("arbitrum")?.rpcs?.first())
        assertEquals("https://eth.llamarpc.com", EvmSwapExecutor7962.chainFor7962("eth")?.rpcs?.first())
        assertEquals("EVM_BSC", MultiChainMemeLane7962.laneOf7962("bsc"))
        assertTrue(CrossAssetCortex7931.isCrossAssetLane("EVM_BSC"))
        assertTrue(MultiChainMemeLane7962.isMajor7962("weth"))
        assertTrue(MultiChainMemeLane7962.isMajor7962("USDT"))
        assertFalse(MultiChainMemeLane7962.isMajor7962("PEPE2"))
    }

    // ── KyberSwap ──

    private val routeOk = """{"code":0,"message":"successfully","data":{"routeSummary":{"tokenIn":"${EvmSwapExecutor7962.NATIVE_7962}",
        "amountIn":"1000000000000000","amountInUsd":"0.60","tokenOut":"$tokenA","amountOut":"123456789","amountOutUsd":"0.58",
        "gas":"180000","gasPrice":"1000000000","gasUsd":"0.11","route":[[{"pool":"0xp","exchange":"pancake"}]]},
        "routerAddress":"$router"},"requestId":"r1"}"""

    @Test fun kyberRouteParsesAndRefusesIncompleteOrErrored() {
        val r: KyberRoute7962 = EvmSwapExecutor7962.parseKyberRoute7962(routeOk)!!
        assertEquals(router, r.routerAddress)
        assertEquals(BigInteger("1000000000000000"), r.amountIn)
        assertEquals(BigInteger("123456789"), r.amountOut)
        assertEquals(180_000L, r.gasUnits)
        assertEquals(0.11, r.gasUsd, 1e-9)
        assertEquals(3.3333, r.swapLossPct, 1e-3)
        assertEquals("pancake", r.routeSummary.getJSONArray("route").getJSONArray(0).getJSONObject(0).getString("exchange"))
        assertNull(EvmSwapExecutor7962.parseKyberRoute7962("""{"code":4008,"message":"route not found"}"""))
        assertNull(EvmSwapExecutor7962.parseKyberRoute7962("""{"code":0,"data":{"routerAddress":"$router"}}"""))
        assertNull(EvmSwapExecutor7962.parseKyberRoute7962("""{"code":0,"data":{"routeSummary":{"amountIn":"1","amountOut":"1"},"routerAddress":"not-an-address"}}"""))
        assertNull(EvmSwapExecutor7962.parseKyberRoute7962("not json"))
        // A bare payload (no envelope) and numeric fields are tolerated; USD-less quotes report NaN loss.
        val bare = EvmSwapExecutor7962.parseKyberRoute7962("""{"routeSummary":{"amountIn":1000,"amountOut":2000,"gas":90000},"routerAddress":"$router"}""")!!
        assertEquals(BigInteger.valueOf(1000L), bare.amountIn)
        assertTrue(bare.swapLossPct.isNaN())
    }

    @Test fun kyberBuildMustMatchTheQuotedRouterAndSpend() {
        val body = """{"code":0,"message":"successfully","data":{"amountIn":"1000000000000000","amountInUsd":"0.6","amountOut":"120000000",
            "amountOutUsd":"0.57","gas":"185000","gasUsd":"0.11","data":"0xe21fd0e9abcdef","routerAddress":"$router","transactionValue":"1000000000000000"}}"""
        val amt = BigInteger("1000000000000000")
        val b: KyberBuild7962 = EvmSwapExecutor7962.parseKyberBuild7962(body, router, amt)!!
        assertEquals("0xe21fd0e9abcdef", b.calldata)
        assertEquals(amt, b.transactionValue)
        assertEquals(BigInteger("120000000"), b.amountOut)
        // Case-insensitive router match; a different router or spend is refused.
        assertNotNull(EvmSwapExecutor7962.parseKyberBuild7962(body, router.lowercase(), amt))
        assertNull(EvmSwapExecutor7962.parseKyberBuild7962(body, "0x1111111111111111111111111111111111111111", amt))
        assertNull(EvmSwapExecutor7962.parseKyberBuild7962(body, router, BigInteger("999")))
        assertNull(EvmSwapExecutor7962.parseKyberBuild7962("""{"code":0,"data":{"routerAddress":"$router","amountOut":"5"}}""", router, amt))
        assertNull(EvmSwapExecutor7962.parseKyberBuild7962("""{"code":4227,"message":"slippage"}""", router, amt))
        // transactionValue may be absent (caller decides from tokenIn).
        val noValue = EvmSwapExecutor7962.parseKyberBuild7962("""{"code":0,"data":{"data":"0xabcdef0123","routerAddress":"$router","amountIn":"5","amountOut":"7"}}""", router, BigInteger.valueOf(5L))!!
        assertNull(noValue.transactionValue)
        assertEquals(1400, EvmSwapExecutor7962.slippageBps7962(10.0, 4.0))
        assertEquals(300, EvmSwapExecutor7962.slippageBps7962(Double.NaN, 3.0))
        assertEquals(2000, EvmSwapExecutor7962.slippageBps7962(40.0, 3.0))
    }

    // ── honeypot.is ──

    @Test fun honeypotVerdictRefusesHoneypotsHighTaxAndUnknowns() {
        val ok: HoneypotVerdict7962 = EvmSwapExecutor7962.parseHoneypot7962("""{"token":{"symbol":"X"},"honeypotResult":{"isHoneypot":false},
            "simulationSuccess":true,"simulationResult":{"buyTax":3,"sellTax":4.5,"transferTax":0},"summary":{"risk":"low","riskLevel":1}}""")
        assertTrue(ok.ok); assertEquals("OK", ok.code); assertEquals(3.0, ok.buyTaxPct, 1e-9); assertEquals(4.5, ok.sellTaxPct, 1e-9)
        assertEquals("HONEYPOT", EvmSwapExecutor7962.parseHoneypot7962("""{"honeypotResult":{"isHoneypot":true},"simulationSuccess":true,"simulationResult":{"buyTax":0,"sellTax":100}}""").code)
        assertEquals("HONEYPOT", EvmSwapExecutor7962.parseHoneypot7962("""{"honeypotResult":{"isHoneypot":false},"simulationSuccess":true,"simulationResult":{"buyTax":0,"sellTax":0},"summary":{"risk":"honeypot"}}""").code)
        assertEquals("HIGH_TAX", EvmSwapExecutor7962.parseHoneypot7962("""{"honeypotResult":{"isHoneypot":false},"simulationSuccess":true,"simulationResult":{"buyTax":2,"sellTax":25}}""").code)
        assertEquals("HIGH_TAX", EvmSwapExecutor7962.parseHoneypot7962("""{"honeypotResult":{"isHoneypot":false},"simulationSuccess":true,"simulationResult":{"buyTax":2,"sellTax":2,"transferTax":30}}""").code)
        assertEquals("SIMULATION_FAILED", EvmSwapExecutor7962.parseHoneypot7962("""{"simulationSuccess":false,"simulationError":"execution reverted"}""").code)
        assertEquals("UNKNOWN", EvmSwapExecutor7962.parseHoneypot7962("""{"token":{"symbol":"X"}}""").code)
        assertEquals("HIGH_RISK", EvmSwapExecutor7962.parseHoneypot7962("""{"honeypotResult":{"isHoneypot":false},"simulationSuccess":true,"simulationResult":{"buyTax":1,"sellTax":1},"summary":{"risk":"very_high"}}""").code)
        assertFalse(EvmSwapExecutor7962.parseHoneypot7962("<html>").ok)
        // Taxes as strings parse too; the cap is the caller's.
        assertTrue(EvmSwapExecutor7962.parseHoneypot7962("""{"honeypotResult":{"isHoneypot":false},"simulationSuccess":true,"simulationResult":{"buyTax":"6","sellTax":"6"}}""", maxTaxPct = 10.0).ok)
        assertFalse(EvmSwapExecutor7962.parseHoneypot7962("""{"honeypotResult":{"isHoneypot":false},"simulationSuccess":true,"simulationResult":{"buyTax":"6","sellTax":"6"}}""", maxTaxPct = 5.0).ok)
    }

    // ── discovery parsers ──

    @Test fun geckoPoolsCarryFlowAgeAndChain() {
        val body = """{"data":[{"id":"bsc_0xpool","type":"pool","attributes":{"base_token_price_usd":"0.00012",
            "address":"0x1111111111111111111111111111111111111111","name":"PEPE2 / WBNB","pool_created_at":"2026-10-10T00:00:00Z",
            "fdv_usd":"80000","market_cap_usd":null,"price_change_percentage":{"m5":"12.5","h1":"40","h24":"40"},
            "transactions":{"m5":{"buys":30,"sells":10},"h1":{"buys":200,"sells":90}},"volume_usd":{"m5":"5000","h1":"40000"},
            "reserve_in_usd":"25000"},"relationships":{"base_token":{"data":{"id":"bsc_$tokenA","type":"token"}},
            "quote_token":{"data":{"id":"bsc_0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c"}},"dex":{"data":{"id":"pancakeswap_v2"}}}},
            {"id":"bsc_0xbad","type":"pool","attributes":{"base_token_price_usd":"0","name":"ZERO / WBNB"},"relationships":{"base_token":{"data":{"id":"bsc_$tokenB"}}}}]}"""
        val c: List<Candidate7962> = MultiChainMemeLane7962.parseGeckoPools7962(body, "bsc", "GT_NEW")
        assertEquals(1, c.size)
        val p = c[0]
        assertEquals("bsc", p.chain); assertEquals(tokenA, p.token); assertEquals("PEPE2", p.symbol)
        assertEquals("bsc|$tokenA", p.key)
        assertEquals(80_000.0, p.mcapUsd, 1e-9)   // null market cap falls back to FDV
        assertEquals(25_000.0, p.liquidityUsd, 1e-9)
        assertEquals(30, p.buys5m); assertEquals(10, p.sells5m); assertEquals(90, p.sells1h)
        assertEquals(12.5, p.chg5mPct, 1e-9); assertEquals(5_000.0, p.vol5mUsd, 1e-9)
        assertEquals(java.time.Instant.parse("2026-10-10T00:00:00Z").toEpochMilli(), p.createdAtMs)
        assertEquals("pancakeswap_v2", p.dexId)
        // Network ids with an underscore keep the address intact.
        val poly = MultiChainMemeLane7962.parseGeckoPools7962(body.replace("bsc_", "polygon_pos_"), "polygon_pos", "GT_NEW")
        assertEquals(tokenA, poly.single().token); assertEquals("polygon", poly.single().chain)
        assertTrue(MultiChainMemeLane7962.parseGeckoPools7962(body, "solana", "GT_NEW").isEmpty())
    }

    @Test fun dexPairsParseAndMarkPrefersTheKnownPair() {
        val body = """[{"chainId":"base","dexId":"uniswap","pairAddress":"0xAAAA000000000000000000000000000000000001",
            "baseToken":{"address":"$tokenB","symbol":"brett2"},"priceUsd":"0.0021","txns":{"m5":{"buys":12,"sells":4},"h1":{"buys":80,"sells":40}},
            "volume":{"m5":1200,"h1":9000},"priceChange":{"m5":3.1,"h1":-2},"liquidity":{"usd":50000},"fdv":900000,"marketCap":850000,"pairCreatedAt":1760000000000},
            {"chainId":"base","dexId":"aerodrome","pairAddress":"0xAAAA000000000000000000000000000000000002","baseToken":{"address":"$tokenB","symbol":"BRETT2"},
            "priceUsd":"0.0022","liquidity":{"usd":90000}},
            {"chainId":"solana","pairAddress":"x","baseToken":{"address":"So11111111111111111111111111111111111111112","symbol":"SOL"},"priceUsd":"150"}]"""
        val pairs = MultiChainMemeLane7962.parseDexPairs7962(body, "DEX_TOKENS")
        assertEquals(2, pairs.size)
        assertEquals("BRETT2", pairs[0].symbol); assertEquals(850_000.0, pairs[0].mcapUsd, 1e-9); assertEquals(1760000000000L, pairs[0].createdAtMs)
        assertEquals(12, pairs[0].buys5m); assertEquals(-2.0, pairs[0].chg1hPct, 1e-9)
        assertEquals(0.0021, MultiChainMemeLane7962.markFromPairs7962(pairs, tokenB, "0xaaaa000000000000000000000000000000000001")!!, 1e-12)
        assertEquals(0.0022, MultiChainMemeLane7962.markFromPairs7962(pairs, tokenB, "")!!, 1e-12)   // deepest pool
        assertNull(MultiChainMemeLane7962.markFromPairs7962(pairs, tokenA, ""))
        val wrapped = """{"pairs":[{"chainId":"bsc","pairAddress":"0x1","baseToken":{"address":"$tokenA","symbol":"X"},"priceUsd":"1.5"}]}"""
        assertEquals("bsc", MultiChainMemeLane7962.parseDexPairs7962(wrapped, "X").single().chain)
        assertTrue(MultiChainMemeLane7962.parseDexPairs7962("garbage", "X").isEmpty())
    }

    // ── cost and gating ──

    @Test fun costIsGasAtTheTicketPlusQuoteLossPlusTax() {
        // BSC: $20 ticket, $0.05 swap gas, $0.02 approve, 1% one-way loss, 3%+3% tax = 0.6 + 2 + 6.
        assertEquals(8.6, MultiChainMemeLane7962.roundTripCostPct7962(20.0, 0.05, 0.02, 1.0, 3.0, 3.0), 1e-9)
        // Ethereum mainnet at the same ticket: gas alone is 35%.
        val eth = MultiChainMemeLane7962.roundTripCostPct7962(20.0, 3.0, 1.0, 0.5, 0.0, 0.0)
        assertEquals(36.0, eth, 1e-9)
        assertTrue(MultiChainMemeLane7962.roundTripCostPct7962(0.0, 0.05, 0.02, 1.0, 0.0, 0.0).isInfinite())
        // The cost decides against the proven edge — no fixed cap.
        assertTrue(MultiChainMemeLane7962.costClears7962(8.6, 12.0))
        assertFalse(MultiChainMemeLane7962.costClears7962(8.6, 8.0))
        assertFalse(MultiChainMemeLane7962.costClears7962(eth, 30.0))
        assertTrue(MultiChainMemeLane7962.costClears7962(eth, 40.0))
        assertFalse(MultiChainMemeLane7962.costClears7962(Double.NaN, 40.0))
        assertFalse(MultiChainMemeLane7962.costClears7962(5.0, Double.NEGATIVE_INFINITY))
    }

    @Test fun aChainIsLiveOnlyWhenFundedAndItsGasClearsTheProvenEdge() {
        fun g(paper: Boolean = false, signer: Boolean = true, bal: Double = 0.05, reserve: Double = 0.002, ticket: Double = 20.0, rt: Double = 0.12, edge: Double = 6.0): ChainGate7962 =
            MultiChainMemeLane7962.chainGate7962(paper, signer, bal, reserve, ticket, rt, edge)
        assertEquals(ChainGate7962(true, "LIVE"), g())
        assertEquals("PAPER_MODE", g(paper = true).reason)
        assertEquals("NO_EVM_SIGNER", g(signer = false).reason)
        assertEquals("NO_GAS", g(bal = 0.0).reason)
        assertEquals("NO_GAS", g(bal = Double.NaN).reason)
        assertEquals("GAS_BELOW_EXIT_RESERVE", g(bal = 0.001).reason)
        assertEquals("TICKET_UNPRICED", g(ticket = 0.0).reason)
        assertEquals("NO_PROVEN_CELL", g(edge = Double.NaN).reason)
        // Ethereum: $7 of round-trip gas on a $20 ticket (35%) against a +12% edge stays shadow.
        val eth = g(rt = 7.0, edge = 12.0)
        assertFalse(eth.live); assertTrue(eth.reason.startsWith("GAS_35PCT"))
        // ...and goes live by itself once the ticket is large enough.
        assertTrue(g(rt = 7.0, edge = 12.0, ticket = 200.0).live)
    }

    @Test fun cellsEarnLiveAuthorityFromTheirOwnLabels() {
        val adm = MultiChainMemeLane7962::admit7962
        assertEquals("LANE_PROVEN_NEGATIVE_7962", adm(null, CellView7962(60, -5.0, 1.0), false, 0.0))
        assertEquals("CELL_PROVEN_NEGATIVE_7962", adm(CellView7962(25, -4.0, 2.0), null, false, 0.0))
        // Paper explores an unproven cell; live does not.
        assertNull(adm(CellView7962(5, 10.0, 8.0), null, false, 0.0))
        assertNull(adm(null, null, false, 0.0))
        assertEquals("CELL_UNPROVEN_FOR_LIVE_7962", adm(CellView7962(5, 10.0, 8.0), null, true, 0.5))
        assertEquals("CELL_UNPROVEN_FOR_LIVE_7962", adm(null, null, true, 0.5))
        // Proven cell: edge = 12 - 0.5*4 = 10.
        assertEquals(10.0, CellView7962(30, 12.0, 4.0).edgePct, 1e-9)
        assertNull(adm(CellView7962(30, 12.0, 4.0), CellView7962(200, 1.0, 0.5), true, 3.0))
        assertEquals("CELL_EDGE_BELOW_CHAIN_COST_7962", adm(CellView7962(30, 12.0, 4.0), null, true, 11.0))
        assertTrue(CellView7962(1, 50.0, Double.POSITIVE_INFINITY).edgePct.isInfinite())
    }

    @Test fun exitsFollowStopTiersTrailAndHold() {
        val tiers = listOf(40.0 to 0.6, 120.0 to 0.7, 400.0 to 0.7)
        fun x(pnl: Double, peak: Double, fired: Int = 0, holdMin: Long = 1L): ExitAction7962? =
            MultiChainMemeLane7962.exitDecision7962(pnl, peak, holdMin * 60_000L, tiers, fired, 0.5, 60L * 60_000L, 25.0)
        assertEquals("STOP_LOSS_7962", x(-26.0, 0.0)!!.reason)
        assertEquals(1.0, x(-26.0, 0.0)!!.fraction, 1e-9)
        val t1 = x(45.0, 45.0)!!
        assertEquals("TIER_1_7962", t1.reason); assertEquals(0.6, t1.fraction, 1e-9)
        assertNull(x(45.0, 45.0, fired = 1))
        assertEquals("TIER_2_7962", x(130.0, 130.0, fired = 1)!!.reason)
        // Trail: peak +100, room max(50, 8) -> exits at or under +50.
        assertEquals("TRAIL_7962", x(40.0, 100.0, fired = 1)!!.reason)
        assertNull(x(60.0, 100.0, fired = 1))
        // Below the arm (half the first tier) the trail waits.
        assertNull(x(2.0, 15.0))
        assertEquals("MAX_HOLD_7962", x(5.0, 10.0, holdMin = 61L)!!.reason)
        assertNull(MultiChainMemeLane7962.exitDecision7962(Double.NaN, 0.0, 0L, tiers, 0, 0.5, 0L, 25.0))
    }

    // ── wiring ──

    @Test fun laneIsWiredIntoLabelsDiagAndTheCryptoTrader() {
        val root = "src/main/kotlin/com/lifecyclebot"
        val xa = File("$root/engine/cortex/CrossAssetCortex7931.kt").readText()
        assertTrue(xa.contains("MultiChainMemeLane7962.freshMark7962(key, nowMs)"))
        assertTrue(xa.contains("l.startsWith(\"EVM_\")"))
        val trader = File("$root/perps/CryptoAltTrader.kt").readText()
        assertTrue(trader.contains("MultiChainMemeLane7962.start7962(it)"))
        assertTrue(trader.contains("MultiChainMemeLane7962.stop7962()"))
        // The diag line rides the Cross-asset Cortex line (PipelineHealthCollector.dumpText is pinned and may only shrink).
        assertTrue(xa.contains("Multi-chain lane (§7962):") && xa.contains("MultiChainMemeLane7962.statusLine()"))
        assertTrue(File("$root/engine/PipelineHealthCollector.kt").readText().contains("CrossAssetCortex7931.statusLine()"))
        val lane = File("$root/perps/crypto/MultiChainMemeLane7962.kt").readText()
        assertTrue(lane.contains("ForwardReturnLabeler7731.observe(ts, lane, admitted, refusal, now, scoreOf(c))"))
        assertTrue(lane.contains("Cortex7885.entryRefusal(ts, lane, !liveChain)"))
        assertTrue(lane.contains("ExitProfile7955.planForKey7955(p.lane"))
        val exec = File("$root/perps/crypto/EvmSwapExecutor7962.kt").readText()
        assertTrue(exec.contains("EvmBridgeTransactionEngine6649.execute(request, creds, rpc, store)"))
        assertTrue(exec.contains("MultiChainWalletVault6546.evmCredentials6649(ctx)"))
        assertTrue(exec.contains("EvmBridgeTransactionEngine6649.approvalData(route.routerAddress, qtyRaw)"))
        // Solana routing is untouched: the resolver still owns SPL/Jupiter.
        val resolver = File("$root/perps/crypto/CryptoUniverseRouteResolver.kt").readText()
        assertTrue(resolver.contains("CryptoExecutionRoute.JUPITER_ROUTABLE"))
        assertFalse(resolver.contains("7962"))
    }
}
