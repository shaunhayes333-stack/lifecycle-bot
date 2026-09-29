package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7465CanonicalEntryMarkContinuityTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun one_mode_aware_resolver_owns_entry_mark_selection() {
        val s = src("engine/truth/CanonicalPriceMark6522.kt")
        val fn = s.substringAfter("fun resolveEntryMarkForMode7465(").substringBefore("/** V5.0.6614")
        assertTrue(fn.contains("EXECUTABLE_ENTRY_QUOTE"))
        assertTrue(fn.contains("if (paperMode)"))
        assertTrue(fn.contains("OBSERVATION_SCORING"))
        assertTrue(fn.contains("ENTRY_MARK_MODE_RESOLVER_LIVE_STRICT_MISSING_7465"))
    }

    @Test fun live_never_falls_back_to_observation() {
        val s = src("engine/truth/CanonicalPriceMark6522.kt")
        val fn = s.substringAfter("fun resolveEntryMarkForMode7465(").substringBefore("/** V5.0.6614")
        val paper = fn.indexOf("if (paperMode)")
        val obs = fn.indexOf("OBSERVATION_SCORING")
        assertTrue(paper >= 0)
        assertTrue(obs > paper)
    }

    @Test fun fresh_intent_seals_full_mark_provenance() {
        val s = src("engine/ExecutableOpenGate.kt")
        assertTrue(s.contains("resolveEntryMarkForMode7465(mint, paperMode = paperRuntime)"))
        assertTrue(s.contains("executableMarkSource6613 = entryMark7096?.source"))
        assertTrue(s.contains("executableMarkTimestampMs6613 = entryMark7096?.timestampMs"))
        assertTrue(s.contains("executableMarkPriceUsd6613 = entryMark7096?.priceUsd?.value?.toDouble()"))
    }

    @Test fun paper_and_reseal_paths_share_the_same_authority() {
        val e = src("engine/Executor.kt")
        val g = src("engine/ExecutableOpenGate.kt")
        assertTrue(e.contains("resolveEntryMarkForMode7465(ts.mint, paperMode = true"))
        assertTrue(g.contains(".resolveEntryMarkForMode7465("))
        assertTrue(g.contains("paperMode = intent.mode.equals(\"PAPER\", true)"))
    }

    @Test fun missing_mark_is_still_a_hard_paper_refusal() {
        val e = src("engine/Executor.kt")
        assertTrue(e.contains("EXECUTION_BLOCKED_NO_CANONICAL_MARK_6613"))
        assertTrue(e.contains("markPaperBuyNotOpened(\"NO_EXECUTABLE_MARK_6575\")"))
        assertTrue(e.contains("return"))
    }
}
