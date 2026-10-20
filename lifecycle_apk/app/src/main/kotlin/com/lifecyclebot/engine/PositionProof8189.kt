package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import java.math.BigInteger

/**
 * V5.0.8189 — ONE PROOF ROW PER LIVE POSITION.
 *
 * Owner, 5.0.8186: "Canonical store has 6 open LIVE; chain reconcile matched 4 chain mints, inventory=MATCH. That
 * doesn't prove all 6." ReconcileClassifier8144 compares canonical lots against the wallet token snapshot; a canonical
 * mint the (partial) snapshot does not list falls into no bucket at all — no state, no exception — so a report can say
 * MATCH while two positions were never compared. Here:
 *
 *  - [augmentChain8189] fills every canonical mint the snapshot lacks from the owner+mint reads the exit path already
 *    made (StopLatencyCore8153 proofs, SellRouteProbe8144 multi-provider HELD verdicts). It only ADDS positive reads
 *    for canonical mints; it never removes an entry and never turns a missing read into zero.
 *  - [bucket8189] names each position's reconcile outcome; [coverage8189] counts how many were really compared.
 *  - [block8189] prints, for every LIVE protective position: mint, wallet raw qty, canonical remaining raw, entry cost,
 *    entry lineage (OnChainCost8024: e.g. UNRECONCILED:HISTORY_TOO_LONG / TX_UNREADABLE), the executable exit mark
 *    with source and age, the hold / deferral reason, the protective latch and the sell-route readiness.
 * Read-only: nothing here writes a position, a balance or a mark. Printed under the chain reconcile line
 * (ReconcileRunner8144.statusLine8144), so the dump itself does not grow.
 */
object PositionProof8189 {

    enum class Bucket8189 { MATCHED, QTY_MISMATCH, NOT_ON_CHAIN, CHAIN_UNREAD, FROZEN, PENDING_TX }

    /** Wallet reads older than this are not used to fill the reconcile chain. */
    private const val READ_MAX_AGE_MS_8189 = com.lifecyclebot.engine.sell.StopLatencyCore8153.HOT_PROOF_TTL_MS_8153

    @Volatile private var lastChain8189: Map<String, BigInteger>? = null
    @Volatile private var lastComplete8189 = false

    // ── pure ──

    /** Pure: [chain] with each of [canonicalMints] it lacks filled from a positive owner+mint [reads] entry. */
    fun augmentChain8189(chain: Map<String, BigInteger>?, canonicalMints: Set<String>, reads: Map<String, BigInteger>): Map<String, BigInteger>? {
        val add = canonicalMints.filter { m -> (chain == null || !chain.containsKey(m)) && (reads[m]?.signum() ?: 0) > 0 }
        if (add.isEmpty()) return chain
        val out = HashMap<String, BigInteger>(chain ?: emptyMap())
        for (m in add) out[m] = reads.getValue(m)
        return out
    }

    /** Pure: one position's reconcile outcome (canonical raw vs chain raw; null chain raw = never read). */
    fun bucket8189(canonicalRaw: BigInteger, chainRaw: BigInteger?, chainComplete: Boolean, frozen: Boolean, pending: Boolean): Bucket8189 = when {
        frozen -> Bucket8189.FROZEN
        pending -> Bucket8189.PENDING_TX
        chainRaw == null || chainRaw.signum() <= 0 -> if (chainComplete) Bucket8189.NOT_ON_CHAIN else Bucket8189.CHAIN_UNREAD
        com.lifecyclebot.engine.truth.ReconcileClassifier8144.qtyMatches8144(canonicalRaw, chainRaw) -> Bucket8189.MATCHED
        else -> Bucket8189.QTY_MISMATCH
    }

    /** Pure: (compared, total) — a position counts as compared unless its chain quantity was never read. */
    fun coverage8189(buckets: List<Bucket8189>): Pair<Int, Int> = buckets.count { it != Bucket8189.CHAIN_UNREAD } to buckets.size

