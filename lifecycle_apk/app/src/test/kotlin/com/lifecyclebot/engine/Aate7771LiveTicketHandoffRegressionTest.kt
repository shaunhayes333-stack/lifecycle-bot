package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7771LiveTicketHandoffRegressionTest {
    private val root = File("src/main/kotlin/com/lifecyclebot")

    @Test fun authorized_ticket_survives_primary_spine_to_live_buy() {
        val bot = File(root, "engine/BotService.kt").readText()
        val executor = File(root, "engine/Executor.kt").readText()
        val primary = bot.substringAfter("🧬 MEME_SPINE EXECUTOR_ROUTE").substringBefore("} else {")
        assertTrue(primary.contains("finalityPrechecked = true"))
        assertTrue(primary.contains("attemptId          = authResult.attemptId"))
        val maybe = executor.substringAfter("fun maybeActWithDecision(").substringBefore("// ── top-up (pyramid add)")
        assertTrue(maybe.contains("finalityPrechecked: Boolean = false"))
        assertTrue(maybe.contains("attemptId: String = \"\""))
        assertTrue(maybe.contains("finalityPrechecked = finalityPrechecked"))
        assertTrue(maybe.contains("attemptId = attemptId"))
        val doBuy = executor.substringAfter("fun doBuy(").substringBefore("private fun runShadowPaperBuy")
        assertTrue(doBuy.contains("finalityPrechecked: Boolean = false"))
        assertTrue(doBuy.contains("attemptId: String = \"\""))
        assertTrue(doBuy.contains("finalityPrechecked = finalityPrechecked"))
        assertTrue(doBuy.contains("attemptId = attemptId"))
    }

    @Test fun dexscreener_body_is_bounded_before_json_materialization() {
        val src = File(root, "network/DexscreenerApi.kt").readText()
        assertTrue(src.contains("MAX_DEXSCREENER_RESPONSE_CHARS_7771"))
        assertTrue(src.contains("DEXSCREENER_RESPONSE_OVERSIZE_REFUSED_7771"))
        assertTrue(src.contains("DEXSCREENER_RESPONSE_STREAM_CAP_7771"))
        assertFalse(src.contains("if (resp.isSuccessful) resp.body?.string() else null"))
    }

    @Test fun helius_sender_is_first_class_runtime_transport_in_diagnostics() {
        val route = File(root, "engine/execution/MemeExecutionRouteStack.kt").readText()
        val preflight = File(root, "engine/truth/LivePreflight7222.kt").readText()
        assertTrue(route.contains("runtimeIntegratedSenders7771"))
        assertTrue(route.contains("\"HeliusSender\", \"Jito\", \"standardRpc\""))
        assertTrue(preflight.contains("checks += check(\"HELIUS_SENDER\")"))
        assertTrue(preflight.contains("Helius Sender -> Jito -> RPC"))
    }
}
