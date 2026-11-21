package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CurveCustody8881
import com.lifecyclebot.engine.truth.LabelEvidence8386
import com.lifecyclebot.engine.truth.LabelMark8386
import com.lifecyclebot.engine.truth.LaunchpadMigration8911
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8911 — a graded coin is followed through its own migration (pump.fun curve -> PumpSwap / Raydium, Meteora DBC -> DAMM,
 * Raydium LaunchLab -> CPMM): a mark on the new pool is the coin's price once the graduation is proven; never onto the dead
 * pump.fun curve; every other evidence rule unchanged.
 */
class Aate8911MigrationFollowTest {
    private fun m(venue: String, px: Double, at: Long, src: String = "DEX_PAIR", mint: String = "M8911") = LabelMark8386(mint, venue, px, at, at, src)
    private val poolA = "PoolAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val poolB = "PoolBbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    @Test fun aProvenMigrationCarriesTheMarkOntoTheNewPool() {
        val curve = m("pump.curve", 1.0, 1_000)
        assertEquals("graduated: the new pool's price is the coin's", "VALID_8386", LabelEvidence8386.status(curve, m(poolB, 1.6, 60_000), true))
        assertEquals("not proven: refused as before", LabelEvidence8386.VENUE_MISMATCH_8783, LabelEvidence8386.status(curve, m(poolB, 1.6, 60_000), false))
        assertEquals("never onto the completed pump.fun curve", LabelEvidence8386.VENUE_MISMATCH_8783,
            LabelEvidence8386.status(m(poolA, 1.0, 1_000), m("pump.curve", 1.6, 60_000), true))
        assertEquals("a 10x still needs both marks chain-witnessed", "OBSERVED_ONLY_UNWITNESSED_EXTREME", LabelEvidence8386.status(curve, m(poolB, 12.0, 60_000), true))
        assertEquals("a 0.1x on another venue still needs a chain read", "OBSERVED_ONLY_VENUE_MISMATCH_EXTREME", LabelEvidence8386.status(curve, m(poolB, 0.05, 60_000), true))
        assertEquals("time order still binds", "OBSERVED_ONLY_TIME_ORDER", LabelEvidence8386.status(m("pump.curve", 1.0, 60_000), m(poolB, 1.6, 1_000), true))
        // the live lookup: a mint whose migration was read
        LaunchpadMigration8911.resetForTest8911()
        assertEquals(LabelEvidence8386.VENUE_MISMATCH_8783, LabelEvidence8386.status(m(poolA, 1.0, 1_000, mint = "N8911"), m(poolB, 1.6, 60_000, mint = "N8911")))
        LaunchpadMigration8911.markForTest8911("N8911")
        assertEquals("VALID_8386", LabelEvidence8386.status(m(poolA, 1.0, 1_000, mint = "N8911"), m(poolB, 1.6, 60_000, mint = "N8911")))
        assertTrue(LaunchpadMigration8911.statusLine8911().startsWith("migration8911[carried=1 "))
        LaunchpadMigration8911.resetForTest8911()
    }

    @Test fun aLaunchpadPoolSaysWhenItsCoinMigrated() {
        val mintBytes = ByteArray(32) { 7 }
        val mint = io.github.novacrypto.base58.Base58.base58Encode(mintBytes)
        fun acct(size: Int, disc: IntArray, mintAt: Int, flagAt: Int, flag: Int) = ByteArray(size).also { d ->
            for (i in 0 until 8) d[i] = disc[i].toByte()
            System.arraycopy(mintBytes, 0, d, mintAt, 32)
            d[flagAt] = flag.toByte()
        }
        val dbc = CurveCustody8881.DBC_PROGRAM_8881
        val lab = CurveCustody8881.LAUNCHLAB_PROGRAM_8881
        assertEquals("DBC", LaunchpadMigration8911.migratedRead8911(mint, dbc, acct(424, CurveCustody8881.DBC_DISC_8881, 136, 305, 1)))
        assertNull("still on its curve", LaunchpadMigration8911.migratedRead8911(mint, dbc, acct(424, CurveCustody8881.DBC_DISC_8881, 136, 305, 0)))
        assertNull("another coin's pool", LaunchpadMigration8911.migratedRead8911("Other8911", dbc, acct(424, CurveCustody8881.DBC_DISC_8881, 136, 305, 1)))
        assertNull("another program", LaunchpadMigration8911.migratedRead8911(mint, "11111111111111111111111111111111", acct(424, CurveCustody8881.DBC_DISC_8881, 136, 305, 1)))
        assertEquals("LAUNCHLAB", LaunchpadMigration8911.migratedRead8911(mint, lab, acct(365, CurveCustody8881.LAUNCHLAB_DISC_8881, 205, 17, 2)))
        assertNull("waiting for migration is not migrated", LaunchpadMigration8911.migratedRead8911(mint, lab, acct(365, CurveCustody8881.LAUNCHLAB_DISC_8881, 205, 17, 1)))
    }

    @Test fun wired() {
        val ev = File("src/main/kotlin/com/lifecyclebot/engine/truth/LabelEvidence8386.kt").readText()
        assertTrue(ev.contains("else try { LaunchpadMigration8911.carriesNow8911(entry, outcome) } catch (_: Throwable) { false })) return VENUE_MISMATCH_8783"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("LaunchpadMigration8911.statusLine8911()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 99. Following a coin through its migration (8911)"))
    }
}
