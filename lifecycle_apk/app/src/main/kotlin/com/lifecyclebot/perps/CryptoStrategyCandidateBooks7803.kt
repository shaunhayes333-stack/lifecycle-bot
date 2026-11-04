package com.lifecyclebot.perps

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7803 — resident Crypto Universe strategy books.
 *
 * Crypto discovery is shared. Strategy ownership is not. MOMENTUM, PULLBACK,
 * BREAKOUT, MEAN_REVERT, LAB_PROPOSED and applicable specialist-desk overlays
 * may watch the same asset independently. Only READY proposals are reduced to
 * one executable signal for the canonical CRYPTO_ALT/CRYPTO_SPOT pipeline.
 */
internal object CryptoStrategyCandidateBooks7803 {
    internal enum class State { WATCHING, QUALIFIED, READY, LOST, EXPIRED }

    internal data class Entry(
        val assetKey: String,
        val symbol: String,
        val strategy: String,
        val candidateVersion: Long,
        val state: State,
        val score: Int,
        val confidence: Int,
        val conviction: Double,
        val reason: String,
        val firstSeenMs: Long,
        val updatedAtMs: Long,
    )

    private const val WATCH_TTL_MS = 30L * 60_000L
    private const val QUALIFIED_TTL_MS = 5L * 60_000L
    private const val READY_TTL_MS = 45_000L
    private const val MAX_PER_STRATEGY = 512

    private val books = ConcurrentHashMap<String, ConcurrentHashMap<String, Entry>>()

    private fun key(raw: String): String = raw.trim().uppercase().replace(' ', '_')
    private fun ttl(state: State): Long = when (state) {
        State.READY -> READY_TTL_MS
        State.QUALIFIED -> QUALIFIED_TTL_MS
        State.WATCHING -> WATCH_TTL_MS
        State.LOST, State.EXPIRED -> 5_000L
    }

    private fun prune(strategy: String, now: Long = System.currentTimeMillis()) {
        val b = books[strategy] ?: return
        b.entries.removeIf { now - it.value.updatedAtMs > ttl(it.value.state) }
        if (b.size > MAX_PER_STRATEGY) {
            b.values.sortedByDescending { it.updatedAtMs }.drop(MAX_PER_STRATEGY)
                .forEach { b.remove(it.assetKey, it) }
        }
    }

    private fun put(
        assetKey: String,
        symbol: String,
        strategyRaw: String,
        candidateVersion: Long,
        state: State,
        score: Int,
        confidence: Int,
        reason: String,
    ): Entry? {
        if (assetKey.isBlank() || strategyRaw.isBlank()) return null
        val strategy = key(strategyRaw)
        val now = System.currentTimeMillis()
        val b = books.computeIfAbsent(strategy) { ConcurrentHashMap() }
        var kept8535 = false
        val next = b.compute(assetKey) { _, old ->
            val s = score.takeIf { it >= 0 } ?: old?.score ?: -1
            val cf = confidence.takeIf { it >= 0 } ?: old?.confidence ?: -1
            val conv = if (s >= 0 && cf >= 0) s.coerceIn(0,100) * 0.60 + cf.coerceIn(0,100) * 0.40
                else old?.conviction ?: 0.0
            // V5.0.8535 §C06 — a WATCH / QUALIFY of the same revision never demotes a live READY (transition8535).
            val t = transition8535(old, state, candidateVersion, now)
            kept8535 = old != null && t.first == State.READY && state != State.READY
            Entry(
                assetKey = assetKey,
                symbol = symbol.ifBlank { old?.symbol.orEmpty() },
                strategy = strategy,
                candidateVersion = t.second,
                state = t.first,
                score = s,
                confidence = cf,
                conviction = conv,
                reason = if (kept8535) old!!.reason else reason.ifBlank { old?.reason.orEmpty() },
                firstSeenMs = old?.firstSeenMs ?: now,
                updatedAtMs = if (kept8535) old!!.updatedAtMs else now,   // a kept READY keeps its own clock; it is not refreshed by a watch tick
            )
        } ?: return null
        if (kept8535) try { PipelineHealthCollector.labelInc("CRYPTO_RESIDENT_READY_KEPT_8535") } catch (_: Throwable) {}
        prune(strategy, now)
        try {
            PipelineHealthCollector.labelInc("CRYPTO_RESIDENT_${state.name}_7803")
            PipelineHealthCollector.labelInc("CRYPTO_RESIDENT_${state.name}_7803_$strategy")
        } catch (_: Throwable) {}
        return next
    }

