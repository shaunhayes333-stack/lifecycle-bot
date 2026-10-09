package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7951 — CAPITAL THROUGHPUT: the live wallet's SOL must turn over into the
 * opportunities that are actually waiting for it.
 *
 * 5.0.7949 live (146 s): equity 0.2219 SOL, liquid 0.0400, reserve 0.012,
 * tradeable 0.0280 against a 0.04532 SOL ($5) routable minimum; ~0.18 SOL sat in
 * four open live positions (CASHGEN two of them at 1.63x its enforced target).
 * Every lane read capitalStarved=true, OrderSizeResolver6441 refused 540 orders
 * for capital and CRYPTO_ALT ended 25 evaluations WALLET_BELOW_ROUTABLE. The
 * 7948 capital rotation that exists for exactly this waited for "a proven
 * lane", and after the 7946 label reset none is proven, so it never fired.
 *
 * This object holds the pure rules (unit-tested) and two tiny in-memory
 * registries:
 *
 *  1. DEMAND — a candidate refused ONLY for capital (resolver capital refusal,
 *     a chart-reader BUY waiting on capital, crypto WALLET_BELOW_ROUTABLE) is
 *     real demand for SOL for [DEMAND_WINDOW_MS_7951]. Capital rotation fires
 *     on demand, not on a lane label.
 *  2. ROTATION CHOICE — every qualifying dead-money position offers itself; only
 *     the deadest one (chart TOP_MOTIF / DEV_SOLD first, then lowest marked P&L x
 *     longest without a new high) is sold.
 *  3. SLOTS UNDER SCARCITY — while routable capacity is scarce, a slow lane may
 *     hold one slot while a fast lane has demand waiting, and a lane over its
 *     enforced allocation does not take another slot from a lane that is asking.
 *  4. RESERVE / MINIMUM — the reserve is sized from the fees the open positions'
 *     sells and one buy actually need; the routable minimum is the larger of the
 *     $-route floor and the order at which fixed round-trip cost is 2.5%.
 */
object CapitalThroughput7951 {

    // ── fixed venue costs, from the code's own values ──────────────────────

    /** Executor.effectiveSenderTipLamports / effectiveJitoTipLamports floor (200_000 lamports);
     *  PumpFunDirectApi.SENDER_MIN_TIP_SOL. Helius Sender needs >= 0.0002 SOL in the tx. */
    const val SENDER_TIP_FLOOR_SOL_7951 = 0.0002
    /** urgent = floor x 2 (effectiveSenderTipLamports(urgent = true)). */
    const val URGENT_TIP_SOL_7951 = 0.0004
    /** Pump-direct drain sell priorityFeeSol (Executor sell path, isDrainExit). */
    const val DRAIN_TIP_SOL_7951 = 0.0008
    /** 25_000 micro-lamports CU price (JupiterApi Sender build) x <= 600k CU + 5_000 lamport signature. */
    const val CU_AND_BASE_FEE_SOL_7951 = 0.00002
    /** Associated token account rent (2_039_280 lamports): locked on buy, returned on close (RentReclaimer7927). */
    const val ATA_RENT_SOL_7951 = 0.00204
    /** A system account must stay rent-exempt (890_880 lamports) or the transaction fails. */
    const val WALLET_RENT_EXEMPT_SOL_7951 = 0.00089
    /** Per open position: fee headroom for a retried / escalated exit attempt. */
    const val PER_OPEN_SELL_ALLOWANCE_SOL_7951 = 0.0003
    /** Never below this, whatever the arithmetic says. */
    const val RESERVE_FLOOR_SOL_7951 = 0.006
    /** Fixed round-trip cost may be at most this share of the order. */
    const val MAX_FIXED_COST_SHARE_7951 = 0.025

    /** One buy transaction's fixed cost. */
    fun buyLegSol7951(): Double = SENDER_TIP_FLOOR_SOL_7951 + CU_AND_BASE_FEE_SOL_7951

    /** One (urgent) sell transaction's fixed cost. */
    fun sellLegSol7951(): Double = URGENT_TIP_SOL_7951 + CU_AND_BASE_FEE_SOL_7951

    /** Fixed round trip (buy + urgent sell). ATA rent is not a cost: it comes back on close. */
    fun fixedRoundTripSol7951(): Double = buyLegSol7951() + sellLegSol7951()

