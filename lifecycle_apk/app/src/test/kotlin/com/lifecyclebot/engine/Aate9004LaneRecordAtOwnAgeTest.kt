package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AgeProof9001
import com.lifecyclebot.engine.truth.EntryReceipt8500
import com.lifecyclebot.engine.truth.RunnerFunnel8297
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9004 — a lane shut on its realised record holds a candidate to that record only at the ages it was made at: a band
 * with 3+ own live closes averaging at or above zero is not held to it. superseded by V5.0.9006: the lane's labels no
 * longer release a band (a label mean is taken over the decisions that could be priced); only its own live closes do.
 */
class Aate9004LaneRecordAtOwnAgeTest {
    @Test fun onlyTheBandsOwnLiveClosesReleaseIt() {
        assertTrue(EntryReceipt8500.ownAgeOpen9004(3.0, 0.0))
        assertTrue(EntryReceipt8500.ownAgeOpen9004(5.0, 42.0))
        assertFalse(EntryReceipt8500.ownAgeOpen9004(3.0, -0.3))
        assertFalse(EntryReceipt8500.ownAgeOpen9004(12.0, -80.0))
        assertFalse(EntryReceipt8500.ownAgeOpen9004(0.0, 0.0))
        assertFalse(EntryReceipt8500.ownAgeOpen9004(2.0, 50.0))      // two closes are not a record
    }

    @Test fun aLanesClosesFoldByTheAgeTheyWereEnteredAt() {
        assertEquals("laneage|MOONSHOT|UNDER_15M", EntryReceipt8500.laneAgeKey9004("MOONSHOT", 4.0))
        assertEquals("laneage|EXPRESS|OVER_24H", EntryReceipt8500.laneAgeKey9004("EXPRESS", 5_000.0))
        AgeProof9001.resetForTest9001()
        EntryReceipt8500.resetForTest8500()
        assertFalse(EntryReceipt8500.ownAgeRecordOpen9004("MOONSHOT", 4.0))
        assertFalse(EntryReceipt8500.ownAgeRecordOpen9004("MOONSHOT", Double.NaN))
        repeat(200) { AgeProof9001.note9001("MOONSHOT", 4.0, if (it % 2 == 0) 60.0 else -10.0, true, true) }
        assertFalse(EntryReceipt8500.ownAgeRecordOpen9004("MOONSHOT", 4.0))                   // 9006: paying labels release nothing
        repeat(3) { EntryReceipt8500.fold8500(EntryReceipt8500.laneAgeKey9004("MOONSHOT", 4.0), RunnerFunnel8297.Outcome8297.LOSS_BOUGHT_TOP, -12.0) }
        assertFalse(EntryReceipt8500.ownAgeRecordOpen9004("MOONSHOT", 4.0))
        repeat(2) { EntryReceipt8500.fold8500(EntryReceipt8500.laneAgeKey9004("MOONSHOT", 4.0), RunnerFunnel8297.Outcome8297.WIN, 60.0) }
        assertTrue(EntryReceipt8500.ownAgeRecordOpen9004("MOONSHOT", 4.0))                    // its own closes average above zero
        assertFalse(EntryReceipt8500.ownAgeRecordOpen9004("MOONSHOT", 5_000.0))               // another band: still the lane's record
        EntryReceipt8500.resetForTest8500()
        AgeProof9001.resetForTest9001()
    }

    @Test fun wired() {
        val eco = File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryEconomics8329.kt").readText()
        assertTrue(eco.contains("if (try { EntryReceipt8500.ownAgeRecordOpen9004(l, ageMin) } catch (_: Throwable) { false }) {"))
        assertTrue(eco.contains("labelInc(\"LANE_SHUT_NOT_AT_THIS_AGE_9004_\$l\")"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryReceipt8500.kt").readText().contains("byLaneAge9004{"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 189. A lane's live record is read at the coin's own age (9004)"))
    }
}
