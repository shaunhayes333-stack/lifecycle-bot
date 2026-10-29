package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8435 — 8434: NON_OWNER_QUALIFIED_NO_SLOT_8364=336. A lane whose own resident book is READY for the coin takes a
 * native slot from a holder that only filed a hypothesis / claim; a READY holder is never displaced; the cap holds.
 */
class Aate8435ReadySlotTest {
    @Test fun aReadyAskerDisplacesOnlyAHolderThatIsNotReady() {
        val ready = setOf("CORE", "CYCLIC", "TREASURY")
        val holders = listOf("QUALITY", "TREASURY", "BLUECHIP")
        assertEquals("the first holder not READY", "QUALITY", SpecialistElection8136.readyVictim8435(holders, "CORE") { it in ready })
        assertNull("an asker not READY displaces nobody", SpecialistElection8136.readyVictim8435(holders, "DIP_HUNTER") { it in ready })
        assertNull("READY holders keep their slots", SpecialistElection8136.readyVictim8435(listOf("CYCLIC", "TREASURY"), "CORE") { it in ready })
        assertEquals(6, SpecialistElection8136.NATIVE_EVAL_CAP_8214)
        assertEquals("every READY lane may evaluate (all twelve)", 12, SpecialistElection8136.READY_EVAL_CAP_8435)
    }

    @Test fun moreLiveSlotsWithoutSmallerPositions() {
        val d = com.lifecyclebot.engine.truth.LiveConcentrationDoctrine7697
        assertEquals(40, d.LIVE_OPEN_LIMIT_8435)
        assertEquals("the sizing divisor is unchanged", 20, d.slots(1.0))
        assertEquals(0.05, d.share(1.0), 1e-12)
        val doc = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveConcentrationDoctrine7697.kt").readText()
        assertTrue(doc.contains("val n = maxOf(slots(tradeable + deployed), LIVE_OPEN_LIMIT_8435)"))
        assertTrue("paper / shadow never count", doc.contains("CanonicalPositionAuthority6441.openPositions().count { it.mode.equals(\"live\", ignoreCase = true) }"))
        val pol = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveRiskPolicy7807.kt").readText()
        assertTrue(pol.contains("LiveConcentrationDoctrine7697.liveOpenCount() >= LiveConcentrationDoctrine7697.LIVE_OPEN_LIMIT_8435"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/CellAllocator7962.kt").readText()
            .contains("val freeSlots = LiveConcentrationDoctrine7697.LIVE_OPEN_LIMIT_8435 - open.size"))
    }

    @Test fun wiring() {
        val e = File("src/main/kotlin/com/lifecyclebot/engine/SpecialistElection8136.kt").readText()
        assertTrue(e.contains("l in set || (set.size < NATIVE_EVAL_CAP_8214 && set.add(l)) || reassignNativeSlot8364(set, mint, l) ||"))
        assertTrue(e.contains("readySlot8435(set, mint, l)"))
        assertTrue(e.contains("readySlot8435=\${readySlotTaken8435.get()}"))
        val b = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(b.contains("val ownBrainOutsideRing8435 = ownBrainReady8429 && specialistEvaluationAllowed6600"))
    }
}
