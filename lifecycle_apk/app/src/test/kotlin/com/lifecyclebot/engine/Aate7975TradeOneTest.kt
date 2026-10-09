package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LearningResetSweep7781
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7975TradeOneTest {

    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/engine/$rel").readText()

    @Test fun resetDeletesTheLearningDatabaseAndMinerAtBoot() {
        assertTrue(LearningResetSweep7781.LEARNING_DBS_7975.contains("learning_kv.db"))
        assertTrue(LearningResetSweep7781.LEARNING_FILES_7975.contains("specialists7972.bin"))
        assertTrue(LearningResetSweep7781.LEARNING_PREFS_7781.contains("cell_allocator_7962"))
        assertTrue(src("truth/LearningResetSweep7781.kt").contains("app.deleteDatabase(db)"))
    }

    @Test fun runnerLanesExploreUntilMeasured() {
        val pb = src("cortex/LanePlaybook7907.kt")
        assertFalse(pb.contains("(runner || noTriggerMeasured7936(st))"))
        assertTrue(pb.contains("setup == NO_TRIGGER && noTriggerMeasured7936(st) && !noTriggerProvenPositive(st)"))
    }

    @Test fun positiveEvidenceAdmitsArePlannedNotMadeToWait() {
        assertFalse(LiveEdgeGate7877.positiveAdmit7975("NEVER_SEEN_MINT_00000000000000000000"))
        assertTrue(src("truth/TradePlan7739.kt").contains("LiveEdgeGate7877.positiveAdmit7975(ts.mint, nowMs)"))
    }
}
