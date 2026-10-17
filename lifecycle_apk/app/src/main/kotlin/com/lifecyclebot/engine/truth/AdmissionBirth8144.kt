package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8144 §UNKNOWN_IS_NOT_OLD_AND_OLD_IS_NOT_UNKNOWN.
 *
 * 5.0.8142: PROJECT_SNIPER refused 2,015 times as BIRTH_METADATA_HYDRATING and qualified zero. A launch age had one
 * resolved-or-nothing answer (CanonicalTokenBirthTime7440), so every mint whose chain birth had not landed read
 * "hydrating" for ever, whether it was a 40-second launch whose create frame the throttle dropped or a three-day-old
 * token with 60,000 signatures that the RPC pager (TokenBirthHydrator7441) gave up on at its page budget.
 *
 * A birth is now read from every witness, with what each one proves:
 *  - KNOWN: a direct witness of the creation — the PumpPortal create frame (any frame, before the create throttle),
 *    pump.fun's own coin record (created_timestamp), the first pool / pair creation, the mint's oldest successful
 *    signature with its history read to the end, or the persisted token-meta copy of one of those. Earliest wins.
 *  - PROVEN_OLDER_THAN: no direct witness, but the token provably existed at some time T — a successful signature seen
 *    while paging back through its history, or the bot's own first sighting of it. Its age is at least now - T, so a
 *    launch window it is already past is a REFUSE (old), never a "hydrating" defer.
 *  - UNKNOWN: nothing observed. Unknown is never fresh and never old: launch lanes DEFER.
 * Nothing is estimated: a lower bound is reported as a lower bound and never as the birth.
 */
object AdmissionBirth8144 {

    enum class Kind8144 { KNOWN, PROVEN_OLDER_THAN, UNKNOWN }
    enum class Window8144 { IN_WINDOW, PAST_WINDOW, UNKNOWN_DEFER }

    /** Every birth witness for one mint at decision time (epoch ms); 0 = not observed. */
    data class Witness8144(
        val createFrameMs: Long = 0L,
        val pumpRecordMs: Long = 0L,
        val pairCreatedMs: Long = 0L,
        val oldestSignatureMs: Long = 0L,
        val metaCreationMs: Long = 0L,
        /** Oldest successful signature seen while the history was NOT yet read to its end (a lower bound only). */
        val historyFloorMs: Long = 0L,
        /** When this bot first saw the mint (a lower bound only: nothing is seen before it exists). */
        val firstSeenMs: Long = 0L,
        /**
         * V5.0.8169 — the creation time of a pool that is NOT the launch itself (a graduated PumpSwap / Raydium pair, any
         * DexScreener best pair other than a pump mint's own bonding curve): the token existed then, so a lower bound only.
         */
        val poolFloorMs: Long = 0L,
        /** V5.0.8179 — the block time of a confirmed transaction that initialised this mint (ChainLaunchFeed8179): its birth. */
        val chainMintInitMs: Long = 0L,
    )

    /** [birthMs] is set only for KNOWN; [minAgeMs] is the age for KNOWN and the proven lower bound for PROVEN_OLDER_THAN. */
    data class Read8144(val kind: Kind8144, val birthMs: Long, val minAgeMs: Long, val source: String)

    internal const val MIN_VALID_MS_8144 = 1_577_836_800_000L
    private const val FUTURE_SLACK_MS_8144 = 60_000L

    internal fun plausible8144(ms: Long, nowMs: Long): Boolean = ms >= MIN_VALID_MS_8144 && ms <= nowMs + FUTURE_SLACK_MS_8144

    /** Pure: pump.fun's created_timestamp arrives in seconds on some endpoints and in ms on others. */
    fun epochMs8144(raw: Long): Long = when {
        raw <= 0L -> 0L
        raw < 100_000_000_000L -> raw * 1000L
        else -> raw
    }

    /** Pure: the birth the witnesses support at [nowMs] (direct witnesses first, earliest wins; then lower bounds). */
    fun read8144(w: Witness8144, nowMs: Long): Read8144 {
        val direct = listOf(
            "CREATE_FRAME" to w.createFrameMs,
            "PUMP_COIN_RECORD" to w.pumpRecordMs,
            "PAIR_CREATION" to w.pairCreatedMs,
            "OLDEST_SIGNATURE" to w.oldestSignatureMs,
            "TOKEN_META" to w.metaCreationMs,
            "CHAIN_MINT_INIT" to w.chainMintInitMs,   // V5.0.8179
        ).filter { plausible8144(it.second, nowMs) }
        direct.minByOrNull { it.second }?.let { (src, ms) ->
            return Read8144(Kind8144.KNOWN, ms, (nowMs - ms).coerceAtLeast(0L), src)
        }
        val floors = listOf("HISTORY_FLOOR" to w.historyFloorMs, "POOL_FLOOR" to w.poolFloorMs, "FIRST_SEEN" to w.firstSeenMs)
            .filter { plausible8144(it.second, nowMs) && it.second <= nowMs }
        floors.minByOrNull { it.second }?.let { (src, ms) ->
            return Read8144(Kind8144.PROVEN_OLDER_THAN, 0L, nowMs - ms, src)
        }
        return Read8144(Kind8144.UNKNOWN, 0L, 0L, "UNKNOWN")
    }

