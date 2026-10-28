package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8418 §BUNDLE_SCAN_ONLY_WHAT_REACHES_SCORING.
 *
 * 5.0.8416: BUNDLE_SCAN spent 29.5k Helius credits (295 analyses x 100) with 2,983 more deferred on pace. The 100-credit
 * first-block read was asked from TokenSafetyChecker for EVERY young, not-hard-blocked coin at its safety check — the
 * intake stage, before any lane or the Cortex had scored it — while its only readers (the Cortex FIRST_BLOCK voters, the
 * lane playbook, the safety report's bundle fields) read it at scoring. Most young intake never reaches scoring.
 *
 * Now the safety check asks here: a coin the Cortex has already scored (assess, [SCORED_FRESH_MS_8418]) is scanned at
 * once; any other coin is remembered ([PENDING_TTL_MS_8418], bounded) and scanned the moment it first reaches scoring.
 * The safety check's own conditions (young enough, no hard block, the Cortex still finding the vote worth paying for)
 * are unchanged and checked before the ask. Unknown stays UNKNOWN until the analysis lands; no default is written.
 */
object BundleScanScope8418 {
    private const val SCORED_FRESH_MS_8418 = 10L * 60_000L
    const val PENDING_TTL_MS_8418 = 10L * 60_000L
    private const val CAP_8418 = 2_000

    private data class Pending8418(val symbol: String, val heliusKey: String, val atMs: Long)

    private val scoredAt = ConcurrentHashMap<String, Long>()
    private val pending = ConcurrentHashMap<String, Pending8418>()
    private val scannedNow = AtomicLong(0L)
    private val deferred = AtomicLong(0L)
    private val releasedAtScoring = AtomicLong(0L)
    private val expired = AtomicLong(0L)

    /** The actual request (BundleDetector, off-thread, credit-paced). Replaceable in tests only. */
    @Volatile internal var launcher8418: (String, String, String) -> Unit =
        { mint, symbol, key -> BundleDetector.requestAsync7763(mint, symbol, key) }

    /** Pure: may a coin last scored at [scoredAtMs] (null = never) be bundle-scanned at [nowMs]? */
    fun scoredRecently8418(scoredAtMs: Long?, nowMs: Long): Boolean =
        scoredAtMs != null && nowMs - scoredAtMs in 0L..SCORED_FRESH_MS_8418

    /** Pure: is a deferral made at [deferredAtMs] still worth releasing at [nowMs]? */
    fun pendingLive8418(deferredAtMs: Long, nowMs: Long): Boolean = nowMs - deferredAtMs in 0L..PENDING_TTL_MS_8418

    /**
     * TokenSafetyChecker: the coin qualifies for a first-block scan. Scans now when it has reached scoring, else defers
     * the scan to its first scoring. True when the request went out now.
     */
    fun requestOrDefer8418(mint: String, symbol: String, heliusKey: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank() || heliusKey.isBlank()) return false
        if (scoredRecently8418(scoredAt[mint], nowMs)) {
            scannedNow.incrementAndGet()
            pending.remove(mint)
            launcher8418(mint, symbol, heliusKey)
            return true
        }
        if (pending.put(mint, Pending8418(symbol, heliusKey, nowMs)) == null) deferred.incrementAndGet()
        if (pending.size > CAP_8418) bound8418(nowMs)
        return false
    }

    /** Cortex7885.assess: [mint] is being scored. Releases its deferred scan, if any. */
    fun noteScored8418(mint: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        scoredAt[mint] = nowMs
        if (scoredAt.size > CAP_8418) bound8418(nowMs)
        val p = pending.remove(mint) ?: return
        if (!pendingLive8418(p.atMs, nowMs)) { expired.incrementAndGet(); return }
        releasedAtScoring.incrementAndGet()
        try { launcher8418(mint, p.symbol, p.heliusKey) } catch (_: Throwable) {}
    }

    private fun bound8418(nowMs: Long) {
        try {
            val before = pending.size
            pending.entries.removeIf { !pendingLive8418(it.value.atMs, nowMs) }
            expired.addAndGet((before - pending.size).coerceAtLeast(0).toLong())
            com.lifecyclebot.engine.truth.MemoryBounds8289.capByAge8289(scoredAt, CAP_8418, SCORED_FRESH_MS_8418, nowMs) { it }
            if (pending.size > CAP_8418) {
                pending.entries.sortedBy { it.value.atMs }.take(pending.size - CAP_8418 * 9 / 10).forEach { pending.remove(it.key) }
            }
        } catch (_: Throwable) {}
    }

    internal fun pendingCount8418(): Int = pending.size
    internal fun resetForTest8418() { scoredAt.clear(); pending.clear() }

    fun statusLine8418(): String =
        "bundleScope8418[scannedAtScoring=${scannedNow.get()} deferredToScoring=${deferred.get()} released=${releasedAtScoring.get()} " +
            "expiredUnscored=${expired.get()} pending=${pending.size}]"
}
