package com.lifecyclebot.engine

import org.junit.Test
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * V5.0.6612 — Bounded Contributor Merge (operator directive Feb 2026):
 *   "many active specialists -> one canonical execution owner per
 *   candidate -> every relevant specialist continues contributing to
 *   sizing/hold/exit/learning. Contributor influence must never be
 *   zero merely because the specialist was not elected owner."
 *
 * V5.0.6607b restored the paper-mode allLaneContribution telemetry
 * emit so contributors are now COUNTED, but their opinions were not
 * yet merged into the elected owner's decision. V5.0.6612 closes that
 * loop with a bounded merge:
 *
 *   * `SpecialistContributorMerge6612.recordContributor` at every
 *     non-owner desk contribution in BotService's all-lane emit block.
 *   * `SpecialistContributorMerge6612.boundedSizeMultiplier6612(mint)`
 *     read in OrderSizeResolver6441.resolve BEFORE the runner ladder
 *     so subsequent hard caps (risk/cash/lane/ladder) still clip it —
 *     the merge cannot break sealed authority.
 *
 * The multiplier is CLAMPED to [0.75, 1.25]. At contribCount=0 it is
 * exactly 1.0 so un-observed mints see zero change. TTL 5s prevents
 * stale desks from influencing future candidates on the same mint.
 */
class Aate6612BoundedContributorMergeCoverageTest {

    @After
    fun tearDown() {
        com.lifecyclebot.engine.truth.SpecialistContributorMerge6612.resetForTest()
    }

    @Test
    fun aate6612_registry_exists_with_bounded_multiplier_api() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/SpecialistContributorMerge6612.kt"
        ).readText()
        assertTrue(
            "V5.0.6612: SpecialistContributorMerge6612 must exist with recordContributor + boundedSizeMultiplier6612",
            src.contains("object SpecialistContributorMerge6612") &&
                src.contains("fun recordContributor(") &&
                src.contains("fun boundedSizeMultiplier6612(mint: String): Double")
        )
        assertTrue(
            "V5.0.6612: multiplier must clamp to [0.75, 1.25]",
            src.contains("private const val LO = 0.75") &&
                src.contains("private const val HI = 1.25") &&
                src.contains(".coerceIn(LO, HI)")
        )
    }

    @Test
    fun aate6612_no_contributors_returns_neutral_multiplier() {
        val merge = com.lifecyclebot.engine.truth.SpecialistContributorMerge6612
        assertEquals("V5.0.6612: unknown mint must return 1.0", 1.0, merge.boundedSizeMultiplier6612("mintA"), 1e-9)
    }

    @Test
    fun aate6612_bullish_contributors_lift_toward_hi_cap() {
        val merge = com.lifecyclebot.engine.truth.SpecialistContributorMerge6612
        // 3 confident buy-intent contributors on the same mint.
        merge.recordContributor("mintB", "MOONSHOT", laneScore = 70.0, aiConfidence = 90.0, buyIntent = true)
        merge.recordContributor("mintB", "SHITCOIN", laneScore = 65.0, aiConfidence = 85.0, buyIntent = true)
        merge.recordContributor("mintB", "EXPRESS",  laneScore = 68.0, aiConfidence = 80.0, buyIntent = true)
        val m = merge.boundedSizeMultiplier6612("mintB")
        assertTrue("V5.0.6612: bullish contributors must lift the multiplier above 1.0 (got $m)", m > 1.0)
        assertTrue("V5.0.6612: multiplier must never exceed HI=1.25 (got $m)", m <= 1.25)
    }

    @Test
    fun aate6612_bearish_contributors_dip_toward_lo_cap() {
        val merge = com.lifecyclebot.engine.truth.SpecialistContributorMerge6612
        // 4 low-confidence non-buy contributors.
        merge.recordContributor("mintC", "MOONSHOT", laneScore = 12.0, aiConfidence = 15.0, buyIntent = false)
        merge.recordContributor("mintC", "SHITCOIN", laneScore = 10.0, aiConfidence = 20.0, buyIntent = false)
        merge.recordContributor("mintC", "EXPRESS",  laneScore = 15.0, aiConfidence = 18.0, buyIntent = false)
        merge.recordContributor("mintC", "QUALITY",  laneScore =  8.0, aiConfidence = 22.0, buyIntent = false)
        val m = merge.boundedSizeMultiplier6612("mintC")
        assertTrue("V5.0.6612: bearish contributors must dip the multiplier below 1.0 (got $m)", m < 1.0)
        assertTrue("V5.0.6612: multiplier must never dip below LO=0.75 (got $m)", m >= 0.75)
    }

    @Test
    fun aate6612_order_size_resolver_consumes_merge() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/OrderSizeResolver6441.kt"
        ).readText()
        assertTrue(
            "V5.0.6612: OrderSizeResolver6441.resolve must accept optional mint and consult SpecialistContributorMerge6612",
            src.contains("mint: String = \"\"") &&
                src.contains("SpecialistContributorMerge6612") &&
                src.contains("boundedSizeMultiplier6612(mint)")
        )
        // The nudge must be applied BEFORE the runner ladder so subsequent
        // hard caps still clip it.
        val nudgeIdx = src.indexOf("val nudgedRisk = (risk * contribMult6612)")
        val ladderIdx = src.indexOf("val laddered = if (ladderTarget.isFinite()")
        assertTrue(
            "V5.0.6612: nudgedRisk must be computed BEFORE the runner ladder so hard caps still clip it (nudge=$nudgeIdx ladder=$ladderIdx)",
            nudgeIdx > 0 && ladderIdx > nudgeIdx
        )
    }

    @Test
    fun aate6612_bot_service_records_non_owner_contributors() {
        val bot = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/BotService.kt"
        ).readText()
        assertTrue(
            "V5.0.6612: BotService must call recordContributor for non-owner (allowed=false) evaluations",
            bot.contains("§BOUNDED_CONTRIBUTOR_MERGE") &&
                bot.contains("SpecialistContributorMerge6612.recordContributor(") &&
                bot.contains("laneScore = laneBase.entryScore") &&
                bot.contains("aiConfidence = laneBase.aiConfidence")
        )
    }
}
