package com.lifecyclebot.network

import org.junit.Assert.*
import org.junit.Test

class Aate8870FanoutClockTest {
    private val now = 1_800_000_000_000L
    private val fan = ParallelMarkFanout7088

    @Test fun excludedDatedOutlierDoesNotAgeAnIndependentFreshCluster() {
        for (outlierAt in listOf(now - 55_000L, now - 600_000L, 0L, now + 1L)) {
            val mark = fan.merge7088("fanout-outlier8870",
                listOf("DEXSCREENER" to 10.0, "JUPITER" to 10.1, "DEFILLAMA" to 20.0),
                now, mapOf("DEFILLAMA" to outlierAt), now)
            assertTrue(mark.corroborated)
            assertEquals(10.05, mark.priceUsd, 1e-12)
            assertEquals(2, mark.agreeingCount)
            assertEquals(3, mark.sourceCount)
            assertEquals(now, mark.observedAtMs8521)
            assertTrue("rejected source remains visible in diagnostics", mark.sources.contains("DEFILLAMA"))
            assertEquals(100.0, mark.spreadPct, 1e-12)
        }
    }

    @Test fun aDatedSourceWhichContributesRetainsItsOriginalClockAndClusterPolicy() {
        val at = now - 600_000L
        val mark = fan.merge7088("fanout-contributor8870",
            listOf("DEXSCREENER" to 10.0, "JUPITER" to 10.1, "DEFILLAMA" to 10.15),
            now, mapOf("DEFILLAMA" to at), now)
        assertEquals(3, mark.agreeingCount)
        assertEquals(10.1, mark.priceUsd, 1e-12)
        assertEquals("the consumer owns age eligibility; the merge does not renew this price", at, mark.observedAtMs8521)
        val multiple = fan.merge7088("fanout-multiple8870",
            listOf("DEXSCREENER" to 10.0, "JUPITER" to 10.1), now,
            mapOf("DEXSCREENER" to (now - 3_000L), "JUPITER" to (now - 1_000L)), now)
        assertEquals(now - 3_000L, multiple.observedAtMs8521)
    }

    @Test fun singleUnknownAndFutureDatedSourcesCannotBeRenewedByTheHttpPass() {
        fun single(at: Long) = fan.merge7088("fanout-single8870", listOf("DEFILLAMA" to 10.0),
            now, mapOf("DEFILLAMA" to at), now)
        assertEquals(now - 55_000L, single(now - 55_000L).observedAtMs8521)
        assertEquals(0L, single(0L).observedAtMs8521)
        assertEquals(0L, single(now + 1L).observedAtMs8521)
        assertFalse(single(now - 1L).corroborated)
        val duringRequest = fan.merge7088("fanout-during8870", listOf("DEFILLAMA" to 10.0),
            now - 2_000L, mapOf("DEFILLAMA" to (now - 1_000L)), now)
        assertEquals("request-start remains a conservative bound", now - 2_000L, duringRequest.observedAtMs8521)
    }

    @Test fun chainSingletonAndContestedMedianKeepTheirExistingMeaning() {
        val curve = fan.merge7088("fanout-curve8870", listOf("PUMP_CURVE_RPC" to 1.2), now, nowMs8870 = now)
        assertTrue(curve.corroborated)
        assertEquals(2, curve.agreeingCount)
        assertEquals(1, curve.sourceCount)
        assertEquals(now, curve.observedAtMs8521)
        val contested = fan.merge7088("fanout-contested8870",
            listOf("DEXSCREENER" to 10.0, "DEFILLAMA" to 20.0), now,
            mapOf("DEFILLAMA" to (now - 15_000L)), now)
        assertFalse(contested.corroborated)
        assertEquals(15.0, contested.priceUsd, 0.0)
        assertEquals(now - 15_000L, contested.observedAtMs8521)
    }

    @Test fun corroboratedReferenceExpiresByObservationAndAnOldResponseCannotReplaceANewerOne() {
        val mint = "fanout-reference8870"
        fan.noteCorroborated8128(mint, 10.0, now - 170_000L, now)
        assertEquals(10.0, fan.corroboratedPrice8128(mint, now)!!, 0.0)
        assertNull(fan.corroboratedPrice8128(mint, now + 20_000L))
        fan.noteCorroborated8128(mint, 10.0, now - 170_000L, now + 20_000L)
        assertNull("fetching the same old answer does not extend the reference", fan.corroboratedPrice8128(mint, now + 20_000L))
        fan.noteCorroborated8128(mint, 11.0, now + 15_000L, now + 20_000L)
        fan.noteCorroborated8128(mint, 10.0, now - 1_000L, now + 20_000L)
        fan.noteCorroborated8128(mint, 12.0, now + 30_000L, now + 20_000L)
        fan.noteCorroborated8128(mint, 13.0, 0L, now + 20_000L)
        assertEquals(11.0, fan.corroboratedPrice8128(mint, now + 20_000L)!!, 0.0)
    }
}
