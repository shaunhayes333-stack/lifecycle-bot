package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** V5.0.8283 — the setup-input families whose newly validated observations advance a mint's feature revision. */
enum class FeatureKind8283 { PRICE, POOL_FLOW, TAPE, SHAPE, LIQUIDITY }

/**
 * V5.0.8283 §SHARED_FEATURE_REVISION (P0).
 *
 * 5.0.8282: LanePlaybook7907's setup cache (inputKey8084) was keyed on the token's own fields and history only, and an
 * unchanged key was reused for up to 5 s, so a fresh DexScreener pair, sweep row, executed print, candle repair or
 * on-chain liquidity read could leave a cached DATA_INCOMPLETE standing beside it (DATA_INCOMPLETE_DEFER_8136 1,497).
 *
 * One bounded revision per mint, shared by every specialist: a producer that ACCEPTED new validated data for a mint
 * calls [bump] (or CandidateAdmission8136.evidenceArrived8252, which advances it too). The revision is part of the
 * setup-cache identity, so the next evaluation of any lane rebuilds its features on the new evidence. Nothing here
 * is a reading: no value is stored, nothing is decided, and a sealed ticket is untouched (material change before
 * broadcast is LaneExecutionCoordinator's revalidation, as before).
 */
object FeatureRevision8283 {
    /** One mint's revision: the latest global sequence it advanced to, per kind, and when. */
    private class Rev8283(val revision: Long, val perKind: LongArray, val atMs: Long)

    internal const val CAP_8283 = 6_000
    internal const val KEEP_MS_8283 = 30L * 60_000L
    /** Repeated bumps of one mint + kind inside this gap advance the revision but wake the held defers once. */
    internal const val WAKE_COALESCE_MS_8283 = 250L

    private val revs = ConcurrentHashMap<String, Rev8283>()
    // A global sequence: an evicted mint that comes back can never repeat a revision a cache still holds.
    private val seq = AtomicLong(0L)
    private val wakeAt = ConcurrentHashMap<String, Long>()
    private val advanced = AtomicLong(0L)
    private val coalesced = AtomicLong(0L)
    private val byKind = Array(FeatureKind8283.values().size) { AtomicLong(0L) }
    private val sweepRows = AtomicLong(0L)
    private val invalidations = AtomicLong(0L)
    private val deferResolved = AtomicLong(0L)
    private val deferRecovered = AtomicLong(0L)

    /** Coordination API: [mint] has ACCEPTED new validated [kind] data now. */
    fun bump(mint: String, kind: FeatureKind8283) { bump8283(mint, kind, System.currentTimeMillis()) }

    // V5.0.8286 — high-rate producers (every tape print, every curve tick) bump at most once per mint + kind per
    // [THROTTLE_MS_8286]: a busy coin's classification refreshes every 2 s instead of being rebuilt on every print.
    internal const val THROTTLE_MS_8286 = 2_000L
    private val throttleAt8286 = ConcurrentHashMap<String, Long>()

    /** Pure: may a throttled bump fire at [nowMs] after the previous one at [lastMs] (null = never)? */
    internal fun throttleDue8286(lastMs: Long?, nowMs: Long): Boolean = lastMs == null || nowMs - lastMs !in 0L until THROTTLE_MS_8286

    fun bumpThrottled8286(mint: String, kind: FeatureKind8283, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        val k = "$mint|${kind.name}"
        if (!throttleDue8286(throttleAt8286[k], nowMs)) return
        throttleAt8286[k] = nowMs
        if (throttleAt8286.size > CAP_8283) throttleAt8286.entries.removeIf { nowMs - it.value > THROTTLE_MS_8286 }
        bump8283(mint, kind, nowMs)
    }

