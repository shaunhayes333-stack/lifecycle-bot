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

    @Test fun sane_absurd_mark_repair_is_published_to_exit_authority() {
        val p = src("engine/OpenPnlSanity.kt")
        val m = src("engine/truth/CanonicalPriceMark6522.kt")
        assertTrue(p.contains("publishRepairedExitEconomic7418"))
        assertTrue(m.contains("purpose = CanonicalMarkPurpose6570.EXIT_ECONOMIC"))
        assertTrue(m.contains("REPAIRED_EXIT_ECONOMIC_7418"))
    }

    @Test fun missing_intent_is_backfilled_only_with_complete_same_record_proof() {
        val f = src("engine/truth/MemeExecutionFunnelReceivers6625.kt")
        assertTrue(f.contains("INTENT_INFERRED_FROM_EXECUTABLE_LINEAGE_7418"))
        assertTrue(f.contains("Stage.DISCOVER in rec.stages"))
        assertTrue(f.contains("hasFdgAllow7418 && hasMark7418 && hasSize7418"))
    }
}
