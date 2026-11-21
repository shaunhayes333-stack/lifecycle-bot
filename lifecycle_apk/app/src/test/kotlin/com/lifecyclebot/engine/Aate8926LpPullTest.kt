package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LpPull8926
import com.lifecyclebot.engine.truth.OnChainDepth8038
import com.lifecyclebot.engine.truth.PoolLock8895
import io.github.novacrypto.base58.Base58
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8926 — a held coin's pool whose liquidity units fall 30 %+ below the watched peak is liquidity pulled: sold at once
 * as an emergency stop. Raydium AMM v4's burned LP is read on chain. The bundle read counts who PAID for tokens, never
 * the curve, a pool or a transfer's receiver.
 */
class Aate8926LpPullTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val mint = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"
    private val pool = "HLnpSz9h2S4hiLQ43rnSD9XkcUThA7B8hQMKmDaiTLcC"
    private val lpMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private val wsol = "So11111111111111111111111111111111111111112"

    @Before fun setUp() = LpPull8926.resetForTest8926()
    @After fun tearDown() = LpPull8926.resetForTest8926()

    private fun put(b: ByteArray, off: Int, key: String) = Base58.base58Decode(key).copyInto(b, off)
    private fun putLe(b: ByteArray, off: Int, v: Long, len: Int) { val x = BigInteger.valueOf(v); for (i in 0 until len) b[off + i] = x.shiftRight(8 * i).toInt().toByte() }
    private fun disc(b: ByteArray, d: IntArray) { for (i in 0 until 8) b[i] = d[i].toByte() }

    @Test fun eachPoolsLiquidityUnitsAreReadFromItsOwnAccount() {
        val damm = ByteArray(PoolLock8895.DAMM_V2_SIZE_8895).also { x -> disc(x, PoolLock8895.DAMM_V2_POOL_DISC_8895); put(x, 168, mint); put(x, 200, wsol); putLe(x, 360, 5_000L, 16) }
        assertEquals(BigInteger.valueOf(5_000L), LpPull8926.units8926(OnChainDepth8038.METEORA_DAMM_V2_8418, mint, damm))
        val cpmm = ByteArray(PoolLock8895.CPMM_POOL_SIZE_8925).also { x -> disc(x, PoolLock8895.CPMM_POOL_DISC_8925); put(x, 136, lpMint); put(x, 168, wsol); put(x, 200, mint); putLe(x, 333, 900L, 8) }
        assertEquals(BigInteger.valueOf(900L), LpPull8926.units8926("CPMMoo8L3F4NbTegBCKVNunggL7H1ZpdTHKxQB5qKP1C", mint, cpmm))
        val v4 = ByteArray(LpPull8926.AMM_V4_SIZE_8926).also { x -> put(x, 400, mint); put(x, 432, wsol); put(x, 464, lpMint); putLe(x, 720, 777L, 8) }
        assertEquals(BigInteger.valueOf(777L), LpPull8926.units8926("675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8", mint, v4))
        assertNull("another coin's pool", LpPull8926.units8926("675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8", lpMint, v4))
        assertNull("a program we do not read", LpPull8926.units8926("6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P", mint, damm))
        val (lp, amount) = PoolLock8895.ammV4Pool8926(mint, v4)!!
        assertEquals(lpMint, lp); assertEquals(BigInteger.valueOf(777L), amount)
        assertEquals("AMM v4: 700 of 777 LP burned", 90.09, PoolLock8895.cpmmLockedPct8925(amount, BigInteger.valueOf(77L), BigInteger.ZERO)!!, 0.01)
    }

    @Test fun thirtyPercentPulledBelowTheWatchedPeakSells() {
        assertEquals(0.0, LpPull8926.dropPct8926(BigInteger.valueOf(1_000L), BigInteger.valueOf(1_200L)), 0.0)
        assertEquals(40.0, LpPull8926.dropPct8926(BigInteger.valueOf(1_000L), BigInteger.valueOf(600L)), 1e-9)
        assertTrue(LpPull8926.pulled8926(BigInteger.valueOf(1_000L), BigInteger.valueOf(700L), 1_000L, 2_000L))
        assertFalse("29 % is counted, not sold", LpPull8926.pulled8926(BigInteger.valueOf(1_000L), BigInteger.valueOf(710L), 1_000L, 2_000L))
        assertFalse("a stale read never sells", LpPull8926.pulled8926(BigInteger.valueOf(1_000L), BigInteger.valueOf(100L), 1_000L, 1_000L + LpPull8926.FRESH_MS_8926 + 1L))
        val now = System.currentTimeMillis()
        LpPull8926.putForTest8926(mint, pool, 1_000L, 100L, now)
        assertEquals(90.0, LpPull8926.check8926(mint, pool, now)!!, 1e-9)
        assertNull("another coin on that pool key", LpPull8926.check8926(lpMint, pool, now))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("lpPullExit8926(ts, wallet, effectiveBalance)   // V5.0.8926"))
        assertTrue(bot.contains("val reason = \"\${com.lifecyclebot.engine.truth.LpPull8926.REASON_8926}_DROP\${drop.toInt()}PCT\""))
        assertTrue("an emergency to every exit class", com.lifecyclebot.engine.sell.ProtectiveExitClass7807.isEmergency(LpPull8926.REASON_8926 + "_DROP90PCT"))
    }

    @Test fun theCryptoDeskReadsTextbookRsiAndMacd() {
        val ai = com.lifecyclebot.perps.PerpsAdvancedAI
        val (ag, al) = ai.wilderAverages8926(listOf(10.0, 11.0, 10.0, 11.0, 12.0), 2)!!
        assertEquals("Wilder: seeded on the first two changes, then smoothed", 0.875, ag, 1e-12)
        assertEquals(0.125, al, 1e-12)
        assertNull(ai.wilderAverages8926(listOf(1.0, 2.0), 2))
        assertEquals(listOf(2.0, 3.0, 4.0), ai.emaSeries8926(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3).map { Math.round(it * 1e9) / 1e9 }.let { listOf(it[0], it[1], it[2]) })
        val rising = List(30) { 100.0 } + List(20) { 101.0 + it }
        assertEquals(com.lifecyclebot.perps.PerpsAdvancedAI.MacdSignal.BULLISH, ai.macdSignal8926(rising))
        val falling = List(30) { 100.0 } + List(20) { 99.0 - it }
        assertEquals(com.lifecyclebot.perps.PerpsAdvancedAI.MacdSignal.BEARISH, ai.macdSignal8926(falling))
        val cross = List(35) { 100.0 } + List(14) { 99.0 - it } + listOf(130.0)
        assertEquals("the MACD line crossing its signal line on the last point", com.lifecyclebot.perps.PerpsAdvancedAI.MacdSignal.BULLISH_CROSS, ai.macdSignal8926(cross))
        assertEquals("too few points for a signal line: the side of zero, never a cross",
            com.lifecyclebot.perps.PerpsAdvancedAI.MacdSignal.BULLISH, ai.macdSignal8926(List(20) { 100.0 } + List(10) { 101.0 + it }))
        assertEquals(com.lifecyclebot.perps.PerpsAdvancedAI.MacdSignal.NEUTRAL, ai.macdSignal8926(List(20) { 100.0 }))
    }

    private fun tx(payer: String, fee: Long, payerLamports: Long, changes: List<Pair<String, Long>>, wsolChange: Long = 0L) = JSONObject("""
        {"signature":"s","slot":1,"timestamp":1,"feePayer":"$payer","fee":$fee,"accountData":[
          {"account":"$payer","nativeBalanceChange":$payerLamports,"tokenBalanceChanges":[
            ${(changes.map { (o, a) -> """{"userAccount":"$o","mint":"$mint","rawTokenAmount":{"tokenAmount":"$a","decimals":0}}""" } +
              (if (wsolChange != 0L) listOf("""{"userAccount":"$payer","mint":"$wsol","rawTokenAmount":{"tokenAmount":"$wsolChange","decimals":9}}""") else emptyList())).joinToString(",")}
          ]}]}""")

    @Test fun whoBoughtIsWhoPaid() {
        // a buy: the payer's SOL fell, the curve paid out 1,000 to the payer and 500 to a second wallet the payer funded
        val buy = BundleDetector.parsed8926(tx("payer", 5_000L, -1_000_000_000L, listOf("payer" to 1_000L, "wallet2" to 500L, "curve" to -1_500L)), mint)!!
        assertEquals(listOf(Triple("payer", 1_000.0, false), Triple("wallet2", 500.0, false)), buy.sortedBy { it.first })
        // a sale: the payer's tokens fell while its SOL rose; the pool receiving them is not a buyer
        val sell = BundleDetector.parsed8926(tx("payer", 5_000L, 400_000_000L, listOf("payer" to -800L, "pool" to 800L)), mint)!!
        assertEquals(listOf(Triple("payer", 800.0, true)), sell)
        // a transfer: the creator moves supply to a second wallet, paying only the fee: neither
        assertTrue(BundleDetector.parsed8926(tx("creator", 5_000L, -5_000L, listOf("creator" to -6_000L, "second" to 6_000L)), mint)!!.isEmpty())
        // a buy paid in wrapped SOL
        val wsolBuy = BundleDetector.parsed8926(tx("payer", 5_000L, -5_000L, listOf("payer" to 300L, "pool" to -300L), wsolChange = -500_000_000L), mint)!!
        assertEquals(listOf(Triple("payer", 300.0, false)), wsolBuy)
        assertNull("no balance change of the coin", BundleDetector.parsed8926(JSONObject("{\"feePayer\":\"p\",\"accountData\":[]}"), mint))
        assertTrue(src("engine/BundleDetector.kt").contains("val firstBlockWalletTotals = firstBlockTxs.filter { !it.isOutboundFromWallet }"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 114. Liquidity pulled, we leave (8926)"))
    }
}
