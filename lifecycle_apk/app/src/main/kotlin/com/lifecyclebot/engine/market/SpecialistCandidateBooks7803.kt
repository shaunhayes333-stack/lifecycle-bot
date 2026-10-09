package com.lifecyclebot.engine.market

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7803 — RESIDENT SPECIALIST CANDIDATE BOOKS.
 *
 * Global market discovery is shared, but specialist opportunity ownership is not.
 * Each of the canonical meme specialists owns an independent resident candidate set.
 * The same mint may legitimately be watched by several lanes at once; cross-lane
 * arbitration begins only when lanes publish READY executable proposals.
 *
 * This object is candidate-state authority only. It does not size, authorize,
 * execute or hold positions.
 */
internal object SpecialistCandidateBooks7803 {
    internal val LANES = setOf(
        "QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
        "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN",
    )

    internal enum class State { DISCOVERED, WATCHING, QUALIFIED, READY, LOST, EXPIRED }

    internal data class Entry(
        val lane: String,
        val mint: String,
        val symbol: String,
        val candidateVersion: Long,
        val state: State,
        val conviction: Double,
        val score: Int,
        val confidence: Double,
        val source: String,
        val reason: String,
        val firstSeenMs: Long,
        val updatedAtMs: Long,
    )

    private const val WATCH_TTL_MS = 30L * 60L * 1000L
    private const val QUALIFIED_TTL_MS = 5L * 60L * 1000L
    private const val READY_TTL_MS = 45_000L
    private const val MAX_PER_LANE = 256

    private val books = LANES.associateWith { ConcurrentHashMap<String, Entry>() }

    private fun canonicalLane(raw: String): String = raw.trim().uppercase()
        .replace("BLUE_CHIP", "BLUECHIP")
        .replace("SHITCOIN_EXPRESS", "EXPRESS")

    private fun ttlFor(state: State): Long = when (state) {
        State.READY -> READY_TTL_MS
        State.QUALIFIED -> QUALIFIED_TTL_MS
        State.DISCOVERED, State.WATCHING -> WATCH_TTL_MS
        State.LOST, State.EXPIRED -> 5_000L
    }

    private fun pruneLane(lane: String, now: Long = System.currentTimeMillis()) {
        val book = books[lane] ?: return
        book.entries.removeIf { now - it.value.updatedAtMs > ttlFor(it.value.state) }
        if (book.size > MAX_PER_LANE) {
            book.values.sortedByDescending { it.updatedAtMs }
                .drop(MAX_PER_LANE)
                .forEach { book.remove(it.mint, it) }
        }
    }

    private fun upsert(
        laneRaw: String,
        mint: String,
        symbol: String,
        candidateVersion: Long,
        state: State,
        conviction: Double,
        score: Int,
        confidence: Double,
        source: String,
        reason: String,
    ): Entry? {
        val lane = canonicalLane(laneRaw)
        if (lane !in LANES || mint.isBlank()) return null
        val now = System.currentTimeMillis()
        val book = books.getValue(lane)
        val next = book.compute(mint) { _, old ->
            val first = old?.firstSeenMs ?: now
            val cv = candidateVersion.takeIf { it > 0L } ?: old?.candidateVersion ?: 0L
            Entry(
                lane = lane,
                mint = mint,
                symbol = symbol.ifBlank { old?.symbol.orEmpty() },
                candidateVersion = cv,
                state = state,
                conviction = conviction.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
                    ?: old?.conviction ?: 0.0,
                score = score.takeIf { it >= 0 } ?: old?.score ?: -1,
                confidence = confidence.takeIf { it.isFinite() && it >= 0.0 }?.coerceIn(0.0, 100.0)
                    ?: old?.confidence ?: 0.0,
                source = source.ifBlank { old?.source.orEmpty() },
                reason = reason.ifBlank { old?.reason.orEmpty() },
                firstSeenMs = first,
                updatedAtMs = now,
            )
        } ?: return null
        pruneLane(lane, now)
        try {
            PipelineHealthCollector.labelInc("SPECIALIST_RESIDENT_${state.name}_7803")
            PipelineHealthCollector.labelInc("SPECIALIST_RESIDENT_${state.name}_7803_$lane")
        } catch (_: Throwable) {}
        return next
    }

    internal fun publishHunt(lane: String, mint: String, symbol: String, source: String = "LANE_HUNTER"): Entry? =
        upsert(lane, mint, symbol, 0L, State.WATCHING, 0.0, -1, -1.0, source, "hunter_resident")

    internal fun markQualified(
        lane: String,
        mint: String,
        symbol: String,
        candidateVersion: Long,
        conviction: Double,
        reason: String,
    ): Entry? = upsert(
        lane, mint, symbol, candidateVersion, State.QUALIFIED,
        conviction, conviction.toInt(), conviction, "TOOLKIT_NATIVE", reason,
    )

