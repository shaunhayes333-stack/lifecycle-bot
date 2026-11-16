package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.SignalBible300
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The 300 handwritten hypotheses must enter the Lab without becoming live gates. */
class Aate8826SignalBibleTest {
    @Test fun everyHandwrittenSignalAndSetupIsPresentOnce() {
        assertEquals(300, SignalBible300.all.size)
        assertEquals(240, SignalBible300.signals.size)
        assertEquals(60, SignalBible300.recipes.size)
        assertEquals(300, SignalBible300.all.map { it.id }.distinct().size)
        assertEquals(20, SignalBible300.signals.map { it.family }.distinct().size)
        assertEquals(10, SignalBible300.recipes.map { it.family }.distinct().size)
        for (i in 1..240) {
            val item = SignalBible300.find("S%03d".format(i))
            assertNotNull(item)
            assertEquals(SignalBible300.Kind.EVIDENCE, item!!.kind)
            assertTrue(item.guidance.contains("ON:"))
            assertTrue(item.guidance.contains("OFF:"))
        }
        for (i in 241..300) assertNotNull(SignalBible300.find("T%03d".format(i)))
        assertEquals(12, SignalBible300.signals.groupBy { it.family }.values.first().size)
        assertTrue(SignalBible300.signals.groupBy { it.family }.values.all { it.size == 12 })
        assertTrue(SignalBible300.recipes.groupBy { it.family }.values.all { it.size == 6 })
    }

    @Test fun everySetupHasAuditableSignalReferencesAndLifecycle() {
        val IDs = SignalBible300.signals.map { it.id }.toSet()
        for (recipe in SignalBible300.recipes) {
            assertTrue("${recipe.id} references nothing", recipe.references.isNotEmpty())
            assertTrue("${recipe.id} references an undefined signal", recipe.references.all { it in IDs })
            assertTrue(recipe.guidance.contains("**Trigger"))
            assertTrue(recipe.guidance.contains("**Fail"))
            assertTrue(recipe.guidance.contains("**Manage"))
        }
        assertEquals(SignalBible300.Kind.NO_LONG, SignalBible300.find("T276")?.kind)
        assertEquals(SignalBible300.Kind.NO_LONG, SignalBible300.find("T288")?.kind)
        assertEquals(SignalBible300.Kind.NO_LONG, SignalBible300.find("T294")?.kind)
        assertEquals(SignalBible300.Kind.MANAGEMENT, SignalBible300.find("T295")?.kind)
        assertEquals(SignalBible300.Kind.MANAGEMENT, SignalBible300.find("T300")?.kind)
        assertTrue(SignalBible300.find("T295")!!.references.contains("S224"))
    }

    @Test fun researchPromptRotatesDeterministicallyAndDoesNotGrantAuthority() {
        assertFalse(SignalBible300.LIVE_AUTHORITY)
        val first = SignalBible300.researchBrief(0L)
        val other = SignalBible300.researchBrief(1L)
        assertEquals(first, SignalBible300.researchBrief(0L))
        assertFalse(first == other)
        assertTrue(first.contains("NONE proven or live-authorised"))
        assertTrue(first.contains("mark UNKNOWN"))
        assertTrue(first.contains("T241"))
        assertEquals(6, Regex("T[0-9]{3} ").findAll(first).count())
        assertTrue(SignalBible300.statusLine().contains("signals=240 recipes=60 families=30 liveAuthority=false"))
    }

    @Test fun theLabConsumesTheCatalogAfterMeasuredDirectionsAndLiveEntryIsUnchanged() {
        fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()
        val llm = src("engine/lab/LlmLabEngine.kt")
        assertTrue(llm.contains("SignalBible300.researchBrief"))
        assertTrue(llm.indexOf("\$directions8825") < llm.indexOf("\$bible8826"))
        assertTrue(llm.contains("No new mandatory gates or live authority arise from this library."))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("SignalBible300.statusLine()"))
        assertFalse("No unproven setup enters existing lane predicates", src("engine/cortex/LanePlaybook7907.kt").contains("SignalBible300"))
        assertFalse("No unproven setup enters FDG", src("engine/truth/CandidateAdmission8136.kt").contains("SignalBible300"))
    }
}
