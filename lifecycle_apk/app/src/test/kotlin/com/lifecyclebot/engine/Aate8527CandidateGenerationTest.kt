package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.SpecialistCandidateBooks7803
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class Aate8527CandidateGenerationTest {
    private val books = SpecialistCandidateBooks7803

    @Before fun reset() = books.resetForTests()
    @After fun cleanup() = books.resetForTests()

    @Test fun newerRefusalCannotBeResurrectedByOlderQualificationOrReadinessInAnyLane() {
        for (lane in books.LANES) {
            val mint = "8527-refusal-$lane"
            books.markReady(lane, mint, lane, 100L, 90, 90.0, "ready-100")
            books.markLost(lane, mint, 101L, "refused-101")
            val lost = books.entry(lane, mint)!!
            assertEquals(101L, lost.candidateVersion)
            assertEquals(SpecialistCandidateBooks7803.State.LOST, lost.state)

            books.markQualified(lane, mint, lane, 100L, 99.0, "late-qualified-100")
            books.markReady(lane, mint, lane, 100L, 99, 99.0, "late-ready-100")
            assertEquals(lost, books.entry(lane, mint))
            assertFalse(books.qualifiedFor(mint, 101L).containsKey(lane))
            assertFalse(books.readyFor(mint, 101L).containsKey(lane))
        }
    }

    @Test fun genuinelyNewerApprovalCanRecoverAfterRefusal() {
        val mint = "8527-newer-approval"
        books.markReady("MOONSHOT", mint, "NEW", 100L, 90, 90.0, "ready-100")
        books.markLost("MOONSHOT", mint, 101L, "refused-101")
        books.markQualified("MOONSHOT", mint, "NEW", 102L, 91.0, "qualified-102")
        assertEquals(102L, books.qualifiedFor(mint, 102L).getValue("MOONSHOT").candidateVersion)
        books.markReady("MOONSHOT", mint, "NEW", 102L, 91, 91.0, "ready-102")
        assertEquals(102L, books.readyFor(mint, 102L).getValue("MOONSHOT").candidateVersion)
    }

    @Test fun staleRefusalStillCannotOverwriteNewerReadyEvidence() {
        val mint = "8527-stale-refusal"
        books.markReady("CORE", mint, "NEW", 102L, 90, 90.0, "ready-102")
        val ready = books.entry("CORE", mint)
        books.markLost("CORE", mint, 101L, "late-refused-101")
        assertEquals(ready, books.entry("CORE", mint))
        assertTrue(books.readyFor(mint, 102L).containsKey("CORE"))
    }

    @Test fun unversionedRefusalPreservesKnownGenerationAndHuntCannotEraseIt() {
        val mint = "8527-unversioned"
        books.markReady("EXPRESS", mint, "NEW", 102L, 90, 90.0, "ready-102")
        books.markLost("EXPRESS", mint, 0L, "unversioned-refusal")
        assertEquals(102L, books.entry("EXPRESS", mint)!!.candidateVersion)
        assertEquals(SpecialistCandidateBooks7803.State.LOST, books.entry("EXPRESS", mint)!!.state)
        books.publishHunt("EXPRESS", mint, "NEW")
        books.markReady("EXPRESS", mint, "NEW", 101L, 99, 99.0, "late-ready-101")
        assertEquals(102L, books.entry("EXPRESS", mint)!!.candidateVersion)
        assertFalse(books.readyFor(mint, 102L).containsKey("EXPRESS"))
    }
    @Test fun refusalOrderingSurvivesPruningTheShortLivedLostDisplayRow() {
        val mint = "8527-pruned-lost"
        books.markReady("QUALITY", mint, "OLD", 100L, 90, 90.0, "ready-100")
        books.markLost("QUALITY", mint, 101L, "refused-101")
        // Age the real display row without sleeps; entry() runs production pruning.
        val field = books.javaClass.getDeclaredField("books").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val maps = field.get(books) as Map<String, java.util.concurrent.ConcurrentHashMap<String, SpecialistCandidateBooks7803.Entry>>
        val lane = maps.getValue("QUALITY")
        lane.computeIfPresent(mint) { _, e -> e.copy(updatedAtMs = System.currentTimeMillis() - 6_000L) }
        assertNull(books.entry("QUALITY", mint))
        assertNull(books.markReady("QUALITY", mint, "OLD", 100L, 99, 99.0, "late-100"))
        assertFalse(books.readyFor(mint, 101L).containsKey("QUALITY"))

        // A genuinely re-evaluated same-generation decision is allowed, as is a newer one.
        assertEquals(101L, books.markReady("QUALITY", mint, "NEW", 101L, 91, 91.0, "reevaluated-101")!!.candidateVersion)
        assertEquals(102L, books.markReady("QUALITY", mint, "NEW", 102L, 92, 92.0, "reevaluated-102")!!.candidateVersion)
    }

    @Test fun refusalArrivingBeforeAnOlderApprovalStillControlsTheGeneration() {
        val mint = "8527-refusal-first"
        books.markLost("BLUECHIP", mint, 101L, "refusal-before-qualification")
        assertNull(books.markQualified("BLUECHIP", mint, "OLD", 100L, 99.0, "late-qualified"))
        assertNull(books.markReady("BLUECHIP", mint, "OLD", 100L, 99, 99.0, "late-ready"))
        assertEquals(102L, books.markReady("BLUECHIP", mint, "NEW", 102L, 99, 99.0, "new-ready")!!.candidateVersion)
    }

}
