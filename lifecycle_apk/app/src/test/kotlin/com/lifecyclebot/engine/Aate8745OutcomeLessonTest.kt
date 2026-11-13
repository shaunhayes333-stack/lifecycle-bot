package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OutcomeLesson8745
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8745 — a close the bot's machinery forced never teaches the strategy brains about the entry. */
class Aate8745OutcomeLessonTest {
    @Test fun mechanicalClosesAreNotEntryLessons() {
        for (r in listOf("RAPID_SUB_TRADER_HARD_FLOOR", "bot_shutdown", "data_quality_stale_feed_evict_feedAge53m",
                "PROTECTIVE_EXIT_STOP_LOSS_6450_RISKCLOCK", "DEAD_MONEY_CULL_7388", "OPPORTUNITY_ROTATION_8098", "MANUAL"))
            assertTrue(r, OutcomeLesson8745.mechanical8745(r))
        for (r in listOf("RAPID_TRAILING_STOP", "RUNNER_DOCTRINE_TRAIL_8630", "DEEP_CATASTROPHE_NET", "LANE_HARD_15PCT_SL_EXPRESS",
                "SCALP_PROFIT_LOCK_8351", "", null))
            assertFalse("$r", OutcomeLesson8745.mechanical8745(r))
    }

    @Test fun theBusWithholdsThemFromEverySubscriber() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/CanonicalLearning.kt").readText()
        assertTrue(Regex("OutcomeLesson8745\\.teaches8745\\(normalized\\.closeReason\\)\\)\\) return").findAll(src).count() == 2)
        val pub = src.substringAfter("fun publish(raw: CanonicalTradeOutcome)").substringBefore("fun publishUnchecked(")
        assertTrue(pub.indexOf("teaches8745") < pub.indexOf("for (s in subscribers)"))
        assertTrue(pub.indexOf("bumpCounters(normalized)") < pub.indexOf("teaches8745"))
    }
}
