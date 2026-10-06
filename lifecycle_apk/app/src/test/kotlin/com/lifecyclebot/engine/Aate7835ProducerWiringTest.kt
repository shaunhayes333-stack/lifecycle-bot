package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Covers the real call sites which the former primary-only seal test missed. */
class Aate7835ProducerWiringTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun every_production_specialist_authorization_carries_its_FDG_object() {
        var calls = 0
        for (file in listOf("engine/BotService.kt", "engine/CyclicTradeEngine.kt")) {
            val code = src(file).lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
            Regex("TradeAuthorizer\\.authorize\\(").findAll(code).forEach { call ->
                var i = call.range.last + 1
                var depth = 1
                while (depth > 0) {
                    when (code[i++]) { '(' -> depth++; ')' -> depth-- }
                }
                val args = code.substring(call.range.first, i)
                assertTrue("$file call $calls missing decision", args.contains("fdgDecision7835 ="))
                assertTrue("$file call $calls missing token", args.contains("tokenState7835 ="))
                calls++
            }
        }
        assertEquals(12, calls)
        assertFalse(src("engine/BotService.kt").contains("ExecutableOpenGate.recordFdg("))
    }

    @Test fun V3_evaluates_FDG_before_authorization_and_consumes_its_returned_intent() {
        val code = src("engine/BotService.kt")
        val block = code.substringAfter("val v3Fdg6533 = FinalDecisionGate.evaluate(").substringBefore("val v3AttemptId =")
        assertTrue(block.contains("TradeAuthorizer.authorize("))
        assertTrue(block.contains("fdgDecision7835 = v3Fdg6533"))
        assertTrue(block.contains("val v3Intent6533 = authResult.executionIntent7835"))
        assertFalse(block.contains("recordFdgAndGetIntent6533("))
    }

    @Test fun computation_budgets_cannot_create_or_replace_a_trade_verdict() {
        // A historical comment is not an executable budget or verdict.
        fun executable(path: String) = src(path).lineSequence()
            .filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        val fdg = executable("engine/FinalDecisionGate.kt")
        assertFalse(fdg.contains("allowFdgEval("))
        assertFalse(fdg.contains("priorVerdictForCandidate7809("))
        assertFalse(fdg.contains("FDG_FANOUT_CAP_7232"))
        assertFalse(fdg.contains("fdgStage6657"))
        assertFalse(executable("engine/AgenticStyleRouter.kt").contains("allowLaneEval("))
        assertFalse(executable("engine/BotService.kt").contains("allowLaneEval("))
    }

    @Test fun crypto_has_one_canonical_sizer_and_keeps_explicit_route_and_refusal_evidence() {
        val code = src("perps/CryptoAltTrader.kt")
        val entry = code.substringAfter("private suspend fun executeSignal(").substringBefore("val canonicalFinalSize6570 =")
        assertTrue(entry.contains("CanonicalEntryAuthority6551.submit("))
        assertFalse(entry.contains("CanonicalSizingBridge6532.resolve("))
        assertFalse(entry.contains("CanonicalEntryAuthority6540.markAuthSubmit("))
        assertTrue(entry.contains("candidate.executionAdapter == \"CRYPTO_UNIVERSE_EXECUTOR\""))
        assertTrue(entry.contains("livePreflight7835.second"))
        assertTrue(entry.contains("remainingExposure7835"))
        assertFalse(code.contains("CRYPTO_LIVE_SIZE_LIFTED_TO_DOCTRINE_7708"))
    }

    @Test fun field_manual_decline_stays_a_cost_protection_and_is_named_unambiguously() {
        val manual = src("engine/truth/FieldManual7715.kt")
        assertTrue(manual.contains("decision == Decision.WAIT || decision == Decision.PASS"))
        assertTrue(manual.contains("Decision.PASS -> \"FIELD_MANUAL_DECLINE_7835:"))
    }
}
