package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLabelHygiene8144
import com.lifecyclebot.engine.truth.EvidenceShadow8344
import com.lifecyclebot.engine.truth.ExploreShadow8347
import com.lifecyclebot.engine.truth.ShadowAlign8366
import com.lifecyclebot.engine.truth.ShadowPersist8355
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8879 — owner, 20 Nov: "the shadow trading engine is meant to basically be exactly the same as the live trader in all
 * forms and functions". The exploration book's RUNNER leg on a doctrine lane now exits by the live doctrine, as the evidence
 * shadow does since 8878; a doctrine-held position (peak +50 %+) keeps the hold's window in both books. Production
 * step8347 / ticketFromRec8355 paths only.
 */
class Aate8879ExploreDoctrineTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    private fun ticket(lane: String): Any {
        val rec = ShadowPersist8355.TicketRec8355("t8879-$lane", "TEST", lane, "BASE", "BASE", "HIGH", "SMALL",
            100.0, 1000, 9.28, 13.92, 50.0, true, true, 100.0, 1000,
            listOf(ShadowPersist8355.LegRec8355("NOW", "RUNNER", true, false, 100.0, 1000, 1.0, 0.0, 0, 0.0, 0.0)),
            entryPullbackPct8394 = 4.0, contractVersion8394 = 8394)
        return ExploreShadow8347::class.java.declaredMethods.single { it.name == "ticketFromRec8355" }
            .apply { isAccessible = true }.invoke(ExploreShadow8347, rec)!!
    }

    private fun field(o: Any, n: String) = o.javaClass.getDeclaredField(n).apply { isAccessible = true }

    private fun leg(t: Any): ShadowPersist8355.LegRec8355 = (ExploreShadow8347::class.java.declaredMethods
        .single { it.name == "ticketRec8355" }.apply { isAccessible = true }
        .invoke(ExploreShadow8347, t) as ShadowPersist8355.TicketRec8355).legs.single()

    /** One observed mark at [px], seen at [at]: the ticket's mark and its time, then the production step. */
    private fun step(t: Any, px: Double, at: Long) {
        field(t, "lastPx").setDouble(t, px); field(t, "lastPxAtMs").setLong(t, at)
        val l = (field(t, "legs").get(t) as List<*>).single()
        ExploreShadow8347::class.java.declaredMethods.single { it.name == "step8347" }.apply { isAccessible = true }
            .invoke(ExploreShadow8347, t, l, px, false, at, doubleArrayOf(0.0, 0.0), at)
    }

    @Test fun aDoctrineLaneRunnerLegRidesPast40PercentTakesTheRungAndSellsOnTheLineOnTwoMarks() {
        val t = ticket("MOONSHOT")
        step(t, 145.0, 100_000L)
        assertEquals("no scalp partial at +45 % on a doctrine lane", "", leg(t).exitWhy8366)
        step(t, 215.0, 110_000L)
        assertEquals("the 2x rung of TIERS_7967", EvidenceShadow8344.DOCTRINE_RUNG_8878, leg(t).exitWhy8366)
        assertEquals(0.25, leg(t).sellFraction8394, 1e-12)
        assertEquals(1, leg(t).sellTier8394)
        val fill = 110_000L + ShadowAlign8366.sellLatencyMs8366(false) + 1
        step(t, 215.0, fill)
        assertEquals(0.75, leg(t).held, 1e-12)
        assertEquals(1, leg(t).tiers)
        assertEquals("", leg(t).exitWhy8366)
        // peak +115 %: the doctrine line is the 1.5x floor. One mark under it is a wick; the second distinct mark sells
        step(t, 140.0, fill + 1_000L)
        assertEquals("one mark under the line waits", "", leg(t).exitWhy8366)
        step(t, 140.0, fill + 2_000L)
        assertEquals(EvidenceShadow8344.DOCTRINE_TRAIL_8878, leg(t).exitWhy8366)
        assertEquals(0.75, leg(t).held, 1e-12)
    }

    @Test fun theClockPassesUnderHalfAGainAndIsHeldAboveTheLineFromIt() {
        val horizon = CortexLabelHygiene8144.executableHorizonMs8392(true)
        val small = ticket("SHITCOIN")
        step(small, 120.0, 50_000L)
        step(small, 120.0, 1_000L + horizon + 1)
        assertEquals("peak +20 %: the doctrine lets the clock pass", "TIMEOUT", leg(small).exitWhy8366)
        val big = ticket("SHITCOIN")
        step(big, 180.0, 50_000L)
        step(big, 170.0, 1_000L + horizon + 1)
        assertEquals("peak +80 %, +70 % above the +40 % line: the doctrine holds the clock", "", leg(big).exitWhy8366)
        assertEquals(1.0, leg(big).held, 1e-12)
        assertTrue(EvidenceShadow8344.doctrineHoldsClock8879("SHITCOIN", 80.0))
        assertTrue(EvidenceShadow8344.doctrineHoldsClock8879("MOONSHOT", 50.0))
        assertFalse(EvidenceShadow8344.doctrineHoldsClock8879("MOONSHOT", 49.9))
        assertFalse("not a doctrine lane", EvidenceShadow8344.doctrineHoldsClock8879("QUALITY", 80.0))
        assertFalse(EvidenceShadow8344.doctrineHoldsClock8879("MOONSHOT", Double.NaN))
    }

    @Test fun aNonDoctrineLaneRunnerLegKeepsItsOwnExits() {
        val t = ticket("QUALITY")
        step(t, 145.0, 100_000L)
        assertEquals("QUALITY still takes the scalp tier", "SPIKE_PARTIAL_8394", leg(t).exitWhy8366)
        assertEquals(0.6, leg(t).sellFraction8394, 1e-12)
    }

    @Test fun aDoctrineHeldPositionKeepsTheHoldWindowOnRestoreAndAtTheCortex() {
        val ev = src("engine/truth/EvidenceShadow8344.kt").substringAfter("fun restoreOpen8417(").substringBefore("} catch (_: Throwable) { false }")
        assertTrue(ev, ev.contains("val noClock8879 = r.hold8366 || doctrineHoldsClock8879(r.lane, r.peakPct)"))
        assertTrue(ev.contains("if (noClock8879 && ShadowPersist8355.expiredOnRestore8355(r.atMs, ShadowAlign8366.holdWindowMs8366(), nowMs))"))
        assertTrue(ev.contains("if (!noClock8879 && ShadowPersist8355.expiredOnRestore8355(r.atMs, r.timeoutMs, nowMs))"))
        val ex = src("engine/truth/ExploreShadow8347.kt")
        assertTrue(ex.contains("(it.exit == Exit8347.RUNNER.name && EvidenceShadow8344.doctrineHoldsClock8879(lane8879, it.peak))) }) ShadowAlign8366.holdWindowMs8366()"))
        assertTrue(ex.contains("val noClock8879 = leg.hold8366 || (leg.exit == Exit8347.RUNNER && EvidenceShadow8344.doctrineHoldsClock8879(t.lane, leg.peak))"))
        val runner = ex.substringAfter("private fun runnerWhy8366(").substringBefore("if (!leg.hold8366 && px != null")
        assertTrue("the doctrine decides first on a doctrine lane", runner.contains("return doctrineLegWhy8879(t, leg, g, px, lost, nowMs)"))
        val fn = ex.substringAfter("private fun doctrineLegWhy8879(").substringBefore("private fun scalpWhy8366(")
        assertTrue(fn.contains("com.lifecyclebot.engine.RunnerDoctrine8584.paperStopLevel8584(t.stopPct, leg.peak)"))
        assertTrue(fn.contains("MemePaperTrader8544.rung8544(com.lifecyclebot.engine.RunnerGrab7967.TIERS_7967, leg.tiers, g)"))
        assertTrue(fn.contains("!com.lifecyclebot.engine.RunnerDoctrine8584.holds8584(t.lane, \"CLOCK_TIME\", g, leg.peak, t.stopPct)) return \"TIMEOUT\""))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 68. The exploration book's runner leg trades the live doctrine (8879)"))
    }
}
