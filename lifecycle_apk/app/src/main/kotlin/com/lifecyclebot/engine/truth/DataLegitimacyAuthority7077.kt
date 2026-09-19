package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7077 §QUALIFY THE DATA BEFORE THE TRADE, NEVER CAP THE RESULT AFTER IT.
 *
 * Operator, twice, the second time as a correction: "we dont cap or limit wins.
 * we quantify the data and trade as legitimate before opening or closing it."
 *
 * WHY EVERY BUILD BEFORE THIS ONE WAS THE WRONG SHAPE
 * ==================================================
 * Look at what this session actually shipped, in order:
 *
 *   V5.0.6895   cross-source marks refused above 10x
 *   V5.0.7017   refused ticks reconciled through market cap
 *   V5.0.7059   that reconciliation made universal, band 3x
 *   V5.0.7068   band tightened to 1.25x
 *   V5.0.7076   partial sale priced on the cap when the tick is >1.25x off it
 *
 * Five builds, one argument, restated five times: HOW BIG A MOVE IS TOO BIG TO
 * BELIEVE? There is no answer to that question, because size is not evidence.
 * A memecoin that goes 1000x in four minutes and a feed that reports a price on
 * the wrong decimal basis produce the identical number, and no threshold
 * separates them — every band that refuses the bug also refuses the runner, and
 * runner capture is the single thing this app exists to do. V5.0.7068's 1.25
 * band took 37 of 41 partials with it. That is the cost of answering an
 * unanswerable question.
 *
 * The question that IS answerable is asked earlier, about the data instead of
 * the move:
 *
 *     DO I KNOW WHAT ONE OF THESE TOKENS IS WORTH, FROM MEASURED FACTS,
 *     WELL ENOUGH TO PUT REAL MONEY THROUGH IT?
 *
 * That has a yes/no answer and it does not depend on the magnitude of anything.
 * Chain supply either resolved or it did not. price x supply either equals the
 * reported cap or it does not. The mint either has a recorded entry cap or it
 * does not. Ask it BEFORE opening, and the position can only ever exist on a
 * token whose economics are measured — at which point the exit needs no band at
 * all, because a 1000x on a qualified mint is a real 1000x and gets booked in
 * full. Uncapped, unlimited, and honest, in that order and for that reason.
 *
 * WHAT QUALIFICATION IS
 * =====================
 *   1. Chain supply resolved      OnChainSupplyAuthority7075.getTokenSupply
 *   2. A reported cap             from the provider payload, > 0
 *   3. A reported price           from the provider payload, > 0
 *   4. price x supply == cap      to IDENTITY_EPSILON, the same identity
 *                                 V5.0.7069 enforces, asked here as a question
 *                                 rather than applied there as a repair
 *
 * Every one of those is a measurement. None is inferred, none is a threshold on
 * a price move, and none of them can be satisfied by a number the app made up.
 * V5.0.7075 deleted the last inferred value in the chain (supply derived as
 * mcap/price, which verified a price against itself); this object is what that
 * deletion was for.
 *
 * WHAT HAPPENS TO A MINT THAT DOES NOT QUALIFY — and this is the part that is
 * NOT an exclusion. Operator, earlier: "nothing should ever go stale, un priced,
 * lost, excluded for bad data! no exceptions." PENDING means the chain call is
 * in flight; the candidate is DEFERRED and re-elected next cycle, when the
 * answer has landed. It is not dropped, not blacklisted, not marked bad. The
 * only thing that changes is that money does not move until the facts do.
 *
 * ARMING, AND WHY IT IS NOT OPTIONAL
 * ==================================
 * A gate that refuses everything at once is indistinguishable from an outage —
 * V5.0.7068 is the proof, and V5.0.7075 wrote the warning into
 * TokenMetricsAuthority7069 rather than ship the gate blind. So the gate reads
 * its own supporting evidence before it is allowed to refuse anything:
 *
 *   rpc installed, MIN_SAMPLE resolve attempts made, and at least
 *   MIN_RESOLVE_RATE of them answered
 *
 * Below that bar this object still classifies every mint and counts what it
 * WOULD have done, and blocks nothing. A refusal then always means "this mint's
 * data is bad", never "the supply resolver is not running yet" — which is the
 * distinction V5.0.7068 could not make and was destroyed by.
 */
