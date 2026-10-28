package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8191 §SPEND_WHERE_A_DECISION_IS_NEAR — the P1 data pipeline's shared bookkeeping.
 *
 * 5.0.8186: Birdeye 150000/150000 CU spent (BIRDEYE_SEED_SKIPPED_BUDGET=1639), GeckoTerminal 0 bars, setup core
 * coverage 33%. Birdeye was spent per watchlist tick on every tradable coin (security, trade data, price stats, whale
 * feed: four premium calls per coin per cache window) and on 1m / 5m / 15m / 4H candle seeds for every new token, most
 * of which never reached a decision, while Codex (keyed, 56/56 chart calls) and DexScreener (free, 98%) were healthy.
 *
 *  - [noteNearDecision8191] / [nearDecision8191]: a coin whose setup asked for its missing inputs (the playbook is
 *    classifying it now) is near a decision for [NEAR_DECISION_MS_8191]; held positions always are.
 *  - [premiumAllowed8191]: Birdeye premium enrichment only for a held or near-decision coin, and not when a cheaper
 *    fresh source (a DexScreener pair answer) already states the same flow and the coin is not held.
 *  - Candle seeds (DataOrchestrator.seedCandleHistory): priority (held / near decision) asks Codex getBars first,
 *    then the keyless feed, then Birdeye for a timeframe still empty. An ordinary new token: keyless, then Codex while
 *    [CODEX_ORDINARY_PER_MIN_8191] lasts ([claimCodexOrdinary8191]), never Birdeye.
 *  - [pairFetchDue8191]: a setup input whose DexScreener pair is NOT_FETCHED / STALE triggers one pair fetch per
 *    [PAIR_FETCH_GAP_MS_8191] per mint (the answer lands in PairFlow8169; nothing is inferred).
 *  - Birdeye spend by endpoint class ([noteBirdeye8191]) so the biggest consumer is named in the report.
 */
object DataPipeline8191 {
    const val NEAR_DECISION_MS_8191 = 10L * 60_000L
    const val PAIR_FETCH_GAP_MS_8191 = 60_000L
    const val CODEX_ORDINARY_PER_MIN_8191 = 6
    private const val CAP_8191 = 4_000

    const val SRC_CODEX = "codex"
    const val SRC_KEYLESS = "keyless"
    const val SRC_BIRDEYE = "birdeye"

    private val nearDecisionAt = ConcurrentHashMap<String, Long>()
    private val pairFetchAt = ConcurrentHashMap<String, Long>()
    private val codexOrdinary = java.util.ArrayDeque<Long>()
    private val birdeyeByEndpoint = ConcurrentHashMap<String, AtomicLong>()
    private val premiumAllowed = AtomicLong(0L)
    private val premiumSkipped = AtomicLong(0L)
    private val premiumSkippedCovered = AtomicLong(0L)
    private val pairFetches = AtomicLong(0L)
    private val seedBySource = ConcurrentHashMap<String, AtomicLong>()

    private fun trim(m: ConcurrentHashMap<String, Long>, nowMs: Long, keepMs: Long) {
        if (m.size > CAP_8191) m.entries.removeIf { nowMs - it.value > keepMs }
        if (m.size > CAP_8191) m.clear()
    }

    fun noteNearDecision8191(mint: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        nearDecisionAt[mint] = nowMs
        trim(nearDecisionAt, nowMs, NEAR_DECISION_MS_8191)
    }

    /** Pure: is a coin noted at [notedAtMs] still near a decision at [nowMs]? */
    fun nearDecisionAt8191(notedAtMs: Long?, nowMs: Long): Boolean =
        notedAtMs != null && nowMs - notedAtMs in 0L..NEAR_DECISION_MS_8191

