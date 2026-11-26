package com.lifecyclebot.engine

import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.engine.truth.ProcessExits9030
import com.lifecyclebot.engine.truth.StartupCrashGuard7717
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9030 — a settings save never erases a saved wallet or provider key unless it says so, the screens save on top of the
 * stored config, and the reports say why the process last died (Android's exit record and the last crash of any build).
 */
class Aate9030SavedKeysStayTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aBlankNeverErasesASavedCredentialUnlessTheSaveNamesIt() {
        // a blank over a saved key: kept
        assertTrue(ConfigStore.keepSavedCredential9030(saved = "wallet-key", incoming = "", default = "", clear = false))
        // the owner's explicit erase: written
        assertFalse(ConfigStore.keepSavedCredential9030(saved = "wallet-key", incoming = "", default = "", clear = true))
        // a new value: written
        assertFalse(ConfigStore.keepSavedCredential9030(saved = "old", incoming = "new", default = "", clear = false))
        // nothing saved, or the same value: written as before
        assertFalse(ConfigStore.keepSavedCredential9030(saved = "", incoming = "", default = "", clear = false))
        assertFalse(ConfigStore.keepSavedCredential9030(saved = "", incoming = "shipped", default = "shipped", clear = false))
        assertFalse(ConfigStore.keepSavedCredential9030(saved = "same", incoming = "same", default = "", clear = false))
        // this build's own default over the owner's key (a BotConfig() nobody read from the store): kept
        assertTrue(ConfigStore.keepSavedCredential9030(saved = "owner-helius", incoming = "shipped", default = "shipped", clear = false))
        assertFalse(ConfigStore.keepSavedCredential9030(saved = "owner-helius", incoming = "shipped", default = "shipped", clear = true))
        // the saved value could not be read: a blank is not written, a value is
        assertTrue(ConfigStore.keepSavedCredential9030(saved = null, incoming = "", default = "", clear = false))
        assertFalse(ConfigStore.keepSavedCredential9030(saved = null, incoming = "typed", default = "", clear = false))
        assertFalse(ConfigStore.keepSavedCredential9030(saved = null, incoming = "", default = "", clear = true))
    }

    @Test fun onlyTheNamedActionsErase() {
        assertEquals("private_key_b58", ConfigStore.CRED_PRIVATE_KEY_9030)
        assertEquals("treasury_private_key_b58", ConfigStore.CRED_TREASURY_KEY_9030)
        assertEquals("pump_portal_api_key", ConfigStore.CRED_PUMP_PORTAL_9030)
        assertEquals(listOf("telegram_chat_id", "birdeye_api_key", "groq_api_key", "gemini_api_key", "jupiter_api_key"),
            listOf(ConfigStore.CRED_TELEGRAM_CHAT_9030, ConfigStore.CRED_BIRDEYE_9030, ConfigStore.CRED_GROQ_9030, ConfigStore.CRED_GEMINI_9030, ConfigStore.CRED_JUPITER_9030))
        // "Clear API keys" erases the provider keys it lists, never a wallet key and never the PumpPortal key
        assertEquals(setOf("helius_api_key", "birdeye_api_key", "groq_api_key", "gemini_api_key", "jupiter_api_key", "telegram_bot_token", "telegram_chat_id"),
            ConfigStore.API_KEY_CLEARS_9030)
        assertFalse(ConfigStore.CRED_PRIVATE_KEY_9030 in ConfigStore.API_KEY_CLEARS_9030)
        assertFalse(ConfigStore.CRED_TREASURY_KEY_9030 in ConfigStore.API_KEY_CLEARS_9030)
        assertTrue(ConfigStore.credentialLine9030().startsWith("savedCredentials9030[keptOverBlank{"))
        val c = src("data/BotConfig.kt")
        // every credential write is guarded; the treasury key (a blank one makes TreasuryWalletManager generate a new wallet) too
        for (name in listOf("CRED_PRIVATE_KEY_9030, cfg.privateKeyB58", "CRED_TREASURY_KEY_9030, cfg.treasuryPrivateKeyB58",
            "CRED_PUMP_PORTAL_9030, cfg.pumpPortalApiKey", "CRED_HELIUS_9030, cfg.heliusApiKey, DefaultKeys.HELIUS",
            "CRED_TELEGRAM_TOKEN_9030, cfg.telegramBotToken")) assertTrue(name, c.contains("if (writes9030($name))"))
        assertFalse("the wallet key is no longer written unconditionally", c.contains("putString(\"private_key_b58\",     cfg.privateKeyB58)"))
        assertTrue(c.contains("fun saveClearing9030(ctx: Context, cfg: BotConfig, clears: Set<String>)"))
        val vm = src("ui/BotViewModel.kt")
        assertTrue(vm.contains("fun saveConfig(cfg: BotConfig, allowRestart: Boolean = true, clears9030: Set<String> = emptySet())"))
        assertTrue(vm.contains("if (clears9030.isEmpty()) ConfigStore.save(ctx, cfg) else ConfigStore.saveClearing9030(ctx, cfg, clears9030)"))
        assertTrue(vm.contains("ConfigStore.saveClearing9030(ctx, cfg.copy(privateKeyB58 = \"\"), setOf(ConfigStore.CRED_PRIVATE_KEY_9030))"))
        assertTrue(src("ui/WalletActivity.kt").contains("vm.saveConfig(cfg2.copy(privateKeyB58 = \"\"), clears9030 = setOf(ConfigStore.CRED_PRIVATE_KEY_9030))"))
    }

    @Test fun theScreensSaveOnTopOfTheStoredConfig() {
        val m = src("ui/MainActivity.kt")
        assertTrue(m.contains("vm.saveConfig(ConfigStore.load(this@MainActivity).copy(activeToken = ts.mint), allowRestart = false)"))
        assertFalse(m.contains("vm.saveConfig(state.config.copy(activeToken = ts.mint), allowRestart = false)"))
        assertTrue(m.contains("DataKeysCard7962.bind(this, { ConfigStore.load(this) })"))
        assertFalse(m.contains("{ vm.ui.value.config }"))
        assertTrue(m.contains("val shown9030 = ConfigStore.load(this)")); assertTrue(m.contains("sheet.setConfig(shown9030)"))
        assertFalse(m.contains("sheet.setConfig(state.config)"))
        assertTrue(m.contains("vm.saveConfig(cfg, clears9030 = ConfigStore.API_KEY_CLEARS_9030)"))
    }

    @Test fun theReportsSayWhyTheProcessLastDied() {
        assertEquals("LOW_MEMORY", ProcessExits9030.reasonName9030(3))
        assertEquals("CRASH", ProcessExits9030.reasonName9030(4))
        assertEquals("CRASH_NATIVE", ProcessExits9030.reasonName9030(5))
        assertEquals("ANR", ProcessExits9030.reasonName9030(6))
        assertEquals("SIGNALED", ProcessExits9030.reasonName9030(2))
        assertEquals("USER_REQUESTED", ProcessExits9030.reasonName9030(10))
        assertEquals("REASON_99", ProcessExits9030.reasonName9030(99))
        assertEquals("LOW_MEMORY 42min ago process=main status=0 pss=815MB rss=900MB",
            ProcessExits9030.row9030(3, 0, 1_000_000L, 1_000_000L + 42L * 60_000L + 59_000L, "com.lifecyclebot.aate", null, 815L * 1024, 900L * 1024))
        assertEquals("ANR 0min ago process=lab status=9 pss=0MB rss=0MB desc=user request after error: Input dispatching timed out",
            ProcessExits9030.row9030(6, 9, 5_000L, 4_000L, "com.lifecyclebot.aate:lab", "user request after error:\nInput dispatching timed out", 0L, 0L))
        assertEquals("      no context", ProcessExits9030.block9030(null))
        // Android's own record is read only on Android 11+ (ProcessExits9030.lines9030), and a failure to read it is a row, not a crash
        val pe = src("engine/truth/ProcessExits9030.kt")
        assertTrue(pe.contains("fun lines9030(ctx: Context, max: Int = 8, nowMs: Long = System.currentTimeMillis()): List<String> = try {"))
        assertTrue(pe.contains("if (Build.VERSION.SDK_INT < 30) listOf(\"not recorded by Android below version 11\")"))
        assertTrue(pe.contains("am.getHistoricalProcessExitReasons(ctx.packageName, 0, max)"))
        // a guard that has not read its record says so instead of "none"
        assertTrue(StartupCrashGuard7717.priorCrashLine9030().isNotBlank())
        val phc = src("engine/PipelineHealthCollector.kt")
        assertTrue(phc.contains("Last crash, any build (§9030): "))
        assertTrue(phc.contains("ProcessExits9030.block9030(com.lifecyclebot.AATEApp.appContextOrNull())"))
        assertTrue(phc.contains("ConfigStore.credentialLine9030()"))
        assertTrue(phc.indexOf("Last crash (§7717):") < phc.indexOf("Last crash, any build (§9030): "))
        val ui = src("ui/PipelineHealthActivity.kt")
        assertTrue(ui.contains("appendLine(\"lastCrashAnyBuild9030: \" + com.lifecyclebot.engine.truth.StartupCrashGuard7717.priorCrashLine9030())"))
        assertTrue(ui.contains("processExits9030 (Android's record, newest first):"))
        assertTrue(ui.contains("heap usedMB="))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## §215 — V5.0.9030: a save never erases a saved key; the report says why the process died"))
    }
}
