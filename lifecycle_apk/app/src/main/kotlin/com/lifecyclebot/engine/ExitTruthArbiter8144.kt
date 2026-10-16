package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8144 — ONE FINAL EXIT ARBITRATION AUTHORITY (pure: no Android, no runtime state beyond the books below, the
 * clock is always passed in).
 *
 * Before 8144 every overlay sold on its own. One rapid-mark tick ran LifecycleHold8065, the spike / runner ladder
 * (SpikeCapture7943.onMark), the chart reader, RunnerGrab7967.heldExit7967 and StructureTracker7962 one after the
 * other, and each called the sell door itself: a +100% print could dispatch the spike rung (sell 50%) and, on the
 * same print, a structure break (sell 100%) — a lower-priority partial raced a higher-priority exit, and the
 * executor's capital recovery / profit lock / wallet-growth harvest could size a second tranche off the same move
 * before the first one's proceeds were confirmed.
 *
 * Now:
 *  - [decide8144] is the one ranking every overlay's intent passes, on the order the owner set
 *    ([Rank8144]: 1 emergency / structurally unsellable, 2 hard loss / invalidated thesis, 3 runner structure
 *    breakdown / severe give-back, 4 confirmed capital recovery, 5 profitable partials / trails, 6 runner milestones,
 *    7 funded residual moonbag, 8 time-based). It takes the immutable entry style and the sealed strategy as inputs
 *    ([Inputs8144]): a position whose sealed setup is not a runner (and has no proven peak) gets no runner milestone;
 *    a runner's protected residual share is never sold by a discretionary ladder; a moonbag set-aside that is not
 *    funded by confirmed proceeds is refused. Tiers 1-3 are never refused, clamped or held.
 *  - [TicketBook8144] is the per-position arbitration ticket: one decision per tick consumes it once, and while a
 *    chosen exit's sale is in flight no other discretionary tranche (from any path) may sell the same holding; a
 *    protective exit (ranks 1-3) always takes the ticket.
 *  - [CaptureBook8144] tracks MFE (raw best print), the confirmed peak (never above the chain-verified peak),
 *    realised capture (confirmed proceeds against sold cost) and the post-exit opportunity separately.
 */
object ExitTruthArbiter8144 {

    /** Lower rank wins. Ranks 1-3 are protective: never refused, never clamped, never held by any overlay. */
    enum class Rank8144(val rank: Int) {
        EMERGENCY_UNSELLABLE(1),
        HARD_LOSS(2),
        RUNNER_BREAKDOWN(3),
        CAPITAL_RECOVERY(4),
        PROFIT_PARTIAL(5),
        RUNNER_MILESTONE(6),
        FUNDED_MOONBAG(7),
        TIME_BASED(8),
    }

    /** A discretionary tranche's sale is in flight for at most this long before its ticket lapses (verifier timeout 60 s). */
    const val TICKET_TTL_MS_8144 = 75_000L
    /** A claim with no sell in flight for the mint lapses after this long (its later door refused it: nothing sold). */
    const val CLAIM_GRACE_MS_8144 = 5_000L
    private const val EPS_8144 = 1e-9

    /** Pure: the rank of an exit reason (RunnerExitContract8136's tiers are the same order). */
    fun rankOf8144(reason: String?): Rank8144 = when (RunnerExitContract8136.tierOf8136(reason)) {
        RunnerExitContract8136.Tier8136.EMERGENCY -> Rank8144.EMERGENCY_UNSELLABLE
        RunnerExitContract8136.Tier8136.HARD_RISK -> Rank8144.HARD_LOSS
        RunnerExitContract8136.Tier8136.RUNNER_CONTINUATION -> Rank8144.RUNNER_BREAKDOWN
        RunnerExitContract8136.Tier8136.CAPITAL_RECOVERY -> Rank8144.CAPITAL_RECOVERY
        RunnerExitContract8136.Tier8136.PROFIT_LOCK -> Rank8144.PROFIT_PARTIAL
        RunnerExitContract8136.Tier8136.RUNNER_MILESTONE -> Rank8144.RUNNER_MILESTONE
        RunnerExitContract8136.Tier8136.MOONBAG -> Rank8144.FUNDED_MOONBAG
        RunnerExitContract8136.Tier8136.ORDINARY -> Rank8144.TIME_BASED
    }

