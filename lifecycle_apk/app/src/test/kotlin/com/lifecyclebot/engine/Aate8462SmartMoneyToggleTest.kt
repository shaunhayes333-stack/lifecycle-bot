package com.lifecyclebot.engine

import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.network.PumpFunWS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8462 — owner: "kill the smart money scan. put it in a toggle in settings. the pumpfun whale follow delivered better
 * results" (chosen: whale buys on pump coins + copy pump.fun top traders). 8457: the alpha consumer spent 666 Helius
 * credits an hour (buysSeen=0 copyFed=0); the PumpPortal trade switch was ON with its data wallet under the minimum
 * ("Minimum balance not met", 40 trade frames), and the Helius program curve tape stood down on the switch alone, so no
 * pump.fun trade with a wallet reached WhaleTrackerAI.
 */
class Aate8462SmartMoneyToggleTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun theSmartMoneyScanIsOffByDefaultAndLivesOutsideBotConfig() {
        assertEquals("smart_money_scan_enabled_8462", ConfigStore.SMART_MONEY_SCAN_KEY_8462)
        assertFalse("off until the owner switches it on", ConfigStore.smartMoneyScan8462)
        val cfg = src("data/BotConfig.kt")
        assertTrue(cfg.contains("prefs(ctx).getBoolean(SMART_MONEY_SCAN_KEY_8462, false)"))
        val ctor = cfg.substringAfter("data class BotConfig(").substringBefore("object ConfigStore")
        assertFalse("never a BotConfig field (constructor slot limit)", ctor.contains("smartMoneyScan"))
        assertTrue(cfg.substringAfter("fun load(ctx: Context): BotConfig {").contains("loadSmartMoneyScan8462(ctx)"))

        val sheet = src("ui/SettingsBottomSheet.kt")
        assertTrue(sheet.contains("switchSmartMoneyScan8462 = view.findViewById(R.id.switchSmartMoneyScan8462)"))
        assertTrue(sheet.contains("switchSmartMoneyScan8462.isChecked = com.lifecyclebot.data.ConfigStore.loadSmartMoneyScan8462(requireContext())"))
        assertTrue(sheet.substringAfter("private fun saveSettings() {")
            .contains("ConfigStore.saveSmartMoneyScan8462(requireContext(), switchSmartMoneyScan8462.isChecked)"))
        assertTrue(File("src/main/res/layout/dialog_settings.xml").readText().contains("android:id=\"@+id/switchSmartMoneyScan8462\""))
    }

    @Test fun offStopsTheHeliusScanButKeepsTheLeaderboardCopyWallets() {
        val smd = src("engine/SmartMoneyDiscovery7277.kt").substringAfter("private fun runCycle(")
        val seed = smd.indexOf("engine?.autoDiscoverTopWallets(maxWallets = 5, minPnlSol = 25.0)")
        val gate = smd.indexOf("if (!com.lifecyclebot.data.ConfigStore.smartMoneyScan8462) { scanSkipped8462.incrementAndGet(); return }")
        assertTrue("the pump.fun / GMGN leaderboard seed runs first, whatever the switch", seed in 0 until gate)
        assertTrue("runner mining waits on the switch", gate < smd.indexOf("observedRunners()"))

        val ew = src("engine/ExpertWallets7962.kt").substringAfter("private fun runCycle(").substringBefore("learnPending()")
        assertTrue(ew.contains("if (scan8462 && key.isNotBlank() && KeyValidator.isUsableEnhancedHeliusKey(key)) fetchHistories(key)"))
        assertTrue(ew.contains("if (scan8462 && HeliusCadence8361.alphaRebuildDue8361(alphaAtMs8361, now8361))"))
        assertTrue("copy wallets are still refreshed", ew.indexOf("refreshExperts(engine, onWatchlistChanged)") in 0 until ew.indexOf("val scan8462"))

        val cad = src("engine/HeliusCadence8361.kt").substringAfter("fun watchList8361(").substringBefore("fun copyWatched8361(")
        assertTrue(cad.contains("val alpha = if (!com.lifecyclebot.data.ConfigStore.smartMoneyScan8462) emptyList()"))
        assertTrue("copy wallets stay on the stream", cad.contains("watchListOf8361(emptyList(), alpha, copyActive, ExpertWallets7962.OWNER_WALLET_7962)"))
    }

    @Test fun theHeliusPumpTapeStandsDownOnlyWhilePumpPortalDelivers() {
        val now = 100_000_000L
        assertTrue(PumpFunWS.delivering8462(true, now - 30_000L, now))
        assertFalse("switch on, wallet unfunded: no frames", PumpFunWS.delivering8462(true, 0L, now))
        assertFalse("frames stopped", PumpFunWS.delivering8462(true, now - PumpFunWS.DELIVERING_MS_8462 - 1L, now))
        assertFalse("not keyed", PumpFunWS.delivering8462(false, now - 1_000L, now))
        val tape = src("network/HeliusCurveTape8354.kt")
        assertFalse(tape.contains("PumpFunWS.tradeStreamEnabled8354()"))
        assertEquals("sync and step-down both read delivery", 2, Regex("PumpFunWS\\.tradeStreamDelivering8462\\(nowMs\\)").findAll(tape).count())
        val ws = src("network/PumpFunWS.kt")
        assertTrue(ws.contains("tradeFramesSinceConnect8182.incrementAndGet()\n                        lastTradeFrameMs8462 = System.currentTimeMillis()"))
        // the curve tape's decoded trades carry the trader wallet to the whale tracker (emitHeliusCurve8354 -> detail callback)
        assertTrue(tape.contains("PumpFunWS.emitHeliusCurve8354(mint, priceSol, mcapSol, ev.isBuy, ev.user, ev.sol, ev.virtualSol)"))
    }
}
