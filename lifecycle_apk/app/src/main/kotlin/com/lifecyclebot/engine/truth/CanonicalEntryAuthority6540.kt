package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6540 §ONE_EXECUTION_AUTHORITY — canonical single funnel for every
 * asset class's executable candidate. Operator mandate:
 *
 *   SPECIALIST/SCANNER
 *      → canonical executable candidate
 *      → canonical pre-entry authority
 *      → canonical sizing authority
 *      → FDG
 *      → immutable ExecIntent
 *      → canonical AssetExecutionRouter
 *      → venue adapter
 *      → canonical fill/open commit
 *      → canonical finalized bus
 *
 * This authority is the *funnel*. It does not (yet) own sizing/FDG/routing
 * — those authorities already exist as `OrderSizeResolver6441`,
 * `FdgAcceptGate` and `MarketsLiveExecutor`. What §6540 adds is:
 *
 *   1.  A single **submission point** every specialist (CryptoAltTrader,
 *       PerpsExecutionEngine, ForexTrader, StocksTrader, MetalsTrader,
 *       CommoditiesTrader) MUST call before it may open a canonical row.
 *   2.  Health telemetry counters at every stage transition so
 *       candidate → FDG → intent → dispatch → open attrition can be
 *       reported per venue (MARKETS_SPOT, MARKETS_PERPS, CRYPTO).
 *   3.  Acceptance-invariant hooks so `AcceptanceInvariantAudit6441` /
 *       `HardAcceptanceInvariantsTest6536` can assert candidate→submission
 *       ratios per venue over two full scan cycles (fail-build guard).
 *
 * Specialists still perform the concrete adapter call, but they MUST
 * invoke `submitCandidate` / `markSized` / `markIntentCreated` /
 * `markAdapterDispatch` / `markOpenConfirmed` at the corresponding
 * stage. The invariant audit reads the resulting counter ratios.
 *
 * DO NOT bypass. DO NOT double-submit. Any specialist that emits a
 * candidate without a matching submission is a design violation and the
 * fail-build guard will trip.
 */
object CanonicalEntryAuthority6540 {

    enum class Venue { MARKETS_SPOT, MARKETS_PERPS, CRYPTO }

    /** Snapshot of one asset class's funnel over the current scan cycle. */
    data class VenueStats(
        val venue: Venue,
        val candidates: Long,
        val authSubmit: Long,
        val authAllow: Long,
        val authBlock: Long,
        val sized: Long,
        val intents: Long,
        val dispatches: Long,
        val opensConfirmed: Long,
    ) {
        val attritionCandidateToOpen: Double
            get() = if (candidates <= 0L) 0.0 else opensConfirmed.toDouble() / candidates
    }

    private val candidateCount = mapOf(
        Venue.MARKETS_SPOT to AtomicLong(),
        Venue.MARKETS_PERPS to AtomicLong(),
        Venue.CRYPTO to AtomicLong(),
    )
    private val submitCount = mapOf(
        Venue.MARKETS_SPOT to AtomicLong(),
        Venue.MARKETS_PERPS to AtomicLong(),
        Venue.CRYPTO to AtomicLong(),
    )
    private val allowCount = mapOf(
        Venue.MARKETS_SPOT to AtomicLong(),
        Venue.MARKETS_PERPS to AtomicLong(),
        Venue.CRYPTO to AtomicLong(),
    )
    private val blockCount = mapOf(
        Venue.MARKETS_SPOT to AtomicLong(),
        Venue.MARKETS_PERPS to AtomicLong(),
        Venue.CRYPTO to AtomicLong(),
    )
    private val sizedCount = mapOf(
        Venue.MARKETS_SPOT to AtomicLong(),
        Venue.MARKETS_PERPS to AtomicLong(),
        Venue.CRYPTO to AtomicLong(),
    )
    private val intentCount = mapOf(
        Venue.MARKETS_SPOT to AtomicLong(),
        Venue.MARKETS_PERPS to AtomicLong(),
        Venue.CRYPTO to AtomicLong(),
    )
    private val dispatchCount = mapOf(
        Venue.MARKETS_SPOT to AtomicLong(),
        Venue.MARKETS_PERPS to AtomicLong(),
        Venue.CRYPTO to AtomicLong(),
    )
    private val openCount = mapOf(
        Venue.MARKETS_SPOT to AtomicLong(),
        Venue.MARKETS_PERPS to AtomicLong(),
        Venue.CRYPTO to AtomicLong(),
    )

