package com.lifecyclebot.engine.sell

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7768 §A_PROVEN_DUST_BAG_IS_NOT_RE-SOLD_EVERY_TICK.
 *
 * 5.0.7767, 968 s: a recovered CRYPTO_ALT bag at -99% (0.004787 tokens, worth
 * ~1e-9 SOL) re-fired DEEP_CATASTROPHE_NET / UNIVERSAL_HARD_FLOOR every tick.
 * Each request took a lease, a sell lock, two balance RPCs and an emergency
 * slippage override, then Executor's dust branch proved "Jupiter can't route"
 * and returned ROUTE_FAILED_NO_SIGNATURE: 1,167 abandoned terminal sells,
 * 1,170 unknown-balance advisories and 644 slippage overrides, all on one bag.
 *
 * Field Manual §10: an exit is a decision with a reason, not a loop. Once an
 * on-chain read proves the bag is dust at a deep loss, the answer cannot change
 * until the price moves a hundredfold, so sell requests for that mint are
 * answered from the proof for [RECHECK_MS] and then re-proved once. The
 * position stays open (no local close without sell finality, V5.9.72).
 */
object DustBagLatch7768 {
    private const val RECHECK_MS = 10L * 60_000L
    private val provenAtMs = ConcurrentHashMap<String, Long>()

    fun latch(mint: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isNotBlank()) provenAtMs[mint] = nowMs
    }

    /** True while a dust proof for [mint] is younger than [RECHECK_MS]. */
    fun held(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = provenAtMs[mint] ?: return false
        if (nowMs - at < RECHECK_MS) return true
        provenAtMs.remove(mint)
        return false
    }
}
