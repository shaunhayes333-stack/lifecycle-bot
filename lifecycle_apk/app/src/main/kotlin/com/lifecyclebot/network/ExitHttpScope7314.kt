package com.lifecyclebot.network

/**
 * V5.0.7314 §AN EXIT IS NEVER REFUSED BY OUR OWN BACKOFF.
 *
 * TTP (5.0.7311 live): the stop could not leave because the app refused its
 * own sell requests before they reached the wire —
 *   • "PumpPortal HTTP 503: {}" was HealthAwareHttp's synthetic lockout reply:
 *     the PumpPortal SELL shared the host key "pumpfun" with pump.fun price
 *     lookups (sr=8%), so failing price reads locked trading out;
 *   • "Jupiter GET skipped: jupiter_quote in backoff lockout" — entry quoting
 *     kept the shared key locked, and the sell quote refused itself;
 *   • HostCircuitInterceptor answered locked providers with a synthetic 599.
 *
 * Everything run inside [run] (Executor.liveSell) carries this scope on its
 * thread. HTTP helpers stamp [HEADER] on requests made in scope, and both
 * refusal layers (HealthAwareHttp lockout, HostCircuitInterceptor lockout and
 * cool-down) let those requests through. Exits still record their outcome, so
 * the health model stays honest; they simply are never blocked by it.
 */
object ExitHttpScope7314 {
    const val HEADER = "X-AATE-Exit"
    private val depth = ThreadLocal.withInitial { 0 }

    fun active(): Boolean = (depth.get() ?: 0) > 0

    inline fun <T> run(block: () -> T): T {
        enter()
        try { return block() } finally { leave() }
    }

    fun enter() { depth.set((depth.get() ?: 0) + 1) }
    fun leave() { depth.set(((depth.get() ?: 1) - 1).coerceAtLeast(0)) }

    fun noteBypass(layer: String) {
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EXIT_BYPASSED_LOCAL_LOCKOUT_7314_$layer") } catch (_: Throwable) {}
    }
}