    fun nearDecision8191(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean = nearDecisionAt8191(nearDecisionAt[mint], nowMs)

    /**
     * V5.0.8269: the Helius WebSocket is a small, paid subscription set, not a
     * second watchlist. Select the newest near-decision mints for its rotating
     * focus slots; the caller adds HELD mints ahead of this list. A TTL-expired
     * candidate consumes no capacity. Pool/mint validity is checked by the caller.
     */
    fun recentNearDecisionMints8269(limit: Int, nowMs: Long = System.currentTimeMillis()): List<String> =
        if (limit <= 0) emptyList()
        else nearDecisionAt.entries.asSequence()
            .filter { nearDecisionAt8191(it.value, nowMs) }
            .sortedByDescending { it.value }
            .take(limit.coerceAtMost(16))
            .map { it.key }
            .toList()

    private fun held8191(mint: String): Boolean = try { HeldPositionSupervisor7246.isHeld(mint) } catch (_: Throwable) { false }

    /** Pure: may Birdeye premium enrichment run for a coin? */
    fun premiumAllowedPure8191(held: Boolean, nearDecision: Boolean, cheaperFresh: Boolean): Boolean =
        held || (nearDecision && !cheaperFresh)

    /** BotService's premium prefetch: held, or near a decision without a fresh DexScreener flow for the same coin. */
    fun premiumAllowed8191(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val held = held8191(mint)
        val near = nearDecision8191(mint, nowMs)
        val cheaper = try {
            com.lifecyclebot.engine.truth.PairFlow8169.peek8169(mint)?.let { nowMs - it.atMs in 0L..(5L * 60_000L) } == true
        } catch (_: Throwable) { false }
        val ok = premiumAllowedPure8191(held, near, cheaper)
        if (ok) premiumAllowed.incrementAndGet() else {
            premiumSkipped.incrementAndGet()
            if (near && cheaper) premiumSkippedCovered.incrementAndGet()
            try { PipelineHealthCollector.labelInc("BIRDEYE_PREMIUM_SKIPPED_NOT_NEAR_DECISION_8191") } catch (_: Throwable) {}
        }
        return ok
    }

    /** BotService's premium prefetch (one-line call site): tradable (liq >= $2K, mcap >= $5K) and [premiumAllowed8191]. */
    fun premium8191(ts: com.lifecyclebot.data.TokenState): Boolean =
        ts.lastLiquidityUsd >= 2_000.0 && ts.lastMcap >= 5_000.0 && premiumAllowed8191(ts.mint)

    /** Is this seed a priority one (held or near a decision)? */
    fun seedPriority8191(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean = held8191(mint) || nearDecision8191(mint, nowMs)

    /** Claim one of the ordinary seeds' Codex calls this minute (priority seeds never ask). */
    fun claimCodexOrdinary8191(nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(codexOrdinary) {
        while (codexOrdinary.isNotEmpty() && nowMs - codexOrdinary.peekFirst() >= 60_000L) codexOrdinary.pollFirst()
        if (codexOrdinary.size >= CODEX_ORDINARY_PER_MIN_8191) false else { codexOrdinary.addLast(nowMs); true }
    }

    fun noteSeed8191(source: String, outcome: String) {
        seedBySource.computeIfAbsent("$source:$outcome") { AtomicLong(0L) }.incrementAndGet()
    }

    /** Pure: is a DexScreener pair fetch due for a mint whose pair flow was last answered at [flowAtMs] and last asked at [askedAtMs]? */
    fun pairFetchDue8191(flowAtMs: Long?, askedAtMs: Long?, nowMs: Long, freshMs: Long = 15L * 60_000L): Boolean {
        val fresh = flowAtMs != null && nowMs - flowAtMs in -60_000L..freshMs
        if (fresh) return false
        return askedAtMs == null || nowMs - askedAtMs !in 0L until PAIR_FETCH_GAP_MS_8191
    }

    /** Claim the pair fetch for [mint] now (false = not due). */
    fun claimPairFetch8191(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val flowAt = try { com.lifecyclebot.engine.truth.PairFlow8169.peek8169(mint)?.atMs } catch (_: Throwable) { null }
        if (!pairFetchDue8191(flowAt, pairFetchAt[mint], nowMs)) return false
        pairFetchAt[mint] = nowMs
        trim(pairFetchAt, nowMs, PAIR_FETCH_GAP_MS_8191)
        pairFetches.incrementAndGet()
        return true
    }

    /** Pure: the endpoint class of a Birdeye URL ("defi/ohlcv", "defi/v3/token/trade-data"). */
    fun birdeyeEndpoint8191(url: String): String {
        val path = url.substringAfter("birdeye.so", url).substringBefore('?').trim('/')
        return path.split('/').filter { it.isNotBlank() }.take(4).joinToString("/").ifBlank { "other" }
    }

    fun noteBirdeye8191(url: String) {
        val k = birdeyeEndpoint8191(url)
        if (birdeyeByEndpoint.size >= 64 && !birdeyeByEndpoint.containsKey(k)) { birdeyeByEndpoint.computeIfAbsent("other") { AtomicLong(0L) }.incrementAndGet(); return }
        birdeyeByEndpoint.computeIfAbsent(k) { AtomicLong(0L) }.incrementAndGet()
    }

    fun statusLine8191(): String =
        "pipeline8191[birdeyeByEndpoint{" + birdeyeByEndpoint.entries.sortedByDescending { it.value.get() }.take(8)
            .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } + "} premium[allowed=${premiumAllowed.get()} " +
            "skipped=${premiumSkipped.get()} coveredByDex=${premiumSkippedCovered.get()}] nearDecision=${nearDecisionAt.size} " +
            "pairFetches=${pairFetches.get()} seed{" + seedBySource.entries.sortedByDescending { it.value.get() }
            .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } + "}] " +
            ProviderLanes8285.statusLine8285() + " " + FeatureSourceCounters8285.line8285() + " " +
            (try { com.lifecyclebot.network.DexPools8285.statusLine8285() } catch (_: Throwable) { "dexPools8285 -" }) + " " +
            (try { DexBatchLane8287.statusLine8287() } catch (_: Throwable) { "dexLane8287 -" }) + " " +   // V5.0.8287
            (try { com.lifecyclebot.network.DexHostBudget8287.statusLine8287() } catch (_: Throwable) { "dexHost8287 -" }) + " " +
            (try { com.lifecyclebot.network.GeckoHostBudget8382.statusLine8382() } catch (_: Throwable) { "geckoHost8382 -" }) + " " +
            (try { BirdeyeBudgetGate.statusLine8287() } catch (_: Throwable) { "birdeyeBudget8287 -" }) + " " +
            (try { IntakeFirstSight8317.statusLine8317() } catch (_: Throwable) { "intake8317 -" }) + " " +   // V5.0.8317
            (try { com.lifecyclebot.network.KeylessRpc8359.statusLine8359() } catch (_: Throwable) { "rpcRoute8359 -" }) + " " +   // V5.0.8359
            (try { HeliusCadence8361.creditsLine8361() } catch (_: Throwable) { "creditsByCaller8361 -" }) + " " +   // V5.0.8361
            (try { com.lifecyclebot.engine.truth.KeylessMarks8359.statusLine8359() } catch (_: Throwable) { "keylessMarks8359 -" }) + " " +
            (try { com.lifecyclebot.network.PumpFunCandles8359.statusLine8359() } catch (_: Throwable) { "pumpCandles8359 -" })

    internal fun resetForTests8191() {
        nearDecisionAt.clear(); pairFetchAt.clear(); synchronized(codexOrdinary) { codexOrdinary.clear() }
        birdeyeByEndpoint.clear(); seedBySource.clear()
        premiumAllowed.set(0L); premiumSkipped.set(0L); premiumSkippedCovered.set(0L); pairFetches.set(0L)
    }
}

/**
 * V5.0.8285 §EVERY_PROVIDER_ITS_OWN_CLOCK — keyless provider lanes (directive 8282 P3).
 *
 *  - Priority: a held position's data first, then a coin near a decision, then a lane hand-off, then bulk updates
 *    ([Prio8285]); a drain takes the highest priority, oldest first.
 *  - One request per key: the same provider / kind / key asked from several lanes is ONE pending request (its priority
 *    raised to the highest asker), never several calls.
 *  - Independent backoff per provider: a 429 / 5xx streak cools only that provider ([cooling8285]); another provider,
 *    and evidence already cached or streamed, stay usable. A 401 / 402 / 403 disables a provider for the session.
 */
object ProviderLanes8285 {
    enum class Prio8285 { HELD, NEAR_DECISION, HANDOFF, BULK }

