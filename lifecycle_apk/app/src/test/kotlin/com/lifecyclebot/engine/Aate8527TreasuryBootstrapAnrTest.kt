package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Android emulator ANR: Application.onCreate blocked on AndroidKeyStore.getKey via Treasury restore. */
class Aate8527TreasuryBootstrapAnrTest {
    @Test fun applicationNeverWaitsForEncryptedTreasuryKeystore() {
        val app = File("src/main/kotlin/com/lifecyclebot/AATEApp.kt").readText()
        assertTrue(app.contains("TreasuryManager.restoreAsync8527(applicationContext)"))
        assertFalse(app.contains("TreasuryManager.restore(this)"))

        val treasury = File("src/main/kotlin/com/lifecyclebot/engine/TreasuryManager.kt").readText()
        assertTrue(treasury.contains("fun restoreAsync8527(ctx: Context)"))
        assertTrue(treasury.contains("restoreScheduled8527.compareAndSet(false, true)"))
        assertTrue(treasury.contains("Thread({"))
        assertTrue(treasury.contains("android.os.Looper.myLooper() == android.os.Looper.getMainLooper()"))
        assertTrue(treasury.contains("synchronized(restoreLock8527)"))
        assertTrue(treasury.contains("restoredThisProcess8527 = true"))
    }

    @Test fun tradeLoopStartsOnlyAfterTreasuryStateIsRestored() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val earlyRestore = bot.indexOf("TreasuryManager.restore(applicationContext)")
        val loopLaunch = bot.indexOf("val createdLoopJob6518 = scope.launch(botLoopDispatcher)")
        assertTrue("Treasury restore must precede live/paper botLoop launch", earlyRestore > 0 && loopLaunch > earlyRestore)
    }
}