    /**
     * Pure (V5.0.8535 §C06): the state and revision an update stores. A LOST / EXPIRED / READY update always applies. A
     * WATCHING / QUALIFIED update for the SAME revision (or with no revision) of a READY entry still inside its TTL keeps
     * READY and the entry's revision — a lower-confidence lifecycle tick cannot erase an executable signal; a different
     * revision (the inputs changed) applies as given. superseded: `state = state` unconditionally in put().
     */
    internal fun transition8535(old: Entry?, incoming: State, incomingVersion: Long, nowMs: Long, readyTtlMs: Long = READY_TTL_MS): Pair<State, Long> {
        val version = incomingVersion.takeIf { it > 0L } ?: old?.candidateVersion ?: 0L
        if (old == null) return incoming to version
        if (incoming == State.LOST || incoming == State.EXPIRED || incoming == State.READY) return incoming to version
        val readyLive = old.state == State.READY && nowMs - old.updatedAtMs in 0L..readyTtlMs
        val sameRevision = incomingVersion <= 0L || old.candidateVersion <= 0L || incomingVersion == old.candidateVersion
        return if (readyLive && sameRevision) State.READY to old.candidateVersion else incoming to version
    }

    internal fun watch(assetKey:String,symbol:String,strategy:String,reason:String="DISCOVERY") =
        put(assetKey,symbol,strategy,0L,State.WATCHING,-1,-1,reason)

    internal fun qualify(assetKey:String,symbol:String,strategy:String,candidateVersion:Long,score:Int,confidence:Int,reason:String) =
        put(assetKey,symbol,strategy,candidateVersion,State.QUALIFIED,score,confidence,reason)

    internal fun ready(assetKey:String,symbol:String,strategy:String,candidateVersion:Long,score:Int,confidence:Int,reason:String) =
        put(assetKey,symbol,strategy,candidateVersion,State.READY,score,confidence,reason)

    internal fun lose(assetKey:String,strategy:String,candidateVersion:Long,reason:String) {
        val k=key(strategy); val cur=books[k]?.get(assetKey) ?: return
        if(candidateVersion>0L && cur.candidateVersion>0L && candidateVersion!=cur.candidateVersion) return
        put(assetKey,cur.symbol,k,cur.candidateVersion,State.LOST,cur.score,cur.confidence,reason)
    }

    internal fun readyFor(assetKey:String,candidateVersion:Long): List<Entry> =
        books.keys.mapNotNull { strategy ->
            prune(strategy)
            books[strategy]?.get(assetKey)?.takeIf { it.state==State.READY && it.candidateVersion==candidateVersion }
        }.sortedByDescending { it.conviction }

    internal fun qualifiedFor(assetKey:String,candidateVersion:Long): List<Entry> =
        books.keys.mapNotNull { strategy ->
            prune(strategy)
            books[strategy]?.get(assetKey)?.takeIf {
                it.candidateVersion==candidateVersion && (it.state==State.QUALIFIED || it.state==State.READY)
            }
        }.sortedByDescending { it.conviction }

    internal fun bestReady(assetKey:String,candidateVersion:Long): Entry? = readyFor(assetKey,candidateVersion).firstOrNull()

    /**
     * V5.0.7823 — resident discovery must be consumed by CryptoAltTrader, not
     * merely displayed in status. Return one deduped priority row per asset
     * across every crypto-native strategy + desk overlay.
     *
     * READY/QUALIFIED outrank WATCHING, then conviction and recency decide.
     * This is scan scheduling only; it grants no FDG/route/execution authority.
     */
    internal fun priorityAssets7823(limit:Int = 512): List<Entry> {
        val now = System.currentTimeMillis()
        val all = books.keys.flatMap { strategy ->
            prune(strategy, now)
            books[strategy]?.values?.toList().orEmpty()
        }
        fun stateRank7823(s:State):Int = when(s) {
            State.READY -> 3
            State.QUALIFIED -> 2
            State.WATCHING -> 1
            State.LOST, State.EXPIRED -> 0
        }
        return all
            .filter { stateRank7823(it.state) > 0 }
            .groupBy { it.assetKey }
            .mapNotNull { (_, rows) ->
                rows.maxWithOrNull(
                    compareBy<Entry> { stateRank7823(it.state) }
                        .thenBy { it.conviction }
                        .thenBy { it.updatedAtMs }
                )
            }
            .sortedWith(
                compareByDescending<Entry> { stateRank7823(it.state) }
                    .thenByDescending { it.conviction }
                    .thenByDescending { it.updatedAtMs }
            )
            .take(limit.coerceAtLeast(1))
    }

    internal fun statusLine(): String = books.keys.sorted().joinToString(" · ") { s ->
        prune(s); val rows=books[s]?.values.orEmpty()
        "$s[resident=${rows.size} q=${rows.count{it.state==State.QUALIFIED}} ready=${rows.count{it.state==State.READY}}]"
    }

    internal fun resetForTests(){books.clear()}
}
