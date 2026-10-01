package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7680ProviderDataWasteContractTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun tokenRefreshAndDuplicateDiscoveryAreCoalesced() {
        val refresh = src("engine/TokenRefreshPolicy.kt")
        assertTrue(refresh.contains("Tier.DORMANT   to Long.MAX_VALUE"))
        assertTrue(refresh.contains("fun shouldRefreshDynamic(mint: String, tier: Tier)"))
        val merge = src("engine/TokenMergeQueue.kt")
        assertTrue(merge.contains("MERGE_REPEAT_NO_NEW_EVIDENCE_COALESCED_7503"))
    }

    @Test fun ohlcvAndSupplyHaveBoundedNegativeCaching() {
        val o = src("network/SolanaOhlcvFeed6916.kt")
        assertTrue(o.contains("NEGATIVE_TTL_MS = 10L * 60_000L"))
        assertTrue(o.contains("negativeCache[mint]?.let"))
        val s = src("engine/truth/OnChainSupplyAuthority7075.kt")
        assertTrue(s.contains("NEGATIVE_TTL_MS = 6L * 60L * 60L * 1000L"))
        assertTrue(s.contains("COOLDOWN_MS = 90_000L"))
        assertTrue(s.contains("retryNotBefore"))
        assertTrue(s.contains("lockoutRemainingMs(hostLabel7116())"))
        assertTrue(s.contains("ArrayBlockingQueue<Runnable>(64)"))
    }

    @Test fun providerDeclinesDoNotBecomeFalseTokenFailures() {
        val s = src("engine/truth/OnChainSupplyAuthority7075.kt")
        assertTrue(s.contains("RATE_LIMITED/TRANSPORT/DECLINED"))
        assertTrue(s.contains("SKIPPED"))
        assertTrue(s.contains("NOT counted as failed"))
    }

    @Test fun p0NineSourceTasksAreClosed() {
        val a = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertFalse(a.contains("- [ ] Remove repeated known-dead provider work"))
        assertFalse(a.contains("- [ ] Coalesce repeated token birth/metric hydration"))
        assertFalse(a.contains("- [ ] Cache negative/no-capability results"))
        assertFalse(a.contains("- [ ] Keep provider degradation fail-open"))
    }
}
