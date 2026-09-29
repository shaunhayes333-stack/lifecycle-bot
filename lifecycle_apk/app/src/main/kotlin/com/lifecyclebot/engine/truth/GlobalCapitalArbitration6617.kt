package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6617 §GLOBAL_CAPITAL_ARBITRATION (operator directive Feb 2026):
 *
 *   "Repair A — Global capital across trading domains. Every lane must
 *    read the SAME spendable cash figure from the same authority.
 *    Lanes may SHAPE their proposals but must never fabricate a
 *    lane-local wallet balance that diverges from the shared account."
 *
 * FORENSIC EVIDENCE (pre-6617):
 *   Main AATE      ~$1,190
 *   Markets        ~$931  PAPER
 *   Crypto Alt     ~$648
 *   Meme           ~1.000 SOL (local lane balance)
 *   Canonical      CASH=0.9081 SOL   ← what actually existed
 *
 * FIVE PERPS TRADERS (Commodities / Metals / Forex / TokenizedStock /
 * PerpsTraderAI) still returned `BotService.status.paperWalletSol` from
 * their getBalance()/getEffectiveBalance() — that field is a stale
 * mirror written by BotService, not the live canonical cash. This
 * arbiter is the single façade every lane reads via
 * `GlobalCapitalArbitration6617.availableForLane(lane, paperMode,
 * liveWalletSol)`. Paper mode → PaperCapitalAuthority6577.cashSol();
 * live mode → the caller's own wallet SOL. Emits
 * LANE_CAPITAL_REQUEST_6617_<lane> so operator sees exactly which lane
 * asked for what capital and how much was granted vs shared.
 *
 * The arbiter is READ-ONLY. It does NOT reserve, debit, or promise
 * capital — reservation still happens at the canonical mutation site
 * (PaperAccountLedger6430.onBuy) which is the single writer. The
 * arbiter's job is: (1) publish the same number to every lane, (2)
 * count divergent requests, and (3) flag any lane that tries to read a
 * lane-local wallet after 6617 lands.
 */
object GlobalCapitalArbitration6617 {

    private val requests = ConcurrentHashMap<String, AtomicLong>()
    private val laneLocalBypassAttempts = ConcurrentHashMap<String, AtomicLong>()
    private val totalRequests = AtomicLong(0L)

    // V5.0.7458 — specialist proposal continuity. Multiple desks may propose
    // the same mint independently, so the key is mint+lane, never mint alone.
    data class SpecialistProposal7458(
        val lane: String,
        val specialistId: String,
        val mint: String,
        val proposedSol: Double,
        val recordedAtMs: Long,
    )
    private val specialistProposals7458 = ConcurrentHashMap<String, SpecialistProposal7458>()
    private fun specialistKey7458(lane: String, mint: String): String =
        "${lane.trim().uppercase()}|${mint.trim()}"