    /** Pure: the sell route's readiness for one held position. */
    fun readiness8189(frozen: Boolean, signedSellPending: Boolean, sellInFlight: Boolean, walletProofAgeMs: Long?, venueKnown: Boolean): String = when {
        frozen -> "FROZEN_UNSELLABLE"
        signedSellPending -> "SIGNED_SELL_PENDING"
        sellInFlight -> "SELL_IN_FLIGHT"
        walletProofAgeMs == null || walletProofAgeMs !in 0L..READ_MAX_AGE_MS_8189 -> if (venueKnown) "READY_NEEDS_BALANCE_READ" else "READY_NEEDS_BALANCE_AND_VENUE_READ"
        !venueKnown -> "READY_VENUE_UNKNOWN_LEGACY_ORDER"
        else -> "READY"
    }

    // ── live ──

    /** The newest positive owner+mint wallet read of [mint] and its time, from the exit path's caches. */
    private fun walletRead8189(mint: String, nowMs: Long): Pair<BigInteger, Long>? {
        // Same post-signature freshness contract as the execution path. A pre-sell
        // positive proof cannot resurrect inventory in the operator report.
        return com.lifecyclebot.engine.sell.StopLatencyWiring8153.provenHolding8153(mint, nowMs)
            ?.let { it.raw to it.atMs }
    }

    /**
     * ReconcileRunner8144.gather8144: the reconcile chain with every canonical mint the snapshot lacks filled from the
     * exit path's owner+mint reads; remembered for the proof table.
     */
    fun chainFor8189(chain: Map<String, BigInteger>?, canonicalMints: Set<String>, chainComplete: Boolean, nowMs: Long = System.currentTimeMillis()): Map<String, BigInteger>? {
        val reads = HashMap<String, BigInteger>()
        for (m in canonicalMints) walletRead8189(m, nowMs)?.let { reads[m] = it.first }
        val out = augmentChain8189(chain, canonicalMints, reads)
        lastChain8189 = out
        lastComplete8189 = chainComplete
        return out
    }

    private fun age(nowMs: Long, atMs: Long): String = if (atMs <= 0L) "-" else "${((nowMs - atMs).coerceAtLeast(0L)) / 1000}s"

    /** The proof table, one line per LIVE protective position (empty when none). */
    fun block8189(nowMs: Long = System.currentTimeMillis()): String {
        val lots = try { CanonicalPositionAuthority6441.protectiveInventory7807("live") } catch (_: Throwable) { emptyList() }
        if (lots.isEmpty()) return ""
        val chain = lastChain8189
        val frozen = try { com.lifecyclebot.engine.sell.FrozenHeldUnsellable8141.heldMints8141().toSet() } catch (_: Throwable) { emptySet() }
        val buckets = ArrayList<Bucket8189>()
        val sb = StringBuilder()
        for (p in lots) {
            val read = walletRead8189(p.mint, nowMs)
            val chainRaw = chain?.get(p.mint) ?: read?.first
            val signed = try { com.lifecyclebot.engine.sell.ExitHotPath7809.signedSellPending8189(p.mint) } catch (_: Throwable) { false }
            val bucket = bucket8189(p.remainingQtyRaw, chainRaw, lastComplete8189, p.mint in frozen, signed)
            buckets += bucket
            sb.append("\n    proof8189 ").append(p.symbol.ifBlank { "?" }.take(10)).append(' ').append(p.mint.take(8))
                .append(" wallet=").append(chainRaw?.toString() ?: "UNREAD").append(" canon=").append(p.remainingQtyRaw)
                .append(" recon=").append(bucket.name)
                .append(" cost=").append("%.4f".format(p.entryCostSol))
                .append(" lineage=").append(lineage8189(p))
                .append(" mark=").append(mark8189(p.mint, nowMs))
                .append(" hold=").append(hold8189(p.mint))
                .append(" protect=").append(protect8189(p.positionId))
                .append(" route=").append(route8189(p.mint, read?.second, p.mint in frozen, signed, nowMs))
        }
        val (compared, total) = coverage8189(buckets)
        return "\n    proof8189 coverage compared=$compared/$total " +
            (try { com.lifecyclebot.engine.truth.HeldExitQuote8189.statusLine8189() } catch (_: Throwable) { "" }) + " " +
            (try { com.lifecyclebot.engine.sell.ProfitEscalation8189.statusLine8189() } catch (_: Throwable) { "" }) + " " +
            (try { com.lifecyclebot.engine.sell.StopRoute8189.statusLine8189() } catch (_: Throwable) { "" }) + sb.toString()
    }

