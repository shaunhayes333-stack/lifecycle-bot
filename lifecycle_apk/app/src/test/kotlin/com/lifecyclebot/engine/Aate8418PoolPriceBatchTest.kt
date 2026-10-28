package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ConcentratedPrice8418
import com.lifecyclebot.engine.truth.OnChainDepth8038
import com.lifecyclebot.engine.truth.PoolBatch8418
import com.lifecyclebot.network.JupiterPriceIds8418
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

/** V5.0.8418 — concentrated-pool spot price from pool state, batched vault reads, Jupiter price ids, leaderboard bench. */
class Aate8418PoolPriceBatchTest {
    private val wsol = OnChainDepth8038.WSOL
    private val coinBytes = ByteArray(32) { (it * 7 + 11).toByte() }
    private val coin = io.github.novacrypto.base58.Base58.base58Encode(coinBytes)
    private val wsolBytes = io.github.novacrypto.base58.Base58.base58Decode(wsol)

    @Before fun reset() { ConcentratedPrice8418.resetForTests8418(); LeaderboardRoutes8418.resetForTests8418() }
    @After fun clean() { ConcentratedPrice8418.resetForTests8418(); LeaderboardRoutes8418.resetForTests8418() }

    private fun putU128(data: ByteArray, off: Int, v: BigInteger) {
        val be = v.toByteArray()
        for (i in 0 until 16) data[off + i] = if (i < be.size) be[be.size - 1 - i] else 0
    }

    private fun putI32(data: ByteArray, off: Int, v: Int) { for (i in 0 until 4) data[off + i] = (v ushr (8 * i)).toByte() }
    private fun putU16(data: ByteArray, off: Int, v: Int) { data[off] = v.toByte(); data[off + 1] = (v ushr 8).toByte() }

    /** sqrt(raw) x 2^64 as the on-chain Q64.64 integer. */
    private fun sqrtX64(raw: Double): BigInteger =
        BigDecimal(Math.sqrt(raw)).multiply(BigDecimal(BigInteger.ONE.shiftLeft(64)), MathContext.DECIMAL128).toBigInteger()

    private fun rel(expected: Double, actual: Double?, tol: Double = 1e-9) {
        assertNotNull(actual)
        assertTrue("expected $expected got $actual", Math.abs(actual!! - expected) <= tol * Math.abs(expected))
    }

    // ── known vectors ──

    @Test fun sqrtPriceAndBinDecodeMatchThePublishedConstants() {
        // Q64.64 identity: sqrt = 2^64 is price 1.
        rel(1.0, ConcentratedPrice8418.rawPriceFromSqrtX64_8418(BigInteger.ONE.shiftLeft(64)))
        // Orca / Raydium CLMM MIN_SQRT_PRICE (tick -443636) and Raydium MAX_SQRT_PRICE_X64 (tick 443636): price = 1.0001^tick.
        rel(Math.pow(1.0001, -443636.0), ConcentratedPrice8418.rawPriceFromSqrtX64_8418(BigInteger("4295048016")), 1e-6)
        rel(Math.pow(1.0001, 443636.0), ConcentratedPrice8418.rawPriceFromSqrtX64_8418(BigInteger("79226673521066979257578248091")), 1e-6)
        assertNull(ConcentratedPrice8418.rawPriceFromSqrtX64_8418(BigInteger.ZERO))
        // DLMM: (1 + bin_step / 10^4)^active_id.
        rel(1.01, ConcentratedPrice8418.rawPriceFromBin8418(1, 100))
        rel(Math.pow(1.0025, -2000.0), ConcentratedPrice8418.rawPriceFromBin8418(-2000, 25))
        assertNull(ConcentratedPrice8418.rawPriceFromBin8418(5, 0))
        // SOL/USDC: SOL token0 (9 dp), USDC token1 (6 dp), $150 -> raw 0.15.
        rel(150.0, ConcentratedPrice8418.solPerCoin8418(0.15, 9, 6, coinIsToken0 = true))
        // Little-endian readers.
        val d = ByteArray(32)
        putI32(d, 0, -2000); putU16(d, 4, 25); putU128(d, 8, BigInteger("123456789012345678901234567"))
        assertEquals(-2000, ConcentratedPrice8418.i32le8418(d, 0))
        assertEquals(25, ConcentratedPrice8418.u16le8418(d, 4))
        assertEquals(BigInteger("123456789012345678901234567"), ConcentratedPrice8418.u128le8418(d, 8))
        assertNull(ConcentratedPrice8418.u128le8418(d, 20))
    }

