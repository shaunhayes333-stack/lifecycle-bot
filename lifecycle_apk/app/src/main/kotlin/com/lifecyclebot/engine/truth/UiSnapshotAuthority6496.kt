package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.BotStatus
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6496 §5 — UI SNAPSHOT AUTHORITY.
 *
 * OPERATOR MANDATE (verbatim, 6495 evidence):
 *
 *   "Main-thread problem is real:
 *      49 ANR hints
 *      max frame gap 3.4 sec
 *      accumulated main-thread stall 51 sec
 *      MainActivity.onCreate(SourceFile:62) dominates the watchdog
 *      BotStatus.getOpenPositions(SourceFile:21) appears directly
 *      in a rolling stall sample
 *
 *    The health/report UI should never enumerate/reconcile live
 *    position structures on the main thread. getOpenPositions()
 *    should read a precomputed immutable snapshot/StateFlow, not
 *    traverse or lock canonical storage during rendering."
 *
 * DESIGN
 * ──────
 * Runs a background refresh loop on `Dispatchers.Default` that
 * every `REFRESH_MS` walks `status.tokens.values`, applies the same
 * filter `BotStatus.openPositions` used to run inline, and stores
 * the result in an `AtomicReference<List<TokenState>>`.
 *
 * `BotStatus.openPositions` (the property Main-thread readers call)
 * consults `current()` first. On cache hit the read is O(1) —
 * atomic ref load. On cache miss (before first refresh, or if
 * refresh has stalled) callers fall through to the existing inline
 * filter so the API contract is preserved.
 *
 * `start(status)` is idempotent. `stop()` is called from
 * BotService.onDestroy so the background loop stops when the
 * service dies.
 */
object UiSnapshotAuthority6496 {

    private const val REFRESH_MS = 500L
    private const val STALE_TTL_MS = 5_000L

    private val cached = AtomicReference<Snapshot?>(null)
    private val refreshes = AtomicLong(0L)
    private val cacheHits = AtomicLong(0L)
    private val cacheMisses = AtomicLong(0L)
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    private data class Snapshot(val positions: List<TokenState>, val atMs: Long)

    @Synchronized
    fun start(status: BotStatus) {
        if (job?.isActive == true) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        job = s.launch {
            while (isActive) {
                try { refresh(status) } catch (_: Throwable) {}
                delay(REFRESH_MS)
            }
        }
        try {
            ForensicLogger.lifecycle("UI_SNAPSHOT_AUTHORITY_STARTED_6496", "refreshMs=$REFRESH_MS")
            PipelineHealthCollector.labelInc("UI_SNAPSHOT_AUTHORITY_STARTED_6496")
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        scope = null
    }

    private fun refresh(status: BotStatus) {
        refreshes.incrementAndGet()
        val list = try {
            // Read-through-copy: ConcurrentHashMap values() is a live view,
            // but we materialise it to a fresh ArrayList before filtering so
            // downstream iteration is on an immutable snapshot.
            val values = ArrayList(status.tokens.values)
            values.filter { ts ->
                try {
                    val pos = ts.position
                    if (com.lifecyclebot.engine.PositionCloseLedger.isClosed(ts.mint)) return@filter false
                    // V5.0.6636 — qtyToken alone is not OPEN authority. Feed
                    // the UI only projections that match an economically valid
                    // canonical lot and its immutable BUY snapshot.
                    QuantityInvariantAuthority6500.isRuntimeOpenEligible6636(ts.mint, pos)
                } catch (_: Throwable) { false }
            }
        } catch (_: Throwable) { emptyList() }
        cached.set(Snapshot(list, System.currentTimeMillis()))
    }

    /**
     * Fast path used by `BotStatus.openPositions`. Returns the cached
     * snapshot when fresh; null when the authority is not started or
     * the cache is stale (caller falls through to inline filter).
     */
    fun current(): List<TokenState>? {
        val snap = cached.get()
        if (snap == null) {
            cacheMisses.incrementAndGet()
            return null
        }
        if (System.currentTimeMillis() - snap.atMs > STALE_TTL_MS) {
            cacheMisses.incrementAndGet()
            return null
        }
        cacheHits.incrementAndGet()
        return snap.positions
    }

    fun statusLine(): String =
        "refreshes=${refreshes.get()} cacheHits=${cacheHits.get()} cacheMisses=${cacheMisses.get()} " +
            "size=${cached.get()?.positions?.size ?: 0}"

    internal fun resetForTest() {
        stop()
        cached.set(null)
        refreshes.set(0L); cacheHits.set(0L); cacheMisses.set(0L)
    }
}
