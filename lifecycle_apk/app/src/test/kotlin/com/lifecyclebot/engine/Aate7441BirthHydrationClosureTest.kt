package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7441BirthHydrationClosureTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun rpc_hydrator_pages_to_oldest_blocktime_without_observation_fallback() {
        val s = src("engine/truth/TokenBirthHydrator7441.kt")
        assertTrue(s.contains("getSignaturesForAddress"))
        assertTrue(s.contains("blockTime"))
        assertTrue(s.contains("PAGES_PER_ATTEMPT"))
        assertTrue(s.contains("before"))
        assertTrue(s.contains("TokenMetaCache.get(ctx).register"))
        assertFalse(s.contains("addedToWatchlistAt"))
        assertFalse(s.contains("firstSeenMs"))
    }

    @Test fun canonical_birth_requests_active_hydration_on_miss() {
        val s = src("engine/truth/CanonicalTokenBirthTime7440.kt")
        assertTrue(s.contains("TokenBirthHydrator7441.request(m)"))
    }

    @Test fun secondary_age_consumers_do_not_use_watchlist_or_first_candle_age() {
        val bridge = src("v3/MemeUnifiedScorerBridge.kt")
        val orth = src("engine/OrthogonalSignals.kt")
        val feat = src("engine/CanonicalFeaturesBuilder.kt")
        val life = src("engine/LifecycleStrategy.kt")
        assertFalse(bridge.contains("nowMs - ts.addedToWatchlistAt"))
        assertFalse(orth.contains("System.currentTimeMillis() - ts.addedToWatchlistAt"))
        assertFalse(feat.contains("entryAt - ts.addedToWatchlistAt"))
        assertFalse(life.contains("System.currentTimeMillis() - (hist.first().ts)"))
        assertFalse(life.contains("System.currentTimeMillis() - ts.addedToWatchlistAt"))
    }

    @Test fun unresolved_age_defers_features_instead_of_fabricating_numeric_age() {
        val adapter = src("v3/bridge/V3Adapter.kt")
        val feat = src("engine/CanonicalFeaturesBuilder.kt")
        val rug = src("engine/HardRugPreFilter.kt")
        assertTrue(adapter.contains("BIRTH_METADATA_HYDRATING_7441"))
        assertFalse(adapter.contains("?: Double.NaN"))
        assertTrue(feat.contains("BIRTH_METADATA_HYDRATING"))
        assertTrue(rug.contains("RUG_PREFILTER_BIRTH_HYDRATING_7441"))
    }

    @Test fun lifecycle_launch_mode_requires_resolved_birth() {
        val s = src("engine/LifecycleStrategy.kt")
        assertTrue(s.contains("tokenAgeMins != null && tokenAgeMins <= 15.0"))
        assertTrue(s.contains("requireNotNull(tokenAgeMins)"))
        assertTrue(s.contains("ADAPTIVE_AGE_DEFERRED_7441"))
    }
}
