package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalTokenAmount
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8237 §ONE_BALANCE_PROOF_PER_EXIT_ATTEMPT — the wallet's token-account map, read once per sell attempt and
 * reused across the attempt's route retries while fresh; invalidated the moment a sell for the mint is registered or
 * ends (a spend may have happened). 8236 forensic (YEARN, t2b=177,675 ms): the normal sell re-read the whole wallet
 * synchronously on every route retry (plus the 8020 re-read), ~200 s of balance work across one exit lifecycle.
 * The raw-quantity and remaining-balance checks downstream are unchanged — they read the same proof.
 */
object SellBalanceProof8237 {
    private const val FRESH_MS_8237 = 4_000L

    private class Proof(val accounts: Map<String, CanonicalTokenAmount>, val atMs: Long, val generation: Long)

    private val proofs = ConcurrentHashMap<String, Proof>()
    private val generations = ConcurrentHashMap<String, Long>()

    /** Pure: is a proof read at [readAtMs] of [readGen] still usable at [nowMs] under [gen]? */
    fun usable8237(readAtMs: Long, readGen: Long, nowMs: Long, gen: Long, freshMs: Long = FRESH_MS_8237): Boolean =
        readGen == gen && nowMs - readAtMs in 0L..freshMs

    /** The fresh proof for [mint] or the result of [read] (stored). Null when the read failed. */
    fun accounts8237(mint: String, nowMs: Long, read: () -> Map<String, CanonicalTokenAmount>?): Map<String, CanonicalTokenAmount>? {
        val gen = generations[mint] ?: 0L
        proofs[mint]?.let { p ->
            if (usable8237(p.atMs, p.generation, nowMs, gen) && p.accounts[mint] != null) {
                try { PipelineHealthCollector.labelInc("SELL_BALANCE_PROOF_REUSED_8237") } catch (_: Throwable) {}
                return p.accounts
            }
        }
        val fresh = read() ?: return null
        if (fresh[mint] != null) proofs[mint] = Proof(fresh, nowMs, gen)
        if (proofs.size > 500) proofs.entries.removeIf { nowMs - it.value.atMs > 60_000L }
        return fresh
    }

    /** A sell for [mint] was registered or ended: whatever the wallet held before is no longer proof. */
    fun invalidate8237(mint: String) {
        if (mint.isBlank()) return
        generations.merge(mint, 1L, Long::plus)
        proofs.remove(mint)
    }
}
