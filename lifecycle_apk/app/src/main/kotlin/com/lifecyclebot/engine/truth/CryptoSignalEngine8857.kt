package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8857 §TWO_ENGINES — owner: "if we can simplify the veto stack but keep what is there at the same time that may
 * help. remember everything is meant to be able to be learnt and unlearnt and adjusted in a fluid state ... the crypto
 * trader's meant to be hitting anything it wants that's a crypto or meme coin that passes safeties etc and has the right
 * signals ... refine the crypto trader back to more of a basic signal engine ... that way we have 2 engines working
 * differently beside each other that can prove and disprove the right way to trade and educate each other".
 *
 * 5.0.8853 (LIVE): the crypto trader's own paper record read CRYPTO_ALT n655, W131 / L174, +3.0 % a trade, +14.465 SOL —
 * the one positive book on the phone — and its live funnel read 139 candidates, 0 allowed. The owner had already chosen
 * (8788 / 8806) that the crypto lanes trade live on their OWN paper record, bounded (CryptoSpotPaperBacking8788: at most
 * four backed entries an hour, an automatic stop at -0.1 SOL realised), but that backing answered one refusal only (the
 * expectancy check). The candidate then met the MEME live edge gate (CrossAssetCortex7931.liveRefusal ->
 * LiveEdgeGate7877 -> CandidateAdmission8136), a stack learned on fresh launches, and died there: 115 of 138 on a data
 * wait (a move-history read the desk's 60 s price series cannot give in time), the rest on learned meme doors. backed=0.
 *
 * The crypto trader is the second engine, and it is the simple one. Its desk elects a coin on its signals; then:
 *  - what BINDS is integrity only ([integrityReason8857]): hard safety, structure, sellability and the LP rule, a dead or
 *    empty launch, identity and price basis, a stale or missing price or an unknown cost ([dataIntegrity8857]), the live
 *    route and executable size, wallet / balance / ledger / duplicates, this bot's own loss locks on the coin, the lane's
 *    own realised live losses, the kill switch, and any check that failed to evaluate (closed);
 *  - every LEARNED door of the meme stack (entry state, stage, origin, setup and cell records, economics forecasts, data
 *    waits for history, evidence holds, chase and timing doors, Cortex and the edge model) is still evaluated, counted
 *    here, and filed on its own record for this coin (ForwardReturnLabeler7731.noteOverriddenDoor8715, graded on the
 *    followed clock since 8856) — it does not refuse while the lane's own record backs the entry ([passes8857]).
 * Nothing is removed and nothing is fixed open: the backing is re-read every minute (a record that stops paying stops
 * backing), the hourly cap and the -0.1 SOL stop are 8788's, and each engine's trades grade the other's doors — a meme
 * door this engine proves wrong is retired for the meme engine by its own record (GateOutcome8418.selectionGateRetired8696),
 * a door it proves right keeps binding there and shows here as the cost of having passed it.
 */
object CryptoSignalEngine8857 {
    /** Refusals that are facts about safety or execution, never forecasts. Matched in the upper-cased reason. */
    private val INTEGRITY_8857 = listOf(
        "SAFETY", "RUG", "FREEZE", "MINT_AUTH", "TOKEN_2022", "TOKEN2022", "T22_", "HOSTILE", "HONEYPOT", "SELLAB", "UNSELLABLE",
        "MAYHEM", "BLACKLIST", "HARD_BLOCK", "LIQUIDITY_COLLAPSE", "EXIT_SAFE", "LP_LOCK", "STRUCTURAL_RISK", "DEAD_LAUNCH",
        "EMPTY_CURVE", "NOBODY_BOUGHT", "AT_LAUNCH_CAP", "IDENTITY", "PRICE_BASIS", "BASIS_PROOF", "UNTRUSTED_MARK", "SENTINEL",
        "PEGGED", "PRICE_STALE", "STALE_PRICE", "STALE_QUOTE", "STALE_MARK", "INVALID_OR_STALE", "ROUTE", "SIZE_NOT_EXECUTABLE",
        "WALLET", "BLIND", "BALANCE", "LEDGER_REJECT", "LEDGER_COMMIT", "LEDGER_BASIS", "LEDGER_PRECHECK", "DUPLICATE", "SINGLE_FLIGHT", "QUARANT", "BIG_LOSER", "REPEAT_LOSER", "REBUY",
        "LANE_REALISED", "KILL_SWITCH", "HALT", "EVAL_FAILED", "READ_FAILED",
    )

    /** Pure: is [reason] an integrity refusal (it binds both engines)? */
    fun integrityReason8857(reason: String?): Boolean {
        val r = reason?.uppercase().orEmpty()
        return r.isBlank() || INTEGRITY_8857.any { r.contains(it) }
    }

    /**
     * Pure: does the admission [d] behind a data refusal rest on the executable minimum — no decision readable, an unknown
     * cost, a stale / missing price or a critical fact unobserved — and not on a wait for history or context?
     */
    fun dataIntegrity8857(d: CandidateAdmission8136.Decision8136?): Boolean {
        if (d == null) return true
        if (d.terminal == CandidateAdmission8136.Terminal8136.REFUSE) return integrityReason8857(d.reason)
        if (d.terminal != CandidateAdmission8136.Terminal8136.DEFER_DATA) return false
        if (!d.costPct.isFinite() || d.costPct < 0.0) return true
        return d.trace.any { line ->
            line.startsWith("criticalUnobserved8170=") ||
                (line.startsWith("missing=") && line.removePrefix("missing=").split(',').any { it.trim() == "price" })
        }
    }

    /**
     * Pure: does the meme gate's refusal [reason] bind the crypto engine? An integrity reason always; a data refusal when
     * the admission read by [admission] rests on the executable minimum (unreadable = it binds); anything else is a learned
     * door.
     */
    fun binds8857(reason: String, admission: () -> CandidateAdmission8136.Decision8136?): Boolean {
        if (integrityReason8857(reason)) return true
        if (reason.uppercase().contains("DATA_")) return dataIntegrity8857(try { admission() } catch (_: Throwable) { null })
        return false
    }

    /**
     * CanonicalAssetEntryContract6551.submit: may a LIVE crypto candidate of [lane] (CRYPTO_SPOT / CRYPTO_ALT) pass a meme
     * gate refusal that is not an integrity one ([integrity] false)? Only while the lane's own record backs it and its
     * hourly room and loss stop allow (CryptoSpotPaperBacking8788.backs8788; the permit is reserved at the seal). V5.0.8886 — [cell]
     * is the candidate's trend cell (CryptoTrend8886), read against its own twin record by the backing; V5.0.8910 — [strategy]
     * its STRATEGY7803, read against the twin's strategy record the same way.
     */
    fun passes8857(live: Boolean, lane: String?, integrity: Boolean, nowMs: Long, cell: String? = null, strategy: String? = null): Boolean =
        live && !integrity && (try { CryptoSpotPaperBacking8788.backs8788(lane, nowMs, cell, strategy) } catch (_: Throwable) { false })

    private val advisory = ConcurrentHashMap<String, AtomicLong>()
    /** The desk signal behind each pass: the specialist lane CryptoLaneDesk7391 read from the signal's own reasons. */
    private val bySignal = ConcurrentHashMap<String, AtomicLong>()
    /** The playbook setup each passed coin was showing (LanePlaybook7907): the engine's entries by the on-board setup that fired. */
    private val bySetup = ConcurrentHashMap<String, AtomicLong>()
    private val bound = ConcurrentHashMap<String, AtomicLong>()
    private val passed = AtomicLong(); private val refused = AtomicLong()
    private fun door(reason: String): String = (try { FdgGate8365.gateKey8392(reason) } catch (_: Throwable) { reason }).take(60)
    private fun bump(m: ConcurrentHashMap<String, AtomicLong>, k: String) {
        (m[k] ?: if (m.size >= 120) return else m.computeIfAbsent(k) { AtomicLong() }).incrementAndGet()
    }

    /**
     * The engine passed the learned door [reason] on ([mint], [learningLane]), elected by the desk signal [deskSignal]
     * (the specialist trader whose signal it was; "NONE" for the crypto brain's own): counted by door and by signal, and
     * filed on the door's own record.
     */
    fun noteAdvisory8857(mint: String, learningLane: String, reason: String, nowMs: Long, deskSignal: String = "", setup: String = "") {
        passed.incrementAndGet(); bump(advisory, door(reason)); bump(bySignal, deskSignal.trim().uppercase().ifBlank { "NONE" }.take(32))
        bump(bySetup, setup.trim().uppercase().ifBlank { "NO_SETUP" }.take(40))
        try { PipelineHealthCollector.labelInc("CRYPTO_ENGINE_LEARNED_DOOR_ADVISORY_8857") } catch (_: Throwable) {}
        if (mint.isNotBlank() && learningLane.isNotBlank())
            try { ForwardReturnLabeler7731.noteOverriddenDoor8715(mint, learningLane, reason, nowMs) } catch (_: Throwable) {}
    }

    /** The gate's refusal [reason] stood (integrity, or the lane's record does not back the entry). */
    fun noteBound8857(reason: String, integrity: Boolean) {
        refused.incrementAndGet(); bump(bound, (if (integrity) "INTEGRITY:" else "UNBACKED:") + door(reason))
    }

    private fun top(m: ConcurrentHashMap<String, AtomicLong>) =
        m.entries.sortedByDescending { it.value.get() }.take(10).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }

    /** Pure: (trades, summed SOL) of the live closes of [stats] whose entry lane [isCrypto] says belongs to the crypto engine, and of the rest. */
    fun split8857(stats: Map<String, Pair<Int, Double>>, isCrypto: (String) -> Boolean): Pair<Pair<Int, Double>, Pair<Int, Double>> {
        var cn = 0; var cs = 0.0; var mn = 0; var ms = 0.0
        for ((lane, v) in stats) if (isCrypto(lane)) { cn += v.first; cs += v.second } else { mn += v.first; ms += v.second }
        return (cn to cs) to (mn to ms)
    }

    fun statusLine8857(): String {
        val live = try {
            com.lifecyclebot.engine.LiveEntrySafetyHold.LiveConfidenceStats.byLane8280(0L)
                .mapValues { it.value.canonicalN to it.value.expectancySol * it.value.canonicalN }
        } catch (_: Throwable) { emptyMap() }
        val (c, m) = split8857(live) { it.trim().uppercase().startsWith("CRYPTO") }
        fun rec(v: Pair<Int, Double>) = "n${v.first}/${"%+.4f".format(java.util.Locale.US, v.second)}SOL"
        return "cryptoSignalEngine8857[on=${CryptoSpotPaperBacking8788.enabled8788} passedLearnedDoor=${passed.get()} doors{${top(advisory)}} " +
            "bySignal{${top(bySignal)}} bySetup{${top(bySetup)}} stood=${refused.get()} {${top(bound)}} " +
            "liveCloses{cryptoEngine=${rec(c)} memeEngine=${rec(m)}} ${try { CryptoSpotPaperBacking8788.statusLine8788() } catch (_: Throwable) { "backing=unavailable" }} " +
            "rule=integrity binds both engines; the meme stack's learned doors are counted and graded but do not refuse a crypto entry its own record backs]"
    }

    internal fun resetForTest8857() { advisory.clear(); bound.clear(); bySignal.clear(); bySetup.clear(); passed.set(0); refused.set(0) }
}