    @Test fun raydiumClmmPriceBothMintOrdersAndLayoutProof() {
        // 1 coin (6 dp) = 2e-6 SOL. Coin is token0: raw(1 per 0) = 2e-6 x 10^(9-6).
        val a = ByteArray(1544)
        coinBytes.copyInto(a, 73); wsolBytes.copyInto(a, 105); a[233] = 6; a[234] = 9
        putU128(a, 253, sqrtX64(2e-3))
        rel(2e-6, ConcentratedPrice8418.decode8418(OnChainDepth8038.RAYDIUM_CLMM_8418, a, coin, 6, 9), 1e-9)
        // WSOL is token0: raw(coin per SOL) = 5e5 x 10^(6-9) = 500.
        val b = ByteArray(1544)
        wsolBytes.copyInto(b, 73); coinBytes.copyInto(b, 105); b[233] = 9; b[234] = 6
        putU128(b, 253, sqrtX64(500.0))
        rel(2e-6, ConcentratedPrice8418.decode8418(OnChainDepth8038.RAYDIUM_CLMM_8418, b, coin, 6, 9), 1e-9)
        // Stored decimals disagree with the vaults: no price (layout not proven).
        assertNull(ConcentratedPrice8418.decode8418(OnChainDepth8038.RAYDIUM_CLMM_8418, a, coin, 9, 9))
        // Another coin's pool: no price.
        assertNull(ConcentratedPrice8418.decode8418(OnChainDepth8038.RAYDIUM_CLMM_8418, a, "Other111111111111111111111111111111111111", 6, 9))
        assertNull("unknown program", ConcentratedPrice8418.decode8418("x", a, coin, 6, 9))
    }

    @Test fun whirlpoolAndDlmmPrices() {
        val w = ByteArray(653)
        wsolBytes.copyInto(w, 101); coinBytes.copyInto(w, 181)
        putU128(w, 65, sqrtX64(500.0))   // a = SOL (9), b = coin (6): 5e5 coin per SOL
        rel(2e-6, ConcentratedPrice8418.decode8418(OnChainDepth8038.ORCA_WHIRLPOOL_8418, w, coin, 6, 9), 1e-9)

        val l = ByteArray(904)
        coinBytes.copyInto(l, 88); wsolBytes.copyInto(l, 120)
        putI32(l, 76, -2000); putU16(l, 80, 25)
        val expected = Math.pow(1.0025, -2000.0) * 1e-3   // x = coin (6), y = SOL (9)
        rel(expected, ConcentratedPrice8418.decode8418(OnChainDepth8038.METEORA_DLMM_8418, l, coin, 6, 9), 1e-9)
        assertEquals("CHAIN_METEORA_DLMM_BIN_8418", ConcentratedPrice8418.sourceOf8418(OnChainDepth8038.METEORA_DLMM_8418))
    }

    // ── the read path: the same acceptance the batch and the buy door use ──

    private fun vault(mint: String, ui: String, dec: Int) = JSONObject().put("data", JSONObject().put("parsed",
        JSONObject().put("info", JSONObject().put("mint", mint).put("tokenAmount", JSONObject().put("uiAmountString", ui).put("decimals", dec)))))