    internal fun markReady(
        lane: String,
        mint: String,
        symbol: String,
        candidateVersion: Long,
        score: Int,
        confidence: Double,
        reason: String,
        // V5.0.7948 — a native opinion already placed on its lane's own pass bar.
        conviction7948: Double? = null,
    ): Entry? {
        val conviction = conviction7948?.takeIf { it.isFinite() }
            ?: (score.coerceIn(0, 100) * 0.60 + confidence.coerceIn(0.0, 100.0) * 0.40)
        return upsert(
            lane, mint, symbol, candidateVersion, State.READY,
            conviction, score, confidence, "TRADE_AUTHORIZER", reason,
        )
    }

    internal fun markLost(lane: String, mint: String, candidateVersion: Long, reason: String) {
        val current = entry(lane, mint) ?: return
        if (candidateVersion > 0L && current.candidateVersion > 0L && current.candidateVersion != candidateVersion) return
        upsert(
            lane, mint, current.symbol, current.candidateVersion, State.LOST,
            current.conviction, current.score, current.confidence, current.source, reason,
        )
    }

    internal fun entry(laneRaw: String, mint: String): Entry? {
        val lane = canonicalLane(laneRaw)
        pruneLane(lane)
        return books[lane]?.get(mint)
    }

    internal fun residentLanes(mint: String): Set<String> {
        val now = System.currentTimeMillis()
        return LANES.filterTo(linkedSetOf()) { lane ->
            pruneLane(lane, now)
            books[lane]?.containsKey(mint) == true
        }
    }

    internal fun qualifiedFor(mint: String, candidateVersion: Long): Map<String, Entry> {
        val now = System.currentTimeMillis()
        return LANES.mapNotNull { lane ->
            pruneLane(lane, now)
            val e = books[lane]?.get(mint) ?: return@mapNotNull null
            if (e.candidateVersion == candidateVersion &&
                (e.state == State.QUALIFIED || e.state == State.READY)
            ) lane to e else null
        }.toMap()
    }

    internal fun readyFor(mint: String, candidateVersion: Long): Map<String, Entry> {
        val now = System.currentTimeMillis()
        return LANES.mapNotNull { lane ->
            pruneLane(lane, now)
            val e = books[lane]?.get(mint) ?: return@mapNotNull null
            if (e.candidateVersion == candidateVersion && e.state == State.READY) lane to e else null
        }.toMap()
    }

    internal fun snapshot(laneRaw: String): List<Entry> {
        val lane = canonicalLane(laneRaw)
        pruneLane(lane)
        return books[lane]?.values?.sortedByDescending { it.updatedAtMs }.orEmpty()
    }

    internal fun statusLine(): String = LANES.joinToString(" · ") { lane ->
        pruneLane(lane)
        val rows = books[lane]?.values.orEmpty()
        val ready = rows.count { it.state == State.READY }
        val qual = rows.count { it.state == State.QUALIFIED }
        "$lane[resident=${rows.size} qualified=$qual ready=$ready]"
    }

    // ── V5.0.7809 — resident candidate → canonical outcome (Field Manual L356) ──
    //
    // The books produced candidates but nothing ever told a lane how the
    // positions it owned settled. At the canonical OPEN the OWNER lane's resident
    // entry (if that lane really held the mint) is frozen against the positionId;
    // the clean finalized close (LaneHunter7297.onSettled, after
    // CanonicalTradeFinalizedBus6450.isCleanForLearning7807) grades that lane
    // exactly once. Other lanes that also watched the mint are not credited.
    private class Graded7809 {
        val n = java.util.concurrent.atomic.AtomicLong(0L)
        val wins = java.util.concurrent.atomic.AtomicLong(0L)
    }

    private val boundOwner7809 = ConcurrentHashMap<String, String>()
    private val graded7809 = ConcurrentHashMap<String, Graded7809>()
    private const val MAX_BOUND_7809 = 400

    internal fun bindPosition7809(positionId: String, mint: String, ownerLane: String): Boolean {
        if (positionId.isBlank() || mint.isBlank()) return false
        val lane = canonicalLane(ownerLane)
        if (lane !in LANES) return false
        books[lane]?.get(mint) ?: return false
        boundOwner7809.putIfAbsent(positionId, lane)
        if (boundOwner7809.size > MAX_BOUND_7809) {
            boundOwner7809.keys.firstOrNull { it != positionId }?.let { boundOwner7809.remove(it) }
        }
        try { PipelineHealthCollector.labelInc("SPECIALIST_RESIDENT_POSITION_BOUND_7809_$lane") } catch (_: Throwable) {}
        return true
    }

    internal fun gradeSettled7809(positionId: String, win: Boolean) {
        val lane = boundOwner7809.remove(positionId) ?: return
        val g = graded7809.getOrPut(lane) { Graded7809() }
        g.n.incrementAndGet()
        if (win) g.wins.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SPECIALIST_RESIDENT_GRADED_7809_$lane") } catch (_: Throwable) {}
    }

    internal fun gradedLine7809(): String =
        "boundOpen=${boundOwner7809.size} " + LANES.joinToString(" ") { lane ->
            val g = graded7809[lane]
            "$lane=${g?.n?.get() ?: 0}/${g?.wins?.get() ?: 0}W"
        }

    internal fun resetForTests() = books.values.forEach { it.clear() }
}
