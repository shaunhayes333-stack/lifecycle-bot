package com.lifecyclebot.network

import com.lifecyclebot.engine.truth.TradeTape8100
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

/** Fixtures follow the official pump_amm IDL field order, including BuyEvent's variable-length ix_name. */
class Aate8870PumpSwapEventTest {
    private val now = 1_800_000_000_000L
    private val poolBytes = ByteArray(32) { (it + 21).toByte() }
    private val pool = io.github.novacrypto.base58.Base58.base58Encode(poolBytes)
    private val hundredSol = BigInteger.valueOf(100_000_000_000L)
    private val u64Max = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
    private data class Fixture(val bytes: ByteArray, val feesAt: Int, val virtualAt: Int)

    private fun fixture(buy: Boolean, virtual: BigInteger, quote: Long = 200_000_000L,
                        rawPoolQuote: BigInteger = hundredSol, base: Long = 1_000_000_000L,
                        poolBase: Long = 1_000_000_000_000L, ixName: String = "buy"): Fixture {
        val out = ByteArrayOutputStream()
        fun integer(v: BigInteger, n: Int) { repeat(n) { out.write(v.shiftRight(8 * it).and(BigInteger.valueOf(255)).toInt()) } }
        fun u64(v: Long) = integer(BigInteger.valueOf(v), 8)
        out.write(if (buy) byteArrayOf(103, -12, 82, 31, 44, -11, 119, 119)
            else byteArrayOf(62, 47, 55, 10, -91, 3, -36, 42))
        u64(now / 1000L); u64(base); u64(0L) // timestamp, base amount, limit quote
        u64(0L); u64(0L) // user base / quote reserves
        u64(poolBase); integer(rawPoolQuote, 8); u64(quote)
        repeat(6) { u64(0L) } // LP/protocol fee bps and amounts, quote after LP fee, user quote
        out.write(poolBytes); out.write(ByteArray(32) { (it + 65).toByte() })
        repeat(5) { out.write(ByteArray(32)) } // user token accounts, fee recipient/accounts, coin creator
        u64(0L); u64(0L) // creator fee bps / amount
        if (buy) {
            out.write(0) // track_volume
            repeat(4) { u64(0L) } // volume/claim counters and timestamp
            u64(0L) // min_base_amount_out
            val name = ixName.toByteArray(Charsets.UTF_8)
            integer(BigInteger.valueOf(name.size.toLong()), 4); out.write(name)
        }
        val feesAt = out.size()
        repeat(4) { u64(0L) } // cashback and buyback bps / amounts
        val virtualAt = out.size()
        integer(virtual, 16); out.write(if (virtual.signum() > 0) 1 else 0) // signed i128, can_boost
        repeat(4) { u64(0L) } // base_supply, holder rewards bps / amount, unclaimed creator fee
        return Fixture(out.toByteArray(), feesAt, virtualAt)
    }

    @Test fun currentBoostedBuyAndSellUseEffectiveReservesWithoutRelaxingSanity() {
        for (buy in listOf(true, false)) {
            val f = fixture(buy, hundredSol)
            assertEquals(if (buy) 440 else 392, f.virtualAt)
            assertNull("legacy raw-reserve interpretation rejects this valid extended event",
                PumpSwapEvents8191.decode8191(f.bytes.copyOf(184)))
            val event = PumpSwapEvents8191.decode8191(f.bytes)
            assertNotNull(event)
            assertEquals(buy, event!!.isBuy)
            assertEquals(0.2, event.sol, 1e-12)
            assertEquals(1000.0, event.tokens, 1e-12)
            assertEquals(pool, event.pool)
            assertEquals(now / 1000L, event.tsSec)
            assertNull("the existing 25% disagreement rule still rejects bad events",
                PumpSwapEvents8191.decode8191(fixture(buy, hundredSol, quote = 300_000_000L).bytes))
        }
    }

