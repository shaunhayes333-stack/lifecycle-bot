package com.lifecyclebot.engine

import com.lifecyclebot.network.JsonArrayStream8501
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader

class Aate8501MemoryOffloadTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aTokenListIsReadOneRowAtATimeWithStringsAndNestingHonoured() {
        val body = """ [ {"id":"A","name":"has } and ] and \" quote","stats24h":{"buyVolume":1,"x":[1,{"y":"}"}]}},
            7, "s", [1,2], {"id":"B"} , {"id":"C"} ] trailing"""
        val seen = ArrayList<String>()
        val n = JsonArrayStream8501.forEachObject8501(StringReader(body)) { seen += it.optString("id"); true }
        assertEquals(3, n)
        assertEquals(listOf("A", "B", "C"), seen)
        val first = ArrayList<String>()
        JsonArrayStream8501.forEachObject8501(StringReader(body)) { first += it.getString("name"); false }
        assertEquals("has } and ] and \" quote", first.single())
        // not an array: nothing
        assertEquals(0, JsonArrayStream8501.forEachObject8501(StringReader("{\"a\":1}")) { true })
        // an element larger than the cap is skipped, the rest still arrive
        val big = "[{\"id\":\"" + "x".repeat(5000) + "\"},{\"id\":\"ok\"}]"
        val got = ArrayList<String>()
        JsonArrayStream8501.forEachObject8501(StringReader(big), maxElementChars = 1000) { got += it.optString("id"); true }
        assertEquals(listOf("ok"), got)
    }

    @Test fun preferenceValuesRoundTripWithTheirTypes() {
        for (v in listOf<Any>("s", 7, 9_000_000_000L, 1.5f, true, setOf("a", "b"))) {
            val (t, s) = OffloadedPrefs8501.encode(v)!!
            assertEquals(v, OffloadedPrefs8501.decode(t, s))
        }
    }

    @Test fun theBigStoresOpenThroughTheOffloadAndResetsClearIt() {
        assertTrue(OffloadedPrefs8501.OFFLOADED_8501.containsAll(listOf("forward_return_labeler_7731", "canonical_entry_evidence_8093",
            "scanner_hard_rejects", "chart_library_builder_7950", "canonical_finality_6486", "whale_wallet_tracker")))
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("OffloadedPrefs8501.of(context, PREFS_7731)"))
        assertTrue(src("engine/LearningPersistence.kt").contains("OffloadedPrefs8501.of(app, IMMUTABLE_EXECUTION_PREFS_8093)"))
        assertTrue(!src("engine/ScannerHardRejectStore.kt").contains("getSharedPreferences(PREFS_NAME"))
        assertTrue(src("engine/truth/LearningResetSweep7781.kt").contains("OffloadedPrefs8501.clear8501(app, name)"))
        // its own database file: the learning reset (learning_kv.db) never deletes immutable execution evidence
        assertTrue(src("engine/OffloadedPrefs8501.kt").contains("private const val DB_8501 = \"prefs_offload_8501.db\""))
        assertTrue(src("network/JupiterStrictTokenList.kt").contains("JsonArrayStream8501.forEachObject8501(b.charStream(), fn = fn)"))
        assertTrue(src("perps/DynamicAltTokenRegistry.kt").contains("JsonArrayStream8501.forEachObject8501(b.charStream(), fn = fn)"))
    }
}
