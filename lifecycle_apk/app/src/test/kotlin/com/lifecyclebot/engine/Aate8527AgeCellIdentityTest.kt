package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.AdmissionBirth8144
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.LearningResetSweep7781
import com.lifecyclebot.engine.truth.TailHunter7996
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class Aate8527AgeCellIdentityTest {
    private fun token(suffix: String) = TokenState(
        mint = ("Age8527" + suffix + System.nanoTime()).padEnd(32, 'x').take(32), symbol = "AGE",
    ).apply { source = "TEST_AGE_8527"; lastMcap = 50_000.0 }

    private fun expected(ts: TokenState, lane: String, now: Long) = ForwardReturnLabeler7731.cellKey(
        ts.source, lane, com.lifecyclebot.engine.truth.TrustedMcap8019.mcap8019(ts),
        ForwardReturnLabeler7731.cellAgeMs8208(ts, now),
    )

    @Test fun unknownBirthStaysUnknownRegardlessOfWatchlistResidence() {
        val now = System.currentTimeMillis()
        val ts = token("unknown").apply { addedToWatchlistAt = now - 60_000L }
        val first = expected(ts, "MOONSHOT", now)
        assertTrue(first.endsWith("|AGE_UNKNOWN"))
        assertEquals(first, CellAllocator7962.cellKey8527(ts, "MOONSHOT", now))
        assertEquals(first, TailHunter7996.cellKey8527(ts, "MOONSHOT", now))
        ts.addedToWatchlistAt = now - 3L * 86_400_000L
        assertEquals(first, CellAllocator7962.cellKey8527(ts, "MOONSHOT", now))
        assertEquals(first, TailHunter7996.cellKey8527(ts, "MOONSHOT", now))
    }

    @Test fun oldBirthCannotEnterFreshCellsAfterRediscovery() {
        val now = System.currentTimeMillis()
        val ts = token("old").apply { addedToWatchlistAt = now - 60_000L }
        AdmissionBirth8144.noteCreateFrame8144(ts.mint, now - 2L * 86_400_000L)
        assertEquals(2L * 86_400_000L, ForwardReturnLabeler7731.cellAgeMs8208(ts, now))
        assertEquals(expected(ts, "MOONSHOT", now), CellAllocator7962.cellKey8527(ts, "MOONSHOT", now))
        assertEquals(expected(ts, "MOONSHOT", now), TailHunter7996.cellKey8527(ts, "MOONSHOT", now))
        assertFalse(expected(ts, "MOONSHOT", now).endsWith("|AGE_LT15M"))
    }

    @Test fun witnessedFreshBirthIsNotAgedByAnOldWatchlistRow() {
        val now = System.currentTimeMillis()
        val ts = token("fresh").apply { addedToWatchlistAt = now - 86_400_000L }
        AdmissionBirth8144.noteCreateFrame8144(ts.mint, now - 30_000L)
        assertTrue(expected(ts, "SHITCOIN", now).endsWith("|AGE_LT15M"))
        assertEquals(expected(ts, "SHITCOIN", now), CellAllocator7962.cellKey8527(ts, "SHITCOIN", now))
        assertEquals(expected(ts, "SHITCOIN", now), TailHunter7996.cellKey8527(ts, "SHITCOIN", now))
    }

    @Test fun futureWitnessDoesNotBecomeAgeZero() {
        val now = System.currentTimeMillis()
        val ts = token("future").apply { addedToWatchlistAt = now - 60_000L }
        AdmissionBirth8144.noteCreateFrame8144(ts.mint, now + 30_000L)
        assertEquals(-1L, ForwardReturnLabeler7731.cellAgeMs8208(ts, now))
        assertTrue(CellAllocator7962.cellKey8527(ts, "CORE", now).endsWith("|AGE_UNKNOWN"))
        assertTrue(TailHunter7996.cellKey8527(ts, "CORE", now).endsWith("|AGE_UNKNOWN"))
    }

    @Test fun onlyAgeDependentRealisedNodesChangeTheirEvidenceNamespace() {
        val cell = "CELL|SRC|MOONSHOT|MC_10K_100K|AGE_LT15M"
        val band = "BAND|SRC|MC_10K_100K|AGE_LT15M"
        assertEquals("V8527|$cell", CellAllocator7962.nodeKey8527("V", cell))
        assertEquals("P8527|$band", CellAllocator7962.nodeKey8527("P", band))
        assertEquals("L8527|$cell", CellAllocator7962.nodeKey8527("L8527", cell))
        for (node in listOf("G", "LANE|MOONSHOT", "LS|MOONSHOT|BREAKOUT")) {
            assertEquals("V|$node", CellAllocator7962.nodeKey8527("V", node))
            assertEquals("P|$node", CellAllocator7962.nodeKey8527("P", node))
        }
    }

    @Test fun migrationPreservesValidNodesByteForByteWithoutReinterpretingLegacyCells() {
        val legacyCell = "V|CELL|SRC|MOONSHOT|MC_10K_100K|AGE_LT15M=60,6000,600000,60,60,-0.1"
        val legacyBand = "P|BAND|SRC|MC_10K_100K|AGE_LT15M=60,6000,600000,60,60,-0.1"
        val valid = listOf(
            "V|LANE|MOONSHOT=60,600,6000,60,60,-0.1",
            "P|LS|MOONSHOT|BREAKOUT=20,200,2000,20,20,-0.2",
            "L8527|CELL|SRC|MOONSHOT|MC_10K_100K|AGE_UNKNOWN=10,-100,1000,10,600,0.0",
            "V8527|CELL|SRC|MOONSHOT|MC_10K_100K|AGE_UNKNOWN=6,30,150,2,60,0.0",
        )
        val current = CellAllocator7962.currentNodes8527((valid + legacyCell + legacyBand).joinToString(";"))
        assertEquals(valid.joinToString(";"), current)
        assertEquals(current, CellAllocator7962.currentNodes8527(current))
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun legacyPositiveLiveLeafCannotPolluteCurrentNegativeBirthLabel() {
        val field = CellAllocator7962.javaClass.getDeclaredField("nodes").apply { isAccessible = true }
        val nodes = field.get(CellAllocator7962) as MutableMap<String, CellAllocator7962.Stat7962>
        val saved = HashMap(nodes)
        val node = "CELL|TEST_AGE_8527|MOONSHOT|MC_10K_100K|AGE_UNKNOWN"
        fun stat(n: Int, value: Double) = CellAllocator7962.Stat7962().apply { repeat(n) { add(value, 1.0) } }
        val read = CellAllocator7962.javaClass.getDeclaredMethod("statFor", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        try {
            nodes.clear()
            nodes["V|$node"] = stat(200, 300.0)
            nodes["P|$node"] = stat(200, 300.0)
            nodes["L8527|$node"] = stat(10, -10.0)
            nodes["V8527|$node"] = stat(2, 5.0)
            nodes["P8527|$node"] = stat(3, -20.0)
            val live = read.invoke(CellAllocator7962, node, true) as CellAllocator7962.Stat7962
            val paper = read.invoke(CellAllocator7962, node, false) as CellAllocator7962.Stat7962
            assertEquals(12.0, live.w, 0.0)
            assertEquals(-90.0, live.sx, 0.0)
            assertEquals(13.0, paper.w, 0.0)
            assertEquals(-160.0, paper.sx, 0.0)
        } finally { nodes.clear(); nodes.putAll(saved) }
    }

    @Test fun tailMigrationKeepsValidFactsButNeverRelabelsObservedAgeCells() {
        val cell = "SRC|MOONSHOT|MC_10K_100K|AGE_LT15M"
        assertFalse(TailHunter7996.currentKey8527("C|$cell"))
        assertFalse(TailHunter7996.currentKey8527("R|MAYHEM_MODE|$cell"))
        assertTrue(TailHunter7996.currentKey8527("C8527|$cell"))
        assertTrue(TailHunter7996.currentKey8527("R8527|MAYHEM_MODE|$cell"))
        for (key in listOf("S|MOONSHOT|BREAKOUT", "SRC|PUMP_PORTAL_WS|MOONSHOT",
            "F|MOONSHOT|age=LT5M", "P|MOONSHOT|age=LT5M|bp=HIGH")) {
            assertTrue("Birth-resolved specialist facts retain their existing contract: $key", TailHunter7996.currentKey8527(key))
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun laneRuleFallbackAndHiveCannotResurrectLegacyAgeAuthority() {
        val field = TailHunter7996.javaClass.getDeclaredField("stats").apply { isAccessible = true }
        val stats = field.get(TailHunter7996) as MutableMap<String, Any>
        val saved = HashMap(stats)
        val statClass = Class.forName("com.lifecyclebot.engine.truth.TailHunter7996\$Stat7996")
        fun positive(): Any {
            val stat = statClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            for ((name, value) in mapOf("n" to 40, "sum" to 400.0, "runners" to 3, "best" to 100.0)) {
                statClass.getDeclaredField(name).apply { isAccessible = true }.set(stat, value)
            }
            return stat
        }
        val rule = TailHunter7996.javaClass.getDeclaredMethod("laneRuleTailProven8097", String::class.java, String::class.java)
            .apply { isAccessible = true }
        val cell = "SRC|MOONSHOT|MC_10K_100K|AGE_LT15M"
        try {
            stats.clear()
            stats["R|TEST|$cell"] = positive()
            stats["C|$cell"] = positive()
            assertEquals(false, rule.invoke(TailHunter7996, "TEST", "MOONSHOT"))
            assertTrue(TailHunter7996.hiveSnapshot8000().isEmpty())
            stats["R8527|TEST|$cell"] = positive()
            assertEquals(true, rule.invoke(TailHunter7996, "TEST", "MOONSHOT"))
            assertEquals(setOf("R8527|TEST|$cell"), TailHunter7996.hiveSnapshot8000().keys)
        } finally { stats.clear(); stats.putAll(saved) }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun allocatorWritesNewPreferenceWithoutOverwritingLegacyArchive() {
        val owner = CellAllocator7962
        fun field(name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val prefsField = field("prefs"); val oldPrefs = prefsField.get(owner)
        val persistClock = field("lastPersistMs"); val oldClock = persistClock.getLong(owner)
        val nodes = field("nodes").get(owner) as MutableMap<String, CellAllocator7962.Stat7962>
        val saved = HashMap(nodes)
        val ctx = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
        `when`(ctx.applicationContext).thenReturn(ctx)
        `when`(ctx.getSharedPreferences("cell_allocator_7962", Context.MODE_PRIVATE)).thenReturn(prefs)
        `when`(prefs.edit()).thenReturn(editor)
        val old = "V|CELL|legacy=60,6000,600000,60,60,0;V|LANE|MOONSHOT=40,400,4000,40,40,0"
        `when`(prefs.getString("nodes_8521", null)).thenReturn(old)
        try {
            nodes.clear(); prefsField.set(owner, null); persistClock.setLong(owner, 0L)
            owner.attach(ctx)
            assertFalse(nodes.containsKey("V|CELL|legacy"))
            assertEquals(40.0, nodes.getValue("V|LANE|MOONSHOT").w, 0.0)
            owner.javaClass.getDeclaredMethod("persist").apply { isAccessible = true }.invoke(owner)
            verify(editor).putString(eq("nodes_observed_8527"), anyString())
            verify(editor, never()).putString(eq("nodes_birth_8527"), anyString())
            verify(editor, never()).remove("nodes_birth_8527")
            verify(editor, never()).putString(eq("nodes_8521"), anyString())
            verify(editor, never()).remove("nodes_8521")
        } finally {
            nodes.clear(); nodes.putAll(saved); prefsField.set(owner, oldPrefs); persistClock.setLong(owner, oldClock)
        }
    }

    @Test fun everyRuntimeCellPathUsesTheSharedBirthRuleAndResetCoversBothFiles() {
        val tail = File("src/main/kotlin/com/lifecyclebot/engine/truth/TailHunter7996.kt").readText()
        val allocator = File("src/main/kotlin/com/lifecyclebot/engine/CellAllocator7962.kt").readText()
        for (src in listOf(tail, allocator)) {
            assertTrue(src.contains("ForwardReturnLabeler7731.cellAgeMs8208(ts, nowMs)"))
            assertFalse(src.contains("nowMs - ts.addedToWatchlistAt"))
        }
        assertTrue(LearningResetSweep7781.LEARNING_FILES_7975.contains("tail_hunter_7996.txt"))
        assertTrue(LearningResetSweep7781.LEARNING_FILES_7975.contains("tail_hunter_birth_8527.txt"))
        assertTrue(tail.contains("private const val FILE = \"tail_hunter_birth_8527.txt\""))
        assertTrue(tail.contains("LEGACY_OBSERVED_AGE_FILE_8527 = \"tail_hunter_7996.txt\""))
    }
}

