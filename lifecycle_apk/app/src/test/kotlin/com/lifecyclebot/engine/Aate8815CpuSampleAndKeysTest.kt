package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PocketLedger8610
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8815 — the CPU sampler's stack classification, and the pocket ledger's cached cell keys equal the composed ones. */
class Aate8815CpuSampleAndKeysTest {
    private fun f(c: String, m: String) = StackTraceElement(c, m, "F.kt", 1)

    @Test fun aStackIsTalliedByItsTopAndDeepestAppFrame() {
        val st = arrayOf(f("org.json.JSONTokener", "nextValue"), f("org.json.JSONObject", "<init>"),
            f("com.lifecyclebot.engine.truth.PocketLedger8610\$seed\$1", "invoke"),
            f("com.lifecyclebot.engine.truth.PocketLedger8610", "ensureLoaded\$lambda\$0"), f("java.lang.Thread", "run"))
        assertEquals("PocketLedger8610.invoke <JSONTokener.nextValue>" to "PocketLedger8610.ensureLoaded", CpuSample8815.keys8815(st))
        val own = arrayOf(f("com.lifecyclebot.engine.cortex.Cortex7885", "encode"), f("com.lifecyclebot.engine.BotService", "loop"))
        assertEquals("Cortex7885.encode" to "BotService.loop", CpuSample8815.keys8815(own))
        // a socket read is RUNNABLE to the VM but not CPU work
        assertEquals(CpuSample8815.IO to CpuSample8815.IO, CpuSample8815.keys8815(arrayOf(f("libcore.io.Linux", "recvfrom"), f("com.lifecyclebot.network.X", "y"))))
        assertNull(CpuSample8815.keys8815(arrayOf(f("android.os.Looper", "loop"))))
        assertNull(CpuSample8815.keys8815(emptyArray()))
        CpuSample8815.resetForTest8815()
        CpuSample8815.note8815("DefaultDispatcher-worker-12", own)
        assertTrue(CpuSample8815.statusLine8815().contains("Cortex7885.encode [DefaultDispatcher-worker] 100.0%"))
    }

    @Test fun cachedCellKeysAreTheComposedKeysAndAreReused() {
        val b = PocketLedger8610.bands8610(5_000.0, 30_000.0, 12.0, 60.0, 4.0, 15.0, 0.9, 6.0, 20.0, "expanding")
        val composed = PocketLedger8610.keys8610(" express ", b).map { "300000|$it" }
        val cached = PocketLedger8610.cellKeys8815(300_000L, " express ", b, market = true)
        assertEquals(composed, cached)
        assertEquals(composed.filter { !it.startsWith("300000|M|") }, PocketLedger8610.cellKeys8815(300_000L, "EXPRESS", b, market = false))
        val again = PocketLedger8610.cellKeys8815(300_000L, "EXPRESS", b, market = true)
        for (i in cached.indices) assertSame(cached[i], again[i])   // built once, reused
    }
}
