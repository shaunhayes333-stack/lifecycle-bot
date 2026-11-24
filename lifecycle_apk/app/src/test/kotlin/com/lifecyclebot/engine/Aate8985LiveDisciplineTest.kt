package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.LiveDiscipline8985
import com.lifecyclebot.ui.ReportCopyDialog8198
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8985 — a LIVE admission is filed under its door (reason + evidence carried), the position it buys is filed back under
 * it at its close, and a door proven losing on live closes keeps one probe open at a time; a dataset too big to upload is
 * also saved gzip-compressed, the same lines.
 */
class Aate8985LiveDisciplineTest {
    private val d = LiveDiscipline8985
    private val now = 1_800_000_000_000L
    private val pocket = Evidence8136(kind = "LANE_POCKET", id = "cell", n = 40, meanPct = 20.0, sePct = 4.0)

    private fun cand(mint: String, live: Boolean = true) = Candidate8136(
        mint = mint, lane = "MOONSHOT", selectedSetup = "S", regime = "TRENDING", stage = "BASE_START",
        priceAgeMs = 5_000L, expectedGrossPct = 30.0, costPct = 6.0, downsidePct = 15.0,
        expectedGrossBySetup = emptyMap(), setups = emptyList(), cell = null, timing = null, genericScore = 12.0, genericFloor = 30.0,
        live = live, marketCell8723 = pocket)

    private val admit = CandidateAdmission8136.Decision8136(Terminal8136.ADMIT, "SPECIFIC_EDGE_PROVEN_8136", true, "S", 10.0, emptyList(), "SETUP:S n40")

    @Test fun theRule() {
        assertFalse(d.provenLosing8985(listOf(-5.0, -5.0, -5.0, -5.0, -5.0)))   // five: not yet
        assertTrue(d.provenLosing8985(listOf(-9.0, -6.0, 4.0, -12.0, -3.0, -8.0)))
        assertFalse(d.provenLosing8985(listOf(-9.0, -6.0, 60.0, -12.0, -3.0, -8.0)))   // one runner and the loss is not proven
        assertTrue(d.provenPaying8985(listOf(9.0, 6.0, 14.0, 12.0, -3.0, 8.0)))
        assertEquals("SPECIFIC_EDGE_PROVEN_8136", d.doorCore8985("SPECIFIC_EDGE_PROVEN_8136+GRAB+TICKET"))
        assertEquals("MOONSHOT|SPECIFIC_EDGE_PROVEN_8136|NATIVE", d.fine8985("moonshot", "SPECIFIC_EDGE_PROVEN_8136", null))
        val rows = (0 until 30).map { doubleArrayOf((now - it * 60_000L).toDouble(), it.toDouble()) }.reversed()
        assertEquals(LiveDiscipline8985.WINDOW_N_8985, d.window8985(rows, now).size)
        assertTrue("older than 72 h is out", d.window8985(listOf(doubleArrayOf((now - 73L * 3_600_000L).toDouble(), -50.0)), now).isEmpty())
    }

    @Test fun aLosingDoorKeepsOneProbeOpen() {
        d.resetForTest8985()
        // six live closes through MOONSHOT | SPECIFIC_EDGE_PROVEN | LANE_POCKET, all losing
        listOf(-9.0, -6.0, 4.0, -12.0, -3.0, -8.0).forEachIndexed { i, pct ->
            val mint = "Mint8985Loss$i"
            assertEquals(Terminal8136.ADMIT, d.discipline8985(cand(mint), admit, now + i).terminal)
            d.onLiveBuy8985(mint, "MOONSHOT", "SPECIFIC_EDGE_PROVEN_8136", now + i)
            d.onLiveClose8985(mint, pct, now + i + 1)
        }
        // the door is proven losing and holds no position: the next admit is the probe
        val probe = d.discipline8985(cand("Mint8985Probe"), admit, now + 100)
        assertEquals(Terminal8136.ADMIT, probe.terminal)
        d.onLiveBuy8985("Mint8985Probe", "MOONSHOT", "SPECIFIC_EDGE_PROVEN_8136", now + 101)
        // while the probe is open, a second admit through the same door is refused, terminally
        val second = d.discipline8985(cand("Mint8985Second"), admit, now + 102)
        assertEquals(Terminal8136.REFUSE, second.terminal)
        assertEquals(LiveDiscipline8985.REASON_8985, second.reason)
        assertTrue(second.specific)
        // a different door (no evidence carried, another reason) is untouched
        val other = admit.copy(reason = "LANE_TRIGGER_8468")
        assertEquals(Terminal8136.ADMIT, d.discipline8985(cand("Mint8985Other").copy(marketCell8723 = null), other, now + 103).terminal)
        // paper is never disciplined
        assertEquals(Terminal8136.ADMIT, d.discipline8985(cand("Mint8985Paper", live = false), admit, now + 104).terminal)
        // the probe wins big: the record stops proving a loss and the door opens again
        d.onLiveClose8985("Mint8985Probe", 120.0, now + 200)
        d.onLiveBuy8985("Mint8985Third", "MOONSHOT", "SPECIFIC_EDGE_PROVEN_8136", now + 201)
        assertEquals(Terminal8136.ADMIT, d.discipline8985(cand("Mint8985Fourth"), admit, now + 202).terminal)
        assertTrue(d.line8985(now + 300).startsWith("liveDiscipline8985[filed=7 "))
        d.resetForTest8985()
    }

    @Test fun theBookSurvivesARestart() {
        d.resetForTest8985()
        d.discipline8985(cand("Mint8985Keep"), admit, now)
        d.onLiveBuy8985("Mint8985Keep", "MOONSHOT", "SPECIFIC_EDGE_PROVEN_8136", now)
        d.onLiveClose8985("Mint8985Keep", -7.0, now + 1)
        d.onLiveBuy8985("Mint8985Held", "MOONSHOT", "SPECIFIC_EDGE_PROVEN_8136", now + 2)
        val raw = d.encode8985()
        d.resetForTest8985()
        d.decode8985(raw)
        assertEquals(raw, d.encode8985())
        d.resetForTest8985()
    }

    @Test fun aBigDatasetIsAlsoSavedCompressed() {
        val src = File.createTempFile("ds8985", ".jsonl")
        try {
            src.writeText((0 until 2_000).joinToString("\n") { """{"t":$it,"mint":"So11111111111111111111111111111111111111112","net":-3.2}""" })
            val out = java.io.ByteArrayOutputStream()
            assertEquals(src.length(), ReportCopyDialog8198.gzipTo8985(src, out))
            val back = java.util.zip.GZIPInputStream(out.toByteArray().inputStream()).readBytes()
            assertTrue(back.contentEquals(src.readBytes()))
            assertTrue("line JSON compresses", out.size() * 5 < src.length())
            assertEquals(24L * 1024L * 1024L, ReportCopyDialog8198.GZIP_OVER_BYTES_8985)
        } finally { src.delete() }
    }

    @Test fun wired() {
        val adm = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(adm.contains(".let { LiveDiscipline8985.discipline8985(candidate, it, nowMs) }.also {"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryReceipt8500.kt").readText()
            .contains("LiveDiscipline8985.onLiveBuy8985(mint, receipt.lane, receipt.door, nowMs)"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("LiveDiscipline8985.line8985()"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/ui/ReportCopyDialog8198.kt").readText().contains("saveGzip8985(app, src, name)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 170. The door answers for its live money (8985)"))
    }
}
