package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7246HeldSupervisorTest {
    private fun src(path: String) =
        File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun held_inventory_is_canonical_and_separate_from_discovery() {
        val supervisor = src("engine/HeldPositionSupervisor7246.kt")
        val bot = src("engine/BotService.kt")
        assertTrue(supervisor.contains("CanonicalPositionAuthority6441.openPositions()"))
        assertTrue(supervisor.contains("fun isHeld("))
        assertTrue(bot.contains("HELD_DISCOVERY_BYPASS_7246"))
        assertTrue(bot.contains("HeldPositionSupervisor7246.solanaHeldPositions()"))
        val registry = src("engine/GlobalTradeRegistry.kt")
        val canonical = src("engine/truth/CanonicalPositionAuthority6441.kt")
        assertTrue(registry.contains("handoffOpenMintToHeld7246"))
        assertTrue(registry.contains("HELD_DISCOVERY_SLOT_RELEASED_7246"))
        assertTrue(registry.contains("HELD_REGISTRY_READMISSION_BLOCKED_7246"))
        assertTrue(registry.contains("HELD_POSITION_SUPERVISOR_7246"))
        assertTrue(canonical.contains("handoffOpenMintToHeld7246(position.mint, position.symbol)"))
    }

    @Test fun crypto_universe_does_not_spend_discovery_batch_on_owned_assets() {
        val crypto = src("perps/CryptoAltTrader.kt")
        assertTrue(crypto.contains("CRYPTO_HELD_DISCOVERY_BYPASS_7246"))
        assertTrue(crypto.contains("HeldPositionSupervisor7246.isHeld(tok.canonicalIdentity6544)"))
    }

    @Test fun cyclic_watchlist_loss_cannot_abandon_a_still_held_position() {
        val cyclic = src("engine/CyclicTradeEngine.kt")
        val heldBranch = cyclic.substringAfter("if (isInPosition && currentMint.isNotBlank())")
            .substringBefore("// V5.9.1359")
        assertTrue(heldBranch.contains("CYCLIC_HELD_WATCHLIST_EVICTION_PRESERVED_7246"))
        assertTrue(heldBranch.contains("HeldPositionSupervisor7246.isHeld(currentMint)"))
        assertFalse(heldBranch.contains("abandonCycle(context, \"token_lost\""))
    }

    @Test fun stale_mark_policy_has_no_terminal_scratch_verdict_or_sell() {
        val stale = src("engine/truth/StaleMarkRunnerProtection6829.kt")
        val bot = src("engine/BotService.kt")
        assertFalse(stale.contains("SCRATCH_ALLOWED"))
        assertTrue(stale.contains("HELD_STALE_MARK_REFRESH_ONLY_7245"))
        assertFalse(bot.contains("Verdict.SCRATCH_ALLOWED"))
        assertTrue(bot.contains("HELD_NO_MARK_REFRESH_ONLY_7246"))
        assertTrue(bot.contains("HELD_STALE_TIMEOUT_REFRESH_ONLY_7246"))
    }

    @Test fun watchlist_ui_exposes_held_as_a_first_class_surface() {
        val ui = src("ui/WatchlistActivity.kt")
        val layout = File("src/main/res/layout/activity_watchlist.xml").readText()
        assertTrue(ui.contains("HeldPositionSupervisor7246.snapshot()"))
        assertTrue(ui.contains("buildHeldTab()"))
        assertTrue(layout.contains("android:id=\"@+id/tabHeld\""))
        assertTrue(layout.contains("android:text=\"HELD\""))
    }
}
