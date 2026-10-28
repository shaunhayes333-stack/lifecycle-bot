package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.LabelBasis8386
import com.lifecyclebot.engine.truth.LabelMark8386
import org.junit.Assert.*
import org.junit.Test

/** Runs the actual expiry, booking and checkpoint functions on one original production observation. */
class Aate8397ForwardRecoveryLifecycleTest {
    private val runtime = ForwardReturnLabeler7731
    private var savedPrefs: Any? = null
    private var savedLoaded: Any? = null
    @org.junit.Before fun durableProducerFixture8404() {
        savedPrefs = get(runtime, "prefs")
        savedLoaded = get(com.lifecyclebot.engine.cortex.Cortex7885, "loaded")
        val prefs = org.mockito.Mockito.mock(android.content.SharedPreferences::class.java)
        val editor = org.mockito.Mockito.mock(android.content.SharedPreferences.Editor::class.java)
        org.mockito.Mockito.`when`(prefs.edit()).thenReturn(editor)
        org.mockito.Mockito.`when`(editor.putString(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(editor)
        org.mockito.Mockito.`when`(editor.commit()).thenReturn(true)
        set(runtime, "prefs", prefs)
        set(com.lifecyclebot.engine.cortex.Cortex7885, "loaded", true)
    }
    @org.junit.After fun restoreProducerFixture8404() {
        set(runtime, "prefs", savedPrefs)
        set(com.lifecyclebot.engine.cortex.Cortex7885, "loaded", savedLoaded)
        val outbox = get(runtime, "forwardOutbox8404")!!
        @Suppress("UNCHECKED_CAST")
        val rows = get(outbox, "rows") as MutableMap<String, String>
        rows.keys.removeAll { it.startsWith("forward-recovery-8397-") }
        for (name in listOf("durable", "acknowledged")) {
            @Suppress("UNCHECKED_CAST")
            val keys = get(outbox, name) as MutableSet<String>
            keys.removeAll { it.startsWith("forward-recovery-8397-") }
        }
    }
    private fun call(name: String, vararg args: Any?): Any? = runtime::class.java.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }
        .invoke(runtime, *args)
    private fun get(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)
    private fun set(o: Any, name: String, value: Any?) = o.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(o, value)
    @Suppress("UNCHECKED_CAST") private fun pending() = runtime::class.java.getDeclaredField("pending")
        .apply { isAccessible = true }.get(runtime) as MutableMap<String, Any>
    private fun observation(mint: String, at: Long): Any {
        val obs = Class.forName("${runtime::class.java.name}\$Obs").declaredConstructors
            .single { it.parameterCount == 13 }.apply { isAccessible = true }
            .newInstance(mint, "TEST", "TEST_RECOVERY_8397_$mint", "TEST", "MOONSHOT", false,
                50, "TEST", "TEST", "TEST", 1.0, 3.0, at)
        val basis = LabelBasis8386(mark(mint, at, 1.0), 0.0)
        set(obs, "basis8386", basis); set(obs, "decisionBasis8386", basis)
        set(obs, "rebased8076", true)
        return obs
    }
    private fun mark(mint: String, at: Long, price: Double) =
        LabelMark8386(mint, "TEST", price, at, at, "TEST", decimals = 6, independentBasis = true)

    @Test fun recoveredFiveMinuteObservationReachesFifteenMinutesAndHourWithoutChangingEpisode() {
        val mint = "forward-recovery-8397-live"; val key = "$mint|MOONSHOT"
        val at = System.currentTimeMillis() - 2 * 3_600_000L
        val obs = observation(mint, at)
        val originalBasis = get(obs, "basis8386")
        val first = mark(mint, at + 4 * 60_000, 1.1)
        set(obs, "lastAcceptedMark8395", first)
        set(obs, "lastMark8386", first)
        pending()[key] = obs
        try {
            assertEquals(at + 450_000L, call("deadline8395", obs))
            assertEquals(true, call("expired8395", key, obs, null, at + 450_000L))
            assertSame("A recovered first label must retain the original observation", obs, pending()[key])
            assertEquals(true, get(obs, "done60")); assertEquals(false, get(obs, "done240"))
            assertTrue((get(obs, "bookedNet7997") as Double).isFinite())
            assertEquals(at + 70 * 60_000L, call("deadline8395", obs))

            val fifteen = mark(mint, at + 15 * 60_000, 1.4)
            set(obs, "lastMark8386", fifteen)
            val firstNet = get(obs, "bookedNet7997") as Double
            assertEquals(false, call("checkpoint7997", obs, 37.0, 40.0, 15 * 60_000L, fifteen.observedAtMs))
            assertEquals(1, get(obs, "ck7997"))
            assertTrue("The actual revision hook has advanced this episode", (get(obs, "bookedNet7997") as Double) > firstNet)

            val hour = mark(mint, at + 60 * 60_000, 1.8)
            set(obs, "lastMark8386", hour)
            val stop = call("checkpoint7997", obs, 77.0, 80.0, 60 * 60_000L, hour.observedAtMs) as Boolean
            call("completeHour8397", key, obs, 77.0, 80.0, stop, true)
            assertEquals(true, get(obs, "done240"))
            assertEquals(true, get(obs, "exitDeferred8006"))
            assertSame(obs, pending()[key])
            assertSame(originalBasis, get(obs, "basis8386"))
            assertEquals(at, get(obs, "atMs"))
            assertEquals(at + 24 * 3_600_000L + 600_000L, call("deadline8395", obs))  // V5.0.8416 — the 24 h checkpoint is the last
        } finally { pending().remove(key, obs) }
    }

    @Test fun unchangedHourOutcomeStillStagesItsFirstHourModelGrade8417() {
        val mint = "forward-recovery-8397-unchanged"; val at = System.currentTimeMillis() - 65 * 60_000L
        val obs = observation(mint, at)
        set(obs, "done60", true); set(obs, "bookedNet7997", 7.0); set(obs, "bookedGross7997", 10.0)
        val mark = mark(mint, at + 60 * 60_000L, 1.1)
        set(obs, "lastMark8386", mark)
        val outbox = get(runtime, "forwardOutbox8404")!!
        @Suppress("UNCHECKED_CAST") val rows = get(outbox, "rows") as MutableMap<String, String>
        assertEquals(true, call("stageForwardRevision8404", obs, 7.0, 10.0, mark.observedAtMs))
        assertTrue("a due horizon carries evidence even with zero return change", rows.keys.any { it.startsWith("$mint|MOONSHOT|$at|R|") })
    }

    @Test fun recoveredHourDefersExitForAContinuingRunnerJustLikeTheNormalHourPath() {
        val mint = "forward-recovery-8397-hour"; val key = "$mint|MOONSHOT"
        val at = System.currentTimeMillis() - 2 * 3_600_000L
        val obs = observation(mint, at)
        set(obs, "done60", true); set(obs, "bookedNet7997", 7.0); set(obs, "bookedGross7997", 10.0)
        set(obs, "bookedLongNet8080", 7.0); set(obs, "bookedLongGross8080", 10.0)
        set(obs, "ck7997", 1)
        val hour = mark(mint, at + 59 * 60_000L, 1.8)
        set(obs, "lastAcceptedMark8395", hour); set(obs, "lastMark8386", hour)
        pending()[key] = obs
        try {
            assertEquals(true, call("expired8395", key, obs, null, at + 70 * 60_000L))
            assertEquals(true, get(obs, "done240"))
            assertEquals(true, get(obs, "exitDeferred8006"))
            assertSame(obs, pending()[key])
        } finally { pending().remove(key, obs) }
    }

    @Test fun actualDeathStillTerminatesInsteadOfUsingTheNewContinuationPath() {
        val mint = "forward-recovery-8397-dead"; val key = "$mint|MOONSHOT"
        val at = System.currentTimeMillis() - 10 * 60_000L
        val obs = observation(mint, at)
        val dying = mark(mint, at + 60_000L, 0.2) // outside the recoverable first-horizon window
        set(obs, "lastAcceptedMark8395", dying); set(obs, "lastMark8386", dying)
        set(obs, "lastPx", dying.usd); set(obs, "lastPxAtMs", dying.observedAtMs)
        pending()[key] = obs
        try {
            assertEquals(true, call("expired8395", key, obs, null, at + 450_000L))
            assertFalse(pending().containsKey(key))
            assertEquals(true, get(obs, "done60"))
        } finally { pending().remove(key, obs) }
    }

    @Test fun wrongBasisCannotBecomeARecoveredLabelAndUncommittedTerminalKeepsOwnership() {
        set(runtime, "prefs", null) // explicit storage refusal for this case
        val mint = "forward-recovery-8397-unavailable"; val key = "$mint|MOONSHOT"
        val at = System.currentTimeMillis() - 10 * 60_000L
        val obs = observation(mint, at)
        val wrongMint = mark("different-mint", at + 4 * 60_000L, 1.1)
        pending()[key] = obs
        try {
            call("expired8395", key, obs, wrongMint, at + 450_000L)
            assertEquals(false, get(obs, "done60"))
            assertTrue((get(obs, "bookedNet7997") as Double).isNaN())
            // No preferences are attached in this unit test: the durable outbox refuses to discard the original.
            assertSame(obs, pending()[key])
        } finally { pending().remove(key, obs) }
    }
}