object DataLegitimacyAuthority7077 {

    /**
     * Rounding, not tolerance. Price, cap and supply describe one instant, so
     * they agree to several decimal places or one of them is wrong. Identical
     * to TokenMetricsAuthority7069's constant deliberately: the same identity
     * must not be able to hold in one authority and break in the other.
     */
    const val IDENTITY_EPSILON = 0.005

    /** Resolve attempts required before a refusal can mean anything. */
    private const val MIN_SAMPLE = 200L

    /** Share of those attempts that must have answered. */
    private const val MIN_RESOLVE_RATE = 0.60

    enum class Verdict {
        /** Measured facts agree. Trade it, at whatever the mark says. */
        QUALIFIED,

        /** Chain state has not answered yet. Defer, re-elect, do not drop. */
        PENDING,

        /** Facts are present and contradict each other. Do not open. */
        REJECTED,
    }

    data class Qualification(
        val verdict: Verdict,
        val reason: String,
        val priceUsd: Double,
        val mcapUsd: Double,
        val supplyTokens: Double,
    )

    /**
     * The most recent mark that passed qualification, per mint. This is what an
     * exit uses when the mark in hand does not qualify: a price that WAS
     * measured, rather than a price derived from a band around the basis. It
     * carries no ceiling — if the last qualified mark was a 1000x, the exit
     * books a 1000x.
     */
    data class QualifiedMark(val priceUsd: Double, val mcapUsd: Double, val supplyTokens: Double, val atMs: Long)

    private val lastQualified = ConcurrentHashMap<String, QualifiedMark>()

    /** Mints whose data qualified at the moment their position was opened. */
    private val qualifiedAtOpen = ConcurrentHashMap<String, Long>()

    private val discovered = AtomicLong(0L)
    private val asked = AtomicLong(0L)
    private val qualified = AtomicLong(0L)
    private val pending = AtomicLong(0L)
    private val rejected = AtomicLong(0L)
    private val wouldRefuseWhileDisarmed = AtomicLong(0L)
    private val opensDeferred = AtomicLong(0L)
    private val opensRefused = AtomicLong(0L)
    private val opensOnQualified = AtomicLong(0L)

    /**
     * A mint has been seen for the first time. Ask chain state for its supply
     * NOW, at discovery, so the answer is on file by the time anything wants to
     * trade it.
     *
     * This is the coverage fix. V5.0.7075 requested supply lazily, from the
     * mark path, which only runs for tokens already being priced for a
     * position — so the 5.0.7072 device saw 2518 live rows and 63 supplies.
     * Discovery runs for all 2518. The call is async, deduplicated per mint and
     * dropped entirely when the RPC is not installed, so intake never waits.
     */
    fun noteDiscovery7077(mint: String) {
        if (mint.isBlank()) return
        discovered.incrementAndGet()
        try { OnChainSupplyAuthority7075.requestAsync7075(mint) } catch (_: Throwable) {}
    }

    /**
     * Is the gate standing on enough evidence to refuse anything? See the
     * ARMING note in the class doc — this is the guard against reproducing
     * V5.0.7068, where a gate that could not tell "bad token" from "resolver
     * not running" refused 37 of 41 partials.
     */
    fun isArmed7077(): Boolean {
        if (!OnChainSupplyAuthority7075.rpcReady7075()) return false
        val resolved = OnChainSupplyAuthority7075.resolvedCount7075()
        val failed = OnChainSupplyAuthority7075.failedCount7075()
        val attempts = resolved + failed
        if (attempts < MIN_SAMPLE) return false
        return resolved.toDouble() / attempts.toDouble() >= MIN_RESOLVE_RATE
    }

