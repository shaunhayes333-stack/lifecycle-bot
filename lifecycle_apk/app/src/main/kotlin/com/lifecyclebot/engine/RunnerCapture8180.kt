package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.market.LaneHunter7297
import com.lifecyclebot.v3.scoring.MoonshotTraderAI
import com.lifecyclebot.v3.scoring.ShitCoinTraderAI

/**
 * V5.0.8180 §STOP_MISSING_RUNNERS — hand-off routing (owner, 5.0.8175: 11 runners, 10 missed).
 *
 * Causes read in code:
 *  - 211 of 335 receiver tasks expired DEFERRED_NO_TOKEN_STATE. The crypto desk (CryptoLaneDesk7391) runs the same native
 *    brains on its own TokenState keyed by its desk identity ("solana|<mint>"), so PROJECT_SNIPER's TOO_OLD / phase
 *    hand-off fired for desk coins (birth8144 unknownMints JUPITER_STRICT/other=405). Those identities have no meme
 *    TokenState and no meme cycle: the task could only wait for a state that never comes. A desk identity no longer
 *    opens a meme hand-off; a receiver looks its token up by the meme key too ([tokenFor8180]).
 *  - 53 arrivals were refused by the named lane's OWN floors (LANE_LIQ_BELOW_FLOOR MOONSHOT=36, MCAP_TOO_LOW=17): the
 *    sniper named MOONSHOT for every coin above MOONSHOT's cap floor ($1.5k cold start — below a pump coin's starting
 *    cap) without reading MOONSHOT's liquidity floor, and the settled refusal then held the mint for ten minutes, so the
 *    same coin grown into the band was never handed on again. The hand-off now names the runner lane whose own floors
 *    the coin meets now (unknown readings never refuse: the lanes defer on them), and is withheld while none does, so
 *    the first hand-off is emitted when a lane can take it. Every floor stays exactly where it is.
 */
object HandoffRoute8180 {

    /** [withhold] = no runner lane's floors are met now (no hand-off emitted); else [named] / [reason] to emit. */
    data class Route8180(val withhold: Boolean, val named: String?, val reason: String)

    private const val SOLANA_PREFIX_8180 = "solana|"
    private val RUNNER_LANES_8180 = listOf("MOONSHOT", "SHITCOIN")

    /** Pure: not a bare Solana meme mint — the crypto desk's "chain|address", or a "static:" / "cg:" asset id. */
    fun deskIdentity8180(mint: String): Boolean = mint.contains('|') || mint.contains(':')

    /** Pure: the meme-pipeline key of [mint] ("solana|X" is the desk's name for meme mint X). */
    fun memeKey8180(mint: String): String =
        if (mint.startsWith(SOLANA_PREFIX_8180)) mint.removePrefix(SOLANA_PREFIX_8180) else mint

    /** The TokenState the meme cycle reads for [mint] (the same map the sender's cycle used), or null. */
    fun tokenFor8180(mint: String): TokenState? = try {
        val tokens = BotService.status.tokens
        synchronized(tokens) {
            val bare = memeKey8180(mint)
            tokens[mint] ?: tokens[bare] ?: if (!deskIdentity8180(bare)) tokens["$SOLANA_PREFIX_8180$bare"] else null
        }
    } catch (_: Throwable) { null }

    /** Pure: a reading meets a floor. Unknown (<= 0 / non-finite) never refuses; a real reading must reach it. */
    fun meets8180(value: Double, floor: Double): Boolean =
        !(value.isFinite() && value > 0.0) || !(floor.isFinite() && floor > 0.0) || value >= floor

    /**
     * Pure: the receiver a runner hand-off names. A lane other than MOONSHOT / SHITCOIN (EXPRESS at migration, a named
     * specialist) is kept as named. The named runner lane is kept when it fits; else the other runner lane that fits
     * (the reason's "_TO_<lane>" follows); else the hand-off is withheld.
     */
    fun route8180(named: String?, reason: String, fits: (String) -> Boolean): Route8180 {
        val n = named?.trim()?.uppercase().orEmpty()
        if (n.isBlank() || n !in RUNNER_LANES_8180 || fits(n)) return Route8180(false, named, reason)
        val alt = RUNNER_LANES_8180.firstOrNull { it != n && fits(it) } ?: return Route8180(true, null, reason)
        return Route8180(false, alt, reason.replace("_TO_$n", "_TO_$alt"))
    }

