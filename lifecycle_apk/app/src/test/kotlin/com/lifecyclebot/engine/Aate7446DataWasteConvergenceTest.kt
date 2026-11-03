package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7446DataWasteConvergenceTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun scanner_dedupes_before_rugcheck_cost() {
        val s = src("engine/SolanaMarketScanner.kt")
        val fn = s.substringAfter("private suspend fun emitWithRugcheck")
            .substringBefore("private val dexFeedCache7381")
        assertTrue(fn.contains("ScannerCanonicalDedupe6411.shouldEnrich"))
        assertTrue(fn.indexOf("ScannerCanonicalDedupe6411.shouldEnrich") < fn.indexOf("quickRugcheck(token.mint)"))
        assertTrue(fn.contains("SCANNER_PRE_RUGCHECK_DEDUPE_7446"))
    }

    @Test fun scanner_dedupe_uses_full_identity_and_enforces_memory_cap() {
        val s = src("engine/truth/ScannerCanonicalDedupe6411.kt")
        assertTrue(s.contains("return \"\${mint.trim()}|\${pool.trim()}|\${sourceFamily(source)}|\$bucket\""))
        assertFalse(s.contains("mint.take(24)"))
        assertTrue(s.contains("SCANNER_DEDUPE_CAP_TRIM_7446"))
    }

    @Test fun unconvertedPairRatioAndPaidSubHourCallsCannotPoisonUsdHistory() {
        val s = src("network/SolanaOhlcvFeed6916.kt")
        assertFalse(s.contains("PAPRIKA_BASE_7293"))
        assertFalse(s.contains("fetchDexPaprika7293"))
        assertTrue(s.contains("currency=usd&token=$" + "mint&include_empty_intervals=false"))
        assertTrue(src("network/DexscreenerApi.kt").contains("fetchDexPaprikaToken6512(tokenAddress)"))
    }

    @Test fun transient_ohlcv_failures_remain_retryable() {
        val s = src("network/SolanaOhlcvFeed6916.kt")
        assertTrue(s.contains("code == 429 || code >= 500"))
        assertTrue(s.contains("cooldownUntilMs.set"))
    }
}