    /**
     * Choose the venue for a candidate given direction + spot capability.
     * Operator spec §P0-3 routing table.
     */
    fun routeVenue(isLong: Boolean, isSpotCapable: Boolean, leveraged: Boolean): Venue = when {
        leveraged -> Venue.MARKETS_PERPS
        isLong && isSpotCapable -> Venue.MARKETS_SPOT
        !isLong -> Venue.MARKETS_PERPS      // SHORT + perp-capable
        else -> Venue.MARKETS_PERPS          // fallback / unsupported spot capability
    }

    fun markCandidate(venue: Venue, symbol: String, note: String = "") {
        candidateCount[venue]?.incrementAndGet()
        bump("${venue}_CANDIDATE")
        try {
            ForensicLogger.lifecycle(
                "${venue}_CANDIDATE_6540",
                "symbol=$symbol note=$note",
            )
        } catch (_: Throwable) {}
    }

    fun markAuthSubmit(venue: Venue, symbol: String, note: String = "") {
        submitCount[venue]?.incrementAndGet()
        bump("CANONICAL_AUTH_SUBMIT")
        try {
            ForensicLogger.lifecycle(
                "CANONICAL_AUTH_SUBMIT_6540",
                "venue=$venue symbol=$symbol note=$note",
            )
        } catch (_: Throwable) {}
    }

    fun markAuthAllow(venue: Venue, symbol: String) {
        allowCount[venue]?.incrementAndGet()
        bump("CANONICAL_AUTH_ALLOW")
    }

    fun markAuthBlock(venue: Venue, symbol: String, reason: String) {
        blockCount[venue]?.incrementAndGet()
        bump("CANONICAL_AUTH_BLOCK")
        try {
            ForensicLogger.lifecycle(
                "CANONICAL_AUTH_BLOCK_6540",
                "venue=$venue symbol=$symbol reason=$reason",
            )
        } catch (_: Throwable) {}
    }

    fun markSized(venue: Venue, symbol: String) {
        sizedCount[venue]?.incrementAndGet()
        bump("CANONICAL_SIZE_RESOLVED")
    }

    fun markIntentCreated(venue: Venue, symbol: String, intentId: String) {
        intentCount[venue]?.incrementAndGet()
        bump("EXEC_INTENT_CREATED")
        try {
            ForensicLogger.lifecycle(
                "EXEC_INTENT_CREATED_6540",
                "venue=$venue symbol=$symbol intentId=$intentId",
            )
        } catch (_: Throwable) {}
    }

    fun markAdapterDispatch(venue: Venue, symbol: String) {
        dispatchCount[venue]?.incrementAndGet()
        bump("ASSET_ROUTER_DISPATCH")
    }

    fun markOpenConfirmed(venue: Venue, symbol: String, positionId: String) {
        openCount[venue]?.incrementAndGet()
        bump(
            when (venue) {
                Venue.MARKETS_SPOT -> "MARKETS_SPOT_OPEN_CONFIRMED"
                Venue.MARKETS_PERPS -> "MARKETS_PERPS_OPEN_CONFIRMED"
                Venue.CRYPTO -> "CRYPTO_OPEN_CONFIRMED"
            }
        )
        try {
            ForensicLogger.lifecycle(
                "${venue}_OPEN_CONFIRMED_6540",
                "symbol=$symbol positionId=$positionId",
            )
        } catch (_: Throwable) {}
    }

    fun snapshot(venue: Venue): VenueStats = VenueStats(
        venue = venue,
        candidates = candidateCount[venue]?.get() ?: 0L,
        authSubmit = submitCount[venue]?.get() ?: 0L,
        authAllow = allowCount[venue]?.get() ?: 0L,
        authBlock = blockCount[venue]?.get() ?: 0L,
        sized = sizedCount[venue]?.get() ?: 0L,
        intents = intentCount[venue]?.get() ?: 0L,
        dispatches = dispatchCount[venue]?.get() ?: 0L,
        opensConfirmed = openCount[venue]?.get() ?: 0L,
    )

    fun snapshotAll(): List<VenueStats> = Venue.values().map { snapshot(it) }

