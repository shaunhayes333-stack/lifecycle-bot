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
 */
object LiveSpendReserveAuthority7255 {
    const val RESERVE_SOL: Double = 0.012
}
