package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8001HiveInheritTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun everyBookIsSharedAndBoostInherits() {
        val h = src("engine/truth/HiveEdge8000.kt")
        listOf("\"FRL|\$k\"", "\"SPEC|\$k\"", "\"PB|\$k\"", "\"TAIL|\$k\"", "\"CX|\$k\"", "\"DEV|\$k\"", "\"EXP|\$k\"").forEach { assertTrue(it, h.contains(it)) }
        assertTrue(h.contains("suspend fun boost8001(client: com.lifecyclebot.collective.TursoClient): String"))
        assertTrue(src("ui/CollectiveBrainActivity.kt").contains("HiveEdge8000.boost8001(c)"))
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("fun hiveInherit8001(): Int"))
        assertTrue(src("engine/truth/SpecialistMiner7972.kt").contains("HiveEdge8000.net8000(\"DEV|\$dev\")"))
        assertTrue(src("engine/ExpertWallets7962.kt").contains("fun hiveInherit8001(): Int"))
    }
}
