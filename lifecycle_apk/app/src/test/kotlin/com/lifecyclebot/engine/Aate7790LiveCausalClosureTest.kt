package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7790LiveCausalClosureTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun liveFailuresTerminalizeCanonicalIntent() {
        val s=src("engine/Executor.kt")
        assertTrue(s.contains("terminalizeCanonicalLiveFailure7790(ts, reason)"))
        assertTrue(s.contains("CanonicalEntryAuthority6551.markFailed(intent, reason)"))
    }

    @Test fun liveEntrySnapshotStampsCausalMark() {
        val s=src("engine/Executor.kt")
        assertTrue(s.contains("stampLiveEntryMark7790(ts, true"))
        assertTrue(s.contains("ToolkitSignalSheet.recordDeskStage(intent.canonicalLane, if (ready) \"MARK_READY\" else \"MARK_REJECT\", intent.attemptId)"))
    }

    @Test fun providerUncertaintyDoesNotOverrideExecutablePlan() {
        val s=src("engine/CommonSenseTradePlaybook.kt")
        assertTrue(s.contains("PLAN_BACKED_PROVIDER_UNCERTAINTY_7790"))
        assertTrue(s.contains("snap.hardSafetyBlocked || snap.holderHardRisk"))
    }

    @Test fun executableSizeRequiresImmutableIntent() {
        val s=src("engine/ToolkitSignalSheet.kt")
        assertTrue(s.contains("stage == \"SIZED_EXECUTABLE\" && sealedIntent7471 == null"))
        assertTrue(s.contains("ADVISORY_SIZE_STAMP_WITHHELD_7790"))
    }
}
