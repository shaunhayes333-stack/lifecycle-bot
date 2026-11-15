package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPaperTransaction6486
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8812 — CI runtime acceptance stalled 42 s at TRADER_CRYPTO_ALT_INIT_STARTING on four builds: the crypto trader's
 * startup repair waited on the paper transaction lock held by the startup reconcile pass. It now waits at most 2 s and
 * otherwise repairs on a background thread.
 */
class Aate8812CryptoStartupTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun anEmptyStartupRepairRunsAtOnceWhenTheLockIsFree() {
        assertTrue(CanonicalPaperTransaction6486.repairCryptoAtStartup8812(emptyList(), 2_000L))
    }

    @Test fun theStartupRepairNeverWaitsPastItsBound() {
        val t = src("engine/truth/CanonicalPaperTransaction6486.kt").substringAfter("fun repairCryptoAtStartup8812(").substringBefore("fun repairCryptoHistory6659()")
        assertTrue(t.contains("if (lock.tryLock(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {"))
        assertTrue(t.contains("Thread({ try { lock.withLock { work() } } catch (_: Throwable) {} }, \"crypto-startup-repair-8812\")"))
        val crypto = src("perps/CryptoAltTrader.kt").substringAfter("private fun rehydrateCanonicalPositions6647").substringBefore("private fun canonicalRecoveryMarket8521")
        assertTrue(!crypto.contains("CanonicalPaperTransaction6486.repairCryptoHistory6659()"))
        assertTrue(!crypto.contains("CanonicalPaperTransaction6486.ensureOpenProjection6659(cp)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 19. Crypto startup does not wait on the journal reconciler (8812)"))
    }
}
