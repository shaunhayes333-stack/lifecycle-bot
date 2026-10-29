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
