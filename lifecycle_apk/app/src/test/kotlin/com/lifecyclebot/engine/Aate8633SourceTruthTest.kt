package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8633 — audit_8629 D6 (one endpoint, one name; aliases count once) and D8 (held / high-touch coins are not
 * re-offered to the buy lanes on boot). Pure rules first, then the wiring pinned at the source.
 */
class Aate8633SourceTruthTest {

    private fun src(rel: String): String = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private val nextFun = Regex("\n    (private |internal )?(suspend )?fun ")

    /** The body of one member function (up to the next member function). */
    private fun body(text: String, name: String): String {
        val start = text.indexOf("fun $name(")
        assertTrue("function $name exists", start >= 0)
        val end = nextFun.find(text, start + 1)?.range?.first ?: text.length
        return text.substring(start, end)
    }

    private val tokenSourceRef = Regex("TokenSource\\.([A-Z_]+)")

    /** Every member function of [text] whose body reads [endpoint] names only [label] as its TokenSource. */
    private fun readersEmitOnly(text: String, endpoint: String, label: String): Int {
        var readers = 0
        for (m in Regex("\n    (private |internal )?(suspend )?fun ([A-Za-z0-9_]+)\\(").findAll(text)) {
            val b = body(text, m.groupValues[3])
            if (!b.contains(endpoint)) continue
            val labels = tokenSourceRef.findAll(b).map { it.groupValues[1] }.toSet()
            if (labels.isEmpty()) continue
            readers++
            assertEquals("${m.groupValues[3]} reads $endpoint", setOf(label), labels)
        }
        return readers
    }

    // ── endpoint identity (pure) ─────────────────────────────────────────────

    @Test fun aliasesOfOneEndpointResolveToOneName() {
        assertEquals("PUMP_PORTAL", SourceTruth8633.endpointOf8633("PUMP_PORTAL_WS"))
        assertEquals("PUMP_PORTAL", SourceTruth8633.endpointOf8633("pumpportal"))
        assertEquals("RAYDIUM_NEW_POOL", SourceTruth8633.endpointOf8633("RAYDIUM_NEW"))
        assertEquals(SourceTruth8633.DEX_PROFILES_8633, SourceTruth8633.endpointOf8633("DEX_GAINERS"))
        assertEquals(SourceTruth8633.DEX_BOOSTS_8633, SourceTruth8633.endpointOf8633("DEX_BOOSTS"))
        assertEquals(SourceTruth8633.MARKET_SWEEP_8633, SourceTruth8633.endpointOf8633("MARKET_HUNT_QUALITY"))
        assertEquals(SourceTruth8633.MARKET_SWEEP_8633, SourceTruth8633.endpointOf8633("MARKET_HUNT_SHITCOIN"))
        assertEquals(SourceTruth8633.METEORA_POOL_8633, SourceTruth8633.endpointOf8633("METEORA_POOL"))
        assertEquals("PUMP_FUN_NEW", SourceTruth8633.endpointOf8633("PUMP_FUN_NEW"))
        assertEquals("DEX_PROFILES", SourceTruth8633.DEX_PROFILES_8633)
        assertEquals("DEX_BOOSTED", SourceTruth8633.DEX_BOOSTS_8633)
    }

    @Test fun sameEndpointIsNoSecondScanner() {
        assertTrue(SourceTruth8633.sameEndpointAsAny8633("PUMP_PORTAL", setOf("PUMP_PORTAL_WS")))
        assertTrue(SourceTruth8633.sameEndpointAsAny8633("MARKET_HUNT_SHITCOIN", setOf("MARKET_HUNT_QUALITY")))
        assertFalse(SourceTruth8633.sameEndpointAsAny8633("DEX_BOOSTED", setOf("DEX_PROFILES")))
        assertFalse(SourceTruth8633.sameEndpointAsAny8633("", setOf("DEX_PROFILES")))
        assertFalse(SourceTruth8633.sameEndpointAsAny8633("DEX_PROFILES", setOf("")))
    }

