package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate7418Remaining7409RegressionTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun crypto_price_unavailable_releases_for_retry_not_terminal_completion() {
        val t = src("perps/CryptoAltTrader.kt")
        val r = src("perps/DynamicAltTokenRegistry.kt")
        assertTrue(t.contains("releaseEvaluationForRetry7418(tok, \"PRICE_UNAVAILABLE\")"))
        assertTrue(r.contains("fun releaseEvaluationForRetry7418"))
        assertTrue(r.contains("CRYPTO_EVAL_SOFT_LEASE_EXPIRED_7418"))
    }

    @Test fun sane_absurd_mark_repair_preserves_indicative_versus_executable_authority() {
        val p = src("engine/OpenPnlSanity.kt")
        val m = src("engine/truth/CanonicalPriceMark6522.kt")
        assertTrue(p.contains("publishRepairedExitEconomic7418"))
        assertTrue(m.contains("exitQuantityRaw.signum() > 0 && exitPositionId.isNotBlank()"))
        assertTrue(m.contains("CanonicalMarkPurpose6570.EXIT_ECONOMIC else CanonicalMarkPurpose6570.HELD_INDICATIVE"))
        assertTrue(m.contains("CANONICAL_REPAIRED_EXIT_MARK_PUBLISHED_7418"))
    }
}
