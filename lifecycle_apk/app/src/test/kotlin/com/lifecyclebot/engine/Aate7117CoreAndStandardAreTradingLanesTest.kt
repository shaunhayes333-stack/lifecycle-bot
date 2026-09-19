package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.MemeOwnershipInvariant6620
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.7117 §CORE_AND_STANDARD_ARE_TRADING_LANES.
 *
 * Operator, twice: "core and standard are trading lanes."
 *
 * The gate denied each one differently, and neither denial was visible as a
 * refusal to trade:
 *
 *   CORE      sat in SOURCE_BUCKET_LANES_6871, from which isRealExecutionLane
 *             was derived, so a CORE candidate with no immutable authority, no
 *             election receipt and no real state.selectedLane resolved to
 *             "UNKNOWN" and was dropped as CANON_LANE_UNRESOLVED — 452 on the
 *             5.0.7115 snapshot, while CORE was the busiest lane in the book at
 *             ownerSelected=285 / buyIntent=304 / rawOpen=2.
 *
 *   STANDARD  sat in isShadowReadOnlyLane6487, which returns early out of
 *             recordFdg and blocks the open at EXEC_OPEN_BLOCKED_SHADOW_LANE_6487.
 *             1,485 lane evaluations, scored and learned from, none allowed a
 *             decision — while STANDARD simultaneously held and closed real
 *             positions through the two exemptions on that check.
 */
class Aate7117CoreAndStandardAreTradingLanesTest {

    private val gateSrc: String by lazy {
        File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
    }

    // ── CORE ──────────────────────────────────────────────────────────────────

    @Test fun `core is a real execution lane`() {
        // Before 7117 this was false, because CORE is a source bucket and
        // isRealExecutionLane was "not a source bucket".
        assertTrue("CORE must be executable — MemeOwnershipInvariant6620 SPECIALIST_LANES",
            ExecutableOpenGate.isRealExecutionLaneForTests7117("CORE"))
        assertTrue(ExecutableOpenGate.isRealExecutionLaneForTests7117("core"))
    }

    @Test fun `standard is a real execution lane`() {
        assertTrue(ExecutableOpenGate.isRealExecutionLaneForTests7117("STANDARD"))
    }

    @Test fun `core remains a source bucket so trunk forgiveness survives`() {
        // This is the half that must NOT change. A MOONSHOT-sealed intent
        // requested through the CORE trunk has to keep matching, or every
        // specialist-via-trunk restore becomes a false lane mismatch — the exact
        // defect V5.9.1169 and V5.0.7115 were written to remove.
        assertTrue("MOONSHOT sealed, requested via the CORE trunk, must still match",
            ExecutableOpenGate.lanesCompatibleForTests("MOONSHOT", "CORE"))
        assertTrue(ExecutableOpenGate.lanesCompatibleForTests("PROJECT_SNIPER", "CORE"))
        assertTrue(ExecutableOpenGate.lanesCompatibleForTests("CASHGEN", "CORE"))
    }

    @Test fun `scanner sources are still not execution lanes`() {
        listOf(
            "UNKNOWN", "WATCHLIST", "PUMP_PORTAL", "PUMP_PORTAL_WS", "PUMP_FUN",
            "PUMP_FUN_NEW", "PUMP_FUN_GRADUATE", "DEX_TRENDING", "DEX_BOOSTED",
            "RAYDIUM", "RAYDIUM_NEW_POOL", "COINGECKO", "COINGECKO_TRENDING",
            "SCANNER_DIRECT", "SCANNER_DIRECT_PUMP_FUN_NEW",
        ).forEach { src ->
            assertFalse("$src is a scanner source, not an execution lane",
                ExecutableOpenGate.isRealExecutionLaneForTests7117(src))
        }
        assertFalse(ExecutableOpenGate.isRealExecutionLaneForTests7117(""))
        assertFalse(ExecutableOpenGate.isRealExecutionLaneForTests7117("   "))
    }

    /**
     * The exception must stay exactly one lane wide. isRealExecutionLane now asks
     * SPECIALIST_LANES before the bucket set, so anything in BOTH sets is exempt
     * from the bucket rule. Today that is {CORE} and only CORE. If a future edit
     * adds a scanner source to SPECIALIST_LANES — or a specialist to the bucket
     * set — this fails rather than silently widening a documented exception.
     */
    @Test fun `the specialist exception is exactly one lane wide`() {
        val buckets = ExecutableOpenGate.sourceBucketLanesForTests7117()
        val overlap = MemeOwnershipInvariant6620.SPECIALIST_LANES.intersect(buckets)
        assertEquals("the trunk-and-specialist exception must be CORE alone",
            setOf("CORE"), overlap)
    }

    @Test fun `every specialist lane is executable`() {
        MemeOwnershipInvariant6620.SPECIALIST_LANES.forEach { lane ->
            assertTrue("$lane is in SPECIALIST_LANES and must be executable",
                ExecutableOpenGate.isRealExecutionLaneForTests7117(lane))
        }
    }

    // ── STANDARD ──────────────────────────────────────────────────────────────

    @Test fun `standard is no longer a shadow read only lane`() {
        val body = gateSrc.substringAfter("private fun isShadowReadOnlyLane6487")
            .substringBefore("fun recordEntryAuthority6487")
        assertTrue("V3_CORE stays — operator named CORE and STANDARD, and §11 " +
            "forbids aliasing the three together", body.contains("V3_CORE"))
        assertFalse("STANDARD is a trading lane", body.contains("STANDARD"))
        assertFalse("CASHGEN left in 6705", body.contains("CASHGEN"))
    }

    @Test fun `the shadow lane check still exists and still has both teeth`() {
        // Removing STANDARD must not have removed the mechanism: V3_CORE still
        // has to be suppressed at recordFdg AND blocked at the open.
        assertTrue(gateSrc.contains("SHADOW_LANE_FDG_SUPPRESSED_6487"))
        assertTrue(gateSrc.contains("EXEC_OPEN_BLOCKED_SHADOW_LANE_6487"))
    }

    /**
     * MemeOwnershipInvariant6620 answers a DIFFERENT question — may this lane
     * take an execution slot away from a specialist that already owns the
     * candidate — and that answer is still no for STANDARD. A lane may trade a
     * candidate nobody owns without being allowed to steal one that is owned.
     * This pins that the two were not conflated while fixing the first.
     */
    @Test fun `standard still may not steal an owned candidate`() {
        val ownership = File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/MemeOwnershipInvariant6620.kt"
        ).readText()
        assertTrue("STANDARD/V3_CORE must remain barred from stealing a specialist's slot",
            ownership.contains("setOf(\"STANDARD\", \"V3_CORE\")"))
        assertTrue(MemeOwnershipInvariant6620.SPECIALIST_LANES.contains("CORE"))
        assertFalse(MemeOwnershipInvariant6620.SPECIALIST_LANES.contains("STANDARD"))
    }
}
