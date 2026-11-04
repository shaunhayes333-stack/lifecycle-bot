package com.lifecyclebot.network

import com.lifecyclebot.engine.HealthAwareHttp
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class Aate8527ObservationHttpScopeTest {
    private val request = Request.Builder().url("http://127.0.0.1/test").build()

    @Test fun scopeCapsWholeCallWithoutWideningExistingTimeouts() {
        var now = 0L
        val scope = ObservationHttpScope8527.Scope(4_000_000_000L, clockNanos = { now })
        val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        val first = scope.prepareCall(client, request)
        assertEquals(4_000_000_000L, first.timeout().timeoutNanos())
        now = 3_000_000_000L
        val second = scope.prepareCall(client, request)
        assertEquals(1_000_000_000L, second.timeout().timeoutNanos())
        val shortClient = client.newBuilder().callTimeout(100, TimeUnit.MILLISECONDS).build()
        assertEquals(100_000_000L, scope.prepareCall(shortClient, request).timeout().timeoutNanos())
        assertEquals(12_000, client.callTimeoutMillis)
        scope.cancel()
        assertTrue(first.isCanceled())
        assertTrue(second.isCanceled())
    }

    @Test fun cancellationAndExpiryRefuseBeforeTransportAndDoNotPoisonOrdinaryErrors() {
        var now = 0L
        val scope = ObservationHttpScope8527.Scope(1_000_000_000L, clockNanos = { now })
        assertFalse(scope.localFailure(IOException("connection reset")))
        now = 1_000_000_000L
        try { scope.prepareCall(OkHttpClient(), request); fail("expired read") } catch (_: InterruptedIOException) {}
        assertTrue(scope.localFailureObserved)
        val cancelled = ObservationHttpScope8527.Scope(9_000_000_000L, clockNanos = { 0L })
        cancelled.cancel()
        try { cancelled.prepareCall(OkHttpClient(), request); fail("cancelled read") } catch (_: InterruptedIOException) {}
    }

    @Test fun childrenCannotWidenParentDeadlineAndThreadContextIsRestored() {
        val parent = ObservationHttpScope8527.Scope(2_000_000_000L, held = true, clockNanos = { 0L })
        val child = parent.child(20_000_000_000L)
        assertEquals(2_000_000_000L, child.deadlineNanos)
        assertTrue(child.held)
        assertNull(ObservationHttpScope8527.current())
        ObservationHttpScope8527.withScope(parent) {
            assertSame(parent, ObservationHttpScope8527.current())
            ObservationHttpScope8527.withScope(child) { assertSame(child, ObservationHttpScope8527.current()) }
            assertSame(parent, ObservationHttpScope8527.current())
        }
        assertNull(ObservationHttpScope8527.current())
        val call = child.prepareCall(OkHttpClient(), request)
        parent.cancel()
        assertTrue(call.isCanceled())
    }

    @Test fun unscopedExecutionKeepsItsOwnCallBudget() {
        var observed = 0L
        val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).addInterceptor { chain ->
            observed = chain.call().timeout().timeoutNanos()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("{}".toResponseBody()).build()
        }.build()
        HealthAwareHttp.execute(client, request, "unscoped_test_8527").use { assertEquals(200, it.code) }
        assertEquals(12_000_000_000L, observed)
    }

    @Test fun cancellingAfterHeadersClosesTheRealCallStillReadingItsBody() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val releaseServer = CountDownLatch(1)
        val readingBody = CountDownLatch(1)
        val clientFinished = CountDownLatch(1)
        val callRef = AtomicReference<Call>()
        val errorRef = AtomicReference<Throwable>()
        val serverThread = Thread {
            try {
                server.accept().use { socket ->
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.getOutputStream().flush()
                    releaseServer.await(5, TimeUnit.SECONDS)
                }
            } catch (_: Throwable) {}
        }.apply { isDaemon = true; start() }
        val scope = ObservationHttpScope8527.Scope(System.nanoTime() + 10_000_000_000L)
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { callRef.set(call) }
        }).build()
        val clientThread = Thread {
            try {
                ObservationHttpScope8527.withScope(scope) {
                    val req = Request.Builder().url("http://127.0.0.1:" + server.localPort + "/body").build()
                    HealthAwareHttp.execute(client, req, "scoped_body_test_8527").use { response ->
                        readingBody.countDown()
                        response.body!!.string()
                    }
                }
            } catch (error: Throwable) { errorRef.set(error) }
            finally { clientFinished.countDown() }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue("headers reached the body reader", readingBody.await(3, TimeUnit.SECONDS))
            scope.cancel()
            assertTrue("actual socket read cancelled", clientFinished.await(3, TimeUnit.SECONDS))
            assertTrue(callRef.get().isCanceled())
            assertTrue(errorRef.get() is IOException)
            assertTrue("body timeout/cancel is owned locally", scope.localFailureObserved)
        } finally {
            scope.cancel(); releaseServer.countDown(); server.close()
            clientThread.join(500L); serverThread.join(500L)
        }
    }
    @Test fun locallyRefusedReadDoesNotBecomeAnEmptyProviderAnswer() {
        val scope = ObservationHttpScope8527.Scope(System.nanoTime() + 4_000_000_000L)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(599)
                .message("HELIUS_RPS_GATE_8191").header(HostCircuitInterceptor.SYNTHETIC_HEADER_6969, "1")
                .body("".toResponseBody()).build()
        }.build()
        try {
            ObservationHttpScope8527.withScope(scope) {
                HealthAwareHttp.execute(client, request, "local_refusal_8527").use { assertEquals(599, it.code) }
            }
            assertTrue(scope.localFailureObserved)
        } finally { scope.cancel() }
    }

    @Test fun anExpiredOrCancelledReadCannotPublishEvenIfItsFetchReturned() {
        var now = 0L
        val scope = ObservationHttpScope8527.Scope(1_000_000_000L, clockNanos = { now })
        val observed = mutableMapOf<String, Double>()
        ObservationHttpScope8527.withScope(scope) {
            ObservationHttpScope8527.publishIfCurrent { observed["landed"] = 2.0 }
            val lateReply = 9.0 // an uncancellable fetch has now returned after its owning deadline
            now = 1_000_000_000L
            try {
                ObservationHttpScope8527.publishIfCurrent { observed["late"] = lateReply }
                fail("expired publication")
            } catch (_: InterruptedIOException) {}
        }
        assertEquals(mapOf("landed" to 2.0), observed)
        val cancelled = ObservationHttpScope8527.Scope(5_000_000_000L, clockNanos = { 0L })
        cancelled.cancel()
        try { cancelled.publishIfCurrent { observed["cancelled"] = 3.0 }; fail("cancelled publication") }
        catch (_: InterruptedIOException) {}
        assertEquals(mapOf("landed" to 2.0), observed)
    }

}
