package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexSnapshotArrays9045
import org.junit.Assert.*
import org.junit.Test

class Aate9045SnapshotArraysTest {
    @Test fun sharedSourceIsCopiedOnceWithinOneCaptureAndNeverAliasesTheLiveArray() {
        val source = doubleArrayOf(-5.0, 0.0, 12.0)
        val capture = CortexSnapshotArrays9045()
        val first = capture.copyOf(source)
        repeat(10_000) { assertSame(first, capture.copyOf(source)) }
        assertNotSame(source, first)
        assertEquals(1, capture.copied)
        assertEquals(10_000, capture.reused)
        source[0] = 999.0
        assertArrayEquals(doubleArrayOf(-5.0, 0.0, 12.0), first, 0.0)
    }

    @Test fun equalValuesWithDistinctSourceIdentityNeverShareTheDetachedArray() {
        val a = doubleArrayOf(1.0, 2.0)
        val b = a.copyOf()
        val capture = CortexSnapshotArrays9045()
        val detachedA = capture.copyOf(a); val detachedB = capture.copyOf(b)
        assertArrayEquals(detachedA, detachedB, 0.0)
        assertNotSame(detachedA, detachedB)
        assertEquals(2, capture.copied)
        assertEquals(0, capture.reused)
    }

    @Test fun aLaterGenerationSeesTheNewValuesThroughItsOwnIndependentCopy() {
        val live = doubleArrayOf(1.0, 2.0)
        val before = CortexSnapshotArrays9045().copyOf(live)
        live[1] = 40.0
        val after = CortexSnapshotArrays9045().copyOf(live)
        assertNotSame(before, after)
        assertArrayEquals(doubleArrayOf(1.0, 2.0), before, 0.0)
        assertArrayEquals(doubleArrayOf(1.0, 40.0), after, 0.0)
        live[1] = 90.0
        assertEquals(40.0, after[1], 0.0)
    }
}
