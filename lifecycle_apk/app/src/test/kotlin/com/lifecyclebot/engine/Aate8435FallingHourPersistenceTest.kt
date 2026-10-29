package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.FallingHourCohort8418
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8435 — 8421 -> 8434 the falling-hour record went backwards (123 -> 90 outcomes) and every capture waiting for its
 * 60-minute label died with the process. The record now restores its waiting captures, merges rather than replaces, saves
 * every graded outcome with an acknowledged write, and never writes before the real record was read. A falling-hour wait
 * is a known condition of the coin: it defers without asking fetches that cannot answer it.
 */
class Aate8435FallingHourPersistenceTest {
    @Before fun setUp() { FallingHourCohort8418.resetForTests8418() }
    @After fun tearDown() { FallingHourCohort8418.resetForTests8418() }

    @Test fun theSavedFormCarriesTheWaitingCapturesInsideTheirWindowOnly() {
        val now = 1_800_000_000_000L
        val s = FallingHourCohort8418.Stat8418().also { repeat(3) { _ -> it.add(-4.0, 1.0) } }
        val json = FallingHourCohort8418.encode8435(mapOf("MOONSHOT" to s),
            mapOf("MintA|MOONSHOT" to now - 60_000L, "MintOld|MOONSHOT" to now - 3L * 3_600_000L), now)
        val o = org.json.JSONObject(json)
        assertEquals("3,-12.0,48.0,0", o.getString("MOONSHOT"))
        val p = o.getJSONObject("__pending_8435")
        assertTrue(p.has("MintA|MOONSHOT"))
        assertFalse("a capture past its label window is not carried", p.has("MintOld|MOONSHOT"))
    }

    @Test fun aRestoreMergesUnderWhatThisProcessAlreadyGradedAndBringsBackItsCaptures() {
        val now = 1_800_000_000_000L
        val mem = FallingHourCohort8418.Stat8418().also { it.add(-2.0, 1.0) }
        FallingHourCohort8418.seedForTest8418("SHITCOIN", mem)
        val disk = FallingHourCohort8418.Stat8418().also { repeat(33) { _ -> it.add(-4.0, 1.0) } }
        val json = FallingHourCohort8418.encode8435(mapOf("SHITCOIN" to disk), mapOf("MintB|SHITCOIN" to now - 120_000L), now)
        FallingHourCohort8418.restoreForTest8435(json, now)
        val e = FallingHourCohort8418.evidence8418("SHITCOIN")!!
        assertEquals("33 restored + 1 graded here: the record only grows", 34, e.n)
        assertEquals(1, FallingHourCohort8418.pendingCountForTest8435())
        // the restored capture is found when its label lands in this process
        FallingHourCohort8418.onLabel60("MintB", "SHITCOIN", -3.0, 0.5)
        assertEquals(35, FallingHourCohort8418.evidence8418("SHITCOIN")!!.n)
        assertEquals(0, FallingHourCohort8418.pendingCountForTest8435())
        assertTrue(FallingHourCohort8418.statusLine8418().contains("persist8435[loaded=true"))
        assertTrue(FallingHourCohort8418.statusLine8418().contains("restoredPending=1"))
    }

    @Test fun theLoadNeverLatchesBeforeTheStoreOpensAndEverySaveIsAcknowledged() {
        val f = File("src/main/kotlin/com/lifecyclebot/engine/truth/FallingHourCohort8418.kt").readText()
        assertTrue(f.contains("if (!(try { LearningPersistence.ready() } catch (_: Throwable) { false })) return"))
        assertTrue(f.contains("if (!loaded) { saveFailed8435.incrementAndGet(); return }   // never write before the real record was read"))
        assertTrue(f.contains("LearningPersistence.saveTogetherVerified8386(mapOf(PERSIST_KEY_8418 to json))"))
        assertFalse("no unacknowledged put", f.contains("LearningPersistence.save(PERSIST_KEY_8418"))
        val t = File("src/main/kotlin/com/lifecyclebot/engine/truth/TradeShapeLearner7883.kt").readText()
        assertTrue(t.indexOf("LearningPersistence.ready()") in 1 until t.indexOf("val o = org.json.JSONObject(LearningPersistence.load(PERSIST_KEY) ?: return)"))
    }

    @Test fun aFallingHourWaitAsksNoFetch() {
        assertTrue(CandidateAdmission8136.conditionWait8435(CandidateAdmission8136.FALLING_HOUR_DEFER_8418))
        assertFalse(CandidateAdmission8136.conditionWait8435("DATA_INCOMPLETE_DEFER_8136"))
        assertFalse(CandidateAdmission8136.conditionWait8435(null))
        val le = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()
            .substringAfter("CandidateAdmission8136.Terminal8136.DEFER_DATA -> {").substringBefore("CandidateAdmission8136.Terminal8136.REFUSE -> {")
        val wait = le.indexOf("if (CandidateAdmission8136.conditionWait8435(d.reason)) {")
        assertTrue(wait in 1 until le.indexOf("DataDeferFetch8180.request8180(ts, missing, nowMs)"))
        assertTrue(le.contains("FALLING_HOUR_WAIT_NO_FETCH_8435_\$l8059"))
        assertTrue(le.contains("val reason8418 = if (conditionWait8435) CandidateAdmission8136.FALLING_HOUR_DEFER_8418"))
    }
}
