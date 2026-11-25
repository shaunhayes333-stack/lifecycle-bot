package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PaperCorrespondence9018
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9018 — a PAPER close is filed by what the LIVE decision said of the same candidate; a lane's corresponding record is
 * its WOULD_ADMIT closes; the MOONSHOT mirror does not pass a live door whose own paper record proves a loss; a pocket found
 * on paper is a hypothesis, never an authority.
 */
class Aate9018PaperCorrespondsToLiveTest {
    private val day = 86_400_000L
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theStampNamesTheLiveDoor() {
        val c = PaperCorrespondence9018
        assertEquals("WOULD_ADMIT", c.verdictKey9018(true, "anything")); assertEquals(c.WOULD_ADMIT_9018, c.verdictKey9018(true, null))
        assertEquals("EDGE_MODEL_NEGATIVE_8537", c.door9018("CANDIDATE_ADMISSION_8136_EDGE_MODEL_NEGATIVE_8537:net=-3.1")); assertEquals("?", c.door9018(" "))
        assertEquals(c.REFUSE_PREFIX_9018 + "LIVE_STAGE_LOSES_8626", c.verdictKey9018(false, "LIVE_STAGE_LOSES_8626"))
        assertEquals("WOULD_REFUSE:EDGE_MODEL_NEGATIVE_8537", c.verdictKey9018(false, "CANDIDATE_ADMISSION_8136_EDGE_MODEL_NEGATIVE_8537:net=-3.1"))
        assertEquals("WOULD_REFUSE:DATA_INCOMPLETE_DEFER_8136", c.verdictKey9018(false, "DATA_INCOMPLETE_DEFER_8136"))
        assertEquals("WOULD_REFUSE:?", c.verdictKey9018(false, null))
        assertEquals("ADMIT", c.class9018("WOULD_ADMIT")); assertEquals("REFUSE", c.class9018("WOULD_REFUSE:X")); assertEquals("UNREAD", c.class9018(c.UNREAD_9018))
    }

    @Test fun aPaperCloseIsFiledByWhatLiveWouldHaveDone() {
        val c = PaperCorrespondence9018
        c.resetForTest9018()
        val t0 = 20_000L * day
        // 40 MOONSHOT paper trades on coins live would also have bought: small steady losses
        repeat(40) { i ->
            c.noteAdmission9018("admit$i", "moonshot", true, "", 12.0, "MOMENTUM", t0 + i)
            c.onClose9018("paper", true, "admit$i", "MOONSHOT", if (i % 5 == 0) 6.0 else -9.0, 20 * 60_000L, t0 + (i % 2) * day)
        }
        // 40 on coins live's edge door refuses: they lose more
        repeat(40) { i ->
            c.noteAdmission9018("edge$i", "MOONSHOT", false, "CANDIDATE_ADMISSION_8136_EDGE_MODEL_NEGATIVE_8537:x", 400.0, "MOMENTUM", t0 + i)
            c.onClose9018("paper", true, "edge$i", "MOONSHOT", if (i % 8 == 0) 4.0 else -14.0, 15 * 60_000L, t0 + (i % 2) * day)
        }
        // 40 on coins live's data door defers: they pay, on both day halves
        repeat(40) { i ->
            c.noteAdmission9018("data$i", "MOONSHOT", false, "DATA_INCOMPLETE_DEFER_8136", 0.5, "LAUNCH", t0 + i)
            c.onClose9018("paper", true, "data$i", "MOONSHOT", if (i % 4 == 0) -20.0 else 30.0, (if (i % 4 == 0) 5 else 12) * 60_000L, t0 + (i % 2) * day)
        }
        // never stamped, a live close, a partial: UNREAD / not filed
        c.onClose9018("paper", true, "nostamp", "MOONSHOT", 250.0, 60_000L, t0)
        c.onClose9018("live", true, "admit0", "MOONSHOT", 999.0, 60_000L, t0)
        c.onClose9018("paper", false, "admit1", "MOONSHOT", 999.0, 60_000L, t0)

        val corresponding = c.proof9018("MOONSHOT")!!
        assertEquals("the corresponding record is the WOULD_ADMIT closes only", 40, corresponding.n)
        assertEquals(-6.0, corresponding.mean, 1e-9)
        assertEquals(0.2, corresponding.winRate, 1e-9)
        assertEquals(20.0, corresponding.holdWinMin, 1e-9); assertEquals(20.0, corresponding.holdLossMin, 1e-9)
        assertEquals(20, corresponding.evenN); assertEquals(20, corresponding.oddN)

        val edge = c.proof9018("MOONSHOT", "WOULD_REFUSE:EDGE_MODEL_NEGATIVE_8537")!!
        assertTrue(c.provenLosing9018(edge)); assertFalse(c.provenPositive9018(edge))
        assertTrue("the mirror does not pass a door whose own paper record loses", c.doorProvenLosing9018("MOONSHOT", "CANDIDATE_ADMISSION_8136_EDGE_MODEL_NEGATIVE_8537:y"))

        val data = c.proof9018("MOONSHOT", "WOULD_REFUSE:DATA_INCOMPLETE_DEFER_8136")!!
        assertEquals(17.5, data.mean, 1e-9)
        assertTrue("a door in front of money is a pocket hypothesis", c.provenPositive9018(data))
        assertFalse(c.doorProvenLosing9018("MOONSHOT", "DATA_INCOMPLETE_DEFER_8136"))
        assertEquals(12.0, data.holdWinMin, 1e-9); assertEquals(5.0, data.holdLossMin, 1e-9)

        assertEquals(1, c.proof9018("MOONSHOT", c.UNREAD_9018)!!.n)
        assertNull("no record for a door paper never met", c.proof9018("MOONSHOT", "WOULD_REFUSE:NEVER_SEEN"))
        assertFalse(c.doorProvenLosing9018("MOONSHOT", "NEVER_SEEN")); assertFalse(c.doorProvenLosing9018("QUALITY", "EDGE_MODEL_NEGATIVE_8537"))
        assertNotNull(c.proof9018("MOONSHOT")); assertNull(c.proof9018("QUALITY"))

        val line = c.statusLine9018()
        assertTrue(line.startsWith("paperCorrespondence9018[closes=121 "))
        assertTrue(line.contains("corresponding{MOONSHOT:n40/-6.0%"))
        assertTrue(line.contains("doorsProvenLosing{MOONSHOT>EDGE_MODEL_NEGATIVE_8537:n40/-11.8%"))
        assertTrue(line.contains("MOONSHOT|refusedBy>DATA_INCOMPLETE_DEFER_8136:n40/+17.5%"))
        assertTrue("the entry state travels with the stamp", line.contains("MOONSHOT|REFUSE|age=") && line.contains("MOONSHOT|REFUSE|setup=LAUNCH"))

        // the record and the open stamps survive their own encoding
        c.noteAdmission9018("stillOpen", "EXPRESS", true, "", 3.0, "IGNITION", t0)
        val saved = c.encode9018()
        c.resetForTest9018(); c.decode9018(saved)
        assertEquals(40, c.proof9018("MOONSHOT")!!.n); assertEquals(-6.0, c.proof9018("MOONSHOT")!!.mean, 1e-9)
        c.onClose9018("paper", true, "stillOpen", "EXPRESS", 5.0, 60_000L, t0)
        assertEquals(1, c.proof9018("EXPRESS")!!.n)
        c.resetForTest9018()
    }

