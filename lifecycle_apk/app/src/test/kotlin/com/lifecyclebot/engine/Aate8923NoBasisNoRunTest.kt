package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ResearchFollow8884
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8923 — a decision on a pool no buyer could take is not followed; a re-read past 100x the decision price is no return. */
class Aate8923NoBasisNoRunTest {
    @Test fun aPriceNoPoolCanHoldIsNoBasis() {
        assertFalse("a $9 pool", ResearchFollow8884.tradeable8923(9.0))
        assertTrue(ResearchFollow8884.tradeable8923(841.0))
        assertTrue("an unread pool is not judged here", ResearchFollow8884.tradeable8923(Double.NaN))
        assertTrue("14x", ResearchFollow8884.plausible8923(1.0, 14.0))
        assertFalse("+73,000 %", ResearchFollow8884.plausible8923(1.0, 731.0))
        assertFalse(ResearchFollow8884.plausible8923(0.0, 1.0))
        val f = File("src/main/kotlin/com/lifecyclebot/engine/truth/ResearchFollow8884.kt").readText()
        assertTrue(f.contains("if (!tradeable8923(liq)) { untradeable8923.incrementAndGet(); return }"))
        assertTrue(f.contains("implausible -> \"IMPLAUSIBLE_RATIO_8923:\" + w.reason"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
            .contains("if (ResearchFollow8884.tradeable8923(o.liqUsd8539)) try {"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 111. A price no pool can hold is no basis (8923)"))
    }
}
