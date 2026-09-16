package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6829 §CAUSAL_DEDUP — operator diagnosis item #2 for build 5.0.6828:
 *   "EXEC_INTENT_CREATED=523 EXEC_INTENT_REUSED=1355
 *    PAPER_ATOMIC_COMMIT_LEDGER_DUPLICATE=242
 *    PAPER_ATOMIC_COMMIT_JOURNAL_DUPLICATE=220
 *    ONE_EXECUTABLE_BUY_PER_MINT_VERSION=30 blocks
 *    47 causal reservations superseded.
 *    Duplicate causal branches survive too far downstream — costs CPU,
 *    creates phantom funnel counts, produces unnecessary releases and
 *    can poison attribution."
 *
 * DESIGN — first-writer-wins early dedup at the (mode, mint, lane,
 * candidateVersion) composite key, BEFORE execution intent creation.
 *   • `claimIntent(...)` returns true only for the FIRST caller with
 *     a given composite key. All subsequent calls with the same key
 *     get false (contributor / duplicate) and are expected to abort.
 *   • Claims expire after TTL_MS (default 30s) so if the winner never
 *     finalizes, the slot re-opens for a new candidate version.
 *   • `releaseIntent` may be called on causal reservation supersession.
 *   • Fail-open: any exception returns true so the guard cannot itself
 *     become a hot-path fault (V5.0.6811 crash-safety lesson).
 */
object CausalDedupGate6829 {

    private data class Claim(
        val key: String,
        val ownerLane: String,
        val ownerVersion: String,
        val claimedAtMs: Long,
    )

    private val claims = ConcurrentHashMap<String, Claim>()
    private const val CAP = 8192
    const val TTL_MS: Long = 30_000L

    private val attempts = AtomicLong(0L)
    private val wins = AtomicLong(0L)
    private val duplicates = AtomicLong(0L)
    private val expiredReclaims = AtomicLong(0L)
    private val releases = AtomicLong(0L)

    fun key(mode: String, mint: String, lane: String, candidateVersion: String): String {
        val m = mode.trim().uppercase()
        val mi = mint.trim().take(12)
        val ln = lane.trim().uppercase().take(24)
        val cv = candidateVersion.trim().take(48)
        return "$m|$mi|$ln|$cv"
    }

    /**
     * Attempt to claim intent for this composite key.
     * @return true if this caller is the AUTHORITATIVE owner and may
     *   proceed to create an ExecIntent; false if a prior caller has
     *   already claimed the same key (duplicate — abort quietly).
     */
    fun claimIntent(
        mode: String,
        mint: String,
        lane: String,
        candidateVersion: String,
    ): Boolean {
        attempts.incrementAndGet()
        return try {
            val k = key(mode, mint, lane, candidateVersion)
            val now = System.currentTimeMillis()
            val existing = claims[k]
            if (existing != null && (now - existing.claimedAtMs) < TTL_MS) {
                duplicates.incrementAndGet()
                try {
                    PipelineHealthCollector.labelInc("CAUSAL_DEDUP_DUPLICATE_6829")
                    PipelineHealthCollector.labelInc(
                        "CAUSAL_DEDUP_DUPLICATE_6829_${lane.uppercase().take(24)}"
                    )
                    ForensicLogger.lifecycle(
                        "CAUSAL_DEDUP_DUPLICATE_6829",
                        "key=${k.take(80)} owner=${existing.ownerLane}/${existing.ownerVersion} " +
                            "ageMs=${now - existing.claimedAtMs} action=abort_second_branch",
                    )
                } catch (_: Throwable) {}
                return false
            }
            if (existing != null) {
                expiredReclaims.incrementAndGet()
                try { PipelineHealthCollector.labelInc("CAUSAL_DEDUP_EXPIRED_RECLAIM_6829") } catch (_: Throwable) {}
            }
            claims[k] = Claim(k, lane, candidateVersion, now)
            wins.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("CAUSAL_DEDUP_WIN_6829")
                PipelineHealthCollector.labelInc("CAUSAL_DEDUP_WIN_6829_${lane.uppercase().take(24)}")
            } catch (_: Throwable) {}
            maybeEvict()
            true
        } catch (_: Throwable) { true }
    }

    /** Release the claim, e.g. on causal reservation supersession or terminal. */
    fun releaseIntent(mode: String, mint: String, lane: String, candidateVersion: String) {
        try {
            val k = key(mode, mint, lane, candidateVersion)
            if (claims.remove(k) != null) {
                releases.incrementAndGet()
                try { PipelineHealthCollector.labelInc("CAUSAL_DEDUP_RELEASE_6829") } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun maybeEvict() {
        if (claims.size <= CAP) return
        val now = System.currentTimeMillis()
        // Drop everything past TTL first
        claims.entries.removeIf { now - it.value.claimedAtMs >= TTL_MS }
        if (claims.size <= CAP) return
        // If still over cap, drop the oldest
        val oldest = claims.entries.minByOrNull { it.value.claimedAtMs }?.key ?: return
        claims.remove(oldest)
    }

    fun statusLine(): String =
        "claims=${claims.size} attempts=${attempts.get()} wins=${wins.get()} " +
            "duplicates=${duplicates.get()} expired=${expiredReclaims.get()} releases=${releases.get()}"

    internal fun clearForTest() {
        claims.clear()
        attempts.set(0L); wins.set(0L)
        duplicates.set(0L); expiredReclaims.set(0L); releases.set(0L)
    }
}
