package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PaperMirror8757
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8761 — code audit (buy 3): the mirror's -0.1 SOL stop survives restarts and counts open positions at their mark. */
class Aate8761MirrorStopDurableTest {
    @Test fun an_open_position_under_water_counts_toward_the_stop() {
        assertEquals(-0.05, PaperMirror8757.openMarkSol8761(10.0, 5.0, 0.1), 1e-12)
        assertEquals(0.02, PaperMirror8757.openMarkSol8761(10.0, 12.0, 0.1), 1e-12)
        assertEquals("no fresh mark adds nothing", 0.0, PaperMirror8757.openMarkSol8761(10.0, null, 0.1), 0.0)
        assertEquals(0.0, PaperMirror8757.openMarkSol8761(0.0, 5.0, 0.1), 0.0)
        assertTrue("realised -0.06 plus open -0.05 trips it", PaperMirror8757.lossStopHit8757(-0.06 + PaperMirror8757.openMarkSol8761(10.0, 5.0, 0.1)))
    }

    @Test fun the_window_and_the_trip_are_persisted_and_read_at_boot() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperMirror8757.kt").readText()
        assertTrue(src.contains("if (lossStopHit8757(realised + openMirrorLaneSol8761(startMs8757))) {"))
        assertTrue(src.contains("if (startMs8757 <= 0L) { startMs8757 = nowMs; persist8761() }"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/LearningResetSweep7781.kt").readText()
            .contains("PaperMirror8757.load8761(app)"))
    }
}
