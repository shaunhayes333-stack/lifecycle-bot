package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7447TraderBootstrapIsolationTest {
    private val src = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    @Test fun trader_starts_are_individually_bounded_after_model_ready() {
        val a = src.indexOf("bootstrapPhase6516(\"MODEL_AND_LAYER_STATE_READY\")")
        val b = src.indexOf("bootstrapPhase6516(\"TRADER_ENGINES_STARTED\")")
        assertTrue(a > 0 && b > a)
        val region = src.substring(a, b)
        assertTrue(region.contains("traderStep7447"))
        assertTrue(region.contains("withTimeout(timeoutMs)"))
        assertTrue(region.contains("runInterruptible(kotlinx.coroutines.Dispatchers.IO)"))
        listOf("PERPS", "STOCK", "COMMODITY", "METAL", "FOREX", "CRYPTO_ALT_INIT", "CRYPTO_ALT_START")
            .forEach { assertTrue("missing bounded startup for $it", region.contains("traderStep7447(\"$it\"")) }
    }

    @Test fun timeout_or_failure_does_not_abort_remaining_trader_bootstrap() {
        val region = src.substringAfter("val traderStep7447").substringBefore("canonicalBootstrapJob6515?.join()")
        assertTrue(region.contains("TRADER_BOOTSTRAP_TIMEOUT_7447"))
        assertTrue(region.contains("TRADER_BOOTSTRAP_FAILED_7447"))
        assertTrue(region.contains("action=continue_other_traders"))
        assertTrue(region.contains("catch (c: kotlinx.coroutines.CancellationException)"))
    }

    @Test fun service_still_marks_trader_engines_started_only_after_attempts() {
        val marker = src.indexOf("bootstrapPhase6516(\"TRADER_ENGINES_STARTED\")")
        assertTrue(src.indexOf("traderStep7447(\"PERPS\"") < marker)
        assertTrue(src.indexOf("traderStep7447(\"CRYPTO_ALT_START\"") < marker)
    }
}