    const val DEX_8285 = "dexscreener"
    const val GECKO_8285 = "geckoterminal"
    const val KIND_PAIR_8285 = "pair"
    const val KIND_TRADES_8285 = "trades"
    private const val BASE_COOLDOWN_MS_8285 = 30_000L
    private const val MAX_COOLDOWN_MS_8285 = 10L * 60_000L
    private const val CAP_8285 = 4_000

    private class Pending8285(@Volatile var prio: Prio8285, val atMs: Long)
    private class Backoff8285 {
        @Volatile var untilMs = 0L
        @Volatile var level = 0
        @Volatile var streak = 0
        @Volatile var disabledCode = 0
        val cooldowns = AtomicLong(0L)
    }

    private val pending = ConcurrentHashMap<String, Pending8285>()
    private val backoff = ConcurrentHashMap<String, Backoff8285>()
    private val coalesced = AtomicLong(0L)
    private val enqueued = AtomicLong(0L)

    /** Pure: the priority of a request for a coin. */
    fun prioOf8285(held: Boolean, nearDecision: Boolean, handoff: Boolean): Prio8285 = when {
        held -> Prio8285.HELD
        nearDecision -> Prio8285.NEAR_DECISION
        handoff -> Prio8285.HANDOFF
        else -> Prio8285.BULK
    }

