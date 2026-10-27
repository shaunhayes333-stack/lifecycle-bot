package com.lifecyclebot.network

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

/** One coalesced FIFO per provider. Local waits retain ownership and do not spend wire attempts. */
internal class OhlcvRequestQueue8401<T>(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val capacity: Int = 256,
) {
    enum class State { WAIT_BUDGET, ANSWERED_EMPTY, ANSWERED_BARS, PROVIDER_FAILURE, DEADLINE, CAPACITY }
    data class Answer<T>(val state: State, val rows: List<T> = emptyList())
    private class Request<T>(val key: String, val deadline: Long, val attempts: Int,
        val fetch: () -> Answer<T>, val waitMs: () -> Long) {
        val result = CompletableDeferred<Answer<T>>()
        var asked = 0
    }
    private val requests = ConcurrentHashMap<String, Request<T>>()
    private val queue = Channel<Request<T>>(capacity)
    private val admission = Any()

    init { scope.launch {
        for (r in queue) {
            try {
                if (r.result.isCompleted) continue
                if (nowMs() >= r.deadline) { r.result.complete(Answer(State.DEADLINE)); continue }
                val wait = r.waitMs().coerceIn(0L, 6_000L)
                if (wait > 0) pause(minOf(wait, (r.deadline - nowMs()).coerceAtLeast(0L)))
                if (nowMs() >= r.deadline || r.result.isCompleted) { r.result.complete(Answer(State.DEADLINE)); continue }
                val answer = r.fetch()
                when (answer.state) {
                    State.ANSWERED_BARS, State.ANSWERED_EMPTY -> r.result.complete(answer)
                    State.WAIT_BUDGET, State.PROVIDER_FAILURE -> {
                        if (answer.state == State.PROVIDER_FAILURE) r.asked++
                        if (r.asked >= r.attempts) r.result.complete(answer)
                        else {
                            // One turn per key, including capacity waits, keeps other queued keys moving.
                            if (answer.state == State.WAIT_BUDGET) pause(100L)
                            if (!queue.trySend(r).isSuccess) r.result.complete(Answer(State.CAPACITY))
                        }
                    }
                    else -> r.result.complete(answer)
                }
            } catch (cancelled: CancellationException) { r.result.cancel(cancelled); throw cancelled }
            catch (_: Exception) { r.result.complete(Answer(State.PROVIDER_FAILURE)) }
            finally { if (r.result.isCompleted) requests.remove(r.key, r) }
        }
    } }

    suspend fun request(key: String, deadlineMs: Long, attempts: Int,
        waitMs: () -> Long, fetch: () -> Answer<T>): Answer<T> {
        val r = synchronized(admission) {
            requests[key] ?: run {
                if (requests.size >= capacity) return Answer(State.CAPACITY)
                Request(key, deadlineMs, attempts.coerceAtLeast(1), fetch, waitMs).also {
                    requests[key] = it
                    if (!queue.trySend(it).isSuccess) { requests.remove(key, it); it.result.complete(Answer(State.CAPACITY)) }
                }
            }
        }
        val remaining = (r.deadline - nowMs()).coerceAtLeast(1L)
        val answer = withTimeoutOrNull(remaining) { r.result.await() }
        if (answer != null) return answer
        r.result.complete(Answer(State.DEADLINE))
        requests.remove(key, r)
        return Answer(State.DEADLINE)
    }
}
