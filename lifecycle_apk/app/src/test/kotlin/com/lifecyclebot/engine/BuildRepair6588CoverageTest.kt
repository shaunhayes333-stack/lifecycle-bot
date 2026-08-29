package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6588 — final round of the 6580 12-point operator directive.
 *
 * §P0-5   Fresh-discovery producer priority: 772 identities → 1
 *         CryptoBrain reach was caused by opportunity-score-only sort
 *         relegating fresh (low volume24h) tokens below established
 *         ones. isFresh6544 is now the primary sort key.
 *
 * §P0-12  Header-level canonical position count. §H / §6454 / §6459
 *         each displayed independent per-guard views of the same
 *         underlying store — operator saw 18 / 61 / 0. Health dump
 *         now prints an unambiguous "AUTHORITATIVE LIVE-OPEN POSITIONS"
 *         line sourced from CanonicalPositionAuthority6441 above every
 *         sub-ledger status.
 */
class BuildRepair6588CoverageTest {

    private val registrySrc = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
    private val healthSrc = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()

    @Test
    fun p0_5_blended_queue_prioritizes_fresh() {
        assertTrue(
            "getBlendedOpportunityQueue6544 must sort isFresh6544 first",
            registrySrc.contains("compareByDescending<DynToken> { it.isFresh6544 }") &&
                registrySrc.contains(".thenByDescending { it.opportunityScore6544 }")
        )
    }

    @Test
    fun p0_12_authoritative_position_count_header() {
        assertTrue(
            "Health dump must print AUTHORITATIVE LIVE-OPEN POSITIONS line from CanonicalPositionAuthority6441",
            healthSrc.contains("AUTHORITATIVE LIVE-OPEN POSITIONS:") &&
                healthSrc.contains("CanonicalPositionAuthority6441.openPositions()")
        )
        assertTrue(
            "Sub-ledger status lines must be explicitly labeled non-authoritative",
            healthSrc.contains("NOT competing authorities")
        )
    }
}
