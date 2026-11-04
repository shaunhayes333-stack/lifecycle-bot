package com.lifecyclebot.network

import com.lifecyclebot.data.Candle
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8527OhlcvAnswerTest {
    @Test fun anUntypedEmptyReturnIsNotConfirmedSourceAbsence() {
        assertEquals(OhlcvRequestQueue8401.State.PROVIDER_FAILURE, SolanaOhlcvFeed6916.typedAnswer8527 { emptyList() }.state)
        assertEquals(OhlcvRequestQueue8401.State.PROVIDER_FAILURE, SolanaOhlcvFeed6916.fetchAnswer8527("", "1m", 180).state)
    }

    @Test fun actualRowsAreReturnedWithoutChangingTheirOriginalClock() {
        val row = Candle(ts = 1_800_000_000_000L, priceUsd = 1.0, marketCap = 0.0, volumeH1 = 0.0, volume24h = 0.0)
        val answer = SolanaOhlcvFeed6916.typedAnswer8527 { listOf(row) }
        assertEquals(OhlcvRequestQueue8401.State.ANSWERED_BARS, answer.state)
        assertSame(row, answer.rows.single())
        assertEquals(OhlcvRequestQueue8401.State.PROVIDER_FAILURE, SolanaOhlcvFeed6916.typedAnswer8527 { emptyList() }.state)
    }

    @Test fun failedReadDoesNotLeaveThreadLocalOutcomeForTheNextCaller() {
        try {
            SolanaOhlcvFeed6916.typedAnswer8527 { throw IllegalStateException("synthetic failure") }
            fail("must propagate the failure to the owning retry policy")
        } catch (_: IllegalStateException) {}
        assertEquals(OhlcvRequestQueue8401.State.PROVIDER_FAILURE, SolanaOhlcvFeed6916.typedAnswer8527 { emptyList() }.state)
    }

    @Test fun typedOwnerReceivesCancellationAndCleanupStillRuns() {
        try {
            SolanaOhlcvFeed6916.typedAnswer8527 { throw kotlinx.coroutines.CancellationException("cancel") }
            fail("cancellation must propagate to the owning queue/thread")
        } catch (_: kotlinx.coroutines.CancellationException) {}
        assertEquals(OhlcvRequestQueue8401.State.PROVIDER_FAILURE, SolanaOhlcvFeed6916.typedAnswer8527 { emptyList() }.state)
    }

    @Test fun actualPoolPayloadsDistinguishAbsenceMalformedAndValidData() {
        val missing = SolanaOhlcvFeed6916.poolResponse8527(org.json.JSONObject("""{"meta":{}}"""))
        assertTrue(missing.askedProvider); assertTrue(missing.failed8078); assertNull(missing.pool)
        val malformed = SolanaOhlcvFeed6916.poolResponse8527(org.json.JSONObject("""{"data":[{"attributes":{}}]}"""))
        assertTrue(malformed.failed8078); assertNull(malformed.pool)
        val wrongType = SolanaOhlcvFeed6916.poolResponse8527(org.json.JSONObject("""{"data":{}}"""))
        assertTrue(wrongType.failed8078)
        val empty = SolanaOhlcvFeed6916.poolResponse8527(org.json.JSONObject("""{"data":[]}"""))
        assertTrue(empty.askedProvider); assertFalse(empty.failed8078); assertNull(empty.pool)
        val valid = SolanaOhlcvFeed6916.poolResponse8527(org.json.JSONObject(
            """{"data":[{"attributes":{"address":"POOL_A","reserve_in_usd":"2"}},{"attributes":{"address":"POOL_B","reserve_in_usd":"9"}}]}"""))
        assertFalse(valid.failed8078); assertEquals("POOL_B", valid.pool)
    }

    @Test fun onlyConfirmedEmptyBranchesSetTheEmptyState() {
        val source = File("src/main/kotlin/com/lifecyclebot/network/SolanaOhlcvFeed6916.kt").readText()
        assertTrue(source.contains("if (arr.length() == 0) return PoolLookup6982(null, askedProvider = true)"))
        assertTrue(source.contains("failed8078 = resolved == null"))
        assertTrue(source.contains("if (list.length() == 0) OhlcvRequestQueue8401.State.ANSWERED_EMPTY"))
        assertTrue(source.contains("finally { requestState8401.remove() }"))
    }
}