    /** [bump] at [nowMs]: wakes held defers waiting on [kind] (coalesced per mint + kind) and advances the revision. */
    fun bump8283(mint: String, kind: FeatureKind8283, nowMs: Long) {
        if (mint.isBlank()) return
        val wk = "$mint|${kind.name}"
        val last = wakeAt[wk]
        if (last != null && nowMs - last in 0L until WAKE_COALESCE_MS_8283) {
            coalesced.incrementAndGet()
            advance8283(mint, setOf(kind), nowMs)
            return
        }
        wakeAt[wk] = nowMs
        if (wakeAt.size > CAP_8283) wakeAt.entries.removeIf { nowMs - it.value > WAKE_COALESCE_MS_8283 }
        // One path for every producer: evidenceArrived8252 advances this revision and wakes the held defers.
        CandidateAdmission8136.evidenceArrived8252(mint, featuresOf8283(kind), nowMs, setOf(kind))
    }

    /**
     * Advances [mint]'s revision for [kinds] and returns it. Empty [kinds] (evidence of no setup-input family, e.g. a
     * safety / authority proof, or a pair answer that carried no field) leaves the revision where it is.
     */
    fun advance8283(mint: String, kinds: Set<FeatureKind8283>, nowMs: Long): Long {
        if (mint.isBlank()) return 0L
        if (kinds.isEmpty()) return revision8283(mint)
        val next = seq.incrementAndGet()
        revs.compute(mint) { _, prev ->
            val per = prev?.perKind?.copyOf() ?: LongArray(FeatureKind8283.values().size)
            for (k in kinds) per[k.ordinal] = next
            Rev8283(next, per, nowMs)
        }
        advanced.incrementAndGet()
        for (k in kinds) byKind[k.ordinal].incrementAndGet()
        if (revs.size > CAP_8283) {
            revs.entries.removeIf { nowMs - it.value.atMs > KEEP_MS_8283 }
            if (revs.size > CAP_8283) revs.entries.sortedBy { it.value.atMs }.take(revs.size - CAP_8283 * 9 / 10).forEach { revs.remove(it.key, it.value) }
        }
        return next
    }

    /** [mint]'s current revision (0 = no evidence noted). Part of the setup-cache identity. */
    fun revision8283(mint: String): Long = revs[mint]?.revision ?: 0L

    fun kindRevision8283(mint: String, kind: FeatureKind8283): Long = revs[mint]?.perKind?.getOrNull(kind.ordinal) ?: 0L

    /** When [mint]'s revision last advanced (0 = never). */
    fun advancedAt8283(mint: String): Long = revs[mint]?.atMs ?: 0L

    /** Pure: the admission feature names (CandidateAdmission8136 missing-input prefixes) one kind answers. */
    internal fun featuresOf8283(kind: FeatureKind8283): Set<String> = when (kind) {
        FeatureKind8283.PRICE -> setOf("price", "chg")
        FeatureKind8283.POOL_FLOW -> setOf("bp", "chg", "mcap", "swaps")
        FeatureKind8283.TAPE -> CandidateAdmission8136.TAPE_FEATURES_8252
        FeatureKind8283.SHAPE -> setOf("shape")
        FeatureKind8283.LIQUIDITY -> setOf("liq", "liquidity")
    }

    /** Pure: the kinds an evidenceArrived8252 feature set names (safety / authority evidence names none). */
    fun kindsOf8283(features: Set<String>): Set<FeatureKind8283> {
        val out = java.util.EnumSet.noneOf(FeatureKind8283::class.java)
        for (raw in features) when (raw.substringBefore(':').trim().lowercase()) {
            "price" -> out.add(FeatureKind8283.PRICE)
            "bp", "chg", "mcap" -> out.add(FeatureKind8283.POOL_FLOW)
            "tape", "swaps" -> out.add(FeatureKind8283.TAPE)
            "shape" -> out.add(FeatureKind8283.SHAPE)
            "liq", "liquidity" -> out.add(FeatureKind8283.LIQUIDITY)
        }
        return out
    }