    /**
     * P0 acceptance invariant: enabled venue with candidates > 0 must have
     * `authSubmit > 0` over the observation window. Returns the set of
     * venues that violate the constraint.
     *
     * The invariant audit calls this every scan cycle; the fail-build
     * guard trips if any venue is in the list for two consecutive cycles.
     */
    fun candidatesWithoutAuthSubmit(): List<VenueStats> =
        snapshotAll().filter { it.candidates > 0L && it.authSubmit == 0L }

    internal fun markCandidateFor6551(assetClass: AssetClass, symbol: String, note: String) {
        val venue = when (assetClass) {
            AssetClass.PERPS -> Venue.MARKETS_PERPS
            AssetClass.CRYPTO_ALT, AssetClass.SOLANA_TOKEN -> Venue.CRYPTO
            else -> Venue.MARKETS_SPOT
        }
        markCandidate(venue, symbol, "class=${assetClass.tag} $note")
    }
    internal fun markSubmitFor6551(assetClass: AssetClass, symbol: String, note: String) {
        val venue = if (assetClass == AssetClass.PERPS) Venue.MARKETS_PERPS else if (assetClass == AssetClass.CRYPTO_ALT || assetClass == AssetClass.SOLANA_TOKEN) Venue.CRYPTO else Venue.MARKETS_SPOT
        markAuthSubmit(venue, symbol, "class=${assetClass.tag} $note")
    }
    internal fun markSizedFor6551(assetClass: AssetClass, symbol: String) { markSized(if (assetClass == AssetClass.PERPS) Venue.MARKETS_PERPS else if (assetClass == AssetClass.CRYPTO_ALT || assetClass == AssetClass.SOLANA_TOKEN) Venue.CRYPTO else Venue.MARKETS_SPOT, symbol) }
    internal fun markAuthAllowFor6551(assetClass: AssetClass, symbol: String) { markAuthAllow(if (assetClass == AssetClass.PERPS) Venue.MARKETS_PERPS else if (assetClass == AssetClass.CRYPTO_ALT || assetClass == AssetClass.SOLANA_TOKEN) Venue.CRYPTO else Venue.MARKETS_SPOT, symbol) }
    internal fun markAuthBlockFor6551(assetClass: AssetClass, symbol: String, reason: String) { markAuthBlock(if (assetClass == AssetClass.PERPS) Venue.MARKETS_PERPS else if (assetClass == AssetClass.CRYPTO_ALT || assetClass == AssetClass.SOLANA_TOKEN) Venue.CRYPTO else Venue.MARKETS_SPOT, symbol, reason) }
    internal fun markIntentCreatedFor6551(assetClass: AssetClass, symbol: String, id: String) { markIntentCreated(if (assetClass == AssetClass.PERPS) Venue.MARKETS_PERPS else if (assetClass == AssetClass.CRYPTO_ALT || assetClass == AssetClass.SOLANA_TOKEN) Venue.CRYPTO else Venue.MARKETS_SPOT, symbol, id) }
    internal fun markAdapterDispatchFor6551(assetClass: AssetClass, symbol: String) { markAdapterDispatch(if (assetClass == AssetClass.PERPS) Venue.MARKETS_PERPS else if (assetClass == AssetClass.CRYPTO_ALT || assetClass == AssetClass.SOLANA_TOKEN) Venue.CRYPTO else Venue.MARKETS_SPOT, symbol) }
    internal fun markOpenConfirmedFor6551(assetClass: AssetClass, symbol: String, id: String) { markOpenConfirmed(if (assetClass == AssetClass.PERPS) Venue.MARKETS_PERPS else if (assetClass == AssetClass.CRYPTO_ALT || assetClass == AssetClass.SOLANA_TOKEN) Venue.CRYPTO else Venue.MARKETS_SPOT, symbol, id) }

    private fun bump(label: String) {
        try { PipelineHealthCollector.labelInc(label + "_6540") } catch (_: Throwable) {}
    }

    /** Test hook — reset counters between test cases. */
    fun clearAllForTest() {
        candidateCount.values.forEach { it.set(0L) }
        submitCount.values.forEach { it.set(0L) }
        allowCount.values.forEach { it.set(0L) }
        blockCount.values.forEach { it.set(0L) }
        sizedCount.values.forEach { it.set(0L) }
        intentCount.values.forEach { it.set(0L) }
        dispatchCount.values.forEach { it.set(0L) }
        openCount.values.forEach { it.set(0L) }
    }
}