    /** The priority of a request for [mint] now (held / near a decision read live; [handoff] from the caller). */
    fun prioFor8285(mint: String, handoff: Boolean = false, nowMs: Long = System.currentTimeMillis()): Prio8285 {
        val held = try { HeldPositionSupervisor7246.isHeld(mint) } catch (_: Throwable) { false }
        // V5.0.8359 — a mint a shadow book wants marked (KeylessMarks8359) is served right after held positions.
        val wanted8359 = try { com.lifecyclebot.engine.truth.KeylessMarks8359.isWanted8359(mint, nowMs) } catch (_: Throwable) { false }
        // V5.0.8418 — a forward-label observation due for its mark rides at HANDOFF: after held and shadow, before bulk.
        val label8418 = try { com.lifecyclebot.engine.truth.KeylessMarks8359.isLabelWanted8418(mint, nowMs) } catch (_: Throwable) { false }
        return prioOf8285(held, DataPipeline8191.nearDecision8191(mint, nowMs) || wanted8359, handoff || label8418)
    }

    /** Pure: the cooldown after the [level]-th consecutive provider cooldown (30 s doubling, capped at 10 min). */
    fun cooldownMs8285(level: Int): Long = (BASE_COOLDOWN_MS_8285 shl level.coerceIn(0, 5)).coerceAtMost(MAX_COOLDOWN_MS_8285)

    /**
     * Queue [key] for [provider]/[kind] at [prio]. False when the identical request is already pending (its priority is
     * raised to [prio] when higher): the second asker shares the first request's answer.
     */
    fun enqueue8285(provider: String, kind: String, key: String, prio: Prio8285, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (key.isBlank()) return false
        val k = "$provider|$kind|$key"
        val prior = pending.putIfAbsent(k, Pending8285(prio, nowMs))
        if (prior != null) {
            if (prio.ordinal < prior.prio.ordinal) prior.prio = prio
            coalesced.incrementAndGet()
            return false
        }
        enqueued.incrementAndGet()
        if (pending.size > CAP_8285) {
            // Bounded: the oldest bulk requests leave first; held / near-decision requests are never evicted for space.
            pending.entries.filter { it.value.prio == Prio8285.BULK }.sortedBy { it.value.atMs }
                .take(pending.size - CAP_8285).forEach { pending.remove(it.key) }
            // V5.0.8289 — hard cap: a request nobody drained for [PENDING_TTL_MS_8289] (any priority) is stale, and past
            // twice the cap the oldest go whatever their priority (a lane whose drain never runs cannot grow without bound).
            com.lifecyclebot.engine.truth.MemoryBounds8289.capByAge8289(pending, CAP_8285 * 2, PENDING_TTL_MS_8289, nowMs) { it.atMs }
        }
        return true
    }

    private const val PENDING_TTL_MS_8289 = 10L * 60_000L

    /** V5.0.8289 — memory census: pending requests (key ~90 chars + [Pending8285] + node). */
    fun pendingTotal8289(): Int = pending.size

    /** V5.0.8289 — pressure trim: pending requests older than [PENDING_TTL_MS_8289] (any priority). Returns removed. */
    fun trim8289(nowMs: Long = System.currentTimeMillis()): Int {
        val before = pending.size
        pending.entries.removeIf { nowMs - it.value.atMs > PENDING_TTL_MS_8289 }
        return (before - pending.size).coerceAtLeast(0)
    }

    /** Take up to [max] pending keys of [provider]/[kind], highest priority first, oldest first within a priority. */
    fun drain8285(provider: String, kind: String, max: Int): List<String> {
        val prefix = "$provider|$kind|"
        val picked = pending.entries.filter { it.key.startsWith(prefix) }
            .sortedWith(compareBy({ it.value.prio.ordinal }, { it.value.atMs })).take(max.coerceAtLeast(0))
        return picked.mapNotNull { e -> if (pending.remove(e.key) != null) e.key.removePrefix(prefix) else null }
    }

    fun pendingCount8285(provider: String, kind: String): Int = pending.keys.count { it.startsWith("$provider|$kind|") }

    /** V5.0.8287 — how long the oldest pending [provider]/[kind] request has waited (0 when none). */
    fun oldestPendingAgeMs8287(provider: String, kind: String, nowMs: Long = System.currentTimeMillis()): Long {
        val prefix = "$provider|$kind|"
        val oldest = pending.entries.filter { it.key.startsWith(prefix) }.minOfOrNull { it.value.atMs } ?: return 0L
        return (nowMs - oldest).coerceAtLeast(0L)
    }