    @Test fun endpointCountCountsFeedsNotNames() {
        assertEquals(1, SourceTruth8633.endpointCount8633(setOf("PUMP_PORTAL", "PUMP_PORTAL_WS")))
        assertEquals(1, SourceTruth8633.endpointCount8633(setOf("MARKET_HUNT_QUALITY", "MARKET_HUNT_SHITCOIN")))
        assertEquals(2, SourceTruth8633.endpointCount8633(setOf("DEX_PROFILES", "DEX_BOOSTED")))
        assertEquals(1, SourceTruth8633.endpointCount8633(setOf("SCANNER_DIRECT_DEX_PROFILES", "DEX_PROFILES")))
        assertEquals(1, SourceTruth8633.endpointCount8633(setOf("SCANNER_DIRECT")))
        assertEquals(0, SourceTruth8633.endpointCount8633(emptySet()))
    }

    @Test fun independentSourceCountUsesTheEndpointTable() {
        assertEquals(1, ScannerSourceTags8292.independentSourceCount8292("MARKET_HUNT_QUALITY", setOf("MARKET_HUNT_SHITCOIN")))
        assertEquals(1, ScannerSourceTags8292.independentSourceCount8292("SCANNER_DIRECT_DEX_PROFILES", setOf("DEX_PROFILES", "SCANNER_DIRECT")))
        assertEquals(2, ScannerSourceTags8292.independentSourceCount8292("DEX_PROFILES", setOf("DEX_BOOSTED")))
        assertEquals(1, ScannerSourceTags8292.independentSourceCount8292("PUMP_PORTAL_WS", setOf("PUMPPORTAL")))
        // the boot warmup is a pathway: one remembered winner is one source
        assertEquals(1, ScannerSourceTags8292.independentSourceCount8292("HOT_CONVICTION_WARMUP_6282", setOf("WIN_MEMORY", "HOT_CONVICTION_WARMUP_6282")))
    }

    // ── venue from facts, not from the feed name (pure) ─────────────────────

    @Test fun venueComesFromTheMintOnlyForVenueAgnosticFeeds() {
        val pumpMint = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hrpump"
        val plainMint = "So11111111111111111111111111111111111111112"
        assertEquals(SourceTruth8633.VENUE_PUMP_FUN_8633, SourceTruth8633.venueFromMint8633(pumpMint))
        assertEquals("", SourceTruth8633.venueFromMint8633(plainMint))
        assertEquals(setOf(SourceTruth8633.VENUE_PUMP_FUN_8633), SourceTruth8633.venueTags8633("SCANNER_DIRECT_DEX_PROFILES", setOf("DEX_PROFILES", "SCANNER_DIRECT"), pumpMint))
        assertEquals(setOf(SourceTruth8633.VENUE_PUMP_FUN_8633), SourceTruth8633.venueTags8633("DEX_BOOSTED", emptySet(), pumpMint))
        assertTrue(SourceTruth8633.venueTags8633("DEX_PROFILES", emptySet(), plainMint).isEmpty())
        // a source that already names its venue (or a hunt) is not widened
        assertTrue(SourceTruth8633.venueTags8633("MARKET_HUNT_QUALITY", emptySet(), pumpMint).isEmpty())
        assertTrue(SourceTruth8633.venueTags8633("RAYDIUM_NEW_POOL", setOf("RAYDIUM_NEW_POOL"), pumpMint).isEmpty())
        assertEquals("DEX_PROFILES|VENUE_PUMP_FUN", SourceTruth8633.withVenueHint8633("DEX_PROFILES", pumpMint))
        assertEquals("DEX_PROFILES", SourceTruth8633.withVenueHint8633("DEX_PROFILES", plainMint))
        assertEquals("BIRDEYE_TRENDING", SourceTruth8633.withVenueHint8633("BIRDEYE_TRENDING", pumpMint))
    }

    // ── D8 (pure) ────────────────────────────────────────────────────────────

    @Test fun heldAndHighTouchRowsAreNeverReseeded() {
        assertTrue(HotConvictionWarmup.reseedEligible8633("WIN_MEMORY", held = false))
        assertFalse("a held winner is the exit supervisor's", HotConvictionWarmup.reseedEligible8633("WIN_MEMORY", held = true))
        assertFalse(HotConvictionWarmup.reseedEligible8633("LIVE_HELD", held = false))
        assertFalse(HotConvictionWarmup.reseedEligible8633("watchlist_high_touch", held = false))
    }

    // ── wiring pins ──────────────────────────────────────────────────────────

