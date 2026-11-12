package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8713 — owner: "the smart wallet list is meant to be the top 50 list on pumpfun plus others already coded".
 * 8711: `Smart Money detected=0`, `LEADERBOARD_SEEDED 0`, `copytrade_other 4xx=1` — the coded pump.fun route was a guessed
 * website path. The list is `GET api.pump.fun/v1/pnl-leaderboard` (Bearer developer key).
 */
class Aate8713PumpFunBoardTest {
    private val a = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
    private val b = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    private fun w(addr: String, label: String, pnl: Double = 0.0) =
        CopyTradeEngine.CopyWallet(addr, label, true, 0, 0, 0, pnl, 0L, 0L)

    @Test fun theDeveloperApiBodyIsRead() {
        val body = """{"entries":[
            {"rank":1,"walletAddress":"$a","pnlSol":310.5,"realizedPnlSol":250.25,"realizedPnlUsd":40000,"username":"x"},
            {"rank":2,"walletAddress":"$b","pnlSol":90.0,"realizedPnlSol":-4.0}],"period":"weekly"}"""
        val rows = ExpertWallets7962.parseLeaderboard7962(body, 160.0)
        assertEquals(listOf(a to 250.25, b to 90.0), rows)
    }

    @Test fun theRouteNeedsAKeyAndAsksForFifty() {
        assertEquals(50, ExpertWallets7962.BOARD_SIZE_8713)
        val keyed = ExpertWallets7962.leaderboardUrls8713(true)
        assertEquals("https://api.pump.fun/v1/pnl-leaderboard?period=weekly&sort=realized&limit=50&positionsLimit=0", keyed.first())
        assertEquals(keyed.drop(1), ExpertWallets7962.leaderboardUrls8713(false))
        assertTrue(ExpertWallets7962.leaderboardUrls8713(false).none { it.contains("pump.fun") })
        LeaderboardRoutes8418.noteKey8713(false)
        assertTrue(LeaderboardRoutes8418.statusLine8418(0L).contains("pumpFunKey8713=MISSING"))
        // a 401 from an old key benches the route; a newly saved key re-opens it
        LeaderboardRoutes8418.note8418(keyed.first(), 401, challenge = false, rows = -1, nowMs = 0L)
        assertFalse(keyed.first() in LeaderboardRoutes8418.live8418(keyed, 1L))
        LeaderboardRoutes8418.keyChanged8713()
        assertTrue(keyed.first() in LeaderboardRoutes8418.live8418(keyed, 1L))
        assertTrue(LeaderboardRoutes8418.statusLine8418(1L).contains("pumpFunKey8713=set"))
    }

    @Test fun theBoardIsTheList() {
        val board = listOf(a to 250.0, b to 90.0)
        // every listed wallet is added — no five-a-pass cap, no typed SOL floor
        assertEquals(board to emptyList<String>(), CopyTradeEngine.boardSync8713(emptyList(), board))
        // a wallet tracked from another source keeps its entry; a top trader off the board leaves unless its copies paid
        val gone = "4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T"
        val paid = "5Q544fKrFoe6tsEbD7S8EmxGTJYAKtTVhAW5Q5pge4j1"
        val tracked = listOf(w(a, "SMART_MONEY_x2"), w(gone, "TopTrader_4Nd1_+30.0"), w(paid, "TopTrader_5Q54_+12.0", pnl = 0.4))
        val (add, drop) = CopyTradeEngine.boardSync8713(tracked, board)
        assertEquals(listOf(b to 90.0), add)
        assertEquals(listOf(gone), drop)
        assertEquals("TopTrader_9WzD_+90.0", CopyTradeEngine.boardLabel8713(b, 90.0))
        assertTrue(CopyTradeEngine.isBoardWallet8713(w(b, CopyTradeEngine.boardLabel8713(b, 250.0))))
    }

    @Test fun wiring() {
        fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
        val cte = src("engine/CopyTradeEngine.kt")
        assertFalse("the guessed website route is gone", src("engine/ExpertWallets7962.kt").contains("frontend-api-v3.pump.fun/leaderboard"))
        assertFalse(cte.contains("if (added >= maxWallets) break"))
        assertFalse(cte.contains("if (pnl < minPnlSol) continue"))
        assertTrue("the key goes to pump.fun's API host only",
            cte.contains("if (effectiveUrl.substringAfter(\"://\").substringBefore('/') == ExpertWallets7962.PUMPFUN_API_HOST_8713)"))
        assertTrue(cte.contains("header(\"Authorization\", \"Bearer \$it\")"))
        val smd = src("engine/SmartMoneyDiscovery7277.kt")
        assertTrue(smd.contains("pushWatchlist8713(engine, onWatchlistChanged)"))
        assertTrue(smd.contains("engine?.getWallets()?.count { !CopyTradeEngine.isBoardWallet8713(it) }"))
        assertTrue(src("engine/HeliusCadence8361.kt").contains("copyActive.filter { it in board }.take(ExpertWallets7962.BOARD_SIZE_8713)"))
        assertTrue(src("ui/DataKeysCard7962.kt").contains("ConfigStore.savePumpFunApiKey8713(a, typed)"))
        assertTrue(File("src/main/res/layout/activity_main.xml").readText().contains("android:id=\"@+id/etPumpFunApiKeyMain8713\""))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("Smart-money miner (§7277): "))
    }
}