    /** Pure: ranks 1-3 — protective exits no hold, budget or ticket may suppress. */
    fun protective8144(reason: String?): Boolean = rankOf8144(reason).rank <= Rank8144.RUNNER_BREAKDOWN.rank

    /**
     * Pure: the tranche identity of a reason — the reason without its price tokens (`_45PCT`, `_250PK`, `_2.1x`), so
     * the same rung re-entering its execution door, or retried at another print, is the same tranche.
     */
    fun trancheKey8144(reason: String): String =
        reason.trim().uppercase()
            .replace(Regex("_-?\\d+(\\.\\d+)?(PCT|PK)"), "")
            .replace(Regex("_\\d+(\\.\\d+)?X$"), "")
            .take(64)

    /**
     * What the authority knows about the position. [entryStyle] is the style sealed at entry and [sealedStrategy] the
     * sealed entry strategy (both immutable for the position's life); [confirmedPeakPct] is the chain-verified,
     * corroborated peak (never a raw print); [remainingShare] the share of the original quantity still held.
     */
    data class Inputs8144(
        val sealedStrategy: String,
        val entryStyle: String,
        val laneHostsRunners: Boolean,
        val confirmedPeakPct: Double,
        val capitalRecoveredConfirmed: Boolean,
        val pnlPct: Double,
        val sellCostPct: Double,
        val remainingShare: Double,
        val continuationConfirmed: Boolean = false,
        /** V5.0.8153 — the position's plan basis from the one authority (RunnerLive8153: includes a latched promotion). */
        val planBasis8153: RunnerExitContract8136.RunnerBasis8136? = null,
    )

    /** One overlay's exit request: [fraction] of the CURRENT holding (1.0 = full exit). */
    data class Intent8144(val reason: String, val fraction: Double)

    /** The exit that acts ([act], null = hold), its rank, the intents it beat and the intents refused (with why). */
    data class Decision8144(
        val act: Intent8144?,
        val rank: Rank8144?,
        val suppressed: List<Intent8144>,
        val refused: List<String>,
    )

    /**
     * Pure: does the position ride the runner contract — its sealed setup (style, else sealed strategy) or a proven peak?
     * V5.0.8153 — one rule (RunnerPlan8153.plan8153): the sealed style decides and the sealed strategy only when the style
     * is unknown; the live plan basis ([Inputs8144.planBasis8153], which knows a latched promotion) wins when given.
     */
    fun runnerBasis8144(inp: Inputs8144): RunnerExitContract8136.RunnerBasis8136 {
        if (!inp.laneHostsRunners) return RunnerExitContract8136.RunnerBasis8136.NONE
        inp.planBasis8153?.let { return it }
        return RunnerPlan8153.basis8153(RunnerPlan8153.plan8153(RunnerPlan8153.Evidence8153(
            laneHostsRunners = true, sealedStyle = inp.entryStyle, sealedStrategy = inp.sealedStrategy,
            verifiedPeakPct = inp.confirmedPeakPct)))
    }

    private data class Admit8144(val fraction: Double, val refusal: String?)

    /** Pure: is [reason] a decision to keep a residual (moonbag set-aside / bank), which must be funded? */
    private fun keepsResidual8144(reason: String): Boolean {
        val r = reason.uppercase()
        return r.contains("SET_ASIDE") || r.startsWith("MOONBAG_BANK")
    }

