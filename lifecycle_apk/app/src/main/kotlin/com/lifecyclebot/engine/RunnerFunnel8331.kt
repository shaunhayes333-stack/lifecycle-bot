package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8331 — THE RUNNER FUNNEL, stage by stage (pure rules + once-per-position counters; no Android, no clock).
 *
 * Device, 5.0.8326 (24 h): 38 observed 4x+ runners, 6 held (all paper), none live, runner rung fills 0. The runner
 * report could say a runner was held and that no rung banked, but not WHERE between the two it stopped. Each stage of
 * a held runner's monetisation now counts once per position (key = mint|entryTime):
 *
 *   held            a LIVE position opened in a lane that can host a runner (RunnerLive8153.plan8153, first read)
 *   planSealed      its one plan (RunnerPlan8153) read RUNNER_SEALED / RUNNER_PROMOTED
 *   rungTriggered   the authoritative mark reached a runner rung above the confirmed one (SpikeCapture7943.onMark)
 *   rungArmed       the rung's tranche was REQUESTED (RunnerRungBook8153: not pending, not in backoff)
 *   sellSent        the tranche reached the sell door (TICKET_ACCEPTED)
 *   sellLanded      the chain-confirmed fill of a runner rung (Executor.commitVerifiedLiveSlice7835 -> bookSlice8153)
 *   proceedsBooked  its proceeds were booked on finality (once per signature)
 *   blockedWhy      why a triggered rung did not advance: OFFCHAIN_MARK, TICKET_HELD, PENDING_OR_BACKOFF, BUDGET_REFUSED,
 *                   SELL_NOT_APPLIED (executor refused / did not land), ARBITER_SUPERSEDED
 *
 * Three pure rules live here too:
 *  - [rungMarkAuthoritative8331]: a rung on an un-graduated pump curve coin reads the coin's own curve print (the
 *    mark the position lifecycle uses), never an off-chain poll that happened to be the row's freshest tick;
 *  - [ladderForPlan8331]: the rung ladder follows the one plan for the position's life (RunnerGrab7967.tiersFor7967
 *    fell back to the scalp ladder once the 6 h hold window lapsed while the plan still said RUNNER);
 *  - [urgentPreemptsHold8331]: a rug / catastrophic / hard-stop / tier-1 exit is never held by a runner hold.
 *
 * The launch ladder (TradePlan7739 LAUNCH_EARLY) counts beside it: [launchLine8331] armed / firstHit / targetHit.
 */
object RunnerFunnel8331 {
    enum class Stage8331 { HELD, PLAN_SEALED, RUNG_TRIGGERED, RUNG_ARMED, SELL_SENT, SELL_LANDED, PROCEEDS_BOOKED }

    private const val MAX_SEEN_8331 = 6_000
    private const val MAX_BLOCK_KEYS_8331 = 24

    /** Mark sources that are the coin's own curve / chain print (StopFeed8327.CHAIN_SOURCES_8327 and the curve seeds). */
    val CURVE_SOURCES_8331: List<String> = listOf("PUMP_CURVE", "PUMP_PORTAL_TRADE_WS_7278", "PUMPSWAP_VAULT_WS_8018", "HELIUS")

    private val stages = ConcurrentHashMap<Stage8331, AtomicLong>()
    private val blocked = ConcurrentHashMap<String, AtomicLong>()
    private val seen = ConcurrentHashMap<String, Boolean>()
    private val launch = ConcurrentHashMap<String, AtomicLong>()

    private fun once8331(tag: String): Boolean {
        if (tag.isBlank()) return true
        if (seen.size > MAX_SEEN_8331 && !seen.containsKey(tag)) seen.clear()
        return seen.putIfAbsent(tag, true) == null
    }