    /** Does [ts] meet [lane]'s own cap / liquidity floors now (the same reads that lane's scorer makes)? */
    internal fun laneFits8180(lane: String, ts: TokenState): Boolean = try {
        val live = try { RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }
        val cap = ts.lastMcap
        val hydrated = try { com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { ts.lastLiquidityUsd }
        when (lane) {
            "MOONSHOT" -> {
                // MoonshotTraderAI.scoreToken: cap band first (an unknown cap is below its floor), then the observed pool.
                val liq = com.lifecyclebot.engine.truth.LiquidityTruth8037.latest8418(hydrated, try { TokenMapAuthority.observedLiquidityUsd(ts) } catch (_: Throwable) { 0.0 })  // V5.0.8418
                cap.isFinite() && cap >= MoonshotTraderAI.minMarketCapUsdFluid7719() && cap <= MoonshotTraderAI.MAX_MARKET_CAP_USD &&
                    meets8180(liq, LaneAgeLiquidityScope8140.effectiveLiquidityFloor8140(MoonshotTraderAI.minLiquidityUsdNow8180(), live))
            }
            "SHITCOIN" ->
                meets8180(cap, LaneHunter7297.floorFor("SHITCOIN", ShitCoinTraderAI.MIN_MARKET_CAP_USD)) &&
                    !(cap.isFinite() && cap > LaneHunter7297.ceilingFor("SHITCOIN", ShitCoinTraderAI.MAX_MARKET_CAP_USD)) &&
                    meets8180(hydrated, LaneAgeLiquidityScope8140.effectiveLiquidityFloor8140(ShitCoinTraderAI.getFluidMinLiquidity(), live))
            else -> true
        }
    } catch (_: Throwable) { true }

    /** AdmissionHandoff8144.emitHandoff8144: the route for [mint]'s hand-off (no meme TokenState: unchanged). */
    fun routeFor8180(mint: String, named: String?, reason: String): Route8180 {
        val ts = tokenFor8180(mint) ?: return Route8180(false, named, reason)
        return route8180(named, reason) { laneFits8180(it, ts) }
    }
}

/**
 * V5.0.8180 — a rescue slot claimed and never evaluated no longer holds the candidate. 5.0.8175 rescueFunnel: 165 claims,
 * 35 scheduled, 1 evaluated, heldDenied=2,141 — a claim that never reached its FinalDecisionGate kept every other
 * qualified lane out for the rest of the version (X +813% ended SHADOW_NOT_PRIMARY_8029). A holder still unconsumed
 * [RESCUE_IDLE_MS_8180] after its claim is idle: the next qualified claimant takes the slot through the same arbitration
 * (best own-scale lane, token safety, receiver record). A consumed rescue (FDG verdict) still ends the version.
 */
object RescueIdle8180 {
    /** Longer than a lineage roll's in-flight rescue (30 s), shorter than the record's 120 s life. */
    const val RESCUE_IDLE_MS_8180 = 45_000L

