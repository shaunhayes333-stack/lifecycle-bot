package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLabelFeed8291
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.LabelBasis8386
import com.lifecyclebot.engine.truth.LabelMark8386
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8521ForwardCoverageTest {
    private val now = 1_000_000L

    @Test fun newlyRestampedCopyStillNeedsARealMarketObservation() {
        assertTrue(CortexLabelFeed8291.needsIndependentRefresh8521(now + 100_000L, now, false, true, now))
        assertTrue(CortexLabelFeed8291.needsIndependentRefresh8521(now + 100_000L, now, true, false, now))
    }

    @Test fun freshCompatibleMarketReadDoesNotCreateAnotherProviderRequest() {
        assertFalse(CortexLabelFeed8291.needsIndependentRefresh8521(now + 100_000L, now, true, true, now - 1_000L))
        assertTrue(CortexLabelFeed8291.needsIndependentRefresh8521(now + 100_000L, now, true, true, now - 21_000L))
        assertTrue(CortexLabelFeed8291.needsIndependentRefresh8521(now + 100_000L, now, true, true, now + 1L))
    }

    @Test fun refreshCannotExtendOrReopenAClosedWindow() {
        assertFalse(CortexLabelFeed8291.needsIndependentRefresh8521(0L, now, false, false, 0L))
        assertFalse(CortexLabelFeed8291.needsIndependentRefresh8521(now, now, false, false, 0L))
        assertFalse(CortexLabelFeed8291.needsIndependentRefresh8521(now - 1L, now, false, false, 0L))
    }

    /** Actual production observation/queue function; short test identity cannot start provider traffic. */
    @Test fun unchangedCopyQueuesBeforeRejectionWithoutChangingTheOriginalBasisOrInventingOutcome() {
        val runtime = ForwardReturnLabeler7731
        val mint = "coverage-8521"
        val at = now - 300_000L
        val obs = Class.forName("${runtime::class.java.name}\$Obs").declaredConstructors
            .single { it.parameterCount == 13 }.apply { isAccessible = true }
            .newInstance(mint, "TEST", "TEST_COVERAGE_8521", "TEST", "MOONSHOT", false,
                50, "TEST", "TEST", "TEST", 1.0, 3.0, at)
        fun set(name: String, value: Any) = obs.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(obs, value)
        fun get(name: String): Any? = obs.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obs)
        val basis = LabelBasis8386(LabelMark8386(mint, "TEST", 1.0, at, at, "CURVE_ACCOUNT", decimals = 6, independentBasis = true), 0.0)
        set("basis8386", basis); set("decisionBasis8386", basis); set("rebased8076", true)
        set("lastPx", 1.0); set("lastPxAtMs", at)
        val copy = LabelMark8386(mint, "TEST", 1.0, now, now, "TOKEN_STATE", decimals = 6)
        val due = ArrayList<Pair<String, Long>>()
        runtime.javaClass.declaredMethods.single { it.name == "queueIndependentRefresh8521" }
            .apply { isAccessible = true }.invoke(runtime, obs, copy, now, due)
        assertEquals(listOf(mint to CortexLabelFeed8291.offWatchKey8291(0, at + 450_000L)), due)
        assertSame(basis, get("basis8386"))
        assertEquals(at, get("lastPxAtMs"))
        assertEquals(false, get("done60"))
        assertTrue((get("bookedNet7997") as Double).isNaN())

        due.clear()
        val real = copy.copy(source = "CURVE_ACCOUNT", independentBasis = true)
        runtime.javaClass.declaredMethods.single { it.name == "queueIndependentRefresh8521" }
            .apply { isAccessible = true }.invoke(runtime, obs, real, now, due)
        assertTrue("a real fresh compatible mark is already covered", due.isEmpty())
    }

    @Test fun productionRefreshPrecedesEveryEarlyMarkContinueAndShadowsUseMarketClock() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        val tick = source.substringAfter("private fun tickOnce8442(")
        val refresh = tick.indexOf("queueIndependentRefresh8521(o, observed8386, nowMs, dueUnpriced7737)")
        assertTrue(refresh >= 0)
        assertTrue(refresh < tick.indexOf("if (!LabelEvidence8386.eligible(o.basis8386.mark,mark8386))"))
        assertTrue(refresh < tick.indexOf("if (mark8386.observedAtMs <= o.lastPxAtMs) continue"))
        assertTrue(tick.contains("freshMarketMark8521(m, nowMs, MARK_MAX_AGE_MS_7731 / 2)"))
        assertFalse(source.contains("private fun freshTokenState8442"))
    }
}