    private fun admit8144(reason: String, fraction: Double, rank: Rank8144, runner: Boolean, inp: Inputs8144): Admit8144 {
        if (rank.rank <= Rank8144.RUNNER_BREAKDOWN.rank) return Admit8144(fraction, null)
        val r = reason.uppercase()
        if (rank == Rank8144.RUNNER_MILESTONE && !runner && !r.startsWith("TAIL_MILESTONE") && !r.startsWith("LIFECYCLE_")) {
            return Admit8144(0.0, "NOT_A_RUNNER_8144")
        }
        if (keepsResidual8144(r) && !RunnerExitContract8136.tailFunded8136(inp.pnlPct, inp.capitalRecoveredConfirmed,
                inp.sellCostPct, TailBag8034.TAIL_FRACTION)) {
            return Admit8144(0.0, "UNFUNDED_MOONBAG_8144")
        }
        if (fraction < 1.0 - EPS_8144 && runner) {
            val allowed = RunnerExitContract8136.budgetedFraction8136(fraction, inp.remainingShare,
                RunnerExitContract8136.RUNNER_RESIDUAL_SHARE_8136, reason)
            if (allowed <= 0.0) return Admit8144(0.0, "RUNNER_PROTECTED_SHARE_8144")
            return Admit8144(allowed, null)
        }
        return Admit8144(fraction, null)
    }

    /**
     * Pure: the one decision over every intent offered for one position on one tick. The best rank acts (ties: the
     * larger fraction). A CONFIRMED runner continuation holds soft FULL exits (ranks 5 and 8); nothing holds ranks 1-3.
     */
    fun decide8144(intents: List<Intent8144>, inp: Inputs8144): Decision8144 {
        val runner = runnerBasis8144(inp) != RunnerExitContract8136.RunnerBasis8136.NONE
        val refused = mutableListOf<String>()
        val admitted = mutableListOf<Intent8144>()
        for (i in intents) {
            if (!i.fraction.isFinite() || i.fraction <= 0.0) continue
            val f = i.fraction.coerceAtMost(1.0)
            val a = admit8144(i.reason, f, rankOf8144(i.reason), runner, inp)
            if (a.refusal != null) { refused += "${a.refusal}:${i.reason.take(48)}"; continue }
            admitted += Intent8144(i.reason, a.fraction)
        }
        val ranked = admitted.sortedWith(compareBy<Intent8144>({ rankOf8144(it.reason).rank }, { -it.fraction }))
        for (candidate in ranked) {
            val rank = rankOf8144(candidate.reason)
            val softFull = (rank == Rank8144.PROFIT_PARTIAL || rank == Rank8144.TIME_BASED) && candidate.fraction >= 1.0 - EPS_8144
            if (inp.continuationConfirmed && softFull) continue
            return Decision8144(candidate, rank, ranked.filter { it !== candidate }, refused)
        }
        return Decision8144(null, null, ranked, refused)
    }

    // ── the per-position arbitration ticket ────────────────────────────────

