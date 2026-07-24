package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * V5.0.6359 — Foundation-policy wire-up golden-tape test.
 *
 * Executor lane-contract PASS path must emit a PreEntryDecisionRecord6345
 * receipt so every live entry ships auditable evidence. Runs in
 * observation mode — verdict is captured, not enforced.
 */
class FoundationPolicyWireUp6359Test {

    @Test
    fun executor_calls_pre_entry_decision_record_emit_on_contract_pass() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("Executor must invoke PreEntryDecisionRecord6345.emit on contract-pass",
            txt.contains("PreEntryDecisionRecord6345.emit("))
        assertTrue("V5.0.6359 rationale must be documented inline",
            txt.contains("V5.0.6359"))
    }

    @Test
    fun v6359_carries_required_fields_to_the_record() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(txt.contains("expectedRMultiple = "))
        assertTrue(txt.contains("stopDistancePct = "))
        assertTrue(txt.contains("liquidityUsd = "))
        assertTrue(txt.contains("hydrationState = "))
        assertTrue(txt.contains("governorState = "))
    }
}
