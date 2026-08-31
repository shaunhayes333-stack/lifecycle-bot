package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalMarkPurpose6570
import com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate6616CausalP0RepairTest {
    @Test fun fresh_valid_source_resolves_executable_mark_synchronously_without_optional_pair_metadata() {
        CanonicalPriceMarkRegistry6522.resetForTest()
        val now = System.currentTimeMillis()
        val result = CanonicalPriceMarkRegistry6522.resolveExecutableFromSourceEvidence6616(
            mint = "Mint6616",
            observedBaseMint = "",
            pairOrPool = "",
            quoteMint = "",
            source = "DEXSCREENER_PAIR_POLL",
            priceUsd = 0.000123,
            liquidityUsd = 42_000.0,
            evidenceTimestampMs = now,
            nowMs = now,
        )
        assertTrue(result.reason, result.promoted)
        val mark = CanonicalPriceMarkRegistry6522.get("Mint6616", CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE)
        assertNotNull(mark)
        assertEquals("Mint6616", mark!!.baseMint)
        assertEquals("MINT_ROUTE:Mint6616", mark.pairId)
        assertEquals("USD", mark.quoteMint)
    }

    @Test fun resolver_rejects_mismatched_identity_stale_price_and_missing_liquidity() {
        CanonicalPriceMarkRegistry6522.resetForTest()
        val now = System.currentTimeMillis()
        fun resolve(base: String = "Mint6616B", ts: Long = now, liq: Double = 10_000.0) =
            CanonicalPriceMarkRegistry6522.resolveExecutableFromSourceEvidence6616(
                mint = "Mint6616B", observedBaseMint = base, pairOrPool = "pair6616",
                quoteMint = "USDC", source = "DEXSCREENER_PAIR_POLL", priceUsd = 1.0,
                liquidityUsd = liq, evidenceTimestampMs = ts, nowMs = now,
            )
        assertEquals("SOURCE_BASE_IDENTITY_MISMATCH", resolve(base = "WrongMint").reason)
        assertEquals("SOURCE_EVIDENCE_STALE", resolve(ts = now - 300_001L).reason)
        assertEquals("SOURCE_LIQUIDITY_INVALID", resolve(liq = 0.0).reason)
    }

    @Test fun supervisor_force_release_requires_monotonic_ttl_progress_and_active_generation() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("startedMonotonicMs"))
        assertTrue(bot.contains("SystemClock.elapsedRealtime()"))
        assertTrue(bot.contains("age >= SUPERVISOR_LEASE_TTL_MS"))
        assertTrue(bot.contains("sinceProgress >= SUPERVISOR_LEASE_PROGRESS_TTL_MS"))
        assertTrue(bot.contains("job?.isActive == true"))
        assertTrue(bot.contains("supervisorLeases.remove(e.key, lease)"))
        assertTrue(bot.contains("SUPERVISOR_FORCE_RELEASE_DEFERRED_YOUNG_6616"))
        assertFalse(bot.contains("SupervisorLease(mint = mint, startedMs = System.currentTimeMillis()"))
    }

    @Test fun pre_v3_and_executor_consume_the_same_canonical_resolver() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val executor = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val registry = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPriceMark6522.kt").readText()
        assertEquals(1, Regex("resolveExecutableFromSourceEvidence6616").findAll(bot).count())
        assertEquals(1, Regex("resolveExecutableFromSourceEvidence6616").findAll(executor).count())
        assertTrue(registry.contains("resolveExecutableFromSourceEvidence6616"))
        assertTrue(registry.contains("return promoteObservationToExecutable6613(mint, nowMs)"))
    }
}
