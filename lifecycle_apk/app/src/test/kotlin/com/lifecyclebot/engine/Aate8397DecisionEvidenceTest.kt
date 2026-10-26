package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.HeliusCreditEconomy7881 as Economy
import com.lifecyclebot.network.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

/** Producer reservation -> actual HTTP/socket adapter; no real provider or wallet is contacted. */
class Aate8397DecisionEvidenceTest {
    private val saved = LinkedHashMap<java.lang.reflect.Field, Any?>()
    private fun set(name: String, value: Any?) {
        val field = Economy.javaClass.getDeclaredField(name).apply { isAccessible = true }
        if (field !in saved) saved[field] = field.get(null)
        field.set(null, value)
    }
    private fun read(name: String): Double = Economy.javaClass.getDeclaredField(name).apply { isAccessible = true }.getDouble(null)
    private var now = 0L
    @Before fun setup() {
        now = System.currentTimeMillis()
        set("day", now / 86_400_000L); set("loaded", true)
        set("total", Economy.ordinaryLimit8397(300_000.0, (now % 86_400_000L) / 86_400_000.0) + 100.0)
        set("decisionReserved8397", 0.0); set("exhaustedUntilMs8094", 0L); set("lastPersistMs", now)
    }
    @After fun restore() { saved.forEach { (f, v) -> f.set(null, v) } }

    private fun chain(request: Request, sent: () -> Unit): Interceptor.Chain = mock(Interceptor.Chain::class.java).also { chain ->
        `when`(chain.request()).thenReturn(request)
        `when`(chain.proceed(any())).thenAnswer {
            sent()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("[]".toResponseBody("application/json".toMediaType())).build()
        }
    }

    @Test fun candleProducerReserveReachesWireWhenOrdinaryPaceIsClosed() {
        assertTrue(Economy.overBudget8066(now))
        assertNull(Economy.reserve8386(Economy.Consumer.SWAP_CANDLES, 200.0, false, now))
        val reservation = Economy.reserve8386(Economy.Consumer.SWAP_CANDLES, 200.0, true, now)!!
        val start = Economy.creditsToday7888(now)
        val request = Request.Builder().url("https://api.helius.xyz/v0/addresses/mint/transactions?api-key=test")
            .tag(Economy.Reservation8386::class.java, reservation).build()
        var sent = 0
        repeat(2) { HeliusCreditMeterInterceptor7881.intercept(chain(request) { sent++ }).use { assertEquals(200, it.code) } }
        HeliusCreditMeterInterceptor7881.intercept(chain(request) { sent++ }).use { assertEquals(429, it.code) }
        assertEquals(2, sent)
        assertEquals(start + 200.0, Economy.creditsToday7888(now), 1e-9)
        Economy.finish8386(reservation)
        assertEquals(200.0, read("decisionReserved8397"), 1e-9) // sent work is charged; no local-denial charge
    }

    @Test fun dailyReserveAndPhysicalBudgetRemainBoundedAndUnsentCreditsReturn() {
        val leases = (1..75).map { Economy.reserveDecision8397(Economy.Consumer.SWAP_CANDLES, 200.0, now)!! }
        assertNull(Economy.reserveDecision8397(Economy.Consumer.SWAP_CANDLES, 1.0, now))
        leases.forEach(Economy::finish8386)
        assertEquals(0.0, read("decisionReserved8397"), 1e-9)
        set("total", 299_950.0)
        assertNull(Economy.reserveDecision8397(Economy.Consumer.SWAP_CANDLES, 100.0, now))
        set("total", 100.0); set("exhaustedUntilMs8094", now + 60_000)
        assertNull(Economy.reserveDecision8397(Economy.Consumer.SWAP_CANDLES, 100.0, now))
    }

    @Test fun prioritySurvivesOrdinaryHeadroomDisappearingBetweenQueueAndWire() {
        set("total", 0.0)
        val reservation = Economy.reserve8386(Economy.Consumer.SWAP_CANDLES, 200.0, true, now)!!
        assertFalse(reservation.decision8397)
        set("total", Economy.ordinaryLimit8397(300_000.0, (now % 86_400_000L) / 86_400_000.0) + 100.0)
        val request = Request.Builder().url("https://api.helius.xyz/v0/addresses/mint/transactions?api-key=test")
            .tag(Economy.Reservation8386::class.java, reservation).build()
        var sent = 0
        HeliusCreditMeterInterceptor7881.intercept(chain(request) { sent++ }).use { assertEquals(200, it.code) }
        Economy.finish8386(reservation)
        assertEquals(1, sent); assertEquals(100.0, read("decisionReserved8397"), 1e-9)
    }

    @Test fun candidateFallbackActuallySubscribesButDoesNotClaimDeliveryBeforeAnEvent() {
        val policy = HeliusCurveTape8354.decide8354(false, 0, false, false, false, true)
        assertEquals(HeliusCurveTape8354.Source8354.HELIUS_PER_MINT, policy.first)
        val socket = HeliusWebSocket("test", { _, _, _, _, _, _ -> }, { _, _, _, _ -> }, {})
        val wire = mock(WebSocket::class.java)
        val messages = ArrayList<String>()
        `when`(wire.send(anyString())).thenAnswer { messages += it.getArgument<String>(0); true }
        socket.javaClass.getDeclaredField("ws").apply { isAccessible = true }.set(socket, wire)
        socket.setPinnedMintsProvider7807 { emptySet() }
        val mint = "So11111111111111111111111111111111111111112"
        try {
            assertTrue(Economy.overBudget8066(now))
            socket.subscribeToken(mint) // optional discovery has no priority lease
            assertTrue(messages.isEmpty())
            assertTrue(socket.subscribeTokenState8386(mint) is HeliusCurveTape8354.TapeState8386.Queued)
            assertEquals(1, messages.size)
            val request = JSONObject(messages.single())
            assertEquals("logsSubscribe", request.getString("method"))
            val parse = socket.javaClass.getDeclaredMethod("parseMessage", String::class.java).apply { isAccessible = true }
            parse.invoke(socket, """{"jsonrpc":"2.0","id":${request.getInt("id")},"result":77}""")
            assertTrue(socket.subscribeTokenState8386(mint) is HeliusCurveTape8354.TapeState8386.Acknowledged)
            assertFalse(socket.subscribeTokenState8386(mint) is HeliusCurveTape8354.TapeState8386.Delivering)
        } finally { socket.disconnect() }
    }

    @Test fun tapeLeasesBoundConcurrencyDurationAndActualByteSpend() {
        val leases = DecisionTapeLeases8397(capacity = 2, durationMs = 1000)
        assertTrue(leases.admit("a", now)); assertTrue(leases.admit("b", now))
        assertTrue(leases.admit("a", now + 1)); assertFalse(leases.admit("c", now + 1))
        assertTrue(leases.noteBytes("a", 100_000, now + 2))
        assertEquals(setOf("a", "b"), leases.expire(now + 1000))
        assertEquals(0, leases.size())
        assertEquals(2.0, read("decisionReserved8397"), 1e-9)
    }
}
