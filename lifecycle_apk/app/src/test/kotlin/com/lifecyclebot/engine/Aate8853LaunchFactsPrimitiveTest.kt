package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchFacts8840
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8853 — a launch's traders in primitive arrays: the same facts as a plain per-wallet count, at a fraction of the memory. */
class Aate8853LaunchFactsPrimitiveTest {
    private class R(var b: Double = 0.0, var s: Double = 0.0, var firstMs: Long = Long.MAX_VALUE, var firstTok: Double = 0.0)

    @Test fun theTableGrowsAndAgreesWithAPlainCount() {
        val rnd = java.util.Random(8853)
        val f = LaunchFacts8840.Fold8840("W0", "SIG0")
        val ref = LinkedHashMap<String, R>()
        var t = 5_000_000L
        val p = 4e-8
        for (k in 0 until 3_000) {
            val w = if (k == 0) "W0" else "W${rnd.nextInt(400)}"
            val buy = k == 0 || rnd.nextDouble() < 0.7
            val sol = 0.01 + rnd.nextDouble()
            val sig = if (k == 0) "SIG0" else "S$k"
            f.add(t, p, sol, buy, w, sig)
            val r = ref.getOrPut(w) { R() }; val tok = sol / p
            if (buy) { r.b += tok; if (r.firstMs == Long.MAX_VALUE) { r.firstMs = t; r.firstTok = tok } else if (t / 1000 == r.firstMs / 1000) r.firstTok += tok } else r.s += tok
            t += if (k < 40) 100L else 700L
        }
        val x = f.snapshot()
        val createdSec = 5_000_000L / 1000L
        val others = ref.filterKeys { it != "W0" }.values.filter { it.firstMs != Long.MAX_VALUE }
        assertEquals(others.count { it.firstMs / 1000 == createdSec }, x.bundleBuyers)
        assertEquals(others.count { it.firstMs / 1000 - createdSec in 0L..2L }, x.sniperBuyers)
        assertEquals(ref.values.count { it.b > 0.0 }, x.uniqueBuyers)
        val held = ref.values.map { maxOf(0.0, it.b - it.s) }.sortedDescending()
        assertEquals(100.0 * held[0] / 1e9, x.top1Pct, 1e-6)
        assertEquals(100.0 * held.take(5).sum() / 1e9, x.top5Pct, 1e-6)
        assertEquals(100.0 * ref.getValue("W0").firstTok / 1e9, x.devInitPct, 1e-6)
    }

    @Test fun theStoreIsBoundedAndNoLongerKeepsAddressStrings() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaunchFacts8840.kt").readText()
        assertTrue(!src.contains("HashMap<String, W>"))
        assertTrue(src.contains("private const val MAX_LIVE = 1_500") && src.contains("internal const val WALLET_SLOTS_8853 = 1_024"))
        assertTrue(LaunchFacts8840.walletKey8853("") != 0L)
    }
}