    @Test fun aClmmReadRecordsATaggedStatePriceAndStaysDepthOnly() {
        val mint = coin
        val data = ByteArray(1544)
        coinBytes.copyInto(data, 73); wsolBytes.copyInto(data, 105); data[233] = 6; data[234] = 9
        putU128(data, 253, sqrtX64(2e-3))
        val poolAcct = JSONObject().put("data", JSONArray().put(java.util.Base64.getEncoder().encodeToString(data)).put("base64"))
        val out = OnChainDepth8038.accept8418(mint, "ClmmPool8418", OnChainDepth8038.RAYDIUM_CLMM_8418,
            vault(mint, "1000000", 6), vault(wsol, "40", 9), poolAcct, 1_000L)
        assertEquals(OnChainDepth8038.Accept8418.CONCENTRATED, out)
        val px = ConcentratedPrice8418.fresh8418(mint, 90_000L, 2_000L)
        assertNotNull(px)
        rel(2e-6, px!!.solPerCoin, 1e-9)
        assertEquals("CHAIN_RAYDIUM_CLMM_SQRT_8418", px.source)
        assertNull("never a constant-product read (no CP sell-out)", OnChainDepth8038.freshRead8198(mint, 90_000L, 2_000L))
        assertNull("stale price is not served", ConcentratedPrice8418.fresh8418(mint, 90_000L, 200_000L))
        assertEquals(listOf("vA", "vB", "ClmmPool8418"), OnChainDepth8038.readKeys8418("vA" to "vB", "ClmmPool8418", OnChainDepth8038.RAYDIUM_CLMM_8418))
        assertEquals(listOf("vA", "vB"), OnChainDepth8038.readKeys8418("vA" to "vB", "P", OnChainDepth8038.PUMP_AMM))
        assertTrue(OnChainDepth8038.priceInState8418(OnChainDepth8038.METEORA_DLMM_8418))
        assertFalse("DAMM v2 stays depth only", OnChainDepth8038.priceInState8418(OnChainDepth8038.METEORA_DAMM_V2_8418))
    }

    @Test fun aConstantProductReadIsStillTheVaultRatioAndAWrongPairIsRefused() {
        val mint = "CpMint8418aaaaaaaaaaaaaaaaaaaaaaaaaaaapump"
        assertEquals(OnChainDepth8038.Accept8418.CONSTANT_PRODUCT,
            OnChainDepth8038.accept8418(mint, "CpPool8418", OnChainDepth8038.PUMP_AMM, vault(mint, "500000", 6), vault(wsol, "25", 9), null, 5_000L))
        val r = OnChainDepth8038.freshRead8198(mint, 90_000L, 6_000L)!!
        assertEquals(25.0, r.quoteSol, 0.0)
        assertEquals(500_000.0, r.baseTokens, 0.0)
        assertEquals(OnChainDepth8038.Accept8418.NOT_COIN_SOL,
            OnChainDepth8038.accept8418("SomeOtherMint", "CpPool8418b", OnChainDepth8038.PUMP_AMM, vault(mint, "1", 6), vault(wsol, "1", 9), null, 5_000L))
        val d = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        assertEquals(3, OnChainDepth8038.accountData8418(JSONObject().put("data", JSONArray().put(d).put("base64")))!!.size)
        assertNull(OnChainDepth8038.accountData8418(JSONObject().put("data", JSONObject())))
    }

    @Test fun batchesPackWholeMintsIntoHundredKeyCalls() {
        val groups = (0 until 40).map { i -> listOf("a$i", "b$i", "p$i") }   // 120 keys
        val calls = PoolBatch8418.pack8418(groups)
        assertEquals(2, calls.size)
        assertEquals(33, calls[0].size)                                      // 99 keys; a 34th mint would split
        assertEquals(7, calls[1].size)
        assertTrue(calls.flatten() == (0 until 40).toList())
        // Shared keys are counted once; an oversized group is dropped, never split.
        assertEquals(listOf(listOf(0, 1)), PoolBatch8418.pack8418(listOf(listOf("x", "y"), listOf("x", "z")), max = 3))
        assertEquals(listOf(listOf(1)), PoolBatch8418.pack8418(listOf(List(101) { "k$it" }, listOf("q"))))
        assertEquals(100, PoolBatch8418.MAX_KEYS)
        assertTrue(PoolBatch8418.statusLine8418().contains("calls="))
        assertFalse(PoolBatch8418.enqueue8418("", listOf("p")))
    }

