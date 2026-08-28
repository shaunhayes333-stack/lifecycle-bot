package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6578 — P1-1, P1-2, P1-4 source-level guarantees.
 *
 * P1-1  CryptoAlt paper path calls the same canonical dispatch/confirm/fail
 *       lifecycle as the live path (operator forensic showed intent=3 but
 *       dispatch=0 open=0 unexplained=3).
 *
 * P1-2  Perps producer emits an attributable terminal reason for every scan
 *       (no silent zero-candidate windows).
 *
 * P1-4  Duplicate terminal-sell attempts increment DUPLICATE_TERMINAL_MUTATION_6578
 *       so runaway close loops are directly visible to the operator invariant
 *       'duplicate close loops = 0'.
 */
class P1RepairSourceCoverage6578Test {

    private val cryptoAltSrc = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
    private val perpsEngineSrc = File("src/main/kotlin/com/lifecyclebot/perps/PerpsExecutionEngine.kt").readText()
    private val ledgerSrc = File("src/main/kotlin/com/lifecyclebot/engine/truth/PositionStateLedger6454.kt").readText()

    @Test
    fun p1_1_crypto_alt_paper_dispatch_parity() {
        assertTrue(
            "Paper branch must call CanonicalEntryAuthority6551.markDispatch before the canonical open",
            cryptoAltSrc.contains("CanonicalEntryAuthority6551.markDispatch(canonicalCryptoIntent6565)\n            val canonicalOpen6486")
        )
        assertTrue(
            "Paper branch must call CanonicalEntryAuthority6551.markConfirmed after a successful open",
            cryptoAltSrc.contains("V5.0.6578 — success confirms the paper dispatch produced a canonical open") &&
                cryptoAltSrc.contains("CanonicalEntryAuthority6551.markConfirmed(canonicalCryptoIntent6565, position.id)")
        )
    }

    @Test
    fun p1_2_perps_emits_terminal_state_on_zero_scan() {
        assertTrue(
            "PerpsExecutionEngine must emit PERPS_MARKET_DATA_EMPTY_6578 when scanners return no results",
            perpsEngineSrc.contains("PERPS_MARKET_DATA_EMPTY_6578")
        )
        assertTrue(
            "PerpsExecutionEngine must emit PERPS_SIGNAL_NONE_6578 when data is OK but no signal fired",
            perpsEngineSrc.contains("PERPS_SIGNAL_NONE_6578")
        )
    }

    @Test
    fun p1_4_duplicate_terminal_mutation_invariant_present() {
        assertTrue(
            "PositionStateLedger6454 must count duplicate close attempts under DUPLICATE_TERMINAL_MUTATION_6578",
            ledgerSrc.contains("DUPLICATE_TERMINAL_MUTATION_6578")
        )
        // Both duplicate-close paths (already-CLOSING + already-CLOSED) must increment the invariant.
        val hits = Regex("DUPLICATE_TERMINAL_MUTATION_6578").findAll(ledgerSrc).count()
        assertTrue(
            "Both duplicate lifecycles (already CLOSING + already CLOSED) must feed the invariant",
            hits >= 2
        )
    }
}
