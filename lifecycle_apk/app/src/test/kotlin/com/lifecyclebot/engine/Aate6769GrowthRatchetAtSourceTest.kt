package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6769 — GROWTH_RATCHET_AT_SOURCE.
 *
 * Operator directive Feb 2026: "starts at over $1000 dumps all of it into
 * positions and doesn't grow. I get it reinvests into new trades but its
 * not increasing the balance. member 5x growth targets!!!!"
 *
 * Source root cause: OrderSizeResolver6441.authoritativeCash exposed the
 * raw PaperCapitalAuthority6577.cashSol() as the sizing cap. Every SELL
 * returned SOL to cash and the NEXT queued admission in a 100-position
 * book consumed it in the same tick — compounding was structurally
 * impossible even under a positive WR × edge.
 *
 * Fix at source (this resolver, one file, no new authority): reserve 30%
 * of TOTAL EQUITY as untouchable dry powder before sizing sees the cash
 * cap. Reserve grows monotonically with equity so wins compound INTO the
 * wallet balance instead of being immediately spent on position #101.
 */
class Aate6769GrowthRatchetAtSourceTest {

    private val src = java.io.File(
        "src/main/kotlin/com/lifecyclebot/engine/truth/OrderSizeResolver6441.kt"
    ).readText()

    @Test
    fun aate6769_growth_reserve_is_computed_before_cash_cap() {
        assertTrue(
            "V5.0.6769: growth reserve constant must be applied at the sizing authority",
            src.contains("growthReserveSol6769") &&
                src.contains("PaperCapitalAuthority6577.totalEquitySol()") &&
                src.contains("equity * 0.30")
        )
    }

    @Test
    fun aate6769_cash_cap_is_the_reserved_deployable_cash() {
        assertTrue(
            "V5.0.6769: cashCap must equal deployableCash6769 (raw cash − growth reserve)",
            src.contains("val deployableCash6769 = (authoritativeCash - growthReserveSol6769).coerceAtLeast(0.0)") &&
                src.contains("val cashCap = deployableCash6769")
        )
    }

    @Test
    fun aate6769_fee_reserve_is_computed_from_deployable_cash() {
        assertTrue(
            "V5.0.6769: PAPER fee reserve must be derived from deployable cash, not raw cash",
            src.contains("deployableCash6769 / (1.0 + PAPER_ENTRY_FEE_RESERVE_RATE_6490)")
        )
    }

    @Test
    fun aate6769_live_mode_untouched() {
        // Live mode reserve is 0.0 — sizing behaviour on live is unchanged.
        assertTrue(
            "V5.0.6769: live mode must not apply the paper growth reserve",
            src.contains("val growthReserveSol6769 = if (paperMode) {") &&
                src.contains("} else 0.0")
        )
    }

    @Test
    fun aate6769_ratchet_telemetry_label_emitted() {
        assertTrue(
            "V5.0.6769: growth ratchet must emit GROWTH_RATCHET_RESERVE_APPLIED_6769",
            src.contains("GROWTH_RATCHET_RESERVE_APPLIED_6769")
        )
    }
}
