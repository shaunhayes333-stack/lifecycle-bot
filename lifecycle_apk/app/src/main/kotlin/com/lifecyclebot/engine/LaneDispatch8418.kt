package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.SpecialistCandidateBooks7803

/**
 * V5.0.8418 — EVERY QUALIFIED CANDIDATE OF CORE / CYCLIC / PROJECT_SNIPER ENDS BY NAME.
 *
 * 5.0.8416 live: CORE chain8364[qualified=820 fdg=0/0 ... lostNoTerminal{QUALIFIED=348}], CYCLIC [qualified=749 fdg=0/0
 * ... lostNoTerminal{QUALIFIED=621}], PROJECT_SNIPER discovered=0 qualified=0 all session. Reading the code:
 *  - CORE and CYCLIC have no per-token buy block in BotService.processTokenCycle: no shouldRunBuyLaneForCycle call ever
 *    names them, so a cycle that elected another primary recorded nothing for their qualified candidate (every other
 *    lane gets NOT_EVALUATED_NOT_PRIMARY_<p> there). They reach the FDG only as the cycle primary (primary spine / V3
 *    trunk) — and a primary whose spine did not propose (no BUY signal / cannot propose) also ended with nothing.
 *  - CYCLIC's own entry is CyclicTradeEngine.tick: one ring pick per tick from the whole watchlist ranked by V3 score,
 *    blind to the candidates CYCLIC's native brain qualified — so its qualified candidates were almost never the pick,
 *    and the ones not picked ended with no terminal.
 *  - PROJECT_SNIPER: a coin its own hunter selected (LaneHunter7297 claim / MARKET_HUNT_PROJECT_SNIPE source) carries no
 *    toolkit hypothesis, and a native refusal removes the hypothesis, so it never reached POOL: discovered=0 although
 *    the hunter emitted hundreds (MARKET_HUNT_7301_EMITTED_PROJECT_SNIPER=334). Its native refusals were already named
 *    (nativeWhy8364), but the funnel could not show that the lane saw the coins at all.
 *
 * Here: [noteCycle8418] gives each block-less lane the same not-evaluated soft terminal every other lane gets at its
 * lane gate; [primarySpineWhy8418] names a block-less primary whose spine did not propose; [cyclicRank8418] ranks the
 * ring's own native-qualified (READY) candidates first among the ones that pass every ring filter, and
 * [noteCyclicRing8418] names the READY candidates the ring did not take; [hunterPoolLanes8418] records POOL (discovered)
 * for a lane whose own hunter selected the coin. Bookkeeping and ordering only: nothing here admits, sizes, relaxes a
 * filter or skips the FDG / TradeAuthorizer.
 */
object LaneDispatch8418 {
    /** Lanes with no per-token buy block of their own in processTokenCycle (they reach the FDG as primary / the ring). */
    private val BLOCKLESS_LANES_8418: Set<String> = setOf("CORE", "CYCLIC")

    private fun lane8418(raw: String?): String = try {
        com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(
            raw.orEmpty().uppercase().replace("BLUE_CHIP", "BLUECHIP").replace("SHITCOIN_EXPRESS", "EXPRESS"))
    } catch (_: Throwable) { raw.orEmpty().trim().uppercase() }

    // ── pure ──

    /** Pure: the block-less lanes a cycle with [primaryLane] did not let evaluate (every one that is not the primary). */
    fun notEvaluatedBlockless8418(primaryLane: String): List<String> {
        val p = primaryLane.trim().uppercase()
        return BLOCKLESS_LANES_8418.filter { it != p }.sorted()
    }

    /**
     * Pure: why a block-less primary's spine did not propose its candidate, or null (it proposes: the FDG verdict names it,
     * or the primary is not block-less / the position is open).
     */
    fun primarySpineWhy8418(primaryLane: String, positionOpen: Boolean, finalSignal: String?, canPropose: Boolean): String? {
        if (positionOpen || primaryLane.trim().uppercase() !in BLOCKLESS_LANES_8418) return null
        val sig = finalSignal.orEmpty().trim().uppercase().replace(Regex("[^A-Z0-9_]"), "_").take(24).ifBlank { "NONE" }
        return when {
            sig != "BUY" -> "PRIMARY_SIGNAL_${sig}_8418"
            !canPropose -> "PRIMARY_CANNOT_PROPOSE_8418"
            else -> null
        }
    }