    /** Pure: the rescue holder claimed at [claimedAtMs] has produced no FDG verdict for longer than the idle window. */
    fun idle8180(consumed: Boolean, claimedAtMs: Long, nowMs: Long): Boolean =
        !consumed && claimedAtMs > 0L && nowMs - claimedAtMs > RESCUE_IDLE_MS_8180
}

/**
 * V5.0.8180 — the cycle primary's native refusal names the lane that wants the coin. Only a lane-FIT refusal (8132) named
 * a receiver, so a primary's own judgement (EXPRESS NO_CONTINUATION on Grok Bot +891%, MANIPULATED "no manipulation
 * evidence" on X +802%) stopped the candidate while another lane had qualified it natively (trace8144
 * QUALIFIED_LANE_STOPPED=5). A primary's judgement is about ITS strategy; another lane's eligibility is independent
 * evidence. Every graded refusal that is not binding (safety / rug / dev / bundle / freeze / toxic) and not a data gap
 * now names the top eligible lane; that receiver's own gates, the FDG, safety and sizing still decide.
 */
object NativeYield8180 {
    fun namesReceiver8180(reason: String?, gradeable: Boolean, fit: Boolean, binding: Boolean, data: Boolean): Boolean =
        !reason.isNullOrBlank() && !binding && !data && (fit || gradeable)
}

/**
 * V5.0.8180 — a data deferral asks for exactly what it is missing. LiveEdgeGate7877.dataDefer8136 (the playbook's
 * DATA_INCOMPLETE) only spent its re-evaluation budget: nothing requested the bars / flow / liquidity it waited for
 * (TZERO +1,884% ended DATA_INCOMPLETE_DEFER_8136), and the admission's "price" / "economics" / "core" names matched no
 * input the refresher knows. Each name maps to the inputs DataOrchestrator.refreshMissingInputs8087 fetches.
 */
object DataDeferFetch8180 {
    // 8211: a near-decision deferred pump token gets a bounded, keyed print subscription.
    // Admission does not use unknown tape as positive evidence; only real incoming trades complete the feature.
    private val urgentStreamAt8211 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    internal fun streamInputNeeded8211(inputs: List<String>): Boolean = inputs.any {
        it.substringBefore(':').trim().lowercase() in setOf("bp", "chg", "shape", "tape")
    }
    /** Pure: the refresher inputs for an admission / playbook "missing" list. */
    fun inputsFor8180(missing: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (m in missing) {
            when (m.substringBefore(':').trim().lowercase()) {
                "" -> {}
                "liquidity", "depth" -> out += "liq:" + m.substringAfter(':', "UNKNOWN")
                "price", "economics" -> { out += "liq:REFRESH"; out += "chg:UNOBSERVED" }
                "core" -> { out += "shape:HISTORY_SPARSE"; out += "chg:UNOBSERVED"; out += "bp:UNOBSERVED" }
                "bars", "candles", "history" -> out += "shape:HISTORY_SPARSE"
                else -> out += m.trim()
            }
        }
        return out.toList()
    }

    /** Requests [missing] for [ts] (bounded per mint by the playbook's 30 s request gap); depth and a fresh mark too. */
    fun request8180(ts: TokenState, missing: List<String>, nowMs: Long = System.currentTimeMillis()) {
        val inputs = inputsFor8180(missing)
        if (inputs.isEmpty()) return
        if (streamInputNeeded8211(inputs) && ts.mint.endsWith("pump") &&
            (try { !com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false })) {
            val attempt8211 = synchronized(urgentStreamAt8211) {
                val prev = urgentStreamAt8211[ts.mint] ?: 0L
                if (nowMs - prev >= 45_000L || prev <= 0L) {
                    urgentStreamAt8211[ts.mint] = nowMs
                    true
                } else false
            }
            if (attempt8211) try {
                val subscribed = com.lifecyclebot.network.PumpFunWS.watchForSetup8191(ts.mint, nowMs)
                PipelineHealthCollector.labelInc(if (subscribed) "DATA_DEFER_URGENT_STREAM_SUBSCRIBED_8211" else "DATA_DEFER_URGENT_STREAM_UNAVAILABLE_8211")
            } catch (_: Throwable) {
                try { PipelineHealthCollector.labelInc("DATA_DEFER_URGENT_STREAM_FAILED_8211") } catch (_: Throwable) {}
            }
            if (urgentStreamAt8211.size > 4096) urgentStreamAt8211.entries.removeIf { nowMs - it.value > 900_000L }
        }
        try { com.lifecyclebot.engine.cortex.LanePlaybook7907.requestEvidence8153(ts, inputs, nowMs) } catch (_: Throwable) {}
        if (inputs.any { it.startsWith("liq:") }) try { com.lifecyclebot.engine.truth.OnChainDepth8038.needDepth8047(ts, nowMs) } catch (_: Throwable) {}
        if (inputs.any { it.startsWith("chg:") }) try { com.lifecyclebot.engine.truth.CandidateMarkFresh8094.ensureFresh8094(ts, nowMs) } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("DATA_DEFER_FETCH_REQUESTED_8180") } catch (_: Throwable) {}
    }
}
