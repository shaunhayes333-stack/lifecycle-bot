package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.TokenFacts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8764 — code audit (data P0-3): no intake seed becomes a label's basis or outcome mark. */
class Aate8764NoSeedLabelMarkTest {
    @Test fun every_seed_label_is_a_seed() {
        for (s in listOf("PUMP_FUN_BC_SYNTHETIC", "CHAIN_SUPPLY_CAP_SEED_7280", "PUMP_FUN_SOURCE_NATIVE_MCAP_SEED_6401", "SYNTHETIC_FALLBACK"))
            assertTrue(s, TokenFacts.seedPrice8559(s))
        for (s in listOf("DEXSCREENER_PAIR_POLL", "PUMP_PORTAL_TRADE_WS_7278", "CURVE_ACCOUNT", "", null))
            assertFalse("$s", TokenFacts.seedPrice8559(s))
    }

    @Test fun the_token_state_candidate_refuses_a_seed() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(src.contains("ts.lastPrice == px && !TokenFacts.seedPrice8559(ts.lastPriceSource))"))
    }
}
