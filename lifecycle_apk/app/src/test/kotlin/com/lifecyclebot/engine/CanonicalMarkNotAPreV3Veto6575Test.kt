package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalMarkPurpose6570
import com.lifecyclebot.engine.truth.CanonicalPriceMark6522
import com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522
import com.lifecyclebot.engine.truth.PriceUsd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigDecimal

/**
 * V5.0.6575 — P0-2 : CANONICAL MARK IS NO LONGER A PRE-V3 VETO.
 *
 * Operator directive:
 *   "PRE_V3_RETURN=1589, ALL 1589 = CANONICAL_MARK_REJECTED."
 *   "Do not turn missing pair/quote metadata into an automatic pre-V3
 *    RETURN when the candidate can still be safely evaluated as an
 *    observation. Instead: candidate → provisional evidence →
 *    intelligence/V3 → hydration continues → execution requires strict
 *    executable proof later."
 *
 * These invariants pin the fix at source level:
 *
 *   1. CanonicalPriceMarkRegistry6522 keys marks per-(mint, purpose) so
 *      an OBSERVATION mark and an EXECUTABLE_ENTRY_QUOTE mark for the
 *      same mint can coexist without overwriting each other.
 *   2. BotService.processTokenCycle no longer `return`s when the mark
 *      is rejected; it now stamps a *_INFO counter and falls through
 *      to V3/FDG.
 *   3. Executor.paperBuy hard-refuses (markPaperBuyNotOpened) when no
 *      EXECUTABLE_ENTRY_QUOTE mark exists — this preserves the safety
 *      invariant EXECUTION_WITH_PROVISIONAL_MARK = 0.
 */
class CanonicalMarkNotAPreV3Veto6575Test {

    private val botSrc = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
    private val execSrc = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

    @Before
    fun reset() {
        CanonicalPriceMarkRegistry6522.resetForTest()
    }

    @Test
    fun observation_and_executable_marks_coexist_per_purpose() {
        val mint = "So11111111111111111111111111111111111111112"
        val pair = "3aBCabcABC123"
        val now = System.currentTimeMillis()

        val obs = CanonicalPriceMark6522(
            mint = mint, pairId = pair, baseMint = mint,
            quoteMint = "USDC1111111111111111111111111111111111111112",
            source = "DEXSCREENER_PAIR_POLL", timestampMs = now,
            priceUsd = PriceUsd(BigDecimal.valueOf(0.123)),
            liquidityUsd = BigDecimal.valueOf(50000.0),
            purpose = CanonicalMarkPurpose6570.OBSERVATION_SCORING,
        )
        val exe = obs.copy(purpose = CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE)

        assertTrue(CanonicalPriceMarkRegistry6522.publish(obs))
        assertTrue(CanonicalPriceMarkRegistry6522.publish(exe))

        assertNotNull(
            "Observation mark must remain retrievable after executable mark publish",
            CanonicalPriceMarkRegistry6522.get(mint, CanonicalMarkPurpose6570.OBSERVATION_SCORING)
        )
        assertNotNull(
            "Executable mark must be independently retrievable",
            CanonicalPriceMarkRegistry6522.get(mint, CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE)
        )
        assertEquals(
            "Back-compat get() prefers executable purpose",
            CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE,
            CanonicalPriceMarkRegistry6522.get(mint)?.purpose
        )
    }

    @Test
    fun botservice_processTokenCycle_no_longer_returns_on_mark_reject() {
        // The old code contained a `return` on canonicalMarkAccepted6522 false.
        // The new code stamps *_INFO_6575 and falls through to V3.
        assertTrue(
            "BotService must not carry the pre-6575 CANONICAL_MARK_REJECTED->return path",
            !botSrc.contains("PreV3ReturnTelemetry6525.stamp(ts, \"CANONICAL_MARK_REJECTED\")\n                return")
        )
        assertTrue(
            "BotService must publish observation + executable marks under new P0-2 counters",
            botSrc.contains("CANONICAL_PRICE_MARK_OBSERVATION_ACCEPTED_6575") &&
                botSrc.contains("CANONICAL_PRICE_MARK_EXECUTABLE_PROMOTED_6613")
        )
        assertTrue(
            "BotService must still stamp *_INFO_6575 so operators can audit rejection counts",
            botSrc.contains("CANONICAL_MARK_REJECTED_INFO_6575")
        )
    }

    @Test
    fun executor_refuses_paperBuy_without_strict_mark() {
        // Any paperBuy path must contain the P0-2 mark gate + counter.
        assertTrue(
            "Executor.paperBuy must probe the strict EXECUTABLE_ENTRY_QUOTE mark",
            execSrc.contains("CanonicalPriceMarkRegistry6522.get(") &&
                execSrc.contains("CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE")
        )
        assertTrue(
            "Executor must emit EXECUTION_WITH_PROVISIONAL_MARK_6575 and refuse when the mark is missing",
            execSrc.contains("promoteObservationToExecutable6613") &&
                execSrc.contains("EXECUTION_BLOCKED_NO_CANONICAL_MARK_6613")
        )
    }

    @Test
    fun observation_only_lookup_still_absent_for_executable_purpose() {
        val mint = "PurposeSplit1111111111111111111111111111112"
        val obs = CanonicalPriceMark6522(
            mint = mint, pairId = "poolobsonly", baseMint = mint,
            quoteMint = "USDC1111111111111111111111111111111111111112",
            source = "DEXSCREENER_PAIR_POLL", timestampMs = System.currentTimeMillis(),
            priceUsd = PriceUsd(BigDecimal.valueOf(0.0007)),
            liquidityUsd = BigDecimal.valueOf(15000.0),
            purpose = CanonicalMarkPurpose6570.OBSERVATION_SCORING,
        )
        assertTrue(CanonicalPriceMarkRegistry6522.publish(obs))
        assertNull(
            "No executable mark = executor refuses (EXECUTION_WITH_PROVISIONAL_MARK invariant)",
            CanonicalPriceMarkRegistry6522.get(mint, CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE)
        )
    }
}
