package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.ChartSources7955
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/** Chart-library refresh must not turn a provider's unbounded JSON body into an OOM. */
class Aate8375BoundedChartResponseTest {
    @Test fun acceptsBodiesAtTheLimitAndRejectsOversizedBodies() {
        val atLimit = "{}".toByteArray()
        assertEquals("{}", ChartSources7955.boundedBody8375(ByteArrayInputStream(atLimit), 2))
        assertNull(ChartSources7955.boundedBody8375(ByteArrayInputStream("{}x".toByteArray()), 2))
        assertEquals("", ChartSources7955.boundedBody8375(ByteArrayInputStream(byteArrayOf()), 2))
    }

    @Test fun oversizedBodyDoesNotRetryAndPreservesProviderFallback() {
        assertEquals("fallback", ChartSources7955.jobRetry8144(ChartSources7955.BODY_TOO_LARGE_8375, 0, 0))
        assertEquals("fallback", ChartSources7955.jobRetry8144(ChartSources7955.BODY_TOO_LARGE_8375, 10, 0))
    }
}