    /**
     * One ticket per position (mint + entry time). A tick's decision consumes it once ([consumeTick8144]); a
     * discretionary tranche from any path ([admit8144]) passes only while no OTHER chosen exit holds it. The ticket
     * is spent when the position's sold share moves (the tranche filled), when the position changes, after
     * [ttlMs], or — when the claim never became a sale (a later door refused it) — once [claimGraceMs] passes with no
     * sell in flight for the mint ([inFlight]). Thread-safe.
     */
    class TicketBook8144(
        private val ttlMs: Long = ExitTruthArbiter8144.TICKET_TTL_MS_8144,
        private val claimGraceMs: Long = ExitTruthArbiter8144.CLAIM_GRACE_MS_8144,
        private val inFlight: (String) -> Boolean = { false },
        private val cap: Int = 4_096,
    ) {
        private data class Ticket(val entryTime: Long, val tranche: String, val rank: Int, val tick: Long,
                                  val atMs: Long, val soldPctAtClaim: Double)
        private val tickets = ConcurrentHashMap<String, Ticket>()

        private fun live(mint: String, t: Ticket?, entryTime: Long, soldPctNow: Double, nowMs: Long): Boolean {
            if (t == null || t.entryTime != entryTime || soldPctNow > t.soldPctAtClaim + 1e-6) return false
            val age = nowMs - t.atMs
            if (age !in 0L..ttlMs) return false
            return age <= claimGraceMs || (try { inFlight(mint) } catch (_: Throwable) { false })
        }

        private fun put(mint: String, t: Ticket) {
            if (tickets.size >= cap && !tickets.containsKey(mint)) tickets.entries.removeIf { it.value.atMs < t.atMs - ttlMs }
            if (tickets.size < cap || tickets.containsKey(mint)) tickets[mint] = t
        }

        /**
         * A discretionary (or protective) tranche asks to sell. Protective ranks always pass and take the ticket; the
         * same tranche re-entering its execution door passes; another tranche while a chosen exit holds it is refused.
         */
        @Synchronized
        fun admit8144(mint: String, entryTime: Long, reason: String, soldPctNow: Double, nowMs: Long): Boolean {
            if (mint.isBlank()) return true
            val rank = ExitTruthArbiter8144.rankOf8144(reason).rank
            val tranche = ExitTruthArbiter8144.trancheKey8144(reason)
            val t = tickets[mint]
            if (rank <= Rank8144.RUNNER_BREAKDOWN.rank || !live(mint, t, entryTime, soldPctNow, nowMs)) {
                put(mint, Ticket(entryTime, tranche, rank, -1L, nowMs, soldPctNow))
                return true
            }
            return t!!.tranche == tranche
        }

        /** The tick's one decision: false when this tick already dispatched, or another chosen exit holds the ticket. */
        @Synchronized
        fun consumeTick8144(mint: String, entryTime: Long, tick: Long, reason: String, soldPctNow: Double, nowMs: Long): Boolean {
            if (mint.isBlank()) return false
            val t = tickets[mint]
            if (t != null && t.entryTime == entryTime && t.tick == tick && tick >= 0L) return false
            val rank = ExitTruthArbiter8144.rankOf8144(reason).rank
            val tranche = ExitTruthArbiter8144.trancheKey8144(reason)
            if (rank > Rank8144.RUNNER_BREAKDOWN.rank && live(mint, t, entryTime, soldPctNow, nowMs) && t!!.tranche != tranche) return false
            put(mint, Ticket(entryTime, tranche, rank, tick, nowMs, soldPctNow))
            return true
        }

        /** Does a chosen exit OTHER than [reason]'s tranche hold this position's ticket right now? */
        fun heldByOther8144(mint: String, entryTime: Long, reason: String, soldPctNow: Double, nowMs: Long): Boolean {
            val t = tickets[mint]
            return live(mint, t, entryTime, soldPctNow, nowMs) && t!!.tranche != ExitTruthArbiter8144.trancheKey8144(reason)
        }

        /** The dispatch failed: the ticket goes back (only if [reason]'s tranche still holds it). */
        @Synchronized
        fun release8144(mint: String, reason: String) {
            val t = tickets[mint] ?: return
            if (t.tranche == ExitTruthArbiter8144.trancheKey8144(reason)) tickets.remove(mint)
        }

        /**
         * A tick's losing intent claimed the ticket at its decision door (budget) during this tick: hand it back so the
         * tick's winner can take it. A ticket claimed before [sinceMs] (an in-flight sale) is kept.
         */
        @Synchronized
        fun releaseIfClaimedSince8144(mint: String, reason: String, sinceMs: Long) {
            val t = tickets[mint] ?: return
            if (t.tranche == ExitTruthArbiter8144.trancheKey8144(reason) && t.atMs >= sinceMs && t.tick < 0L) tickets.remove(mint)
        }

        fun size8144(): Int = tickets.size
        fun clear8144() = tickets.clear()
    }

    // ── what the exits captured ────────────────────────────────────────────

    /**
     * Per position: MFE (best raw print), confirmed peak (best print never above the chain-verified peak), realised
     * capture (confirmed proceeds against the cost basis they sold) and, after a full exit, the post-exit opportunity
     * (best gross seen within [postExitWindowMs] after the exit, less the gross the exit was decided at). Bounded.
     */
    class CaptureBook8144(private val cap: Int = 2_000, private val postExitWindowMs: Long = 30L * 60_000L) {
        data class Capture8144(
            val mfePct: Double = Double.NaN,
            val confirmedPeakPct: Double = Double.NaN,
            val realisedProceedsSol: Double = 0.0,
            val realisedCostBasisSol: Double = 0.0,
            val exitGrossPct: Double = Double.NaN,
            val exitAtMs: Long = 0L,
            val exitEntryPrice: Double = 0.0,
            val postExitBestPct: Double = Double.NaN,
        ) {
            /** Realised capture, percent of the sold cost basis (NaN until a confirmed fill). */
            val realisedCapturePct: Double get() =
                if (realisedCostBasisSol > 0.0) (realisedProceedsSol / realisedCostBasisSol - 1.0) * 100.0 else Double.NaN
            /** Post-exit opportunity in points of gross (>= 0; NaN before an exit or without a later print). */
            val postExitOpportunityPp: Double get() =
                if (exitGrossPct.isFinite() && postExitBestPct.isFinite()) (postExitBestPct - exitGrossPct).coerceAtLeast(0.0) else Double.NaN
        }