    /** Pure: the kinds one DexScreener pair answer states (an absent field states nothing). */
    fun pairKinds8283(f: PairFlow8169.Flow8169): Set<FeatureKind8283> {
        val out = java.util.EnumSet.noneOf(FeatureKind8283::class.java)
        if ((f.buysM5 >= 0 && f.sellsM5 >= 0) || (f.buysH1 >= 0 && f.sellsH1 >= 0) || f.chg5m.isFinite() || f.chg1h.isFinite()) out.add(FeatureKind8283.POOL_FLOW)
        if (f.liquidityUsd.isFinite() && f.liquidityUsd > 0.0) out.add(FeatureKind8283.LIQUIDITY)
        return out
    }

    /**
     * Pure: the kinds a market-sweep row newly states over [prev] (same mint): only a row observed LATER than the one
     * held, carrying a valid reading of that family. A carried row (same observation time) states nothing new.
     */
    fun sweepKinds8283(prev: com.lifecyclebot.engine.market.MarketSweep7297.Row?,
                       cur: com.lifecyclebot.engine.market.MarketSweep7297.Row): Set<FeatureKind8283> {
        if (cur.observedAtMs8169 <= 0L) return emptySet()
        if (prev != null && cur.observedAtMs8169 <= prev.observedAtMs8169) return emptySet()
        val out = java.util.EnumSet.noneOf(FeatureKind8283::class.java)
        if (cur.priceUsd.isFinite() && cur.priceUsd > 0.0) out.add(FeatureKind8283.PRICE)
        if ((cur.buysH1 >= 0 && cur.sellsH1 >= 0) || cur.priceChange5mPct.isFinite()) out.add(FeatureKind8283.POOL_FLOW)
        if (cur.liquidityUsd.isFinite() && cur.liquidityUsd > 0.0) out.add(FeatureKind8283.LIQUIDITY)
        return out
    }

    /** MarketSweep7297.sweep: advance every mint whose merged row is a new observation (no defer wake: rows are bulk). */
    fun noteSweepRows8283(prev: List<com.lifecyclebot.engine.market.MarketSweep7297.Row>?,
                          cur: List<com.lifecyclebot.engine.market.MarketSweep7297.Row>, nowMs: Long): Int {
        val before = prev?.associateBy { it.mint }.orEmpty()
        var n = 0
        for (r in cur) {
            if (r.mint.length < 32) continue
            val kinds = sweepKinds8283(before[r.mint], r)
            if (kinds.isEmpty()) continue
            advance8283(r.mint, kinds, nowMs); n++
        }
        sweepRows.addAndGet(n.toLong())
        return n
    }

    // ── P5 report counters ──

    /** LanePlaybook7907.classify8084: a cached classification dropped because evidence advanced the revision. */
    fun noteInvalidation8283() { invalidations.incrementAndGet() }

    /** CandidateAdmission8136: a held DATA defer decided again to a non-data terminal ([afterEvidence]: new evidence since). */
    fun noteDeferResolved8283(afterEvidence: Boolean) {
        deferResolved.incrementAndGet()
        if (afterEvidence) deferRecovered.incrementAndGet()
    }

    /** "featureRev8283[mints=.. advanced=.. byKind{..} coalesced=.. sweepRows=.. cacheInvalidatedAfterEvidence=.. defersResolved=.. recoveredAfterEvidence=..]". */
    fun statusLine8283(): String {
        val kinds = FeatureKind8283.values().joinToString(",") { "${it.name}=${byKind[it.ordinal].get()}" }
        return "featureRev8283[mints=${revs.size} advanced=${advanced.get()} byKind{$kinds} coalesced=${coalesced.get()} " +
            "sweepRows=${sweepRows.get()} cacheInvalidatedAfterEvidence=${invalidations.get()} " +
            "defersResolved=${deferResolved.get()} recoveredAfterEvidence=${deferRecovered.get()}]"
    }

    internal fun clearForTest8283() {
        revs.clear(); wakeAt.clear(); advanced.set(0L); coalesced.set(0L); sweepRows.set(0L)
        invalidations.set(0L); deferResolved.set(0L); deferRecovered.set(0L); byKind.forEach { it.set(0L) }
    }
}
