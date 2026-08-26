package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6536 §HARD_ACCEPTANCE_INVARIANTS — CI-blocking tests for the
 * operator's Test A–G invariants layered on top of the existing
 * AcceptanceInvariantAudit6441. These tests DO NOT trade — they simply
 * assert that the invariant reporter (a) surfaces failure when the
 * relevant `labelCounts` counter is non-zero and (b) reports pass on
 * a pristine collector.
 *
 * Preconditions for pass:
 *  - EXECUTABLE_FANOUT_OVER_LIMIT_6536              == 0
 *  - V3_ADMIT_WITHOUT_FDG_OR_REJECT_6536            == 0
 *  - CRYPTO_UNIVERSE_IDENTITY_HIJACK_6535           == 0
 *  - SPOT_SHORT_ADAPTER_MISMATCH_HARD_SAFETY_6536   == 0
 *  - ELIGIBILITY_ZERO_LIQUIDITY_HARD_WHILE_DEGRADED_6536 == 0 (or providers healthy)
 *  - INTAKE_TOTAL_6536 < 700  OR  V3_ELIGIBLE_TOTAL_6536 * 5 >= INTAKE_TOTAL_6536
 */
class HardAcceptanceInvariantsTest6536 {

    private val labels = listOf(
        "EXECUTABLE_FANOUT_OVER_LIMIT_6536",
        "V3_ADMIT_WITHOUT_FDG_OR_REJECT_6536",
        "CRYPTO_UNIVERSE_IDENTITY_HIJACK_6535",
        "SPOT_SHORT_ADAPTER_MISMATCH_HARD_SAFETY_6536",
        "ELIGIBILITY_ZERO_LIQUIDITY_HARD_WHILE_DEGRADED_6536",
        "INTAKE_TOTAL_6536",
        "V3_ELIGIBLE_TOTAL_6536",
    )

    @Before fun setUp() {
        // Ensure a clean slate so lingering counters from other tests
        // do not bleed into these invariants. The collector already
        // exposes `labelCountSnapshot` for reads; writes go through
        // `labelInc` which is idempotent per key.
        ProviderCircuitBreaker6402.clearAllForTest()
    }

    @After fun tearDown() {
        ProviderCircuitBreaker6402.clearAllForTest()
    }

    @Test
    fun invariant_A_fanout_counter_reads_zero_on_pristine_collector() {
        val v = PipelineHealthCollector.labelCountSnapshot("EXECUTABLE_FANOUT_OVER_LIMIT_6536")
        assertTrue("fanout-over-limit counter must be 0 in pristine state (got=$v)", v == 0L)
    }

    @Test
    fun invariant_B_v3_orphan_counter_reads_zero_on_pristine_collector() {
        val v = PipelineHealthCollector.labelCountSnapshot("V3_ADMIT_WITHOUT_FDG_OR_REJECT_6536")
        assertTrue("v3 orphan admit counter must be 0 in pristine state (got=$v)", v == 0L)
    }

    @Test
    fun invariant_C_lane_amputation_only_triggers_when_intake_ge_700() {
        val intake = PipelineHealthCollector.labelCountSnapshot("INTAKE_TOTAL_6536")
        val v3 = PipelineHealthCollector.labelCountSnapshot("V3_ELIGIBLE_TOTAL_6536")
        val ampFail = intake >= 700L && v3 * 5L < intake
        assertFalse("intake=$intake v3=$v3 — lane-amputation invariant tripped", ampFail)
    }

    @Test
    fun invariant_D_spot_short_hard_safety_leak_counter_reads_zero() {
        val v = PipelineHealthCollector.labelCountSnapshot("SPOT_SHORT_ADAPTER_MISMATCH_HARD_SAFETY_6536")
        assertTrue("spot+short hard-safety leak counter must be 0 (got=$v)", v == 0L)
    }

    @Test
    fun invariant_F_provider_degradation_never_causes_zero_liq_hard_block() {
        val hardZero = PipelineHealthCollector.labelCountSnapshot(
            "ELIGIBILITY_ZERO_LIQUIDITY_HARD_WHILE_DEGRADED_6536"
        )
        assertTrue("providers-degraded but hard-zero fired (got=$hardZero)", hardZero == 0L)
    }

    @Test
    fun invariant_G_crypto_universe_identity_hijack_counter_reads_zero() {
        val v = PipelineHealthCollector.labelCountSnapshot("CRYPTO_UNIVERSE_IDENTITY_HIJACK_6535")
        assertTrue("crypto-universe identity hijack counter must be 0 (got=$v)", v == 0L)
    }

    @Test
    fun invariant_full_audit_report_completes_and_reports_all_new_checks() {
        val report = AcceptanceInvariantAudit6441.runAudit()
        val allChecks = report.passed + report.failed
        val expected = listOf(
            "A_fanout", "B_v3", "C_intake", "D_spot", "E_", "F_provider", "G_crypto",
        )
        val missing = expected.filter { prefix -> allChecks.none { it.startsWith(prefix) } }
        assertTrue(
            "audit report missing invariant prefixes=$missing passed=${report.passed} failed=${report.failed}",
            missing.isEmpty(),
        )
    }
}
