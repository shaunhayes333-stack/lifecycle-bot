package com.lifecyclebot.perps

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7472CryptoFreshEvidenceWarmupTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun fresh_candidates_are_not_terminal_before_tactic_tape_is_mature() {
        val s = src("perps/CryptoAltTrader.kt")
        val block = s.substringAfter("V5.0.7472 — do not terminally retire a FRESH candidate")
            .substringBefore("} catch (e: CancellationException)")
        assertTrue(block.contains("CryptoLaneDesk7391.prices(deskIdentity7391).size"))
        assertTrue(block.contains("refreshed.isFresh6544 && freshTapeSamples7472 < 4"))
        assertTrue(block.contains("releaseEvaluationForRetry7418("))
        assertTrue(block.contains("CRYPTO_FRESH_TAPE_WARMUP_7472"))
        assertTrue(block.indexOf("releaseEvaluationForRetry7418(") < block.indexOf("markEvaluationDisposition6567("))
    }

    @Test fun mature_no_action_still_terminalises_without_floor_relaxation() {
        val s = src("perps/CryptoAltTrader.kt")
        val block = s.substringAfter("V5.0.7472 — do not terminally retire a FRESH candidate")
            .substringBefore("} catch (e: CancellationException)")
        assertTrue(block.contains("CRYPTO_BRAIN_NO_ACTIONABLE_SIGNAL_7244"))
        assertFalse(block.contains("scoreFloor -"))
        assertFalse(block.contains("confFloor -"))
        assertFalse(block.contains("actionableLong = true"))
    }

    @Test fun warmup_is_explicitly_retryable_registry_state() {
        val r = src("perps/DynamicAltTokenRegistry.kt")
        val fn = r.substringAfter("private fun isRetryableProgress7418").substringBefore("fun markEvaluationStarted6567")
        assertTrue(fn.contains("CRYPTO_FRESH_TAPE_WARMUP_7472"))
    }
}