    /** Smallest order whose fixed round trip is <= [MAX_FIXED_COST_SHARE_7951] of it, in SOL. */
    fun costFloorOrderSol7951(): Double = fixedRoundTripSol7951() / MAX_FIXED_COST_SHARE_7951

    /** Fixed round-trip cost as a percent of [orderSol]. */
    fun fixedCostSharePct7951(orderSol: Double): Double =
        if (orderSol.isFinite() && orderSol > 0.0) fixedRoundTripSol7951() / orderSol * 100.0 else Double.NaN

    /**
     * SOL the wallet must keep liquid with [openLivePositions] open.
     *
     * Transactions on one fee payer execute one at a time and a sell's proceeds
     * land inside the same transaction, so the reserve covers the single most
     * expensive transaction rather than every sell at once:
     *   wallet rent-exempt minimum
     *   + one buy's locked cost (fee + the new token account's rent)
     *   + the worst sell (drain tip + CU fee + the temporary wSOL account's rent)
     *   + a retry allowance per open position.
     * Bounded to [RESERVE_FLOOR_SOL_7951, ceiling] (ceiling = the old fixed 0.012).
     */
    fun reserveSol7951(openLivePositions: Int, ceilingSol: Double): Double {
        val n = openLivePositions.coerceIn(0, 1_000)
        val worstSell = DRAIN_TIP_SOL_7951 + CU_AND_BASE_FEE_SOL_7951 + ATA_RENT_SOL_7951
        val oneBuy = buyLegSol7951() + ATA_RENT_SOL_7951
        val need = WALLET_RENT_EXEMPT_SOL_7951 + oneBuy + worstSell + n * PER_OPEN_SELL_ALLOWANCE_SOL_7951
        return need.coerceIn(RESERVE_FLOOR_SOL_7951, maxOf(RESERVE_FLOOR_SOL_7951, ceilingSol))
    }

    /** Routable orders [walletSol] can fund after [reserveSol]. */
    fun routableCapacity7951(walletSol: Double, reserveSol: Double, routableMinSol: Double): Int {
        if (!walletSol.isFinite() || !routableMinSol.isFinite() || routableMinSol <= 0.0) return 0
        val t = (walletSol - (if (reserveSol.isFinite()) reserveSol else 0.0)).coerceAtLeast(0.0)
        return kotlin.math.floor(t / routableMinSol + 1e-9).toInt()
    }

    // ── 1. demand ──────────────────────────────────────────────────────────

    const val DEMAND_WINDOW_MS_7951 = 120_000L

    /** Lanes whose turnover is slow: they hold, and on a scarce wallet one slot each is plenty. */
    val SLOW_LANES_7951: Set<String> = setOf("CASHGEN", "BLUECHIP", "TREASURY", "QUALITY")
    /** Lanes whose edge is speed; capital they are waiting for is the throughput the owner wants. */
    val FAST_LANES_7951: Set<String> = setOf("SHITCOIN", "MOONSHOT", "EXPRESS", "CORE", "CRYPTO_ALT", "CRYPTO", "CRYPTO_SPOT")

    data class Demand7951(val lane: String, val mint: String, val source: String, val atMs: Long)

    private val demand7951 = ConcurrentHashMap<String, Demand7951>()

    /** A candidate in [lane] (for [mint]) was refused only because liquid SOL could not fund it. */
    fun noteCapitalDemand7951(lane: String, mint: String, source: String, nowMs: Long = System.currentTimeMillis()) {
        val l = CanonicalLaneIdentity6506.canonical(lane)
        if (l.isBlank()) return
        demand7951["$l|$mint"] = Demand7951(l, mint, source, nowMs)
        if (demand7951.size > 256) demand7951.entries.removeIf { nowMs - it.value.atMs > DEMAND_WINDOW_MS_7951 }
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CAPITAL_DEMAND_7951_$source") } catch (_: Throwable) {}
    }

    /** Pure: the demand entries still inside the window, other than [excludeLane], optionally fast lanes only. */
    fun freshDemand7951(entries: Collection<Demand7951>, nowMs: Long, excludeLane: String = "", fastOnly: Boolean = false): List<Demand7951> {
        val ex = CanonicalLaneIdentity6506.canonical(excludeLane)
        return entries.filter {
            nowMs >= it.atMs && nowMs - it.atMs <= DEMAND_WINDOW_MS_7951 &&
                (ex.isBlank() || it.lane != ex) && (!fastOnly || it.lane in FAST_LANES_7951)
        }
    }

