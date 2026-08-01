package com.lifecyclebot.engine.truth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Bundle6405PaperEvBucketGateTest {

    @Test fun paper_is_never_gated() {
        val v = PaperEvBucketGate6405.evaluate(
            mint = "M", symbol = "SYM", lane = "STANDARD",
            scoreInt = 5, isPaper = true,
        )
        assertFalse(v.block)
    }

    @Test fun unknown_bucket_or_small_sample_allows_live() {
        // TacticSwitcher has no data for this brand-new key.
        val v = PaperEvBucketGate6405.evaluate(
            mint = "M", symbol = "SYM", lane = "NEW_LANE_${System.nanoTime()}",
            scoreInt = 5, isPaper = false,
        )
        assertFalse(v.block)
    }
}