    @Test fun aProofNeedsCountSpreadAndBothHalves() {
        val c = PaperCorrespondence9018
        fun p(n: Int, mean: Double, se: Double, evenN: Int = n / 2, evenMean: Double = mean, oddN: Int = n / 2, oddMean: Double = mean) =
            PaperCorrespondence9018.Proof9018(n, mean, se, 0.5, 0, evenN, evenMean, oddN, oddMean, 10.0, 10.0)
        assertTrue(c.provenPositive9018(p(40, 10.0, 4.0)))
        assertFalse("too few", c.provenPositive9018(p(29, 10.0, 1.0)))
        assertFalse("within two standard errors of nothing", c.provenPositive9018(p(40, 10.0, 6.0)))
        assertFalse("one set of days carried it", c.provenPositive9018(p(40, 10.0, 4.0, evenMean = 25.0, oddMean = -5.0)))
        assertFalse("one set of days is nearly empty", c.provenPositive9018(p(40, 10.0, 4.0, evenN = 35, oddN = 5)))
        assertTrue(c.provenLosing9018(p(30, -10.0, 4.0))); assertFalse(c.provenLosing9018(p(30, -10.0, 6.0))); assertFalse(c.provenLosing9018(p(29, -10.0, 1.0)))
        assertFalse(c.provenLosing9018(p(40, -10.0, Double.NaN)))
    }

    @Test fun theWhatIfIsAskedInPaperOnlyAndOnlyTheMirrorIsNarrowed() {
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue(adm.contains("val live8290 = liveOverride9018 ?: (try { !com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false })"))
        assertTrue(adm.contains("if (mode != \"LIVE\" && it.admitted && !FdgGate8365.shadowOnly8393()) try {"))
        assertTrue(adm.contains("val built9018 = candidateFor8136(ts, laneRaw, nowMs, sizeForQuote8223, liveOverride9018 = true)"))
        assertTrue(adm.contains("val whatIf9018 = decide8136(live9018, ::verifiedRecordDoor8650)"))
        assertTrue(src("engine/truth/RunnerCapture8017.kt").contains("PaperCorrespondence9018.onClose9018(env.mode, env.terminal, env.mint, env.lane, env.realizedReturnPct, env.holdingTimeMs, env.atMs)"))
        val mirror = src("engine/truth/PaperMirror8757.kt").substringAfter("fun mirror8757(").substringBefore("internal fun bookLosing8883(")
        val held = mirror.indexOf("PaperCorrespondence9018.doorProvenLosing9018(c.lane, d.reason)")
        assertTrue("after the hard list and the book's own stop, before the paper decision is asked",
            held in (mirror.indexOf("bookLosing8883(c.lane)") + 1) until mirror.indexOf("CandidateAdmission8136.decide8136(c.copy(live = false"))
        assertTrue(mirror.substring(held, held + 400).contains("return d"))
        // nothing else reads it: a paper pocket is reported, not served
        val readers = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "PaperCorrespondence9018.kt" }
            .filter { it.readText().contains("PaperCorrespondence9018.") }.map { it.name }.toSet()
        assertEquals(setOf("CandidateAdmission8136.kt", "RunnerCapture8017.kt", "PaperMirror8757.kt", "DiagnosisBrief8438.kt"), readers)
    }
}