    /** Count [s] for the position [key] once (every call counts when [key] is blank). True when counted. */
    fun stage8331(s: Stage8331, key: String = ""): Boolean {
        if (!once8331(if (key.isBlank()) "" else "$key|${s.name}")) return false
        stages.computeIfAbsent(s) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("RUNNER_FUNNEL_8331_${s.name}") } catch (_: Throwable) {}
        return true
    }

    /** A triggered rung did not advance because of [why] (once per [key]|[why] when a key is given). */
    fun blocked8331(why: String, key: String = ""): Boolean {
        if (why.isBlank()) return false
        if (!once8331(if (key.isBlank()) "" else "$key|B|$why")) return false
        if (blocked.size < MAX_BLOCK_KEYS_8331 || blocked.containsKey(why)) blocked.computeIfAbsent(why) { AtomicLong(0) }.incrementAndGet()
        return true
    }

    fun count8331(s: Stage8331): Long = stages[s]?.get() ?: 0L

    fun blockedCount8331(why: String): Long = blocked[why]?.get() ?: 0L

    // ── pure rules ──

    /**
     * Pure: may a rung trigger on a mark from [source]? A coin still on its pump curve ([curveCoin]) is marked by its own
     * curve print (PumpPortal trade, curve account, PumpSwap vault, Helius trade); an off-chain poll of it is not a price
     * the position lifecycle would sell on. A graduated / DEX coin's freshest row mark stands.
     */
    fun rungMarkAuthoritative8331(curveCoin: Boolean, source: String): Boolean {
        if (!curveCoin) return true
        val s = source.trim().uppercase()
        if (s.isBlank()) return false
        return CURVE_SOURCES_8331.any { s.contains(it) }
    }

    /** Pure: the rung ladder of [plan] — the runner ladder for a sealed / promoted runner, none (scalp / learned) otherwise. */
    fun ladderForPlan8331(plan: RunnerPlan8153.Plan8153): List<Pair<Double, Double>>? =
        if (plan == RunnerPlan8153.Plan8153.SCALP) null else RunnerGrab7967.TIERS_7967

    /**
     * Pure: an exit no runner hold may delay — tier 1 (rug, drain, sellability, freeze), capital preservation
     * (catastrophe, wallet drain, kill switch), a rug / catastrophic name, or the runner's own hard stop.
     */
    fun urgentPreemptsHold8331(reason: String): Boolean {
        val r = reason.trim().uppercase()
        if (r.isBlank()) return false
        if (RunnerExitContract8136.emergency8136(r)) return true
        if (com.lifecyclebot.engine.sell.ProtectiveExitClass7807.of(r) == com.lifecyclebot.engine.sell.ProtectiveExitClass7807.Priority.CAPITAL_PRESERVATION) return true
        return r.contains("RUG") || r.contains("CATASTROPH") || r.contains("RUNNER_HARD_STOP") || r.contains("HARD_FLOOR")
    }

    // ── launch ladder ──

    /** TradePlan7739: a LAUNCH_EARLY plan was sealed to a position ("armed"), banked its half ("firstHit"), or its target ("targetHit"). */
    fun launch8331(what: String, key: String = ""): Boolean {
        if (what.isBlank()) return false
        if (!once8331(if (key.isBlank()) "" else "$key|L|$what")) return false
        launch.computeIfAbsent(what) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LAUNCH_LADDER_8331_${what.uppercase()}") } catch (_: Throwable) {}
        return true
    }

    fun launchCount8331(what: String): Long = launch[what]?.get() ?: 0L

    fun launchLine8331(): String =
        "launchLadder8331[armed=${launchCount8331("armed")} firstHit=${launchCount8331("firstHit")} targetHit=${launchCount8331("targetHit")}]"

    // ── report ──

    fun statusLine8331(): String {
        val why = blocked.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "runnerFunnel8331[held=${count8331(Stage8331.HELD)} planSealed=${count8331(Stage8331.PLAN_SEALED)} " +
            "rungArmed=${count8331(Stage8331.RUNG_ARMED)} rungTriggered=${count8331(Stage8331.RUNG_TRIGGERED)} " +
            "sellSent=${count8331(Stage8331.SELL_SENT)} sellLanded=${count8331(Stage8331.SELL_LANDED)} " +
            "proceedsBooked=${count8331(Stage8331.PROCEEDS_BOOKED)} blockedWhy={$why}]"
    }

    internal fun resetForTest8331() {
        stages.clear(); blocked.clear(); seen.clear(); launch.clear()
    }
}
