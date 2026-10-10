package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7987SmallAccountMinTest {
    @Test fun smallWalletCanSendTheVenueMinimum() {
        assertEquals(0.30, LiveRiskPolicy7807.smallAccountMinShare7987(0.12), 1e-9)
        assertEquals(0.15, LiveRiskPolicy7807.smallAccountMinShare7987(2.0), 1e-9)
        assertEquals(0.0, LiveRiskPolicy7807.smallAccountMinShare7987(0.0), 1e-9)
        // 5.0.7985: equity 0.1215, min 0.0273 — fundable now, and the loss at a 15% stop + 3.6% cost stays under the cap.
        assertTrue(0.0273 <= 0.1215 * LiveRiskPolicy7807.smallAccountMinShare7987(0.1215))
        assertTrue(LiveRiskPolicy7807.executableMinRiskOk(0.0273, 15.0, 3.6, 0.1215))
    }
}
