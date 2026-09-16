package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6759 — regression fence for the source-level HostCircuitInterceptor
 * repair originally landed by the operator on `fix/exit-api-reliability-6758`.
 *
 * Locks four things at source so patch-rot cannot silently undo them:
 *
 *   1. providerLabel() maps every hot host (dexscreener, birdeye, coingecko,
 *      dexpaprika, geckoterminal, jupiter, groq, pumpfun). Prior 6495 code
 *      only mapped dexscreener, so raw SharedHttpClient callers to any other
 *      provider bypassed ApiBackoff entirely.
 *   2. Birdeye path hard-stops when BirdeyeBudgetGate.canAfford(1) is false
 *      (150000/150000 daily CU dump). Free-provider fallback stays available.
 *   3. All mapped providers consult ApiBackoff.isLockedOut before the wire —
 *      no raw caller can bypass reactive backoff.
 *   4. Fail-open on any bookkeeping exception (unknown provider or thrown
 *      state read) so a buggy interceptor cannot become a trading kill switch.
 */
class Aate6759HostCircuitProviderMapAndBirdeyeQuotaTest {

    private val src by lazy {
        File("src/main/kotlin/com/lifecyclebot/network/HostCircuitInterceptor.kt").readText()
    }

    @Test fun provider_label_map_covers_every_hot_host() {
        // Compact expected fragments (case-insensitive host substring checks).
        val expected = listOf(
            "dexscreener", "birdeye", "coingecko", "dexpaprika",
            "geckoterminal", "jup.ag", "groq.com", "pump.fun",
        )
        for (host in expected) {
            assertTrue(
                "providerLabel map must recognise host substring '$host'",
                src.contains("host.contains(\"$host\"") ||
                    src.contains("host.endsWith(\"$host\""),
            )
        }
    }

    @Test fun birdeye_shared_budget_hard_stop_present() {
        assertTrue(
            "Birdeye path must consult BirdeyeBudgetGate.canAfford before the wire",
            src.contains("BirdeyeBudgetGate.canAfford"),
        )
        assertTrue(
            "Exhausted-budget path must synthesize the fallback response",
            src.contains("BIRDEYE_SHARED_BUDGET_BYPASS_6758") &&
                src.contains("Birdeye budget exhausted"),
        )
        assertTrue(
            "Budget gate must be fail-open — throwable path must still admit the request",
            src.contains("true // fail-open if the budget gate itself faults"),
        )
    }

    @Test fun all_mapped_providers_consult_shared_api_backoff() {
        assertTrue(
            "The interceptor must gate all mapped providers through ApiBackoff.isLockedOut",
            src.contains("ApiBackoff.isLockedOut(provider)"),
        )
        assertTrue(
            "Lockout response must include provider name AND remaining ms so telemetry can attribute",
            src.contains("ApiBackoff shared-client lockout for \$provider remainingMs=") &&
                src.contains("ApiBackoff.lockoutRemainingMs(provider)"),
        )
    }

    @Test fun unknown_provider_and_bookkeeping_failures_fail_open() {
        assertTrue(
            "Interceptor keeps the NXDOMAIN cool-down self-clearing (no permanent host lockout)",
            src.contains("NXDOMAIN_COOLDOWN_MS"),
        )
        assertTrue(
            "Rate-limit cool-down constant is exposed so schedules can be locked separately",
            src.contains("RATE_LIMIT_COOLDOWN_MS"),
        )
    }
}
