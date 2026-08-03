package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6411 §8 — SCANNER CANONICAL DEDUPE.
 *
 * OPERATOR DIRECTIVE (Feb 2026)
 * ─────────────────────────────
 * "There are approximately 18 scanner callbacks per recorded intake
 *  event. Deduplicate BEFORE enrichment. Create a lightweight
 *  pre-intake key: mint + pool + source family + discovery epoch
 *  bucket. Drop duplicates before token mapping, holder analysis,
 *  safety refresh, strategy evaluation."
 *
 * DESIGN
 * ──────
 *   • Bounded TTL cache (5 min) keyed by canonicalKey().
 *   • First observation stamps the record, subsequent observations
 *     merge sources into a shared sourceSet and bump lastSeenAt but
 *     do NOT trigger a new enrichment cycle.
 *   • Report SCANNER_CANONICAL_DEDUPE_6411 on every suppressed
 *     duplicate so operators see actual dedup volume.
 *
 * This module is ADVISORY — scanners call `shouldEnrich(...)` and
 * skip expensive work when the answer is false. Behaviour of any
 * caller that ignores this signal is unchanged.
 */
object ScannerCanonicalDedupe6411 {

    private const val TTL_MS = 5L * 60_000L
    private const val EPOCH_BUCKET_MS = 5_000L
    private const val CAP = 4096

    private data class Rec(
        val canonicalKey: String,
        val firstSeenAtMs: Long,
        val lastSeenAtMs: AtomicLong,
        val sources: MutableSet<String>,
    )

    private val records = ConcurrentHashMap<String, Rec>()

    /** Family-normalised source name (§8.2). */
    private fun sourceFamily(source: String): String {
        val s = source.uppercase()
        return when {
            s.contains("RAYDIUM") -> "RAYDIUM"
            s.contains("PUMP") -> "PUMP"
            s.contains("MEME_REGISTRY") -> "MEME_REGISTRY"
            s.contains("DEX_TRENDING") || s.contains("DEX_BOOSTED") -> "DEXSCREENER"
            s.contains("COINGECKO") -> "COINGECKO"
            s.contains("SOLANA_BLUECHIP") -> "BLUECHIP"
            s.contains("BIRDEYE") -> "BIRDEYE"
            s.contains("HELIUS") -> "HELIUS"
            else -> "OTHER"
        }
    }

    private fun canonicalKey(mint: String, pool: String, source: String): String {
        val bucket = System.currentTimeMillis() / EPOCH_BUCKET_MS
        return "${mint.take(24)}|${pool.take(24)}|${sourceFamily(source)}|$bucket"
    }

    /**
     * Returns true when this observation should trigger a full
     * enrichment cycle (i.e. we haven't seen this canonical key
     * within the current epoch bucket). Returns false when the
     * observation should be merged and dropped.
     */
    fun shouldEnrich(mint: String, pool: String, source: String): Boolean {
        val now = System.currentTimeMillis()
        // Housekeeping: cap growth by evicting the oldest record when needed.
        if (records.size > CAP) {
            val threshold = now - TTL_MS
            val it = records.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.value.firstSeenAtMs < threshold) it.remove()
                if (records.size <= CAP - 64) break
            }
        }
        val key = canonicalKey(mint, pool, source)
        val existing = records[key]
        if (existing == null) {
            val rec = Rec(
                canonicalKey = key,
                firstSeenAtMs = now,
                lastSeenAtMs = AtomicLong(now),
                sources = java.util.Collections.synchronizedSet(mutableSetOf(sourceFamily(source))),
            )
            records[key] = rec
            return true
        }
        existing.lastSeenAtMs.set(now)
        existing.sources.add(sourceFamily(source))
        try {
            PipelineHealthCollector.labelInc("SCANNER_CANONICAL_DEDUPE_6411")
            // Log at low volume (every 100th dedupe) to preserve signal
            // without overwhelming the forensic ring.
            val n = PipelineHealthCollector.labelCountSnapshot("SCANNER_CANONICAL_DEDUPE_6411")
            if (n % 100L == 0L) {
                ForensicLogger.lifecycle(
                    "SCANNER_CANONICAL_DEDUPE_6411",
                    "mint=${mint.take(10)} pool=${pool.take(10)} source=$source key=$key sources=${existing.sources} dedupeCount=$n",
                )
            }
        } catch (_: Throwable) {}
        return false
    }

    fun statusLine(): String = "cache=${records.size} dedupes=${try { PipelineHealthCollector.labelCountSnapshot("SCANNER_CANONICAL_DEDUPE_6411") } catch (_: Throwable) { 0L }}"

    internal fun resetForTest() {
        records.clear()
    }
}
