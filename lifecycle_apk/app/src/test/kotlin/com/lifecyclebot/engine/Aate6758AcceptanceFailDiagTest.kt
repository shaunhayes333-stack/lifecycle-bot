package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6758 §PHANTOM_DELTA_DIAG — regression fence for the acceptance-fail
 * diagnostic breakdown. On any 120-second acceptance FAIL that surfaces
 * PHANTOM_SIZED_ONLY / CASH_DELTA / BASIS_DELTA / REALIZED_DELTA /
 * QUANTITY_DELTA (the P1 mark/sizing/economics discontinuities the
 * operator has been chasing), a per-lane phantom breakdown and a
 * forensic reconciliation snapshot must be emitted alongside the
 * existing FAIL witness so the next runtime capture immediately names
 * the offending lane without requiring a full logcat grep.
 *
 * Behaviour is 100% additive telemetry; no trading thresholds change.
 */
class Aate6758AcceptanceFailDiagTest {
    @Test
    fun acceptance_fail_emits_per_lane_phantom_breakdown_and_forensic_snapshot() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutionSpineAcceptance6647.kt").readText()
        assertTrue(
            "diagnostic emission block must be present after emitFailure6689",
            src.contains("EXECUTION_SPINE_ACCEPTANCE_6647_FAIL_DIAG_6758"),
        )
        assertTrue(
            "diag must include a per-lane phantom breakdown",
            src.contains("laneSnapshot6647(desk)") && src.contains("phantomBreakdown="),
        )
        assertTrue(
            "diag must include forensic reconciled cash/basis/realized/qty snapshot",
            src.contains("reconciled=\${it.reconciled} cash=\${it.cashSol} basis=\${it.basisSol} realized=\${it.realizedSol} qty=\${it.quantityRaw}"),
        )
        assertTrue(
            "diag must include openPositions and exit sweep sample so accounting deltas can be triaged inline",
            src.contains("openPositions=\${canonicalOpenPositions.size}") &&
                src.contains("exitStart=\${observation.exitStart}") &&
                src.contains("exitDone=\${observation.exitDone}"),
        )
        assertTrue(
            "counter must be incremented so the pipeline funnel sheet can count occurrences",
            src.contains("PipelineHealthCollector.labelInc(\"EXECUTION_SPINE_ACCEPTANCE_6647_FAIL_DIAG_6758\")"),
        )
    }
}