    // ── Jupiter price ids / leaderboard ──

    @Test fun jupiterPriceBatchesCarrySolanaMintsOnlyAndV3IsParsed() {
        val sol = "So11111111111111111111111111111111111111112"
        assertEquals(listOf(sol), JupiterPriceIds8418.ids8418(listOf("0x4200000000000000000000000000000000000006", sol, sol, "")))
        val alpha = "123456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val many = List(80) { coin.dropLast(2) + alpha[it % 33] + alpha[it / 33] }
        assertEquals(80, many.toSet().size)
        assertEquals(50, JupiterPriceIds8418.ids8418(many).size)
        assertEquals(80, JupiterPriceIds8418.ids8418(many, Int.MAX_VALUE).size)
        assertFalse(JupiterPriceIds8418.solanaMint8418("0xabc"))
        assertEquals(162.5, JupiterPriceIds8418.usdPrice8418(JSONObject("""{"$sol":{"usdPrice":162.5}}"""), sol)!!, 0.0)
        assertEquals(161.0, JupiterPriceIds8418.usdPrice8418(JSONObject("""{"data":{"$sol":{"price":"161.0"}}}"""), sol)!!, 0.0)
        assertNull(JupiterPriceIds8418.usdPrice8418(JSONObject("{}"), sol))
    }

    @Test fun deadLeaderboardRoutesAreBenchedAndARowReopensThem() {
        val gmgn = "https://gmgn.ai/defi/quotation/v1/rank/sol/wallets/7d"
        val pump = "https://frontend-api-v3.pump.fun/leaderboard?timeframe=7d"
        LeaderboardRoutes8418.note8418(gmgn, 403, challenge = true, rows = -1, nowMs = 0L)
        assertEquals(listOf(pump), LeaderboardRoutes8418.live8418(listOf(pump, gmgn), 1_000L))
        assertEquals(listOf(pump, gmgn), LeaderboardRoutes8418.live8418(listOf(pump, gmgn), LeaderboardRoutes8418.BENCH_BASE_MS_8418 + 1L))
        // Three empty answers bench a route; a 5xx / transport failure does not.
        LeaderboardRoutes8418.note8418(pump, 503, challenge = false, rows = -1, nowMs = 0L)
        LeaderboardRoutes8418.note8418(pump, 0, challenge = false, rows = -1, nowMs = 0L)
        assertTrue(LeaderboardRoutes8418.live8418(listOf(pump), 1L).isNotEmpty())
        repeat(3) { LeaderboardRoutes8418.note8418(pump, 200, challenge = false, rows = 0, nowMs = 0L) }
        assertTrue(LeaderboardRoutes8418.live8418(listOf(pump), 1L).isEmpty())
        assertTrue(LeaderboardRoutes8418.statusLine8418(1L).contains("benched(noRows"))
        LeaderboardRoutes8418.note8418(pump, 200, challenge = false, rows = 5, nowMs = 2L)
        assertTrue(LeaderboardRoutes8418.live8418(listOf(pump), 3L).isNotEmpty())
        assertEquals(2L * LeaderboardRoutes8418.BENCH_BASE_MS_8418, LeaderboardRoutes8418.benchMs8418(1))
        assertEquals(7L * 24 * 3_600_000L, LeaderboardRoutes8418.benchMs8418(9))
        assertTrue(LeaderboardRoutes8418.deadAnswer8418(404, false))
        assertFalse(LeaderboardRoutes8418.deadAnswer8418(429, false))
    }
}
