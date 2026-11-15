package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8782 — code audit (data P1-8): only a pump.fun mint is seeded with 6 decimals; other relayed mints are chain-read. */
class Aate8782DecimalsSeedTest {
    @Test fun the_seed_needs_a_pump_fun_mint() {
        val bs = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bs.contains("if (isPumpFun6912 && com.lifecyclebot.network.PumpCurveKeys7269.isPumpMint8658(mint) &&"))
        assertTrue(com.lifecyclebot.network.PumpCurveKeys7269.isPumpMint8658("AbCdEfpump"))
        assertTrue(!com.lifecyclebot.network.PumpCurveKeys7269.isPumpMint8658("AbCdEfbonk"))
    }
}
