package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8745 §AN_EXIT_THE_BOT_FORCED_IS_NOT_A_LESSON_ABOUT_THE_ENTRY — owner: "ensure that the learning is being correctly
 * across the learning metacognition ... all the brains" / "I want them really built really implemented and engineered".
 *
 * Every closed trade reaches the brains through CanonicalOutcomeBus: FluidLearningAI, the rich specialist adapters,
 * LayerVoteStore (MetaCognition's per-layer record) and the readiness recorder. Every close taught every one of them that
 * the ENTRY won or lost — including exits the bot's own machinery forced regardless of the coin: the sub-trader's flat
 * -15 % sweep (8743), shutdown liquidations, stale-feed evictions, risk-clock / dead-money / rotation culls, lost marks,
 * manual closes. Those measure the machinery, not the decision to buy.
 *
 * A MECHANICAL close is still counted, recorded and shown; it is withheld only from the strategy brains. What the entry
 * actually led to is learned where exits cannot touch it: the forward labels (ForwardReturnLabeler7731 -> EdgeModel8537,
 * TreeRanker8737, PocketLedger8610, PayingCell8723). A real crash (a catastrophe stop on a falling coin) stays a lesson.
 */
object OutcomeLesson8745 {
    /** Close-reason markers of exits the bot forced for its own reasons (upper-cased contains). */
    private val MECHANICAL_8745 = listOf(
        "RAPID_SUB_TRADER_HARD_FLOOR", "BOT_SHUTDOWN", "DATA_QUALITY_STALE", "STALE_FEED", "RISKCLOCK", "RISK_CLOCK",
        "DEAD_MONEY", "OPPORTUNITY_ROTATION", "LOST_MARK", "MANUAL", "WALLET_ZERO", "ZERO_BALANCE", "DUST_UNROUTABLE",
        "EXPIRED", "TIME_EXIT_SHUTDOWN", "RESTART",
    )

    /** Pure: did the bot's machinery force this close (not the coin, not the lane's exit plan)? */
    fun mechanical8745(closeReason: String?): Boolean {
        val r = closeReason?.trim()?.uppercase().orEmpty()
        if (r.isEmpty()) return false
        return MECHANICAL_8745.any { r.contains(it) }
    }

    private val withheld = ConcurrentHashMap<String, AtomicLong>()
    private val taught = AtomicLong(0L)

    /** CanonicalOutcomeBus: may this close teach the strategy brains? Counts both ways. */
    fun teaches8745(closeReason: String?): Boolean {
        if (!mechanical8745(closeReason)) { taught.incrementAndGet(); return true }
        val key = closeReason.orEmpty().uppercase().let { r -> MECHANICAL_8745.firstOrNull { r.contains(it) } ?: "?" }
        if (withheld.size < 64 || withheld.containsKey(key)) withheld.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
        return false
    }

    fun statusLine8745(): String = "outcomeLesson8745[taught=${taught.get()} withheldMechanical{" +
        withheld.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } +
        "} rule=a close the bot's machinery forced never teaches the entry; forward labels carry what the coin did]"
}
