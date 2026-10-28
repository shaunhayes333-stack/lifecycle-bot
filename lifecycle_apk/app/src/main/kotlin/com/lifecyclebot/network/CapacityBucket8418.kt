package com.lifecyclebot.network

/**
 * V5.0.8418 §DAISY_CHAIN — one provider's own budget: a requests-per-minute token bucket, a concurrency limit, and
 * the provider's measured health and latency.
 *
 * Owner: "providers are meant to daisy chain to build a bigger power source not block each other." Each provider (or
 * each shared rate-limit host) gets exactly one of these. A pool's capacity is the SUM of its buckets, and a refusal
 * (429 / 5xx / timeout) only ever touches the bucket that earned it — nothing here can slow a sibling down.
 *
 * Every method takes the clock as an argument, so the arithmetic is testable without waiting.
 */
class CapacityBucket8418(val key: String, val rpm: Int, val maxInFlight: Int) {

    enum class Outcome8418 { OK, EMPTY, RATE, SERVER, TIMEOUT, ERROR, OWN_REFUSAL }

    private val ratePerMs: Double = rpm.coerceAtLeast(1) / 60_000.0

    /** Ten seconds of budget, at least one request: a provider can answer a burst but not exceed its minute. */
    val burst: Double = maxOf(1.0, rpm.coerceAtLeast(1) / 6.0)

    private var tokens: Double = burst
    private var lastRefillMs: Long = Long.MIN_VALUE
    private var inFlight: Int = 0
    private var limitedUntilMs: Long = 0L
    private val starts = java.util.ArrayDeque<Long>()

    /** Exponentially weighted success share; 0.5 until the provider has been measured (untried is not failed). */
    var okShare: Double = 0.5
        private set
    var latencyMs: Double = 0.0
        private set
    var samples: Long = 0L
        private set
    var lastOutcome: Outcome8418? = null
        private set

    private fun refill(nowMs: Long) {
        if (lastRefillMs == Long.MIN_VALUE) { lastRefillMs = nowMs; return }
        val dt = nowMs - lastRefillMs
        if (dt > 0L) {
            tokens = minOf(burst, tokens + dt * ratePerMs)
            lastRefillMs = nowMs
        }
    }

    private fun trim(nowMs: Long) {
        while (true) {
            val head = starts.peekFirst() ?: break
            if (nowMs - head >= 60_000L) starts.pollFirst() else break
        }
    }

    /** Takes one request slot (one token and one in-flight place) or answers false without waiting. */
    @Synchronized fun tryAcquire(nowMs: Long): Boolean {
        refill(nowMs)
        if (nowMs < limitedUntilMs) return false
        if (inFlight >= maxInFlight) return false
        if (tokens < 1.0) return false
        tokens -= 1.0
        inFlight++
        starts.addLast(nowMs)
        trim(nowMs)
        return true
    }

    /** True when a request could start now, without taking it. */
    @Synchronized fun hasCapacity(nowMs: Long): Boolean {
        refill(nowMs)
        return nowMs >= limitedUntilMs && inFlight < maxInFlight && tokens >= 1.0
    }

    /**
     * Gives the in-flight place back and records what the provider did. A refusal by this app's own backoff never
     * reached the provider, so its token is refunded and it is not a health sample.
     */
    @Synchronized fun release(nowMs: Long, outcome: Outcome8418, latencyMs: Long, retryAfterMs: Long? = null) {
        refill(nowMs)
        if (inFlight > 0) inFlight--
        lastOutcome = outcome
        if (outcome == Outcome8418.OWN_REFUSAL) {
            tokens = minOf(burst, tokens + 1.0)
            if (!starts.isEmpty()) starts.pollLast()
            return
        }
        samples++
        val hit = if (outcome == Outcome8418.OK) 1.0 else 0.0
        okShare = 0.8 * okShare + 0.2 * hit
        if (outcome == Outcome8418.OK && latencyMs >= 0L) {
            this.latencyMs = if (this.latencyMs <= 0.0) latencyMs.toDouble() else 0.7 * this.latencyMs + 0.3 * latencyMs
        }
        val pauseMs = when (outcome) {
            Outcome8418.RATE -> retryAfterMs ?: 60_000L
            Outcome8418.SERVER -> retryAfterMs ?: 10_000L
            Outcome8418.TIMEOUT -> 5_000L
            else -> 0L
        }
        if (pauseMs > 0L) {
            if (outcome == Outcome8418.RATE) tokens = 0.0
            limitedUntilMs = maxOf(limitedUntilMs, nowMs + pauseMs)
        }
    }

    /**
     * Routing weight: health x spare capacity / latency. Zero when the bucket cannot start a request now. Untried
     * providers (okShare 0.5, no latency) rank between a proven one and a failing one.
     */
    @Synchronized fun weight(nowMs: Long): Double {
        refill(nowMs)
        if (nowMs < limitedUntilMs || inFlight >= maxInFlight || tokens < 1.0) return 0.0
        val spareTokens = (tokens / burst).coerceIn(0.0, 1.0)
        val spareSlots = (maxInFlight - inFlight).toDouble() / maxInFlight.coerceAtLeast(1)
        return (0.05 + okShare) * (0.5 + 0.5 * spareTokens) * spareSlots / (1.0 + latencyMs / 3_000.0)
    }

    /** Requests started in the last minute (refused-by-us ones excluded). */
    @Synchronized fun usedLastMinute(nowMs: Long): Int { trim(nowMs); return starts.size }

    @Synchronized fun inFlightNow(): Int = inFlight

    @Synchronized private fun limitedForMs(nowMs: Long): Long = (limitedUntilMs - nowMs).coerceAtLeast(0L)

    /** Requests this bucket can still start in the current minute: its rpm minus what it used, zero while limited. */
    @Synchronized fun availableThisMinute(nowMs: Long): Int {
        trim(nowMs)
        if (nowMs < limitedUntilMs) return 0
        return (rpm - starts.size).coerceAtLeast(0)
    }

    /** One short status cell: `name avail/rpm used=N inflight=N ok=NN% lat=NNNms` (+ `lim=Ns` while paused). */
    @Synchronized fun cell(nowMs: Long): String {
        val lim = limitedForMs(nowMs)
        val ok = if (samples == 0L) "?" else "${Math.round(okShare * 100.0)}%"
        val lat = if (latencyMs <= 0.0) "?" else "${Math.round(latencyMs)}ms"
        return "$key ${availableThisMinute(nowMs)}/$rpm used=${usedLastMinute(nowMs)} inflight=$inFlight ok=$ok lat=$lat" +
            (if (lim > 0L) " lim=${lim / 1000}s" else "")
    }
}