    /**
     * Quantify [mint] from the numbers in hand. Pure classification — this
     * changes no price, writes no field and caps nothing.
     */
    fun qualify7077(mint: String, priceUsd: Double, mcapUsd: Double): Qualification {
        asked.incrementAndGet()
        val price = if (priceUsd.isFinite() && priceUsd > 0.0) priceUsd else 0.0
        val mcap = if (mcapUsd.isFinite() && mcapUsd > 0.0) mcapUsd else 0.0

        val supply = try { OnChainSupplyAuthority7075.supplyOf7075(mint) } catch (_: Throwable) { 0.0 }
        if (supply <= 0.0) {
            try { OnChainSupplyAuthority7075.requestAsync7075(mint) } catch (_: Throwable) {}
            pending.incrementAndGet()
            return Qualification(Verdict.PENDING, "onchain_supply_unresolved", price, mcap, 0.0)
        }
        if (price <= 0.0) {
            pending.incrementAndGet()
            return Qualification(Verdict.PENDING, "no_price_reported", 0.0, mcap, supply)
        }
        if (mcap <= 0.0) {
            pending.incrementAndGet()
            return Qualification(Verdict.PENDING, "no_market_cap_reported", price, 0.0, supply)
        }

        val implied = mcap / supply
        if (!implied.isFinite() || implied <= 0.0) {
            pending.incrementAndGet()
            return Qualification(Verdict.PENDING, "implied_price_not_finite", price, mcap, supply)
        }
        val ratio = price / implied
        if (ratio in (1.0 - IDENTITY_EPSILON)..(1.0 + IDENTITY_EPSILON)) {
            qualified.incrementAndGet()
            lastQualified[mint] = QualifiedMark(price, mcap, supply, System.currentTimeMillis())
            return Qualification(Verdict.QUALIFIED, "identity_holds", price, mcap, supply)
        }

        rejected.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("DATA_ILLEGITIMATE_7077")
            if (rejected.get() % 25L == 1L) {
                ForensicLogger.lifecycle(
                    "DATA_ILLEGITIMATE_7077",
                    "mint=${mint.take(10)} reportedPrice=$price impliedPrice=$implied " +
                        "mcap=${mcap.toLong()} onChainSupply=${supply.toLong()} " +
                        "ratio=${"%.6g".format(ratio)} " +
                        "action=not_tradeable_until_price_and_cap_agree",
                )
            }
        } catch (_: Throwable) {}
        return Qualification(Verdict.REJECTED, "identity_broken_ratio=${"%.4g".format(ratio)}", price, mcap, supply)
    }

    /** The last mark that passed qualification for [mint], if there was one. */
    fun lastQualifiedMark7077(mint: String): QualifiedMark? = lastQualified[mint]

    /**
     * Should an OPEN proceed on this mint? Returns null to proceed, or the
     * reason to hold. Holding is a DEFER: the caller releases its lane election
     * and the candidate is re-evaluated next cycle, by which time the chain
     * call has normally landed.
     */
    fun holdOpenReason7077(mint: String, priceUsd: Double, mcapUsd: Double): String? {
        val q = qualify7077(mint, priceUsd, mcapUsd)
        if (q.verdict == Verdict.QUALIFIED) {
            qualifiedAtOpen[mint] = System.currentTimeMillis()
            opensOnQualified.incrementAndGet()
            try { PipelineHealthCollector.labelInc("OPEN_ON_QUALIFIED_DATA_7077") } catch (_: Throwable) {}
            return null
        }
        if (!isArmed7077()) {
            wouldRefuseWhileDisarmed.incrementAndGet()
            try { PipelineHealthCollector.labelInc("OPEN_WOULD_HOLD_DISARMED_7077") } catch (_: Throwable) {}
            return null
        }
        if (q.verdict == Verdict.PENDING) {
            opensDeferred.incrementAndGet()
            try { PipelineHealthCollector.labelInc("OPEN_DEFERRED_PENDING_DATA_7077") } catch (_: Throwable) {}
        } else {
            opensRefused.incrementAndGet()
            try { PipelineHealthCollector.labelInc("OPEN_REFUSED_ILLEGITIMATE_DATA_7077") } catch (_: Throwable) {}
        }
        return "${q.verdict}:${q.reason}"
    }

    /** True when this mint's position was opened on data that qualified. */
    fun openedOnQualifiedData7077(mint: String): Boolean = qualifiedAtOpen.containsKey(mint)

    /**
     * The price a CLOSE should be booked at.
     *
     * Exits are never blocked — a position that cannot be priced perfectly is
     * still a position that must be allowed to leave, and trapping capital
     * behind a data check would be a worse defect than the one being fixed.
     * What the exit is not allowed to do is book a number the app has proven
     * wrong. In order:
     *
     *   1. the mark, when it qualifies                    — used in full, at any
     *                                                       magnitude, no band
     *   2. cap / on-chain supply, when supply is known    — a measurement, also
     *                                                       with no ceiling
     *   3. the mark, unqualified                          — nothing better is
     *                                                       known; counted, so
     *                                                       the residue is
     *                                                       visible and shrinks
     *                                                       as coverage grows
     *
     * Note what is absent: there is no comparison against the entry basis and
     * no ratio anywhere in this function. A 1000x mint takes path 1 or path 2
     * and books 1000x either way.
     */
    fun closePrice7077(mint: String, markPriceUsd: Double, mcapUsd: Double): Double {
        val q = qualify7077(mint, markPriceUsd, mcapUsd)
        if (q.verdict == Verdict.QUALIFIED) {
            try { PipelineHealthCollector.labelInc("CLOSE_ON_QUALIFIED_MARK_7077") } catch (_: Throwable) {}
            return q.priceUsd
        }
        if (q.supplyTokens > 0.0 && q.mcapUsd > 0.0) {
            val implied = q.mcapUsd / q.supplyTokens
            if (implied.isFinite() && implied > 0.0) {
                try {
                    PipelineHealthCollector.labelInc("CLOSE_ON_MEASURED_CAP_7077")
                    ForensicLogger.lifecycle(
                        "CLOSE_ON_MEASURED_CAP_7077",
                        "mint=${mint.take(10)} mark=$markPriceUsd measuredPrice=$implied " +
                            "mcap=${q.mcapUsd.toLong()} onChainSupply=${q.supplyTokens.toLong()} " +
                            "action=book_on_two_measured_facts_no_cap_applied",
                    )
                } catch (_: Throwable) {}
                return implied
            }
        }
        val last = lastQualified[mint]
        if (last != null && last.priceUsd.isFinite() && last.priceUsd > 0.0) {
            try { PipelineHealthCollector.labelInc("CLOSE_ON_LAST_QUALIFIED_MARK_7077") } catch (_: Throwable) {}
            return last.priceUsd
        }
        try { PipelineHealthCollector.labelInc("CLOSE_ON_UNQUALIFIED_MARK_7077") } catch (_: Throwable) {}
        return markPriceUsd
    }

    /** Diagnostic line for the pipeline report. */
    fun status(): String =
        "armed=${isArmed7077()} discovered=${discovered.get()} asked=${asked.get()} " +
            "qualified=${qualified.get()} pending=${pending.get()} rejected=${rejected.get()} " +
            "wouldHoldDisarmed=${wouldRefuseWhileDisarmed.get()} " +
            "opensQualified=${opensOnQualified.get()} opensDeferred=${opensDeferred.get()} " +
            "opensRefused=${opensRefused.get()} qualifiedMarks=${lastQualified.size}"
}