    /**
     * Every lane's getBalance()/getEffectiveBalance() calls this instead
     * of returning a private wallet field. Paper mode always returns
     * the canonical shared cash; live mode returns the caller's wallet
     * (finality-authoritative). The lane's proposed size may still be
     * shaped by learned lane risk caps in TraderSizingBridge6444.
     */
    fun availableForLane(
        lane: String,
        paperMode: Boolean,
        liveWalletSol: Double = 0.0,
    ): Double {
        totalRequests.incrementAndGet()
        requests.computeIfAbsent(lane) { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LANE_CAPITAL_REQUEST_6617_${lane.uppercase()}") } catch (_: Throwable) {}
        return if (paperMode) {
            try { PaperCapitalAuthority6577.availableCashSol().coerceAtLeast(0.0) } catch (_: Throwable) { 0.0 }
        } else {
            liveWalletSol.coerceAtLeast(0.0)
        }
    }

    /**
     * V5.0.6617 §D — SPECIALIST_EXECUTION_STAYS_SPECIALIST.
     * Called by TraderSizingBridge6444.resolveForLane to journal the
     * specialist identity attached to a sizing proposal. The seal is
     * observation-only (SealedOrderSizeAuthority6497 already binds the
     * final size to the mint). This layer proves the SPECIALIST that
     * proposed the size is the same specialist that dispatched the
     * order — no silent swap in Executor before FDG.
     */
    fun recordSpecialistProposal6617(
        lane: String,
        specialistId: String,
        mint: String,
        proposedSol: Double,
    ) {
        val laneKey = lane.trim().uppercase()
        val mintKey = mint.trim()
        if (laneKey.isBlank() || mintKey.isBlank() || !proposedSol.isFinite() || proposedSol < 0.0) {
            try { PipelineHealthCollector.labelInc("SPECIALIST_PROPOSAL_INVALID_7458") } catch (_: Throwable) {}
            return
        }
        specialistProposals7458[specialistKey7458(laneKey, mintKey)] = SpecialistProposal7458(
            lane = laneKey,
            specialistId = specialistId.ifBlank { laneKey },
            mint = mintKey,
            proposedSol = proposedSol,
            recordedAtMs = System.currentTimeMillis(),
        )
        // Bound stale proposal memory; the proposal is only useful around the
        // current candidate generation and never becomes economic authority.
        if (specialistProposals7458.size > 12_000) {
            val cutoff = System.currentTimeMillis() - 30L * 60_000L
            specialistProposals7458.entries.removeIf { it.value.recordedAtMs < cutoff }
        }
        try {
            PipelineHealthCollector.labelInc("SPECIALIST_PROPOSAL_${laneKey}_6617")
            PipelineHealthCollector.labelInc("SPECIALIST_PROPOSAL_RECORDED_7458")
            if (specialistId.isNotBlank()) {
                PipelineHealthCollector.labelInc("SPECIALIST_PROPOSAL_ID_${specialistId.uppercase()}_6617")
            }
        } catch (_: Throwable) {}
    }

    /**
     * Verify that the same specialist lane reaching canonical entry had a
     * sizing proposal for this asset. Observation-only: absence/mismatch is
     * forensic evidence, not a new trading gate.
     */
    fun verifySpecialistProposal7458(
        lane: String,
        specialistId: String,
        mint: String,
        dispatchedSol: Double,
    ): Boolean {
        val laneKey = lane.trim().uppercase()
        val mintKey = mint.trim()
        val p = specialistProposals7458[specialistKey7458(laneKey, mintKey)]
        if (p == null) {
            try {
                PipelineHealthCollector.labelInc("SPECIALIST_PROPOSAL_MISSING_AT_DISPATCH_7458")
                PipelineHealthCollector.labelInc("SPECIALIST_PROPOSAL_MISSING_AT_DISPATCH_7458_$laneKey")
            } catch (_: Throwable) {}
            return false
        }
        val sameSpecialist = specialistId.isBlank() || p.specialistId.equals(specialistId, true)
        val saneSize = dispatchedSol.isFinite() && dispatchedSol >= 0.0
        val ok = sameSpecialist && saneSize
        try {
            PipelineHealthCollector.labelInc(if (ok) "SPECIALIST_PROPOSAL_DISPATCH_MATCH_7458" else "SPECIALIST_PROPOSAL_DISPATCH_MISMATCH_7458")
            if (!ok) {
                ForensicLogger.lifecycle(
                    "SPECIALIST_PROPOSAL_DISPATCH_MISMATCH_7458",
                    "mint=${mintKey.take(12)} proposalLane=${p.lane} proposalSpecialist=${p.specialistId} dispatchLane=$laneKey dispatchSpecialist=$specialistId proposed=${p.proposedSol} dispatched=$dispatchedSol",
                )
            }
        } catch (_: Throwable) {}
        return ok
    }


    /**
     * Any lane call site that must legacy-fallback to a private wallet
     * field (extremely rare, e.g. cold-start before the authority
     * hydrates) records the attempt here. Steady-state target = 0 per
     * operator invariant.
     */
    fun recordLaneLocalBypass6617(lane: String, source: String) {
        laneLocalBypassAttempts.computeIfAbsent(lane) { AtomicLong(0L) }.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("LANE_LOCAL_WALLET_BYPASS_6617_${lane.uppercase()}")
            ForensicLogger.lifecycle(
                "LANE_LOCAL_WALLET_BYPASS_6617",
                "lane=$lane source=${source.take(40)}",
            )
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        val top = requests.entries
            .sortedByDescending { it.value.get() }
            .take(6)
            .joinToString(",") { "${it.key}=${it.value.get()}" }
        val bypasses = laneLocalBypassAttempts.entries
            .filter { it.value.get() > 0L }
            .joinToString(",") { "${it.key}=${it.value.get()}" }
        return "totalRequests=${totalRequests.get()} top=[$top] " +
            (if (bypasses.isBlank()) "bypasses=none" else "bypasses=[$bypasses]")
    }

    internal fun resetForTest() {
        requests.clear(); laneLocalBypassAttempts.clear(); specialistProposals7458.clear(); totalRequests.set(0L)
    }
}