    /** Pure: a launch window ([windowMs]) against a birth read. A lower bound past the window is proof of age. */
    fun window8144(r: Read8144, windowMs: Long): Window8144 = when (r.kind) {
        Kind8144.KNOWN -> if (r.minAgeMs > windowMs) Window8144.PAST_WINDOW else Window8144.IN_WINDOW
        Kind8144.PROVEN_OLDER_THAN -> if (r.minAgeMs > windowMs) Window8144.PAST_WINDOW else Window8144.UNKNOWN_DEFER
        Kind8144.UNKNOWN -> Window8144.UNKNOWN_DEFER
    }

    // ── runtime witnesses (bounded, earliest kept) ──

    private const val CAP_8144 = 8_000
    private val createFrames = ConcurrentHashMap<String, Long>()
    private val pumpRecords = ConcurrentHashMap<String, Long>()
    private val oldestSigs = ConcurrentHashMap<String, Long>()
    private val historyFloors = ConcurrentHashMap<String, Long>()
    private val reads = ConcurrentHashMap<String, AtomicLong>()
    // V5.0.8169 — DexScreener pair creation, already on the wire (best pair, sweep enrichment) and dropped: a pump mint's
    // own bonding-curve pair is its create event (direct); any other pool only proves the token existed then (a floor).
    private val curvePairs8169 = ConcurrentHashMap<String, Long>()
    private val poolFloors8169 = ConcurrentHashMap<String, Long>()
    // V5.0.8179 — mints whose initialisation the chain launch feed saw in a confirmed pool-creation transaction.
    private val chainMintInits8179 = ConcurrentHashMap<String, Long>()
    // V5.0.8169 — distinct mints a launch lane had to defer as UNKNOWN, keyed by feed and pump suffix.
    private val unknownMints8169 = ConcurrentHashMap<String, String>()

    private fun putEarliest8144(map: ConcurrentHashMap<String, Long>, mint: String, ms: Long) {
        val m = mint.trim()
        if (m.length !in 32..44 || !plausible8144(ms, System.currentTimeMillis())) return
        if (map.size >= CAP_8144 && !map.containsKey(m)) {
            // The oldest births matter least to a launch window; an evicted one is re-proven old by its history floor.
            try { map.entries.sortedBy { it.value }.take(CAP_8144 / 10).forEach { map.remove(it.key) } } catch (_: Throwable) {}
        }
        map.merge(m, ms) { a, b -> minOf(a, b) }
    }

    private fun count8144(label: String) {
        reads.computeIfAbsent(label) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {}
    }

    /** PumpFunWS: a create frame arrived for [mint] (recorded before the create throttle, with or without reserves). */
    fun noteCreateFrame8144(mint: String, atMs: Long = System.currentTimeMillis()) = putEarliest8144(createFrames, mint, atMs)

    /** pump.fun coin record (mark fan-out, new-coin scan, birth hydrator): its created_timestamp is the create event. */
    fun notePumpCoinRecord8144(mint: String, createdTimestampRaw: Long) {
        val ms = epochMs8144(createdTimestampRaw)
        if (ms > 0L) putEarliest8144(pumpRecords, mint, ms)
    }

    /** TokenBirthHydrator7441: the mint's history was read to its end; its oldest successful signature is the birth. */
    fun noteOldestSignature8144(mint: String, ms: Long) = putEarliest8144(oldestSigs, mint, ms)

    /** TokenBirthHydrator7441: a successful signature seen while paging back (history not yet complete): a lower bound. */
    fun noteHistoryFloor8144(mint: String, ms: Long) = putEarliest8144(historyFloors, mint, ms)

    /**
     * V5.0.8169 — DexscreenerApi.parsePair / MarketSweep7297 enrichment: a pair for [mint] created at [createdMs]. The
     * pump.fun bonding-curve pair of a pump mint is the create event (a direct witness, PAIR_CREATION); every other pool
     * is a lower bound of the age (POOL_FLOOR) — never read as the birth, so a graduated pool never makes a token young.
     */
    fun notePairCreated8169(mint: String, dexId: String?, createdMs: Long) {
        when (LaunchBirth8169.pairProof8169(mint, dexId.orEmpty(), createdMs, System.currentTimeMillis())) {
            LaunchBirth8169.PairProof8169.BIRTH -> putEarliest8144(curvePairs8169, mint, createdMs)
            LaunchBirth8169.PairProof8169.FLOOR -> putEarliest8144(poolFloors8169, mint, createdMs)
            LaunchBirth8169.PairProof8169.NONE -> {}
        }
    }

