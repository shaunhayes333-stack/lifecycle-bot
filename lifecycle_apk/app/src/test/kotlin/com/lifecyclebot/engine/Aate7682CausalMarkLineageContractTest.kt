package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7682CausalMarkLineageContractTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun predecessorRecoveryIsProofBoundedAndNeverInventsExecutableStages() {
        val s = src("engine/truth/MemeExecutionFunnelReceivers6625.kt")
        val fn = s.substringAfter("fun ensureAffinityLineage7464(").substringBefore("fun stamp6625(")
        assertTrue(fn.contains("GlobalTradeRegistry.getLaneAffinity(key.mint)"))
        assertTrue(fn.contains("rec.stages[Stage.DISCOVER]"))
        assertTrue(fn.contains("rec.stages[Stage.QUALIFY]"))
        assertFalse(fn.contains("rec.stages[Stage.INTENT]"))
        assertFalse(fn.contains("rec.stages[Stage.FDG]"))
        assertFalse(fn.contains("rec.stages[Stage.MARK]"))
        assertFalse(fn.contains("rec.stages[Stage.SIZE]"))
    }

    @Test fun canonicalMarkProvenanceIsSealedIntoIntentAndMissingMarkRefuses() {
        val g = src("engine/ExecutableOpenGate.kt")
        assertTrue(g.contains("resolveEntryMarkForMode7465(mint, paperMode = paperRuntime)"))
        assertTrue(g.contains("executableMarkSource6613 = entryMark7096?.source"))
        assertTrue(g.contains("executableMarkTimestampMs6613 = entryMark7096?.timestampMs"))
        assertTrue(g.contains("executableMarkPriceUsd6613 = entryMark7096?.priceUsd?.value?.toDouble()"))
        val e = src("engine/Executor.kt")
        assertTrue(e.contains("EXECUTION_BLOCKED_NO_CANONICAL_MARK_6613"))
        assertTrue(e.contains("markPaperBuyNotOpened(\"NO_EXECUTABLE_MARK_6575\")"))
    }

    @Test fun executableSizeTelemetryRequiresExactImmutableIntent() {
        val s = src("engine/truth/CanonicalSizingBridge6532.kt")
        assertTrue(s.contains("activeExecutionIntent6519(mode6674, canonicalAssetId, resolvedCandidateVersion6620)"))
        assertTrue(s.contains("val resolvedCausalEventId6674 = intentOwnedCausalId6893"))
        assertTrue(s.contains("ADVISORY_SIZE_STAMP_WITHHELD_6893"))
    }

    @Test fun topLevelSourceRowsAreClosedWhileRuntimeChecksRemain() {
        val a = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertFalse(a.contains("- [ ] Preserve the same immutable candidate/attempt identity"))
        assertFalse(a.contains("- [ ] Trace exact source-valid -> canonical mark"))
        assertFalse(a.contains("- [ ] No candidate may reach executable sizing"))
        assertTrue(a.contains("- [ ] Raw stage counts and validated counts must converge"))
        assertTrue(a.contains("Runtime acceptance: valid-source missing mark count materially below 164"))
    }
}
