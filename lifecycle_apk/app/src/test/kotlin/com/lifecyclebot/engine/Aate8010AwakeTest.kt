package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8010AwakeTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun cpuSuspendIsTheGapBetweenTheTwoClocks() {
        assertEquals(59_000L, Awake8010.suspendedBetween8010(60_000L, 1_000L))
        assertEquals(0L, Awake8010.suspendedBetween8010(1_000L, 1_000L))
        assertEquals(0L, Awake8010.suspendedBetween8010(1_000L, 2_000L))
        Awake8010.noteTick(1_000L, 1_000L)
        Awake8010.noteTick(70_000L, 11_000L)
        assertTrue(Awake8010.statusLine(), Awake8010.statusLine().contains("cpuSuspends=1"))
        assertTrue(Awake8010.statusLine().contains("max=59s"))
    }

    @Test fun eachPhoneMakerGetsItsOwnFix() {
        assertTrue(Awake8010.oemHint8010("Xiaomi").contains("Autostart"))
        assertTrue(Awake8010.oemHint8010("samsung").contains("Never sleeping apps"))
        assertTrue(Awake8010.oemHint8010("OnePlus").contains("Auto-launch"))
        assertTrue(Awake8010.oemHint8010("Google").contains("Unrestricted"))
    }

    @Test fun wiredIntoTheLoopThePromptAndTheDiag() {
        val b = src("engine/BotService.kt")
        assertTrue(b.contains("Awake8010.noteTick(android.os.SystemClock.elapsedRealtime(), android.os.SystemClock.uptimeMillis())"))
        assertTrue(b.contains("try { postBatteryFixNotification8010() } catch (_: Throwable) {}"))
        assertTrue(b.contains("canScheduleExactAlarms()"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("Awake8010.statusLine()"))
    }
}