    /**
     * V5.0.8179 — ChainLaunchFeed8179: a confirmed transaction at [blockTimeMs] initialised [mint] (spl-token /
     * token-2022 initializeMint in the same transaction that created its pool). A direct witness of the creation; a pool
     * created for an already existing mint is never recorded here (that is a floor, [notePairCreated8169]).
     */
    fun noteChainMintInit8179(mint: String, blockTimeMs: Long) = putEarliest8144(chainMintInits8179, mint, blockTimeMs)

    internal fun chainMintInitMs8179(mint: String): Long? = chainMintInits8179[mint.trim()]

    /** V5.0.8169 — the direct create witnesses held here, each by name (LaunchBirth8169.launchBirth8169 reads them). */
    internal fun createFrameMs8169(mint: String): Long? = createFrames[mint.trim()]
    internal fun pumpRecordMs8169(mint: String): Long? = pumpRecords[mint.trim()]
    internal fun curvePairMs8169(mint: String): Long? = curvePairs8169[mint.trim()]
    internal fun oldestSignatureMs8169(mint: String): Long? = oldestSigs[mint.trim()]

    /** CanonicalTokenBirthTime7440: the pump create event witnessed here (create frame or pump.fun coin record), or null. */
    fun pumpCreateMs8144(mint: String): Long? {
        val m = mint.trim()
        return listOfNotNull(createFrames[m], pumpRecords[m]).minOrNull()
    }

    /** The witnesses this object holds for [ts] (the canonical resolver's own witnesses are read by it, not here). */
    internal fun witnessFor8144(ts: TokenState): Witness8144 {
        val m = ts.mint.trim()
        return Witness8144(
            createFrameMs = createFrames[m] ?: 0L,
            pumpRecordMs = pumpRecords[m] ?: 0L,
            oldestSignatureMs = oldestSigs[m] ?: 0L,
            historyFloorMs = historyFloors[m] ?: 0L,
            firstSeenMs = ts.addedToWatchlistAt,
            pairCreatedMs = curvePairs8169[m] ?: 0L,   // V5.0.8169 — a pump mint's own bonding-curve pair (direct)
            poolFloorMs = poolFloors8169[m] ?: 0L,      // V5.0.8169 — any other pool (lower bound)
            chainMintInitMs = chainMintInits8179[m] ?: 0L,   // V5.0.8179 — mint initialised on chain (direct)
        )
    }

    fun readFor8144(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Read8144 = read8144(witnessFor8144(ts), nowMs)

    /**
     * ProjectSniperAI / launch lanes, when the canonical birth is unresolved: the age to judge the launch window on —
     * the real age when a witness here knows the birth, the proven lower bound when it is already past [windowMs] (the
     * token is old and is handed on, not left "hydrating"), else null (unknown: defer).
     */
    fun provenAgeMs8144(ts: TokenState, windowMs: Long, nowMs: Long = System.currentTimeMillis()): Long? {
        val r = readFor8144(ts, nowMs)
        return when (window8144(r, windowMs)) {
            Window8144.IN_WINDOW -> { count8144("BIRTH_8144_KNOWN_${r.source}"); r.minAgeMs }
            Window8144.PAST_WINDOW -> { count8144("BIRTH_8144_PROVEN_PAST_WINDOW_${r.source}"); r.minAgeMs }
            Window8144.UNKNOWN_DEFER -> { count8144("BIRTH_8144_UNKNOWN_DEFER"); noteUnknown8169(ts); null }
        }
    }

    /** V5.0.8169 — one distinct UNKNOWN mint, keyed by its feed and pump suffix (bounded; the first sighting names it). */
    private fun noteUnknown8169(ts: TokenState) {
        val m = ts.mint.trim()
        if (m.isBlank() || unknownMints8169.containsKey(m)) return
        if (unknownMints8169.size >= CAP_8144) unknownMints8169.clear()
        unknownMints8169[m] = LaunchBirth8169.unknownKey8169(m, ts.source)
    }

    /** The proven lower bound of [ts]'s age (ms) from this object's witnesses, or null when nothing proves one. */
    fun provenMinAgeMs8144(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Long? =
        readFor8144(ts, nowMs).takeIf { it.kind != Kind8144.UNKNOWN }?.minAgeMs

    fun statusLine8144(): String =
        "birth8144[frames=${createFrames.size} pumpRecords=${pumpRecords.size} oldestSig=${oldestSigs.size} floors=${historyFloors.size} " +
            "reads{${reads.entries.sortedByDescending { it.value.get() }.take(6).joinToString(",") { "${it.key.removePrefix("BIRTH_8144_")}=${it.value.get()}" }.ifBlank { "-" }}} " +
            // V5.0.8169 — the pair witnesses, the distinct birthless mints by feed, and the sniper's cheap age pre-filter.
            "curvePairs8169=${curvePairs8169.size} poolFloors8169=${poolFloors8169.size} chainMintInits8179=${chainMintInits8179.size} " +
            "unknownMints8169{${LaunchBirth8169.histogram8169(unknownMints8169.values, 5)}} ${LaunchBirth8169.sniperScopeLine8169()}]"
}