    @Test fun everyEndpointIsEmittedUnderItsOwnName() {
        val scanner = src("engine/SolanaMarketScanner.kt")
        assertEquals(7, readersEmitOnly(scanner, "token-profiles/latest", "DEX_PROFILES"))
        // scanPumpFunVolume and scanPumpGraduates read token-boosts/top; scanDexBoosted hands its URLs to scanDexBoostedFrom8179
        assertEquals(2, readersEmitOnly(scanner, "token-boosts/", "DEX_BOOSTED"))
        assertEquals(setOf("DEX_BOOSTED"), tokenSourceRef.findAll(body(scanner, "scanDexBoostedFrom8179")).map { it.groupValues[1] }.toSet())
        assertEquals(1, readersEmitOnly(scanner, "dexes/meteora/pools", "METEORA_POOL"))
        assertEquals(2, readersEmitOnly(scanner, "networks/solana/trending_pools?", "COINGECKO_TRENDING"))
        assertTrue(body(scanner, "scanEmergencySweepRows8128").contains("source = TokenSource.MARKET_SWEEP,"))
        assertFalse("no label-by-age", body(scanner, "scanDexGainers").contains("ageHours < 6 -> TokenSource."))
        for (v in listOf("DEX_PROFILES,", "METEORA_POOL,", "MARKET_SWEEP,")) assertTrue(scanner.contains("        $v\n"))
        // the new names are source buckets exactly as the labels they replace, never execution lanes
        for (v in listOf("DEX_PROFILES", "METEORA_POOL", "MARKET_SWEEP")) {
            assertFalse(ExecutableOpenGate.isRealExecutionLaneForTests7117(v))
            assertTrue(ExecutableOpenGate.lanesCompatibleForTests("MOONSHOT", v))
        }
    }

    @Test fun countersReadTheEndpointTable() {
        val intake = src("engine/ScannerIntake8292.kt")
        assertTrue(intake.contains(".map { SourceTruth8633.endpointOf8633(it) }"))
        assertTrue(intake.contains("setOf(\"CHAIN_LAUNCH_8179\", \"PROBATION\", \"HOT_CONVICTION_WARMUP_6282\")"))
        val reg = src("engine/GlobalTradeRegistry.kt")
        assertEquals(3, Regex("SourceTruth8633\\.sameEndpointAsAny8633\\(").findAll(reg).count())
        val queue = src("engine/TokenMergeQueue.kt")
        assertTrue(queue.contains("val independent8633 = SourceTruth8633.endpointCount8633(scanners)"))
        assertTrue(queue.contains("val multiSourceBonus = when (independent8633) {"))
        assertEquals(2, Regex("multiScannerBoost = SourceTruth8633\\.endpointCount8633\\(entry\\.scanners\\) > 1").findAll(queue).count())
        assertTrue(queue.contains("pendingDiscoveries[mint]?.scanners?.let { SourceTruth8633.endpointCount8633(it) } ?: 0"))
    }

    @Test fun venueRulesReadTheMint() {
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("val sources = feedSources + SourceTruth8633.venueTags8633(source, feedSources, mint)"))
        assertEquals(4, Regex("intakeAffinity8394\\([^)]*claims8394, mint\\)").findAll(bot).count())
        val manip = src("v3/scoring/ManipulatedTraderAI.kt")
        assertTrue(manip.contains("calcManipScore(bundlePct, buyPressurePct, momentum, venueSource8633, ageMinutes, rugcheckScore)"))
    }

    @Test fun bootWarmupReseedsNoHeldOrHighTouchCoin() {
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("val hotList = HotConvictionWarmup.reseedCandidates8633 { heldAtBoot8633(it) }"))
        assertTrue(bot.contains("HeldPositionSupervisor7246.isHeld(mint) || PositionPersistence.hasPersistedPosition(mint)"))
        val warm = src("engine/HotConvictionWarmup.kt")
        assertFalse(warm.contains("source = \"LIVE_HELD\""))
        assertFalse(warm.contains("source = \"WATCHLIST_HIGH_TOUCH\""))
        assertTrue(warm.contains("if (w.mint in held8633) continue"))
        assertTrue(warm.contains("if (!reseedEligible8633(o.optString(\"source\"), held = false)) continue"))
    }

    // V5.0.8693 — superseded: no discovery source earns the PumpPortal route; only an active pump.fun curve does.
    @Test fun the_profiles_feed_no_longer_earns_the_pumpportal_route() {
        val ex = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertFalse(ex.contains("srcUpperForRoute.contains(com.lifecyclebot.engine.SourceTruth8633.DEX_PROFILES_8633)"))
    }
}
