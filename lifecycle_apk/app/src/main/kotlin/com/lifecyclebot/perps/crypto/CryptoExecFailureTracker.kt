package com.lifecyclebot.perps.crypto

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.9.495z36 — sticky failure tracker for CryptoUniverseExecutor.
 *
 * Operator-reported issue: PAXG / WBTC / TNSR / TRUMP route as
 * JUPITER_ROUTABLE (a Solana SPL mint exists) but the actual
 * `executeLiveTrade` call returns no signature ("no Jupiter liquidity"
 * or thin pool). The resolver currently keeps re-trying the same path
 * every scan, producing a stream of `[CRYPTO_TX_BUILD_FAILED]` rows in
 * the forensics log.
 *
 * Behaviour:
 *   - record a failure for a given symbol → bump count.
 *   - record a success for a given symbol → reset count to 0.
 *   - a symbol that has accumulated `THRESHOLD` failures inside the
 *     rolling `WINDOW_MS` is treated as `JUPITER_NO_LIQUIDITY`. The
 *     resolver downgrades it to PAPER_ONLY for `COOLDOWN_MS` so we
 *     stop pinging Jupiter for it.
 *   - after the cooldown elapses the counter resets and we re-attempt.
 */
object CryptoExecFailureTracker {

    private const val THRESHOLD: Int = 3                  // consecutive failures
    private const val WINDOW_MS: Long = 5L * 60_000L      // sliding window
    private const val COOLDOWN_MS: Long = 15L * 60_000L   // pause length

    private data class Entry(
        val count: AtomicInteger = AtomicInteger(0),
        @Volatile var firstFailMs: Long = 0L,
        @Volatile var cooldownUntilMs: Long = 0L,
    )

    private val map = ConcurrentHashMap<String, Entry>()
    // Symbols are display labels; two unrelated tokens can both be named USDC.
    // Base58 mint case is identity and must never be uppercased.
    private fun key(raw:String)=if(raw.startsWith("solana:"))raw else raw.uppercase()
    fun recordMintFailure9063(mint:String)=recordFailure("solana:$mint")
    fun recordMintSuccess9063(mint:String)=recordSuccess("solana:$mint")
    fun mintCooledDown9063(mint:String)=isCooledDown("solana:$mint")
    fun mintCooldownRemaining9063(mint:String)=cooldownRemainingMs("solana:$mint")

    fun recordFailure(symbol: String) {
        val sym = key(symbol)
        val now = System.currentTimeMillis()
        val e = map.computeIfAbsent(sym) { Entry() }
        if (e.firstFailMs == 0L || now - e.firstFailMs > WINDOW_MS) {
            e.count.set(1)
            e.firstFailMs = now
        } else {
            val n = e.count.incrementAndGet()
            if (n >= THRESHOLD) {
                e.cooldownUntilMs = now + COOLDOWN_MS
            }
        }
    }

    fun recordSuccess(symbol: String) {
        val sym = key(symbol)
        map[sym]?.let {
            it.count.set(0)
            it.firstFailMs = 0L
            it.cooldownUntilMs = 0L
        }
    }

    /** True when this symbol is currently in the no-liquidity cooldown. */
    fun isCooledDown(symbol: String): Boolean {
        val sym = key(symbol)
        val e = map[sym] ?: return false
        return System.currentTimeMillis() < e.cooldownUntilMs
    }

    fun cooldownRemainingMs(symbol: String): Long {
        val e = map[key(symbol)] ?: return 0L
        return (e.cooldownUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }
}
