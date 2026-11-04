package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.CortexVotersWide8004
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class Aate8527ScannerSourceEvidenceTest {
    private val mapNames = listOf("sourceWins", "sourceLosses", "liqBucketWins", "liqBucketLosses", "ageBucketWins", "ageBucketLosses")
    private val saved = HashMap<String, Map<String, Int>>()
    private var oldContext: Any? = null
    private fun field(name: String) = ScannerLearning.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun values(name: String) = field(name).get(ScannerLearning) as MutableMap<String, Int>

    @Before fun isolate() {
        oldContext = field("ctx").get(ScannerLearning)
        field("ctx").set(ScannerLearning, null)
        for (name in mapNames) { saved[name] = HashMap(values(name)); values(name).clear() }
    }

    @After fun restore() {
        for (name in mapNames) { values(name).clear(); values(name).putAll(saved.getValue(name)) }
        field("ctx").set(ScannerLearning, oldContext)
    }

    @Test fun noDataAndSmallSamplesAbstainWhileLegacyScoreApiStaysNeutral() {
        assertNull(ScannerLearning.sourceEvidence8527("A"))
        assertEquals(0.5, ScannerLearning.getSourceWinRate("A"), 0.0)
        repeat(4) { ScannerLearning.recordTrade("A", 10_000.0, 1.0, false) }
        assertNull(ScannerLearning.sourceEvidence8527("A"))
        assertEquals(0.5, ScannerLearning.getSourceWinRate("A"), 0.0)
        ScannerLearning.recordTrade("A", 10_000.0, 1.0, false)
        assertEquals(0.0, ScannerLearning.sourceEvidence8527("A")!!.winRate, 0.0)
    }

    @Test fun measuredZeroFiftyAndHundredPercentRemainDistinctFromMissing() {
        repeat(6) { i ->
            ScannerLearning.recordTrade("ZERO", 10_000.0, 1.0, false)
            ScannerLearning.recordTrade("HALF", 10_000.0, 1.0, i % 2 == 0)
            ScannerLearning.recordTrade("ALL", 10_000.0, 1.0, true)
        }
        for ((source, rate) in listOf("ZERO" to 0.0, "HALF" to 0.5, "ALL" to 1.0)) {
            val evidence = ScannerLearning.sourceEvidence8527(source)!!
            assertEquals(6L, evidence.sampleCount)
            assertEquals(rate, evidence.winRate, 0.0)
        }
        assertNull(ScannerLearning.sourceEvidence8527("MISSING"))
    }

    @Test fun commaProvenanceReadsOneDeclaredFamilyWithoutDoubleCountingOrBestPicking() {
        repeat(6) { ScannerLearning.recordTrade(" A, B,A ", 10_000.0, 2.0, true) }
        repeat(6) { ScannerLearning.recordTrade("B", 10_000.0, 2.0, false) }
        val a = ScannerLearning.sourceEvidence8527(" A, B,A ")!!
        val b = ScannerLearning.sourceEvidence8527("B,A")!!
        assertEquals("A", a.source)
        assertEquals(6L, a.sampleCount)
        assertEquals(1.0, a.winRate, 0.0)
        assertEquals(12L, b.sampleCount)
        assertEquals(0.5, b.winRate, 0.0)
        assertEquals(1.0, ScannerLearning.getSourceWinRate("A,B"), 0.0)
        assertNull("a good secondary source cannot silently replace the absent primary", ScannerLearning.sourceEvidence8527("MISSING,A"))
        assertEquals("A", ScannerLearning.sourceEvidence8527(" , A,B")!!.source)
    }

    @Test fun repeatedCorrelatedTagsDoNotClearTheSampleFloor() {
        repeat(4) { ScannerLearning.recordTrade("A,B,A,B", 10_000.0, 2.0, true) }
        assertEquals(4, values("sourceWins")["A"])
        assertEquals(4, values("sourceWins")["B"])
        assertNull(ScannerLearning.sourceEvidence8527("A,B"))
    }

    @Test fun cortexVoterAbstainsOnFallbackButKeepsGenuineNeutralEvidence() {
        assertFalse(CortexVotersWide8004.VOTERS.any { it.id == "SCANNER_SOURCE_WR" })
        val voter = CortexVotersWide8004.VOTERS.single { it.id == "SCANNER_SOURCE_WR_8527" }
        val token = TokenState("ScannerEvidence8527", symbol = "S").apply { source = "A,B" }
        assertNull(voter.read(token, "MOONSHOT", System.currentTimeMillis()))
        repeat(6) { ScannerLearning.recordTrade("A,B", 10_000.0, 2.0, it % 2 == 0) }
        assertEquals(0.5, voter.read(token, "MOONSHOT", System.currentTimeMillis())!!, 0.0)
        values("sourceWins")["A"] = 0
        assertEquals(0.5, ScannerLearning.getSourceWinRate("A"), 0.0) // only three observations remain
        assertNull(voter.read(token, "MOONSHOT", System.currentTimeMillis()))
        values("sourceLosses")["A"] = 6
        assertEquals(0.0, voter.read(token, "MOONSHOT", System.currentTimeMillis())!!, 0.0)
    }

    @Test fun unknownAndInvalidAgeCannotTeachTheYoungOrOldestCohort() {
        for (age in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            repeat(5) { ScannerLearning.recordTrade("A", 10_000.0, age, true) }
            assertNull(ScannerLearning.ageBucket8527(age))
        }
        assertTrue(values("ageBucketWins").isEmpty())
        assertTrue(values("ageBucketLosses").isEmpty())
        assertEquals(20L, ScannerLearning.sourceEvidence8527("A")!!.sampleCount)
        ScannerLearning.recordTrade("A", 10_000.0, 0.0, true)
        assertEquals(1, values("ageBucketWins")["age_0_1h"])
        assertEquals("age_1_6h", ScannerLearning.ageBucket8527(1.0))
        assertEquals("age_6_24h", ScannerLearning.ageBucket8527(6.0))
        assertEquals("age_24h_plus", ScannerLearning.ageBucket8527(24.0))
    }

    @Test fun unknownAgeCannotReadABonusFromAnyKnownAgeBucket() {
        values("ageBucketWins")["age_0_1h"] = 10
        values("ageBucketWins")["age_24h_plus"] = 10
        for (age in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(0.0, ScannerLearning.getDiscoveryBonus("MISSING", 500_000.0, age), 0.0)
        }
        assertEquals(7.5, ScannerLearning.getDiscoveryBonus("MISSING", 500_000.0, 0.0), 0.0)
        assertEquals(7.5, ScannerLearning.getDiscoveryBonus("MISSING", 500_000.0, 24.0), 0.0)
    }

    @Test fun persistedValidSourceAndAgeEvidenceKeepsItsOriginalMeaning() {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSharedPreferences("scanner_learning", Context.MODE_PRIVATE)).thenReturn(prefs)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(prefs.getString("sourceWins", null)).thenReturn(JSONObject().put("A", 6).toString())
        `when`(prefs.getString("sourceLosses", null)).thenReturn(JSONObject().put("A", 4).toString())
        `when`(prefs.getString("ageBucketWins", null)).thenReturn(JSONObject().put("age_6_24h", 8).toString())
        `when`(prefs.getString("ageBucketLosses", null)).thenReturn(JSONObject().put("age_6_24h", 2).toString())
        ScannerLearning.init(context)
        val evidence = ScannerLearning.sourceEvidence8527("A,B")!!
        assertEquals(10L, evidence.sampleCount)
        assertEquals(0.6, evidence.winRate, 0.0)
        assertEquals(8, values("ageBucketWins")["age_6_24h"])
        assertEquals(2, values("ageBucketLosses")["age_6_24h"])
        ScannerLearning.recordTrade("C", 10_000.0, Double.NaN, true)
        assertEquals(mapOf("age_6_24h" to 8), values("ageBucketWins"))
        assertEquals(mapOf("age_6_24h" to 2), values("ageBucketLosses"))
        verify(editor).putString(eq("ageBucketWins"), eq(JSONObject().put("age_6_24h", 8).toString()))
    }
}
