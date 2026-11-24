package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ResearchRows8877
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8990 — the owner's research hour rows ship with the build and every pocket reads them before the device's own file:
 * the same row format, hour rows only, no crypto lanes, no diagnostics; a missing asset reads nothing and breaks nothing.
 */
class Aate8990ResearchSeedTest {
    private val asset = File("src/main/assets/" + ResearchRows8877.SEED_ASSET_8990)

    @Test fun theShippedRowsAreTheResearchFormat() {
        assertTrue(asset.isFile)
        assertTrue("small enough to ship", asset.length() < 1_500_000L)
        var base = 0; var withPred = 0; var unpriced = 0
        val lanes = HashSet<String>()
        val n = ResearchRows8877.forEachSeedLine8990(open = { asset.inputStream() }) { l ->
            assertTrue(l.contains("\"horizonMs\":3600000"))
            assertFalse(l.contains("diag8891"))
            val o = JSONObject(l)
            assertTrue(o.getString("mint").length in 32..44)
            assertTrue(o.getLong("t") > 1_795_000_000_000L)
            lanes += o.getString("lane")
            if (l.contains("\"stage\":\"BASE_START\"")) base++
            if (!l.contains("\"pred\":null")) withPred++
            if (!ResearchRows8877.rowNet8983(o).isFinite()) unpriced++
        }
        assertEquals(13_022, n)
        assertTrue(lanes.containsAll(listOf("MOONSHOT", "EXPRESS", "SHITCOIN", "CORE")))
        assertTrue(lanes.none { it.startsWith("CRYPTO") || it.startsWith("EVM") })
        assertTrue("the base pocket's window (200 coins) is covered", base >= 1_000)
        assertTrue(withPred > 10_000)
        assertTrue("nearly every row is a reading", unpriced < n / 20)
    }

    @Test fun aMissingAssetReadsNothing() {
        assertEquals(0, ResearchRows8877.forEachSeedLine8990(open = { null }) { throw IllegalStateException("never called") })
        // V5.0.8992 — a plain-text stream is read as it is (the reader goes by the stream's own magic); was 0 for "not gzip"
        assertEquals(2, ResearchRows8877.forEachSeedLine8990(open = { "{\"a\":1}\n{\"a\":2}\n".byteInputStream() }) { })
        assertEquals(0, ResearchRows8877.forEachSeedLine8990(open = { ByteArray(0).inputStream() }) { })
        assertEquals("research_seed_8990.bin", ResearchRows8877.SEED_ASSET_8990)
        assertTrue(ResearchRows8877.seedLine8990().contains(" last=ok "))
        assertEquals(0, ResearchRows8877.forEachSeedLine8990(open = { throw java.io.FileNotFoundException("gone") }) { })
        assertTrue(ResearchRows8877.seedLine8990().contains(" last=FAILED FileNotFoundException: gone"))
        assertTrue(ResearchRows8877.seedLine8990().startsWith("researchSeed8990[linesRead="))
    }

    @Test fun everyPocketReadsTheSeedFirst() {
        fun src(n: String) = File("src/main/kotlin/com/lifecyclebot/engine/truth/$n.kt").readText()
        val lane = src("LanePocket8934")
        assertTrue(lane.contains("ResearchRows8877.forEachSeedLine8990 { l -> fileLine8990(l) }"))
        assertTrue(lane.contains("val loaded = if (source.isFile) loadFile8961(source) else seedRows > 0"))
        for (n in listOf("BasePocket8904", "AgreementPocket8874")) {
            val s = src(n)
            assertTrue(n, s.indexOf("ResearchRows8877.forEachSeedLine8990 {") in 1 until s.indexOf("ResearchRows8877.file8877()?.takeIf { it.exists() }?.useLines"))
        }
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("ResearchRows8877.seedLine8990()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 175. The proven pockets ship with the build (8990)"))
    }
}
