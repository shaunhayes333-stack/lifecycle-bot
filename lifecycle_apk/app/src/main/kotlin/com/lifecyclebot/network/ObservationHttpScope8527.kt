package com.lifecyclebot.network

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * Scoped ONLY by read-only mark observation workers. It is not a trade gate.
 * Owns actual Calls until the task finishes reading their bodies, so cancelling
 * a Future cannot leave its sockets running behind the next observation pass.
 */
internal object ObservationHttpScope8527 {
    private val local = ThreadLocal<Scope?>()

    class Scope(
        val deadlineNanos: Long,
        val held: Boolean = false,
        private val clockNanos: () -> Long = System::nanoTime,
        private val parent: Scope? = null,
    ) {
        private val lock = Any()
        private val calls = LinkedHashSet<Call>()
        private val children = LinkedHashSet<Scope>()
        @Volatile private var cancelled = false
        @Volatile var localFailureObserved: Boolean = false
            private set

        fun child(deadline: Long = deadlineNanos): Scope {
            val child = Scope(minOf(deadlineNanos, deadline), held, clockNanos, this)
            val stop = synchronized(lock) {
                if (cancelled) true else { children.add(child); false }
            }
            if (stop) child.cancel()
            return child
        }

        fun remainingMs(): Long {
            if (cancelled || Thread.currentThread().isInterrupted) {
                markLocalFailure()
                throw InterruptedIOException("Observation read cancelled")
            }
            val left = (deadlineNanos - clockNanos()) / 1_000_000L
            if (left <= 0L) {
                markLocalFailure()
                throw InterruptedIOException("Observation read budget exhausted")
            }
            return left
        }

        private fun capped(originalMs: Int, leftMs: Long): Long =
            if (originalMs <= 0) leftMs else minOf(originalMs.toLong(), leftMs)

        fun prepareCall(client: OkHttpClient, request: Request): Call {
            val left = remainingMs()
            val scoped = client.newBuilder()
                .callTimeout(capped(client.callTimeoutMillis, left), TimeUnit.MILLISECONDS)
                .connectTimeout(capped(client.connectTimeoutMillis, left), TimeUnit.MILLISECONDS)
                .readTimeout(capped(client.readTimeoutMillis, left), TimeUnit.MILLISECONDS)
                .writeTimeout(capped(client.writeTimeoutMillis, left), TimeUnit.MILLISECONDS)
                .build()
            val call = scoped.newCall(request)
            val stop = synchronized(lock) {
                if (cancelled || clockNanos() >= deadlineNanos) true else { calls.add(call); false }
            }
            if (stop) {
                call.cancel()
                markLocalFailure()
                throw InterruptedIOException("Observation read cancelled before transport")
            }
            return call
        }

        /** Publish only while this task still owns its read deadline; cancellation cannot race the write. */
        fun <T> publishIfCurrent(block: () -> T): T = synchronized(lock) {
            remainingMs()
            block()
        }

        /** Preserve normal body parsing while attributing an owning deadline after headers correctly. */
        fun watchResponse(response: Response): Response {
            val body = response.body ?: return response
            val watched = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long = try {
                    super.read(sink, byteCount)
                } catch (error: java.io.IOException) {
                    localFailure(error)
                    throw error
                }
            }.buffer().asResponseBody(body.contentType(), body.contentLength())
            return response.newBuilder().body(watched).build()
        }

        /** Only this owning scope's cancellation/expiry/interrupt is a local miss. */
        fun localFailure(error: Throwable): Boolean {
            val own = cancelled || Thread.currentThread().isInterrupted ||
                error is InterruptedException ||
                (error is InterruptedIOException && (
                    clockNanos() >= deadlineNanos - 2_000_000L ||
                    error.message.orEmpty().contains("Observation read")))
            if (own) markLocalFailure()
            return own
        }

        /** A local rate/credit/circuit refusal is missing evidence, never provider absence. */
        fun noteLocalRefusal() { markLocalFailure() }

        private fun markLocalFailure() {
            localFailureObserved = true
            parent?.markLocalFailure()
        }

        fun cancel() {
            val snapshot = synchronized(lock) {
                if (cancelled) return
                cancelled = true
                val copy = calls.toList() to children.toList()
                calls.clear(); children.clear()
                copy
            }
            snapshot.second.forEach { it.cancel() }
            snapshot.first.forEach { try { it.cancel() } catch (_: Throwable) {} }
        }
    }

    fun current(): Scope? = local.get()

    fun <T> publishIfCurrent(block: () -> T): T {
        val scope = current()
        return if (scope == null) block() else scope.publishIfCurrent(block)
    }

    fun <T> withScope(scope: Scope, block: () -> T): T {
        val previous = local.get()
        local.set(scope)
        try { return block() } finally {
            if (previous == null) local.remove() else local.set(previous)
        }
    }
}