    /** V5.0.8287 — is a held position's request pending for [provider]/[kind]? (it is sent without a collection wait) */
    fun heldPending8287(provider: String, kind: String): Boolean {
        val prefix = "$provider|$kind|"
        return pending.entries.any { it.key.startsWith(prefix) && it.value.prio == Prio8285.HELD }
    }

    /** V5.0.8287 — ms left of [provider]'s own cooldown (0 = not cooling; Long.MAX_VALUE = disabled for the session). */
    fun coolingLeftMs8287(provider: String, nowMs: Long = System.currentTimeMillis()): Long {
        val b = backoff[provider] ?: return 0L
        if (b.disabledCode != 0) return Long.MAX_VALUE
        return (b.untilMs - nowMs).coerceAtLeast(0L)
    }

    /** Is [provider] cooling down (or disabled) now? Never consulted for any other provider or for cached evidence. */
    fun cooling8285(provider: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val b = backoff[provider] ?: return false
        return b.disabledCode != 0 || nowMs < b.untilMs
    }

    /** Record [provider]'s HTTP answer: 2xx resets its ladder, 429 cools at once, a 5xx streak of 3 cools, 401/402/403 disables. */
    fun noteResult8285(provider: String, code: Int, nowMs: Long = System.currentTimeMillis()) {
        val b = backoff.computeIfAbsent(provider) { Backoff8285() }
        when {
            code in 200..299 -> { b.streak = 0; b.level = 0 }
            code == 401 || code == 402 || code == 403 -> b.disabledCode = code
            code == 429 || code >= 500 || code <= 0 -> {
                b.streak++
                if (code == 429 || b.streak >= 3) {
                    b.untilMs = nowMs + cooldownMs8285(b.level)
                    b.level = (b.level + 1).coerceAtMost(5)
                    b.streak = 0
                    b.cooldowns.incrementAndGet()
                }
            }
        }
    }

    fun statusLine8285(nowMs: Long = System.currentTimeMillis()): String =
        "lanes8285[enqueued=${enqueued.get()} coalesced=${coalesced.get()} pending=${pending.size} backoff{" +
            backoff.entries.sortedBy { it.key }.joinToString(",") { (k, b) ->
                "$k:" + if (b.disabledCode != 0) "disabled${b.disabledCode}" else "level=${b.level},cooldowns=${b.cooldowns.get()},leftMs=${(b.untilMs - nowMs).coerceAtLeast(0L)}"
            }.ifBlank { "-" } + "}]"

    internal fun resetForTests8285() { pending.clear(); backoff.clear(); coalesced.set(0L); enqueued.set(0L) }
}

/**
 * V5.0.8285 — directive 8282 P5: per missing feature and source, how often a source was asked, returned evidence,
 * returned something invalid, was throttled (our gate or its cooldown), and repaired the feature. One helper line.
 */
object FeatureSourceCounters8285 {
    const val ATTEMPT = "attempt"
    const val RETURNED = "returned"
    const val INVALID = "invalid"
    const val THROTTLED = "throttled"
    const val REPAIRED = "repaired"
    private val OUTCOMES = listOf(ATTEMPT, RETURNED, INVALID, THROTTLED, REPAIRED)
    private val counts = ConcurrentHashMap<String, AtomicLong>()

    fun note8285(feature: String, source: String, outcome: String) {
        if (outcome !in OUTCOMES) return
        val k = "${feature.trim().lowercase().ifBlank { "?" }}|${source.trim().ifBlank { "?" }}|$outcome"
        if (counts.size >= 512 && !counts.containsKey(k)) return
        counts.computeIfAbsent(k) { AtomicLong(0L) }.incrementAndGet()
    }

    fun count8285(feature: String, source: String, outcome: String): Long = counts["${feature.lowercase()}|$source|$outcome"]?.get() ?: 0L

    /** featureSources8285[bp{dexBatch:a=4,r=3,i=0,t=1,rep=2} ...] */
    fun line8285(): String {
        val byFeature = counts.keys.map { it.split('|') }.filter { it.size == 3 }.groupBy { it[0] }
        val body = byFeature.keys.sorted().joinToString(" ") { f ->
            val sources = byFeature[f].orEmpty().map { it[1] }.distinct().sorted()
            f + "{" + sources.joinToString(",") { s ->
                "$s:a=${count8285(f, s, ATTEMPT)},r=${count8285(f, s, RETURNED)},i=${count8285(f, s, INVALID)}," +
                    "t=${count8285(f, s, THROTTLED)},rep=${count8285(f, s, REPAIRED)}"
            } + "}"
        }
        return "featureSources8285[" + body.ifBlank { "-" } + "]"
    }

    internal fun resetForTests8285() { counts.clear() }
}
