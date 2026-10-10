package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class Aate7993NoHourlyCapTest {
    @Test fun liveEntriesHaveNoHourlyCap() {
        val ks = File("src/main/kotlin/com/lifecyclebot/engine/KillSwitch.kt").readText()
        assertFalse(ks.contains("maxTradesPerHour = config7835.maxTradesPerHour"))
        assertEquals(2, Regex("maxTradesPerHour = NO_HOURLY_CAP_7993\\)").findAll(ks).count())
    }
}