    /**
     * Pure: the CYCLIC ring's rank of a candidate that passed every ring filter. Score x confidence as before (V5.9.1347);
     * a candidate CYCLIC's own native brain qualified (READY) ranks ahead of every one it did not.
     */
    fun cyclicRank8418(score: Double, confidencePct: Int?, nativeReady: Boolean): Double {
        val s = if (score.isFinite()) score else 0.0
        val conf = (confidencePct ?: 50).coerceIn(1, 100).toDouble()
        return s * (conf / 100.0) + (if (nativeReady) 1_000.0 else 0.0)
    }

    /**
     * Pure: the lanes whose own intake selected the coin and that hold no desk hypothesis for it — [hunterClaims] (the
     * resident hunter claims) plus PROJECT_SNIPER for a direct sniper-hunt [source]. Known lanes only.
     */
    fun hunterPoolLanes8418(hunterClaims: Collection<String>, hypothesisLanes: Collection<String>, sniperHuntSource: Boolean): Set<String> {
        val held = hypothesisLanes.map { it.trim().uppercase() }.toSet()
        val claimed = hunterClaims.map { it.trim().uppercase().replace("BLUE_CHIP", "BLUECHIP").replace("SHITCOIN_EXPRESS", "EXPRESS") }
            .toMutableSet()
        if (sniperHuntSource) claimed.add("PROJECT_SNIPER")
        return claimed.filter { it in SpecialistCandidateBooks7803.LANES && it !in held }.toSortedSet()
    }

    // ── runtime ──

    /** BotService.processTokenCycle, once the cycle primary is elected: the block-less lanes it did not let evaluate. */
    fun noteCycle8418(mint: String, primaryLane: String, positionOpen: Boolean) {
        if (positionOpen || mint.isBlank()) return
        for (l in notEvaluatedBlockless8418(lane8418(primaryLane))) {
            try { SpecialistOwnership7951.noteReadyNotEvaluated7951(l, mint, primaryLane) } catch (_: Throwable) {}
        }
    }

