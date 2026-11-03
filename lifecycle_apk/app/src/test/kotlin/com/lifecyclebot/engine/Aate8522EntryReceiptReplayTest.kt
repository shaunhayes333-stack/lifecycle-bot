package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.EntryReceipt8500
import com.lifecyclebot.engine.truth.RunnerFunnel8297
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Aate8522EntryReceiptReplayTest {
    private val at = 1_000_000L
    private val mint = "receipt8522"
    private val token get() = TokenState(mint)
    private fun receipt(time: Long = at, door: String = "FROZEN_DOOR") =
        EntryReceipt8500.Receipt8500("MOONSHOT", door, "BASE_BREAKOUT", "BASE_START", 5.0, 0.8, 1.0, 10.0, time)

    @Before fun prepare() { EntryReceipt8500.resetForTest8500(trackingFromMs = at) }
    @After fun cleanup() { EntryReceipt8500.resetForTest8500() }

    private fun close(id: String, closeAt: Long = at + 60_000L, holding: Long = 60_000L,
                      lane: String = "MOONSHOT", asset: String = "SOLANA_TOKEN") =
        EntryReceipt8500.onLiveClose8500(mint, "LIVE", true, 5.0, 8.0, id, closeAt, holding, lane, asset)
    private fun saved(): JSONObject = JSONObject(EntryReceipt8500.encode8500())
    private fun sameAggregates(expected: JSONObject, actual: JSONObject) {
        assertEquals(expected.length(), actual.length())
        for (key in expected.keys()) {
            val a = expected.getJSONArray(key); val b = actual.getJSONArray(key)
            assertEquals(a.length(), b.length())
            repeat(a.length()) { assertEquals(a.getDouble(it), b.getDouble(it), 0.0) }
        }
    }
    private fun restart() {
        val before = EntryReceipt8500.encode8500()
        EntryReceipt8500.resetForTest8500()
        EntryReceipt8500.decode8500(before, at + 120_000L)
    }

    @Test fun aggregateOpenRemovalAndTerminalIdentitySurviveTogetherAndReplayCannotLoseOrDoubleGrade() {
        EntryReceipt8500.onLiveBuy8500(token, receipt(), "position-a")
        close("position-a")
        val persisted = saved()
        assertEquals(0, persisted.getJSONObject("open").length())
        assertEquals("GRADED", persisted.getJSONObject("terminals8522").getJSONArray("pid:position-a").getString(0))
        assertEquals(1.0, persisted.getJSONObject("agg").getJSONArray("door|MOONSHOT|FROZEN_DOOR").getDouble(0), 0.0)
        restart()
        repeat(5) { close("position-a") }
        val after = saved()
        sameAggregates(persisted.getJSONObject("agg"), after.getJSONObject("agg"))
        assertEquals(0L, after.getLong("unmatched8510"))
        assertTrue(EntryReceipt8500.line8500().contains("replay8522=5"))
        // A repeated fill callback cannot resurrect a position already graded.
        EntryReceipt8500.onLiveBuy8500(token, receipt(door = "REPLACEMENT"), "position-a")
        assertEquals(0, saved().getJSONObject("open").length())
    }

    @Test fun concurrentReplayedClosesAndSameMintReentryKeepSeparateOriginalReceipts() {
        EntryReceipt8500.onLiveBuy8500(token, receipt(), "position-a")
        EntryReceipt8500.onLiveBuy8500(token, receipt(door = "NOT_THE_FILL"), "position-a")
        EntryReceipt8500.onLiveBuy8500(token, receipt(at + 90_000L, "SECOND_DOOR"), "position-b")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 12).map { pool.submit {
                assertTrue(start.await(5, TimeUnit.SECONDS)); close("position-a")
            } }
            start.countDown()
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        val first = saved()
        assertEquals(1, first.getJSONObject("open").length())
        assertEquals(1.0, first.getJSONObject("agg").getJSONArray("door|MOONSHOT|FROZEN_DOOR").getDouble(0), 0.0)
        assertFalse(first.getJSONObject("agg").has("door|MOONSHOT|NOT_THE_FILL"))
        restart()
        close("position-b", at + 180_000L, 90_000L)
        assertEquals(1.0, saved().getJSONObject("agg").getJSONArray("door|MOONSHOT|SECOND_DOOR").getDouble(0), 0.0)
        assertEquals(0L, saved().getLong("unmatched8510"))
    }

    @Test fun restoreKeepsTheDurableOriginalOverAnEarlyRepeatedFillAndRetainsDistinctNewPositions() {
        EntryReceipt8500.onLiveBuy8500(token, receipt(), "original")
        val persisted = saved()
        EntryReceipt8500.resetForTest8500(trackingFromMs = at)
        // Deterministically stage the callbacks before the stored book is decoded.
        EntryReceipt8500.onLiveBuy8500(token, receipt(at + 30_000L, "EARLY_REPLAY"), "original")
        EntryReceipt8500.onLiveBuy8500(token, receipt(at + 90_000L, "NEW_POSITION"), "new-position")
        EntryReceipt8500.decode8500(persisted.toString(), at + 120_000L)
        val restored = saved()
        assertEquals(2, restored.getJSONObject("open").length())
        assertEquals(persisted.getJSONObject("open").getJSONArray("pid:original").toString(),
            restored.getJSONObject("open").getJSONArray("pid:original").toString())
        assertEquals("NEW_POSITION", restored.getJSONObject("open").getJSONArray("pid:new-position").getString(1))
        close("original")
        close("new-position", at + 180_000L, 90_000L)
        val graded = saved()
        assertEquals(0, graded.getJSONObject("open").length())
        assertEquals(1.0, graded.getJSONObject("agg").getJSONArray("door|MOONSHOT|FROZEN_DOOR").getDouble(0), 0.0)
        assertEquals(1.0, graded.getJSONObject("agg").getJSONArray("door|MOONSHOT|NEW_POSITION").getDouble(0), 0.0)
        assertFalse(graded.getJSONObject("agg").has("door|MOONSHOT|EARLY_REPLAY"))
        restart()
        repeat(3) { close("original"); close("new-position", at + 180_000L, 90_000L) }
        sameAggregates(graded.getJSONObject("agg"), saved().getJSONObject("agg"))
        assertEquals(0L, saved().getLong("unmatched8510"))
    }

    @Test fun restoredGradedIdentityRemovesItsEarlyReplayedOpenWithoutDroppingANewPositionOrDoubleGrading() {
        EntryReceipt8500.onLiveBuy8500(token, receipt(), "already-graded")
        close("already-graded")
        val persisted = saved()
        EntryReceipt8500.resetForTest8500(trackingFromMs = at)
        EntryReceipt8500.onLiveBuy8500(token, receipt(at + 30_000L, "EARLY_REPLAY"), "already-graded")
        EntryReceipt8500.onLiveBuy8500(token, receipt(at + 90_000L, "NEW_POSITION"), "new-position")
        assertEquals(2, saved().getJSONObject("open").length())
        EntryReceipt8500.decode8500(persisted.toString(), at + 120_000L)
        val restored = saved()
        assertEquals(1, restored.getJSONObject("open").length())
        assertFalse(restored.getJSONObject("open").has("pid:already-graded"))
        assertTrue(restored.getJSONObject("open").has("pid:new-position"))
        sameAggregates(persisted.getJSONObject("agg"), restored.getJSONObject("agg"))
        repeat(3) { close("already-graded") }
        EntryReceipt8500.onLiveBuy8500(token, receipt(door = "ANOTHER_REPLAY"), "already-graded")
        assertEquals(1, saved().getJSONObject("open").length())
        sameAggregates(persisted.getJSONObject("agg"), saved().getJSONObject("agg"))
        close("new-position", at + 180_000L, 90_000L)
        val graded = saved()
        assertEquals(0, graded.getJSONObject("open").length())
        assertEquals(1.0, graded.getJSONObject("agg").getJSONArray("door|MOONSHOT|FROZEN_DOOR").getDouble(0), 0.0)
        assertEquals(1.0, graded.getJSONObject("agg").getJSONArray("door|MOONSHOT|NEW_POSITION").getDouble(0), 0.0)
        assertFalse(graded.getJSONObject("agg").has("door|MOONSHOT|EARLY_REPLAY"))
        restart()
        repeat(3) { close("already-graded"); close("new-position", at + 180_000L, 90_000L) }
        sameAggregates(graded.getJSONObject("agg"), saved().getJSONObject("agg"))
        assertEquals(0L, saved().getLong("unmatched8510"))
    }

    @Test fun legacyAggregatesStayUnchangedAndTheirMissingIdentityIsExplicitlyUnknown() {
        EntryReceipt8500.onLiveBuy8500(token, receipt(), "still-open")
        EntryReceipt8500.fold8500("door|QUALITY|OLD_DOOR", RunnerFunnel8297.Outcome8297.WIN, 12.0)
        val legacy = saved().apply {
            remove("terminals8522"); remove("legacyThrough8522"); remove("trackingFrom8522")
        }
        val oldAggregates = legacy.getJSONObject("agg").toString()
        EntryReceipt8500.resetForTest8500()
        EntryReceipt8500.decode8500(legacy.toString(), at + 120_000L)
        repeat(3) { close("old-no-identity", at + 60_000L) }
        assertEquals(oldAggregates, saved().getJSONObject("agg").toString())
        assertEquals(1L, saved().getLong("legacyUnknown8522"))
        assertEquals(0L, saved().getLong("unmatched8510"))
        assertEquals(1, saved().getJSONObject("open").length())
        restart()
        close("old-no-identity", at + 60_000L)
        assertEquals(1L, saved().getLong("legacyUnknown8522"))
        close("still-open", at + 180_000L, 180_000L)
        assertEquals(1.0, saved().getJSONObject("agg").getJSONArray("door|QUALITY|OLD_DOOR").getDouble(0), 0.0)
        assertEquals(1.0, saved().getJSONObject("agg").getJSONArray("door|MOONSHOT|FROZEN_DOOR").getDouble(0), 0.0)
        close("new-missing", at + 240_000L)
        assertEquals("a genuinely new missing receipt stays visible", 1L, saved().getLong("unmatched8510"))
    }

    @Test fun canonicalNonMemeAssetsAreOutsideScopeButUnknownLaneAndNewMissingMemeRemainUnmatched() {
        close("crypto", lane = "CRYPTO_TREASURY", asset = "CRYPTO_ALT")
        close("market", lane = "STOCKS", asset = "UNKNOWN")
        close("unknown", lane = "UNRESOLVED", asset = "UNKNOWN")
        close("missing-meme")
        val state = saved()
        assertEquals(2L, state.getLong("outside8522"))
        assertEquals(2L, state.getLong("unmatched8510"))
        assertEquals(0, state.getJSONObject("agg").length())
        restart()
        close("crypto", lane = "CRYPTO_TREASURY", asset = "CRYPTO_ALT")
        close("missing-meme")
        assertEquals(2L, saved().getLong("outside8522"))
        assertEquals(2L, saved().getLong("unmatched8510"))
    }

    @Test fun aLateActualReceiptCanResolveAnEarlierUnmatchedCloseWithoutErasingTheDiagnostic() {
        close("late-receipt")
        assertEquals(1L, saved().getLong("unmatched8510"))
        restart()
        EntryReceipt8500.onLiveBuy8500(token, receipt(), "late-receipt")
        close("late-receipt")
        repeat(3) { close("late-receipt") }
        val state = saved()
        assertEquals(1L, state.getLong("unmatched8510"))
        assertEquals(1L, state.getLong("resolved8522"))
        assertEquals(1.0, state.getJSONObject("agg").getJSONArray("door|MOONSHOT|FROZEN_DOOR").getDouble(0), 0.0)
        assertEquals("GRADED", state.getJSONObject("terminals8522").getJSONArray("pid:late-receipt").getString(0))
    }

    @Test fun historicalPaperAndNonTerminalEventsDoNotBecomeCurrentMissingReceipts() {
        close("before-tracking", at - 30_000L)
        EntryReceipt8500.onLiveClose8500(mint, "PAPER", true, 5.0, 8.0, "paper", at + 60_000L, 60_000L)
        EntryReceipt8500.onLiveClose8500(mint, "LIVE", false, 5.0, 8.0, "partial", at + 60_000L, 60_000L)
        assertEquals(1L, saved().getLong("historical8510"))
        assertEquals(0L, saved().getLong("unmatched8510"))
        assertEquals(1, saved().getJSONObject("terminals8522").length())
        restart()
        close("before-tracking", at - 30_000L)
        assertEquals(1L, saved().getLong("historical8510"))
    }
}
