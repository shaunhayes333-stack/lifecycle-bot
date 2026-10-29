package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LaneCheatSheet8323
import com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450
import com.lifecyclebot.engine.truth.RunnerFunnel8297
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8435 — a chain-read cost teaches even on a quantity-quarantined mint; a lane's hunter never selects above the cap
 * its own playbook proved dead; the stop latency keeps its first trigger.
 */
class Aate8435LearningAndHuntTest {
    @Test fun aChainReadCostThatAgreesWithItselfTeaches() {
        assertFalse("no position, no proof", CanonicalTradeFinalizedBus6450.chainCostTeaches8435("ONCHAIN_COST_8024", null))
        assertFalse(CanonicalTradeFinalizedBus6450.chainCostTeaches8435("WALLET_RECOVERY_X", null))
        val bus = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalTradeFinalizedBus6450.kt").readText()
        val accounting = bus.indexOf("pos7776.quarantineReason.isNotBlank()) -> \"QUARANTINED_ACCOUNTING_7807\"")
        val chain = bus.indexOf("chainCostTeaches8435(src7722, pos7776) -> null")
        val quantity = bus.indexOf("QuantityInvariantAuthority6500.isQuarantined(mint) -> \"QUANTITY_QUARANTINED_7807\"")
        assertTrue("accounting quarantine still binds first", accounting in 1 until chain)
        assertTrue("the chain-read cost is judged before the per-mint quantity flag", chain < quantity)
        assertNull(CanonicalTradeFinalizedBus6450.learningUncleanReason7807("no-such-position-8435", ""))
    }

    @Test fun aHunterNeverSelectsAboveItsOwnPlaybooksCut() {
        assertEquals(5_000_000.0, LaneCheatSheet8323.huntMcapMax8435("TREASURY"), 0.0)
        assertEquals(5_000_000.0, LaneCheatSheet8323.huntMcapMax8435("CASHGEN"), 0.0)
        assertTrue("BLUECHIP keeps its large caps (no MCAP_GT_5M cut)", LaneCheatSheet8323.huntMcapMax8435("BLUECHIP") > 1.0e9)
        assertTrue(LaneCheatSheet8323.huntMcapMax8435("SHITCOIN") <= LaneCheatSheet8323.mcapMax8323("SHITCOIN"))
        val h = File("src/main/kotlin/com/lifecyclebot/engine/market/LaneHunter7297.kt").readText()
        assertTrue(h.contains("LaneCheatSheet8323.huntMcapMax8435(\"TREASURY\")"))
        assertFalse(h.contains("LaneCheatSheet8323.mcapMax8323("))
    }

    @Test fun aStopEpisodeKeepsItsFirstTrigger() {
        val m = "MintStop8435" + System.nanoTime()
        SourceChokeDiagnostics4584.stopTriggered(m, "MOONSHOT", "HARD_STOP", -40.0)
        val first = SourceChokeDiagnostics4584.stopTriggerAtForTest8435(m)!!
        Thread.sleep(5)
        SourceChokeDiagnostics4584.stopTriggered(m, "MOONSHOT", "HARD_STOP", -70.0)
        assertEquals(first, SourceChokeDiagnostics4584.stopTriggerAtForTest8435(m))
        SourceChokeDiagnostics4584.stopFinalized(m, "MOONSHOT", "HARD_STOP", -79.0)
        assertNull(SourceChokeDiagnostics4584.stopTriggerAtForTest8435(m))
    }

    @Test fun theRunnerFunnelStillNamesARefusalWhenNothingWasAdmitted() {
        assertEquals(RunnerFunnel8297.Stage8297.GATE_REFUSED,
            RunnerFunnel8297.attribute8297(false, Double.NaN, 900.0, Double.NaN, 0L, 0L, 0L, true, "NATIVE_TREASURY_ROLE_FLOOR", "").stage)
    }
}
