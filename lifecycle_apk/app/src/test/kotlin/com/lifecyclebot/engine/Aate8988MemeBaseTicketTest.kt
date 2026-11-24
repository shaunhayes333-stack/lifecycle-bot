package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveConcentrationDoctrine7697
import com.lifecyclebot.engine.truth.LiveDiscipline8985
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8988 — a LIVE meme ticket is never shaped below the doctrine's cost-clearing position; a door proven paying on its
 * own live closes takes 1.25x of it; the wallet share, the ceiling and absolute pins still bound it; paper is untouched.
 */
class Aate8988MemeBaseTicketTest {
    private val d = LiveConcentrationDoctrine7697

    @Test fun theShapeDoesNotGoBelowTheBase() {
        assertEquals(0.0626, d.baseFloorFor8988(0.0324, 0.0626, false, 0.2713, 0.5), 1e-12)   // the 21:27 capture's numbers
        assertEquals(0.0626 * 1.25, d.baseFloorFor8988(0.0324, 0.0626, true, 0.2713, 0.5), 1e-12)
        assertEquals("a shape above the base stands", 0.09, d.baseFloorFor8988(0.09, 0.0626, false, 0.2713, 0.5), 1e-12)
        assertEquals("never over half the wallet", 0.05, d.baseFloorFor8988(0.02, 0.0626, true, 0.10, 0.5), 1e-12)
        assertEquals("never over the ceiling", 0.5, d.baseFloorFor8988(0.2, 0.6, true, 10.0, 0.5), 1e-12)
        assertEquals("no doctrine position: unchanged", 0.0324, d.baseFloorFor8988(0.0324, 0.0, true, 0.2713, 0.5), 1e-12)
        assertEquals(0.0, d.baseFloorFor8988(0.0, 0.0626, false, 0.2713, 0.5), 0.0)
        // scales with the wallet: the same rule on a 10 SOL wallet (base = 1/20 = 0.5)
        assertEquals(0.5, d.baseFloorFor8988(0.2, 0.5, false, 10.0, 0.5), 1e-12)
    }

    @Test fun paperAndPinnedTicketsAreUntouched() {
        assertEquals(0.01, d.baseFloored8988(0.01, "MOONSHOT", "So11111111111111111111111111111111111111112", paper = true, pinned = false, ceilingSol = 0.5), 0.0)
        assertEquals(0.01, d.baseFloored8988(0.01, "MOONSHOT", "So11111111111111111111111111111111111111112", paper = false, pinned = true, ceilingSol = 0.5), 0.0)
    }

    @Test fun aDoorIsPayingOnlyOnItsOwnLiveCloses() {
        LiveDiscipline8985.resetForTest8985()
        assertFalse(LiveDiscipline8985.doorPaying8988("NoSuchMint8988"))
        LiveDiscipline8985.resetForTest8985()
    }

    @Test fun wired() {
        val fdg = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("LiveConcentrationDoctrine7697.baseFloored8988(finalSize, specialistLane?.trim()?.takeIf { it.isNotBlank() } ?: laneName, ts.mint, config.paperMode, sizePinnedAbsolute6827, absoluteCeiling6827)"))
        assertTrue(fdg.indexOf("canonical_respected_absolute_pin_6827") < fdg.indexOf("baseFloored8988("))
        assertEquals(1.25, LiveConcentrationDoctrine7697.PAYING_DOOR_MULT_8988, 0.0)
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 173. The base meme ticket is the cost-clearing ticket (8988)"))
    }
}
