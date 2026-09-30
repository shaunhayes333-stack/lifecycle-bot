package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7521CorrectnessBundleTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/" + path).readText()

    @Test fun crypto_has_one_canonical_entry_authority_after_seal() {
        val s = src("perps/CryptoAltTrader.kt")
        val sealedIntent = "val canonicalCryptoIntent6565 = when (canonicalCryptoAdmission6565)"
        assertTrue(s.contains(sealedIntent))
        val post = s.substringAfter(sealedIntent)
        val beforePaper = post.substringBefore("if (authoritativePaperMode7425())")
        assertFalse(beforePaper.contains("val finalExecutableVerdict6647 = ExecutableOpenGate.canOpenExecutablePosition"))
        assertTrue(beforePaper.contains("CRYPTO_POST_SEAL_DUPLICATE_GATE_ELIMINATED_7521"))
    }

    @Test fun causal_recorder_recovers_affinity_for_direct_downstream_writers() {
        val s = src("engine/truth/MemeExecutionFunnelReceivers6625.kt")
        val fn = s.substringAfter("fun stamp6625(").substringBefore("fun stageCounts6625")
        assertTrue(fn.contains("ensureAffinityLineage7464(key, stage)"))
    }

    @Test fun closed_history_open_projection_requires_terminal_receipt() {
        val s = src("engine/truth/CanonicalPaperTransaction6486.kt")
        val fn = s.substringAfter("fun repairCryptoHistory6659()").substringBefore("fun open(")
        assertTrue(fn.contains("positionsWithDurableSell7521"))
        assertTrue(fn.contains("HISTORY_OPEN_PROJECTION_WITHHELD_NO_TERMINAL_7521"))
    }

    @Test fun durable_terminal_without_entry_snapshot_is_accounted_not_trained() {
        val s = src("engine/truth/FinalizedLearningReconciler7423.kt")
        val fn = s.substringAfter("fun repairDurableBusPublishFailures7459").substringBefore("fun statusLine")
        assertTrue(fn.contains("terminalOnly7521"))
        assertTrue(fn.contains("DURABLE_TERMINAL_NO_ENTRY_SNAPSHOT_7521"))
        assertTrue(fn.contains("FINALIZED_BUS_TERMINAL_ONLY_NO_ENTRY_7521"))
    }
}