    /** Is any candidate waiting on capital right now? */
    fun demandWaiting7951(nowMs: Long = System.currentTimeMillis(), excludeLane: String = "", fastOnly: Boolean = false): Boolean =
        freshDemand7951(demand7951.values, nowMs, excludeLane, fastOnly).isNotEmpty()

    // ── 2. which dead position to rotate ───────────────────────────────────

    /** How long the deadest offer set must have been forming before a pick (other positions get a tick). */
    const val OFFER_SETTLE_MS_7951 = 8_000L
    /** An offer not refreshed in this long is no longer a candidate. */
    const val OFFER_FRESH_MS_7951 = 30_000L

    data class RotationOffer7951(val key: String, val chartExit: Boolean, val deadness: Double, val atMs: Long)

    /** Pure: lowest marked P&L x longest without a new high. Higher = deader. */
    fun deadness7951(pnlPct: Double, msSinceNewHigh: Long): Double {
        val pnl = if (pnlPct.isFinite()) pnlPct.coerceAtMost(1.0) else 1.0
        val minutes = (msSinceNewHigh.coerceAtLeast(0L)).toDouble() / 60_000.0
        return (2.0 - pnl) * minutes
    }

    /** Pure: the offer to rotate among the fresh ones — chart exit first, then deadest, then key. */
    fun deadestKey7951(offers: Collection<RotationOffer7951>, nowMs: Long): String? =
        offers.filter { nowMs >= it.atMs && nowMs - it.atMs <= OFFER_FRESH_MS_7951 }
            .sortedWith(compareByDescending<RotationOffer7951> { it.chartExit }.thenByDescending { it.deadness }.thenBy { it.key })
            .firstOrNull()?.key

    private val offers7951 = ConcurrentHashMap<String, RotationOffer7951>()
    @Volatile private var offerSetSinceMs7951 = 0L

    /** A qualifying position offers itself; true only when it is the deadest offer after the settle time. */
    fun offerRotation7951(offer: RotationOffer7951, nowMs: Long = offer.atMs): Boolean {
        offers7951.entries.removeIf { nowMs - it.value.atMs > OFFER_FRESH_MS_7951 }
        if (offers7951.isEmpty()) offerSetSinceMs7951 = nowMs
        offers7951[offer.key] = offer
        if (nowMs - offerSetSinceMs7951 < OFFER_SETTLE_MS_7951) return false
        return deadestKey7951(offers7951.values, nowMs) == offer.key
    }

    /** The position no longer qualifies (or was just rotated). */
    fun withdrawRotationOffer7951(key: String) { offers7951.remove(key) }

    // ── 3. slots under scarcity ────────────────────────────────────────────

    /** At or below this many routable orders of EQUITY the slots are scarce. */
    const val SCARCE_EQUITY_CAPACITY_7951 = 5

    /** Scarce: equity carries few routable orders, or liquid SOL carries at most one. */
    fun scarce7951(equityCapacity: Int, liquidCapacity: Int): Boolean =
        equityCapacity <= SCARCE_EQUITY_CAPACITY_7951 || liquidCapacity <= 1

    /**
     * Pure. Null = this lane may take the slot. Applies only while slots are
     * scarce AND another lane is asking for capital — a lane alone with the
     * wallet is never refused here.
     *  - a slow lane already holding a slot yields to a waiting fast lane;
     *  - a lane at or over its enforced allocation yields to any other waiting lane.
     */
    fun scarceSlotRefusal7951(
        lane: String,
        laneOpen: Int,
        utilization: Double,
        equityCapacity: Int,
        liquidCapacity: Int,
        fastDemandWaiting: Boolean,
        otherDemandWaiting: Boolean,
    ): String? {
        val l = CanonicalLaneIdentity6506.canonical(lane)
        if (l.isBlank() || !scarce7951(equityCapacity, liquidCapacity)) return null
        if (l in SLOW_LANES_7951 && laneOpen >= 1 && fastDemandWaiting) return "SLOW_LANE_ONE_SLOT_WHILE_FAST_DEMAND_7951"
        if (utilization.isFinite() && utilization >= 1.0 && laneOpen >= 1 && otherDemandWaiting) return "LANE_OVER_ALLOCATION_SCARCE_7951"
        return null
    }
}
