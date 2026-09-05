package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.6680 — terminal PAPER reject telemetry has one source owner. */
class Aate6680PaperBuyTelemetrySingleOwnerTest {

    private val executor = File(
        "src/main/kotlin/com/lifecyclebot/engine/Executor.kt"
    ).readText()

    @Test
    fun `aggregate PAPER buy not opened counter has one production increment owner`() {
        assertEquals(
            1,
            Regex("PipelineHealthCollector\\.labelInc\\(\\\"PAPER_BUY_NOT_OPENED\\\"\\)")
                .findAll(executor)
                .count(),
        )
    }

    @Test
    fun `presale rejection delegates telemetry to markPaperBuyNotOpened`() {
        val presale = executor.substringAfter("PAPER_BUY_BLOCKED_PRESALE_SNIPE_6373F")
            .substringBefore("if (sol <= 0")
        assertTrue(presale.contains("markPaperBuyNotOpened(\"PRESALE_SNIPE_51K_RUG_6373F\")"))
        assertFalse(
            presale.contains(
                "PipelineHealthCollector.labelInc(\"PAPER_BUY_NOT_OPENED_PRESALE_SNIPE_51K_RUG_6373F\")"
            )
        )
    }
}