    private fun lineage8189(p: CanonicalPositionAuthority6441.Position): String =
        (try { com.lifecyclebot.engine.truth.OnChainCost8024.cachedVerdict8189(p.mint) } catch (_: Throwable) { null })
            ?: "NOT_READ(entry=${p.entryPriceSource.take(20).ifBlank { "-" }})"

    private fun mark8189(mint: String, nowMs: Long): String {
        val m = try {
            com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.get(mint, com.lifecyclebot.engine.truth.CanonicalMarkPurpose6570.EXIT_ECONOMIC)
        } catch (_: Throwable) { null }
        val q = try { com.lifecyclebot.engine.truth.HeldExitQuote8189.last8189(mint) } catch (_: Throwable) { null }
        val markText = if (m != null) "%.4g@%s/%s".format(m.priceUsd.value.toDouble(), m.source.take(28), age(nowMs, m.timestampMs)) else "NONE"
        val quoteText = if (q != null) " heldQuote=%.4g/%s".format(q.priceUsd, age(nowMs, q.observedAtMs)) else ""
        return markText + quoteText
    }

    private fun hold8189(mint: String): String {
        val ts = try { synchronized(BotService.status.tokens) { BotService.status.tokens[mint] } } catch (_: Throwable) { null } ?: return "NO_RUNTIME_ROW"
        val lane = ts.position.tradingMode.trim().uppercase()
        val ev = try { LifecycleEvidence8189.evidenceFor8189(ts, lane) } catch (_: Throwable) { null }
        val held = try { LifecycleHold8065.holding8065(ts) } catch (_: Throwable) { false }
        val episode = LifecycleEvidence8189.deferralOf8189("${ts.mint}|${ts.position.entryTime}")
        val ep = if (episode != null) " deferred=${episode.first} firstAt=${"%.1f".format(episode.second)}%" else ""
        return when {
            held -> "LIFECYCLE_HELD_${ev?.name ?: "?"}$ep"
            LifecycleHold8065.lifecycleLane8065(lane) -> "NOT_HELD_${ev?.name ?: "UNPROVEN"}_LANE_STOP$ep"
            else -> "NONE$ep"
        }
    }

    private fun protect8189(positionId: String): String {
        val l = try { com.lifecyclebot.engine.truth.ProtectiveExitScheduler6450.latch(positionId) } catch (_: Throwable) { null }
        return l?.let { "LATCHED_${it.kind.name}" } ?: "ARMED"
    }

    private fun route8189(mint: String, readAtMs: Long?, frozen: Boolean, signed: Boolean, nowMs: Long): String {
        val inFlight = try { com.lifecyclebot.engine.sell.CloseLease.sellInFlight7948(mint) } catch (_: Throwable) { false }
        val venue = try { com.lifecyclebot.engine.sell.SellRouteVenueProbe8144.cached8144(mint, nowMs) } catch (_: Throwable) {
            com.lifecyclebot.engine.sell.SellRouteEndpoint8144.Venue8144.UNKNOWN
        }
        val known = venue != com.lifecyclebot.engine.sell.SellRouteEndpoint8144.Venue8144.UNKNOWN
        return readiness8189(frozen, signed, inFlight, readAtMs?.let { nowMs - it }, known) + "(${venue.name})"
    }
}
