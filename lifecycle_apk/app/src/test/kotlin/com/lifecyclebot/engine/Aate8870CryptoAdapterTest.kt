package com.lifecyclebot.engine

import com.lifecyclebot.perps.CryptoShortWindow8832
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider vocabulary, JSON nulls and observation clocks must survive crypto discovery unchanged in meaning. */
class Aate8870CryptoAdapterTest {
    private val now = 1_800_000_000_000L
    private val token = "0x1111111111111111111111111111111111111111"
    private val quote = "0x2222222222222222222222222222222222222222"

    private fun payload(network: String = "eth"): JSONObject = JSONObject("""{
        "data":[{"id":"${network}_0x3333333333333333333333333333333333333333",
          "attributes":{"name":"CLOCK / WETH","base_token_price_usd":"0.00000125",
            "pool_created_at":"2026-11-01T00:00:00Z","market_cap_usd":null,"fdv_usd":"900000",
            "reserve_in_usd":"45000.50","volume_usd":{"m5":"1250.25","h24":"240000"},
            "price_change_percentage":{"m5":"1.25","h24":"0"},
            "transactions":{"m5":{"buys":12,"sells":3},"h24":{"buys":1500,"sells":1000}}},
          "relationships":{"base_token":{"data":{"id":"${network}_$token"}},
            "quote_token":{"data":{"id":"${network}_$quote"}},"dex":{"data":{"id":"uniswap-v3"}}}}
        ]} """)

    private fun parse(body: JSONObject, network: String = "eth", observedAt: Long = now - 1_234L) =
        DynamicAltTokenRegistry.parseGeckoPools8870(body.toString(), network, true, observedAt, now).single()

    @Test fun providerNetworkAliasesResolveToTheSameIdentityAsDexDiscovery() {
        for ((gecko, dex) in listOf("eth" to "ethereum", "polygon_pos" to "polygon", "avax" to "avalanche",
            "base" to "base", "bsc" to "bsc", "arbitrum" to "arbitrum", "optimism" to "optimism")) {
            val row = parse(payload(gecko), gecko)
            assertEquals(dex, row.chainId)
            assertEquals(DynamicAltTokenRegistry.canonicalIdentity6544(dex, token), row.canonicalIdentity6544)
            assertEquals(token, row.tokenAddress)
            assertEquals(quote, row.quoteAddress)
        }
    }

    @Test fun usdValuesAndFiveMinuteActivityRetainTheResponseObservationClock() {
        val row = parse(payload())
        assertEquals(0.00000125, row.price, 0.0)
        assertEquals(45_000.50, row.liquidityUsd, 0.0)
        assertEquals(0.0, row.mcap, 0.0)
        assertEquals(900_000.0, row.fdv, 0.0)
        assertEquals(now - 1_234L, row.priceObservedAtMs8372)
        assertEquals(now - 1_234L, row.shortWindowObservedAtMs8832)
        assertEquals(now - 1_234L, row.change24hObservedAtMs8752)
        assertEquals(java.time.Instant.parse("2026-11-01T00:00:00Z").toEpochMilli(), row.pairCreatedAtMs)
        val evidence = CryptoShortWindow8832.from(now, row.shortWindowObservedAtMs8832,
            row.buys5m8832, row.sells5m8832, row.volume5m8832, row.change5mPct8832)
        assertTrue(evidence.activeBuyerFlow)
        assertEquals(80.0, evidence.buyPressurePct, 0.0)
        assertEquals(1250.25, evidence.volume5mUsd!!, 0.0)
        assertEquals(1.25, evidence.change5mPct!!, 0.0)
        assertEquals(0.0, row.priceChange24h, 0.0)
    }

    @Test fun explicitZerosRemainReadingsWhileNullOrAbsentFieldsRemainUnknown() {
        val body = payload()
        val attrs = body.getJSONArray("data").getJSONObject(0).getJSONObject("attributes")
        attrs.getJSONObject("transactions").getJSONObject("m5").put("buys", 0).put("sells", 0)
        attrs.getJSONObject("transactions").getJSONObject("h24").put("buys", JSONObject.NULL)
        attrs.getJSONObject("volume_usd").put("m5", 0)
        attrs.getJSONObject("price_change_percentage").put("m5", 0)
        val zero = parse(body)
        assertEquals(0, zero.buys5m8832)
        assertEquals(0, zero.sells5m8832)
        assertEquals(0.0, zero.volume5m8832, 0.0)
        assertEquals(0.0, zero.change5mPct8832, 0.0)
        assertEquals(now - 1_234L, zero.shortWindowObservedAtMs8832)
        assertEquals("null buys cannot attest to a flat 24h tape", 0L, zero.txns24hObservedAtMs8752)

        attrs.getJSONObject("transactions").getJSONObject("m5").put("buys", JSONObject.NULL).remove("sells")
        attrs.getJSONObject("volume_usd").put("m5", JSONObject.NULL)
        attrs.getJSONObject("price_change_percentage").remove("m5")
        val unknown = parse(body)
        assertEquals(-1, unknown.buys5m8832)
        assertEquals(-1, unknown.sells5m8832)
        assertTrue(unknown.volume5m8832.isNaN())
        assertTrue(unknown.change5mPct8832.isNaN())
        assertEquals(0L, unknown.shortWindowObservedAtMs8832)
    }

    @Test fun staleZeroFutureAndSecondsClocksCannotBecomeFreshMilliseconds() {
        for (at in listOf(0L, now + 1L)) {
            val row = parse(payload(), observedAt = at)
            assertEquals(0L, row.priceObservedAtMs8372)
            assertEquals(0L, row.shortWindowObservedAtMs8832)
        }
        for (at in listOf(now - 350_000L, now / 1000L)) {
            val row = parse(payload(), observedAt = at)
            assertEquals(at, row.priceObservedAtMs8372)
            val evidence = CryptoShortWindow8832.from(now, row.shortWindowObservedAtMs8832,
                row.buys5m8832, row.sells5m8832, row.volume5m8832, row.change5mPct8832)
            assertFalse(evidence.flowObserved)
            assertEquals(null, evidence.change5mPct)
        }
    }

    @Test fun malformedNumbersAndWrongNetworkRelationshipsDoNotFabricateEvidence() {
        val body = payload()
        val attrs = body.getJSONArray("data").getJSONObject(0).getJSONObject("attributes")
        attrs.getJSONObject("transactions").getJSONObject("m5").put("buys", 2.5).put("sells", "not-a-count")
        val unknown = parse(body)
        assertEquals(-1, unknown.buys5m8832)
        assertEquals(-1, unknown.sells5m8832)
        attrs.put("base_token_price_usd", "NaN")
        assertTrue(DynamicAltTokenRegistry.parseGeckoPools8870(body.toString(), "eth", true, now - 100L, now).isEmpty())
        assertTrue(DynamicAltTokenRegistry.parseGeckoPools8870(payload().toString(), "polygon_pos", true, now - 100L, now).isEmpty())
    }
}
