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
        ).filter { plausible8144(it.second, nowMs) }
        direct.minByOrNull { it.second }?.let { (src, ms) ->
            return Read8144(Kind8144.KNOWN, ms, (nowMs - ms).coerceAtLeast(0L), src)
        }
        val floors = listOf("HISTORY_FLOOR" to w.historyFloorMs, "FIRST_SEEN" to w.firstSeenMs)
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
            Window8144.UNKNOWN_DEFER -> { count8144("BIRTH_8144_UNKNOWN_DEFER"); null }
        }
    }

    /** The proven lower bound of [ts]'s age (ms) from this object's witnesses, or null when nothing proves one. */
    fun provenMinAgeMs8144(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Long? =
        readFor8144(ts, nowMs).takeIf { it.kind != Kind8144.UNKNOWN }?.minAgeMs

    fun statusLine8144(): String =
        "birth8144[frames=${createFrames.size} pumpRecords=${pumpRecords.size} oldestSig=${oldestSigs.size} floors=${historyFloors.size} " +
            "reads{${reads.entries.sortedByDescending { it.value.get() }.take(6).joinToString(",") { "${it.key.removePrefix("BIRTH_8144_")}=${it.value.get()}" }.ifBlank { "-" }}}]"
}
