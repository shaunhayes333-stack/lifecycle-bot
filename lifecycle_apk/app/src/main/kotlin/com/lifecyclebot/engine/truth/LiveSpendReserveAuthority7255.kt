package com.lifecyclebot.engine.truth

/**
 * V5.0.7255 — one reserve for every live sizing authority.
 *
 * The executor already retained 0.012 SOL for rent, fees and transaction
 * retries, while V3, the last-mile routability check and LivePreflight each
 * independently subtracted 0.05 SOL. On a 0.0873 SOL wallet that duplicate
 * policy declared only 0.0373 SOL tradeable and refused a 0.0418 SOL route,
 * even though the route would leave about 0.0455 SOL in the wallet.
 *
 * Keep the executor's established reserve and make every reader use it.
 *
 * V5.0.7951 — the reserve is sized from what it must actually pay for, not a
 * fixed 0.012. 5.0.7949 live: liquid 0.0400, reserve 0.012, tradeable 0.0280,
 * one routable order refused. What the reserve has to cover is the wallet's
 * rent-exempt minimum, one buy's locked cost (fee + token-account rent), the
 * worst single sell (drain tip + CU fee + the temporary wSOL account), and a
 * retry allowance per open live position (CapitalThroughput7951.reserveSol7951):
 * 0.0060 SOL flat, 0.0072 SOL with four positions open, never above the old
 * 0.012. Every reader still reads RESERVE_SOL, so they all move together.
 */
object LiveSpendReserveAuthority7255 {
    /** V5.0.7951 — the former fixed reserve, now the ceiling. */
    const val RESERVE_CEILING_SOL_7951: Double = 0.012

    @Volatile private var cachedReserve7951 = RESERVE_CEILING_SOL_7951
    @Volatile private var cachedAtMs7951 = 0L

    /** Live reserve for the current number of open live positions (cached 2 s). */
    val RESERVE_SOL: Double
        get() {
            val now = System.currentTimeMillis()
            if (now - cachedAtMs7951 in 0L..2_000L) return cachedReserve7951
            val v = try {
                CapitalThroughput7951.reserveSol7951(LiveConcentrationDoctrine7697.liveOpenCount(), RESERVE_CEILING_SOL_7951)
            } catch (_: Throwable) { RESERVE_CEILING_SOL_7951 }
            cachedReserve7951 = if (v.isFinite() && v > 0.0) v else RESERVE_CEILING_SOL_7951
            cachedAtMs7951 = now
            return cachedReserve7951
        }
}
