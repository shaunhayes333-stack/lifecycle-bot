package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.MarkIdentityRepairAuthority7236
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8432 — a failed sell attempt voids the executable quote that served it; the memory line names the kind of memory held. */
class Aate8432ExitRequoteTest {
    @Test fun aFailedSellAttemptInvalidatesTheMintsExecutableQuote() {
        val spine = File("src/main/kotlin/com/lifecyclebot/engine/sell/SellRouteSpine8144.kt").readText()
        val fail = spine.indexOf("private fun fail8144(")
        assertTrue(fail > 0)
        assertTrue(spine.indexOf("MarkIdentityRepairAuthority7236.invalidateExecutable8432(mint)", fail) > fail)
        // invalidating a mint nobody quoted is a no-op that still leaves no fresh price
        MarkIdentityRepairAuthority7236.invalidateExecutable8432("Mint8432".padEnd(44, 'x'))
        assertNull(MarkIdentityRepairAuthority7236.getExecutablePriceIfFresh7301("Mint8432".padEnd(44, 'x')))
        val auth = File("src/main/kotlin/com/lifecyclebot/engine/truth/MarkIdentityRepairAuthority7236.kt").readText()
        assertTrue(auth.contains("executable7301.remove(mint)") && auth.contains("execAttempt7301.remove(mint)"))
    }

    @Test fun theMemoryLineReportsTheKindOfMemoryHeld() {
        val mg = File("src/main/kotlin/com/lifecyclebot/engine/MemoryGuard7977.kt").readText()
        assertTrue(mg.contains("censusStatus8144 + \" \" + netLine8305() + \" \" + pssKinds8432()"))
        assertTrue("never throws: a missing Debug class degrades to a named blank", mg.contains("} catch (_: Throwable) { \"pss8432[-]\" }"))
        assertTrue(mg.contains("summary.java-heap") && mg.contains("summary.native-heap") && mg.contains("summary.graphics"))
    }
}
