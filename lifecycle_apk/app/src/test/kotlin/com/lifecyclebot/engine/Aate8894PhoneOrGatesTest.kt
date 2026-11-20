package com.lifecyclebot.engine

import com.lifecyclebot.engine.NetworkVerdict8894.Host8894
import com.lifecyclebot.engine.truth.LaunchHour8628
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8894 — the phone or the gates: (1) the brief names a device-wide network failure (5.0.8893's "dead" 10 minutes:
 * five providers failing on transport at once while the FDG's rate was 5.0.8882's); (2) 8888's first-decision read, the app's
 * top CPU frame in 5.0.8889 / 5.0.8893, reads only the replayed launches, by mint, on the primary key.
 */
class Aate8894PhoneOrGatesTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    // the host table of the 17:57 report (5.0.8893) and of the 11:22 report (5.0.8882): successes, 4xx, 5xx, transport failures
    private val at1757 = listOf(
        Host8894("jupiter", 11, 8, 30, 150), Host8894("jupiter_price_keyed", 14, 4, 0, 112),
        Host8894("dexscreener", 123, 0, 0, 58), Host8894("raydium", 118, 0, 0, 55), Host8894("helius", 105, 0, 0, 44),
        Host8894("helius_curve", 113, 0, 0, 0), Host8894("helius-enhanced", 5, 0, 0, 20), Host8894("coingecko", 3, 2, 0, 1))
    private val at1122 = listOf(
        Host8894("jupiter", 42, 18, 30, 1), Host8894("jupiter_price_keyed", 96, 1, 0, 1), Host8894("dexscreener", 131, 0, 0, 11),
        Host8894("raydium", 139, 0, 0, 17), Host8894("helius", 185, 0, 0, 2), Host8894("geckoterminal", 40, 1, 0, 1))

    @Test fun fiveProvidersFailingTogetherIsThePhonesNetwork() {
        assertTrue(NetworkVerdict8894.degraded8894(at1757))
        val line = NetworkVerdict8894.line8894(at1757)
        assertTrue(line, line.startsWith("network8894[DEGRADED failingProviders=4 jupiter net150/199 75%"))
        assertTrue(line, line.contains("helius net44/149 30%"))
        assertFalse("under 20 calls proves nothing", line.contains("coingecko"))
        assertFalse(NetworkVerdict8894.degraded8894(at1122))
        assertTrue(NetworkVerdict8894.line8894(at1122).startsWith("network8894[OK failingProviders=0 -"))
    }

    @Test fun oneProviderFailingIsThatProviderNotThePhone() {
        val jupiterOnly = listOf(Host8894("jupiter", 11, 8, 30, 150), Host8894("jupiter_price_keyed", 14, 4, 0, 112),
            Host8894("jupiter_quote", 2, 0, 0, 30), Host8894("helius", 185, 0, 0, 2))
        assertEquals(listOf("jupiter", "jupiter", "jupiter"), NetworkVerdict8894.failing8894(jupiterOnly).map { NetworkVerdict8894.provider8894(it.host) })
        assertFalse(NetworkVerdict8894.degraded8894(jupiterOnly))
        assertTrue(NetworkVerdict8894.line8894(jupiterOnly).startsWith("network8894[PROVIDER failingProviders=1 "))
        assertEquals("helius", NetworkVerdict8894.provider8894("helius-enhanced"))
        assertEquals("dlmm", NetworkVerdict8894.provider8894("dlmm.datapi.meteora.ag"))
        assertEquals(emptyList<Host8894>(), NetworkVerdict8894.failing8894(emptyList()))
    }

    @Test fun theBriefSaysSoFirstAndNothingTradesOnIt() {
        val brief = src("engine/DiagnosisBrief8438.kt")
        assertTrue(brief.contains("sb.append(\"               \").append(NetworkVerdict8894.line8894(net8894)).append('\\n')"))
        assertTrue(brief.contains("if (NetworkVerdict8894.degraded8894(net8894)) fixes += Fix8438(9_000.0, \"FAULT\","))
        assertTrue("printed in A RUN, before the money section", brief.indexOf("NetworkVerdict8894.line8894(net8894)") in 0 until brief.indexOf("section(sb, \"B MONEY\")"))
        val users = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("NetworkVerdict8894") }
            .map { it.name }.toSet()
        assertEquals("read by the brief only: no gate, size or exit", setOf("NetworkVerdict8894.kt", "DiagnosisBrief8438.kt"), users)
    }

    @Test fun theFirstDecisionReadAsksOnlyForTheReplayedLaunchesByMint() {
        assertTrue("nothing asked, nothing read", LaunchHour8628.firstDecisions8888(1_795_161_425_000L, emptyList()).isEmpty())
        assertTrue("no database in a unit test: empty, never a throw", LaunchHour8628.firstDecisions8888(1_795_161_425_000L, listOf("DQKmfaSk2eTzfEfykkxzVAjyToZ3jpL5q1rvFMREnxJ")).isEmpty())
        assertEquals("SELECT l,MIN(t),a,c FROM decision WHERE m=? AND t >= CAST(? AS INTEGER) GROUP BY l ORDER BY l", LaunchHour8628.FIRST_SQL_8894)
        val hour = src("engine/truth/LaunchHour8628.kt")
        val body = hour.substringAfter("internal fun firstDecisions8888(now: Long, mints: Collection<String>)").substringBefore("internal const val FIRST_SQL_8894")
        assertTrue(body.contains("db.rawQuery(FIRST_SQL_8894, arrayOf(mint, since))"))
        assertFalse("no read of every decision row", body.contains("ORDER BY m,l,t"))
        assertTrue("the primary key the per-mint read rides", hour.contains("CREATE TABLE IF NOT EXISTS decision(m TEXT,t INTEGER,l TEXT,k TEXT,a INTEGER,c REAL, PRIMARY KEY(m,t,l,k,a))"))
        assertTrue(src("engine/truth/LaunchReplay8744.kt").contains("LaunchHour8628.firstDecisions8888(nowMs, mints8888)"))
        assertTrue(src("engine/truth/LaneExitGrade8888.kt").contains("read8894{\$read}"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 83. The phone or the gates (8894)"))
    }
}
