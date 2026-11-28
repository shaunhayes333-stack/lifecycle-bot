package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.LaneHunter7297
import com.lifecyclebot.engine.lab.StrategyLab8755
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate9063RuntimeIntegrationTest {
    @Test fun directHunterLookupKeepsMultipleOwnersAndRejectsExpiredClaims() {
        val now=System.currentTimeMillis()
        val mint="hunter9063-$now"
        assertTrue(LaneHunter7297.claimRun8293("MOONSHOT",mint,0.0,now))
        assertTrue(LaneHunter7297.claimRun8293("EXPRESS",mint,0.0,now))
        assertTrue(LaneHunter7297.claimRun8293("CORE",mint,0.0,now-31*60_000L))
        assertEquals(setOf("MOONSHOT","EXPRESS"),LaneHunter7297.claimsFor7803(mint,0.0))
        assertTrue(LaneHunter7297.claimsFor7803("not-$mint",0.0).isEmpty())
        // Expiry must remove the exact old claim so a new native claim can replace it.
        assertTrue(LaneHunter7297.claimRun8293("CORE",mint,0.0,now))
        assertEquals(setOf("MOONSHOT","EXPRESS","CORE"),LaneHunter7297.claimsFor7803(mint,0.0))
    }

    @Test fun prolongedPressureCannotForceFullMiningAndNativeTurnsUseExistingWorker() {
        assertTrue(StrategyLab8755.deferredForMemory9033("CRITICAL",24*3_600_000L))
        assertTrue(StrategyLab8755.deferredForMemory9033("HARD",24*3_600_000L))
        assertFalse(StrategyLab8755.deferredForMemory9033("NORMAL",24*3_600_000L))
        val lab=File("src/main/kotlin/com/lifecyclebot/engine/lab/StrategyLab8755.kt").readText()
        val checkpoint=lab.substringAfter("private fun maybeCheckpoint9061()").substringBefore("// ── the cycle")
        assertTrue(checkpoint.contains("exec.execute"))
        assertTrue(checkpoint.contains("pressure==\"NORMAL\" && !running.get()"))
        assertTrue(checkpoint.contains("NativeZoneLab9058.batch"))
        val crypto=File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
            .substringAfter("private suspend fun freshDynamicEntryBasis7275").substringBefore("private val RECENT_BASIS_MS_7281")
        assertTrue(crypto.contains("DynamicAltTokenRegistry.solanaMintOf8866(identity)"))
        assertTrue(crypto.indexOf("NativePriceEvidence9062.markFor")<crypto.indexOf("registryBasis8870()?.let"))
        assertTrue(crypto.contains("decisionPriceUsable8870(mark.observedAtMs,now9063)"))
    }
}