        private val book = ConcurrentHashMap<String, Capture8144>()
        private val exitedByMint = ConcurrentHashMap<String, String>()

        private fun trim(key: String) {
            if (book.size >= cap && !book.containsKey(key)) { book.clear(); exitedByMint.clear() }
        }

        /** A mark: [rawGrossPct] from the print, [verifiedPeakPct] the chain-verified position peak. */
        fun noteMark8144(key: String, rawGrossPct: Double, verifiedPeakPct: Double) {
            if (key.isBlank() || !rawGrossPct.isFinite()) return
            trim(key)
            val confirmed = if (verifiedPeakPct.isFinite()) minOf(rawGrossPct, verifiedPeakPct) else Double.NaN
            book.compute(key) { _, c ->
                val p = c ?: Capture8144()
                p.copy(
                    mfePct = if (p.mfePct.isFinite()) maxOf(p.mfePct, rawGrossPct) else rawGrossPct,
                    confirmedPeakPct = when {
                        !confirmed.isFinite() -> p.confirmedPeakPct
                        p.confirmedPeakPct.isFinite() -> maxOf(p.confirmedPeakPct, confirmed)
                        else -> confirmed
                    },
                )
            }
        }

        /**
         * The position's CONFIRMED realised totals so far (proceeds of confirmed fills and the cost basis they sold).
         * Totals only ratchet up: a lagging read never erases a fill already seen.
         */
        fun noteRealised8144(key: String, proceedsSol: Double, soldCostBasisSol: Double) {
            if (key.isBlank() || !proceedsSol.isFinite() || proceedsSol < 0.0 || !soldCostBasisSol.isFinite() || soldCostBasisSol <= 0.0) return
            trim(key)
            book.compute(key) { _, c ->
                val p = c ?: Capture8144()
                if (soldCostBasisSol < p.realisedCostBasisSol) p
                else p.copy(realisedProceedsSol = maxOf(p.realisedProceedsSol, proceedsSol), realisedCostBasisSol = soldCostBasisSol)
            }
        }

        /** A full exit was chosen at [exitGrossPct] on a position entered at [entryPrice]. */
        fun noteExit8144(mint: String, key: String, exitGrossPct: Double, entryPrice: Double, nowMs: Long) {
            if (key.isBlank() || mint.isBlank()) return
            trim(key)
            book.compute(key) { _, c -> (c ?: Capture8144()).copy(exitGrossPct = exitGrossPct, exitAtMs = nowMs, exitEntryPrice = entryPrice) }
            exitedByMint[mint] = key
        }

        /** A print of [mint] at [px] after its full exit (read against the exited position's entry). */
        fun notePostExitPx8144(mint: String, px: Double, nowMs: Long) {
            if (!px.isFinite() || px <= 0.0) return
            val key = exitedByMint[mint] ?: return
            val c = book[key] ?: return
            if (nowMs - c.exitAtMs !in 0L..postExitWindowMs || c.exitEntryPrice <= 0.0) { exitedByMint.remove(mint, key); return }
            val g = (px / c.exitEntryPrice - 1.0) * 100.0
            book[key] = c.copy(postExitBestPct = if (c.postExitBestPct.isFinite()) maxOf(c.postExitBestPct, g) else g)
        }

        fun of8144(key: String): Capture8144? = book[key]
        fun all8144(): Map<String, Capture8144> = HashMap(book)
        fun clear8144() { book.clear(); exitedByMint.clear() }
    }
}
