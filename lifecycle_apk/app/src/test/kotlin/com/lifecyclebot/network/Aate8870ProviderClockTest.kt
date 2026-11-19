package com.lifecyclebot.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate8870ProviderClockTest {
    private val now = 1_800_000_000_000L
    private fun record(seconds: Long?) = JSONObject().put("price", 1.25).put("confidence", 0.99).also {
        if (seconds != null) it.put("timestamp", seconds)
    }

    @Test fun providerSecondsRemainThePriceClockAfterFreshHttpResponse() {
        val mark = KeylessPriceSources6996.parseLlamaObservation8870(record((now - 12_000L) / 1_000L))!!
        assertEquals(1.25, mark.priceUsd, 0.0)
        assertEquals(now - 12_000L, mark.observedAtMs)
        assertTrue(KeylessPriceSources6996.freshObservation8870(mark, now))
        assertEquals(mark.observedAtMs, ParallelMarkFanout7088.mergedClock8870(now, mark.observedAtMs))
        assertFalse(KeylessPriceSources6996.freshObservation8870(mark, now + 60_000L))
    }

    @Test fun staleUnknownFutureAndWrongUnitsCannotBecomeFreshMarks() {
        val stale = KeylessPriceSources6996.parseLlamaObservation8870(record((now - 300_000L) / 1_000L))!!
        assertFalse(KeylessPriceSources6996.freshObservation8870(stale, now))
        for (stamp in listOf<Long?>(null, 0L, -1L, now / 1_000L + 1L, now, Long.MAX_VALUE)) {
            val mark = KeylessPriceSources6996.parseLlamaObservation8870(record(stamp))!!
            assertFalse("timestamp=$stamp", KeylessPriceSources6996.freshObservation8870(mark, now))
        }
        assertEquals(0L, ParallelMarkFanout7088.mergedClock8870(now, 0L))
        assertEquals(now, ParallelMarkFanout7088.mergedClock8870(now, null))
    }

    @Test fun priceAndConfidenceValidationRemainInForce() {
        assertNull(KeylessPriceSources6996.parseLlamaObservation8870(record(now / 1_000L).put("price", 0.0)))
        assertNull(KeylessPriceSources6996.parseLlamaObservation8870(record(now / 1_000L).put("price", "garbage")))
        assertNull(KeylessPriceSources6996.parseLlamaObservation8870(record(now / 1_000L).put("confidence", 0.69)))
        val absentConfidence = record(now / 1_000L).also { it.remove("confidence") }
        assertNotNull(KeylessPriceSources6996.parseLlamaObservation8870(absentConfidence))
    }

    @Test fun dexNullDailyCountsAreUnknownButMeasuredZeroIsObserved() {
        val parser = DexscreenerApi::class.java.getDeclaredMethod("parsePair", JSONObject::class.java)
            .apply { isAccessible = true }
        val api = DexscreenerApi()
        fun parse(counts: JSONObject): PairInfo = parser.invoke(api,
            JSONObject().put("priceUsd", "0.001").put("txns", JSONObject().put("h24", counts))) as PairInfo
        val zero = parse(JSONObject().put("buys", 0).put("sells", 0))
        assertTrue(zero.txnsH24Observed)
        assertEquals(0, zero.candle.buys24h)
        assertFalse(parse(JSONObject().put("buys", JSONObject.NULL).put("sells", JSONObject.NULL)).txnsH24Observed)
        assertFalse(parse(JSONObject().put("buys", 3).put("sells", JSONObject.NULL)).txnsH24Observed)
        assertFalse(parse(JSONObject()).txnsH24Observed)
    }
}
