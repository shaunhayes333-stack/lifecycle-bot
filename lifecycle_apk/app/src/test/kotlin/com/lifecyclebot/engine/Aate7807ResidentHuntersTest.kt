package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.LaneHunter7297
import com.lifecyclebot.engine.market.MarketSweep7297
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.7807 — resident market sweep / lane hunters.
 * Root cause: the sweep only ran as a scanner batch source under a 5 s source /
 * 8 s batch budget, so it never completed ("no sweep yet", hunted=0, rebuilds=0).
 */
class Aate7807ResidentHuntersTest {

    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private val twelveLanes = listOf(
        "QUALITY", "BLUECHIP", "SHITCOIN", "EXPRESS", "CORE", "MOONSHOT",
        "MANIPULATED", "TREASURY", "CASHGEN", "CYCLIC", "DIP_HUNTER", "PROJECT_SNIPER",
    )

    private fun gridSnapshot(): MarketSweep7297.Snapshot {
        val rows = ArrayList<MarketSweep7297.Row>()
        var i = 0
        for (mcap in listOf(5_000.0, 20_000.0, 80_000.0, 300_000.0, 1_500_000.0, 4_000_000.0, 20_000_000.0, 200_000_000.0)) {
            for (chg in listOf(-10.0, 5.0, 20.0)) {
                for (age in listOf(0.02, 0.1, 3.0, 500.0)) {
                    i++
                    val mint = ("M7807x" + i.toString().padStart(4, '0')).padEnd(44, 'A')
                    rows += MarketSweep7297.Row(
                        mint = mint, symbol = "T$i", name = "T$i",
                        priceUsd = 1.0, mcapUsd = mcap, liquidityUsd = mcap * 0.3,
                        volumeH1Usd = mcap * 0.3, volumeH24Usd = mcap * 3.0,
                        priceChangeH1Pct = chg, txCountH1 = 100, holders = 500,
                        organicScore = 50.0, verified = true, ageHours = age,
                        providers = setOf("TEST_7807"),
                    )
                }
            }
        }
        return MarketSweep7297.Snapshot(rows, emptyMap(), mapOf("TEST_7807" to rows.size), System.currentTimeMillis())
    }

    @Test fun every_one_of_the_twelve_specialist_lanes_receives_a_hunted_set() {
        val picks = LaneHunter7297.hunt(gridSnapshot())
        for (lane in twelveLanes) {
            assertTrue("lane $lane must receive hunted rows", picks[lane].orEmpty().isNotEmpty())
        }
        assertEquals(twelveLanes.toSet(), LaneHunter7297.profiles.map { it.lane }.toSet())
    }

    @Test fun every_hunter_lane_has_a_token_source_so_its_picks_reach_intake() {
        for (p in LaneHunter7297.profiles) {
            val ts = SolanaMarketScanner.TokenSource.valueOf(LaneHunter7297.SOURCE_PREFIX + p.lane)
            assertEquals(p.lane, LaneHunter7297.laneFromSource(ts.name))
        }
    }

    @Test fun hunted_mints_are_claimed_for_ownership_after_handoff() {
        val snap = gridSnapshot()
        val picks = LaneHunter7297.hunt(snap)
        val (lane, rows) = picks.entries.first { it.value.isNotEmpty() }
        val r = rows.first()
        val claims = LaneHunter7297.claimsFor7803(r.mint, r.mcapUsd)
        assertTrue("claim for $lane must survive hand-off", lane in claims)
        assertNotNull(com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.entry(lane, r.mint))
    }

    @Test fun sweep_no_longer_runs_inside_the_scanner_batch_budget() {
        val sc = src("engine/SolanaMarketScanner.kt")
        val batch = sc.substringAfter("val deepScans = mutableListOf<Pair<String, suspend () -> Unit>>(")
            .substringBefore("when (rot % 4)")
        assertFalse(batch.lines().any { !it.trim().startsWith("//") && it.contains("scanMarketSweep7297()") })
        assertFalse(sc.contains("MarketSweep7297.sweep("))
        assertTrue(sc.contains("suspend fun emitResidentHunt7807("))
        assertTrue(sc.contains("Semaphore(EMIT_PARALLELISM_7807)"))
    }

    @Test fun worker_is_resident_single_flight_and_bounded() {
        val w = src("engine/market/ResidentHunterWorker7807.kt")
        assertTrue(w.contains("inFlight.compareAndSet(false, true)"))
        assertTrue(w.contains("withTimeoutOrNull(SWEEP_BUDGET_MS_7807) { MarketSweep7297.sweep(k.first, k.second) }"))
        assertTrue(w.contains("withTimeoutOrNull(EMIT_BUDGET_MS_7807) { emitter(snap, picks) }"))
        assertTrue(w.contains("LaneHunter7297.hunt(snap)"))
        assertTrue(w.contains("authority=candidates_only"))
        val ms = src("engine/market/MarketSweep7297.kt")
        assertTrue(ms.contains("withTimeoutOrNull(ENRICH_BUDGET_MS_7807)"))
        assertTrue(ms.contains("kotlinx.coroutines.runInterruptible(Dispatchers.IO)"))
        assertTrue(ms.contains("withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { fn() }"))
    }

    @Test fun bot_service_starts_and_stops_the_worker_with_the_bot() {
        val bs = src("engine/BotService.kt")
        assertTrue(bs.contains("marketScanner?.start()\n                startResidentHunters7807()"))
        assertTrue(bs.contains("ResidentHunterWorker7807.stop(\"stopBot:\$source\")"))
        assertTrue(bs.contains("emitter = { snap, picks -> marketScanner?.emitResidentHunt7807(snap, picks) ?: 0 }"))
    }

    @Test fun opportunity_tape_is_fed_from_the_live_poll_not_the_dead_socket() {
        val bs = src("engine/BotService.kt")
        assertTrue(bs.contains("recordOpportunityTape7807(ts, pair, m5)"))
        assertTrue(bs.contains("com.lifecyclebot.engine.market.MarketSweep7297.recordRealtime7777("))
        val ws = src("network/DexScreenerWebSocket.kt")
        assertTrue("the socket is still disabled, so the poll must be the writer", ws.contains("private val DISABLED_7381 = true"))
    }

    @Test fun crypto_universe_gets_the_hunted_equivalent() {
        val w = src("engine/market/ResidentHunterWorker7807.kt")
        assertTrue(w.contains("DynamicAltTokenRegistry.ingestMarketHunt7807("))
        assertTrue(w.contains("CryptoStrategyCandidateBooks7803.watch("))
        assertTrue(w.contains("\"DESK_\$deskLane\""))
        val reg = src("perps/DynamicAltTokenRegistry.kt")
        assertTrue(reg.contains("fun ingestMarketHunt7807("))
        assertTrue(reg.contains("if (!liquidityUsd.isFinite() || liquidityUsd < MIN_LIQ_USD) return null"))
        val desk = src("perps/CryptoLaneDesk7391.kt")
        for (lane in listOf("CORE", "EXPRESS", "DIP_HUNTER", "TREASURY", "CASHGEN", "QUALITY", "BLUECHIP", "SHITCOIN", "MOONSHOT")) {
            assertTrue(desk.contains("\"$lane\""))
            assertTrue(lane in com.lifecyclebot.engine.market.ResidentHunterWorker7807.CRYPTO_DESK_LANES_7807)
        }
    }
}
