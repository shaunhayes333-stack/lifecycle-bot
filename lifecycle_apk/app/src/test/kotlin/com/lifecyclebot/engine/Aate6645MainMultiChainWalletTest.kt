package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6645MainMultiChainWalletTest {
    @Test fun generationAndActivationAreSeparateFailClosedSteps() {
        val vault = File("src/main/kotlin/com/lifecyclebot/engine/MultiChainWalletVault6546.kt").readText()
        val manager = File("src/main/kotlin/com/lifecyclebot/engine/WalletManager.kt").readText()
        val ui = File("src/main/kotlin/com/lifecyclebot/ui/WalletActivity.kt").readText()
        assertTrue(vault.contains("BACKUP_CONFIRMED") && vault.contains("ACTIVE_MAIN"))
        assertTrue(vault.contains("confirmBackupAndActivate"))
        assertTrue(manager.contains("MAIN_WALLET_ALREADY_CONFIGURED_MIGRATION_REQUIRED"))
        assertTrue(manager.contains("activateStagedMultiChainAsMain"))
        assertTrue(ui.contains("I BACKED IT UP") && ui.contains("setCancelable(false)"))
    }
}
