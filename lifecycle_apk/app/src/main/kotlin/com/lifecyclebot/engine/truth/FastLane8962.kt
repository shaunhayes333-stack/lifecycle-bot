package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8962 §THE_FAST_LANE_BELONGS_TO_THE_SIGNALS
 *
 * Report 23 Nov 22:07 (5.0.8961, LIVE, 37 min): FAST_LANE_EVALUATED_7277=537 against FAST_LANE_SATURATED_7277=4322. The six
 * event slots that run a coin's pipeline the moment a signal fires (a launch rule's armed fire, the 20th / 40th / 80th print,
 * a proven coin's evidence wait, a copy signal) were refused 89 % of the time. The demand was chain answers: 8957 / 8959 put
 * the UNKNOWN-stage read among the uses whose price/depth answer re-runs the coin (chainConfirm8950 STAGE:PRICE_DEPTH=7276,
 * marketAnswers8959=4954), so a coin nobody was deciding on took a slot ahead of a coin at the gate. Two changes:
 *  - a stage read is not a decision ping (ChainConfirm8950.RERUN_USES_8957); its own history answer still re-runs the coin
 *    through StageRead8950.direct8950, as before 8957;
 *  - the chain-answer re-runs that remain (a decision, lane, evidence or exec wait) hold at most [CHAIN_ANSWER_SLOTS_8962] of
 *    the six slots at once, so the signals always have the rest.
 * Every request is counted by its origin: ran, or refused for want of a slot. No gate, bar or threshold changes.
 */
object FastLane8962 {
    const val CHAIN_ANSWER_ORIGIN_8962 = "CHAIN_ANSWER_8957"
    const val CHAIN_ANSWER_SLOTS_8962 = 2
    private const val MAX_ORIGINS_8962 = 48

    private val chainAnswerSlots = Semaphore(CHAIN_ANSWER_SLOTS_8962)
    private val ran = ConcurrentHashMap<String, AtomicLong>()
    private val refused = ConcurrentHashMap<String, AtomicLong>()
    private val yielded = AtomicLong(0L)

    /** Pure: the counter key of [origin] (a print milestone's number is kept; anything past the cap is OTHER). */
    internal fun key8962(origin: String, known: Set<String>): String {
        val o = origin.trim().uppercase().ifBlank { "UNNAMED" }
        return if (o in known || known.size < MAX_ORIGINS_8962) o else "OTHER"
    }

    /** A chain answer takes one of its own slots first; every other origin enters freely. False = yield, nothing held. */
    fun enter8962(origin: String): Boolean {
        if (origin != CHAIN_ANSWER_ORIGIN_8962) return true
        if (chainAnswerSlots.tryAcquire()) return true
        yielded.incrementAndGet()
        return false
    }

    /** Releases what [enter8962] took for [origin]. Call exactly once per successful enter. */
    fun leave8962(origin: String) {
        if (origin == CHAIN_ANSWER_ORIGIN_8962) chainAnswerSlots.release()
    }

    fun note8962(origin: String, gotSlot: Boolean) {
        val book = if (gotSlot) ran else refused
        book.computeIfAbsent(key8962(origin, book.keys)) { AtomicLong(0L) }.incrementAndGet()
    }

    internal fun chainAnswerSlotsFree8962(): Int = chainAnswerSlots.availablePermits()

    fun statusLine8962(): String {
        fun show(m: Map<String, AtomicLong>) = m.entries.sortedByDescending { it.value.get() }.take(12)
            .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "fastLane8962[ran{${show(ran)}} noSlot{${show(refused)}} chainAnswerYielded=${yielded.get()} " +
            "rule=six event slots; chain-answer re-runs hold at most $CHAIN_ANSWER_SLOTS_8962, an UNKNOWN-stage read is not a decision ping]"
    }

    internal fun resetForTest8962() {
        ran.clear(); refused.clear(); yielded.set(0L)
        chainAnswerSlots.drainPermits(); chainAnswerSlots.release(CHAIN_ANSWER_SLOTS_8962)
    }
}
