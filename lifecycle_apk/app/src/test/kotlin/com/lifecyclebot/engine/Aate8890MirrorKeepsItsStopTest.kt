package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PaperMirror8757
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8890 — owner: "make sure we dont fuck with the behaviour thats made money!". 5.0.8882 (the build that made money)
 * stopped the MOONSHOT mirror on the paper book's own record. Since 8883 the evidence shadow's record is read too, but it can
 * only add a stop: it can never switch on a mirror the paper record stops.
 */
class Aate8890MirrorKeepsItsStopTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theShadowCanStopTheMirrorButNeverLiftThePaperBooksStop() {
        assertTrue("the paper book proves a loss: stopped, whatever the shadow says", PaperMirror8757.stops8890(true, 100, false))
        assertTrue(PaperMirror8757.stops8890(true, 0, false))
        assertTrue("a shadow proven losing on 30+ closes also stops it", PaperMirror8757.stops8890(false, 30, true))
        assertFalse("29 shadow closes prove nothing", PaperMirror8757.stops8890(false, 29, true))
        assertFalse("neither proves a loss: the mirror follows, as in 5.0.8882", PaperMirror8757.stops8890(false, 30, false))
    }

    @Test fun thePaperRecordIsReadFirstAndOutsideLiveIsTheOnlyOne() {
        val f = src("engine/truth/PaperMirror8757.kt").substringAfter("internal fun bookLosing8883(").substringBefore("internal fun stops8890(")
        assertTrue(f.contains("val paperLosing = MemePaperTrader8544.laneProvenLosing8608(lane)"))
        assertTrue(f.contains("if (paperLosing || !(try { com.lifecyclebot.engine.LiveNoPaper8184.closeOutInLive8872() } catch (_: Throwable) { false }))"))
        assertTrue(f.indexOf("val paperLosing") < f.indexOf("EvidenceShadow8344.proof8344("))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 79. The mirror keeps 5.0.8882's stop (8890)"))
    }
}
