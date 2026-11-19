package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8878 — owner, 20 Nov: "the shadow trading engine is meant to basically be exactly the same as the live trader in all
 * forms and functions". On a doctrine lane the evidence shadow (whose record lifts LIVE vetoes and unlocks lanes) now exits
 * by the live doctrine: the TIERS_7967 ladder and the doctrine line on two marks, not the scalp tiers. Production open, mark,
 * exit and close paths only.
 */
class Aate8878ShadowDoctrineTest {
    private var epoch = 0L
    private var wasPaper = true
    private var t0 = 0L

    @Before fun setup() {
        epoch = LearningEpoch8164.epochMs8164()
        wasPaper = RuntimeModeAuthority.isPaper()
        t0 = maxOf(System.currentTimeMillis(), EvidenceHold8336.startMs8336, epoch) + 1_000L
        EvidenceShadow8344.resetForTests8345()
        LearningEpoch8164.setForTest8164(t0 - 1)
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
    }

    @After fun cleanup() {
        EvidenceShadow8344.resetForTests8345()
        LearningEpoch8164.setForTest8164(epoch)
        RuntimeModeAuthority.publishConfig(paperMode = wasPaper, autoTrade = false)
    }

    private fun landedMint(prefix: String, lane: String): String =
        (0..10_000).asSequence().map { "$prefix-$it" }.first { mint ->
            ShadowAlign8366.lands8366("$mint|$lane|$t0|EVIDENCE", ShadowAlign8366.landFor8366(mint, t0).rate)
        }

    private fun token(mint: String) = TokenState(mint = mint, symbol = "T8878").also {
        it.lastPrice = 1.0; it.lastPriceUpdate = t0; it.lastLiquidityUsd = 1_000_000.0; it.lastMcap = 1_000_000.0
    }

    @Suppress("UNCHECKED_CAST")
    private fun open(): Map<String, Any> = EvidenceShadow8344::class.java.getDeclaredField("open")
        .apply { isAccessible = true }.get(EvidenceShadow8344) as Map<String, Any>

    private fun field(p: Any, name: String): Any? = p.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(p)

    private fun mark(ts: TokenState, px: Double, at: Long) {
        ts.lastPrice = px; ts.lastPriceUpdate = at
        EvidenceShadow8344.tick8344(mapOf(ts.mint to ts), at)
    }

    @Test fun aDoctrineLaneRidesPast40PercentTakesThe2xRungAndSellsOnTheDoctrineLine() {
        val ts = token(landedMint("doctrine-8878", "MOONSHOT"))
        EvidenceShadow8344.open8344(ts, "MOONSHOT", t0)
        val p = open().values.single()
        mark(ts, 1.6, t0 + 10_000L)
        assertNull("no scalp partial at +40 % on a doctrine lane", field(p, "exitWhy8366"))
        mark(ts, 2.3, t0 + 20_000L)
        assertEquals("the 2x rung of TIERS_7967", EvidenceShadow8344.DOCTRINE_RUNG_8878, field(p, "exitWhy8366"))
        assertEquals(0.25, field(p, "sellFraction8394") as Double, 1e-12)
        val fill = t0 + 20_000L + ShadowAlign8366.sellLatencyMs8366(false) + 1
        mark(ts, 2.3, fill)
        assertEquals(0.75, field(p, "held") as Double, 1e-12)
        assertEquals(1, field(p, "tiersHit"))
        assertEquals(1, open().size)
        // the doctrine line from a ~2.2x peak is the 1.5x floor: one mark under it waits, the second distinct mark sells
        mark(ts, 1.4, fill + 1_000L)
        assertNull("one mark under the line is a wick", field(p, "exitWhy8366"))
        mark(ts, 1.4, fill + 2_000L)
        assertEquals(EvidenceShadow8344.DOCTRINE_TRAIL_8878, field(p, "exitWhy8366"))
        mark(ts, 1.4, fill + 2_000L + ShadowAlign8366.sellLatencyMs8366(false) + 1)
        assertTrue(open().isEmpty())
        assertEquals(1, EvidenceShadow8344.proof8344("MOONSHOT", t0).n)
        assertTrue(EvidenceShadow8344.statusLine8344(t0).contains("doctrine lanes (8878): TIERS_7967 rungs+doctrine line on two marks"))
    }

    @Test fun aNonDoctrineLaneKeepsItsOwnExits() {
        val ts = token(landedMint("quality-8878", "QUALITY"))
        EvidenceShadow8344.open8344(ts, "QUALITY", t0)
        val p = open().values.single()
        mark(ts, 1.6, t0 + 10_000L)
        assertEquals("QUALITY still takes the scalp tier", "SPIKE_PARTIAL_8394", field(p, "exitWhy8366"))
    }

    @Test fun aDoctrineLossStillStopsAndTheClockOnlyPassesUnderHalfAGain() {
        val ts = token(landedMint("loss-8878", "SHITCOIN"))
        EvidenceShadow8344.open8344(ts, "SHITCOIN", t0)
        val p = open().values.single()
        mark(ts, 0.3, t0 + 1_000L)
        assertEquals("a crash past -60 % confirms on its first mark", "STOP", field(p, "exitWhy8366"))
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/EvidenceShadow8344.kt").readText()
        val fn = src.substringAfter("private fun doctrineWhy8878(").substringBefore("internal const val DOCTRINE_TRAIL_8878")
        assertTrue(fn.contains("com.lifecyclebot.engine.RunnerDoctrine8584.paperStopLevel8584(p.stopPct, p.peakPct)"))
        assertTrue(fn.contains("MemePaperTrader8544.rung8544(com.lifecyclebot.engine.RunnerGrab7967.TIERS_7967, p.tiersHit, g)"))
        assertTrue(fn.contains("!com.lifecyclebot.engine.RunnerDoctrine8584.holds8584(p.lane, \"CLOCK_TIME\", g, p.peakPct, p.stopPct)) return \"TIMEOUT\""))
        assertTrue("a doctrine lane is never promoted into the lifecycle hold here",
            src.contains("if (!doctrine8878 && !p.hold8366 && fresh != null && ShadowAlign8366.holdPromotes8366(g, p.peakPct))"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 67. The evidence shadow trades the live doctrine (8878)"))
    }
}