    @Test fun negativeVirtualFeesAreSignedAndAddedBeforeFloatingPointConversion() {
        for (buy in listOf(true, false)) {
            val f = fixture(buy, hundredSol.negate().divide(BigInteger.TWO), quote = 50_000_000L)
            assertEquals(hundredSol.negate().divide(BigInteger.TWO), PumpSwapEvents8191.virtualQuoteReserves8870(f.bytes, buy))
            assertNotNull(PumpSwapEvents8191.decode8191(f.bytes))
            val one = fixture(buy, BigInteger.ONE.subtract(u64Max), quote = 1L,
                rawPoolQuote = u64Max, base = 1L, poolBase = 1L)
            assertEquals(1.0, PumpSwapEvents8191.effectiveQuoteReserves8870(one.bytes, buy)!!, 0.0)
            assertNotNull(PumpSwapEvents8191.decode8191(one.bytes))
            assertNull(PumpSwapEvents8191.decode8191(fixture(buy, hundredSol.negate()).bytes))
            assertNull(PumpSwapEvents8191.decode8191(fixture(buy, hundredSol.negate().subtract(BigInteger.ONE)).bytes))
        }
    }

    @Test fun completeOlderExtendedPayloadsKeepTheirZeroVirtualReserveMeaning() {
        for (buy in listOf(true, false)) {
            val f = fixture(buy, BigInteger.ZERO, quote = 100_000_000L)
            val historicalEnds = listOf(184, 312, 360, f.feesAt, f.feesAt + 16, f.feesAt + 32) +
                if (buy) listOf(393, 401) else emptyList()
            for (end in historicalEnds.distinct()) {
                val old = f.bytes.copyOf(end)
                assertEquals("complete older tail ending at $end", BigInteger.ZERO,
                    PumpSwapEvents8191.virtualQuoteReserves8870(old, buy))
                assertNotNull("legacy event ending at $end still decodes", PumpSwapEvents8191.decode8191(old))
            }
        }
    }

    @Test fun variableBuyInstructionLengthCannotMoveTheSignedFieldToAFixedOffset() {
        for (name in listOf("buy", "buy_exact_quote_in", "buy_v2", "buy_exact_quote_in_v2")) {
            val f = fixture(true, hundredSol, ixName = name)
            assertEquals(401 + 4 + name.toByteArray(Charsets.UTF_8).size + 32, f.virtualAt)
            assertEquals(hundredSol, PumpSwapEvents8191.virtualQuoteReserves8870(f.bytes, true))
            assertNotNull(PumpSwapEvents8191.decode8191(f.bytes))
        }
    }

    @Test fun partialExtensionsAndInvalidBorshLengthsAreNotTreatedAsLegacyZero() {
        for (buy in listOf(true, false)) {
            val f = fixture(buy, BigInteger.ZERO, quote = 100_000_000L)
            for (n in 1..15) assertNull(PumpSwapEvents8191.decode8191(f.bytes.copyOf(f.virtualAt + n)))
            for (n in listOf(1, 7, 9, 15, 17, 31))
                assertNull(PumpSwapEvents8191.decode8191(f.bytes.copyOf(f.feesAt + n)))
            for (n in listOf(2, 8, 10, 16, 18, 24, 26, 32))
                assertNull(PumpSwapEvents8191.decode8191(f.bytes.copyOf(f.virtualAt + 16 + n)))
            val badFlag = f.bytes.copyOf().apply { this[f.virtualAt + 16] = 2 }
            assertNull(PumpSwapEvents8191.decode8191(badFlag))
        }
        val buy = fixture(true, BigInteger.ZERO, quote = 100_000_000L).bytes
        for (n in 402..404) assertNull(PumpSwapEvents8191.decode8191(buy.copyOf(n)))
        val badLength = buy.copyOf().apply { for (i in 401..404) this[i] = -1 }
        assertNull(PumpSwapEvents8191.decode8191(badLength))
    }

    @Test fun anExtendedEventStillCannotPublishBeforeThePoolsUnitsAreProven() {
        TradeTape8100.resetForTest8100(); PumpSwapTape8307.clearForTest8307()
        val mint = "Aate887oEventUnproven11111111111111111pump"
        val bytes = fixture(true, hundredSol).bytes
        val logs = listOf("Program data: " + Base64.getEncoder().encodeToString(bytes))
        try {
            assertNotNull(PumpSwapEvents8191.decode8191(bytes))
            // A symbol, subscription or balance hint is not proof that this is a 6-decimal base quoted in WSOL.
            PumpSwapTape8307.notePoolMint8307(pool, mint, now, verifiedSolQuote8751 = false)
            assertNull(PumpSwapTape8307.mintForPool8307(pool))
            assertEquals(0, PumpSwapTape8307.onProgramLogs8307("unproven8870", logs, now))
            assertTrue(TradeTape8100.prints8128(mint, now, 120_000L).isEmpty())
        } finally {
            TradeTape8100.resetForTest8100(); PumpSwapTape8307.clearForTest8307()
        }
    }
}