    /** BotService primary spine: a block-less primary that did not propose ends its qualified candidate by name. */
    fun notePrimarySpine8418(mint: String, primaryLane: String, positionOpen: Boolean, finalSignal: String?, canPropose: Boolean) {
        val why = primarySpineWhy8418(lane8418(primaryLane), positionOpen, finalSignal, canPropose) ?: return
        try {
            OwnershipChain8364.onNotEvaluated8364(lane8418(primaryLane), mint, why)
            PipelineHealthCollector.labelInc("LANE_DISPATCH_8418_$why")
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.8418 (phone run: CORE qualified 182 fdg=0/0, CYCLIC qualified 342 fdg=0/0) — lanes that own a per-token buy block
     * in processTokenCycle. When one of them is the cycle primary, it is evaluated by FDG in that block; the primary spine
     * (BotService "MEME_SPINE", the V3 strategy decision) then evaluated the SAME candidate again under the primary's lane.
     */
    private val OWN_BLOCK_LANES_8418: Set<String> = setOf(
        "TREASURY", "CASHGEN", "QUALITY", "BLUECHIP", "MOONSHOT", "SHITCOIN", "MANIPULATED", "EXPRESS", "PROJECT_SNIPER", "DIP_HUNTER",
    )

    /**
     * Pure: the lane the primary spine evaluates under. A block-less lane (CORE, then CYCLIC) whose own native brain
     * qualified the coin (READY) takes the spine when the primary is a lane with its own block (that lane is still
     * evaluated there) and the block-less lane passes the same lane-cycle gate every other lane passes ([allowed]: quarantine,
     * sealed owner, metric-stage safety, desk qualification, primary / rescue / hand-off / native slot). Otherwise the
     * primary, unchanged. Ordering only: the FDG, TradeAuthorizer, lane election, caps and every gate after still decide.
     */
    fun spineLanePure8418(primaryLane: String, positionOpen: Boolean, coreReady: Boolean, cyclicReady: Boolean,
                          allowed: (String) -> Boolean): String {
        if (positionOpen) return primaryLane
        val p = primaryLane.trim().uppercase()
        if (p !in OWN_BLOCK_LANES_8418) return primaryLane
        if (coreReady && allowed("CORE")) return "CORE"
        if (cyclicReady && allowed("CYCLIC")) return "CYCLIC"
        return primaryLane
    }

    /**
     * BotService primary spine: the lane it evaluates under ([spineLanePure8418], READY read from the specialist books),
     * with a block-less spine lane that did not propose ended by name (the former notePrimarySpine8418 call).
     */
    fun spineLane8418(mint: String, primaryLane: String, positionOpen: Boolean, finalSignal: String?, canPropose: Boolean,
                      allowed: (String) -> Boolean): String {
        val p = lane8418(primaryLane)
        val spine = try {
            spineLanePure8418(p, positionOpen, readyIn8418("CORE", mint), readyIn8418("CYCLIC", mint)) { l ->
                try { allowed(l) } catch (_: Throwable) { false }
            }
        } catch (_: Throwable) { p }
        val lane = if (spine == p) primaryLane else spine
        if (lane != primaryLane) try { PipelineHealthCollector.labelInc("LANE_DISPATCH_8418_SPINE_${lane}_OVER_$p") } catch (_: Throwable) {}
        notePrimarySpine8418(mint, lane, positionOpen, finalSignal, canPropose)
        return lane
    }

    private fun readyIn8418(lane: String, mint: String): Boolean = try {
        SpecialistCandidateBooks7803.entry(lane, mint)?.state == SpecialistCandidateBooks7803.State.READY
    } catch (_: Throwable) { false }

    /**
     * V5.0.8418 (phone run: PROJECT_SNIPER residentOwnLane7815=22, hunter claims=22, MARKET_HUNT_7301_EMITTED_PROJECT_SNIPER=9,
     * yet discovered=0 trafficSeen=false). The sheet-side POOL stamp ([hunterPoolLanes8418]) only fires when the coin's
     * toolkit sheet is built while the claim is live and in band; a launch picked inside its 3-minute window that is still
     * hydrating at intake (INTAKE PAIR_PENDING_HYDRATION) never gets that far, so the lane looked dead while its hunter was
     * selecting coins. The hunter's own selection is the lane's observation of the coin: POOL is stamped at publish, on
     * the candidate's causal id (the same one the sheet uses, so a later sheet stamp dedupes). Lanes listed in
     * [HUNT_POOL_LANES_8418] only. Bookkeeping: nothing here qualifies, admits or sizes.
     */
    private val HUNT_POOL_LANES_8418: Set<String> = setOf("PROJECT_SNIPER")

    /** Pure: does a hunter pick for [lane] stamp POOL at publish? */
    fun poolAtHunt8418(lane: String): Boolean = lane.trim().uppercase() in HUNT_POOL_LANES_8418

    fun notePoolAtHunt8418(lane: String, mint: String) {
        if (mint.isBlank() || !poolAtHunt8418(lane)) return
        try {
            val v = LaneExecutionCoordinator.candidateVersionFor(mint)
            ToolkitSignalSheet.recordDeskStage(lane.trim().uppercase(), "POOL", "$mint:$v")
            PipelineHealthCollector.labelInc("LANE_DISPATCH_8418_POOL_AT_HUNT_${lane.trim().uppercase()}")
        } catch (_: Throwable) {}
    }

    /** CyclicTradeEngine: is [mint] a CYCLIC native-qualified (READY) candidate right now? */
    fun cyclicReady8418(mint: String): Boolean = try {
        SpecialistCandidateBooks7803.entry("CYCLIC", mint)?.state == SpecialistCandidateBooks7803.State.READY
    } catch (_: Throwable) { false }

    /**
     * CyclicTradeEngine.tick: every READY CYCLIC candidate the ring did not take this tick ends by name ([why]: the ring
     * was holding, took another coin, or none passed its filters). A soft terminal: a later pick replaces it with the FDG.
     */
    fun noteCyclicRing8418(picked: String?, why: String) {
        try {
            val ready = SpecialistCandidateBooks7803.snapshot("CYCLIC").filter { it.state == SpecialistCandidateBooks7803.State.READY }
            for (e in ready) {
                if (e.mint == picked) continue
                OwnershipChain8364.onNotEvaluated8364("CYCLIC", e.mint, why)
            }
            if (ready.isNotEmpty()) PipelineHealthCollector.labelInc("CYCLIC_RING_8418_$why")
        } catch (_: Throwable) {}
    }
}
