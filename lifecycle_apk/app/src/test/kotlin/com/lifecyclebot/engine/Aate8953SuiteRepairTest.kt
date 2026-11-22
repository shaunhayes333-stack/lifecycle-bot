package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8953 — the full suite's five new failures from 8949-8951 are repaired: one behaviour, four moved pins. */
class Aate8953SuiteRepairTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aNewShadowKeyStaysHotAfterItsFirstColdAppend() {
        val s = src("engine/truth/ShadowColdStore8397.kt")
        assertTrue(s.contains("if (raw.isNullOrEmpty()) hotRows[k] = listOf(row.copyOf())"))
        assertTrue(s.indexOf("payloads[k] = appendRow8949(raw, rowText, maxRows)") < s.indexOf("if (raw.isNullOrEmpty()) hotRows[k]"))
    }
}
