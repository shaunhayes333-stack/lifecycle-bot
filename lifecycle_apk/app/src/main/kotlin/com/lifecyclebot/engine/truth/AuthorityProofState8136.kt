package com.lifecyclebot.engine.truth

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8136 — the mint-authority proof as an explicit state with freshness.
 *
 * 8127: live buys refused FREEZE_AUTHORITY_UNVERIFIED because the proof was first asked at the pre-broadcast gate,
 * synchronously, while scan, gate and crypto callers raced each other to the same RPCs. The proof had no state: a
 * `null` meant "never asked", "asked and nobody answered" and "the answer is old" all at once.
 *
 *   PROVEN_CLEAN    freeze revoked and no hostile Token-2022 extension. Reusable forever ONLY when immutable: an SPL
 *                   mint/freeze authority set to null can never be set again, but a Token-2022 transfer-fee or
 *                   transfer-hook authority that is still live can turn a clean mint hostile later, so that proof is
 *                   reused for [CLEAN_MUTABLE_TTL_MS_8136] and then asked again.
 *   PROVEN_HOSTILE  freeze authority live or a hostile extension; cached [HOSTILE_TTL_MS_8136] (an authority may still
 *                   be revoked), then STALE.
 *   UNANSWERED      no provider answered. Never executable; the async prefetch retries on a bounded ladder.
 *   STALE           a proof older than its TTL. Never executable; it is asked again.
 *   CONTRADICTED    an answer that is impossible given an earlier immutable-clean proof (a revoked authority came back).
 *                   Never executable and never cached clean; the next answer decides.
 *
 * Only PROVEN_CLEAN is executable. Pure apart from the bounded record map.
 */
object AuthorityProofState8136 {

    enum class State { PROVEN_CLEAN, PROVEN_HOSTILE, UNANSWERED, STALE, CONTRADICTED }

    data class Record(
        val state: State,
        val freezeAuthorityDisabled: Boolean?,
        val mintAuthorityDisabled: Boolean?,
        val hostileExtension: String?,
        val mutableExtension: String?,
        val provider: String,
        val atMs: Long,
    ) {
        /** Clean and nothing on the mint can ever change that. */
        val immutableClean: Boolean
            get() = state == State.PROVEN_CLEAN && freezeAuthorityDisabled == true && mintAuthorityDisabled == true &&
                hostileExtension == null && mutableExtension == null
    }

    const val CLEAN_MUTABLE_TTL_MS_8136 = 60_000L
    const val HOSTILE_TTL_MS_8136 = 5 * 60_000L
    private const val RECORD_CAP_8136 = 20_000

    /** Pure: the state one chain answer proves on its own. */
    fun classify8136(freezeDisabled: Boolean, hostileExtension: String?): State =
        if (!freezeDisabled || hostileExtension != null) State.PROVEN_HOSTILE else State.PROVEN_CLEAN

    /** Pure: the record for a fresh chain answer, given what was known before. */
    fun merge8136(
        prev: Record?,
        freezeDisabled: Boolean,
        mintDisabled: Boolean,
        hostileExtension: String?,
        mutableExtension: String?,
        provider: String,
        nowMs: Long,
    ): Record {
        val fresh = classify8136(freezeDisabled, hostileExtension)
        // A revoked SPL authority cannot come back: an immutable-clean proof followed by a live authority is a
        // provider/account disagreement, not a state change. Block this decision; the next answer decides.
        val contradicted = prev != null && prev.immutableClean && (!freezeDisabled || !mintDisabled)
        val state = if (contradicted) State.CONTRADICTED else fresh
        return Record(state, freezeDisabled, mintDisabled, hostileExtension, mutableExtension, provider, nowMs)
    }

    /** Pure: how long a record may be reused without asking the chain again (0 = never). */
    fun ttlMs8136(r: Record): Long = when (r.state) {
        State.PROVEN_CLEAN -> if (r.immutableClean) Long.MAX_VALUE else CLEAN_MUTABLE_TTL_MS_8136
        State.PROVEN_HOSTILE -> HOSTILE_TTL_MS_8136
        State.UNANSWERED, State.STALE, State.CONTRADICTED -> 0L
    }

    /** Pure: the state a caller must act on now (no record = UNANSWERED, an expired proof = STALE). */
    fun effective8136(r: Record?, nowMs: Long): State {
        if (r == null) return State.UNANSWERED
        if (r.state != State.PROVEN_CLEAN && r.state != State.PROVEN_HOSTILE) return r.state
        val ttl = ttlMs8136(r)
        if (ttl == Long.MAX_VALUE) return r.state
        return if (nowMs - r.atMs < ttl) r.state else State.STALE
    }

    /** Pure: may a live buy proceed on this state? Only a fresh clean proof. */
    fun executable8136(state: State): Boolean = state == State.PROVEN_CLEAN

    /** Pure: must the chain be asked again before acting? */
    fun mustAsk8136(state: State): Boolean = state != State.PROVEN_CLEAN && state != State.PROVEN_HOSTILE

    // ── bounded async retry for unanswered proofs ──
    private val RETRY_LADDER_MS_8136 = longArrayOf(0L, 3_000L, 10_000L, 30_000L, 60_000L)
    const val MAX_ATTEMPTS_PER_WINDOW_8136 = 6
    const val RETRY_WINDOW_MS_8136 = 10 * 60_000L

