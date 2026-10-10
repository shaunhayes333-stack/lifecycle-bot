package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7992PaperLeakTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun phantomRowsAreNotLiveEquity() {
        assertTrue(src("engine/truth/LiveRiskPolicy7807.kt").contains(".filter { walletHolds7992(it.mint, it.entryPriceUsd) }"))
        assertTrue(src("engine/KillSwitch.kt").contains("storedSchema7843 < 7992"))
    }

    @Test fun liveExitsLearnFromLiveCloses() {
        assertTrue(src("engine/ExitProfile7955.kt").contains("if (!env.mode.equals(\"live\", true)) return"))
    }
}
