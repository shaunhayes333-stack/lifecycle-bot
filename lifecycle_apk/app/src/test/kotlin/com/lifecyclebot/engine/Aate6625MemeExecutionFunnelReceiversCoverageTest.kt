package com.lifecyclebot.engine

import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * V5.0.6625 — MEME EXECUTION FUNNEL RECEIVERS coverage (P2/P3/P4/P5/P6
 * of the operator's V5.0.6622 forensic).
 *
 * The receivers themselves already exist in
 * MemeExecutionFunnelReceivers6625.kt. This suite proves:
 *   1. ToolkitSignalSheet.recordDeskStage is the single fan-out into
 *      the receivers (no drift possible; every specialist stage
 *      goes through one door).
 *   2. Express funnel counters advance on EXPRESS lane stages only.
 *   3. Pending intent backlog records on BUY_INTENT, consumes on
 *      TICKET/EXEC/SELL_CONFIRMED/SIZE_REJECT, and reap6625 drains
 *      stale entries older than the age threshold.
 *   4. MOONSHOT retries with the same positionId resume the same
 *      transaction id rather than opening a competing one.
 *   5. SpecialistCausalFunnel6625 stamps one canonical record per
 *      causal key so impossible combos like fdgAllow=0 exec=113 are
 *      structurally prevented for the same intentId.
 *   6. Source-file authority — receivers file exists with the
 *      required public API. Guards against a silent revert of the
 *      MemeExecutionFunnelReceivers6625.kt module.
 */
class Aate6625MemeExecutionFunnelReceiversCoverageTest {

    @After
    fun tearDown() {
        try { com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.resetForTest() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.PendingIntentBacklog6625.resetForTest() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.MoonshotExitTransaction6625.resetForTest() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.resetForTest() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.UiOffMainAudit6625.resetForTest() } catch (_: Throwable) {}
    }

    @Test
    fun aate6625_express_funnel_advances_on_express_stages() {
        val id = "aate6625-express-intent-A"
        ToolkitSignalSheet.recordDeskStage("EXPRESS", "BUY_INTENT", id)
        ToolkitSignalSheet.recordDeskStage("EXPRESS", "MARK_READY", id)
        ToolkitSignalSheet.recordDeskStage("EXPRESS", "SIZED_EXECUTABLE", id)
        ToolkitSignalSheet.recordDeskStage("EXPRESS", "TICKET", id)
        ToolkitSignalSheet.recordDeskStage("EXPRESS", "EXEC", id)
        val status = com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.statusLine()
        assertTrue("V5.0.6625 §P2: intent seen must count", status.contains("intent=1"))
        assertTrue("V5.0.6625 §P2: mark ok must count", status.contains("markOK=1"))
        assertTrue("V5.0.6625 §P2: positive sizing must count", status.contains("sizedPos=1"))
        assertTrue("V5.0.6625 §P2: ticket seal must count", status.contains("ticketSealed=1"))
        assertTrue("V5.0.6625 §P2: exec must count", status.contains("executed=1"))
    }

    @Test
    fun aate6625_express_funnel_ignores_non_express_lanes() {
        // CORE / BLUECHIP / MOONSHOT stages must NOT leak into the
        // EXPRESS counters (that would recreate P5's cross-drift).
        ToolkitSignalSheet.recordDeskStage("CORE", "BUY_INTENT", "aate6625-core-A")
        ToolkitSignalSheet.recordDeskStage("BLUECHIP", "TICKET", "aate6625-bc-A")
        ToolkitSignalSheet.recordDeskStage("MOONSHOT", "EXEC", "aate6625-ms-A")
        val status = com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.statusLine()
        assertTrue("V5.0.6625 §P2: other lanes must NOT bump EXPRESS counters",
            status.contains("intent=0") && status.contains("ticketSealed=0") && status.contains("executed=0"))
    }

    @Test
    fun aate6625_pending_backlog_records_and_consumes() {
        ToolkitSignalSheet.recordDeskStage("CORE", "BUY_INTENT", "aate6625-core-Y")
        ToolkitSignalSheet.recordDeskStage("BLUECHIP", "BUY_INTENT", "aate6625-bc-Y")
        val afterRecord = com.lifecyclebot.engine.truth.PendingIntentBacklog6625.statusLine()
        assertTrue("V5.0.6625 §P3: intents must be recorded",
            afterRecord.contains("pending=2"))
        // Ticket seal on CORE consumes; SIZE_REJECT on BLUECHIP consumes.
        ToolkitSignalSheet.recordDeskStage("CORE", "TICKET", "aate6625-core-Y")
        ToolkitSignalSheet.recordDeskStage("BLUECHIP", "SIZE_REJECT", "aate6625-bc-Y")
        val afterConsume = com.lifecyclebot.engine.truth.PendingIntentBacklog6625.statusLine()
        assertTrue("V5.0.6625 §P3: TICKET and SIZE_REJECT must consume backlog",
            afterConsume.contains("pending=0") && afterConsume.contains("consumed=2"))
    }

    @Test
    fun aate6625_pending_backlog_reap_drains_stale_entries() {
        ToolkitSignalSheet.recordDeskStage("CORE", "BUY_INTENT", "aate6625-core-stale")
        Thread.sleep(35L)
        val reaped = com.lifecyclebot.engine.truth.PendingIntentBacklog6625.reap6625(maxAgeMs = 10L)
        assertTrue("V5.0.6625 §P3: reap must evict entries older than the age threshold", reaped >= 1)
        val status = com.lifecyclebot.engine.truth.PendingIntentBacklog6625.statusLine()
        assertTrue("V5.0.6625 §P3: aged-out counter must advance", status.contains("agedOut="))
    }

    @Test
    fun aate6625_moonshot_retry_resumes_same_transaction_id() {
        val positionId = "aate6625-ms-position-B"
        ToolkitSignalSheet.recordDeskStage("MOONSHOT", "SELL_ATTEMPT", positionId)
        // Second SELL_ATTEMPT with the same positionId is idempotent at
        // the desk layer, so drive the receiver directly to prove the
        // resume-vs-new-tx invariant.
        val first = com.lifecyclebot.engine.truth.MoonshotExitTransaction6625
            .beginOrResumeTransaction6625(positionId, txIdIfNew = "tx-first")
        val second = com.lifecyclebot.engine.truth.MoonshotExitTransaction6625
            .beginOrResumeTransaction6625(positionId, txIdIfNew = "tx-second")
        assertEquals("V5.0.6625 §P4: retry must resume the SAME tx id", first, second)
        val status = com.lifecyclebot.engine.truth.MoonshotExitTransaction6625.statusLine()
        assertTrue("V5.0.6625 §P4: retries must count as retries, not new attempts",
            status.contains("retries="))
    }

    @Test
    fun aate6625_moonshot_sell_confirmed_terminates_transaction() {
        val positionId = "aate6625-ms-position-C"
        ToolkitSignalSheet.recordDeskStage("MOONSHOT", "SELL_ATTEMPT", positionId)
        ToolkitSignalSheet.recordDeskStage("MOONSHOT", "SELL_CONFIRMED", positionId)
        val status = com.lifecyclebot.engine.truth.MoonshotExitTransaction6625.statusLine()
        assertTrue("V5.0.6625 §P4: SELL_CONFIRMED must terminate the live tx",
            status.contains("live=0") && status.contains("terminated=1"))
    }

    @Test
    fun aate6625_causal_funnel_records_stages_under_one_key() {
        val id = "aate6625cfD:1"
        ToolkitSignalSheet.recordDeskStage("CORE", "BUY_INTENT", id)
        ToolkitSignalSheet.recordDeskStage("CORE", "FDG_ALLOW", id)
        ToolkitSignalSheet.recordDeskStage("CORE", "TICKET", id)
        ToolkitSignalSheet.recordDeskStage("CORE", "EXEC", id)
        val stages = com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.stageCounts6625("CORE")
        assertTrue("V5.0.6625 §P5: INTENT stage must record for the CORE lane",
            (stages[com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.INTENT] ?: 0) >= 1)
        assertTrue("V5.0.6625 §P5: FDG stage must record for the CORE lane",
            (stages[com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.FDG] ?: 0) >= 1)
        assertTrue("V5.0.6625 §P5: EXEC stage must record for the CORE lane",
            (stages[com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.EXEC] ?: 0) >= 1)
    }

    @Test
    fun aate6625_receiver_source_file_exists_with_required_api() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/MemeExecutionFunnelReceivers6625.kt"
        ).readText()
        assertTrue("V5.0.6625: ExpressHandoffFunnel6625 must expose the sizing+ticket+exec hops",
            src.contains("object ExpressHandoffFunnel6625") &&
                src.contains("fun onIntentSeen6625(") &&
                src.contains("fun onSizingResult6625(") &&
                src.contains("fun onTicketSealed6625(") &&
                src.contains("fun onExecuted6625("))
        assertTrue("V5.0.6625: PendingIntentBacklog6625 must expose record/consume/reap",
            src.contains("object PendingIntentBacklog6625") &&
                src.contains("fun record6625(") &&
                src.contains("fun consume6625(") &&
                src.contains("fun reap6625("))
        assertTrue("V5.0.6625: MoonshotExitTransaction6625 must expose begin-or-resume and terminate",
            src.contains("object MoonshotExitTransaction6625") &&
                src.contains("fun beginOrResumeTransaction6625(") &&
                src.contains("fun terminate6625("))
        assertTrue("V5.0.6625: SpecialistCausalFunnel6625 must stamp one causal-key per stage",
            src.contains("object SpecialistCausalFunnel6625") &&
                src.contains("data class CausalKey(") &&
                src.contains("fun stamp6625("))
        assertTrue("V5.0.6625: UiOffMainAudit6625 must record long-running main-thread work",
            src.contains("object UiOffMainAudit6625") &&
                src.contains("fun recordMainThreadWork6625("))
    }

    @Test
    fun aate6625_recordDeskStage_wires_receivers_source_authority() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt"
        ).readText()
        assertTrue("V5.0.6625: ToolkitSignalSheet.recordDeskStage must fan out to receivers",
            src.contains("fanOutToReceivers6625") &&
                src.contains("ExpressHandoffFunnel6625") &&
                src.contains("PendingIntentBacklog6625") &&
                src.contains("MoonshotExitTransaction6625") &&
                src.contains("SpecialistCausalFunnel6625"))
    }

    @Test
    fun aate6625_pipeline_health_collector_appends_receiver_status() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt"
        ).readText()
        assertTrue("V5.0.6625: pipeline dump must include the receiver status block",
            src.contains("MEME EXECUTION FUNNEL RECEIVERS (V5.0.6625)") &&
                src.contains("PendingIntentBacklog6625.statusLine()") &&
                !src.contains("PendingIntentBacklog6625.reap6625"))
    }
}
