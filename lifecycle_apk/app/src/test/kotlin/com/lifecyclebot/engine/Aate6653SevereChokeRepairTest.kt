package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-level acceptance locks for the V5.0.6653 closed-loop choke repair. */
class Aate6653SevereChokeRepairTest {

    @Test
    fun `execution gate validates sealed receipt and never re-elects after FDG`() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val start = source.indexOf("private fun canOpenExecutablePositionInternal")
        val end = source.indexOf("val ticketAuthority6564", start)
        val boundary = source.substring(start, end)
        assertTrue(boundary.contains("sealedReceiptLane6653"))
        assertTrue(boundary.contains("IMMUTABLE_ELECTION_LANE_MISMATCH_6653"))
        assertFalse(boundary.contains(".elect6629("))
        assertFalse(boundary.contains("SPECIALIST_NON_ELECTED_EXECUTION_REJECTED_6641"))
    }

    @Test
    fun `stale intent terminalization is runtime owned and reports stay read only`() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val report = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val authorizer = File("src/main/kotlin/com/lifecyclebot/engine/TradeAuthorizer.kt").readText()
        val toolkit = File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        assertTrue(bot.contains("PendingIntentBacklog6625.reap6625(30_000L)"))
        assertTrue(bot.contains("ExpressHandoffFunnel6625.reap6627(30_000L)"))
        assertFalse(report.contains("PendingIntentBacklog6625.reap6625"))
        assertTrue(authorizer.contains("recordDeskStage(requestedBook.name, \"AUTH_REJECT\""))
        assertTrue(authorizer.contains("recordDeskStage(requestedBook.name, \"SUPERSEDED\""))
        assertTrue(toolkit.contains("\"AUTH_REJECT\", \"SUPERSEDED\", \"STALE\""))
    }

    @Test
    fun `specialist liveness sample cannot become a 256 event discard queue`() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/truth/MemeExecutionFunnelReceivers6625.kt").readText()
        val start = source.indexOf("object SpecialistRuntimeRegistry6647")
        val boundary = source.substring(start)
        assertTrue(boundary.contains("AtomicReference<Traffic?>"))
        assertTrue(boundary.contains("SPECIALIST_RUNTIME_SAMPLE_COALESCED_6653"))
        assertFalse(boundary.contains("ArrayBlockingQueue"))
        assertFalse(boundary.contains("SPECIALIST_RUNTIME_QUEUE_OVERFLOW_6647"))
    }

    @Test
    fun `paper size floor is immutable and risk cap can fund it`() {
        val resolver = File("src/main/kotlin/com/lifecyclebot/engine/truth/OrderSizeResolver6441.kt").readText()
        val fdg = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        val executor = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(resolver.contains("private const val PAPER_EXECUTABLE_MINIMUM_SOL = 0.05"))
        assertFalse(resolver.contains("private val paperExecutableMinimum = AtomicReference"))
        assertTrue(fdg.contains("laneRiskCapSol = maxOf(sizingCash6653 * 0.12, paperMinimum6653)"))
        val minimumBlock = executor.substring(
            executor.indexOf("private fun minConfiguredPaperTradeSol"),
            executor.indexOf("private fun clampPaperTradeSol"),
        )
        assertFalse(minimumBlock.contains("updatePaperExecutableMinimumSol"))
    }

    @Test
    fun `terminal learning publishes only after exact economic commit`() {
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTerminalBridge6469.kt").readText()
        val start = bridge.indexOf("val finalizedEvent6653")
        val afterCommit = bridge.indexOf("CanonicalEconomicEvent6635.afterCommitted", start)
        val publish = bridge.indexOf("publish(finalizedEvent6653)", start)
        assertTrue(start >= 0 && publish > start && afterCommit > publish)
        assertTrue(bridge.contains("FINALIZED_BUS_QUEUED_AFTER_COMMIT_6653"))

        val journal = File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        assertTrue(journal.contains("durableEconomicEventIds"))
        assertTrue(journal.contains("trade.side.equals(\"SELL\", ignoreCase = true) && trade.economicEventId.isBlank()"))
        assertTrue(journal.contains("PAPER_JOURNAL_EXACT_EVENT_IDEMPOTENT_6653"))
    }
}
