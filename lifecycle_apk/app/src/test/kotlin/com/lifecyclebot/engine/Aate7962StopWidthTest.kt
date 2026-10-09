package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.7962 — stop width learned from how deep a key's winners dip before running; heal asks the chain directly. */
class Aate7962StopWidthTest {
    private fun s(peak: Double, dip: Double) = doubleArrayOf(peak, 5.0, 0.3, 0.3, dip)

    @Test fun shakeoutKeysGetRoomCleanKeysGetTight() {
        // Winners that shake out -18..-22% before a 3-8x run.
        val shake = ExitProfile7955.profileOf7955((0 until 30).map { i -> s(200.0 + i * 10, -18.0 - (i % 5)) })!!
        assertEquals(30, shake.winnerDipN)
        val wide = ExitProfile7955.learnedStopMag7962(shake, 20.0)!!
        assertTrue("room for the shakeout", wide >= 22.0 && wide <= 25.0)
        // Winners that never dip more than 3%.
        val clean = ExitProfile7955.profileOf7955((0 until 30).map { i -> s(60.0 + i, -1.0 - (i % 3)) })!!
        val tight = ExitProfile7955.learnedStopMag7962(clean, 20.0)!!
        assertTrue("losers cut sooner", tight < 8.0)
        // Thin evidence: nothing learned.
        assertNull(ExitProfile7955.learnedStopMag7962(ExitProfile7955.profileOf7955((0 until 3).map { s(100.0, -10.0) }), 20.0))
        assertNull(ExitProfile7955.learnedStopMag7962(null, 20.0))
        // Old 4-field samples still load and simply carry no dip.
        assertEquals(0, ExitProfile7955.profileOf7955(listOf(doubleArrayOf(100.0, 5.0, 0.3, 0.3)))!!.winnerDipN)
        assertTrue(ExitProfile7955.winnerDips7962(listOf(s(10.0, -30.0))).isEmpty())   // not a winner
    }

    @Test fun wired() {
        val sa = File("src/main/kotlin/com/lifecyclebot/engine/cortex/StopAuthority7887.kt").readText()
        assertTrue(sa.contains("ExitProfile7955.learnedStopMag7962("))
        val lab = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(lab.contains("dipBeforePeakPct = o.dipBeforePeak7962"))
        val rec = File("src/main/kotlin/com/lifecyclebot/engine/LiveCanonicalRecovery6686.kt").readText()
        assertTrue(rec.contains("singleMintReads7962(due.filter"))
    }
}