    /**
     * Pure: is another background ask due? [attempts] asks have been made in the current window, the last at
     * [lastAttemptAtMs], the window opened at [windowStartMs]. Bounded: at most [MAX_ATTEMPTS_PER_WINDOW_8136] per window.
     */
    fun retryDue8136(attempts: Int, lastAttemptAtMs: Long, windowStartMs: Long, nowMs: Long): Boolean {
        if (attempts <= 0) return true
        if (nowMs - windowStartMs >= RETRY_WINDOW_MS_8136) return true
        if (attempts >= MAX_ATTEMPTS_PER_WINDOW_8136) return false
        val wait = RETRY_LADDER_MS_8136[attempts.coerceIn(0, RETRY_LADDER_MS_8136.lastIndex)]
        return nowMs - lastAttemptAtMs >= wait
    }

    // ── record store (bounded) ──
    private val records = ConcurrentHashMap<String, Record>()
    private val contradictions = AtomicLong(0L)

    fun record8136(mint: String): Record? = if (mint.isBlank()) null else records[mint]

    fun stateFor8136(mint: String, nowMs: Long = System.currentTimeMillis()): State = effective8136(record8136(mint), nowMs)

    /** A chain answer arrived for [mint]; returns the stored record (CONTRADICTED when it must not be trusted). */
    fun observe8136(
        mint: String,
        freezeDisabled: Boolean,
        mintDisabled: Boolean,
        hostileExtension: String?,
        mutableExtension: String?,
        provider: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Record {
        val rec = merge8136(records[mint], freezeDisabled, mintDisabled, hostileExtension, mutableExtension, provider, nowMs)
        if (rec.state == State.CONTRADICTED) contradictions.incrementAndGet()
        if (records.size >= RECORD_CAP_8136) trim8136(RECORD_CAP_8136 / 2, nowMs)
        if (mint.isNotBlank()) records[mint] = rec
        return rec
    }

    /** No provider answered for [mint]. A live proof is kept (it is still valid for its TTL); anything else reads UNANSWERED. */
    fun observeUnanswered8136(mint: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        val prev = records[mint]
        if (prev != null && effective8136(prev, nowMs).let { it == State.PROVEN_CLEAN || it == State.PROVEN_HOSTILE }) return
        if (records.size >= RECORD_CAP_8136) trim8136(RECORD_CAP_8136 / 2, nowMs)
        records[mint] = Record(State.UNANSWERED, null, null, null, null, "none", nowMs)
    }

    /** Memory pressure: keep immutable-clean and fresh records first, at most [keep]. */
    fun trim8136(keep: Int, nowMs: Long = System.currentTimeMillis()): Int {
        val before = records.size
        records.entries.removeIf { !it.value.immutableClean && effective8136(it.value, nowMs) != it.value.state }
        if (records.size > keep) {
            records.entries.sortedBy { it.value.atMs }.take(records.size - keep).forEach { records.remove(it.key, it.value) }
        }
        return (before - records.size).coerceAtLeast(0)
    }

    fun size8136(): Int = records.size
    fun contradictions8136(): Long = contradictions.get()

    internal fun clearForTest8136() { records.clear(); contradictions.set(0L) }
}

/**
 * V5.0.8136 — one in-flight computation per key. Concurrent callers for the same key wait (bounded) for the leader's
 * result instead of putting a second identical request on the wire. Pure JVM.
 */
class SingleFlight8136<V : Any> {
    data class Outcome<V : Any>(val value: V?, val joined: Boolean)

    private val inflight = ConcurrentHashMap<String, CompletableFuture<V?>>()
    private val leads = AtomicLong(0L)
    private val joins = AtomicLong(0L)

    fun run(key: String, joinTimeoutMs: Long, block: () -> V?): Outcome<V> {
        val mine = CompletableFuture<V?>()
        val existing = inflight.putIfAbsent(key, mine)
        if (existing != null) {
            joins.incrementAndGet()
            val v = try { existing.get(joinTimeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS) } catch (_: Throwable) { null }
            return Outcome(v, joined = true)
        }
        leads.incrementAndGet()
        var v: V? = null
        try {
            v = try { block() } catch (_: Throwable) { null }
        } finally {
            mine.complete(v)
            inflight.remove(key, mine)
        }
        return Outcome(v, joined = false)
    }

    fun inFlight(key: String): Boolean = inflight.containsKey(key)
    fun leads(): Long = leads.get()
    fun joins(): Long = joins.get()
}

/**
 * V5.0.8136 — mints that became credible admission prospects (a proof prefetch was asked for them). The memory guard
 * protects these rows under pressure: freeing a candidate the gate is about to execute only re-fetches it.
 */
object AdmissionProspect8136 {
    const val ACTIVE_MS_8136 = 5 * 60_000L
    private const val CAP_8136 = 2_000
    private val notedAt = ConcurrentHashMap<String, Long>()

    fun note8136(mint: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        notedAt[mint] = nowMs
        if (notedAt.size > CAP_8136) notedAt.entries.removeIf { nowMs - it.value >= ACTIVE_MS_8136 }
        if (notedAt.size > CAP_8136) notedAt.entries.sortedBy { it.value }.take(notedAt.size - CAP_8136).forEach { notedAt.remove(it.key, it.value) }
    }

    fun isActive8136(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = notedAt[mint] ?: return false
        return nowMs - at < ACTIVE_MS_8136
    }

    fun size8136(): Int = notedAt.size

    internal fun clearForTest8136() = notedAt.clear()
}
