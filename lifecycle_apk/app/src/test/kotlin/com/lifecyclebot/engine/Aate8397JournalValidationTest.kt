package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Calls the actual shared journal reader, including its revision/ceiling memo and accounting predicates. */
class Aate8397JournalValidationTest {
    private fun field(name: String) = TradeHistoryStore::class.java.getDeclaredField(name).apply { isAccessible = true }
    private val journalLock: Any get() = field("lock").get(TradeHistoryStore)

    @Suppress("UNCHECKED_CAST")
    private fun read(ceiling: () -> Double): List<Trade> =
        TradeHistoryStore::class.java.getDeclaredMethod("validRowsNewestFirst7346", Function0::class.java)
            .apply { isAccessible = true }.invoke(TradeHistoryStore, ceiling) as List<Trade>

    @Suppress("UNCHECKED_CAST")
    private fun readHead(cap: Int, ceiling: () -> Double): List<Trade> =
        TradeHistoryStore::class.java.getDeclaredMethod("validRowsHead7348", Int::class.javaPrimitiveType, Function0::class.java)
            .apply { isAccessible = true }.invoke(TradeHistoryStore, cap, ceiling) as List<Trade>

    @Suppress("UNCHECKED_CAST")
    private fun withRows(rows: List<Trade>, block: (MutableList<Trade>, AtomicLong) -> Unit) {
        val buildLock = field("validRowsBuildLock7348").get(TradeHistoryStore)
        synchronized(buildLock) {
            val live = field("trades").get(TradeHistoryStore) as MutableList<Trade>
            val revision = field("journalRevision7343").get(TradeHistoryStore) as AtomicLong
            val builds = field("validRowsBuilds7346").get(TradeHistoryStore) as AtomicLong
            val reuses = field("validRowsReuse7346").get(TradeHistoryStore) as AtomicLong
            val memoField = field("validRowsMemo7346")
            val oldMemo = memoField.get(TradeHistoryStore)
            val oldRevision = revision.get()
            val oldBuilds = builds.get()
            val oldReuses = reuses.get()
            val original = synchronized(journalLock) {
                live.toList().also {
                    live.clear(); live.addAll(rows); revision.incrementAndGet()
                    memoField.set(TradeHistoryStore, null)
                }
            }
            try { block(live, revision) } finally {
                synchronized(journalLock) {
                    live.clear(); live.addAll(original); revision.set(oldRevision)
                    memoField.set(TradeHistoryStore, oldMemo)
                    builds.set(oldBuilds); reuses.set(oldReuses)
                }
            }
        }
    }

    private fun buy(id: String, amount: Double = 0.5) = Trade(
        side = "BUY", mode = "paper", sol = amount, price = 1.0, ts = 1L,
        mint = "journal8397-$id", positionId = id, proofState = "PAPER_SIMULATED",
        entryPriceSnapshot = 1.0, entryCostSol = amount, reason = "verified entry",
    )

    @Test fun persisted8192RowReadUsesOneCeilingAndReusesTheCompletedRevision() {
        val rows = (0 until 8192).map { buy("bulk-$it") }
        withRows(rows) { _, _ ->
            val reads = AtomicInteger()
            val ceiling = { reads.incrementAndGet(); 1.0 }
            val builds = field("validRowsBuilds7346").get(TradeHistoryStore) as AtomicLong
            val before = builds.get()
            val first = read(ceiling)
            assertEquals(8192, first.size)
            assertEquals(1, reads.get())
            assertEquals(before + 1L, builds.get())
            assertSame(first, read(ceiling))
            assertEquals(2, reads.get()) // one policy snapshot per read, never per historical row
            assertEquals(before + 1L, builds.get())
        }
    }

    @Test fun changedCeilingAndJournalRevisionRevalidateWithoutUsingAnotherPolicy() {
        withRows(listOf(buy("small"), buy("larger", 1.5))) { live, revision ->
            assertEquals(listOf("small"), read { 1.0 }.map { it.positionId })
            assertEquals(listOf("larger", "small"), read { 2.0 }.map { it.positionId })
            assertEquals(listOf("small"), read { 1.0 }.map { it.positionId })
            synchronized(journalLock) { live.add(buy("new")); revision.incrementAndGet() }
            assertEquals(listOf("new", "small"), read { 1.0 }.map { it.positionId })
        }
    }

    @Test fun smallReadSharesOnePolicyWithoutBuildingTheWholeJournal() {
        withRows((0 until 8192).map { buy("head-$it") }) { _, _ ->
            val reads = AtomicInteger()
            val ceiling = { reads.incrementAndGet(); 1.0 }
            val builds = field("validRowsBuilds7346").get(TradeHistoryStore) as AtomicLong
            val before = builds.get()
            assertEquals((8191 downTo 7942).map { "head-$it" }, readHead(250, ceiling).map { it.positionId })
            assertEquals(1, reads.get())
            assertEquals(before, builds.get())
            assertEquals(700, readHead(700, ceiling).size)
            assertEquals(2, reads.get())
            assertEquals(before + 1L, builds.get())
        }
    }

    @Test fun capturedPolicyPreservesBasisProofAndRealizedRunnerChecks() {
        val runner = Trade(side = "SELL", mode = "paper", sol = 5.0, price = 5.0, ts = 2L,
            mint = "journal8397-runner", positionId = "runner", reason = "TAKE_PROFIT",
            proofState = "PAPER_SIMULATED", entryPriceSnapshot = 1.0, entryCostSol = 1.0,
            soldCostBasisSol = 1.0, grossProceedsSol = 5.0, pnlSol = 4.0, netPnlSol = 4.0, pnlPct = 400.0)
        val pending = runner.copy(mode = "live", proofState = "LIVE_BROADCAST", positionId = "pending")
        val missingBasis = runner.copy(entryCostSol = 0.0, positionId = "missingBasis")
        val invalid = runner.copy(pnlSol = Double.NaN, positionId = "invalid")
        withRows(listOf(buy("small"), buy("overCeiling", 1.5), buy("badPrice").copy(price = 0.0),
            runner, pending, missingBasis, invalid)) { _, _ ->
            assertEquals(setOf("small", "runner"), read { 1.0 }.map { it.positionId }.toSet())
        }
    }
}
