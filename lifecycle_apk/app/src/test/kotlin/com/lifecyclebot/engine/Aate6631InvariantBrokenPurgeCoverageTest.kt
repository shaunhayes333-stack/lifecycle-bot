package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * V5.0.6631 §B/§L PURGE_INVARIANT_BROKEN coverage.
 *
 * Operator P0 directive Feb 2026:
 *   > "Any position for which entryPrice <= 0, qty <= 0, qty non-finite,
 *   >  price non-finite, decimal conversion unresolved, price basis
 *   >  invariant failed, fill-unit replay failed, economic identity
 *   >  unresolved MUST be excluded from
 *   >  CanonicalPositionAuthority.openPositions()."
 *
 * Source-authority test only — runtime behaviour is validated on the
 * operator's PAPER acceptance run.
 */
class Aate6631InvariantBrokenPurgeCoverageTest {

    @Test
    fun aate6631_open_positions_filters_invalid_economic_state() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt"
        ).readText()
        // openPositions() must consult the economic-validity gate.
        assertTrue("V5.0.6631 §B: openPositions must use the economic-validity gate",
            src.contains("openPositions(): List<Position> = positions.values.filter { isEconomicallyValidOpen6631(it) }"))
        assertTrue("V5.0.6631 §B: hasOpenMint must use the economic-validity gate",
            src.contains("hasOpenMint(mint: String): Boolean = positions.values.any {") &&
                src.contains("isEconomicallyValidOpen6631(it)"))
        // Gate must include the operator's required rejection reasons.
        assertTrue("V5.0.6631 §B: gate must reject quarantined rows",
            src.contains("CANONICAL_OPEN_FILTERED_QUARANTINED_6631"))
        assertTrue("V5.0.6631 §B: gate must reject invalid raw quantity",
            src.contains("CANONICAL_OPEN_FILTERED_INVALID_QTY_6631") &&
                src.contains("remainingQtyRaw.signum() <= 0"))
        assertTrue("V5.0.6631c §B: gate must strictly reject zero/negative entry price",
            src.contains("CANONICAL_OPEN_FILTERED_ZERO_ENTRY_PRICE_6631") &&
                src.contains("entry <= 0.0"))
        assertTrue("V5.0.6631c §B: openPosition must auto-derive entryPriceUsd from cost/qty for legacy callers",
            src.contains("OPEN_POSITION_DERIVED_FROM_COST_QTY_6631"))
        assertTrue("V5.0.6631 §B: gate must reject INVARIANT_BROKEN_6500 entryPriceSource",
            src.contains("CANONICAL_OPEN_FILTERED_INVARIANT_BROKEN_SOURCE_6631") &&
                src.contains("INVARIANT_BROKEN_6500"))
        // Legacy V5.0.6630 quarantine tag must also be filtered so
        // the 482 replay rows do not re-enter open inventory.
        assertTrue("V5.0.6631 §B: gate must reject LEGACY_REPLAY_QUARANTINED_6630 source",
            src.contains("LEGACY_REPLAY_QUARANTINED_6630"))
    }
}
