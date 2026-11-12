package com.lifecyclebot.engine.truth

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7731 §THE_SAMPLE_IS_THE_EDGE.
 *
 * The bot learns from live closes: 11 to 23 per session, spread over nineteen
 * learners that each want 8 to 60 before they act. It sees about 700 unique
 * tokens and 36,000 candidate offers in the same session and keeps none of
 * them. The one counterfactual it does keep (LaneShadowProof7307, refused
 * unproven-lane candidates, 60 open at most) is the clearest line in the
 * report: refused MOONSHOT ran -49.5%, SHITCOIN -49%, TREASURY +7.3%.
 *
 * This labeler follows EVERY candidate FinalDecisionGate rules on, admitted or
 * refused, and books its forward return at 15, 60 and 240 minutes, net of the
 * Field Manual's all-in cost at the routable minimum size, into a cell:
 *
 *     source family | lane | market-cap band | age band
 *
 * It spends nothing, opens nothing and touches no ledger. It reads prices the
 * loop already holds (the same closure LaneShadowProof7307 ticks on) and the
 * canonical mark registry; the loop never waits on a provider (7737 below). Cells persist across
 * restarts, so a thousand labels a day accumulate into the table a selection
 * authority can read (CellProofLadder7731), instead of a dozen closes a day
 * being asked to prove everything.
 *
 * A candidate whose mark disappears before its horizon is counted as
 * LOST_MARK on its cell and not booked: a watchlist eviction and a rug look
 * the same from here, and guessing which would poison the table. The ladder
 * reads the lost share next to the mean and refuses to act on a cell that
 * mostly vanishes.
 *
 * V5.0.7737 — a mark that leaves the watchlist is fetched. 5.0.7736 read
 * admitted60 n=71 against lost=1481: the table kept the survivors and dropped
 * the rugs, so its means flattered whatever stayed on the bench. An observation
 * due at a horizon with no loop or registry price now joins a batch (fifty
 * mints, one Jupiter Price call, off the loop, at most every
 * [OFFWATCH_FETCH_GAP_MS_7737]); a dead token Jupiter no longer prices stays lost.
 */
object ForwardReturnLabeler7731 {

    /**
     * V5.0.7946 — the read is FIVE minutes. Meme tokens spike and fade inside
     * minutes; a 60-minute read taught the Cortex an hour late, lost the mark of
     * every token that left the watchlist in that hour (5.0.7941: 1,928 lost), and
     * graded the price long after the bot's own exits had acted. The three label
     * slots keep their historical names (15 / 60 / 240, and n60 / meanNet60Pct in
     * CellStat) but now mean:
     *   slot "15"  -> 2 minutes  (the early read)
     *   slot "60"  -> 5 minutes  (THE read: lane proof, cells, stages, Cortex, playbooks)
     *   slot "240" -> 60 minutes (the late read that spares runner lanes)
     * The table is stored under new keys, so no 60-minute label mixes into it.
     */
    // V5.0.8040 — owner's schedule: 1 min, 5 min, 1 hour, then 4 h and 24 h only while the tokenomics are worth watching.
    private const val H15_MS_7731 = 1L * 60_000L
    private const val H60_MS_7731 = 5L * 60_000L
    private const val H240_MS_7731 = 60L * 60_000L
    /** A mark older than this is not a price for this purpose (tightened for the 5-minute read). */
    private const val MARK_MAX_AGE_MS_7731 = 45_000L
    /** Lost-mark grace past the longest horizon before the observation is dropped. */
    private const val LOST_GRACE_MS_7731 = 10L * 60_000L
    /** V5.0.7946 — one observation per mint and lane per 15 minutes (the read is 5). */
    private const val REOBSERVE_MS_7731 = 15L * 60_000L
    // V5.0.8064 — a fresh book: every cell before it was graded with spike credit live never banks (see captured7945).
    // V5.0.8527 — the old runner remainder fabricated a half-peak exit without an observed fill.
    // These are forward-only stores, not realised trade journals. Preserve old preferences and SQLite rows as
    // archives, including frozen pending/outbox payloads; never replay them into the observed-only authority.
    private const val CELLS_KEY_7946 = "cells_8527"
    private const val PENDING_KEY_7946 = "pending_8527"

    internal data class ModelledCheckpoint8527(val cells: String?, val pending: String?, val paired: String?)

    /** A checkpoint and its predictions must use one grading contract; no fallback to the synthetic epoch. */
    internal fun modelledCheckpoint8527(p: SharedPreferences): ModelledCheckpoint8527 = ModelledCheckpoint8527(
        p.getString(CELLS_KEY_7946, null), p.getString(PENDING_KEY_7946, null),
        p.getString(PAIRED_CORTEX_KEY_8401, null),
    )

    /** V5.0.7946 — Pure: how late a horizon may still be read: half the horizon, between 1 and 10 minutes. */
    fun graceFor7946(horizonMs: Long): Long = (horizonMs / 2).coerceIn(60_000L, LOST_GRACE_MS_7731)
    private const val MAX_PENDING_7731 = 6_000

    /** V5.0.8031 — how full the pending book is (0..1): low-priority labels (native verdicts) back off above half. */
    fun pendingLoad8031(): Double = pending.size.toDouble() / MAX_PENDING_7731
    private const val MAX_CELLS_7731 = 1_000 // V5.0.7928 — +lane x stage aggregates (never pruned); V5.0.8063 — +coin kind / narrative books
    private const val MAX_SEEN_7731 = 12_000
    private const val PERSIST_EVERY_BOOKINGS_7731 = 25
    private const val PREFS_7731 = "forward_return_labeler_7731"
    /** Routable-minimum ticket, the size every cell is costed at. */
    internal const val COST_SIZE_USD_7731 = 5.0
    /** A gross move at the read (spike tiers credited) above this is a runner. */
    private const val RUNNER_PCT_7731 = 50.0
    /** V5.0.7735 — oldest decision-time price a label may start from. */
    private const val ENTRY_MARK_MAX_AGE_MS_7735 = 90_000L // V5.0.7946 — a 5-minute read cannot start from a 10-minute-old price
    /**
     * V5.0.7735 — pending observations survive a restart. Every install or
     * restart inside the 60-minute horizon used to discard every open
     * observation, and the app is reinstalled about once an hour while it is
     * being built: 5.0.7734 at 54 minutes read booked15=65 booked60=0, the
     * ladder's horizon never reached. Persisted beside the cells, restored on
     * attach, bounded to the newest [MAX_PERSISTED_PENDING_7735].
     */
    // Every admitted observation owns a checkpoint row. A restart must not select only one third of the book.
    // The serialized pending checkpoint is compressed, as its paired prediction checkpoint already is.
    private const val MAX_PERSISTED_PENDING_7735 = MAX_PENDING_7731
    /** V5.0.8014 — labels a cell keeps per horizon before the oldest fade (fresh edge). */
    const val CELL_WINDOW_8014 = 300
    private const val FIELD_SEP_7735 = '\u001F'
    private const val ROW_SEP_7735 = '\u001E'
    private val restoredPending7735 = AtomicLong(0)
    /** V5.0.7737 — off-watchlist marks fetched for observations due at a horizon. */
    // V5.0.8405 §PRICE_THE_OUTCOME_YOU_PROMISED — 5.0.8404 (547 s): observed=3369, booked15=544, booked60=426, lostMark=1197,
    // offWatch deferred=2663 priced=305, horizonMissed=426; CortexAuthority8366 INCOMPLETE_OUTCOMES on every lane with
    // matured=925 missing=823 (CORE), coverageRest=0.11. The off-watch mark path was 50 mints every 20 s (150/min) led by
    // Jupiter (429-limited free tier, sr=63%), re-asking a missed mint only after 90 s — past the 1-minute horizon's grace —
    // while ~370 observations/min matured. Capacity, not data: DexScreener (sr=100%, 30 per call) now leads for the whole
    // batch, the curve read prices pump mints, Jupiter is the last rung; 90 mints every 10 s; a missed mint is re-asked at 30 s.
    // superseded by V5.0.8618 — 3 s (was 10 s): 03:50 dump (8613) horizonMissed7809=1,980, pendingMints8475=513, labels 45 %
    // priced; one batch averaged 2.9 s, so the 10 s gap (not the providers) left a due coin waiting up to ~13 s plus its place
    // in a 300-coin serial batch, past the 1-minute read's 60 s grace. Still one batch in flight at a time.
    private const val OFFWATCH_FETCH_GAP_MS_7737 = 3_000L
    // superseded by V5.0.8442 — 150 a batch (was 90): ~370 label reads/min mature plus the open shadow tickets' marks
    // (noteShadowWanted8442); five DexScreener calls every 10 s is 30/min of a 270/min host budget that used ~112/min in 8439.
    // superseded by V5.0.8455 — 300 a batch: every tracked coin's path is read continuously (trackPath8455), ~2,800 coins at a
    // time; ten DexScreener calls every 10 s is 60/min of the 270/min host budget.
    // superseded by V5.0.8618 — 120 a batch every ~3 s (was 300 every 10 s): the batch is ordered soonest-closing window first,
    // so a shorter batch prices the coins about to miss within seconds instead of after ten serial DexScreener chunks. Capacity
    // rises (~2,400 coins/min vs ~1,800) at ~80 DexScreener calls/min of the 270/min host budget.
    private const val OFFWATCH_BATCH_7737 = 120
    private const val OFFWATCH_DEX_CHUNK_8405 = 30

    // ── V5.0.8442 §SHADOW_TICKETS_SHARE_THE_LABEL_MARKS ──
    // 8439: SHADOW_OUTCOME_UNAVAILABLE_8394_LOST_MARK=5634 — an open shadow ticket's mint was asked only of the keyless pair
    // lane (KeylessMarks8359, 400 wanted at most) while ~860 tickets were open; the unpriced ones closed with no outcome,
    // and the Cortex's outcome coverage (CortexAuthority8366 needs 70%) stayed short. They now ride this batch too, after
    // every label-due mint. ExploreShadow reads the result through observedMark8372, which already reads this store.
    private val shadowWanted8442 = ConcurrentHashMap<String, Long>()
    private const val MAX_SHADOW_WANTED_8442 = 3_000

    /** ExploreShadow8347.want8360: keep [mint] priced off-watch until [untilMs]. */
    fun noteShadowWanted8442(mint: String, untilMs: Long) {
        if (mint.isBlank()) return
        shadowWanted8442.merge(mint, untilMs) { a, b -> maxOf(a, b) }
        if (shadowWanted8442.size > MAX_SHADOW_WANTED_8442) {
            val now = System.currentTimeMillis()
            shadowWanted8442.entries.removeIf { it.value <= now }
            if (shadowWanted8442.size > MAX_SHADOW_WANTED_8442) shadowWanted8442.entries.sortedBy { it.value }
                .take(shadowWanted8442.size - MAX_SHADOW_WANTED_8442).forEach { shadowWanted8442.remove(it.key) }
        }
    }

    /**
     * Pure (V5.0.8442): the wanted shadow mints to ask now — still wanted, not priced elsewhere within [freshMs], and with
     * no off-watch mark younger than [freshMs]; the longest-unpriced first.
     */
    fun shadowDue8442(wanted: Map<String, Long>, marks: Map<String, Pair<Double, Long>>, nowMs: Long, freshMs: Long,
                      freshElsewhere: (String) -> Boolean): List<String> =
        wanted.entries.asSequence()
            .filter { it.value > nowMs && nowMs - (marks[it.key]?.second ?: 0L) >= freshMs && !freshElsewhere(it.key) }
            .sortedBy { marks[it.key]?.second ?: 0L }.map { it.key }.toList()

    private fun freshMarketMark8521(mint: String, nowMs: Long, freshMs: Long): Boolean {
        val observedAt = try { independentMarkAt8418(mint, nowMs) } catch (_: Throwable) { 0L }
        return observedAt > 0L && nowMs - observedAt in 0L until freshMs
    }
    private val offWatchMarks7737 = ConcurrentHashMap<String, Pair<Double, Long>>()
    private val offWatchInFlight7737 = java.util.concurrent.atomic.AtomicBoolean(false)
    // V5.0.8508 §ONE_SLOW_BATCH_STARVES_EVERY_LABEL — the off-watch pricer runs one batch at a time. A batch whose network
    // calls stall (DexScreener limiter wait, a slow curve RPC, Jupiter 5xx) held the single slot for as long as it took:
    // 8506 priced 7.2 mints/s at 584 s, 8507 priced 1.2/s by 16,823 s and 68% of due labels found no source. A batch that
    // has run past OFFWATCH_STALL_MS_8508 no longer owns the slot (its results still land when it finishes); a generation
    // id keeps the late finisher from releasing the newer batch's slot. Batch wall time is measured.
    private const val OFFWATCH_STALL_MS_8508 = 45_000L
    /** V5.0.8540 — a pool-vault read used as a label mark is at most this old. */
    private const val CHAIN_MARK_MAX_AGE_MS_8540 = 20_000L
    private val offWatchChainPriced8540 = AtomicLong(0)
    private val chainRequested8540 = AtomicLong(0)
    @Volatile private var offWatchStartedAt8508 = 0L
    private val offWatchGen8508 = AtomicLong(0)
    private val offWatchBatches8508 = AtomicLong(0)
    private val offWatchBatchMsSum8508 = AtomicLong(0)
    @Volatile private var offWatchBatchMsMax8508 = 0L
    private val offWatchStallReleased8508 = AtomicLong(0)
    private val noSolRate8508 = AtomicLong(0)
    /** Batches still running (a released stalled batch plus the current one); never more than OFFWATCH_MAX_RUNNING_8508. */
    private val offWatchRunning8508 = java.util.concurrent.atomic.AtomicInteger(0)
    private const val OFFWATCH_MAX_RUNNING_8508 = 3
    @Volatile private var offWatchLastFetchMs7737 = 0L
    private val offWatchPriced7737 = AtomicLong(0)
    private val offWatchMissed7737 = AtomicLong(0)
    /** V5.0.8474 — label coins whose DexScreener batch our own limiter refused, re-asked next pass (not missed). */
    private val offWatchLimiterRetry8474 = AtomicLong(0)
    /** V5.0.8474 — how long the background label batch waits for the shared DexScreener slot (held marks wait 200 ms). */
    private const val LABEL_SLOT_WAIT_MS_8474 = 750L
    /**
     * V5.0.7738 — basis guard. 5.0.7737 at 396 s: PROJECT_SNIPER labels read
     * n=44 net=+1116.5% wr=9%, and the forecast model's S|PROJECT_SNIPER cell
     * E=+1245% +-7073 at n=39: a mean that size beside a 9% win rate is one or
     * two marks tens of times the entry, an entry price and a mark on different
     * bases, not a token that ran 250x in an hour. A mark more than
     * [BASIS_MAX_RATIO_7738] times the entry, or under 1/[BASIS_MAX_RATIO_7738]
     * of it, is not booked; a booked net enters the means capped at
     * [NET_CEILING_PCT_7738] (the runner count still reads the gross move).
     */
    private const val BASIS_MAX_RATIO_7738 = 20.0
    /** V5.0.8094 — a 20x+ label move is corroborated by the cap only below this entry cap (launch scale). */
    const val CORROBORATED_RUN_MAX_ENTRY_MCAP_8094 = 1_000_000.0
    // V5.0.7769 — 300 clipped every real 4x-20x launch to +300% before the cell
    // mean saw it; the corroboration check below is what keeps basis errors out.
    // V5.0.8072 — NO win cap. Owner, 2026-10-12: "remove caps on blockchain provable data ... no win caps. the whole data set is blockchain verifiable".
    // A 1000x is graded as a 1000x. Basis errors are still kept out where they are caught: a move past 20x books only
    // when the market cap moved by the same multiple (runCorroborated7769, the chain-side witness), else it is dropped.
    private const val NET_CEILING_PCT_7738 = Double.POSITIVE_INFINITY
    private val basisSuspect7738 = AtomicLong(0)
    private val purgedCells7738 = AtomicLong(0)

    /**
     * V5.0.8165 — 8162/8163 device: Cortex lanes read REFUSE +1,298% (CORE), +1,457% (MANIPULATED), +2,332% (MOONSHOT),
     * +2,630% (QUALITY) — every lane's refuse mean moved by thousands of points at once, an hour after the same rows read
     * -20..-30%. One coin, refused by every lane, labelled at a 5-minute 1,000x+: a mark on another basis. The 20x guard
     * lets it through when the cap "moved with it" (the cap is derived from the same price) and the two-feed check only
     * runs on a step that doubles the previous peak. The coin's own bonding curve is a chain witness neither shares:
     * while the curve is ACTIVE and read inside [CURVE_WITNESS_MAX_AGE_MS_8165], its spot price bounds the mark.
     */
    private const val CURVE_WITNESS_MAX_AGE_MS_8165 = 90_000L
    private val curveRefusedEntry8165 = AtomicLong(0)
    private val curveRefusedMark8165 = AtomicLong(0)

    /** V5.0.8337 (10d) — a mark and a curve read are compared only when they were read within this of each other. */
    const val CURVE_WITNESS_SYNC_MS_8337 = 5_000L
    private val curveHeldMark8337 = AtomicLong(0)

    /**
     * Pure (V5.0.8337, 10d): the curve witness verdict on a mark. 0 = the mark stands (no disagreement), 1 = CONTRADICTED
     * (a curve read within [CURVE_WITNESS_SYNC_MS_8337] of the mark disagrees by 2x: a basis error), 2 = HELD (a curve read
     * further than 5 s from the mark disagrees: the two may describe different moments on a fast curve, so the mark waits
     * for a newer curve read that agrees — it is never dropped on that read).
     */
    fun curveWitness8337(markUsd: Double, curveUsd: Double, markAtMs: Long, curveAtMs: Long): Int {
        if (!curveDisagrees8165(markUsd, curveUsd)) return 0
        return if (kotlin.math.abs(markAtMs - curveAtMs) <= CURVE_WITNESS_SYNC_MS_8337) 1 else 2
    }

    private fun curveWitnessAt8337(mint: String, markUsd: Double, markAtMs: Long, nowMs: Long): Int {
        val curveSol = try { CurveReserves7951.priceSolFor7951(mint, nowMs, CURVE_WITNESS_MAX_AGE_MS_8165) } catch (_: Throwable) { 0.0 }
        if (!(curveSol > 0.0)) return 0
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        if (!(solUsd > 0.0)) return 0
        val curveAt = (try { CurveReserves7951.readingAtMs8173(mint) } catch (_: Throwable) { null }) ?: return 0
        return curveWitness8337(markUsd, curveSol * solUsd, markAtMs, curveAt)
    }

    /** V5.0.8337 (10d) — contradicted only by a curve read within 5 s of the mark ([markAtMs], the read time when unknown). */
    private val chainEntry8571 = AtomicLong(0)
    private val skippedNoState8572 = AtomicLong(0)
    private val curveRebasedEntry8571 = AtomicLong(0)

    /**
     * Pure (V5.0.8571): the decision's entry basis. A fresh (<= [DECISION_FILL_MAX_AGE_MS_8567]) chain-provable mark of the
     * coin ([LabelEvidence8386.independent8418]) outranks the selected one; a selected price [contradicted] by the coin's
     * own curve is replaced by any in-age chain mark that is not itself contradicted; otherwise null (no buyable price).
     */
    internal fun preferChainEntry8571(selected: LabelMark8386, typed: LabelMark8386?, nowMs: Long,
                                      contradicted: (LabelMark8386) -> Boolean): LabelMark8386? {
        val chain = typed?.takeIf { it.known() && LabelEvidence8386.independent8418(it) && nowMs - it.observedAtMs in 0L..MARK_MAX_AGE_MS_7731 }
        if (chain != null && chain != selected && nowMs - chain.observedAtMs <= DECISION_FILL_MAX_AGE_MS_8567 && !contradicted(chain)) return chain
        if (!contradicted(selected)) return selected
        return chain?.takeIf { it != selected && !contradicted(it) }
    }

    private fun curveContradicts8165(mint: String, markUsd: Double, nowMs: Long, markAtMs: Long = nowMs): Boolean =
        curveWitnessAt8337(mint, markUsd, markAtMs, nowMs) == 1

    /** V5.0.8337 (10d) — held: an out-of-sync curve read disagrees; the mark waits for a newer read (never dropped). */
    private fun curveHolds8337(mint: String, markUsd: Double, nowMs: Long, markAtMs: Long = nowMs): Boolean =
        curveWitnessAt8337(mint, markUsd, markAtMs, nowMs) == 2

    /**
     * V5.0.8365: cap is commonly derived from the very same price being checked, so
     * cap co-movement is not independent proof. Only a fresh, synchronized curve spot
     * can witness a label. Raw price and cap observations remain unchanged.
     */
    fun witnessed8324(markUsd: Double, curveUsd: Double, @Suppress("UNUSED_PARAMETER") capCorroborated: Boolean): Boolean =
        markUsd > 0.0 && curveUsd > 0.0 && markUsd.isFinite() && curveUsd.isFinite() && !curveDisagrees8165(markUsd, curveUsd)

    private val witnessedLabels8324 = AtomicLong(0)

    /** V5.0.8324 — [o]'s current mark is chain-witnessed (sticky once true for the observation). */
    private fun witnessNow8324(o: Obs, nowMs: Long): Boolean {
        val px = o.lastPx
        if (!(px > 0.0) || !(o.entryPrice > 0.0)) { o.witnessed8324 = false; return false }
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val curveSol = try { CurveReserves7951.priceSolFor7951(o.mint, nowMs, CURVE_WITNESS_MAX_AGE_MS_8165) } catch (_: Throwable) { 0.0 }
        val curveAt = try { CurveReserves7951.readingAtMs8173(o.mint) } catch (_: Throwable) { null }
        // Witness THIS mark, not an older, unrelated price before a claimed runner peak.
        val synchronous = curveAt != null && o.lastPxAtMs > 0L &&
            kotlin.math.abs(curveAt - o.lastPxAtMs) <= CURVE_WITNESS_SYNC_MS_8337
        val curveUsd = if (synchronous && curveSol > 0.0 && solUsd > 0.0) curveSol * solUsd else 0.0
        // V5.0.8418 — a graduated / non-curve token has no curve spot: its own chain-provable mark (parsed swap, pool reserve,
        // curve tape) at this exact price and time witnesses it, so post-curve runners are not capped by a missing curve.
        val w = witnessed8324(px, curveUsd, false) || markWitnesses8418(o.lastMark8386, px, o.lastPxAtMs)
        if (w && !o.witnessed8324) witnessedLabels8324.incrementAndGet()
        o.witnessed8324 = w
        return w
    }

    /** Pure (V5.0.8418): the typed basis a pre-8386 observation keeps — its own stored price at its decision time. */
    internal fun legacyBasisMark8418(mint: String, px: Double, atMs: Long): LabelMark8386? =
        if (mint.isBlank() || !px.isFinite() || px <= 0.0 || atMs <= 0L) null
        else LabelMark8386(mint, "", px, atMs, atMs, "LEGACY_PRE8386")

    private val seedBasisUnknown8627 = AtomicLong(0L)

    /** Pure (V5.0.8627): the decision's basis — UNKNOWN when it is the token-state price and that price is an intake seed. */
    internal fun seedBasis8627(chosen: LabelMark8386, stateMark: LabelMark8386, stateSource: String?): LabelMark8386 {
        if (chosen !== stateMark || !TokenFacts.seedPrice8559(stateSource)) return chosen
        seedBasisUnknown8627.incrementAndGet()
        try { PipelineHealthCollector.labelInc("FORWARD_LABEL_SEED_BASIS_UNKNOWN_8627") } catch (_: Throwable) {}
        return chosen.copy(source = "UNKNOWN")
    }

    fun seedBasisUnknownCount8627(): Long = seedBasisUnknown8627.get()

    /** Pure (V5.0.8418): may an UNKNOWN basis adopt [mark]? Only before the re-base and inside its window. */
    internal fun adoptBasis8418(basisKnown: Boolean, rebased: Boolean, ageMs: Long, mark: LabelMark8386?): Boolean =
        !basisKnown && !rebased && ageMs in 0L..REBASE_MAX_MS_8076 && mark != null && mark.known()

    /** Pure (V5.0.8524): the adopt window has passed and the basis is still unknown — the first typed mark is the (late) basis. */
    internal fun adoptLateBasis8524(basisKnown: Boolean, ageMs: Long, mark: LabelMark8386?): Boolean =
        !basisKnown && ageMs > REBASE_MAX_MS_8076 && mark != null && mark.known()

    /** Pure (V5.0.8418): [mark] is chain-provable and is exactly the observation priced at [px] / [pxAtMs]. */
    internal fun markWitnesses8418(mark: LabelMark8386?, px: Double, pxAtMs: Long): Boolean =
        mark != null && px > 0.0 && px.isFinite() && pxAtMs > 0L && mark.observedAtMs == pxAtMs && mark.usd == px &&
            LabelEvidence8386.independent8418(mark)

    /** Pure (V5.0.8165): a USD mark more than 2x away (either way) from the curve's own USD spot price. */
    fun curveDisagrees8165(markUsd: Double, curveUsd: Double): Boolean {
        if (!(markUsd > 0.0) || !(curveUsd > 0.0) || !markUsd.isFinite() || !curveUsd.isFinite()) return false
        val r = markUsd / curveUsd
        return r > 2.0 || r < 0.5
    }

    /** Pure: true when a mark and an entry price cannot be on the same basis. */
    fun basisSuspect7738(entryPrice: Double, markPrice: Double): Boolean {
        if (!entryPrice.isFinite() || !markPrice.isFinite() || entryPrice <= 0.0 || markPrice <= 0.0) return true
        val r = markPrice / entryPrice
        return r > BASIS_MAX_RATIO_7738 || r < 1.0 / BASIS_MAX_RATIO_7738
    }

    /**
     * V5.0.7769 §A_REAL_RUN_IS_NOT_A_BASIS_ERROR. The 20x basis guard also threw
     * away every genuine runner: a $6.4k pump.fun launch that went to $1.2M (187x)
     * would be dropped unbooked, so the cell it came from could only ever read
     * its losers and the ladder refused that cell live. A move past 20x is a real
     * move when the market cap moved by the same multiple (within 2x) and the
     * ratio is not the SOL/USD factor that the 7738 basis mix-ups produced.
     * Field Manual §12: authority comes from finalized outcomes, all of them.
     */
    fun runCorroborated7769(entryPrice: Double, markPrice: Double, entryMcap: Double, nowMcap: Double, solUsd: Double): Boolean {
        if (!entryPrice.isFinite() || !markPrice.isFinite() || !entryMcap.isFinite() || !nowMcap.isFinite()) return false
        if (entryPrice <= 0.0 || markPrice <= 0.0 || entryMcap <= 0.0 || nowMcap <= 0.0) return false
        // V5.0.8094 — the cap is usually DERIVED from the same price (price x supply, curve fallback), so above launch
        // scale "the cap moved by the same multiple" corroborated its own basis error: 8093 booked RETARDIO (seen by
        // CASHGEN, a $1M+ hunter) at +720,038% — a $7B peak in hours — into the Cortex buckets, the runner report and
        // the tail hunter. A 20x+ move is corroborated only from launch scale, where such runs are real.
        if (entryMcap >= CORROBORATED_RUN_MAX_ENTRY_MCAP_8094) return false
        val r = markPrice / entryPrice
        val agree = (nowMcap / entryMcap) / r in 0.5..2.0
        val solBasis = solUsd.isFinite() && solUsd > 0.0 &&
            (kotlin.math.abs(EconomicUnitInvariant7061.usdToSol(r, solUsd) - 1.0) < 0.25 ||
                kotlin.math.abs(r / EconomicUnitInvariant7061.usdToSol(1.0, solUsd) - 1.0) < 0.25)
        return agree && !solBasis
    }

    private fun nowMcap7769(mint: String): Double =
        try { com.lifecyclebot.engine.BotService.status.tokens[mint]?.lastMcap ?: 0.0 } catch (_: Throwable) { 0.0 }

    private class Obs(
        val mint: String,
        val symbol: String,
        val cell: String,
        val source: String,
        val lane: String,
        val admitted: Boolean,
        /** V5.0.7734 — the forecast signature this label teaches (ForwardOutcomeModel). */
        val score: Int,
        val quality: String,
        val regime: String,
        val phase: String,
        entryPrice: Double,   // V5.0.8076 — re-based once to the first reachable mark
        @Volatile var costPct: Double,
        val atMs: Long,
    ) {
        @Volatile var basis8386 = LabelBasis8386(LabelMark8386(mint, "", entryPrice, 0L, atMs, "UNKNOWN"), 0.0)
        @Volatile var decisionBasis8386 = basis8386
        @Volatile var lastAcceptedMark8395: LabelMark8386? = null
        @Volatile var lastMark8386: LabelMark8386? = null
        /** V5.0.8418 — the first value the non-Cortex learners booked while the Cortex staging waits (null = none waiting). */
        @Volatile var staged8418: DoubleArray? = null
        /** The exact source observation behind a staged value, retained while Cortex/disk delivery retries. */
        @Volatile var stagedProof8521: com.lifecyclebot.engine.cortex.CortexOutcomeProof8395? = null
        @Volatile var lateEntry8521 = false
        /** Chosen with the original Cortex prediction, never recomputed after restart or a later token read. */
        @Volatile var targetHorizon8521 = H60_MS_7731
        @Volatile var timingPlan8528: SetupTimingGrades8528.Plan? = null
        val timingDone8528 = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        @Volatile var interimUnavailable8521 = false
        @Volatile var firstReadValid8521 = false
        /** V5.0.8418 — SpecialistMiner7972 booking stamps [boot, per combination] (a revision's remaining weight there). */
        @Volatile var minerScales8418: DoubleArray = DoubleArray(0)
        var entryPrice: Double
            get() = basis8386.mark.usd
            set(value) { basis8386 = basis8386.copy(mark = basis8386.mark.copy(usd = value)) }
        /** V5.0.8094 — the price at the decision itself, never re-based (the fill-time move check reads it). */
        val decisionPx8094: Double get() = decisionBasis8386.mark.usd
        var entryStyle8077: String = "" // frozen decision style; old pending labels retain legacy semantics
        @Volatile var done15 = false
        @Volatile var done60 = false
        @Volatile var done240 = false
        @Volatile var peakPct = 0.0
        /** V5.0.7769 — market cap at the decision, the second witness for a big move. */
        var entryMcap: Double
            get() = basis8386.capUsd
            set(value) { basis8386 = basis8386.copy(capUsd = value) }
        /** V5.0.7928 — the token's lifecycle stage at the decision (TokenMetricStageRouter). */
        @Volatile var stage = ""
        /** V5.0.7944 — the last price seen for it and when, so a vanished mark books at its real last value. */
        @Volatile var lastPx = 0.0
        @Volatile var lastPxAtMs = 0L
        /** V5.0.7955 — the setup at the decision (ExitProfile7955 key), when the peak was set, and the 5-minute give-back. */
        @Volatile var setup7955 = ""
        @Volatile var peakAtMs7955 = 0L
        @Volatile var giveback5_7955 = Double.NaN
        /** V5.0.7962 — lowest gross seen so far, and the lowest before the (latest) peak. */
        @Volatile var minPct7962 = 0.0
        @Volatile var dipBeforePeak7962 = Double.NaN
        /** V5.0.7967 — the refusal reason at the decision (missed-runner audit). */
        @Volatile var reason7967: String = ""
        /**
         * V5.0.8071 — the highest gain the price HELD for [HELD_MS_8071] (every mark in a 20-second window at or above
         * it): a level a sell can actually land at. A one-print spike that reverts inside seconds never sets it.
         */
        @Volatile var heldPeak8071 = 0.0
        /** V5.0.8076 — the entry has been re-based to the first mark a buy could reach (or the window passed). */
        @Volatile var rebased8076 = false
        /** V5.0.8094 — the chase share of [costPct]; credited back when the entry rebases at the lane's fill latency. */
        @Volatile var chase8094 = 0.0
        val recent8071 = ArrayDeque<DoubleArray>()   // [atMs, gross], the last ~30 s of marks
        /**
         * V5.0.8366 §HONEST_LABELS — true for an observation opened by this build (its path is recorded from the first mark):
         * its stop grades at the first mark at or below the stop and its ladder at a peak held for the sell latency
         * (HonestExit8366). A restored observation keeps the previous grading.
         */
        @Volatile var honest8366 = false
        /** V5.0.8366 — the level held for the measured sell latency (HonestExit8366.heldPeakStep8366), and its window. */
        @Volatile var heldPeak8366 = 0.0
        val recent8366 = ArrayDeque<DoubleArray>()
        /** V5.0.8366 — the path's new lows in order: [atMs, gross, prevAtMs, prevGross] (HonestExit8366.noteLow8366). */
        val lows8366 = ArrayList<DoubleArray>()
        /**
         * V5.0.8379 — LaunchWindow8379 in the label: when the decision was a launch-window entry (MOONSHOT / SHITCOIN /
         * EXPRESS, witnessed birth under 30 min), the window's end; 0 = not a window entry. The lane stop is graded only
         * after it, on [postLows8379] (the path's new lows after the window, same format as [lows8366]); inside it the
         * label grades the window floor, as live now trades it.
         */
        @Volatile var windowEnd8379 = 0L
        val postLows8379 = ArrayList<DoubleArray>()
        /** V5.0.8418 — when live would have released the launch-window hold (metrics collapsed: LifecycleHold8065.metricsCollapsed8109). */
        @Volatile var windowReleasedAtMs8418 = 0L
        @Volatile var windowReleaseGross8418 = Double.NaN
        /** V5.0.8006 — the exit-profile sample waits for the end of a followed run. */
        @Volatile var exitDeferred8006: Boolean = false
        /** V5.0.7972 — the decision's discrete facts (SpecialistMiner7972). */
        @Volatile var feats7972: List<String> = emptyList()
        /** V5.0.8537 — the decision's frozen features and the edge model's prediction made then (EdgeModel8537). */
        var expertAtDecision: com.lifecyclebot.engine.ExpertTraderKnowledge7813.Snapshot? = null
        @Volatile var edge8537: EdgeModel8537.Snap? = null
        /** V5.0.8539 — hydrated depth at the decision (curve / chain / map); 0 = unread (LearnedLiquidityFloor8539). */
        @Volatile var liqUsd8539: Double = 0.0
        /** V5.0.7997 — the label value last given to the learners, the next checkpoint, liquidity at the decision. */
        @Volatile var bookedNet7997 = Double.NaN
        @Volatile var bookedGross7997 = Double.NaN
        /** V5.0.8418 — reached its 5-minute read (counted once in labelMarks8418 due); its first mark there came after the window. */
        @Volatile var due8418 = false
        /** V5.0.8535 §P0-01 — the 1-minute and 1-hour reads counted due (coverage by horizon). */
        @Volatile var due15_8535 = false
        @Volatile var due240_8535 = false
        @Volatile var lateMark8418 = false
        /** V5.0.8410 — decay each destination carried at booking (tally keys / allocator nodes); empty = legacy, weight unknown. */
        @Volatile var tallyScales8410: DoubleArray = DoubleArray(0)
        @Volatile var allocScales8410: DoubleArray = DoubleArray(0)
        /** V5.0.8418 — the first buy-path gate that ruled on this candidate (GateOutcome8418), "" = none; not persisted. */
        @Volatile var gate8418: String = ""
        /** V5.0.8715 — the learned door lane authority overrode to admit this coin; its outcome is that door's record too. */
        @Volatile var advisoryGate8715: String = ""
        /** V5.0.8709 — the origin x pool-depth cohort this observation is also graded into ("" = none). Not persisted. */
        @Volatile var origin8701: String = ""
        @Volatile var ck7997 = 0
        @Volatile var entryLiq7997 = 0.0
        /** V5.0.8063 — the coin's kind at the decision: "VENUE/NARRATIVE" (memes), blank for cross-asset rows. */
        @Volatile var kind8063: String = ""
        /** V5.0.8468 — the lane's OWN native brain qualified this coin at the decision (its trigger fired); not persisted. */
        @Volatile var nativeTrigger8468 = false
        /** V5.0.8080 — the label on the LONG-HOLD exit family (the setup books grade each setup on its own family). */
        @Volatile var bookedLongNet8080 = Double.NaN
        @Volatile var bookedLongGross8080 = Double.NaN
        /** V5.0.8324 — a mark of this label was chain-witnessed (sticky); and the provenance of the value last booked. */
        @Volatile var witnessed8324 = false
        @Volatile var bookedWitness8324 = false
    }

    /** Per-horizon tallies for one cell (or one aggregate key). */
    class Tally {
        var n15 = 0; var sum15 = 0.0; var win15 = 0
        var n60 = 0; var sum60 = 0.0; var sumSq60 = 0.0; var win60 = 0; var runner60 = 0
        /** V5.0.8324 — local runner labels whose marks were chain-witnessed (provenance). */
        var witness60 = 0
        var n240 = 0; var sum240 = 0.0; var win240 = 0
        var lost = 0
        /** V5.0.8505 — carried fraction of the faded lost count (lost fades with the 60 slot it is a share of). */
        var lostFrac8505 = 0.0
        /** V5.0.8410 — cumulative decay of the 60 slot (ln k per window pass, falling); a revision owns exp(logScale60 - bookScale). */
        var logScale60 = 0.0
        /** V5.0.8410 — fractional remainders of weighted count revisions (wins / runners / witnessed), carried, never dropped. */
        var winFrac60 = 0.0; var runnerFrac60 = 0.0; var witnessFrac60 = 0.0
        /** V5.0.8420 — carried fractions of the faded 15 / 240 win counts. */
        var win15Frac8420 = 0.0; var win240Frac8420 = 0.0
        /**
         * V5.0.8014 — fresh edge: a horizon past [w] labels keeps its most recent ~[w] (the oldest fade
         * proportionally), so the cell record watch-first and every lane read is recent, not all-time.
         */
        fun window8014(w: Int) {
            // V5.0.8420 — every count fades exactly (DecayCount8420); a rounded count below ~w/2 never fell.
            if (n15 > w) { val k = w.toDouble() / n15; n15 = w; sum15 *= k
                DecayCount8420.fade(win15, win15Frac8420, k).let { (c, r) -> win15 = c.coerceIn(0, n15); win15Frac8420 = r } }
            if (n60 > w) {
                val k = w.toDouble() / n60; n60 = w; sum60 *= k; sumSq60 *= k
                logScale60 += kotlin.math.ln(k)   // V5.0.8410
                DecayCount8420.fade(win60, winFrac60, k).let { (c, r) -> win60 = c.coerceIn(0, n60); winFrac60 = r }
                DecayCount8420.fade(runner60, runnerFrac60, k).let { (c, r) -> runner60 = c.coerceIn(0, n60); runnerFrac60 = r }
                DecayCount8420.fade(witness60, witnessFrac60, k).let { (c, r) -> witness60 = c.coerceIn(0, runner60); witnessFrac60 = r }
                // V5.0.8505 — lost fades with the window it is a share of. It never did: n60 was held at the window while lost
                // grew for the life of the install, so resolvedShare (= n60 / (n60 + lost)) fell forever (8504 AGG|ADMITTED
                // n300 cov33%) and read restart history as current pricing failure.
                DecayCount8420.fade(lost, lostFrac8505, k).let { (c, r) -> lost = c.coerceAtLeast(0); lostFrac8505 = r }
            }
            if (n240 > w) { val k = w.toDouble() / n240; n240 = w; sum240 *= k
                DecayCount8420.fade(win240, win240Frac8420, k).let { (c, r) -> win240 = c.coerceIn(0, n240); win240Frac8420 = r } }
        }
        fun encode(): String = "$n15,$sum15,$win15,$n60,$sum60,$sumSq60,$win60,$runner60,$n240,$sum240,$win240,$lost,$witness60,INDEPENDENT_CURVE_8365"  // Proof epoch
        fun decode(s: String): Boolean {
            val f = s.split(',')
            if (f.size !in 12..14) return false
            n15 = f[0].toIntOrNull() ?: 0; sum15 = f[1].toDoubleOrNull() ?: 0.0; win15 = f[2].toIntOrNull() ?: 0
            n60 = f[3].toIntOrNull() ?: 0; sum60 = f[4].toDoubleOrNull() ?: 0.0; sumSq60 = f[5].toDoubleOrNull() ?: 0.0
            win60 = f[6].toIntOrNull() ?: 0; runner60 = f[7].toIntOrNull() ?: 0
            n240 = f[8].toIntOrNull() ?: 0; sum240 = f[9].toDoubleOrNull() ?: 0.0; win240 = f[10].toIntOrNull() ?: 0
            lost = f[11].toIntOrNull() ?: 0
            witness60 = if (f.getOrNull(13) == "INDEPENDENT_CURVE_8365")
                (f.getOrNull(12)?.toIntOrNull() ?: 0).coerceIn(0, maxOf(runner60, 0)) else 0  // Prior cap-only witnesses must re-qualify
            return true
        }
    }

    /** Read-only view of a cell at THE read (slot "60", 5 minutes since V5.0.7946), the ladder's horizon. */
    data class CellStat(
        val key: String,
        val n60: Int,
        val meanNet60Pct: Double,
        val winRate60: Double,
        val runnerRate60: Double,
        val stderr60Pct: Double,
        val lost: Int,
        /** V5.0.7769 — the 4-hour record, where a launch cell's runners pay. */
        val n240: Int = 0,
        val meanNet240Pct: Double = 0.0,
        /**
         * V5.0.8185 — the stored mean is impossible under the per-label plausibility cap (RobustLabelRead8185).
         * V5.0.8322 — and its tail is NOT real (fewer than [TAIL_RUNNERS_8322] local runners): only then does it veto.
         */
        val outlierSuspect8185: Boolean = false,
        /** V5.0.8322 — the raw mean exceeds the plausibility bound (a flag for the screen; with a real tail it vetoes nothing). */
        val outlierFlag8322: Boolean = false,
    ) {
        /** Share of observations that reached a label at the read rather than losing their mark. */
        val resolvedShare: Double get() = if (n60 + lost > 0) n60.toDouble() / (n60 + lost) else 0.0
    }

    private val pending = ConcurrentHashMap<String, Obs>()          // key = mint|lane
    /** V5.0.8430 — when this process started: a window that closed before it is a downtime window (DowntimeRecovery8430). */
    private val bootMs8430 = System.currentTimeMillis()

    // ── V5.0.8169 §STALE_LEARNED_REFUSALS ──
    // 5.0.8168: ~697 CELL / STAGE_PROVEN_LOSING_8136 refusals rested on cells graded under stops this build calls
    // defective (BLUECHIP / QUALITY 5%, 9% fresh-meme floors: stoppedBeforePeak=750), and LearningEpoch8164 stayed 0
    // because nobody pressed reset. Beside every cell a FRESH tally books only observations decided after the grading
    // epoch: the later of the build's grading version ([GRADING_VERSION_8169] — a change starts a new epoch at
    // attach, no manual reset needed) and the learning reset line (LearningEpoch8164). A loss pattern may refuse only on
    // fresh labels (CandidateAdmission8136.cellLoss8169); older evidence decays to neutral, never to allow. Local
    // labels only: the hive's tallies carry no epoch.
    // V5.0.8418 — new grading contract: labels stop paying the ~9 % mark-based "cost" (§MARK_GAP_IS_NOT_A_FEE), so loss
    // patterns graded under the old charge (~6 pt too negative) decay to neutral; positive evidence keeps its history.
    private const val GRADING_VERSION_8169 = 8527
    private const val FRESH_KEY_8169 = "fresh_cells_8527"
    private const val FRESH_EPOCH_KEY_8169 = "fresh_epoch_8169"
    private const val GRADING_VERSION_KEY_8169 = "grading_version_8169"
    private val fresh8169 = ConcurrentHashMap<String, Tally>()
    @Volatile private var freshEpochMs8169 = 0L
    private val freshBooked8169 = AtomicLong(0)
    private val freshSkippedPreEpoch8169 = AtomicLong(0)

    /** Pure: the grading epoch — the later of the build's grading start and the learning reset line. */
    fun gradingEpoch8169(gradingStartMs: Long, learningEpochMs: Long): Long = maxOf(gradingStartMs, learningEpochMs, 0L)

    /** Pure: may an observation decided at [decidedAtMs] teach a loss pattern under epoch [epochMs]? (0 = no epoch yet: none). */
    fun freshObservation8169(decidedAtMs: Long, epochMs: Long): Boolean = epochMs > 0L && decidedAtMs >= epochMs

    /** A newer learning reset than the fresh book's epoch starts the fresh book over. */
    private fun syncEpoch8169() {
        val reset = try { LearningEpoch8164.epochMs8164() } catch (_: Throwable) { 0L }
        if (reset > freshEpochMs8169) synchronized(fresh8169) {
            if (reset > freshEpochMs8169) { fresh8169.clear(); freshEpochMs8169 = reset }
        }
    }
    private val lastSeenAt = ConcurrentHashMap<String, Long>()       // key = mint|lane
    private val cells = ConcurrentHashMap<String, Tally>()
    @Volatile private var prefs: SharedPreferences? = null
    private val observed = AtomicLong(0)
    private val skippedNoPrice = AtomicLong(0)
    private val skippedRecent = AtomicLong(0)
    private val skippedFull = AtomicLong(0)
    private val booked15 = AtomicLong(0)
    private val booked60 = AtomicLong(0)
    private val booked240 = AtomicLong(0)
    private val lostMark = AtomicLong(0)
    private val offWatchCurvePriced7753 = AtomicLong(0)
    private val bookingsSincePersist = AtomicLong(0)
    @Volatile private var lastPersistMs = 0L
    private val persistDeferredCount8144 = AtomicLong(0)

    /** V5.0.8144 — census: open observations, cell tallies, dedupe stamps, off-watch marks; saves deferred by pressure. */
    fun pendingCount8144(): Int = pending.size
    fun cellCount8144(): Int = cells.size
    fun seenCount8144(): Int = lastSeenAt.size + offWatchAttemptAt7809.size + lastAdmit8094.size
    fun persistDeferred8144(): Long = persistDeferredCount8144.get()

    // ── aggregate keys, kept beside the cells in the same table ──
    private const val AGG_ADMITTED = "AGG|ADMITTED"
    private const val AGG_REFUSED = "AGG|REFUSED"
    private fun laneKey(lane: String) = "LANE|$lane"
    /** V5.0.8094 — the lane's admitted and refused cohorts apart: does refusing in this lane avoid losers? */
    private fun laneDecisionKey8094(lane: String, admitted: Boolean) = "LANEDEC|${lane.trim().uppercase()}|${if (admitted) "A" else "R"}"
    /** V5.0.8094 — (admitted, refused) 60-minute records of [lane]. */
    fun laneDecisionStats8094(lane: String): Pair<CellStat?, CellStat?> =
        cellStat(laneDecisionKey8094(lane, true)) to cellStat(laneDecisionKey8094(lane, false))
    private fun sourceKey(src: String) = "SRC|$src"
    /** V5.0.8539 — the main stack's ADMITTED cohort in [lane], executable: the yardstick the Cortex must beat (CortexVsStack8539). */
    fun laneAdmittedExecutable8539(lane: String): CellStat? = lane.trim().uppercase().let { executable8187(cellStat(laneDecisionKey8094(it, true)), it) }
    /** V5.0.8539 — lane x liquidity band at the decision: the record that opens or closes a lane's floor (LearnedLiquidityFloor8539). */
    fun liqBandKey8539(lane: String, liqUsd: Double) = "LIQB|${lane.trim().uppercase()}|${com.lifecyclebot.engine.LearnedLiquidityFloor8539.band(liqUsd)}"
    fun liqBandStat8539(lane: String, liqUsd: Double): CellStat? = cellStat(liqBandKey8539(lane, liqUsd))
    /** V5.0.8292 — read-only: the 60-minute record of a discovery source's family (scanner ranking / probation throttle). */
    fun sourceStat8292(source: String): CellStat? = cellStat(sourceKey(sourceFamily(source)))
    /** V5.0.7928 — lane x lifecycle stage: does this lane's play pay at this stage? */
    fun stageKey7928(lane: String, stage: String) = "STAGE|${lane.trim().uppercase()}|${stage.trim().uppercase()}"
    /** V5.0.8019 — lane x cap band across every source and age: the record that says "this lane loses under $10k". */
    fun bandKey8019(lane: String, mcapUsd: Double) = "BAND|${lane.trim().uppercase()}|${mcapBand(mcapUsd)}"
    /**
     * V5.0.8468 — the lane's own trigger record: every coin the lane's native brain qualified (OwnershipChain8364.nativeEligible8364
     * at the decision), whatever the shared setup menu called it. Never pruned (a lane aggregate, like LANE|).
     */
    fun triggerKey8468(lane: String) = "TRIGGER|${lane.trim().uppercase()}|NATIVE"
    /** V5.0.8468 — LiveEdgeGate7877 / LaneTrigger8468: [lane]'s trigger record (local + hive), or null. */
    fun laneTriggerStat8468(lane: String): CellStat? = cellStat(triggerKey8468(lane))
    /** V5.0.8019 — LiveEdgeGate7877: the lane's 60-minute record in [ts]'s cap band (local + hive). */
    fun bandStatFor8019(ts: TokenState, lane: String): CellStat? = cellStat(bandKey8019(lane, TrustedMcap8019.mcap8019(ts)))
    // V5.0.8709 — the coin's origin x pool-depth cohort (MarketCohort8701) is one more book the SAME observation is graded
    // into. 8701 filed a second observation per coin for it: every pending label, its feature row and the restart
    // snapshot doubled (learning store 13 MB -> 22 MB; Runtime Smoke ANR in SplashActivity on restart, 8706 and 8708).
    private fun keysOf7928(o: Obs): List<String> =
        laneKeys7928(o).let { if (o.origin8701.isEmpty()) it else it + laneKey(o.origin8701) }

    private fun laneKeys7928(o: Obs): List<String> {
        val base = listOf(o.cell, laneKey(o.lane), sourceKey(o.source), if (o.admitted) AGG_ADMITTED else AGG_REFUSED,
            "BAND|${o.lane}|${o.cell.split('|').getOrNull(2) ?: "MC_UNKNOWN"}",  // V5.0.8019 — lane x cap band
            laneDecisionKey8094(o.lane, o.admitted))
        // V5.0.7930 — plan pseudo-lanes (PLANWAIT_/PLANADMIT_) carry no stage book.
        val pseudo = o.lane.startsWith("PLANWAIT_") || o.lane.startsWith("PLANADMIT_") || ExpertSetups8692.setupCohort8692(o.lane)
        // UNKNOWN is not a measured lifecycle stage. Never teach a catch-all
        // STAGE|lane|UNKNOWN that later refuses legitimate classified strategies.
        val staged = if (o.stage.isBlank() || o.stage.equals("UNKNOWN", true) || pseudo) base else base + stageKey7928(o.lane, o.stage)
        // V5.0.8468 — the lane's own trigger record: coins its native brain qualified, graded like every label.
        val withStage = if (o.nativeTrigger8468 && !pseudo) staged + triggerKey8468(o.lane) else staged
        val withLiq8539 = if (pseudo) withStage else withStage + liqBandKey8539(o.lane, o.liqUsd8539)  // V5.0.8539
        // V5.0.8063 — the coin's kind: lane x venue x cap band, and lane x narrative.
        val k = o.kind8063.split('/')
        if (pseudo || k.size != 2 || k[0].isBlank()) return withLiq8539
        return withLiq8539 + kindKey8063(o.lane, k[0], o.cell.split('|').getOrNull(2) ?: "MC_UNKNOWN") + narrKey8063(o.lane, k[1])
    }

    @Synchronized
    fun attach(context: Context) {
        if (prefs != null) return
        val p = try {
            com.lifecyclebot.engine.OffloadedPrefs8501.of(context, PREFS_7731)
        } catch (_: Throwable) { return }
        prefs = p
        try { timingBook8528.restore(p.getString("setup_timing_8528", null)) } catch (_: Exception) { PipelineHealthCollector.labelInc("SETUP_TIMING_RESTORE_FAILED_8528") }
        val checkpoint8527 = modelledCheckpoint8527(p)
        pairedRestore8401 = checkpoint8527.paired
        try { com.lifecyclebot.engine.cortex.CrossAssetCortex7931.restorePriceIdentities8402(p.getString(PRICE_IDENTITIES_KEY_8402, null)) }
        catch (_: Exception) { PipelineHealthCollector.labelInc("FORWARD_PRICE_IDENTITY_RESTORE_FAILED_8402") }
        try { openRowStore8409(context, p) } catch (_: Throwable) {}   // V5.0.8409 — rows leave the checkpoint file
        val rows8409 = loadRows8409(p)
        rows8409.forEach { (key, value) -> if (key.startsWith(TERMINAL_PREFIX_8395)) terminalOutbox8395[key] = value }
        forwardOutbox8404.restore(forwardPayloads8527(rows8409))
        try { FreshLaunchSelector7737.attach(context) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.CellAllocator7962.attach(context) } catch (_: Throwable) {}
        try { SpecialistMiner7972.attach7972(context) } catch (_: Throwable) {}
        try {
            checkpoint8527.cells?.split(';')?.forEach { row ->
                val sep = row.lastIndexOf('=')
                if (sep <= 0) return@forEach
                val key = row.substring(0, sep)
                val t = Tally()
                if (t.decode(row.substring(sep + 1))) cells[key] = t
            }
        } catch (_: Throwable) {}
        try { restoreFresh8169(p, System.currentTimeMillis()) } catch (_: Throwable) {}
        // V5.0.7738 — once: cells whose 60-minute mean was built on basis artefacts start over.
        try {
            if (!p.getBoolean("purged7738", false)) {
                for ((k, t) in cells.entries.toList()) {
                    val implausible = synchronized(t) { t.n60 > 0 && t.sum60 / t.n60 > 150.0 }
                    if (implausible) {
                        val fresh = Tally()
                        fresh.lost = synchronized(t) { t.lost }
                        cells[k] = fresh
                        purgedCells7738.incrementAndGet()
                    }
                }
                p.edit().putBoolean("purged7738", true).apply()
                if (purgedCells7738.get() > 0) {
                    PipelineHealthCollector.labelInc("FORWARD_LABEL_CELLS_PURGED_7738")
                    ForensicLogger.lifecycle("FORWARD_LABEL_CELLS_PURGED_7738", "purged=${purgedCells7738.get()} bar=mean60<=150% action=basis_artefact_cells_restarted")
                }
            }
        } catch (_: Throwable) {}
        try {
            val n = restorePending7735(checkpoint8527.pending, System.currentTimeMillis())
            if (n > 0) {
                restoredPending7735.addAndGet(n.toLong())
                PipelineHealthCollector.labelInc("FORWARD_LABELER_PENDING_RESTORED_7735")
                ForensicLogger.lifecycle("FORWARD_LABELER_PENDING_RESTORED_7735", "restored=$n action=open_observations_survive_the_restart")
            }
        } catch (_: Throwable) {}
    }

    internal fun pendingPriceKeys8402(): Set<String> = pending.values.mapTo(HashSet()) { it.mint }
    private const val PRICE_IDENTITIES_KEY_8402 = "price_identities_8402"
    private const val PAIRED_CORTEX_KEY_8401 = "paired_cortex_8527"
    @Volatile private var pairedRestore8401: String? = null
    @Volatile private var pairedRestoreSinceMs8418 = 0L
    private val pairedSkipped8418 = AtomicLong(0)
    private const val PAIRED_RESTORE_MAX_WAIT_MS_8418 = 5L * 60_000L

    /** Pure (V5.0.8418): drop the paired blob — corrupt, or the Cortex has not restored it within [maxWaitMs]. */
    internal fun pairedRestoreAbandoned8418(corrupt: Boolean, waitedMs: Long, maxWaitMs: Long): Boolean = corrupt || waitedMs >= maxWaitMs

    private val timingBook8528 = SetupTimingGrades8528.Book()
    internal fun freezeTiming8528(mint: String, lane: String, atMs: Long, setup: String?, band: String) {
        val o = pending["$mint|${lane.trim().uppercase()}"]?.takeIf { it.atMs == atMs } ?: return
        if (setup != null && o.timingPlan8528 == null) o.timingPlan8528 = SetupTimingGrades8528.plan(setup, band)
    }
    internal fun timingAdjustment8528(lane: String, setup: String, band: String): Double =
        timingBook8528.adjustment(lane, setup, band)

    private val checkpointLock8401 = Any()
    private fun persist(force: Boolean = false) = synchronized(checkpointLock8401) { persistLocked8401(force) }

    private fun persistLocked8401(force: Boolean) {
        // Never overwrite a checkpoint whose original predictions have not been restored yet.
        if (pairedRestore8401 != null) return
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistMs < 60_000L) return
        // V5.0.8144 — the cells + pending blob (up to 2,000 rows with their facts: ~1.5M chars) is rebuilt and rewritten
        // whole into SharedPreferences; under heap pressure that rebuild is spaced out (forced saves still run).
        if (!force && now - lastPersistMs < com.lifecyclebot.engine.MemoryBounds8144.saveIntervalMs8144(60_000L,
                try { com.lifecyclebot.engine.MemoryGuard7977.pressure8144() } catch (_: Throwable) { com.lifecyclebot.engine.RetentionPolicy8136.Pressure.NORMAL })) {
            persistDeferredCount8144.incrementAndGet(); return
        }
        lastPersistMs = now
        bookingsSincePersist.set(0)
        try {
            val enc = cells.entries.joinToString(";") { (k, t) -> "$k=${synchronized(t) { t.encode() }}" }
            // V5.0.8169 — the fresh (post-epoch) tallies ride beside the cells.
            val freshEnc8169 = fresh8169.entries.joinToString(";") { (k, t) -> "$k=${synchronized(t) { t.encode() }}" }
            // Freeze acknowledgements BEFORE selecting observations. A terminal created during encoding must retain
            // its tombstone, even if this snapshot still contains its old observation row.
            val acknowledged = terminalOutbox8395.keys.filter { deliveredTerminals8395.contains(it) }
            val selected = selectedPending8401(now)
            // First-label predictions are paired here. Already-trained progressive traces stay with the atomic
            // Cortex ledger checkpoint; do not decode its cold archive under the capture monitor on every save.
            // V5.0.8418 — a Cortex that is not loaded (checkpointForward8401 throws) no longer stops the labeler persisting its
            // own rows: the cells and observations are saved and the prior paired checkpoint is retained unchanged.
            val paired = try { com.lifecyclebot.engine.cortex.Cortex7885.checkpointForward8401(
                // V5.0.8541 — only prediction observations (Cortex / native enrollment) can own a Cortex prediction at their
                // own time; gate / plan / missed-lane observations never do and were counted as ORIGINAL_UNAVAILABLE (1,495).
                selected.filter { (!it.done60 || (!it.done240 && it.targetHorizon8521 > H60_MS_7731)) &&
                    com.lifecyclebot.engine.NativeDecisionEnrollment8372.predictionObservation8404(it.reason7967) }
                    .map { "${it.mint}|${it.lane}|${it.atMs}" }.toSet()) }
                catch (_: Exception) { pairedSkipped8418.incrementAndGet(); null }
            val committed = p.edit().putString(CELLS_KEY_7946, enc).putString(PENDING_KEY_7946, encodePending7735(now, selected))
                .also { editor -> if (paired != null) editor.putString(PAIRED_CORTEX_KEY_8401, paired) }
                .putString(PRICE_IDENTITIES_KEY_8402, com.lifecyclebot.engine.cortex.CrossAssetCortex7931
                    .checkpointPriceIdentities8402(selected.mapTo(HashSet()) { it.mint }))
                .putString("setup_timing_8528", timingBook8528.encode())
                .putString(FRESH_KEY_8169, freshEnc8169).putLong(FRESH_EPOCH_KEY_8169, freshEpochMs8169)
                .remove("cells").remove("pending").also { editor ->
                    // Same preference transaction removes the old pending row and its delivered tombstone.
                    acknowledged.forEach { editor.remove(it) }
                }.commit()
            if (committed) acknowledged.forEach { terminalOutbox8395.remove(it); deliveredTerminals8395.remove(it) }
            else { terminalStorageFailures8401.incrementAndGet(); lastPersistMs = 0L }
        } catch (_: Exception) { terminalStorageFailures8401.incrementAndGet(); lastPersistMs = 0L }
    }

    private fun selectedPending8401(nowMs: Long): List<Obs> = pending.values.asSequence()
        .filterNot { terminalOutbox8395.containsKey(terminalKey8395(it)) }
        // Expiry is a durable disposition, not a snapshot filter. A failed outcome/terminal write still owns this row.
        .sortedWith(compareByDescending<Obs> { followed8013(it, nowMs) }.thenByDescending { it.atMs })
        .take(MAX_PERSISTED_PENDING_7735).toList()

    private fun encodePending7735(nowMs: Long, selected: List<Obs> = selectedPending8401(nowMs)): String {
        val fs = FIELD_SEP_7735.toString()
        if (selected.isEmpty()) return ""
        val rows = selected.asSequence().map { o ->
                listOf(
                    o.mint, o.symbol.replace(FIELD_SEP_7735, ' ').replace(ROW_SEP_7735, ' '), o.cell, o.source, o.lane,
                    if (o.admitted) "1" else "0", o.score.toString(), o.quality, o.regime, o.phase,
                    o.entryPrice.toString(), o.costPct.toString(), o.atMs.toString(),
                    if (o.done15) "1" else "0", if (o.done60) "1" else "0", if (o.done240) "1" else "0", o.peakPct.toString(),
                    o.entryMcap.toString(), o.stage, o.lastPx.toString(), o.lastPxAtMs.toString(),
                    o.setup7955.replace(FIELD_SEP_7735, ' ').replace(ROW_SEP_7735, ' '), o.peakAtMs7955.toString(),
                    // V5.0.8013 — the progressive-grading state rides along (fields 23..30).
                    ckField8416(o.ck7997), o.bookedNet7997.toString(), o.bookedGross7997.toString(), o.entryLiq7997.toString(),
                    if (o.exitDeferred8006) "1" else "0", o.minPct7962.toString(), o.dipBeforePeak7962.toString(),
                    o.feats7972.joinToString("\u0002") { it.replace(FIELD_SEP_7735, ' ').replace(ROW_SEP_7735, ' ') },
                    // V5.0.8016 — the refusal reason survives a restart (missed runners read UNKNOWN without it).
                    o.reason7967.replace(FIELD_SEP_7735, ' ').replace(ROW_SEP_7735, ' '),
                    // V5.0.8063 — the coin's kind (field 33).
                    o.kind8063.replace(FIELD_SEP_7735, ' ').replace(ROW_SEP_7735, ' '),
                    o.entryStyle8077,
                    observationState8521(o).toString(),
                    // V5.0.8410 (field 36) — booking-time decay per tally key / allocator node; a revision's remaining weight.
                    o.tallyScales8410.joinToString(":") { "%.5f".format(java.util.Locale.ROOT, it) } + "~" +
                        o.allocScales8410.joinToString(":") { "%.5f".format(java.util.Locale.ROOT, it) } +
                        // V5.0.8418 — the miner stamps ride as a third half (the boot id exact; NaN stamps stay NaN).
                        (if (o.minerScales8418.isEmpty()) "" else "~" + o.minerScales8418.joinToString(":") { it.toString() }),
                ).joinToString(fs)
            }
        return com.lifecyclebot.engine.cortex.ForwardCheckpoint8401.packRows8521(rows, ROW_SEP_7735)
    }

    private fun packedPending8521(raw: String): String =
        if (raw.isEmpty()) raw else com.lifecyclebot.engine.cortex.ForwardCheckpoint8401.pack(raw)

    /** All evidence that changes a replayed return travels with its immutable entry and frozen prediction. */
    private fun observationState8521(o: Obs): org.json.JSONObject = synchronized(o.recent8071) {
        fun rows(values: Collection<DoubleArray>) = org.json.JSONArray().also { out ->
            values.forEach { row -> out.put(org.json.JSONArray(row.toList())) }
        }
        org.json.JSONObject().put("v", 8521).put("entry", o.basis8386.json())
            .put("decision", o.decisionBasis8386.json()).put("last", o.lastMark8386?.json())
            .put("accepted8395", o.lastAcceptedMark8395?.json())
            .put("staged8418", o.staged8418?.joinToString(":") { it.toString() } ?: "")
            .put("stagedProof8521", o.stagedProof8521?.encode8395().orEmpty())
            .put("rebased", o.rebased8076).put("lateEntry", o.lateEntry8521)
            .put("timingPlan8528", o.timingPlan8528?.json())
            .put("timingDone8528", org.json.JSONArray(o.timingDone8528.toList()))
            .put("targetHorizon", o.targetHorizon8521).put("interimUnavailable", o.interimUnavailable8521)
            .put("firstReadValid", o.firstReadValid8521)
            .put("chase", o.chase8094).put("honest", o.honest8366)
            .put("giveback5", o.giveback5_7955.toString())
            .put("heldPeak", o.heldPeak8071).put("heldPeakHonest", o.heldPeak8366)
            .put("windowEnd", o.windowEnd8379).put("windowRelease8418", o.windowReleasedAtMs8418)
            .put("windowReleaseGross8418", o.windowReleaseGross8418.toString())
            .put("recent", rows(o.recent8071.toList())).put("recentHonest", rows(o.recent8366.toList()))
            .put("lows", rows(o.lows8366)).put("postLows", rows(o.postLows8379))
            .put("longNet", o.bookedLongNet8080.toString()).put("longGross", o.bookedLongGross8080.toString())
            .put("witness", o.witnessed8324).put("bookedWitness", o.bookedWitness8324)
            .put("nativeTrigger", o.nativeTrigger8468).put("gate", o.gate8418).put("advisoryGate", o.advisoryGate8715)
            // V5.0.8571 — the frozen edge snapshot and the decision's liquidity band survive a restart.
            .put("expert8629", o.expertAtDecision?.let { com.lifecyclebot.engine.ExpertTraderKnowledge7813.snapshotJson(it) })
            .put("edge8571", o.edge8537?.let { EdgeModel8537.encodeSnap8571(it) }).put("liq8571", o.liqUsd8539.toString())
    }

    private fun restoreObservationState8521(o: Obs, e: org.json.JSONObject, nowMs: Long) {
        if (e.optInt("v") < 8521) return
        o.rebased8076 = e.optBoolean("rebased"); o.lateEntry8521 = e.optBoolean("lateEntry")
        o.targetHorizon8521 = e.optLong("targetHorizon", H60_MS_7731)
        o.timingPlan8528 = SetupTimingGrades8528.restorePlan(e.optJSONObject("timingPlan8528"))
        e.optJSONArray("timingDone8528")?.let { a -> for (i in 0 until a.length()) o.timingDone8528.add(a.optLong(i)) }
        o.interimUnavailable8521 = e.optBoolean("interimUnavailable")
        o.firstReadValid8521 = e.optBoolean("firstReadValid")
        o.chase8094 = e.optDouble("chase", 0.0); o.honest8366 = e.optBoolean("honest")
        o.giveback5_7955 = e.optString("giveback5").toDoubleOrNull() ?: Double.NaN
        o.heldPeak8071 = e.optDouble("heldPeak", 0.0); o.heldPeak8366 = e.optDouble("heldPeakHonest", 0.0)
        o.windowEnd8379 = e.optLong("windowEnd")
        o.bookedLongNet8080 = e.optString("longNet").toDoubleOrNull() ?: Double.NaN
        o.bookedLongGross8080 = e.optString("longGross").toDoubleOrNull() ?: Double.NaN
        o.witnessed8324 = e.optBoolean("witness"); o.bookedWitness8324 = e.optBoolean("bookedWitness")
        o.nativeTrigger8468 = e.optBoolean("nativeTrigger"); o.gate8418 = e.optString("gate"); o.advisoryGate8715 = e.optString("advisoryGate")
        o.expertAtDecision = e.optJSONObject("expert8629")?.let { com.lifecyclebot.engine.ExpertTraderKnowledge7813.snapshotFromJson(it) }
        o.edge8537 = EdgeModel8537.decodeSnap8571(e.optJSONObject("edge8571"))  // V5.0.8571
        o.liqUsd8539 = e.optString("liq8571").toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
        o.stagedProof8521 = com.lifecyclebot.engine.cortex.CortexOutcomeProof8395.decode8395(e.optString("stagedProof8521"))
        fun restoreRows(name: String, width: Int, limit: Int, add: (DoubleArray) -> Unit) {
            val rows = e.optJSONArray(name) ?: return
            for (i in 0 until minOf(rows.length(), limit)) {
                val a = rows.optJSONArray(i) ?: continue
                if (a.length() != width) continue
                val row = DoubleArray(width) { j -> a.optDouble(j, Double.NaN) }
                if (row.all { it.isFinite() } && row[0] > 0.0 && row[0] <= nowMs.toDouble()) add(row)
            }
        }
        restoreRows("recent", 2, 256) { o.recent8071.addLast(it) }
        restoreRows("recentHonest", 2, 256) { o.recent8366.addLast(it) }
        restoreRows("lows", 4, HonestExit8366.MAX_LOWS_8366) { o.lows8366.add(it) }
        restoreRows("postLows", 4, HonestExit8366.MAX_LOWS_8366) { o.postLows8379.add(it) }
        if (o.lateEntry8521 && !o.done60) lateFirst8153["${o.mint}|${o.lane}|${o.atMs}"] = nowMs
    }

    /** V5.0.8013 — an observation the progressive schedule is still following past the hour. */
    private fun followed8013(o: Obs, nowMs: Long): Boolean =
        o.done240 && o.bookedNet7997.isFinite() && scheduleOpen8013(o.ck7997, nowMs - o.atMs)

    private fun checkpointDue8013(o: Obs, age: Long): Boolean =
        (o.done240 || o.done60) && o.ck7997 in CK_MS_7997.indices && age >= CK_MS_7997[o.ck7997]   // V5.0.8357 — the 15 m checkpoint asks for an off-watch mark before the hour too

    /** Pure. V5.0.8013 — checkpoint [ck] of the schedule is still ahead (within its grace) at [ageMs]. */
    fun scheduleOpen8013(ck: Int, ageMs: Long): Boolean =
        ck in CK_MS_7997.indices && ageMs <= CK_MS_7997[CK_MS_7997.size - 1] + LOST_GRACE_MS_7731

    private fun restorePending7735(enc: String?, nowMs: Long): Int {
        if (enc.isNullOrBlank()) return 0
        var n = 0
        com.lifecyclebot.engine.cortex.ForwardCheckpoint8401.unpack(enc).split(ROW_SEP_7735).forEach { row ->
            val f = row.split(FIELD_SEP_7735)
            if (f.size !in 17..36) return@forEach
            val atMs = f[12].toLongOrNull() ?: return@forEach
            val ck8013 = if (f.size >= 24) ckFromField8416(f[23]) else 0
            if (atMs <= 0L) return@forEach
            if (f[15] == "1" && nowMs - atMs > H240_MS_7731 + LOST_GRACE_MS_7731 && !scheduleOpen8013(ck8013, nowMs - atMs)) return@forEach
            val px = f[10].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return@forEach
            val o = Obs(f[0], f[1], f[2], f[3], f[4], f[5] == "1", f[6].toIntOrNull() ?: -1, f[7], f[8], f[9], px, f[11].toDoubleOrNull() ?: 0.0, atMs)
            o.done15 = f[13] == "1"; o.done60 = f[14] == "1"; o.done240 = f[15] == "1"
            o.peakPct = f[16].toDoubleOrNull() ?: 0.0
            if (f.size >= 18) o.entryMcap = f[17].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            if (f.size >= 19) o.stage = f[18]
            if (f.size >= 21) {
                o.lastPx = f[19].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                o.lastPxAtMs = f[20].toLongOrNull() ?: 0L
            }
            if (f.size >= 23) { o.setup7955 = f[21]; o.peakAtMs7955 = f[22].toLongOrNull() ?: 0L }
            if (f.size >= 31) {
                o.ck7997 = ck8013
                o.bookedNet7997 = f[24].toDoubleOrNull() ?: Double.NaN
                o.bookedGross7997 = f[25].toDoubleOrNull() ?: Double.NaN
                o.entryLiq7997 = f[26].toDoubleOrNull() ?: 0.0
                o.exitDeferred8006 = f[27] == "1"
                o.minPct7962 = f[28].toDoubleOrNull() ?: 0.0
                o.dipBeforePeak7962 = f[29].toDoubleOrNull() ?: Double.NaN
                o.feats7972 = f[30].split('\u0002').filter { it.isNotBlank() }
            }
            if (f.size >= 32) o.reason7967 = f[31]
            if (f.size >= 33) o.kind8063 = f[32]
            if (f.size >= 34) o.entryStyle8077 = f[33]
            if (f.size >= 36) try {   // V5.0.8410
                val halves = f[35].split('~')
                fun arr(x: String?) = x.orEmpty().split(':').mapNotNull { it.toDoubleOrNull() }.toDoubleArray()
                o.tallyScales8410 = arr(halves.getOrNull(0)); o.allocScales8410 = arr(halves.getOrNull(1))
                o.minerScales8418 = halves.getOrNull(2)?.split(':')?.map { it.toDoubleOrNull() ?: Double.NaN }?.toDoubleArray() ?: DoubleArray(0)   // V5.0.8418
            } catch (_: Exception) {}
            if (f.size >= 35) try {
                val e = org.json.JSONObject(f[34])
                o.basis8386 = LabelBasis8386.decode(e.getJSONObject("entry"))
                o.decisionBasis8386 = LabelBasis8386.decode(e.getJSONObject("decision"))
                o.lastMark8386 = e.optJSONObject("last")?.let { LabelMark8386.decode(it) }
                o.lastAcceptedMark8395 = e.optJSONObject("accepted8395")?.let { LabelMark8386.decode(it) }
                    ?: o.lastMark8386?.takeIf { LabelEvidence8386.eligible(o.basis8386.mark,it) }
                // V5.0.8418 — a first value the learners already booked survives the restart (never re-booked).
                o.windowReleasedAtMs8418 = e.optLong("windowRelease8418", 0L)
                o.windowReleaseGross8418 = e.optString("windowReleaseGross8418", "").toDoubleOrNull() ?: Double.NaN
                o.staged8418 = e.optString("staged8418", "").takeIf { it.isNotBlank() }?.split(':')?.map { it.toDoubleOrNull() ?: Double.NaN }
                    ?.toDoubleArray()?.takeIf { it.size == 8 }
                restoreObservationState8521(o, e, nowMs)
            } catch (_: Exception) { /* incomplete legacy evidence remains observed-only */ }
            // V5.0.8418 — a pre-8386 row (no typed evidence) keeps its own stored decision price as a LEGACY basis instead of
            // stalling as UNKNOWN until its deadline. Not chain-provable: a 10x move from it stays observed-only (LabelEvidence8386).
            if (f.size < 35) legacyBasisMark8418(o.mint, o.entryPrice, o.atMs)?.let { m ->
                o.basis8386 = LabelBasis8386(m, o.entryMcap); o.decisionBasis8386 = o.basis8386
                try { PipelineHealthCollector.labelInc("FORWARD_LABEL_LEGACY_BASIS_KEPT_8418") } catch (_: Throwable) {}
            }

            if (o.mint.isBlank() || o.lane.isBlank()) return@forEach
            val key = "${o.mint}|${o.lane}"
            if (terminalOutbox8395.containsKey(terminalKey8395(o))) return@forEach
            if (pending.putIfAbsent(key, o) == null) {
                lastSeenAt[key] = atMs
                notePendingMint8475(o.mint, nowMs)
                n++
            }
        }
        return n
    }

    // ── cell naming (pure) ──

    fun sourceFamily(source: String): String {
        val first = source.split(',').firstOrNull()?.trim().orEmpty().uppercase()
        val stripped = first.removePrefix("SCANNER_DIRECT_").removePrefix("SCANNER_HEAL_").removePrefix("SCANNER_")
        val clean = stripped.replace(Regex("[^A-Z0-9_]"), "_").trim('_')
        return clean.ifBlank { "UNKNOWN" }.take(28)
    }

    fun mcapBand(mcapUsd: Double): String = when {
        !mcapUsd.isFinite() || mcapUsd <= 0.0 -> "MC_UNKNOWN"
        mcapUsd < 10_000.0 -> "MC_LT10K"
        mcapUsd < 100_000.0 -> "MC_10K_100K"
        mcapUsd < 1_000_000.0 -> "MC_100K_1M"
        else -> "MC_GT1M"
    }

    fun ageBand(ageMs: Long): String = when {
        ageMs < 0L -> "AGE_UNKNOWN"
        ageMs < 15L * 60_000L -> "AGE_LT15M"
        ageMs < 2L * 60L * 60_000L -> "AGE_15M_2H"
        ageMs < 24L * 60L * 60_000L -> "AGE_2H_24H"
        else -> "AGE_GT24H"
    }

    /**
     * V5.0.8063 — owner: "crypto has meme coins, stable coins, utility coins the works ... the same thing could be said
     * for the meme trader". A meme cell was scanner | lane | cap | age: a pump.fun curve coin, a graduated PumpSwap coin,
     * a Raydium pool and a bonk launch with the same scanner and cap shared one record, as did an AI coin and a cat coin.
     * Two more books ride beside the cells (the cells keep their history): lane x venue x cap band, and lane x narrative.
     *
     * Pure: the venue a meme trades on — CURVE (pump.fun bonding curve), PUMPSWAP (graduated pump coin), BONK,
     * RAYDIUM, METEORA, ORCA or OTHER.
     */
    fun memeVenue8063(mint: String, dex: String, curveActive: Boolean): String {
        val d = dex.trim().uppercase()
        val m = mint.trim()
        return when {
            curveActive -> "CURVE"
            "BONK" in d || m.endsWith("bonk") -> "BONK"
            "PUMPSWAP" in d || "PUMP_SWAP" in d || "PUMP_AMM" in d -> "PUMPSWAP"
            "PUMP" in d -> if (m.endsWith("pump") && !d.contains("FUN")) "PUMPSWAP" else "CURVE"
            "RAYDIUM" in d -> "RAYDIUM"
            "METEORA" in d -> "METEORA"
            "ORCA" in d || "WHIRLPOOL" in d -> "ORCA"
            m.endsWith("pump") -> "PUMPSWAP"
            else -> "OTHER"
        }
    }

    fun kindKey8063(lane: String, venue: String, band: String) = "KIND|${lane.trim().uppercase()}|${venue.trim().uppercase()}|$band"
    private fun narrKey8063(lane: String, narrative: String) = "NARR|${lane.trim().uppercase()}|${narrative.trim().uppercase().ifBlank { "UNKNOWN" }}"

    /** V5.0.8063 — the coin's kind "VENUE/NARRATIVE"; blank for cross-asset rows (their source already names the kind). */
    private fun kindOf8063(ts: TokenState): String {
        val src = ts.source.uppercase()
        if (src.startsWith("XC_") || src.startsWith("XASSET_")) return ""
        val curve = try { CurveReserves7951.realSolIfActive8046(ts.mint) != null } catch (_: Throwable) { false }
        val dex = ts.lastPriceDex.ifBlank { try { ts.tokenMap.dexId } catch (_: Throwable) { "" } }
        val narr = try { com.lifecyclebot.engine.NarrativeDetectorAI.detectNarrative(ts.symbol, ts.name).name } catch (_: Throwable) { "UNKNOWN" }
        return "${memeVenue8063(ts.mint, dex, curve)}/$narr"
    }

    /**
     * V5.0.8208 — the age that keys a cell (writer AND both readers, one rule). A DIRECT canonical birth witness
     * (LaunchBirth8169: create frame, pump coin record, curve create, curve pair, oldest signature, chain mint init) is the
     * token's age; only when nobody witnessed the birth does the cell fall back to time-since-discovery, the pre-8208 key, so
     * existing unwitnessed records keep matching. Discovery age graded a days-old coin found a minute ago as AGE_LT15M, so
     * STAGE/CELL losses and wins were being attributed to the wrong lifecycle band (8205 directive P0 #4).
     */
    fun cellAgeMs8208(ts: TokenState, nowMs: Long): Long {
        val birth = try { LaunchBirth8169.launchBirth8169(ts.mint, nowMs) } catch (_: Throwable) { null }
        if (birth != null && nowMs >= birth.birthMs) return nowMs - birth.birthMs
        // V5.0.8210 — discovery never proves birth, even when the mint first appeared seconds ago.
        return -1L
    }

    fun cellKey(source: String, lane: String, mcapUsd: Double, ageMs: Long): String =
        "${sourceFamily(source)}|${lane.trim().uppercase().ifBlank { "UNKNOWN" }.take(24)}|${mcapBand(mcapUsd)}|${ageBand(ageMs)}"

    private val overridden8715 = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * V5.0.8715 — LiveEdgeGate7877: lane authority admitted ([mint], [lane]) over the learned door [door]. The coin's forward
     * label is filed on that door's record as well (GateOutcome8418), so an overridden door keeps being graded.
     */
    fun noteOverriddenDoor8715(mint: String, lane: String, door: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || door.isBlank()) return
        val key = "$mint|${lane.trim().uppercase().ifBlank { "UNKNOWN" }}"
        val gate = FdgGate8365.gateKey8392(door)
        val p = pending[key]
        if (p != null && nowMs - p.atMs in 0L..GateOutcome8418.ATTACH_MAX_AGE_MS_8418) { if (p.advisoryGate8715.isEmpty()) p.advisoryGate8715 = gate; return }
        if (overridden8715.size > 2_000) overridden8715.entries.removeIf { nowMs - it.value.second > GateOutcome8418.ATTACH_MAX_AGE_MS_8418 }
        overridden8715[key] = gate to nowMs
    }

    /** Pure: the net return of one observation at a mark, in percent. */
    // V5.0.8715 — floored at the stake: 770 of the 72,220 rows of the 8712 edge dataset read below -100 % (to -158 %), a
    // -98 % mark with a 60 % modelled cost subtracted on top. No position loses more than it cost.
    fun netPct(entryPrice: Double, markPrice: Double, costPct: Double): Double =
        ((markPrice / entryPrice - 1.0) * 100.0 - costPct).coerceAtLeast(-100.0)

    // ── observation (called from ExecutableOpenGate.recordFdg for every distinct verdict) ──

    /**
     * V5.0.7961 — the price and time of the admitted decision for (mint, lane), the base
     * EntryChase7961 measures a fill against; any admitted decision on the mint when the
     * lane names differ. Null when none is pending.
     */
    private val lastAdmit8094 = ConcurrentHashMap<String, Pair<Double, Long>>()

    fun decisionPx7961(mint: String, lane: String): Pair<Double, Long>? {
        // V5.0.8094 — the latest admit of this (mint, lane), else of the mint in any lane.
        (lastAdmit8094["$mint|${lane.trim().uppercase()}"]
            ?: lastAdmit8094.entries.filter { it.key.startsWith("$mint|") }.maxByOrNull { it.value.second }?.value)?.let { return it }
        val o = pending["$mint|${lane.trim().uppercase()}"]?.takeIf {
            com.lifecyclebot.engine.NativeDecisionEnrollment8372.executionAdmission(it.admitted, it.reason7967)
        } ?: pending.values.filter { it.mint == mint &&
            com.lifecyclebot.engine.NativeDecisionEnrollment8372.executionAdmission(it.admitted, it.reason7967)
        }.maxByOrNull { it.atMs }
            ?: return null
        return o.entryPrice to o.atMs
    }

    /** V5.0.8094 — the un-rebased price and time of the latest admitted decision on [mint] (any lane); null when none. */
    fun decisionPx8094(mint: String): Pair<Double, Long>? =
        lastAdmit8094.entries.filter { it.key.startsWith("$mint|") }.maxByOrNull { it.value.second }?.value
            ?: pending.values.filter { it.mint == mint &&
                com.lifecyclebot.engine.NativeDecisionEnrollment8372.executionAdmission(it.admitted, it.reason7967)
            }.maxByOrNull { it.atMs }?.let { it.decisionPx8094 to it.atMs }

    /**
     * V5.0.8051 — the (quality, phase) of a forecast signature for [ts] in [lane]: the token's setup shape (PUL / REA /
     * BRE / MOM, NON when no setup fires) and its lifecycle stage (TokenMetricStageRouter).
     */
    fun signature8051(ts: TokenState, lane: String): Pair<String, String> {
        val setup = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.classify(ts, lane) } catch (_: Throwable) { null }
        val shape = if (setup == null || setup == com.lifecyclebot.engine.cortex.LanePlaybook7907.NO_TRIGGER) "NON"
            else try { com.lifecyclebot.engine.learning.TacticSwitcher.seedTactic8049(lane, setup).name.take(3) } catch (_: Throwable) { "NON" }
        val stage = try {
            val snap = com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts)
            if (snap.stage != com.lifecyclebot.engine.TokenMetricStageRouter.Stage.UNKNOWN) snap.stage.name
            else com.lifecyclebot.engine.TokenMetricStageRouter.nearestStage8051(snap.currentVsPeak, snap.drawdownFromPeakPct,
                snap.runupFromLocalLowPct, snap.buyPressurePct, snap.sellPressurePct)
        } catch (_: Throwable) { "UNKNOWN" }
        return shape to stage
    }

    fun observe(mint: String, lane: String, admitted: Boolean, reason: String?, nowMs: Long = System.currentTimeMillis(), score: Int = -1, incumbent8506: Boolean? = null) {
        if (mint.isBlank()) return
        val ts = try { com.lifecyclebot.engine.BotService.status.tokens[mint] } catch (_: Throwable) { null } ?: run {
            skippedNoState8572.incrementAndGet()   // V5.0.8572 — was a silent return: a decision on a coin with no row is counted
            return
        }
        observe(ts, lane, admitted, reason, nowMs, score, incumbent8506)
    }

    fun observe(ts: TokenState, lane: String, admitted: Boolean, reason: String?, nowMs: Long = System.currentTimeMillis(), score: Int = -1, incumbent8506: Boolean? = null) {
        val l = lane.trim().uppercase().ifBlank { "UNKNOWN" }
        // Capture the decision even when the forward labeler cannot establish an entry mark.
        try { LaunchHour8628.observeDecision(ts, l, admitted, nowMs) } catch (_: Exception) { PipelineHealthCollector.labelInc("LAUNCH_HOUR_DECISION_MISSING_8628") }
        val key = "${ts.mint}|$l"
        // V5.0.8418 — the buy-path gate that ruled on a candidate whose observation is already pending (GateOutcome8418).
        pending[key]?.let { p ->
            GateOutcome8418.attachGate8418(p.gate8418, p.atMs, admitted, reason, l, nowMs)?.let { g -> p.gate8418 = g; GateOutcome8418.noteAttached8418() }
        }
        // Freeze the number and source clock BEFORE ancillary setup/liquidity work can race a live mark update.
        // Future-dated state is not fresh; the canonical market fallback still uses its own real observation time.
        val stateMark8527 = LabelMark8386(ts.mint, ts.lastPriceDex, ts.lastPrice, ts.lastPriceUpdate, nowMs, "TOKEN_STATE")
        val entryMark8527 = ForwardEntryMark8527.select(stateMark8527, typedMark8386(ts.mint, { null }, nowMs),
            nowMs, MARK_MAX_AGE_MS_7731, ENTRY_MARK_MAX_AGE_MS_7735) ?: run {
            skippedNoPrice.incrementAndGet()
            return
        }
        try {
            if (entryMark8527 !== stateMark8527) PipelineHealthCollector.labelInc("FORWARD_LABEL_ENTRY_FROM_CANONICAL_MARK_7733")
            else if (nowMs - entryMark8527.observedAtMs > MARK_MAX_AGE_MS_7731) PipelineHealthCollector.labelInc("FORWARD_LABEL_ENTRY_STALE_MARK_7735")
        } catch (_: Throwable) {}
        // V5.0.8165 — an entry price the coin's own bonding curve contradicts is not a buyable price.
        // V5.0.8337 (10d) — compared on the mark's own time: a curve read out of sync with it is no witness against it.
        // V5.0.8571 — the coin's own chain mark (curve account / print / vaults / tape) is the decision's basis when it is fresh
        // (<= 5 s), and it replaces a contradicted price instead of dropping the decision (8562: curveWitness entry=459 lost).
        val basisChosen8571 = preferChainEntry8571(entryMark8527, typedMark8386(ts.mint, { null }, nowMs), nowMs) {
            curveContradicts8165(ts.mint, it.usd, nowMs, it.observedAtMs)
        } ?: run {
            curveRefusedEntry8165.incrementAndGet()
            return
        }
        // V5.0.8627 — a decision priced by the intake seed (cap / supply, PUMP_FUN_BC_SYNTHETIC) has no traded basis: the seed
        // reached the label as "TOKEN_STATE", and the first real curve price 27-30x above it was refused as a suspect re-base,
        // so 13 fresh launches booked +2,600..+2,930 % at 5 min (8622 export) and made PROJECT_SNIPER read PF 3.3 to every
        // learner. The basis is UNKNOWN instead: the first typed mark in the re-base window becomes it (adoptBasis8418).
        val basisMark8571 = seedBasis8627(basisChosen8571, stateMark8527, ts.lastPriceSource)
        if (basisChosen8571 != entryMark8527) {
            if (curveContradicts8165(ts.mint, entryMark8527.usd, nowMs, entryMark8527.observedAtMs)) curveRebasedEntry8571.incrementAndGet()
            else chainEntry8571.incrementAndGet()
        }
        val px = basisMark8571.usd
        // V5.0.7930 — an ADMIT supersedes a pending refusal of the same (mint, lane): a
        // token refused earlier (or by an advisory gate) and then bought was labelled
        // and graded as "refused" for up to four hours.
        // V5.0.8094 — every admit stamps its own price and time (the label keeps the first; the chase / latency of a fill is
        // measured from the admit that actually led to it, not one up to 15 minutes older).
        if (com.lifecyclebot.engine.NativeDecisionEnrollment8372.executionAdmission(admitted, reason)) {
            if (lastAdmit8094.size > 4_000) lastAdmit8094.entries.removeIf { nowMs - it.value.second > 10L * 60_000L }
            lastAdmit8094[key] = px to nowMs
        }
        val prior7930 = pending[key]
        val nativePrior8372 = com.lifecyclebot.engine.NativeDecisionEnrollment8372.predictionObservation8404(prior7930?.reason7967)
        if (com.lifecyclebot.engine.NativeDecisionEnrollment8372.bindExecutionWithoutReplacingObservation(
                admitted, reason, prior7930?.reason7967)) {
            // The initial YES/NO observation keeps its original price, time and verdict. A later actual FDG allow
            // gets a separate exact entry read for its real position instead of erasing the earlier prediction.
            try {
                val bound8372 = com.lifecyclebot.engine.cortex.Cortex7885.noteExecutionAdmission8372(ts, l, nowMs)
                PipelineHealthCollector.labelInc(if (bound8372) "NATIVE_LABEL_EXECUTION_READ_BOUND_8372"
                    else "NATIVE_LABEL_EXECUTION_READ_PENDING_8372")
            } catch (_: Throwable) {}
            skippedRecent.incrementAndGet()
            return
        }
        val upgrade7930 = admitted && prior7930 != null && !prior7930.admitted && !nativePrior8372
        if (upgrade7930) {
            pending.remove(key, prior7930)
            try { PipelineHealthCollector.labelInc("FORWARD_LABEL_REFUSAL_SUPERSEDED_BY_ADMIT_7930") } catch (_: Throwable) {}
        }
        val seen = lastSeenAt[key]
        if (!upgrade7930 && seen != null && nowMs - seen < REOBSERVE_MS_7731) { skippedRecent.incrementAndGet(); return }
        if (pending.containsKey(key)) { skippedRecent.incrementAndGet(); return }
        if (pending.size >= MAX_PENDING_7731) {
            skippedFull.incrementAndGet()
            try { PipelineHealthCollector.labelInc("FORWARD_LABELER_PENDING_FULL_7731") } catch (_: Throwable) {}
            return
        }
        val ageMs = cellAgeMs8208(ts, nowMs)
        // V5.0.8164 — the hydrated depth (curve / chain / map), not the raw tick field: an unread tick on a curve coin
        // charged every label the 20% unknown-depth impact, and those labels refused whole stages as PROVEN_LOSING.
        val liq = (try { LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { ts.lastLiquidityUsd }).let { if (it.isFinite()) it else 0.0 }
        // V5.0.7962 — the lane's measured live round trip once it has 8+ trades (CostLedger7962; excludes the chase).
        // V5.0.7961 — what the lane's fills cost above the decision price (EntryChase7961).
        val chase8094 = (try { EntryChase7961.lanePenaltyPct7961(l) } catch (_: Throwable) { 0.0 })
        // V5.0.8534 — an unread depth is not charged the 20 % impact guard on a $5 label (FieldManual7715.labelCostPct8534).
        // superseded: `FieldManual7715.allInCostPct8350(COST_SIZE_USD_7731, liq, ts.mint)` — unknown depth -> MAX impact.
        val labelCost8534 = try { FieldManual7715.labelCostPct8534(COST_SIZE_USD_7731, liq, ts.mint) } catch (_: Throwable) { FieldManual7715.BASE_ROUND_TRIP_COST_PCT_7715 to true }
        if (!labelCost8534.second) { depthUnknownLabels8534.incrementAndGet(); try { PipelineHealthCollector.labelInc("LABEL_DEPTH_UNKNOWN_NO_IMPACT_8534") } catch (_: Throwable) {} }
        val cost = CostLedger7962.costOr7962(l, Double.NaN, labelCost8534.first) + chase8094
        noteChargedCost8418(l, cost - chase8094)
        val cell = cellKey(ts.source, l, TrustedMcap8019.mcap8019(ts), ageMs)  // V5.0.8019 — the cap the price agrees with
        // V5.0.7734 — the regime is read now, at decision time, as the forecast
        // model keys it; the token state carries no setup quality or edge phase,
        // so the label lands on the coarse signature (lane | band | regime).
        val regime = try { com.lifecyclebot.engine.RegimeDetector.currentRegime().name } catch (_: Throwable) { "UNKNOWN" }
        // V5.0.8051 — the forecast signature is filled from the token: its score (was -1 -> every label in S00 while the
        // readers asked at the coin's score), the shape of the setup it shows in this lane (was "U") and its lifecycle
        // stage (was "UNKNOWN"). The Cortex forecasters read the same signature ([signature8051]).
        val sig8051 = signature8051(ts, l)
        val score8051 = if (score >= 0) score else ts.entryScore.toInt()
        notePendingMint8475(ts.mint, nowMs)
        pending[key] = Obs(ts.mint, ts.symbol, cell, sourceFamily(ts.source), l, admitted, score8051, sig8051.first, regime, sig8051.second, px, cost.coerceIn(0.0, 60.0), nowMs)
            .also {
                it.honest8366 = true  // V5.0.8366
                it.targetHorizon8521 = com.lifecyclebot.engine.cortex.CortexForwardEpoch8521.targetHorizon(
                    com.lifecyclebot.engine.cortex.CortexForwardEpoch8521.targetKind(l))
                it.gate8418 = GateOutcome8418.attachGate8418("", nowMs, admitted, reason, l, nowMs).orEmpty()  // V5.0.8418
                it.advisoryGate8715 = overridden8715.remove(key)?.takeIf { d -> nowMs - d.second in 0L..GateOutcome8418.ATTACH_MAX_AGE_MS_8418 }?.first.orEmpty()
                // V5.0.8701 / 8709 — a real lane's observation also carries the coin's origin x pool-depth cohort, once per coin per 15 minutes
                if (!l.startsWith("PLANWAIT_") && !l.startsWith("PLANADMIT_") && !ExpertSetups8692.setupCohort8692(l))
                    it.origin8701 = (try { MarketCohort8701.claim8701(ts, nowMs) } catch (_: Throwable) { null }).orEmpty()
                it.windowEnd8379 = launchWindowEnd8379(l, (try { CanonicalTokenBirthTime7440.launchAgeMs7767(ts, nowMs) } catch (_: Throwable) { null }) ?: -1L, nowMs)  // V5.0.8379 — the live window's one age rule
                it.chase8094 = chase8094.coerceAtLeast(0.0)
                it.entryStyle8077 = try {
                    com.lifecyclebot.engine.AgenticStyleRouter.decide(ts, com.lifecyclebot.engine.ModeRouter.classify(ts), l).style.name
                } catch (_: Throwable) { "" }
                it.entryMcap = if (ts.lastMcap.isFinite() && ts.lastMcap > 0.0) ts.lastMcap else 0.0
                // The source mark selected above IS the basis; do not re-read a mutable token or registry here.
                it.basis8386 = LabelBasis8386(basisMark8571, it.entryMcap)
                if (basisMark8571.source == "TOKEN_STATE")
                    try { PipelineHealthCollector.labelInc("FORWARD_LABEL_BASIS_FROM_DECISION_PRICE_8524") } catch (_: Throwable) {}
                it.decisionBasis8386 = it.basis8386

                it.reason7967 = reason.orEmpty().take(120)
                it.stage = try { com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts).stage.name } catch (_: Throwable) { "" }
                it.feats7972 = try { SpecialistMiner7972.features7972(ts, l, nowMs) } catch (_: Throwable) { emptyList() }
                it.expertAtDecision = try { com.lifecyclebot.engine.ExpertTraderKnowledge7813.freezeForLabel(ts, l, score8051, nowMs) } catch (_: Throwable) { null }
                it.edge8537 = EdgeModel8537.snapshot(ts, l, cost.coerceIn(0.0, 60.0), admitted, nowMs)  // V5.0.8537
                it.liqUsd8539 = liq  // V5.0.8539
                it.entryLiq7997 = if (ts.lastLiquidityUsd.isFinite() && ts.lastLiquidityUsd > 0.0) ts.lastLiquidityUsd else 0.0
                it.kind8063 = try { kindOf8063(ts) } catch (_: Throwable) { "" }
                it.nativeTrigger8468 = try { com.lifecyclebot.engine.OwnershipChain8364.nativeEligible8364(ts.mint, l) == true } catch (_: Throwable) { false }
            }
        lastSeenAt[key] = nowMs
        // V5.0.7883 — the lane's trade shape (tokenomics, timing, flow) at this decision.
        try { TradeShapeLearner7883.capture(ts, l, nowMs) } catch (_: Throwable) {}
        try { FallingHourCohort8418.capture8418(ts, l, nowMs) } catch (_: Throwable) {}  // V5.0.8418 — the falling-hour rule's own evidence
        // V5.0.7885 — the Cortex snapshot + every voter's opinion at this decision.
        try { com.lifecyclebot.engine.cortex.Cortex7885.capture(ts, l, admitted, nowMs, reason, incumbent8506) } catch (_: Throwable) {}
        // The normal Cortex capture has now frozen the exact playbook matches for this decision.
        try { LaunchHour8628.observeDecision(ts, l, admitted, nowMs) } catch (_: Exception) { PipelineHealthCollector.labelInc("LAUNCH_HOUR_DECISION_MISSING_8628") }
        // V5.0.7955 — the setup this decision is traded as keys its exit profile (classify is cached by the capture above).
        try { pending[key]?.setup7955 = com.lifecyclebot.engine.ExitProfile7955.entrySetup7955(ts, l, nowMs) } catch (_: Throwable) {}
        // V5.0.7737 — a fresh launch is also followed by first touch (+50% / -30%).
        try { FreshLaunchSelector7737.observe(ts, admitted, px, cost.coerceIn(0.0, 60.0), nowMs) } catch (_: Throwable) {}
        if (lastSeenAt.size > MAX_SEEN_7731) {
            val cutoff = nowMs - REOBSERVE_MS_7731
            lastSeenAt.entries.removeIf { it.value < cutoff }
        }
        observed.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("FORWARD_LABEL_OBSERVED_7731")
            PipelineHealthCollector.labelInc(if (admitted) "FORWARD_LABEL_OBSERVED_ADMITTED_7731" else "FORWARD_LABEL_OBSERVED_REFUSED_7731")
        } catch (_: Throwable) {}
    }

    // ── ticking (called once per loop with the loop's price closure) ──

    private fun tallyFor(key: String): Tally = cells.getOrPut(key) {
        if (cells.size >= MAX_CELLS_7731) pruneCells()
        Tally()
    }

    private fun pruneCells() {
        // Drop the thinnest non-aggregate cells so the table stays bounded.
        val victims = cells.entries
            .filter { !it.key.startsWith("AGG|") && !it.key.startsWith("LANE|") && !it.key.startsWith("SRC|") && !it.key.startsWith("STAGE|") && !it.key.startsWith("TRIGGER|") }
            .sortedBy { synchronized(it.value) { it.value.n60 + it.value.n15 } }
            .take(MAX_CELLS_7731 / 10)
        victims.forEach { cells.remove(it.key, it.value) }
        try { PipelineHealthCollector.labelInc("FORWARD_LABELER_CELLS_PRUNED_7731") } catch (_: Throwable) {}
    }

    private fun book(o: Obs, horizon: Int, net: Double, gross: Double, witnessed8324: Boolean = false) {
        if (!eligible8386(o)) return
        // V5.0.8546 — every 5-minute meme label feeds the market regime (breadth: mean net, share rising)
        if (horizon == 60 && MemePaperTrader8544.memeLane8544(o.lane)) try { MemeRegime8546.note8546(System.currentTimeMillis(), net, gross) } catch (_: Throwable) {}
        val keys = keysOf7928(o)
        val scales8410 = DoubleArray(keys.size)   // V5.0.8410 — the 60-slot decay each key had when this label was booked
        for ((i, k) in keys.withIndex()) {
            val t = tallyFor(k)
            scales8410[i] = synchronized(t) { t.logScale60 }
            synchronized(t) {
                when (horizon) {
                    15 -> { t.n15 += 1; t.sum15 += net; if (net > 0.0) t.win15 += 1 }
                    60 -> { t.n60 += 1; t.sum60 += net; t.sumSq60 += net * net; if (net > 0.0) t.win60 += 1; if (gross >= RUNNER_PCT_7731) { t.runner60 += 1; if (witnessed8324) t.witness60 += 1 } }
                    else -> { t.n240 += 1; t.sum240 += net; if (net > 0.0) t.win240 += 1 }
                }
                t.window8014(CELL_WINDOW_8014)
            }
        }
        if (horizon == 60) o.tallyScales8410 = scales8410
        if (horizon == 60 && o.gate8418.isNotEmpty()) try { GateOutcome8418.book8418(o.gate8418, o.lane, net, gross >= RUNNER_PCT_7731) } catch (_: Throwable) {}  // V5.0.8418
        if (horizon == 60 && o.advisoryGate8715.isNotEmpty() && o.advisoryGate8715 != o.gate8418)
            try { GateOutcome8418.book8418(o.advisoryGate8715, o.lane, net, gross >= RUNNER_PCT_7731) } catch (_: Throwable) {}
        when (horizon) { 15 -> booked15.incrementAndGet(); 60 -> booked60.incrementAndGet(); else -> booked240.incrementAndGet() }
        if (horizon == 60) bookFresh8169(o, keys, net, gross, witnessed8324)
        // V5.0.8198 — the late (60-minute) read books into the fresh tally too: the stage rule spares a stage that pays late.
        if (horizon == 240) bookFreshLate8198(o, keys, net)
        if (bookingsSincePersist.incrementAndGet() >= PERSIST_EVERY_BOOKINGS_7731) persist()
    }

    /** V5.0.8169 — the 5-minute label also books into the fresh (post-epoch) tally when its decision is after the epoch. */
    private fun bookFresh8169(o: Obs, keys: List<String>, net: Double, gross: Double, witnessed8324: Boolean = false) {
        syncEpoch8169()
        if (!freshObservation8169(o.atMs, freshEpochMs8169)) { freshSkippedPreEpoch8169.incrementAndGet(); return }
        for (k in keys) {
            if (!fresh8169.containsKey(k) && fresh8169.size >= MAX_CELLS_7731) continue
            val t = fresh8169.getOrPut(k) { Tally() }
            synchronized(t) {
                t.n60 += 1; t.sum60 += net; t.sumSq60 += net * net; if (net > 0.0) t.win60 += 1
                if (gross >= RUNNER_PCT_7731) { t.runner60 += 1; if (witnessed8324) t.witness60 += 1 }
                t.window8014(CELL_WINDOW_8014)
            }
        }
        freshBooked8169.incrementAndGet()
    }

    /** V5.0.8198 — the 60-minute label of a post-epoch decision books into the fresh tally's late slot. */
    private fun bookFreshLate8198(o: Obs, keys: List<String>, net: Double) {
        syncEpoch8169()
        if (!freshObservation8169(o.atMs, freshEpochMs8169)) return
        for (k in keys) {
            val t = fresh8169[k] ?: continue   // a key with no post-epoch 5-minute label has no record to spare
            synchronized(t) { t.n240 += 1; t.sum240 += net; if (net > 0.0) t.win240 += 1; t.window8014(CELL_WINDOW_8014) }
        }
    }

    /** V5.0.8169 — restore the fresh tallies; a new grading version starts a new epoch now (the old fresh book is dropped). */
    private fun restoreFresh8169(p: SharedPreferences, nowMs: Long) {
        val version = p.getInt(GRADING_VERSION_KEY_8169, 0)
        if (version != GRADING_VERSION_8169) {
            fresh8169.clear()
            freshEpochMs8169 = nowMs
            p.edit().putInt(GRADING_VERSION_KEY_8169, GRADING_VERSION_8169).putLong(FRESH_EPOCH_KEY_8169, nowMs).remove(FRESH_KEY_8169).apply()
            try {
                PipelineHealthCollector.labelInc("FORWARD_LABEL_GRADING_EPOCH_STARTED_8169")
                ForensicLogger.lifecycle("FORWARD_LABEL_GRADING_EPOCH_STARTED_8169", "from=$version to=$GRADING_VERSION_8169 action=older_loss_patterns_decay_to_neutral")
            } catch (_: Throwable) {}
        } else {
            freshEpochMs8169 = p.getLong(FRESH_EPOCH_KEY_8169, nowMs).takeIf { it > 0L } ?: nowMs
            p.getString(FRESH_KEY_8169, null)?.split(';')?.forEach { row ->
                val sep = row.lastIndexOf('=')
                if (sep <= 0) return@forEach
                val t = Tally()
                if (t.decode(row.substring(sep + 1))) fresh8169[row.substring(0, sep)] = t
            }
        }
        syncEpoch8169()
    }

    private fun markLost(o: Obs) {
        for (k in keysOf7928(o)) {
            val t = tallyFor(k)
            synchronized(t) { t.lost += 1 }
        }
        lostMark.incrementAndGet()
        try { PipelineHealthCollector.labelInc("FORWARD_LABEL_LOST_MARK_7731") } catch (_: Throwable) {}
    }

    /**
     * V5.0.7945 — a label is what the bot's exits would have earned, not the price
     * at the hour. Every learner read the 60/240-minute price alone, so a token that
     * spiked +276% at minute two and was back at entry by the hour taught every
     * lane, cell, voter and source "break-even minus cost": the lanes that find
     * spikes were graded as if the spike never happened. The spike tiers
     * (SpikeCapture7943) now sell into those prints live, so the label books what
     * they bank on the observed peak plus the remainder at the horizon.
     */
    private fun captured7945(o: Obs, net: Double, gross: Double, longFamily8080: Boolean? = null): Pair<Double, Double> {
        // V5.0.7955 review — labels are credited on the FIXED ladder: crediting on the learned
        // ladder (fit to these same labels) inflated the grades that drive authority and size.
        // V5.0.8029 — graded on THIS lane's play: its own stop first (a coin through the stop before its peak is a
        // stopped trade, not a run), then the spike ladder on the peak with the rest at the mark — or at the stop.
        // V5.0.8080 — an explicit exit family (scalp / long hold) grades one STRATEGY on its own exits; null = the
        // decision's frozen style (lane / cell tallies).
        val longHold8077 = (longFamily8080 ?: com.lifecyclebot.engine.TradeExitContract8077.longHoldStyle(o.entryStyle8077))?.let {
            it && com.lifecyclebot.engine.LifecycleHold8065.lifecycleLane8065(o.lane)
        }
        val stop8029 = try { com.lifecyclebot.engine.cortex.StopAuthority7887.laneStopMag8029(o.lane, longHold8077) } catch (_: Throwable) { Double.NaN }
        // V5.0.8071 — a REAL move is credited, a one-print spike is not. The ladder sells into the level the price HELD
        // for 20 seconds (heldPeak8071) — a level a live sell can land at — on the lane's own ladder (lifecycle lanes:
        // half at 2x, 30% at 5x and 10x; scalp lanes: the spike tiers). The 8064 note below is why the raw peak is not.
        // V5.0.8064 — NO spike credit. Owner, 5.0.8063: "its got worse not better" — 44 live closes, 3 wins, PF 0.09,
        // while the label book read its best cells at +529% (wr 95%) and +588% (wr 86%): 302 of 341 graded labels were
        // credited the spike ladder on their peak. Live never banks those prints (spike tiers fired=0; a +156% quick take
        // landed at -40%; a +236% peak closed at -18%) — a pump.fun spike is one or two trades, gone before a sell lands.
        // A label is now the lane's own play (its stop first) with the position worth the mark at the read.
        // V5.0.8527 — runner lanes keep their own observed held-level ladder (2x / 5x / 11x), not scalp tiers.
        // A held peak plus a later mark does NOT establish a chronological exit or a fill at either the old half-peak
        // threshold or the current GreenFloor. The remaining quantity is valued only at the observed mark.
        val runner8106 = try { com.lifecyclebot.engine.TradeExitContract8077.runnerFromEntryLane8104(o.lane, false) } catch (_: Throwable) { false }
        val tiers8071 = try { if (longHold8077 ?: com.lifecyclebot.engine.LifecycleHold8065.lifecycleLane8065(o.lane)) com.lifecyclebot.engine.LifecycleHold8065.ladder8071() else if (runner8106) com.lifecyclebot.engine.RunnerGrab7967.TIERS_7967 else com.lifecyclebot.engine.SpikeCapture7943.TIERS } catch (_: Throwable) { com.lifecyclebot.engine.SpikeCapture7943.TIERS }
        // V5.0.8366 §HONEST_LABELS — the ladder credits a level held for the measured sell latency (not a free 20 s window),
        // and a stop fills at the first observed mark at or below it, after the exit slippage (HonestExit8366).
        val held8366 = if (o.honest8366) o.heldPeak8366 else o.heldPeak8071
        val ladder8040: (Double, Double) -> Double = { pk, rest ->
            if (runner8106) heldCredit8071(minOf(pk, held8366), runnerRest8106(true, minOf(pk, held8366), rest), tiers8071)
            else heldCredit8071(minOf(pk, held8366), rest, tiers8071)
        }
        // V5.0.8379 — a launch-window entry is graded the way live now trades it (LaunchWindow8379), not on the lane stop alone.
        val g = if (o.honest8366 && o.windowEnd8379 > 0L && longFamily8080 == null) playWindow8379(o, stop8029, gross, ladder8040)
            else playGross8366(o, stop8029, gross, ladder8040)
        // V5.0.8040 — the same path under every candidate stop: the lane learns its own stop (StopLearner8040).
        if (stop8029 > 0.0 && longFamily8080 == null) try {
            com.lifecyclebot.engine.cortex.StopLearner8040.onPath8040(o.lane, "${o.mint}|${o.atMs}", stop8029, System.currentTimeMillis() - o.atMs) { s ->
                playGross8366(o, s, gross, ladder8040)  // V5.0.8366 — candidate stops graded on the same honest fill
            }
        } catch (_: Throwable) {}
        if (!g.isFinite() || g == gross) return net to gross
        val dipFirst8029 = if (o.dipBeforePeak7962.isFinite()) o.dipBeforePeak7962 else o.minPct7962
        if (g < gross || (stop8029 > 0.0 && o.minPct7962 <= -stop8029)) try {
            com.lifecyclebot.engine.LaneParticipation8029.noteGraded8029(first = dipFirst8029 <= -stop8029, afterPeak = dipFirst8029 > -stop8029)
            // V5.0.8169 — per lane, and whether the coin ran past the stop distance after stopping out (a shakeout).
            if (longFamily8080 == null) com.lifecyclebot.engine.ExitDoctrine8169.noteGraded8169(o.lane, stop8029, o.peakPct, dipFirst8029)
        } catch (_: Throwable) {}
        if (g > gross) capturedLabels7945.incrementAndGet()
        return (net + (g - gross)).coerceAtMost(NET_CEILING_PCT_7738) to g
    }

    private val capturedLabels7945 = AtomicLong(0)

    // ── V5.0.8366 §HONEST_LABELS (HonestExit8366) ──
    private val honestStops8366 = AtomicLong(0)

    /** The lane play on [o]'s path with a stop of [stopMag]: an honest observation fills at its first mark past the stop. */
    private fun playGross8366(o: Obs, stopMag: Double, gross: Double, ladder: (Double, Double) -> Double): Double {
        if (!o.honest8366) return com.lifecyclebot.engine.LaneParticipation8029.playGross8029(stopMag, o.peakPct, o.dipBeforePeak7962, o.minPct7962, gross, ladder)
        val fill = synchronized(o.recent8071) { HonestExit8366.stopFill8366(o.lows8366, stopMag, HonestExit8366.sellLatencyMs8366()) }
        // Lows past the record cap: the deepest stops fall back to the lowest mark seen (never better than the path).
        val fill2 = if (fill.isFinite() || !(stopMag > 0.0) || o.minPct7962 > -stopMag) fill else o.minPct7962
        if (fill2.isFinite() && fill2 < -stopMag) honestStops8366.incrementAndGet()
        return HonestExit8366.playGross8366(stopMag, o.peakPct, o.dipBeforePeak7962, o.minPct7962, gross, fill2, ladder)
    }

    private fun notePathLow8366(o: Obs, nowMs: Long, gross: Double) {
        if (!o.honest8366) return
        val prevGross = if (o.lastPx > 0.0 && o.entryPrice > 0.0 && o.lastPxAtMs > 0L) (o.lastPx / o.entryPrice - 1.0) * 100.0 else 0.0
        val prevAt = if (o.lastPxAtMs > 0L) o.lastPxAtMs else o.atMs
        synchronized(o.recent8071) {
            HonestExit8366.noteLow8366(o.lows8366, nowMs, gross, prevAt, prevGross)
            // V5.0.8379 — after a launch window the lane stop applies again: its own path of new lows from the window's end.
            if (o.windowEnd8379 > 0L && nowMs > o.windowEnd8379) HonestExit8366.noteLow8366(o.postLows8379, nowMs, gross, maxOf(prevAt, o.windowEnd8379), prevGross)
        }
    }

    /** Pure (V5.0.8379): the launch window's end for a decision in [lane] at coin age [birthAgeMs], or 0 when not a window entry. */
    fun launchWindowEnd8379(lane: String, birthAgeMs: Long, decidedAtMs: Long): Long {
        val l = try { CanonicalLaneIdentity6506.desk8392(lane) } catch (_: Throwable) { lane.trim().uppercase() }
        if (l !in com.lifecyclebot.engine.LaunchWindow8379.LANES_8379) return 0L
        if (birthAgeMs !in 0L until com.lifecyclebot.engine.LaunchWindow8379.TOKEN_AGE_MS_8379) return 0L
        return decidedAtMs + maxOf(com.lifecyclebot.engine.LaunchWindow8379.minHoldMs8379(), com.lifecyclebot.engine.LaunchWindow8379.TOKEN_AGE_MS_8379 - birthAgeMs)
    }

    /**
     * Pure (V5.0.8379): the lane play of a launch-window entry. Inside the window the stop is [floorMag] (filled at
     * [earlyFill], HonestExit8366); after it the lane stop [laneStopMag], filled at [lateFill] at [lateAtMs]. A peak set
     * before the late stop is credited on the ladder; one after it was never reached.
     */
    fun windowPlay8379(floorMag: Double, laneStopMag: Double, peakPct: Double, peakAtMs: Long, dipBeforePeakPct: Double, minPct: Double,
                       grossNow: Double, earlyFill: Double, lateFill: Double, lateAtMs: Long, ladder: (Double, Double) -> Double): Double {
        if (earlyFill.isFinite()) return HonestExit8366.playGross8366(floorMag, peakPct, dipBeforePeakPct, minPct, grossNow, earlyFill, ladder)
        if (lateFill.isFinite() && laneStopMag > 0.0) {
            val stopped = minOf(lateFill, -laneStopMag)
            return if (peakAtMs in 1L until lateAtMs) ladder(peakPct, stopped) else stopped
        }
        return ladder(peakPct, grossNow)
    }

    private fun playWindow8379(o: Obs, laneStopMag: Double, gross: Double, ladder: (Double, Double) -> Double): Double {
        val floor = com.lifecyclebot.engine.LaunchWindow8379.FLOOR_PCT_8379
        val lat = HonestExit8366.sellLatencyMs8366()
        // V5.0.8418 §WINDOW_LABELS_HONOUR_LIVE_RELEASES — live releases the window hold the moment the token's metrics collapse
        // (liquidity under half its entry depth, or sellers owning a fresh tape); from then the lane stop applies. 64422194d
        // graded the whole window on the -35 % floor, so window labels read better than live could bank.
        val end8418 = windowHoldEnd8418(o.windowEnd8379, o.windowReleasedAtMs8418)
        val (early, late) = synchronized(o.recent8071) {
            HonestExit8366.stopFill8366(o.lows8366.filter { it.isNotEmpty() && it[0] <= end8418.toDouble() }, floor, lat) to
                (if (end8418 < o.windowEnd8379) o.lows8366.firstOrNull { it.size >= 4 && it[0] >= end8418.toDouble() && it[1].isFinite() && laneStopMag > 0.0 && it[1] <= -laneStopMag }
                 else o.postLows8379.firstOrNull { it.size >= 4 && it[1].isFinite() && laneStopMag > 0.0 && it[1] <= -laneStopMag })
        }
        // V5.0.8418 — released already past the lane stop: live sells at the release mark itself (an observed mark).
        val atRelease8418 = end8418 < o.windowEnd8379 && laneStopMag > 0.0 && o.windowReleaseGross8418.isFinite() && o.windowReleaseGross8418 <= -laneStopMag
        val lateFill = if (atRelease8418) o.windowReleaseGross8418 else late?.let { HonestExit8366.stopFill8366(listOf(it), laneStopMag, lat) } ?: Double.NaN
        windowGraded8379.incrementAndGet()
        return windowPlay8379(floor, laneStopMag, o.peakPct, o.peakAtMs7955, o.dipBeforePeak7962, o.minPct7962, gross, early, lateFill,
            if (atRelease8418) end8418 else late?.get(0)?.toLong() ?: Long.MAX_VALUE, ladder)
    }

    /** Pure (V5.0.8418): where the window's floor stops holding — its end, or the earlier live release. */
    internal fun windowHoldEnd8418(windowEndMs: Long, releasedAtMs: Long): Long =
        if (releasedAtMs in 1L until windowEndMs) releasedAtMs else windowEndMs

    /** V5.0.8418 — the first in-window mark at which live's metrics-collapse release would have fired. */
    private fun noteWindowRelease8418(o: Obs, gross: Double, atMs: Long) {
        if (o.windowEnd8379 <= 0L || o.windowReleasedAtMs8418 > 0L || atMs > o.windowEnd8379 || !(gross < 0.0)) return
        val ts = try { com.lifecyclebot.engine.BotService.status.tokens[o.mint] } catch (_: Throwable) { null }
        val collapsed = try {
            com.lifecyclebot.engine.LifecycleHold8065.metricsCollapsed8109(gross, o.entryLiq7997, nowLiq7997(o.mint),
                ts?.lastBuyPressurePct ?: Double.NaN, ts != null && atMs - ts.buyPressureObservedAtMs8084 in 0L..120_000L)
        } catch (_: Throwable) { false }
        if (collapsed) { o.windowReleasedAtMs8418 = atMs; o.windowReleaseGross8418 = gross; windowReleased8418.incrementAndGet() }
    }
    private val windowReleased8418 = AtomicLong(0)

    private val windowGraded8379 = AtomicLong(0)
    fun windowGraded8379(): Long = windowGraded8379.get()

    /** Caller holds o.recent8071. */
    private fun notePathHeld8366(o: Obs, nowMs: Long, gross: Double) {
        if (o.honest8366) o.heldPeak8366 = HonestExit8366.heldPeakStep8366(o.recent8366, nowMs, gross, o.heldPeak8366, HonestExit8366.sellLatencyMs8366())
    }

    /** Caller holds o.recent8071: a re-based entry restarts the recorded path. */
    private fun resetPath8366(o: Obs) {
        o.heldPeak8366 = 0.0; o.recent8366.clear(); o.lows8366.clear()
    }

    fun honestLine8366(): String = "honestStops8366=${honestStops8366.get()} sellLatency=${HonestExit8366.sellLatencyMs8366() / 1000}s"

    /**
     * V5.0.8076 — the label's entry is the first mark 5-60 s after the decision, not the decision price. 5.0.8075:
     * PUMP_PORTAL_WS|MOONSHOT|MC_10K_100K|AGE_LT15M read +784% (wr 100%, runners 98%) and SHITCOIN +752% (wr 100%)
     * while the live book closed 1 win in 7: a launch's decision price is the PumpPortal frame's curve price,
     * stale by the time any buy lands — every launch "ran" from a price nobody could buy at.
     */
    private const val REBASE_MIN_MS_8076 = 5_000L
    private const val REBASE_MAX_MS_8076 = 60_000L

    /** Pure (V5.0.8094): when a label re-bases — the lane's measured live fill latency, within the 5-55 s window. */
    /** V5.0.8094 — a rebase prices the chase in only when it ran at a MEASURED lane latency (not the 5 s default). */
    fun rebaseClearsChase8094(laneLatencyMs: Long): Boolean = laneLatencyMs > 0L

    fun rebaseDelayMs8094(laneLatencyMs: Long): Long = laneLatencyMs.coerceIn(REBASE_MIN_MS_8076, REBASE_MAX_MS_8076 - 5_000L)

    /** A decision-time mark is not the later executable fill. Keep requesting until the fill has actually rebased. */
    @Suppress("UNUSED_PARAMETER")
    fun firstMarkDue8187(rebased: Boolean, lastPxAtMs: Long, ageMs: Long): Boolean =
        !rebased && ageMs in REBASE_MIN_MS_8076 until REBASE_MAX_MS_8076
    private val rebasedUp8076 = AtomicLong(0)

    // ── V5.0.8153 — the re-base-at-fill-latency flag for labels whose first price arrives late ──
    // A label re-bases only inside its 5-60 s window. An observation first priced after it (a refused coin off the
    // watchlist is first priced by the off-watch batch at its horizon) keeps the decision price nobody could buy at.
    // The Cortex consumes this flag at the 5-minute label (Cortex7885.onLabel): such a label stays an observed grade
    // record and never an executable value (LearningEntryTruth8153).
    private const val LATE_KEEP_MS_8153 = 2L * 3_600_000L
    private const val LATE_MAX_8153 = 4_000
    private val lateFirst8153 = ConcurrentHashMap<String, Long>()   // "mint|LANE|decisionAtMs" -> flagged at
    private val lateFirstCount8153 = AtomicLong(0)

    private fun noteLateFirstPrice8153(mint: String, lane: String, atMs: Long, nowMs: Long) {
        if (lateFirst8153.size >= LATE_MAX_8153) lateFirst8153.entries.removeIf { nowMs - it.value > LATE_KEEP_MS_8153 }
        if (lateFirst8153.size >= LATE_MAX_8153) return
        lateFirst8153["$mint|${lane.trim().uppercase()}|$atMs"] = nowMs
        lateFirstCount8153.incrementAndGet()
        try { PipelineHealthCollector.labelInc("FORWARD_LABEL_LATE_FIRST_PRICE_8153") } catch (_: Throwable) {}
    }

    /** Cortex7885.onLabel: was the label of the decision on ([mint], [lane]) at [atMs] first priced late? Consumed once. */
    fun lateFirstPrice8153(mint: String, lane: String, atMs: Long): Boolean {
        val key = "$mint|${lane.trim().uppercase()}"
        val remembered = lateFirst8153.remove("$key|$atMs") != null
        return remembered || pending[key]?.let { it.atMs == atMs && it.lateEntry8521 } == true
    }
    private val rebasedDown8076 = AtomicLong(0)
    /** V5.0.8322 — re-bases whose first mark equalled the decision price, and five-minute labels read on a flat mark. */
    private val rebasedFlat8322 = AtomicLong(0)
    private val flatMarks8322 = AtomicLong(0)
    @Volatile private var rebaseGapSum8076 = 0.0

    /** V5.0.8071 — how long a level must hold before a sell is counted as able to land there. */
    private const val HELD_MS_8071 = 20_000L

    /**
     * Pure (V5.0.8071): one mark into the held-peak tracker. [window] keeps the last ~30 s of [atMs, gross]; once it spans
     * [HELD_MS_8071], the lowest gross inside the last 20 s is a level the price held, and the held peak is the best such.
     */
    fun heldPeakStep8071(window: ArrayDeque<DoubleArray>, nowMs: Long, gross: Double, heldPeak: Double): Double {
        if (!gross.isFinite()) return heldPeak
        window.addLast(doubleArrayOf(nowMs.toDouble(), gross))
        // Keep exactly one mark at or before (now - 20 s) — the start of the hold — and everything after it.
        while (window.size >= 2 && nowMs - window.elementAt(1)[0].toLong() >= HELD_MS_8071) window.removeFirst()
        if (nowMs - window.first()[0].toLong() < HELD_MS_8071) return heldPeak
        // Every mark from the hold's start to now was at or above this level: the price held it for 20 s or more.
        return maxOf(heldPeak, window.minOf { it[1] })
    }

    /** Pure (V5.0.8071): the ladder credit for a level the price held ([heldPeakPct]), the rest at the read ([restPct]). */
    fun heldCredit8071(heldPeakPct: Double, restPct: Double, tiers: List<Pair<Double, Double>>): Double {
        if (!heldPeakPct.isFinite() || !restPct.isFinite()) return restPct
        val g = com.lifecyclebot.engine.SpikeCapture7943.realisableGrossPct(heldPeakPct, restPct, tiers)
        return if (g.isFinite() && g > restPct) g else restPct
    }

    /**
     * V5.0.8527 — summary extrema do not prove the order, executable crossing or a sell fill.
     * Keep the observed remainder; chronological runner exits are replayed by RunnerReplay8153 at actual marks.
     * Retain this adapter so old callers cannot silently reintroduce synthetic half-peak proceeds.
     */
    fun runnerRest8106(@Suppress("UNUSED_PARAMETER") runnerLane: Boolean,
                       @Suppress("UNUSED_PARAMETER") heldPeakPct: Double, restPct: Double): Double = restPct

    /** Pure (V5.0.8064): what a label credits for a peak [peakPct] above the mark at the read [restPct] — nothing. */
    fun spikeCredit8064(@Suppress("UNUSED_PARAMETER") peakPct: Double, restPct: Double): Double = restPct

    private fun outcomeProof8395(o: Obs, net: Double, gross: Double, horizonMs: Long): com.lifecyclebot.engine.cortex.CortexOutcomeProof8395? {
        val mark = o.lastMark8386 ?: return null
        return com.lifecyclebot.engine.cortex.CortexOutcomeProof8395("${o.mint}|${o.lane.trim().uppercase()}|${o.atMs}",
            horizonMs, mark.observedAtMs, net, gross, o.basis8386.mark, mark)
    }

    private fun bookSixty7944(o: Obs, net0: Double, gross0: Double, nowMs: Long): Boolean {
        if (o.staged8418 != null) return retryCortexStage8418(o)   // only the exact delivery remains; never book twice
        if (!eligible8386(o)) return true
        // V5.0.7955 — the give-back from the peak to the 5-minute read rides on the observation to its 60-minute sample.
        o.giveback5_7955 = com.lifecyclebot.engine.ExitProfile7955.giveback7955(o.peakPct, gross0)
        var (net, gross) = captured7945(o, net0, gross0)
        // V5.0.8324 §PROVENANCE — a runner label whose marks the chain witnessed (curve reserves / launch-scale cap) is real at any size.
        var w8324 = gross >= RUNNER_PCT_7731 && witnessNow8324(o, nowMs)
        try { ObservedRunners8707.note8707(o.mint, gross0, w8324, nowMs) } catch (_: Throwable) {}   // V5.0.8707 — the smart-money miner's runners
        // V5.0.8337 (FIX 2) — a flat mark (no price other than the entry ever seen) is a LOST mark, never a -cost label:
        // 8322 booked it first and flagged it after, so every lane / cell / shape / specialist book read "lost the round trip".
        var flat8322 = com.lifecyclebot.engine.cortex.CortexFlatMark8322.flatMark8322(gross0, o.peakPct, o.minPct7962)
        if (flat8322 && liveMark8454(o.lastMark8386)) { flat8322 = false; flatLiveBooked8454.incrementAndGet() }   // V5.0.8454 — the market's own flat read
        val scalp8080 = captured7945(o, net0, gross0, longFamily8080 = false)
        val long8080 = captured7945(o, net0, gross0, longFamily8080 = true)
        val fam8080 = doubleArrayOf(scalp8080.first, scalp8080.second, long8080.first, long8080.second)
        // Own the value and its frozen prediction durably BEFORE completing this horizon or notifying learners.
        val frozen8404 = stageForwardOutcome8404(o, net, gross, fam8080, flat8322, w8324)
        if (frozen8404 != null) {
            // A failed disk write may have frozen an earlier value. All consumers must use that same
            // value when storage recovers, rather than the newer price used by this retrying tick.
            net = frozen8404.net; gross = frozen8404.gross
            flat8322 = frozen8404.flat; w8324 = frozen8404.witness
        } else {
            // A blocked Cortex delivery cannot erase an observed price. Keep this exact value and source proof while
            // delivery retries; the entry learners below run only if this read is the frozen target.
            o.staged8418 = stagedValue8418(net, gross, flat8322, w8324, fam8080)
            o.stagedProof8521 = outcomeProof8395(o, net, gross, H60_MS_7731)
            try { PipelineHealthCollector.labelInc("FORWARD_OBSERVATION_CORTEX_STAGE_WAIT_8521") } catch (_: Throwable) {}
        }
        o.firstReadValid8521 = !flat8322
        // The frozen lane target owns every entry book. A valid early price is useful observation coverage,
        // but it must not train an hour policy before the promised outcome exists.
        if (o.targetHorizon8521 > H60_MS_7731) return frozen8404 != null
        if (flat8322) {
            flatMarks8322.incrementAndGet()
            o.bookedNet7997 = Double.NaN
            markLost(o)                       // counted as lost mark, never as a -cost label
        } else {
            bookEntryTarget8521(o, net, gross, w8324, nowMs)
        }
        // V5.0.8080 §ONE_PATH_TWO_EXIT_FAMILIES. The setup books graded every setup that fired on one value, so a
        // momentum scalp and a breakout runner on the same coin were credited the same return. The same observed path is
        // now played under the scalp exits and under the long-hold exits; each setup is graded on its own family.
        o.bookedLongNet8080 = frozen8404?.family?.getOrNull(2) ?: long8080.first
        o.bookedLongGross8080 = frozen8404?.family?.getOrNull(3) ?: long8080.second
        return frozen8404 != null
    }

    private fun timingHorizons8528(o: Obs): LongArray = synchronized(checkpointLock8401) {
        o.timingPlan8528?.horizons?.filter { it !in o.timingDone8528 }?.toLongArray() ?: longArrayOf()
    }

    private fun gradeTiming8528(o: Obs, mark: LabelMark8386?, nowMs: Long) = synchronized(checkpointLock8401) {
        val plan = o.timingPlan8528 ?: return@synchronized
        val eligible = mark != null && entryFill8521(o) && liveMark8454(mark) &&
            LabelEvidence8386.eligible(o.basis8386.mark, mark)
        timingBook8528.observe(o.lane, plan, o.timingDone8528, o.atMs, o.basis8386.mark.observedAtMs,
            mark?.observedAtMs ?: 0L, nowMs, eligible,
            if (mark != null && o.entryPrice > 0.0) (mark.usd / o.entryPrice - 1.0) * 100.0 else Double.NaN)
    }

    /** A fixed decision target contributes once; later observations remain available to path and exit learners. */
    private fun bookEntryTarget8521(o: Obs, net: Double, gross: Double, witness: Boolean, nowMs: Long) {
        // 8663: pre-8660 non-pump Solana observations can carry the pump.fun curve
        // price basis. Previously only EdgeModel declined their labels; book(), the
        // cell miner, setup/shape learners and developer reputation still learned
        // those phantom runners. One provenance guard protects every consumer.
        // A missing frozen epoch is not evidence of a corrected non-pump venue.
        if (!EdgeModel8537.venueScopedForLearning8663(o.mint, o.edge8537?.modelEpoch)) {
            o.edge8537 = null
            try { PipelineHealthCollector.labelInc("FORWARD_VENUE_UNSCOPED_LEARNING_REFUSED_8663") } catch (_: Throwable) {}
            return
        }
        // V5.0.8617 — a 5-minute outcome no coin of that size can make is a mark on the wrong basis (03:50 export: a $2.96 M
        // day-old coin at +1,406,280,000 %, a $112 k coin at +518,638 %). It reaches no learner: not the setup books, the shape /
        // falling-hour / forward learners, the meme regime, EdgeModel8537 or PocketLedger8610.
        if (net.isFinite() && !EdgeModel8537.plausible8617(o.edge8537?.raw?.mcap ?: Double.NaN, net, witness)) {
            o.edge8537 = null
            try { PipelineHealthCollector.labelInc("FORWARD_LABEL_IMPLAUSIBLE_REJECTED_8617") } catch (_: Throwable) {}
            try { ForensicLogger.lifecycle("FORWARD_LABEL_IMPLAUSIBLE_8617", "mint=${o.mint.take(10)} lane=${o.lane} net=${"%.0f".format(net)} entry=${o.entryPrice}") } catch (_: Throwable) {}
            return
        }
        // V5.0.8546 — an unfillable entry (no reachable mark in the rebase window: most fast meme coins) never books, but its
        // 5-minute read still counts for the market regime's breadth and is written to the research dataset, flagged.
        if (eligible8386(o) && net.isFinite() && !o.bookedNet7997.isFinite() && !entryFill8521(o) && MemePaperTrader8544.memeLane8544(o.lane)) {
            try { MemeRegime8546.note8546(nowMs, net, gross) } catch (_: Throwable) {}
            o.edge8537?.let { o.edge8537 = null; try { EdgeModel8537.recordUnfilled8546(it, net, witness) } catch (_: Throwable) {} }
        }
        if (!eligible8386(o) || !entryFill8521(o) || o.bookedNet7997.isFinite() || !net.isFinite() ||
            !horizonOpen7809((o.lastMark8386?.observedAtMs ?: 0L) - o.atMs, o.targetHorizon8521)) return
        // book() may trigger an atomic producer checkpoint. That checkpoint must already carry this target's receipt,
        // otherwise a restart can restore the incremented tally with an unbooked observation and add it a second time.
        o.bookedNet7997 = net; o.bookedGross7997 = gross; o.bookedWitness8324 = witness
        book(o, 60, net, gross, witness)
        o.edge8537?.let { o.edge8537 = null; EdgeModel8537.learn(it, net, witness) }  // V5.0.8650 — preserve same-basis runner proof
        try { TradeShapeLearner7883.onLabel60(o.mint, o.lane, net, gross, o.atMs) } catch (_: Throwable) {}
        try { FallingHourCohort8418.onLabel60(o.mint, o.lane, net, gross) } catch (_: Throwable) {}
        teachForwardLearners8521(o, net, gross, nowMs)
        if (o.targetHorizon8521 > H60_MS_7731) {   // V5.0.8592 — the hour target (now 15 m for new obs, 60 m for old) just booked
            revisedToHour8337.incrementAndGet()
            try { PipelineHealthCollector.labelInc("FORWARD_LABEL_FROZEN_HOUR_TARGET_8521") } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.cortex.LaneGradeHorizon8406.noteHourGrade8408(o.lane) } catch (_: Throwable) {}
        }
    }

    /** A forward outcome starts at the genuine fill-window observation, never an unreached decision price. */
    private fun entryFill8521(o: Obs): Boolean = (o.rebased8076 && !o.lateEntry8521 &&
        o.basis8386.mark.observedAtMs - o.atMs in REBASE_MIN_MS_8076..REBASE_MAX_MS_8076) ||
        ((!o.rebased8076 || o.lateEntry8521) && o.basis8386 == o.decisionBasis8386 && decisionChainFill8567(o.basis8386.mark.source, o.basis8386.mark.independentBasis,
            o.atMs - o.basis8386.mark.observedAtMs))

    /**
     * Pure (V5.0.8567): with no mark inside the rebase window (most fast meme coins — their outcomes were written "unfilled" and
     * never trained the edge model: 1,496 rows, 4-10 per meme lane), a decision priced from the chain itself (curve account /
     * curve print / parsed swap / tape / pool reserves) no more than [DECISION_FILL_MAX_AGE_MS_8567] old IS a price a buy
     * could get; the lane's chase stays charged (no rebase cleared it). Aggregator / pair-API prices never qualify.
     */
    fun decisionChainFill8567(source: String, independentBasis: Boolean, ageAtDecisionMs: Long): Boolean =
        (independentBasis || LabelEvidence8386.independentSource8418(source)) && ageAtDecisionMs in 0L..DECISION_FILL_MAX_AGE_MS_8567
    const val DECISION_FILL_MAX_AGE_MS_8567 = 5_000L

    /** The same frozen target teaches all entry learners, whether the decision was taken or refused. */
    private fun teachForwardLearners8521(o: Obs, net: Double, gross: Double, nowMs: Long) {
        // The same net target grades the decision cell for slot / size / priority.
        try { o.allocScales8410 = com.lifecyclebot.engine.CellAllocator7962.onLabel7962(o.cell, o.lane, o.setup7955, net, "${o.mint}|${o.lane}|${o.atMs}", o.targetHorizon8521 / 60_000.0) } catch (_: Throwable) {}
        // V5.0.7972 — the same label grades every pair/triple of the decision's facts (specialist miner).
        try { o.minerScales8418 = SpecialistMiner7972.onLabel7972(o.lane, o.mint, o.feats7972, net, gross, o.atMs) } catch (_: Throwable) {}   // V5.0.8418
        try { SignalSourceProof7291.onForwardLabel7731(o.mint, net / 100.0, nowMs) } catch (_: Throwable) {}
        // V5.0.7734 — the same label teaches the forecast model the admission stack reads.
        try { com.lifecyclebot.engine.ForwardOutcomeModel.recordLabel7734(o.lane, o.score, o.quality, o.regime, o.phase, net, "${o.mint}|${o.lane}|${o.atMs}") } catch (_: Throwable) {}
        // V5.0.7813 — counterfactual entry-quality learning, graded whether FDG admitted or refused.
        try { o.expertAtDecision?.let { com.lifecyclebot.engine.ExpertTraderKnowledge7813.recordFrozenForward(it, net, o.admitted) } } catch (_: Throwable) {}
    }

    /** Pure (V5.0.8418): the value the learners booked while Cortex staging waits: [net, gross, flat, witness, family x4]. */
    internal fun stagedValue8418(net: Double, gross: Double, flat: Boolean, witness: Boolean, family: DoubleArray): DoubleArray =
        doubleArrayOf(net, gross, if (flat) 1.0 else 0.0, if (witness) 1.0 else 0.0,
            family.getOrElse(0) { Double.NaN }, family.getOrElse(1) { Double.NaN }, family.getOrElse(2) { Double.NaN }, family.getOrElse(3) { Double.NaN })

    /**
     * V5.0.8418 — the learners already booked [o]'s first value ([Obs.staged8418]); only the Cortex staging remains. Retries it
     * with that exact value. Never re-books a learner. True once staged (the horizon is then complete).
     */
    private fun retryCortexStage8418(o: Obs): Boolean {
        val v = o.staged8418 ?: return true
        if (v.size < 8) { o.staged8418 = null; return true }
        val frozen = stageForwardOutcome8404(o, v[0], v[1], v.copyOfRange(4, 8), v[2] == 1.0, v[3] == 1.0) ?: return false
        o.staged8418 = null; o.stagedProof8521 = null
        try { PipelineHealthCollector.labelInc(if (frozen.net.toBits() == v[0].toBits()) "FORWARD_CORTEX_STAGED_LATE_8418" else "FORWARD_CORTEX_STAGED_EARLIER_VALUE_8418") } catch (_: Throwable) {}
        return true
    }

    /** Pure (V5.0.8418): give up the Cortex staging (learners keep the label; the Cortex decision is released) past [graceMs]. */
    internal fun stageAbandoned8418(nowMs: Long, deadlineMs: Long, graceMs: Long): Boolean = deadlineMs > 0L && nowMs >= deadlineMs + graceMs
    private const val STAGE_GRACE_MS_8418 = 10L * 60_000L

    // ── V5.0.7997 — PROGRESSIVE GRADING ──
    // V5.0.8521 — checkpoints retain the observed path for exit learning. Entry learners contribute once at
    // the decision's frozen lane target; neither an early read nor a later surviving token replaces that target.
    // Continue the 1m/5m/15m/30m/1h/4h/24h path; after 30m tokenomics decides whether continued observation is useful.
    private val CK_MS_7997 = longArrayOf(15L * 60_000L, 30L * 60_000L, 60L * 60_000L, 4L * 3_600_000L, 24L * 3_600_000L)
    /** V5.0.8416 — the pre-8416 schedule, only to read a persisted checkpoint index from an older build. */
    private val CK_MS_LEGACY_8416 = longArrayOf(15L * 60_000L, 60L * 60_000L, 4L * 3_600_000L, 24L * 3_600_000L,
        48L * 3_600_000L, 72L * 3_600_000L, 7L * 24L * 3_600_000L)
    /** V5.0.8416 — from this checkpoint on, a coin is followed only while it is worth following. */
    private const val FOLLOW_GATE_MS_8416 = 30L * 60_000L

    /** Pure (V5.0.8416): the next-due checkpoint as its horizon (persisted), so a schedule change never shifts an index. */
    fun ckField8416(ck: Int): String = if (ck in CK_MS_7997.indices) "H${CK_MS_7997[ck]}" else "H-1"

    /** Pure (V5.0.8416): a persisted checkpoint field (new horizon form, or a legacy index) -> index in today's schedule. */
    fun ckFromField8416(field: String?): Int {
        val f = field?.trim().orEmpty()
        val dueMs = if (f.startsWith("H")) f.drop(1).toLongOrNull() ?: return 0
            else { val i = f.toIntOrNull() ?: return 0; if (i in CK_MS_LEGACY_8416.indices) CK_MS_LEGACY_8416[i] else return CK_MS_7997.size }
        if (dueMs < 0L) return CK_MS_7997.size
        val i = CK_MS_7997.indexOfFirst { it >= dueMs }
        return if (i < 0) CK_MS_7997.size else i
    }

    /** Pure (V5.0.8416): at checkpoint age [ageMs], does the worth-following gate (not just "alive") decide? */
    fun followGate8416(ageMs: Long): Boolean = ageMs >= FOLLOW_GATE_MS_8416
    private const val RUNNING_GROSS_7997 = 20.0
    private const val DEAD_GROSS_7997 = -60.0
    private const val MAX_EXTENDED_7997 = 1_500
    private val revisions7997 = AtomicLong(0)
    private val extended7997 = AtomicLong(0)
    private val diedEarly7997 = AtomicLong(0)

    /** Pure. V5.0.7997 — is a coin still alive at a checkpoint? Not collapsed, liquidity held (unknown = held). */
    private fun alive7997(grossPct: Double, entryLiq: Double, nowLiq: Double): Boolean =
        grossPct.isFinite() && grossPct > DEAD_GROSS_7997 &&
            (entryLiq <= 0.0 || !nowLiq.isFinite() || nowLiq <= 0.0 || nowLiq >= 0.4 * entryLiq)

    private fun nowLiq7997(mint: String): Double =
        try { com.lifecyclebot.engine.BotService.status.tokens[mint]?.lastLiquidityUsd ?: Double.NaN } catch (_: Throwable) { Double.NaN }

    private fun keepRunning7997(o: Obs, gross: Double): Boolean =
        pending.size < MAX_PENDING_7731 && extended7997.get() - diedEarly7997.get() < MAX_EXTENDED_7997 &&
            worthWatchingNow8040(o, gross)

    private val tokenomicsKept8040 = AtomicLong(0)
    private val tokenomicsDropped8040 = AtomicLong(0)
    private const val MAX_TOP_HOLDER_PCT_8040 = 50.0
    private const val MAX_CAP_TO_LIQ_8040 = 200.0
    private const val STALE_MARK_MS_8040 = 15L * 60_000L

    /**
     * Pure. V5.0.8040 — past the hour, is the coin worth watching on its tokenomics? Alive (not down 60%, liquidity
     * held above 40% of the decision's), not safety-blocked, still trading (a mark under 15 minutes old), no single
     * holder over half the supply, and a cap its pool can carry (cap / liquidity <= 200). Unknown facts do not
     * disqualify; known bad ones do. Without the coin's live row only the price rule can judge (up 20%+ and alive).
     */
    fun worthWatching8040(grossPct: Double, entryLiq: Double, nowLiq: Double, markAgeMs: Long?, topHolderPct: Double?,
                          capUsd: Double, safetyBlocked: Boolean?): Boolean {
        if (safetyBlocked == null) return grossPct >= RUNNING_GROSS_7997 && alive7997(grossPct, entryLiq, nowLiq)
        // Its pool must still be there (above 40% of the decision's liquidity, when both are known).
        if (entryLiq > 0.0 && nowLiq.isFinite() && nowLiq > 0.0 && nowLiq < 0.4 * entryLiq) return false
        // A deep drawdown is not death while the pool holds and the coin still trades: it may recover, and the
        // recovery is graded. Without a live mark, a coin down 60%+ is treated as dead.
        if (!(grossPct > DEAD_GROSS_7997) && markAgeMs == null) return false
        if (safetyBlocked) return false
        if (markAgeMs != null && markAgeMs > STALE_MARK_MS_8040) return false
        if (topHolderPct != null && topHolderPct.isFinite() && topHolderPct > MAX_TOP_HOLDER_PCT_8040) return false
        if (capUsd.isFinite() && capUsd > 0.0 && nowLiq.isFinite() && nowLiq > 0.0 && capUsd / nowLiq > MAX_CAP_TO_LIQ_8040) return false
        return true
    }

    private fun worthWatchingNow8040(o: Obs, gross: Double): Boolean {
        val ts = try { com.lifecyclebot.engine.BotService.status.tokens[o.mint] } catch (_: Throwable) { null }
        val ok = worthWatching8040(
            gross, o.entryLiq7997, nowLiq7997(o.mint),
            ts?.lastPriceUpdate?.takeIf { it > 0L }?.let { System.currentTimeMillis() - it },
            ts?.topHolderPct,
            ts?.let { try { TrustedMcap8019.mcap8019(it) } catch (_: Throwable) { it.lastMcap } } ?: Double.NaN,
            ts?.safety?.isBlocked,
        )
        if (ok) tokenomicsKept8040.incrementAndGet() else tokenomicsDropped8040.incrementAndGet()
        return ok
    }

    /** Observe the wider path for exit learning; the entry target is immutable. True when following ends. */
    private fun checkpoint7997(o: Obs, net0: Double, gross0: Double, age: Long, nowMs: Long): Boolean {
        if (!eligible8386(o)) return false
        if (o.ck7997 >= CK_MS_7997.size) return true
        if (age < CK_MS_7997[o.ck7997]) return false
        captured7945(o, net0, gross0) // candidate stop/path diagnostics retain the actual later observations
        o.ck7997 += 1
        revisions7997.incrementAndGet()
        // V5.0.8040 — past the hour, alive means worth watching on tokenomics (a surviving or recovering coin).
        // V5.0.8416 — from 30 minutes on, only a coin worth following on its tokenomics is followed (was: past the hour).
        val alive = if (followGate8416(age)) worthWatchingNow8040(o, gross0) else alive7997(gross0, o.entryLiq7997, nowLiq7997(o.mint))
        if (!alive) { diedEarly7997.incrementAndGet(); o.ck7997 = CK_MS_7997.size }
        return !alive || o.ck7997 >= CK_MS_7997.size
    }

    /** Pure (V5.0.8418): has a checkpoint waited long enough for its Cortex revision to stage? */
    internal fun revisionStageGiveUp8418(ageMs: Long, checkpointMs: Long, waitMs: Long): Boolean = ageMs >= checkpointMs + waitMs
    private const val REVISION_STAGE_WAIT_MS_8418 = 120_000L

    /** V5.0.8410 — pure: the share of a booked label an aggregate still carries after decaying from [atBook] to [now]. */
    fun revisionWeight8410(atBook: Double, now: Double): Double =
        // V5.0.8418 — an unknown (non-finite) weight is 0: the revision is skipped, never applied at full weight.
        // superseded by V5.0.8418 — 8410 returned 1.0 for a non-finite input.
        if (!atBook.isFinite() || !now.isFinite()) 0.0 else kotlin.math.exp(now - atBook).coerceIn(0.0, 1.0)

    /** V5.0.8410 — pure: split an accumulated fractional count into (whole, remainder), the remainder in (-1, 1). */
    fun carry8410(x: Double): Pair<Int, Double> { val whole = if (x >= 0.0) kotlin.math.floor(x) else kotlin.math.ceil(x); return whole.toInt() to (x - whole) }

    fun progressiveLine7997(): String =
        "checkpoints=1m,5m,15m,30m,1h,4h,24h (from 30m only if worth following: kept=${tokenomicsKept8040.get()} dropped=${tokenomicsDropped8040.get()}) entryTarget=frozen pathReads=${revisions7997.get()} " +
            "followedPast1h=${extended7997.get()} stoppedDead=${diedEarly7997.get()} runExitSamples8006=${exitRunSamples8006.get()}"

    private fun bookTwoForty7944(o: Obs, net0: Double, gross0: Double, deferExit8006: Boolean = false): Boolean {
        if (!eligible8386(o)) return true
        var (net, gross) = captured7945(o, net0, gross0)
        if (o.targetHorizon8521 > H60_MS_7731 && !flatMarkNow8342(o, gross0) &&   // V5.0.8592 — key off the per-obs hour target
            !stageForwardRevision8404(o, net, gross, System.currentTimeMillis())) return false
        if (o.targetHorizon8521 > H60_MS_7731 && !o.bookedNet7997.isFinite() && !flatMarkNow8342(o, gross0)) {   // V5.0.8592
            val episode = "${o.mint}|${o.lane}|${o.atMs}"
            val fixed = forwardOutbox8404.firstRevisionForHorizon8521(episode, o.targetHorizon8521)?.second?.let {
                com.lifecyclebot.engine.cortex.ForwardOutcomeDelivery8404.decode(it)
            }
            if (fixed != null) {
                net = fixed.net; gross = fixed.gross
            }
            val witnessed = fixed?.witness ?: (gross >= RUNNER_PCT_7731 && witnessNow8324(o, System.currentTimeMillis()))
            bookEntryTarget8521(o, net, gross, witnessed, System.currentTimeMillis())
            val long = captured7945(o, net0, gross0, longFamily8080 = true)
            o.bookedLongNet8080 = long.first; o.bookedLongGross8080 = long.second
        }
        // V5.0.7955 — one exit-profile sample per observation: peak, time to peak, give-back to the 60-minute read.
        // V5.0.7955 review — exits are learned from decisions the bot took (and realised closes),
        // not from the refused pool, which is mostly tokens that fade.
        // V5.0.7961 — a refused decision whose setup fired teaches that setup's own key only.
        // V5.0.8006 — a coin still running at the hour is followed on (7997); its exit sample waits
        // for the end of the run, so the exit plans learn the peak and time-to-peak of the whole
        // run instead of "peaked at minute 60" (which taught every runner key to sell early).
        if (deferExit8006) o.exitDeferred8006 = true else exitSample8006(o, gross0)
        // V5.0.8342 — a mark still flat at the hour (no price other than the entry ever seen) is a LOST mark here too, never a
        // -cost label: 8337 FIX 2 closed the 60 slot; this slot booked it (and its lane / stage late tallies) unchanged.
        if (flatMarkNow8342(o, gross0)) { flatMarks8322.incrementAndGet(); markLost(o); return true }
        book(o, 240, net, gross)
        return true // Cortex's old onLabel(..., 240) was a no-op; the exact revision now owns delivery.
    }

    /** V5.0.8342 — the observation's mark at [grossPct] has never left its entry (8322's flat test, on the live peak / dip). */
    private fun flatMarkNow8342(o: Obs, grossPct: Double): Boolean =
        com.lifecyclebot.engine.cortex.CortexFlatMark8322.flatMark8322(grossPct, o.peakPct, o.minPct7962) && !liveMark8454(o.lastMark8386)

    // ── V5.0.8454 §A_FLAT_READ_FROM_THE_MARKET_IS_AN_OUTCOME ──
    // Owner, 2026-10-31: "there isn't meant to be a lost mark ever, missing data ever, a stale price a stale mark nothing.
    // everything is meant to be tracked from discovery until disposal with consistent true data". 8450 (10 min): 1,034
    // labels went LOST as flat (8337 FIX 2 treated every unchanged price as no observation) — every coin that did not move
    // left the books, so no book ever learned that a dead coin costs the round trip and TAKE / REFUSE read the same -6%.
    // A flat read the market itself gave (the off-watch fetch, a curve account / tape read, an independent basis) is now
    // the coin's real outcome; a flat read from a token-state price alone (which 42 writers stamp "now") is confirmed by a
    // live fetch inside the window before anything is booked.
    private val flatAwaitingLive8454 = AtomicLong(0)
    private val flatLiveBooked8454 = AtomicLong(0)

    /** Pure (V5.0.8454): did this mark observe the market itself (not a possibly reused token-state value)? */
    fun liveMark8454(source: String?, independent: Boolean): Boolean =
        independent || source == "OFFWATCH" || source?.startsWith("CURVE_") == true ||
            source == CROSS_ASSET_MARK_SOURCE_8571   // V5.0.8571 — the identity's own feed, at its own observation time

    private fun liveMark8454(m: LabelMark8386?): Boolean = m != null && liveMark8454(m.source, m.independentBasis)

    /** V5.0.8455 — a tracked coin's price path is refreshed at least this often between its horizons. */
    const val PATH_REFRESH_MS_8455 = 60_000L

    /** Pure (V5.0.8455): is a tracked coin (decided at [atMs], last priced at [lastPxAtMs]) due a continuous path read? */
    fun trackPath8455(lastPxAtMs: Long, atMs: Long, nowMs: Long): Boolean =
        nowMs - maxOf(lastPxAtMs, atMs) >= PATH_REFRESH_MS_8455

    // Count first hour target grades, including decisions with an unavailable five-minute interim read.
    private val revisedToHour8337 = AtomicLong(0)

    /** Pure (V5.0.8337): is [lane] graded on its hour slot — a runner-from-entry lane or a runner lane? */
    fun runnerSlot8337(lane: String): Boolean =
        (try { com.lifecyclebot.engine.TradeExitContract8077.runnerFromEntryLane8104(lane, false) } catch (_: Throwable) { false }) ||
            (try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }) ||
            (try { com.lifecyclebot.engine.cortex.LaneGradeHorizon8406.hourGraded8406(lane) } catch (_: Throwable) { false })   // V5.0.8406 — the mandate decides

    private val exitRunSamples8006 = AtomicLong(0)

    /** The observation's one exit-profile sample, at the hour or at the end of a followed run. */
    private fun exitSample8006(o: Obs, gross0: Double) {
        if (!eligible8386(o)) return
        val setupFired7961 = try { com.lifecyclebot.engine.ExitProfile7955.setupFired7961(o.setup7955) } catch (_: Throwable) { false }
        if (o.admitted || setupFired7961) try {
            com.lifecyclebot.engine.ExitProfile7955.onLabel7955(
                o.lane, o.setup7955, maxOf(o.peakPct, gross0), if (o.peakAtMs7955 > o.atMs) o.peakAtMs7955 - o.atMs else 0L,
                com.lifecyclebot.engine.ExitProfile7955.giveback7955(maxOf(o.peakPct, gross0), gross0), o.giveback5_7955,
                keyOnly = !o.admitted, dipBeforePeakPct = o.dipBeforePeak7962,
            )
        } catch (_: Throwable) {}
    }

    /** V5.0.8006 — a followed run ended: its deferred exit sample is taken on the whole path. */
    private fun endRun8006(o: Obs, gross: Double) {
        if (!eligible8386(o)) return
        if (!o.exitDeferred8006) return
        o.exitDeferred8006 = false
        exitRunSamples8006.incrementAndGet()
        exitSample8006(o, gross)
    }

    /**
     * V5.0.7944 — what a vanished mark was worth. 5.0.7941 dropped 1,928 refused
     * labels as LOST_MARK and 5.0.7942 then counted every one of them as -100%,
     * which was as wrong as dropping them. Every route and the curve stopped
     * pricing the token by its horizon, so its last price is the best read of
     * what it was worth:
     *  - DEAD: the last read was already a collapse (gross <= [DEAD_GROSS_PCT_7944])
     *    and nothing prices it now: the pool or curve is gone, booked at -100%.
     *  - LAST_MARK: a read from the second half of the horizon: booked at it.
     *  - GAP: no read late enough to say anything (the app was off, or it left the
     *    watchlist early): stays LOST_MARK and is not booked either way.
     */
    enum class Vanished7944 { DEAD, LAST_MARK, GAP }

    private const val DEAD_GROSS_PCT_7944 = -60.0

    /** Pure. [lastSeenAgeMs] is the observation's age at its last read, or <= 0 when it never had one. */
    fun classifyVanished7944(lastGrossPct: Double, lastSeenAgeMs: Long, horizonMs: Long): Vanished7944 = when {
        lastSeenAgeMs <= 0L || !lastGrossPct.isFinite() -> Vanished7944.GAP
        lastGrossPct <= DEAD_GROSS_PCT_7944 -> Vanished7944.DEAD
        lastSeenAgeMs >= horizonMs / 2 -> Vanished7944.LAST_MARK
        else -> Vanished7944.GAP
    }

    private val vanishedDead7944 = AtomicLong(0)
    private val vanishedLastMark7944 = AtomicLong(0)

    /** A price for [mint] from the loop's token states, else the canonical mark registry when fresh. */
    /**
     * V5.0.7948 — the same mark a forward label is read on (loop price, else the
     * canonical registry, else the off-watch batch), for the learners that grade on
     * the label's clock (CortexTiming7900, the shadow book). They read the loop's
     * token-state closure alone, so a candidate that left the watchlist was never
     * priced at its window: Timing WINDOW_MISSED=544, shadow book 9 opens / 0 closes.
     */
    fun markFor7948(mint: String, priceFor: (String) -> Double?, nowMs: Long = System.currentTimeMillis()): Double? =
        try { markFor(mint, priceFor, nowMs) } catch (_: Throwable) { null }

    /**
     * V5.0.8372 — cached fallback with the real observation clock. A shadow sell
     * cannot use the time it READ a still-cached mark as evidence of a new fill.
     * The freshest valid registry/off-watch/curve observation; no provider call or fetch.
     */
    fun observedMark8372(mint: String, nowMs: Long = System.currentTimeMillis()): Pair<Double, Long>? {
        if (mint.isBlank()) return null
        val registry = try {
            CanonicalMarkPurpose6570.values().mapNotNull { CanonicalPriceMarkRegistry6522.get(mint, it) }
                .filter { it.timestampMs > 0L && nowMs - it.timestampMs in 0L..MARK_MAX_AGE_MS_7731 &&
                    it.priceUsd.value.toDouble().let { px -> px.isFinite() && px > 0.0 } }
                .maxByOrNull { it.timestampMs }
        } catch (_: Throwable) { null }
        var freshest = registry?.let { it.priceUsd.value.toDouble() to it.timestampMs }
        val off = offWatchMarks7737[mint]
        if (off != null && off.first.isFinite() && off.first > 0.0 && off.second > 0L &&
            nowMs - off.second in 0L..MARK_MAX_AGE_MS_7731 && off.second > (freshest?.second ?: 0L)) freshest = off
        try {
            val reserves = CurveReserves7951.activeExitObservation(mint, nowMs, MARK_MAX_AGE_MS_7731)
            val solUsd = com.lifecyclebot.engine.WalletManager.labelSolPrice8580(nowMs)
            if (reserves != null && reserves.observedAtMs > (freshest?.second ?: 0L)) {
                val px = CurveReserves7951.priceSol7951(reserves.virtualSol, reserves.virtualTokens) * solUsd
                if (solUsd.isFinite() && solUsd > 0.0 && px.isFinite() && px > 0.0) freshest = px to reserves.observedAtMs
            }
        } catch (_: Throwable) {}
        return freshest
    }

    /**
     * A due label must be covered by a MARKET observation, not simply a fresh-looking token-state/registry copy.
     * Several writers re-stamp a cached price with the current clock; allowing that timestamp to satisfy the
     * pair-lane freshness check suppresses the very independent fetch needed to grade the forward outcome.
     * This is a local, cached read only (no provider request); missing evidence remains unknown.
     */
    fun independentMarkAt8418(mint: String, nowMs: Long = System.currentTimeMillis()): Long {
        if (mint.isBlank()) return 0L
        val mark = typedMark8386(mint, { null }, nowMs)
        return if (liveMark8454(mark)) mark?.observedAtMs ?: 0L else 0L
    }

    internal fun typedMark8386(mint: String, priceFor: (String) -> Double?, nowMs: Long): LabelMark8386? {
        val candidates = ArrayList<LabelMark8386>(6)
        try {
            for (purpose in CanonicalMarkPurpose6570.values()) {
                val m = CanonicalPriceMarkRegistry6522.get(mint, purpose) ?: continue
                if (m.mint != mint || m.baseMint != mint || m.canonicalAssetId7424 != mint) continue
                candidates.add(LabelMark8386(mint, m.pairId, m.priceUsd.value.toDouble(), m.timestampMs, nowMs,
                    m.source, m.priceUnits7424, m.tokenDecimals7424,
                    independentBasis = LabelEvidence8386.independentSource8418(m.source)))   // V5.0.8418 — swap prints / pool reserves
            }
            val o = CurveReserves7951.activeExitObservation(mint, nowMs, MARK_MAX_AGE_MS_7731)
            val solUsd = com.lifecyclebot.engine.WalletManager.labelSolPrice8580(nowMs)
            val accepted = CurveReserves7951.acceptedObservation8385(mint)
            // V5.0.8508 — a curve read the label could not use because no fresh SOL/USD rate existed (counted, not guessed).
            if (o != null && !(solUsd.isFinite() && solUsd > 0)) noSolRate8508.incrementAndGet()
            if (o != null && solUsd.isFinite() && solUsd > 0) candidates.add(LabelMark8386(mint, "pump.curve",
                CurveReserves7951.priceSol7951(o.virtualSol, o.virtualTokens) * solUsd, o.observedAtMs, nowMs,
                "CURVE_${accepted?.source ?: "UNKNOWN"}", decimals = 6,
                // V5.0.8418 — the curve tape (FRAME: a trade print with its post-trade reserves) is chain data as much as the
                // account read; either one, at this exact observation, is an independent basis.
                independentBasis = accepted != null && (accepted.source == "ACCOUNT" || accepted.source == "FRAME") && accepted.atMs == o.observedAtMs))
        } catch (_: Exception) {}
        offWatchMarks7737[mint]?.let { candidates.add(LabelMark8386(mint, "", it.first, it.second, nowMs, "OFFWATCH")) }
        // V5.0.8475 — the coin's own tape (Helius program curve tape, PumpSwap pooled prints, PumpPortal frames): an executed
        // print is a chain-exact mark. 8471: tape prints priced 6 labels while the tape taped thousands.
        try {
            val tp = TradeTape8100.lastPrint8360(mint)
            val solUsd = com.lifecyclebot.engine.WalletManager.labelSolPrice8580(nowMs)
            if (tp != null && !(solUsd.isFinite() && solUsd > 0.0)) noSolRate8508.incrementAndGet()
            if (tp != null && solUsd.isFinite() && solUsd > 0.0)
                candidates.add(LabelMark8386(mint, "", tp.first * solUsd, tp.second, nowMs, TAPE_MARK_SOURCE_8475, independentBasis = true))
        } catch (_: Throwable) {}
        // V5.0.8571 — the coin's own pool vaults (the off-watch chain rung and every depth read): a chain mark at the vault read's
        // own time. The chain rung priced these into its batch map only, so a vault read never reached a label (chain8540=169).
        try {
            val vr = OnChainDepth8038.freshRead8198(mint, MARK_MAX_AGE_MS_7731, nowMs)
            val solUsd = com.lifecyclebot.engine.WalletManager.labelSolPrice8580(nowMs)
            if (vr != null && !(solUsd.isFinite() && solUsd > 0.0)) noSolRate8508.incrementAndGet()
            if (vr != null && solUsd.isFinite() && solUsd > 0.0) {
                val px = OnChainDepth8038.priceUsd8038(vr.quoteSol, vr.baseTokens, solUsd)
                if (px.isFinite() && px > 0.0) candidates.add(LabelMark8386(mint, vr.pool, px, vr.atMs, nowMs, VAULT_MARK_SOURCE_8571,
                    independentBasis = true))
            }
        } catch (_: Throwable) {}
        // A bare loop price has no timestamp. It may only reuse the matching state observation, never this read's clock.
        try {
            val px = priceFor(mint)
            val ts = com.lifecyclebot.engine.BotService.status.tokens[mint]
            if (px != null && ts != null && ts.mint == mint && ts.lastPrice == px)
                candidates.add(LabelMark8386(mint, ts.lastPriceDex, px, ts.lastPriceUpdate, nowMs, "TOKEN_STATE"))
        } catch (_: Exception) {}
        // V5.0.8571 — a cross-asset identity (crypto desk, multi-chain meme pair) is priced by its own feed with that feed's
        // observation time; its TokenState is synthetic, so the untimed loop price above never matched it.
        try {
            com.lifecyclebot.engine.cortex.CrossAssetCortex7931.markAt8571(mint, nowMs)?.let { (px, at) ->
                candidates.add(LabelMark8386(mint, "", px, at, nowMs, CROSS_ASSET_MARK_SOURCE_8571))
            }
        } catch (_: Throwable) {}
        // A market read (curve account / tape print / off-watch fetch / independent basis) outranks a re-stamped copy: token-state
        // and registry copies are stamped "now" by dozens of writers, so newest-first picked the cached entry price over a real
        // print and the label read flat (8479 report: rebased flat8322=632, flatLostMarks=266). A copy is used only when no
        // market read is in age.
        val fresh = candidates.filter { it.known() && nowMs - it.observedAtMs in 0L..MARK_MAX_AGE_MS_7731 }
        return (fresh.filter { liveMark8454(it) }.takeIf { it.isNotEmpty() } ?: fresh)
            .maxWithOrNull(compareBy<LabelMark8386> { it.observedAtMs }.thenBy { it.independentBasis })
    }

    private fun markFor(mint: String, priceFor: (String) -> Double?, nowMs: Long): Double? =
        typedMark8386(mint, priceFor, nowMs)?.usd

    private fun eligible8386(o: Obs): Boolean = LabelEvidence8386.eligible(o.basis8386.mark, o.lastMark8386)

    // ── V5.0.8475 — the mints with a forward label pending (for the Helius program tape's care filter) and the tape mark ──
    /** The source of a label mark taken from the coin's own trade tape (markFamily8418: TAPE). */
    const val TAPE_MARK_SOURCE_8475 = "TAPE_LAST_PRINT_8475"
    /** V5.0.8571 — the source of a label mark taken from the coin's own pool vault read (OnChainDepth8038). */
    const val VAULT_MARK_SOURCE_8571 = "POOL_RESERVES_VAULT_8571"
    /** V5.0.8571 — the source of a label mark taken from a cross-asset identity's own price feed (CrossAssetCortex7931). */
    const val CROSS_ASSET_MARK_SOURCE_8571 = "CROSS_ASSET_FEED_8571"
    /** A pending mint is kept this long after its last observation: the longest horizon (60 min) and its grace. */
    private const val PENDING_MINT_MS_8475 = 70L * 60_000L
    private const val MAX_PENDING_MINTS_8475 = 8_000
    private val pendingMints8475 = ConcurrentHashMap<String, Long>()

    private fun notePendingMint8475(mint: String, nowMs: Long) {
        if (mint.isBlank()) return
        pendingMints8475[mint] = nowMs + PENDING_MINT_MS_8475
        if (pendingMints8475.size > MAX_PENDING_MINTS_8475) prunePendingMints8475(nowMs)
    }

    private fun prunePendingMints8475(nowMs: Long) {
        pendingMints8475.entries.removeIf { it.value <= nowMs }
        if (pendingMints8475.size > MAX_PENDING_MINTS_8475)
            pendingMints8475.entries.sortedBy { it.value }.take(pendingMints8475.size - MAX_PENDING_MINTS_8475).forEach { pendingMints8475.remove(it.key) }
    }

    /** HeliusCurveTape8354.cares8354: does [mint] have a forward label pending (its reads come from the tape)? */
    fun isPendingMint8475(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean = (pendingMints8475[mint] ?: 0L) > nowMs
    private fun pendingMintCount8475(): Int = pendingMints8475.size

    private val curvePriced8062 = AtomicLong(0)

    /**
     * V5.0.7809 §THE_OLDEST_DEAD_MINTS_HELD_THE_BATCH. Off-watch marks are
     * fetched fifty at a time, oldest due observation first. A mint Jupiter and
     * the curve no longer price stays due (and oldest) until its 70-minute
     * lost-mark deadline, so once fifty dead mints sat at the head every batch
     * re-asked for exactly those fifty and no younger observation was ever
     * priced: large pending/observed, booked15/60/240 near zero. A mint just
     * asked for waits [OFFWATCH_RETRY_MS_7809] before it is asked again, so the
     * batch rotates through every due observation.
     */
    private const val OFFWATCH_RETRY_MS_7809 = 30_000L   // V5.0.8405 — inside the 1-minute horizon grace (was 90 s); V5.0.8535 — the ceiling of the window-scaled gap
    /** V5.0.8535 §P0-01 — the Jupiter rung asks at most this many misses a batch (three calls of 50). */
    private const val JUP_MAX_PER_BATCH_8535 = 150
    private val offWatchAttemptAt7809 = ConcurrentHashMap<String, Long>()
    private val offWatchDeferred7809 = AtomicLong(0)
    private val horizonMissed7809 = AtomicLong(0)

    // V5.0.8418 §EVERY_LABEL_GETS_ITS_MARK — labelMarks8418: the 5-minute reads due, booked from a mark (by its source family),
    // and closed unavailable (noSource: never priced by any source; late: marks seen, none accepted in the window). See CortexLabelFeed8291.
    private val labelDue8418 = AtomicLong(0)
    private val labelPricedDex8418 = AtomicLong(0)
    private val labelPricedCurve8418 = AtomicLong(0)
    private val labelPricedTape8418 = AtomicLong(0)
    private val labelLostNoSource8418 = AtomicLong(0)
    private val labelLostLate8418 = AtomicLong(0)
    private const val MAX_OFFWATCH_MARKS_8418 = 6_000

    /**
     * V5.0.8418 — KeylessMarks8359: the pair lane priced [mint] (a due label mint) at [atMs]. Stored as its off-watch mark,
     * exactly as the off-watch batch stores a DexScreener answer; an older entry never replaces a newer one. True when kept.
     */
    fun noteLaneMark8418(mint: String, px: Double, atMs: Long): Boolean {
        if (mint.isBlank() || !px.isFinite() || px <= 0.0 || atMs <= 0L) return false
        val observation = px to atMs
        val newest = offWatchMarks7737.compute(mint) { _, prior ->
            if (prior != null && prior.second >= atMs) prior else observation
        }
        if (newest !== observation) return false
        if (offWatchMarks7737.size > MAX_OFFWATCH_MARKS_8418) offWatchMarks7737.entries.removeIf { atMs - it.value.second > MARK_MAX_AGE_MS_7731 }
        return true
    }

    /** Publish this network response immediately. A later fallback cannot change its receipt time. */
    internal fun noteOffWatchResponse(prices: Map<String, Double>, receivedAtMs: Long = System.currentTimeMillis()) {
        for ((mint, price) in prices) noteLaneMark8418(mint, price, receivedAtMs)
    }

    /** V5.0.8418 — the window an unpriced [o] must be priced in now (0 = none): re-base, each open horizon, the due checkpoint. */
    private fun labelWindowEnd8418(o: Obs, age: Long, firstMarkDue: Boolean): Long {
        val ck = if (o.done60 && o.ck7997 in CK_MS_7997.indices) CK_MS_7997[o.ck7997] else -1L
        val early = timingHorizons8528(o)
        val hs = longArrayOf(if (o.done15) -1L else H15_MS_7731, if (o.done60) -1L else H60_MS_7731, if (o.done240) -1L else H240_MS_7731, ck) + early
        val gs = longArrayOf(graceFor7946(H15_MS_7731), graceFor7946(H60_MS_7731), graceFor7946(H240_MS_7731), if (ck > 0L) graceFor7946(ck) else 0L) + early.map { SetupTimingGrades8528.grace(it) }.toLongArray()
        return com.lifecyclebot.engine.cortex.CortexLabelFeed8291.windowEnd8418(o.atMs, age, firstMarkDue, REBASE_MAX_MS_8076, hs, gs,
            com.lifecyclebot.engine.cortex.CortexLabelFeed8291.LABEL_LEAD_MS_8418)
    }

    /** All promised targets compete by their actual deadline, including the patient lane's hour. */
    private fun labelOffWatchRank8521(o: Obs, age: Long, first: Boolean): Int {
        val ck = if (o.done60 && o.ck7997 in CK_MS_7997.indices) CK_MS_7997[o.ck7997] else -1L
        val horizons = longArrayOf(if (o.done15) -1L else H15_MS_7731, if (o.done60) -1L else H60_MS_7731,
            if (o.done240) -1L else H240_MS_7731, ck) + timingHorizons8528(o)
        val due = com.lifecyclebot.engine.cortex.CortexLabelFeed8291.requiredObservationAt8521(o.atMs, o.atMs + age,
            horizons, LongArray(horizons.size) { if (it >= 4) SetupTimingGrades8528.grace(horizons[it]) else graceFor7946(horizons[it]) })
        if (due > 0L) return 0
        return com.lifecyclebot.engine.cortex.CortexLabelFeed8291.offWatchRank8291(age, o.done60, first,
            H60_MS_7731, graceFor7946(H60_MS_7731))
    }

    /** A non-null cached or incompatible mark must not skip its own independent refresh on an early continue. */
    private fun queueIndependentRefresh8521(o: Obs, mark: LabelMark8386, nowMs: Long,
                                            due: MutableList<Pair<String, Long>>) {
        val age = nowMs - o.atMs
        val first = firstMarkDue8187(o.rebased8076, o.lastPxAtMs, age)
        val end = labelWindowEnd8418(o, age, first)
        val fillAt = o.atMs + rebaseDelayMs8094(try { EntryChase7961.laneLatencyMs8094(o.lane) } catch (_: Throwable) { 0L })
        val requiredAt = if (first && nowMs >= fillAt) fillAt else {
            val ck = if (o.done60 && o.ck7997 in CK_MS_7997.indices) CK_MS_7997[o.ck7997] else -1L
            val horizons = longArrayOf(if (o.done15) -1L else H15_MS_7731, if (o.done60) -1L else H60_MS_7731,
                if (o.done240) -1L else H240_MS_7731, ck) + timingHorizons8528(o)
            com.lifecyclebot.engine.cortex.CortexLabelFeed8291.requiredObservationAt8521(o.atMs, nowMs,
                horizons, LongArray(horizons.size) { if (it >= 4) SetupTimingGrades8528.grace(horizons[it]) else graceFor7946(horizons[it]) })
        }
        if (!com.lifecyclebot.engine.cortex.CortexLabelFeed8291.needsIndependentRefresh8521(
                end, nowMs, liveMark8454(mark), LabelEvidence8386.eligible(o.basis8386.mark, mark), mark.observedAtMs,
                requiredAfterMs = requiredAt)) return
        try { KeylessMarks8359.noteLabelDue8418(o.mint, end, nowMs) } catch (_: Throwable) {}
        if (end > nowMs) labelWindowEnd8535[o.mint] = end   // V5.0.8535 §P0-01
        val rank = labelOffWatchRank8521(o, age, first)
        due.add(o.mint to com.lifecyclebot.engine.cortex.CortexLabelFeed8291.offWatchKey8291(rank, end))
    }

    /** V5.0.8418 — the 5-minute read booked from a mark: counted by the mark's source family. */
    private fun notePriced8418(o: Obs) {
        if (!o.firstReadValid8521 && !o.bookedNet7997.isFinite()) return   // flat / ineligible: not a priced read
        when (com.lifecyclebot.engine.cortex.CortexLabelFeed8291.markFamily8418(o.lastMark8386?.source)) {
            "CURVE" -> labelPricedCurve8418.incrementAndGet()
            "TAPE" -> labelPricedTape8418.incrementAndGet()
            else -> labelPricedDex8418.incrementAndGet()
        }
    }

    /** V5.0.8441 — DiagnosisBrief8438 F LEARNING: [due, priced, lostNoSource, lostLate] for the 5-minute label marks. */
    fun labelMarks8441(): LongArray = longArrayOf(labelDue8418.get(),
        labelPricedDex8418.get() + labelPricedCurve8418.get() + labelPricedTape8418.get(), labelLostNoSource8418.get(), labelLostLate8418.get())

    private fun labelMarksLine8418(): String = com.lifecyclebot.engine.cortex.CortexLabelFeed8291.labelMarksLine8418(
        labelDue8418.get(), labelPricedDex8418.get(), labelPricedCurve8418.get(), labelPricedTape8418.get(),
        labelLostNoSource8418.get(), labelLostLate8418.get(), try { KeylessMarks8359.labelCounts8418() } catch (_: Throwable) { null }) +
        " " + lostByVenueLine8474() +   // V5.0.8474
        " " + DowntimeRecovery8430.statusLine8430() +   // V5.0.8430
        " " + LabelClock8442.statusLine8442() + " shadowWanted8442=${shadowWanted8442.size} pendingMints8475=${pendingMintCount8475()}" +   // V5.0.8442 / 8475
        " flat8454[awaitingLive=${flatAwaitingLive8454.get()} liveBooked=${flatLiveBooked8454.get()}]" +   // V5.0.8454
        " downtimeUnobserved8505=${labelLostDowntime8505.get()} ${timingBook8528.line()}" +   // V5.0.8505
        " " + chargedCostLine8534() +   // V5.0.8534
        " " + coverageLine8535()   // V5.0.8535

    /** V5.0.8535 §P0-01 — priced / due by horizon and the provider cause of every unavailable terminal. */
    private fun coverageLine8535(): String =
        "labelCoverage8535[h1m=${booked15.get()}/${labelDue15_8535.get()} h5m=${booked60.get()}/${labelDue8418.get()} h60m=${booked240.get()}/${labelDue240_8535.get()} " +
            "lostByCause{" + lostByCause8535.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } + "}]"

    /** Pure: a horizon label may book only from a mark at most the lost-mark grace past it. */
    fun horizonOpen7809(ageMs: Long, horizonMs: Long): Boolean =
        ageMs >= horizonMs && ageMs <= horizonMs + graceFor7946(horizonMs)

    // V5.0.8535 §P0-01 — each due mint's window end (the grace it must be priced inside), so its retry gap follows ITS window:
    // a 1-minute label (60 s grace) is re-asked every ~20 s, not once at 30 s; a 1-hour label keeps the 30 s gap.
    private val labelWindowEnd8535 = ConcurrentHashMap<String, Long>()
    private val lastCause8535 = ConcurrentHashMap<String, OffWatchSchedule8535.Cause>()
    private val lostByCause8535 = ConcurrentHashMap<String, AtomicLong>()
    private val labelDue15_8535 = AtomicLong(0)
    private val labelDue240_8535 = AtomicLong(0)

    private fun noteCause8535(mint: String, next: OffWatchSchedule8535.Cause) {
        lastCause8535.compute(mint) { _, prior -> OffWatchSchedule8535.merge(prior, next) }
    }

    private fun rotateDue7809(due: List<String>, nowMs: Long): List<String> {
        if (offWatchAttemptAt7809.size > MAX_SEEN_7731) {
            offWatchAttemptAt7809.entries.removeIf { nowMs - it.value > OFFWATCH_RETRY_MS_7809 }
            labelWindowEnd8535.entries.removeIf { it.value < nowMs - LOST_GRACE_MS_7731 }
            lastCause8535.keys.removeIf { !labelWindowEnd8535.containsKey(it) && !offWatchAttemptAt7809.containsKey(it) }
        }
        // The caller's order (rank, then time) is kept for mints with no known window; a known window sorts by its close.
        val (ready, deferred) = OffWatchSchedule8535.ready(
            due.mapIndexed { i, m -> OffWatchSchedule8535.Due(m, labelWindowEnd8535[m] ?: (Long.MAX_VALUE - (due.size - i))) },
            { m -> offWatchAttemptAt7809[m] }, nowMs)
        if (deferred > 0) offWatchDeferred7809.addAndGet(deferred.toLong())
        return ready
    }

    /** V5.0.7737 — true when [o] has reached a horizon it has not booked yet. */
    private fun dueAtHorizon7737(o: Obs, age: Long): Boolean =
        timingHorizons8528(o).any { age in it..(it + SetupTimingGrades8528.grace(it)) } ||
        (!o.done15 && age >= H15_MS_7731) || (!o.done60 && age >= H60_MS_7731) || (!o.done240 && age >= H240_MS_7731)

    /** V5.0.7737 — prices the oldest due, unpriced mints (the 60-minute label first) in one batch on a background thread. */
    /** Pure (V5.0.8418): split [batch] into (on an active pump curve, everything else), order kept. */
    fun splitOnCurve8418(batch: List<String>, onCurve: (String) -> Boolean): Pair<List<String>, List<String>> {
        val on = ArrayList<String>(); val off = ArrayList<String>()
        for (m in batch) if (try { onCurve(m) } catch (_: Throwable) { false }) on += m else off += m
        return on to off
    }

    /**
     * Pure (V5.0.8474): the venue family of a coin whose label went unpriced — an active pump.fun curve (the curve account
     * prices it), a graduated pump.fun coin (its PumpSwap / AMM pool must), a pump.fun mint the curve was never read for, a
     * Raydium LaunchLab ("bonk") coin, or any other venue.
     */
    fun venueOf8474(mint: String, onActiveCurve: Boolean, graduated: Boolean): String = when {
        onActiveCurve -> "PUMP_CURVE"
        graduated -> "PUMP_GRADUATED"
        mint.endsWith("pump", ignoreCase = true) -> "PUMP_UNREAD"
        mint.endsWith("bonk", ignoreCase = true) -> "LAUNCHLAB"
        else -> "OTHER"
    }

    private val lostByVenue8474 = ConcurrentHashMap<String, AtomicLong>()

    private fun noteLostVenue8474(mint: String, late: Boolean) {
        val venue = venueOf8474(mint, try { onActiveCurve8418(mint) } catch (_: Throwable) { false },
            try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint) } catch (_: Throwable) { false })
        lostByVenue8474.computeIfAbsent(venue + if (late) "|late" else "|noSource") { AtomicLong() }.incrementAndGet()
    }

    /** V5.0.8474 — lostByVenue8474{venue:noSource/late ...}, most lost first. */
    private fun lostByVenueLine8474(): String {
        val venues = lostByVenue8474.keys.map { it.substringBefore('|') }.distinct()
        val rows = venues.map { v -> Triple(v, lostByVenue8474["$v|noSource"]?.get() ?: 0L, lostByVenue8474["$v|late"]?.get() ?: 0L) }
            .sortedByDescending { it.second + it.third }
        return "lostByVenue8474{" + rows.joinToString(" ") { "${it.first}:${it.second}/${it.third}" }.ifBlank { "-" } + " (noSource/late)}"
    }

    private fun onActiveCurve8418(mint: String): Boolean =
        (try { com.lifecyclebot.network.PumpFunDirectApi.isPumpFunMint(mint) } catch (_: Throwable) { false }) &&
            !(try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint) } catch (_: Throwable) { true })

    // ── V5.0.8583 §HORIZON_PRICER — every label window gets its own price read, on its own thread ──
    //
    // 8575 log: labelCoverage h1m=612/912, h5m=16/443, h60m=0/1,826 — the read that grades every hour lane was NEVER priced.
    // A horizon was booked only if SOME source happened to hold a fresh mark inside its window: the coin's watch row (gone
    // once it leaves the watchlist), or the shared off-watch batch, which re-asks all ~3,400 pending coins every 60 s from
    // one 300-mint slot on the bot loop (avg 16-24 s a batch, 13,283 deferrals) and so reached a 60-minute window by luck.
    // The pricer owns the windows instead: every 2 s, on its own thread, it takes each pending observation whose re-base,
    // 1-minute, 5-minute or 60-minute read window is OPEN and unbooked, collapses them to their mints (lanes share coins),
    // and prices each mint (at most once per [HP_READ_GAP_MS_8583]) through the curve account, DexScreener, Jupiter and the
    // pool vaults, publishing each answer stamped at its read time — inside the window by construction. The tick then books it.
    const val HP_TICK_MS_8583 = 2_000L
    const val HP_READ_GAP_MS_8583 = 15_000L
    // V5.0.8650 — no 300-mint serial provider trip ahead of a one-minute deadline.
    // Three 30-mint Dex chunks fit the off-watch host lane and keep retry opportunities open.
    const val HP_MAX_MINTS_8583 = 90
    private val hpLastRead8583 = ConcurrentHashMap<String, Long>()
    private val hpRunning8583 = java.util.concurrent.atomic.AtomicBoolean(false)
    private val hpPasses8583 = AtomicLong(0); private val hpAsked8583 = AtomicLong(0)
    private val hpCurve8583 = AtomicLong(0); private val hpDex8583 = AtomicLong(0)
    private val hpJup8583 = AtomicLong(0); private val hpChain8583 = AtomicLong(0); private val hpUnpriced8583 = AtomicLong(0)

    /** Pure (V5.0.8583): an unbooked read window of a decision made [ageMs] ago is open now. */
    fun horizonWindowOpen8583(ageMs: Long, rebased: Boolean, done15: Boolean, done60: Boolean, done240: Boolean): Boolean =
        (!rebased && ageMs in REBASE_MIN_MS_8076..REBASE_MAX_MS_8076) ||
            (!done15 && horizonOpen7809(ageMs, H15_MS_7731)) ||
            (!done60 && horizonOpen7809(ageMs, H60_MS_7731)) ||
            (!done240 && horizonOpen7809(ageMs, H240_MS_7731))

    /**
     * V5.0.8650: the ACTUAL earliest closing mark window for this observation. The 8583 loop ranked oldest
     * decisions first, so 60-minute reads consumed bandwidth while short 1/5-minute windows expired. It also
     * never opened the new 15-minute long-lane target (8592): it waited for the old 60-minute slot instead.
     * Pure, constant-memory and based only on already promised deadlines, not a new trading gate.
     */
    internal fun horizonDeadline8650(atMs: Long, nowMs: Long, rebased: Boolean, done15: Boolean,
                                     done60: Boolean, done240: Boolean, targetHorizonMs: Long): Long? {
        val age = nowMs - atMs
        var first = Long.MAX_VALUE
        if (!rebased && age in REBASE_MIN_MS_8076..REBASE_MAX_MS_8076)
            first = minOf(first, atMs + REBASE_MAX_MS_8076)
        if (!done15 && horizonOpen7809(age, H15_MS_7731))
            first = minOf(first, atMs + H15_MS_7731 + graceFor7946(H15_MS_7731))
        if (!done60 && horizonOpen7809(age, H60_MS_7731))
            first = minOf(first, atMs + H60_MS_7731 + graceFor7946(H60_MS_7731))
        val hourTarget = if (targetHorizonMs > H60_MS_7731) targetHorizonMs else H240_MS_7731
        if (!done240 && horizonOpen7809(age, hourTarget))
            first = minOf(first, atMs + hourTarget + graceFor7946(hourTarget))
        return first.takeIf { it != Long.MAX_VALUE }
    }

    /** Pure: [open.second] is its nearest DEADLINE, not the age of its oldest decision. */
    fun hpDueMints8583(open: List<Pair<String, Long>>, lastRead: (String) -> Long?, nowMs: Long, cap: Int = HP_MAX_MINTS_8583): List<String> =
        open.sortedBy { it.second }.map { it.first }.distinct()
            .filter { m -> (lastRead(m) ?: 0L).let { nowMs - it >= HP_READ_GAP_MS_8583 } }.take(cap)

    private fun startHorizonPricer8583() {
        if (!hpRunning8583.compareAndSet(false, true)) return
        try {
            Thread({
                while (true) {
                    try { horizonPass8583(System.currentTimeMillis()) } catch (_: Throwable) {}
                    try { Thread.sleep(HP_TICK_MS_8583) } catch (_: InterruptedException) { break }
                }
                hpRunning8583.set(false)
            }, "label-horizon-8583").apply { isDaemon = true }.start()
        } catch (_: Throwable) { hpRunning8583.set(false) }
    }

    private fun horizonPass8583(nowMs: Long) {
        val open = ArrayList<Pair<String, Long>>()
        for (o in pending.values) {
            val deadline = horizonDeadline8650(o.atMs, nowMs, o.rebased8076, o.done15, o.done60, o.done240, o.targetHorizon8521)
            if (deadline != null) open += o.mint to deadline
        }
        val due = hpDueMints8583(open, { hpLastRead8583[it] }, nowMs)
        if (hpLastRead8583.size > 8_000) hpLastRead8583.entries.removeIf { nowMs - it.value > 2 * H240_MS_7731 }
        if (due.isEmpty()) return
        hpPasses8583.incrementAndGet(); hpAsked8583.addAndGet(due.size.toLong())
        for (m in due) hpLastRead8583[m] = nowMs
        val got = HashMap<String, Double>()
        fun keep(prices: Map<String, Double>, counter: AtomicLong) {
            var n = 0
            for ((m, px) in prices) if (m !in got && px.isFinite() && px > 0.0) { got[m] = px; n++ }
            counter.addAndGet(n.toLong())
            if (prices.isNotEmpty()) noteOffWatchResponse(prices.filterValues { it.isFinite() && it > 0.0 }, System.currentTimeMillis())
        }
        val (onCurve, _) = splitOnCurve8418(due) { m -> onActiveCurve8418(m) }
        if (onCurve.isNotEmpty()) keep(try { com.lifecyclebot.network.ParallelMarkFanout7088.curvePrices7392(onCurve) } catch (_: Throwable) { emptyMap() }, hpCurve8583)
        for (chunk in due.filter { it !in got }.chunked(OFFWATCH_DEX_CHUNK_8405)) {
            val r = try { com.lifecyclebot.network.DexscreenerApi().batchPriceFetchTyped8087(chunk, LABEL_SLOT_WAIT_MS_8474, heldMarkTraffic = false) } catch (_: Throwable) { null }
            if (r != null) keep(r.prices, hpDex8583)
        }
        for (chunk in due.filter { it !in got }.chunked(50))
            keep(try { com.lifecyclebot.engine.sell.PriceResolverFallback.jupiterBatchPrices7737(chunk) } catch (_: Throwable) { emptyMap() }, hpJup8583)
        val rest = due.filter { it !in got }
        if (rest.isNotEmpty()) {
            val chain = HashMap<String, Double>()
            for (m in rest) {
                val px = try { OnChainDepth8038.chainPriceUsd8540(m, CHAIN_MARK_MAX_AGE_MS_8540) } catch (_: Throwable) { Double.NaN }
                if (px.isFinite() && px > 0.0) chain[m] = px
            }
            keep(chain, hpChain8583)
            val ask = rest.filter { it !in got }
            if (ask.isNotEmpty()) try { OnChainDepth8038.requestMints8543(ask, CHAIN_MARK_MAX_AGE_MS_8540) } catch (_: Throwable) {}
            hpUnpriced8583.addAndGet(ask.size.toLong())
            // an unpriced mint is retried at the next gap inside its window (the vault read requested above usually lands first)
        }
    }

    fun horizonPricerLine8583(): String =
        "horizonPricer8583[running=${hpRunning8583.get()} passes=${hpPasses8583.get()} asked=${hpAsked8583.get()} " +
            "priced{curve=${hpCurve8583.get()} dex=${hpDex8583.get()} jup=${hpJup8583.get()} chain=${hpChain8583.get()}} unpriced=${hpUnpriced8583.get()}]"

    private fun fetchOffWatchMarks7737(due: List<String>, nowMs: Long) {
        if (due.isEmpty() || nowMs - offWatchLastFetchMs7737 < OFFWATCH_FETCH_GAP_MS_7737) return
        if (!offWatchInFlight7737.compareAndSet(false, true)) {
            if (offWatchStartedAt8508 <= 0L || nowMs - offWatchStartedAt8508 < OFFWATCH_STALL_MS_8508) return
            if (offWatchRunning8508.get() >= OFFWATCH_MAX_RUNNING_8508) return   // bounded: never a pile of stalled threads
            offWatchStallReleased8508.incrementAndGet()   // V5.0.8508 — the stalled batch keeps running; it no longer owns the slot
            try { PipelineHealthCollector.labelInc("FORWARD_LABEL_OFFWATCH_STALL_RELEASED_8508") } catch (_: Throwable) {}
        }
        val gen8508 = offWatchGen8508.incrementAndGet()
        offWatchRunning8508.incrementAndGet()
        offWatchStartedAt8508 = nowMs
        offWatchLastFetchMs7737 = nowMs
        val batch = due.distinct().take(OFFWATCH_BATCH_7737)
        for (m in batch) offWatchAttemptAt7809[m] = nowMs
        try {
            Thread({
                try {
                    // V5.0.8405 — rungs by measured health: DexScreener first over the whole batch (30 per call), the curve
                    // account for pump mints it did not list, Jupiter last (free tier 429s; it also misses most young tokens,
                    // 5.0.7749 priced=1517 missed=7233). Every rung is a bounded batch call on this background thread.
                    val got = HashMap<String, Double>()
                    // V5.0.8418 (review 2b46b8833) — a pump.fun mint still on its curve is priced from the curve account first
                    // (chain-exact; DexScreener's curve price can lag); only the curve's misses and every other mint go to
                    // DexScreener. Same rungs, same bounded batches; one curve call per batch.
                    val (onCurve8418, offCurve8418) = splitOnCurve8418(batch) { m -> onActiveCurve8418(m) }
                    // V5.0.8572 — every off-curve mint's own pool vaults are asked for NOW, on the depth reader's thread (batched
                    // account reads). The read lands as a typed chain mark (typedMark8386) while this batch is still walking the
                    // DexScreener / Jupiter rungs (avg 22 s, max 309 s in 8562), so the 1 / 5 / 60-minute windows stop being
                    // missed while the batch waits on providers.
                    if (offCurve8418.isNotEmpty()) try { OnChainDepth8038.requestMints8543(offCurve8418, CHAIN_MARK_MAX_AGE_MS_8540) } catch (_: Throwable) {}
                    if (onCurve8418.isNotEmpty()) {
                        val curve8418 = try { com.lifecyclebot.network.ParallelMarkFanout7088.curvePrices7392(onCurve8418) } catch (_: Throwable) { emptyMap() }
                        val priced8418 = curve8418.filter { it.value.isFinite() && it.value > 0.0 }
                        got.putAll(priced8418)
                        offWatchCurvePriced7753.addAndGet(priced8418.size.toLong())
                        if (priced8418.isNotEmpty()) PipelineHealthCollector.labelAdd("FORWARD_LABEL_OFFWATCH_CURVE_FIRST_8418", priced8418.size.toLong())
                    }
                    val dexBatch8418 = offCurve8418 + onCurve8418.filter { it !in got }
                    val limiterRetry8474 = ArrayList<String>()
                    for (chunk in dexBatch8418.chunked(OFFWATCH_DEX_CHUNK_8405)) {
                        // V5.0.8418 — a due label mint the pair lane was just asked for is not asked twice on the same host.
                        // A curve RPC can outlive the request lease. Test coverage at this actual rung, not batch start.
                        val leaseAt8527 = System.currentTimeMillis()
                        val ask8418 = chunk.filter { m -> !(try { KeylessMarks8359.laneCovered8418(m, leaseAt8527) } catch (_: Throwable) { false }) }
                        if (ask8418.isEmpty()) continue
                        // V5.0.8474 — this call waited 0 ms for the shared DexScreener limiter slot, so whenever any other caller
                        // held it the batch came back EMPTY and all 30 coins were counted missed (8471: offWatch missed=8381
                        // against priced=7666; labels lost noSource=1519). The held-mark batch waits 200 ms; this background
                        // thread waits [LABEL_SLOT_WAIT_MS_8474], and a batch still refused is re-asked next pass, not written off.
                        val r8474 = try {
                            com.lifecyclebot.network.DexscreenerApi().batchPriceFetchTyped8087(ask8418, LABEL_SLOT_WAIT_MS_8474, heldMarkTraffic = false)
                        } catch (_: Throwable) { null }
                        if (r8474 == null || r8474.cause == com.lifecyclebot.network.DexscreenerApi.BatchCause8087.LIMITER_REFUSED) {
                            // V5.0.8535 §P0-01 — the cause travels with the mint: our refused slot is a retry, a failed call is UNAVAILABLE.
                            for (m in ask8418) noteCause8535(m, OffWatchSchedule8535.dexCause(refused = r8474 != null, answered = false, listed = false))
                            limiterRetry8474.addAll(ask8418)
                            continue
                        }
                        val dex8405 = r8474.prices.filter { it.value.isFinite() && it.value > 0.0 }
                        val answered8535 = r8474.cause != com.lifecyclebot.network.DexscreenerApi.BatchCause8087.PROVIDER_FAILED &&
                            r8474.cause != com.lifecyclebot.network.DexscreenerApi.BatchCause8087.UNPARSEABLE
                        for (m in ask8418) noteCause8535(m, OffWatchSchedule8535.dexCause(refused = false, answered = answered8535, listed = m in dex8405))
                        // Dex returns a snapshot without a trade timestamp. Its receipt time belongs to this response.
                        noteOffWatchResponse(dex8405)
                        got.putAll(dex8405)
                        if (dex8405.isNotEmpty()) PipelineHealthCollector.labelAdd("FORWARD_LABEL_OFFWATCH_DEX_PRICED_8217", dex8405.size.toLong())
                    }
                    val missed7753 = offCurve8418.filter { it !in got }   // V5.0.8418 — on-curve mints were curve-read already
                    if (missed7753.isNotEmpty()) {
                        val curve7753 = try { com.lifecyclebot.network.ParallelMarkFanout7088.curvePrices7392(missed7753) } catch (_: Throwable) { emptyMap() }
                        got.putAll(curve7753)
                        offWatchCurvePriced7753.addAndGet(curve7753.size.toLong())
                    }
                    // V5.0.8535 §P0-01 — the Jupiter rung covers every miss of the batch (bounded to JUP_MAX_PER_BATCH_8535 in
                    // chunks of 50), not the first 50 only; a chunk that returns nothing leaves UNAVAILABLE on its mints.
                    val missedJup8405 = batch.filter { it !in got }.take(JUP_MAX_PER_BATCH_8535)
                    for (jupChunk8535 in missedJup8405.chunked(50)) {
                        val jup = try { com.lifecyclebot.engine.sell.PriceResolverFallback.jupiterBatchPrices7737(jupChunk8535) } catch (_: Throwable) { emptyMap() }
                        noteOffWatchResponse(jup)
                        got.putAll(jup)
                        for (m in jupChunk8535) noteCause8535(m, when {
                            m in jup -> OffWatchSchedule8535.Cause.PRICED
                            jup.isEmpty() -> OffWatchSchedule8535.Cause.UNAVAILABLE
                            else -> OffWatchSchedule8535.Cause.NOT_LISTED
                        })
                        if (jup.isNotEmpty()) PipelineHealthCollector.labelAdd("FORWARD_LABEL_OFFWATCH_JUP_PRICED_8405", jup.size.toLong())
                    }
                    // V5.0.8540 — the chain rung: every mint still unpriced is read from its own pool's vaults (no TokenState
                    // needed). A fresh read prices it now; otherwise a read is requested and the mint is retried next pass,
                    // so a provider gap is no longer a lost label (8539: 256 of 491 due marks unpriced, missed 2,867).
                    val missedChain8540 = batch.filter { it !in got }
                    if (missedChain8540.isNotEmpty()) {
                        val chain8540 = HashMap<String, Double>()
                        val ask8543 = ArrayList<String>()
                        for (m in missedChain8540) {
                            val px = try { OnChainDepth8038.chainPriceUsd8540(m, CHAIN_MARK_MAX_AGE_MS_8540) } catch (_: Throwable) { Double.NaN }
                            if (px.isFinite() && px > 0.0) chain8540[m] = px
                            else {
                                ask8543 += m
                                offWatchAttemptAt7809.remove(m)   // retried on the next pass, when the vault read has landed
                                chainRequested8540.incrementAndGet()
                            }
                        }
                        // V5.0.8543 — pool discovery (token register, PDA derivation) runs on the depth reader's own thread, never on
                        // this batch: 8542 batches averaged 8.2 s (max 287 s; 3.5 s / 21 s before) and 1,607 marks landed past their horizon.
                        if (ask8543.isNotEmpty()) try { OnChainDepth8038.requestMints8543(ask8543, CHAIN_MARK_MAX_AGE_MS_8540) } catch (_: Throwable) {}
                        got.putAll(chain8540)
                        offWatchChainPriced8540.addAndGet(chain8540.size.toLong())
                        if (chain8540.isNotEmpty()) PipelineHealthCollector.labelAdd("FORWARD_LABEL_OFFWATCH_CHAIN_PRICED_8540", chain8540.size.toLong())
                    }
                    for (m in got.keys) noteCause8535(m, OffWatchSchedule8535.Cause.PRICED)
                    val at = System.currentTimeMillis()
                    // Curve prices are already available through activeExitObservation, with the account observation time.
                    // The end of a later fallback must never refresh earlier marks.
                    // V5.0.8474 — a coin our own limiter refused (and nothing else priced) is retried on the next pass.
                    val retry8474 = limiterRetry8474.filter { it !in got }
                    for (m in retry8474) offWatchAttemptAt7809.remove(m)
                    offWatchLimiterRetry8474.addAndGet(retry8474.size.toLong())
                    offWatchPriced7737.addAndGet(got.size.toLong())
                    offWatchMissed7737.addAndGet((batch.size - got.size - retry8474.size).coerceAtLeast(0).toLong())
                    PipelineHealthCollector.labelInc("FORWARD_LABEL_OFFWATCH_BATCH_7737")
                    val cutoff = at - MARK_MAX_AGE_MS_7731
                    offWatchMarks7737.entries.removeIf { it.value.second < cutoff }
                } catch (_: Throwable) {
                } finally {
                    val ms8508 = (System.currentTimeMillis() - nowMs).coerceAtLeast(0L)
                    offWatchBatches8508.incrementAndGet(); offWatchBatchMsSum8508.addAndGet(ms8508)
                    if (ms8508 > offWatchBatchMsMax8508) offWatchBatchMsMax8508 = ms8508
                    offWatchRunning8508.decrementAndGet()
                    if (offWatchGen8508.get() == gen8508) offWatchInFlight7737.set(false)
                }
            }, "fwd-label-offwatch-7737").apply { isDaemon = true }.start()
        } catch (_: Throwable) {
            offWatchRunning8508.decrementAndGet()
            if (offWatchGen8508.get() == gen8508) offWatchInFlight7737.set(false)
        }
    }

    private const val TERMINAL_PREFIX_8395 = "terminal8395|"
    private const val FORWARD_PREFIX_8404 = "forward8527|"

    /** Old durable synthetic outcomes remain archived, never renamed into the current delivery queue. */
    internal fun forwardPayloads8527(rows: Map<String, String>): Map<String, String> = rows.mapNotNull { (key, value) ->
        if (key.startsWith(FORWARD_PREFIX_8404)) key.removePrefix(FORWARD_PREFIX_8404) to value else null
    }.toMap()

    // ── V5.0.8409 §ROWS_DO_NOT_LIVE_IN_THE_CHECKPOINT_FILE ───────────────────────────────────────────────────────────────────
    // Every forward outcome write, forward acknowledgement and terminal write was a synchronous commit() on the one preferences
    // XML that also carries the pending checkpoint (~3,200 rows), the cells and the paired Cortex restore: one row = one full
    // rewrite of a multi-MiB file, 64+ times per drain. The rows now live as individually addressable SQLite rows in the
    // transactional store the shadow books already use (ShadowColdStore8397, namespace below); a write or an acknowledgement
    // is one row transaction. Durable acknowledgement is unchanged in meaning: the consumer's update and receipt commit first,
    // then the producer row is deleted. Rows already in the preferences file migrate once (batch write, then one removal
    // commit); a migration interrupted before the removal simply repeats (INSERT OR REPLACE), losing and duplicating nothing.
    private const val ROWS_NAMESPACE_8409 = "labeler_rows_8409"
    @Volatile private var rowStore8409: ShadowColdStore8397? = null
    private val rowStoreFailures8409 = AtomicLong(0)
    private val rowsMigrated8409 = AtomicLong(0)

    private fun openRowStore8409(context: Context, p: SharedPreferences) {
        val store = try { ShadowColdStore8397.open(context, ROWS_NAMESPACE_8409) } catch (_: Throwable) { null } ?: return
        rowStore8409 = store
        // migrate the row entries the preferences file still holds
        val legacy = p.all.mapNotNull { (k, v) -> if ((k.startsWith(TERMINAL_PREFIX_8395) || k.startsWith(FORWARD_PREFIX_8404)) && v is String) k to v else null }
        if (legacy.isNotEmpty() && store.write(legacy.toMap())) {
            val e = p.edit(); legacy.forEach { e.remove(it.first) }
            if (e.commit()) rowsMigrated8409.addAndGet(legacy.size.toLong())
        }
    }

    /** One row written durably (true) or not (false, counted). The preferences file is the fallback only while the store is absent. */
    private fun putRow8409(key: String, value: String): Boolean {
        val store = rowStore8409
        val ok = if (store != null) store.write(mapOf(key to value))
        else try { prefs?.edit()?.putString(key, value)?.commit() == true } catch (_: Exception) { false }
        if (!ok) rowStoreFailures8409.incrementAndGet()
        return ok
    }

    private fun removeRows8409(keys: Collection<String>): Boolean {
        if (keys.isEmpty()) return true
        val store = rowStore8409
        val ok = if (store != null) store.write(keys.associateWith { null })
        else try { val e = prefs?.edit() ?: return false; keys.forEach { e.remove(it) }; e.commit() } catch (_: Exception) { false }
        if (!ok) rowStoreFailures8409.incrementAndGet()
        return ok
    }

    private fun loadRows8409(p: SharedPreferences): Map<String, String> {
        val out = HashMap<String, String>()
        p.all.forEach { (k, v) -> if ((k.startsWith(TERMINAL_PREFIX_8395) || k.startsWith(FORWARD_PREFIX_8404)) && v is String) out[k] = v }
        rowStore8409?.let { st -> try { st.entries().forEach { (k, v) -> out[k] = v } } catch (_: Throwable) { rowStoreFailures8409.incrementAndGet() } }
        return out
    }

    private fun rowStoreLine8409(): String = "rows8409[store=${if (rowStore8409 != null) "sqlite" else "prefs"} migrated=${rowsMigrated8409.get()} failures=${rowStoreFailures8409.get()}]"
    // One first value, one immutable hour target, and at most one coalesced later checkpoint per observation.
    private val forwardOutbox8404 = com.lifecyclebot.engine.cortex.ForwardOutcomeOutbox8404(MAX_PENDING_7731 * 3)

    private fun writeForward8404(id: String, raw: String): Boolean =
        putRow8409(FORWARD_PREFIX_8404 + id, raw)   // V5.0.8409 — one row transaction, not a checkpoint-file rewrite

    /** Canonical target duration and actual source timestamp are distinct: a read at 60m+5s belongs to the hour. */
    internal fun revisionHorizon8521(observedAgeMs: Long): Long =
        CK_MS_7997.lastOrNull { horizonOpen7809(observedAgeMs, it) } ?: observedAgeMs.coerceAtLeast(1L)

    private fun stageForwardOutcome8404(o: Obs, net: Double, gross: Double, family: DoubleArray,
                                       flat: Boolean, witness: Boolean, revision: Boolean = false): com.lifecyclebot.engine.cortex.ForwardOutcomeDelivery8404? {
        val proof = (if (!revision) o.stagedProof8521 else null) ?: outcomeProof8395(o, net, gross, if (revision)
            revisionHorizon8521((o.lastMark8386?.observedAtMs ?: o.atMs) - o.atMs) else H60_MS_7731)
        if (revision && proof == null) return null
        val episode = "${o.mint}|${o.lane}|${o.atMs}"
        if (revision && proof?.horizonMs == o.targetHorizon8521 && o.targetHorizon8521 > H60_MS_7731) {   // V5.0.8592 — coalesce at the hour target
            forwardOutbox8404.firstRevisionForHorizon8521(episode, o.targetHorizon8521)?.let { (firstId, firstRaw) ->
                return forwardOutbox8404.stagePayload(firstId, { firstRaw }, ::writeForward8404)
                    ?.let { com.lifecyclebot.engine.cortex.ForwardOutcomeDelivery8404.decode(it) }
            }
        }
        val id = if (revision) "$episode|R|${proof?.observedAtMs}|${proof?.horizonMs}" else episode
        if (revision) try {
            val dropped = forwardOutbox8404.supersedeRevisions8418("$episode|R|", id, protectedHorizonMs = o.targetHorizon8521) { old ->   // V5.0.8592 — protect the hour-target grade
                removeRows8409(listOf(FORWARD_PREFIX_8404 + old)) }
            if (dropped > 0) PipelineHealthCollector.labelInc("FORWARD_REVISION_COALESCED_8418")
        } catch (_: Exception) {}
        val staged = try {
            forwardOutbox8404.stagePayload(id, {
                com.lifecyclebot.engine.cortex.Cortex7885.freezeForwardOutcome8404(o.mint, o.lane, o.atMs)?.let { frozen ->
                    com.lifecyclebot.engine.cortex.ForwardOutcomeDelivery8404(o.mint, o.lane, o.atMs, net, gross,
                        family.copyOf(), flat, witness, proof, frozen, revision,
                        if (revision && o.bookedNet7997.isFinite()) o.bookedNet7997 else net,
                        if (revision && o.bookedGross7997.isFinite()) o.bookedGross7997 else gross).encode()
                }
            }, ::writeForward8404)
        } catch (_: Exception) { null }
        if (staged == null) PipelineHealthCollector.labelInc("FORWARD_SUCCESS_OUTBOX_WAIT_8404")
        return staged?.let { com.lifecyclebot.engine.cortex.ForwardOutcomeDelivery8404.decode(it) }
    }

    private fun stageForwardRevision8404(o: Obs, net: Double, gross: Double, nowMs: Long): Boolean {
        // Each due horizon carries evidence even when its return is unchanged. In hour-graded lanes this may be
        // the first model grade; the five-minute observation never made a contribution to revise.
        if (!net.isFinite()) return true
        val firstHour = o.targetHorizon8521 > H60_MS_7731 &&   // V5.0.8592 — stage the first grade at the per-obs hour target (15 m / 60 m)
            horizonOpen7809((o.lastMark8386?.observedAtMs ?: 0L) - o.atMs, o.targetHorizon8521)
        if (!firstHour || o.bookedNet7997.isFinite()) return true
        return stageForwardOutcome8404(o, net, gross, doubleArrayOf(), false,
            gross >= RUNNER_PCT_7731 && witnessNow8324(o, nowMs), revision = true) != null
    }

    private fun drainForwardOutcomes8404() {
        forwardOutbox8404.drain(32, ::writeForward8404, { raw ->
            com.lifecyclebot.engine.cortex.Cortex7885.deliverForwardOutcome8404(
                com.lifecyclebot.engine.cortex.ForwardOutcomeDelivery8404.decode(raw))
        }, { id ->
            removeRows8409(listOf(FORWARD_PREFIX_8404 + id)).also { removed ->   // V5.0.8409
                if (removed) PipelineHealthCollector.labelInc("FORWARD_SUCCESS_DURABLE_ACK_8404")
            }
        })
        if (forwardOutbox8404.size() > 0) com.lifecyclebot.engine.cortex.Cortex7885.flushShadowOutcomes8372()
    }
    private val terminalOutbox8395 = java.util.concurrent.ConcurrentHashMap<String,String>()
    private val deliveredTerminals8395 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val terminalLock8395 = Any()
    private fun terminalKey8395(o: Obs) = "$TERMINAL_PREFIX_8395${o.mint}|${o.lane}|${o.atMs}"

    /** Input is durably owned before the observation releases capacity. No return is fabricated. */
    private fun unavailable8395(key: String, o: Obs, why: String, nowMs: Long, downtime8505: Boolean = false): Boolean {
        val id = terminalKey8395(o)
        val durable = synchronized(terminalLock8395) {
            if (terminalOutbox8395.containsKey(id)) true
            else if (terminalOutbox8395.size >= MAX_PENDING_7731) { terminalCapacityRefusals8401.incrementAndGet(); false }
            else {
                val cause8535 = (lastCause8535[o.mint] ?: OffWatchSchedule8535.Cause.NO_SOURCE).name   // V5.0.8535 §P0-01
                lostByCause8535.computeIfAbsent(cause8535) { AtomicLong() }.incrementAndGet()
                try { com.lifecyclebot.engine.DecisionEpisode8535.noteLabel8535(o.lane, o.mint, "TERMINAL:$why:$cause8535", nowMs) } catch (_: Throwable) {}   // V5.0.8535 §P2-01
                val value=org.json.JSONObject().put("mint",o.mint).put("lane",o.lane).put("at",o.atMs).put("why",why)
                    .put("terminalAt",nowMs).put("entry",o.basis8386.json()).put("last",o.lastMark8386?.json())
                    .put("cause8535", cause8535)
                    .put("downtime8535", if (downtime8505) (DowntimeRecovery8430.receipt8535(o.mint) ?: "NO_RECEIPT") else "-").toString()
                val committed = putRow8409(id, value)   // V5.0.8409
                if (committed) terminalOutbox8395[id]=value else terminalStorageFailures8401.incrementAndGet()
                committed
            }
        }
        if (!durable) { try { PipelineHealthCollector.labelInc("FORWARD_TERMINAL_DURABILITY_WAIT_8395") } catch (_: Throwable) {}; return false }
        // V5.0.8571 — an hour-target lane's (MOONSHOT, SHITCOIN, ... targetHorizon8521 > 5 m) 5-minute read is interim, so its
        // done60 is set without a booking. Losing the HOUR target then released nothing: meme lanes read lost=0 while their
        // labels died. The target itself is lost when it was never booked (done240 false); a 5-minute lane's later follow-up
        // read is not its target and stays uncounted, exactly as before.
        val hourTargetLost8571 = o.targetHorizon8521 > H60_MS_7731 && !o.done240
        val released8505 = pending.remove(key,o) && (!o.done60 || hourTargetLost8571)
        if (released8505 && hourTargetLost8571 && o.done60) hourTargetLost8571Count.incrementAndGet()
        if (released8505 && downtime8505) {
            // V5.0.8505 §DOWNTIME_WINDOW_IS_NOT_A_LOST_MARK — the window closed while the process was down and its candle could
            // not be fetched: nothing observed it, so it is no evidence about pricing or the coin. Not a label, not a lost mark
            // (8504: three installs in one evening read as 77% of labels lost). Counted on its own.
            labelLostDowntime8505.incrementAndGet()
            try { PipelineHealthCollector.labelInc("FORWARD_LABEL_DOWNTIME_UNOBSERVED_8505") } catch (_: Throwable) {}
        } else if (released8505) {
            markLost(o)
            // V5.0.8418 — noSource: no source ever priced it; late: marks were seen, none accepted inside the window.
            val late8474 = com.lifecyclebot.engine.cortex.CortexLabelFeed8291.lostLate8418(o.lateMark8418, o.lastAcceptedMark8395 != null, o.lastMark8386 != null)
            if (late8474) labelLostLate8418.incrementAndGet() else labelLostNoSource8418.incrementAndGet()
            // V5.0.8474 — the gap map: which kind of coin loses its label, and how.
            noteLostVenue8474(o.mint, late8474)
        }
        return true
    }

    private val labelLostDowntime8505 = AtomicLong(0)
    /** V5.0.8571 — hour-target labels lost at the hour (counted in lostMark too); interim 5-minute reads missed by them. */
    private val hourTargetLost8571Count = AtomicLong(0)
    private val interimMissed8571 = AtomicLong(0)
    private val terminalStorageFailures8401 = AtomicLong(0)
    private val terminalCapacityRefusals8401 = AtomicLong(0)
    private var lastTerminalCleanup8401 = 0L

    /** Remove at most one acknowledged batch and its old checkpoint rows atomically, without rebuilding learner books. */
    private fun cleanupTerminals8401() = synchronized(checkpointLock8401) {
        val p = prefs ?: return@synchronized
        // V5.0.8419 — up to TerminalOutbox8419.CLEANUP_BATCH_8419 acknowledged terminals per cleanup (64 every 30 s could not drain
        // a 5,000-terminal outbox: 8418b queued=5015), and the multi-MB pending checkpoint is rewritten only when it holds one.
        val acknowledged = TerminalOutbox8419.cleanupBatch8419(terminalOutbox8395.keys.filter { deliveredTerminals8395.contains(it) })
        if (acknowledged.isEmpty()) return@synchronized
        try {
            val old = com.lifecyclebot.engine.cortex.ForwardCheckpoint8401.unpack(p.getString(PENDING_KEY_7946, null).orEmpty())
            val retained = TerminalOutbox8419.retainedRows8419(old, acknowledged, ROW_SEP_7735, FIELD_SEP_7735, TERMINAL_PREFIX_8395)
            // V5.0.8409 — the checkpoint shrinks in its own commit; the acknowledged rows leave in one store transaction. If the
            // second fails the rows stay queued and acknowledged in memory and are retried next cleanup (receipts are idempotent).
            val checkpointOk8419 = retained == null || p.edit().putString(PENDING_KEY_7946, packedPending8521(retained)).commit()
            if (checkpointOk8419 && removeRows8409(acknowledged))
                acknowledged.forEach { terminalOutbox8395.remove(it); deliveredTerminals8395.remove(it) }
            else terminalStorageFailures8401.incrementAndGet()
        } catch (_: Exception) { terminalStorageFailures8401.incrementAndGet() }
    }

    internal const val TERMINAL_DRAIN_BATCH_8445 = 192
    private var terminalCursor8403 = ""
    private fun terminalBatch8401(): List<Map.Entry<String, String>> = synchronized(terminalLock8395) {
        // Staged is not acknowledged. Rotate past staged records too, otherwise the first 64
        // keep a consumer waiting for its 256-record checkpoint while later records never arrive.
        val waiting = terminalOutbox8395.entries.filterNot { deliveredTerminals8395.contains(it.key) }.sortedBy { it.key }
        val after = waiting.indexOfFirst { it.key > terminalCursor8403 }.takeIf { it >= 0 } ?: 0
        // superseded by V5.0.8445 — 192 a tick (was 64): each terminal now acknowledges on its own receipt row, and 64 a
        // tick at the loop's pace took ~37 min to revisit a 6,000-terminal outbox (8439: queued 5947, oldest 3.7 h).
        val batch = (waiting.drop(after) + waiting.take(after)).take(TERMINAL_DRAIN_BATCH_8445)
        batch.lastOrNull()?.let { terminalCursor8403 = it.key }
        batch
    }

    private fun drainTerminals8395() {
        // V5.0.8451 — the 8449 one-call batch is withdrawn: 8449's runtime run raised Cortex lock waits to mean 500 ms / max
        // 5.7 s and ANR'd MainActivity (8448, per-terminal, passed). Each terminal is again its own durable call.
        for ((key,raw) in terminalBatch8401()) {
            try {
                val j=org.json.JSONObject(raw)
                if (com.lifecyclebot.engine.cortex.Cortex7885.observationUnavailable8395(j.getString("mint"),j.getString("lane"),j.getLong("at"),j.getString("why")))
                    deliveredTerminals8395.add(key)
            } catch (_: Exception) { /* retained durable outbox, never treated as delivered */ }
        }
        if (terminalOutbox8395.isNotEmpty()) try { com.lifecyclebot.engine.cortex.Cortex7885.flushShadowOutcomes8372() } catch (_: Exception) {}
        // Independent of successful label booking and of the observation queue becoming empty.
        val now = System.currentTimeMillis()
        // V5.0.8419 — a full batch of acknowledgements does not wait for the 30 s cadence.
        if (deliveredTerminals8395.isNotEmpty() && (now - lastTerminalCleanup8401 >= 30_000L || deliveredTerminals8395.size >= TerminalOutbox8419.CLEANUP_BATCH_8419)) {
            lastTerminalCleanup8401 = now
            cleanupTerminals8401()
        }
    }

    private fun deadline8395(o: Obs): Long = o.atMs + when {
        !o.done60 -> H60_MS_7731 + graceFor7946(H60_MS_7731)
        !o.done240 -> H240_MS_7731 + graceFor7946(H240_MS_7731)
        else -> CK_MS_7997.last() + LOST_GRACE_MS_7731
    }

    /**
     * Recovery finishes a horizon, not the original observation. Keep its identity and basis for the next
     * checkpoint. The hour uses the same tokenomics/exit-deferral decision as an on-time hour completion.
     */
    private fun recoveredHorizon8397(key: String, o: Obs, recovered: LabelMark8386, nowMs: Long) {
        if (!entryFill8521(o)) {
            // A historical horizon cannot supply the earlier missing entry. Keep it observed-only through delivery/restart.
            o.rebased8076 = true
            if (!o.lateEntry8521) {
                o.lateEntry8521 = true
                noteLateFirstPrice8153(o.mint, o.lane, o.atMs, nowMs)
            }
        }
        val gross = (recovered.usd / o.entryPrice - 1.0) * 100.0
        if (recovered.observedAtMs > o.lastPxAtMs) {
            if (gross < o.minPct7962) { notePathLow8366(o, recovered.observedAtMs, gross); o.minPct7962 = gross }
            if (gross > o.peakPct) { o.peakPct = gross; o.peakAtMs7955 = recovered.observedAtMs; o.dipBeforePeak7962 = o.minPct7962 }
            synchronized(o.recent8071) {
                o.heldPeak8071 = heldPeakStep8071(o.recent8071, recovered.observedAtMs, gross, o.heldPeak8071)
                notePathHeld8366(o, recovered.observedAtMs, gross)
            }
        }
        o.lastMark8386 = recovered; o.lastAcceptedMark8395 = recovered
        o.lastPx = recovered.usd; o.lastPxAtMs = recovered.observedAtMs
        val net = netPct(o.entryPrice, recovered.usd, o.costPct).coerceAtMost(NET_CEILING_PCT_7738)
        if (!o.done60) {
            o.done60 = bookSixty7944(o, net, gross, nowMs)
            if (!o.done60) return
            notePriced8418(o)   // V5.0.8418
            // deadline8395 now points to the ORIGINAL decision's hour + grace; it never moves with wall time.
            try { PipelineHealthCollector.labelInc("FORWARD_RECOVERED_FIRST_HORIZON_CONTINUES_8397") } catch (_: Throwable) {}
        } else {
            val stop = checkpoint7997(o, net, gross, recovered.observedAtMs - o.atMs, nowMs)
            completeHour8397(key, o, net, gross, stop, bookable = true)
        }
    }

    private fun completeHour8397(key: String, o: Obs, net: Double, gross: Double, stop: Boolean, bookable: Boolean) {
        val follow = !stop && keepRunning7997(o, gross)
        if (bookable) { if (!bookTwoForty7944(o, net, gross, deferExit8006 = follow)) return }
        else horizonMissed7809.incrementAndGet()
        o.done240 = true
        if (!follow) pending.remove(key, o) else extended7997.incrementAndGet()
    }

    /** Every fresh/invalid/unchanged/rejected mark crosses this deadline before any early continue. */
    private fun expired8395(key: String, o: Obs, incoming: LabelMark8386?, nowMs: Long): Boolean {
        val deadline=deadline8395(o)
        // V5.0.8418 — the learners booked this observation's first value; only the Cortex staging is outstanding. It is never
        // turned into a lost mark (that would count the label twice): retry, and past the grace release the Cortex decision.
        if (!o.done60 && o.staged8418 != null) {
            if (retryCortexStage8418(o)) { o.done60 = true; return false }
            if (!stageAbandoned8418(nowMs, deadline, STAGE_GRACE_MS_8418)) return nowMs >= deadline
            o.staged8418 = null; o.stagedProof8521 = null; o.done60 = true
            try { PipelineHealthCollector.labelInc("FORWARD_CORTEX_STAGE_ABANDONED_8418") } catch (_: Throwable) {}
            if (o.targetHorizon8521 > H60_MS_7731) o.interimUnavailable8521 = true
            else try { com.lifecyclebot.engine.cortex.Cortex7885.onLabelLost8187(o.mint, o.lane, "STAGE", decisionAtMs8371 = o.atMs) } catch (_: Throwable) {}
            return false
        }
        if (nowMs < deadline) return false
        val horizon=if (!o.done60) H60_MS_7731 else H240_MS_7731
        // V5.0.8430 §A_WINDOW_THAT_CLOSED_WHILE_THE_PROCESS_WAS_DOWN — the restart cohort (8422: 968 of 1,881 due reads lost,
        // all of them windows that elapsed while the app was down) is offered the keyless 1-minute candle covering its horizon,
        // through this same filter; it waits only while its bounded fetch is queued inside the boot window.
        val recoverable8435: (LabelMark8386) -> Boolean = {
            it.observedAtMs in (o.atMs+horizon)..deadline && LabelEvidence8386.eligible(o.basis8386.mark,it) &&
                !curveContradicts8165(o.mint,it.usd,nowMs,it.observedAtMs) && !curveHolds8337(o.mint,it.usd,nowMs,it.observedAtMs) &&
                PositionDisplayTruth8078.highStepOk8133(o.mint,o.entryPrice,o.entryPrice*(1.0+o.peakPct/100.0),it.usd,"FORWARD_LABEL") &&
                // V5.0.8180 — a recovered mark the entry cannot share a basis with is no read at all.
                (o.entryPrice <= 0.0 || LabelBasis8180.bookable8180(o.entryPrice, it.usd, o.entryMcap, nowMcap7769(o.mint),
                    try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }))
        }
        // The accepted in-window source comes first. If none exists, the same bounded recovery is offered to losses
        // and winners: an earlier collapse is not the horizon price and cannot suppress a genuine later observation.
        var recovered=listOfNotNull(o.lastAcceptedMark8395,incoming).filter(recoverable8435).maxByOrNull { it.observedAtMs }
        if (recovered == null && !o.done60 && o.targetHorizon8521 > H60_MS_7731) {
            // The five-minute read is an interim observation for this decision. Losing it cannot cancel a promised
            // one-hour target or manufacture a missing hour outcome fifty-two minutes before that outcome is due.
            o.done60 = true; o.interimUnavailable8521 = true
            try { PipelineHealthCollector.labelInc("FORWARD_INTERIM_MISSING_AWAITS_HOUR_8521") } catch (_: Throwable) {}
            interimMissed8571.incrementAndGet()   // V5.0.8571 — shown beside labelMarks (due = priced + lost + interim + late)
            return if (nowMs >= deadline8395(o)) expired8395(key, o, incoming, nowMs) else false
        }
        // V5.0.8541 — the pool's own swap history prices a window that closed while the app was down (vault balances after
        // the first swap inside the window, read on chain). It goes through the SAME recovery filter; the candle path below
        // still runs for anything the chain did not price, and the label waits only while either read is queued.
        var chainWaiting8541 = false
        if (recovered == null && !o.done240) {
            val chain8541 = ChainHistoryMark8541.read8541(o.mint, o.atMs + horizon, deadline, bootMs8430, nowMs)
            chainWaiting8541 = chain8541.waiting
            recovered = chain8541.mark?.takeIf(recoverable8435)
        }
        if (recovered == null && !o.done240) {
            val downtime8430 = DowntimeRecovery8430.candleMark8430(o.mint, o.atMs + horizon, o.atMs + horizon, deadline, bootMs8430, nowMs)
            if (downtime8430.waiting) return false
            recovered = downtime8430.mark?.takeIf(recoverable8435)
            if (recovered == null && chainWaiting8541) return false
        }
        if (recovered != null && !o.done240) {
            // valid in-window recovery of the ORIGINAL basis; a later price never becomes the entry
            recoveredHorizon8397(key, o, recovered, nowMs)
            return true
        }
        // A severe last decline is observed risk, but provider silence does not prove a total loss. Retain that actual
        // mark in the unavailable terminal; never manufacture a -100% label or mix a one-minute drop into a five-minute target.
        if (!o.done240) {
            val horizonMs=if (!o.done60) H60_MS_7731 else H240_MS_7731
            val lastAt=o.lastPxAtMs.takeIf { it in (o.atMs+1)..deadline } ?: 0L
            val lastGross=if (lastAt > 0L && o.lastPx > 0.0 && o.entryPrice > 0.0) (o.lastPx/o.entryPrice-1.0)*100.0 else Double.NaN
            if (classifyVanished7944(lastGross, if (lastAt > 0L) lastAt-o.atMs else 0L, horizonMs) == Vanished7944.DEAD) {
                vanishedDead7944.incrementAndGet()
                try { PipelineHealthCollector.labelInc("FORWARD_LABEL_VANISHED_DEAD_7944") } catch (_: Throwable) {}
                unavailable8395(key, o, "OBSERVATION_DEADLINE_AFTER_OBSERVED_COLLAPSE_8521", nowMs,
                    DowntimeRecovery8430.downtime8430(deadline, bootMs8430))
                return true
            }
        }
        if (o.done240 && o.lastPx > 0.0 && o.entryPrice > 0.0) endRun8006(o, (o.lastPx / o.entryPrice - 1.0) * 100.0)
        unavailable8395(key,o,"OBSERVATION_DEADLINE_${LabelEvidence8386.status(o.basis8386.mark,incoming)}",nowMs,
            DowntimeRecovery8430.downtime8430(deadline, bootMs8430))   // V5.0.8505
        return true
    }

    /**
     * V5.0.8442 — two drivers, one tick at a time. The bot loop's learning tick still calls this; [LabelClock8442] now also
     * calls it every few seconds, so a label's 5-minute read no longer waits on the trading loop. 8439 (3.2 h, loop at 24 s
     * a cycle): label marks due 9657, priced 1254 — the off-watch batch is only asked from inside a tick, and ran ~75 times.
     */
    private val ticking8442 = java.util.concurrent.atomic.AtomicBoolean(false)

    fun tick(priceFor0: (String) -> Double?, nowMs: Long = System.currentTimeMillis()) {
        if (!ticking8442.compareAndSet(false, true)) {
            try { PipelineHealthCollector.labelInc("FORWARD_TICK_OVERLAP_SKIPPED_8442") } catch (_: Throwable) {}
            return
        }
        try { tickOnce8442(priceFor0, nowMs) } finally { ticking8442.set(false) }
    }

    private fun tickOnce8442(priceFor0: (String) -> Double?, nowMs: Long) {
        prunePendingMints8475(nowMs)
        shadowWanted8442.entries.removeIf { it.value <= nowMs }
        drainForwardOutcomes8404()
        pairedRestore8401?.let { raw ->
            // V5.0.8418 §PAIRED_RESTORE_BOUNDED — 0e5412fb7 returned here until the restore succeeded, so one corrupt blob (or a
            // Cortex that never loads) stopped the 1m/5m/15m/30m/1h/4h/24h grading and the labeler's own saves for good. A corrupt
            // blob is dropped at once; a not-yet-loaded Cortex is retried for [PAIRED_RESTORE_MAX_WAIT_MS_8418]; then the blob is
            // dropped, logged and counted, and labelling continues (the affected decisions read ORIGINAL_UNAVAILABLE in Cortex).
            var corrupt8418 = false
            val restored = try { com.lifecyclebot.engine.cortex.Cortex7885.restoreForward8401(raw) } catch (_: Exception) { corrupt8418 = true; false }
            if (!restored) {
                if (pairedRestoreSinceMs8418 == 0L) pairedRestoreSinceMs8418 = nowMs
                if (!pairedRestoreAbandoned8418(corrupt8418, nowMs - pairedRestoreSinceMs8418, PAIRED_RESTORE_MAX_WAIT_MS_8418)) {
                    // The prediction checkpoint keeps ownership, but price collection cannot pause for a five-minute
                    // model restore: that would erase every currently open executable-entry and forward-read window.
                    PipelineHealthCollector.labelInc("FORWARD_PAIRED_RESTORE_RETRY_8401")
                    return@let
                }
                PipelineHealthCollector.labelInc(if (corrupt8418) "FORWARD_PAIRED_RESTORE_DROPPED_CORRUPT_8418" else "FORWARD_PAIRED_RESTORE_DROPPED_TIMEOUT_8418")
                try { com.lifecyclebot.engine.ForensicLogger.lifecycle("FORWARD_PAIRED_RESTORE_DROPPED_8418",
                    "corrupt=$corrupt8418 waitedMs=${nowMs - pairedRestoreSinceMs8418} bytes=${raw.length} action=drop_blob_continue_labelling") } catch (_: Throwable) {}
            }
            pairedRestore8401 = null
            pairedRestoreSinceMs8418 = 0L
        }
        // V5.0.7931 — crypto-universe and Markets identities are priced by their own feeds.
        val priceFor: (String) -> Double? = { m -> priceFor0(m) ?: com.lifecyclebot.engine.cortex.CrossAssetCortex7931.priceFor(m, nowMs) }
        startHorizonPricer8583()   // V5.0.8583 — idempotent; every open label window is priced on its own thread
        val freshUnpriced7737 = try { FreshLaunchSelector7737.tick({ m -> markFor(m, priceFor, nowMs) }, nowMs) } catch (_: Throwable) { emptyList() }
        drainTerminals8395()
        try { DowntimeRecovery8430.drain8430(nowMs) } catch (_: Throwable) {}   // V5.0.8430 — one bounded candle fetch when the provider is idle
        if (pending.isEmpty() && freshUnpriced7737.isEmpty() && shadowWanted8442.isEmpty()) return
        val dueUnpriced7737 = ArrayList<Pair<String, Long>>()
        for ((key, o) in pending.entries.toList()) {
            val age = nowMs - o.atMs
            if (!o.due8418 && !o.done60 && age >= H60_MS_7731) { o.due8418 = true; labelDue8418.incrementAndGet() }   // V5.0.8418
            if (!o.due15_8535 && !o.done15 && age >= H15_MS_7731) { o.due15_8535 = true; labelDue15_8535.incrementAndGet() }   // V5.0.8535
            if (!o.due240_8535 && !o.done240 && age >= H240_MS_7731) { o.due240_8535 = true; labelDue240_8535.incrementAndGet() }
            val observed8386 = typedMark8386(o.mint, priceFor, nowMs)
            // V5.0.8521 — queue before unknown-basis / unchanged-copy / curve rejection returns.
            // Otherwise a continuously re-stamped cached price prevents the real horizon price from ever being fetched.
            if (observed8386 != null) queueIndependentRefresh8521(o, observed8386, nowMs, dueUnpriced7737)
            if (observed8386 == null) gradeTiming8528(o, null, nowMs)
            if (expired8395(key,o,observed8386,nowMs)) continue
            val px = observed8386?.usd
            if (px == null) {
                // V5.0.8187 — an unpriced observation inside its re-base window asks the off-watch batch FIRST: its label can
                // only start from a buyable price if a mark lands inside 60 s (else LATE_FIRST_PRICE: observed only, and its
                // cohort unresolved — the walk-forward's UNRESOLVED_COHORT). Its due time is its decision, so it sorts first.
                // V5.0.8291 — asked in rank order (inside the 5-minute window first), oldest first within a rank (CortexLabelFeed8291).
                val first8291 = firstMarkDue8187(o.rebased8076, o.lastPxAtMs, age)
                // V5.0.8418 — due in a window now: the pair lane and the curve read price it (KeylessMarks8359), in priority after held / shadow.
                val end8418 = labelWindowEnd8418(o, age, first8291)
                if (end8418 > nowMs) { labelWindowEnd8535[o.mint] = end8418; try { KeylessMarks8359.noteLabelDue8418(o.mint, end8418, nowMs) } catch (_: Throwable) {} }
                val rank8291 = labelOffWatchRank8521(o, age, first8291)
                if (first8291) dueUnpriced7737.add(o.mint to com.lifecyclebot.engine.cortex.CortexLabelFeed8291.offWatchKey8291(rank8291, o.atMs - REBASE_MAX_MS_8076))
                else if (dueAtHorizon7737(o, age)) dueUnpriced7737.add(o.mint to com.lifecyclebot.engine.cortex.CortexLabelFeed8291.offWatchKey8291(rank8291,
                    end8418.takeIf { it > nowMs } ?: if (o.done60) o.atMs + H240_MS_7731 else o.atMs))
                // V5.0.8013 — a followed coin's due checkpoint asks for an off-watch mark too.
                else if (checkpointDue8013(o, age)) dueUnpriced7737.add(o.mint to com.lifecyclebot.engine.cortex.CortexLabelFeed8291.offWatchKey8291(rank8291, o.atMs + CK_MS_7997[o.ck7997]))
                // V5.0.8455 — owner: "everything is meant to be tracked from discovery until disposal". Between horizons an
                // unpriced coin was not asked at all, so its path (peak, dip, the stop it hit) went unseen and its label could
                // find no source when it fell due (8450: noSource=160, late=55). Every tracked coin without a current price is
                // asked continuously, at the lowest rank (after every due read), longest-unpriced first.
                else if (trackPath8455(o.lastPxAtMs, o.atMs, nowMs)) dueUnpriced7737.add(o.mint to com.lifecyclebot.engine.cortex.CortexLabelFeed8291.offWatchKey8291(9, maxOf(o.lastPxAtMs, o.atMs)))
                continue
            }
            val mark8386 = observed8386 ?: continue
            if (!o.basis8386.mark.known() && age <= REBASE_MAX_MS_8076 && o.decisionBasis8386.mark.known())
                o.basis8386 = o.decisionBasis8386
            // V5.0.8418 — the decision price had no exactly-matching typed mark (observe: m.usd == px): the first typed mark
            // inside the re-base window becomes the basis (what the re-base would do), instead of a silent UNKNOWN stall.
            if (adoptBasis8418(o.basis8386.mark.known(), o.rebased8076, age, mark8386)) {
                o.basis8386 = LabelBasis8386(mark8386, o.entryMcap)
                if (!o.decisionBasis8386.mark.known()) o.decisionBasis8386 = o.basis8386
                try { PipelineHealthCollector.labelInc("FORWARD_LABEL_BASIS_ADOPTED_8418") } catch (_: Throwable) {}
            }
            // V5.0.8524 — the adopt window passed with the basis still unknown: the first typed mark becomes the OBSERVED basis,
            // and the executable truth records what it is — a late first price (8153: the decision price was never buyable).
            // Before this every later mark died as OBSERVED_ONLY_UNKNOWN_BASIS and the observation could never book.
            if (adoptLateBasis8524(o.basis8386.mark.known(), age, mark8386)) {
                o.basis8386 = LabelBasis8386(mark8386, o.entryMcap)
                if (!o.decisionBasis8386.mark.known()) o.decisionBasis8386 = o.basis8386
                // the 8076 block below records the late first price (lateEntry8521) once the mark clears the curve / step guards
                try { PipelineHealthCollector.labelInc("FORWARD_LABEL_BASIS_ADOPTED_LATE_8524") } catch (_: Throwable) {}
            }
            // Validate against the original typed basis BEFORE a rebase can erase a decimals/units mismatch.
            if (!LabelEvidence8386.eligible(o.basis8386.mark,mark8386)) {
                o.lastMark8386=mark8386
                try { PipelineHealthCollector.labelInc("FORWARD_LABEL_${LabelEvidence8386.status(o.basis8386.mark,mark8386)}") } catch (_: Throwable) {}
                continue
            }
            if (mark8386.observedAtMs <= o.lastPxAtMs) continue // a cache read is not a new path event
            // nor is a copy re-stamped with a new clock: a non-market mark repeating the last price observed nothing new
            if (!liveMark8454(mark8386) && o.lastPxAtMs > 0L && px == o.lastPx) continue
            // V5.0.8167 — validate before rebasing or deleting the observation. The chain witness: a mark the coin's own active bonding curve contradicts is a basis error, not a
            // move. Rejecting it must leave the entry basis and rebase eligibility unchanged.
            if (curveContradicts8165(o.mint, px, nowMs, mark8386.observedAtMs)) {
                curveRefusedMark8165.incrementAndGet()
                try { PipelineHealthCollector.labelInc("FORWARD_LABEL_MARK_CURVE_CONTRADICTED_8165") } catch (_: Throwable) {}
                continue
            }
            // V5.0.8337 (10d) — a curve read more than 5 s from the mark that disagrees HOLDS the mark (the observation and its
            // basis untouched) until a newer curve read agrees or the read leaves the witness window; it is never dropped.
            if (curveHolds8337(o.mint, px, nowMs, mark8386.observedAtMs)) {
                curveHeldMark8337.incrementAndGet()
                try { PipelineHealthCollector.labelInc("FORWARD_LABEL_MARK_CURVE_HELD_8337") } catch (_: Throwable) {}
                continue
            }
            // V5.0.8167 — corroborate against the OLD basis; rebasing first hides a phantom jump.
            if (!PositionDisplayTruth8078.highStepOk8133(o.mint, o.entryPrice, o.entryPrice * (1.0 + o.peakPct / 100.0), px, "FORWARD_LABEL")) continue
            // V5.0.8076 — the label starts where a buy could actually land: the first mark 5-60 s after the decision.
            // V5.0.8094 — at the lane's MEASURED live fill latency (8094 SHITCOIN ~40 s), not the first mark 5 s out: a coin
            // that pumps between the decision and the fill was graded from a price live never gets, so setups graded
            // positive lost live (-17..-40% stop-outs within minutes).
            if (!o.rebased8076 && age >= REBASE_MIN_MS_8076) {
              if (mark8386.observedAtMs - o.atMs >= rebaseDelayMs8094(try { EntryChase7961.laneLatencyMs8094(o.lane) } catch (_: Throwable) { 0L })) {
                o.rebased8076 = true
                // V5.0.8153 — the FIRST mark ever seen (lastPxAtMs survives a restart), past the window: the entry stays unbuyable.
                if (com.lifecyclebot.engine.cortex.LearningEntryTruth8153.lateFirstPrice8153(mark8386.observedAtMs - o.atMs, REBASE_MAX_MS_8076)) {
                    o.lateEntry8521 = true
                    noteLateFirstPrice8153(o.mint, o.lane, o.atMs, nowMs)
                }
                if (mark8386.observedAtMs - o.atMs <= REBASE_MAX_MS_8076 && !basisSuspect7738(o.entryPrice, px)) {
                    // V5.0.8322 — an unchanged first mark is FLAT, not "down" (8319 read up=99 down=859).
                    when (com.lifecyclebot.engine.cortex.CortexFlatMark8322.rebaseDirection8322(o.entryPrice, px)) {
                        1 -> rebasedUp8076.incrementAndGet()
                        -1 -> rebasedDown8076.incrementAndGet()
                        else -> rebasedFlat8322.incrementAndGet()
                    }
                    rebaseGapSum8076 += (px / o.entryPrice - 1.0) * 100.0
                    o.basis8386 = o.basis8386.rebase(mark8386)
                    // V5.0.8094 — the rebased entry already holds the decision-to-fill move: the chase is not charged twice.
                    if (rebaseClearsChase8094(try { EntryChase7961.laneLatencyMs8094(o.lane) } catch (_: Throwable) { 0L })) { o.costPct = (o.costPct - o.chase8094).coerceAtLeast(0.0); o.chase8094 = 0.0 }
                    o.peakPct = 0.0; o.minPct7962 = 0.0; o.heldPeak8071 = 0.0; o.dipBeforePeak7962 = Double.NaN
                    synchronized(o.recent8071) { o.recent8071.clear(); resetPath8366(o) }
                }
              }
            }
            o.lastMark8386 = mark8386
            // Keep invalid observations for investigation; never turn missing proof into a loss or train on it.
            if (!eligible8386(o)) {
                val status8386 = LabelEvidence8386.status(o.basis8386.mark, mark8386)
                try { PipelineHealthCollector.labelInc("FORWARD_LABEL_$status8386") } catch (_: Exception) {}
                if (status8386 == "OBSERVED_ONLY_UNWITNESSED_EXTREME") {
                    // V5.0.7738/8386 — an unwitnessed 10x jump is a basis question, not a move: drop it unbooked and
                    // release the Cortex decision as BASIS-lost (never a loss sample, never a capped win).
                    basisSuspect7738.incrementAndGet()
                    pending.remove(key, o)
                    if (!o.done60) try { com.lifecyclebot.engine.cortex.Cortex7885.onLabelLost8187(o.mint, o.lane, "BASIS", decisionAtMs8371 = o.atMs) } catch (_: Throwable) {}
                }
                continue
            }
            val priorAccepted8395 = o.lastAcceptedMark8395
            o.lastAcceptedMark8395=mark8386
            val gross = (px / o.entryPrice - 1.0) * 100.0
            noteWindowRelease8418(o, gross, mark8386.observedAtMs)   // V5.0.8418 — before the low is graded
            if (gross < o.minPct7962) { notePathLow8366(o, mark8386.observedAtMs, gross); o.minPct7962 = gross }  // V5.0.8366 — first mark past a stop
            // V5.0.8000 — a runner inside its first 30 minutes is broadcast to every installed instance.
            if (gross >= 100.0 && age <= 30L * 60_000L) try { HiveEdge8000.noteRunner8000(o.mint, o.symbol, gross, o.atMs) } catch (_: Throwable) {}
            if (gross >= 400.0 && o.peakPct < 400.0) try {
                com.lifecyclebot.engine.RunnerGrab7967.onRunnerLabel7967(o.mint, o.symbol, o.lane, o.admitted, o.reason7967, gross, nowMs)
                // V5.0.7973 — a +400% coin's name/ticker words become a 6-hour theme (copycat / beta rotation).
                com.lifecyclebot.engine.market.MemeMeta7973.noteLeader7973(o.mint, o.symbol,
                    try { com.lifecyclebot.engine.BotService.status.tokens[o.mint]?.name.orEmpty() } catch (_: Throwable) { "" }, nowMs)
            } catch (_: Throwable) {}
            // V5.0.8017 — every 4x+ runner joined to the bot's own position (held? entry vs start? share banked?).
            // V5.0.8018 — a confirmed run (+100% over the decision price inside the hour) can earn a second entry.
            if (gross >= com.lifecyclebot.engine.RunnerPlay8018.RUN_CONFIRM_PCT_8018) try { com.lifecyclebot.engine.RunnerPlay8018.onRun8018(o.mint, gross, nowMs - o.atMs, nowMs) } catch (_: Throwable) {}
            if (gross >= RunnerCapture8017.RUNNER_PEAK_PCT_8017 && gross > o.peakPct) try { RunnerCapture8017.onRunnerPeak8017(o.mint, o.symbol, o.lane, o.entryPrice, gross, if (o.admitted) "" else o.reason7967.take(40), nowMs, mark8386.observedAtMs) } catch (_: Throwable) {}
            if (gross > o.peakPct) { o.peakPct = gross; o.peakAtMs7955 = mark8386.observedAtMs; o.dipBeforePeak7962 = o.minPct7962 }
            // V5.0.8071 — the level the price held long enough to sell into.
            synchronized(o.recent8071) { o.heldPeak8071 = heldPeakStep8071(o.recent8071, mark8386.observedAtMs, gross, o.heldPeak8071); notePathHeld8366(o, mark8386.observedAtMs, gross) }
            val priorPx7944 = o.lastPx to o.lastPxAtMs
            o.lastPx = px; o.lastPxAtMs = mark8386.observedAtMs
            val net = netPct(o.entryPrice, px, o.costPct).coerceAtMost(NET_CEILING_PCT_7738)
            gradeTiming8528(o, mark8386, nowMs)
            val observedAge8521 = mark8386.observedAtMs - o.atMs
            // V5.0.7809 — a horizon label is booked only from a mark inside its own
            // window (Field Manual L357): a 15-minute label first priced at minute 70
            // used to book the 70-minute move into the 15-minute cohort.
            // V5.0.8342 — a flat mark is a LOST mark at every slot (8337 FIX 2 closed the 60 slot only): the 15 slot books nothing on it.
            if (!o.done15 && observedAge8521 >= H15_MS_7731) { o.done15 = true; if (!horizonOpen7809(observedAge8521, H15_MS_7731)) horizonMissed7809.incrementAndGet() else if (flatMarkNow8342(o, gross)) flatMarks8322.incrementAndGet() else captured7945(o, net, gross).let { (n, g) -> book(o, 15, n, g) } }
            if (!o.done60 && age >= H60_MS_7731 && !horizonOpen7809(age, H60_MS_7731)) {
                // Restored after its 60-minute window closed: priced at the last mark seen before it
                // (V5.0.7944), lost only when there is none inside the window.
                // V5.0.8395 — the same bounded recovery / durable unavailable terminal as every other expiry: only the
                // last ACCEPTED in-window mark may label it; otherwise one exact unavailable terminal.
                o.lastPx = priorPx7944.first; o.lastPxAtMs = priorPx7944.second
                o.lastAcceptedMark8395 = priorAccepted8395; o.lastMark8386 = priorAccepted8395
                o.lateMark8418 = true   // V5.0.8418 — if this closes unavailable, its mark came after the window
                expired8395(key, o, null, maxOf(nowMs, deadline8395(o)))
                continue
            }
            // V5.0.8454 — a flat 5-minute read from a token-state price alone is neither booked nor lost: the off-watch batch
            // fetches the market's own price first (highest rank), inside the window.
            if (!o.done60 && observedAge8521 >= H60_MS_7731 && com.lifecyclebot.engine.cortex.CortexFlatMark8322.flatMark8322(gross, o.peakPct, o.minPct7962) &&
                !liveMark8454(mark8386)) {
                flatAwaitingLive8454.incrementAndGet()
                dueUnpriced7737.add(o.mint to com.lifecyclebot.engine.cortex.CortexLabelFeed8291.offWatchKey8291(0, o.atMs))
                continue
            }
            if (!o.done60 && observedAge8521 >= H60_MS_7731) {
                o.done60 = bookSixty7944(o, net, gross, nowMs)
                if (!o.done60) continue
                notePriced8418(o)   // V5.0.8418
            }
            // V5.0.7997 — progressive grading: 15 m, 30 m, 1 h, 4 h, 8 h, 12 h, 24 h while the coin lives.
            val stop7997 = if (o.done60) checkpoint7997(o, net, gross, observedAge8521, nowMs) else false
            // V5.0.8592 — an hour lane completes at its own target (15 m for new obs, 60 m for old persisted obs); a scalp lane
            // keeps the 60-min path read. bookable keys off the same horizon so completeHour8397 books, not horizonMissed.
            val hourTargetMs8592 = if (o.targetHorizon8521 > H60_MS_7731) o.targetHorizon8521 else H240_MS_7731
            if (!o.done240 && observedAge8521 >= hourTargetMs8592) {
                completeHour8397(key, o, net, gross, stop7997, horizonOpen7809(observedAge8521, hourTargetMs8592))
            } else if (o.done240 && stop7997) {
                endRun8006(o, gross)
                pending.remove(key, o)
            }
        }
        val shadow8442 = shadowDue8442(shadowWanted8442, offWatchMarks7737, nowMs, MARK_MAX_AGE_MS_7731 / 2) { m -> freshMarketMark8521(m, nowMs, MARK_MAX_AGE_MS_7731 / 2) }
        val due7737 = rotateDue7809(dueUnpriced7737.sortedBy { it.second }.map { it.first } + freshUnpriced7737 + shadow8442, nowMs)
        if (due7737.isNotEmpty()) fetchOffWatchMarks7737(due7737, nowMs)
        // Checkpoint live observations even before the first batch of 25 labels matures.
        persist()
    }

    // ── reads ──

    private fun cellStat(key: String): CellStat? {
        // V5.0.8000 — the rest of the hive's labels for this key ride on the local tally.
        val hive = try { HiveEdge8000.net8000("FRL8527|$key") } catch (_: Throwable) { null }
        val t = cells[key] ?: if (hive == null) return null else Tally()
        return synchronized(t) {
            val n = t.n60 + (hive?.get(0)?.toInt() ?: 0)
            val sum = t.sum60 + (hive?.get(1) ?: 0.0)
            val sumSq = t.sumSq60 + (hive?.get(2) ?: 0.0)
            val wins = t.win60 + (hive?.get(3)?.toInt() ?: 0)
            val runners = t.runner60 + (hive?.get(4)?.toInt() ?: 0)
            if (n <= 0 && t.lost <= 0) null
            else {
                // V5.0.8185 — robust read: a record impossible under the per-label plausibility cap proves nothing either way.
                // V5.0.8322 — unless its tail is real: 3+ local runners on chain-observed (basis-checked) marks read raw.
                val robust = tailRead8322(key, n, sum, sumSq, wins, t.runner60, t.sum60, t.witness60)  // V5.0.8324 — local sum, provenance
                val mean = robust.mean
                val se = robust.stderr
                CellStat(
                    key = key, n60 = n, meanNet60Pct = mean,
                    winRate60 = if (n > 0) wins.toDouble() / n else 0.0,
                    runnerRate60 = if (n > 0) runners.toDouble() / n else 0.0,
                    stderr60Pct = se, lost = t.lost,
                    n240 = t.n240, meanNet240Pct = if (t.n240 > 0) t.sum240 / t.n240 else 0.0,
                    outlierSuspect8185 = robust.suspect,
                    outlierFlag8322 = robust.suspect || flagged8322(key, n, sum, wins),
                )
            }
        }
    }

    // ── V5.0.8322 §NO_WIN_CAPS on labeler cells (owner directive 8072) ──
    //
    // 8185/8290 read every cell whose raw mean exceeds the per-label plausibility bound at that bound with the winsorised
    // range as its error, and 8290 made OUTLIER_SUSPECT "proves nothing" at every live door (judge, runner tail, cohort,
    // cell-wins, watch-first, RunnerGrab, CellProofLadder). On the runner-tail pond (runners +744..+1342 %) that vetoed
    // exactly the cells whose edge is the tail. The Cortex rule (CortexLedger7885.Stat.tailReal8322) now applies here too:
    // a cell with [TAIL_RUNNERS_8322]+ LOCAL runners (hive labels do not count) on basis-checked marks reads its raw mean
    // with the sample (n-1) standard error and is not suspect — one huge label then moves mean - SE by ~0. A cell resting on
    // fewer runners (one implausible label) keeps the 8185 bound and the 8290 veto. LiveAdmissionProof8290's realised
    // shortfall is unchanged.
    const val TAIL_RUNNERS_8322 = 3
    private val tailReadRaw8322 = AtomicLong(0)

    /** Pure (V5.0.8322): the cell read — 8185's robust read, or the raw read when the suspect cell's tail is real. */
    fun tailRead8322(key: String, n: Int, sum: Double, sumSq: Double, wins: Int, localRunners: Int,
                     localSum: Double = Double.NaN, localWitnessed: Int = 0): RobustLabelRead8185.Read8185 {
        val r = RobustLabelRead8185.read8185(key, n, sum, sumSq, wins)
        if (!r.suspect || localRunners < TAIL_RUNNERS_8322 || n < 2) return r
        // V5.0.8320 — the local runners must be physically plausible for this cell (the 8175 basis-error shape keeps the bound).
        // V5.0.8324 — judged on the LOCAL sum the local runners carry (8320 divided the hive-inflated sum by the local runner
        // count, so a hive-heavy cell was never plausible), and real at any size when the runners were chain-witnessed.
        val plausSum = if (localSum.isFinite()) localSum else sum
        if (!com.lifecyclebot.engine.cortex.CortexLedger7885.tailPlausible8320(plausSum, localRunners.toDouble(), RobustLabelRead8185.capForKey8185(key), localWitnessed.toDouble())) return r
        val nd = n.toDouble()
        val mean = sum / nd
        val variance = ((sumSq / nd) - mean * mean).coerceAtLeast(0.0) * nd / (nd - 1.0)
        tailReadRaw8322.incrementAndGet()
        return RobustLabelRead8185.Read8185(mean, kotlin.math.sqrt(variance / nd), false)
    }

    /** Pure (V5.0.8322): the raw mean is above the plausibility bound (display flag only). */
    private fun flagged8322(key: String, n: Int, sum: Double, wins: Int): Boolean =
        n > 0 && RobustLabelRead8185.outlierSuspect8185(n.toDouble(), sum, wins.toDouble(), RobustLabelRead8185.capForKey8185(key))

    /** V5.0.8000 — HiveEdge8000: this instance's own 5-minute tallies ([n, sum, sumSq, wins, runners, 0]). */
    fun hiveSnapshot8000(): Map<String, DoubleArray> {
        val out = HashMap<String, DoubleArray>(cells.size)
        for ((k, t) in cells) synchronized(t) {
            if (t.n60 >= 3) out[k] = doubleArrayOf(t.n60.toDouble(), t.sum60, t.sumSq60, t.win60.toDouble(), t.runner60.toDouble(), 0.0)
        }
        return out
    }

    fun cellStatFor(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): CellStat? {
        // V5.0.8337 (FIX 7a) — inside the 8114 legacy scope the cell is keyed on the 8114 age (time since discovery), so the
        // 8114 decision reads the cells 8114 wrote; the 8208 birth-witness age keys everything else.
        val ageMs = cellAgeMs8208(ts, nowMs)
        return executable8187(cellStat(cellKey(ts.source, lane, TrustedMcap8019.mcap8019(ts), ageMs)), lane)  // V5.0.8019 / V5.0.8187
    }

    /**
     * V5.0.8187 §EXECUTABLE_LABELS — the cell / kind / lane records the gates and sizing read (cell proofs, Kelly shares) are
     * read executable: the lane's measured label-to-bank gap (Cortex7885.executableOffset8187) comes off the mean. The stored
     * tallies and every display line stay raw.
     */
    /** V5.0.8326 — the 8114 read of a cell (raw mean, population SE, never suspect): retired compatibility mode's legacy scope. */

    fun executable8187(c: CellStat?, lane: String): CellStat? {
        if (c == null) return null
        val off = try { com.lifecyclebot.engine.cortex.Cortex7885.executableOffset8187(lane) } catch (_: Throwable) { 0.0 }
        return execShift8187(c, off)
    }

    /** Pure (V5.0.8187): [c] with [offsetPct] (>= 0) taken off its 5-minute and 60-minute means. */
    fun execShift8187(c: CellStat, offsetPct: Double): CellStat =
        if (!offsetPct.isFinite() || offsetPct <= 0.0) c
        else c.copy(meanNet60Pct = c.meanNet60Pct - offsetPct, meanNet240Pct = c.meanNet240Pct - offsetPct)

    /**
     * V5.0.8063 — the 5-minute record of [lane] for coins of [ts]'s kind: (venue x cap band, narrative). A kind proven to
     * pay is measured edge (LiveEdgeGate7877) and sizes like a proven cell (LiveRiskPolicy7807).
     */
    fun kindStatsFor8063(ts: TokenState, lane: String): List<CellStat> {
        val k = kindOf8063(ts).split('/')
        if (k.size != 2) return emptyList()
        val l = lane.trim().uppercase()
        // V5.0.8280 — an UNKNOWN narrative is not a kind: NARR|lane|UNKNOWN is the whole lane minus the classified coins
        // (8279: NARR|SHITCOIN|UNKNOWN n=1024 +368 % OUTLIER_SUSPECT), and it must not prove live money for any coin.
        val narr = k[1].trim().uppercase()
        val narrStat = if (narr.isBlank() || narr == "UNKNOWN") null else executable8187(cellStat(narrKey8063(l, k[1])), l)
        return listOfNotNull(executable8187(cellStat(kindKey8063(l, k[0], mcapBand(TrustedMcap8019.mcap8019(ts)))), l), narrStat)  // V5.0.8187
    }

    private fun laneStat(lane: String): CellStat? = cellStat(laneKey(lane.trim().uppercase()))

    /** V5.0.7928 — the 60-minute record of every [lane] decision taken at lifecycle [stage]. */
    fun stageStatFor7928(lane: String, stage: String): CellStat? =
        if (stage.equals("UNKNOWN", true) || stage.isBlank()) null else cellStat(stageKey7928(lane, stage))

    /** V5.0.8473 — every discovery-source family's record (local + hive), most-labelled first: which scanners find coins that pay. */
    fun sourceStats8473(limit: Int): List<CellStat> =
        cells.keys.toList().filter { it.startsWith("SRC|") }.mapNotNull { cellStat(it) }.sortedByDescending { it.n60 }.take(limit)

    /** V5.0.8169 — a key's local record since the grading epoch (no hive), the only record a loss pattern may refuse on. */
    private fun freshStat8169(key: String): CellStat? {
        syncEpoch8169()
        val t = fresh8169[key] ?: return null
        return synchronized(t) {
            val n = t.n60
            if (n <= 0) null else {
                val robust = tailRead8322(key, n, t.sum60, t.sumSq60, t.win60, t.runner60, t.sum60, t.witness60)  // V5.0.8185 / V5.0.8322 / V5.0.8324 — a real tail reads raw
                CellStat(
                    key = key, n60 = n, meanNet60Pct = robust.mean,
                    winRate60 = t.win60.toDouble() / n, runnerRate60 = t.runner60.toDouble() / n,
                    stderr60Pct = robust.stderr, lost = 0, outlierSuspect8185 = robust.suspect,
                    outlierFlag8322 = robust.suspect || flagged8322(key, n, t.sum60, t.win60),
                    n240 = t.n240, meanNet240Pct = if (t.n240 > 0) t.sum240 / t.n240 else 0.0,  // V5.0.8198
                )
            }
        }
    }

    /** V5.0.8169 — [cellStatFor] since the grading epoch. */
    fun freshCellStatFor8169(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): CellStat? {
        val ageMs = cellAgeMs8208(ts, nowMs)
        return freshStat8169(cellKey(ts.source, lane, TrustedMcap8019.mcap8019(ts), ageMs))
    }

    /** V5.0.8169 — [stageStatFor7928] since the grading epoch. */
    fun freshStageStatFor8169(lane: String, stage: String): CellStat? =
        if (stage.equals("UNKNOWN", true) || stage.isBlank()) null else freshStat8169(stageKey7928(lane, stage))

    /** V5.0.8169 — report: the epoch and the fresh book's size. */
    fun freshStatus8169(): String {
        val epoch = freshEpochMs8169
        val age = if (epoch > 0L) "${(System.currentTimeMillis() - epoch) / 60_000L}m" else "-"
        return "fresh8169[gradingVersion=$GRADING_VERSION_8169 epochAge=$age learningEpoch8164=${if ((try { LearningEpoch8164.epochMs8164() } catch (_: Throwable) { 0L }) > 0L) "set" else "0"} keys=${fresh8169.size} booked=${freshBooked8169.get()} preEpochSkipped=${freshSkippedPreEpoch8169.get()}]"
    }

    /** V5.0.7737 — the lane's 60-minute label record (LaneAutoPauseGuard's label-proof release). */
    // ── V5.0.8418 §OFFSET_READS_WHAT_LABELS_PAID — the Cortex executable offset (Cortex7885.costGap8187) compared the ticket's
    // round trip with CostLedger7962.labelChargedCostPct8153, which assumed labels paid the 1.5 % base estimate; labels pay
    // the all-in cost at their own size and depth (~3.3 %), so the gap (~2.1 pt) was charged a second time on every lane.
    // The labeler keeps the mean cost (chase excluded) it actually charged per lane; the offset reads that.
    private val charged8418 = ConcurrentHashMap<String, DoubleArray>()   // lane -> [n, mean]
    private val depthUnknownLabels8534 = AtomicLong(0)

    /** V5.0.8534 — chargedCost8418{LANE=mean%/n ...}: what this session's labels were actually charged, per lane (top 8 by n). */
    private fun chargedCostLine8534(): String = "chargedCost8418{" + charged8418.entries
        .map { (l, a) -> synchronized(a) { Triple(l, a[1], a[0].toInt()) } }.filter { it.third > 0 }
        .sortedByDescending { it.third }.take(8).joinToString(" ") { "${it.first}=${"%.1f".format(it.second)}%/n${it.third}" }
        .ifBlank { "-" } + "} depthUnknownLabels8534=${depthUnknownLabels8534.get()}"

    internal fun noteChargedCost8418(lane: String, costPct: Double) {
        if (!costPct.isFinite() || costPct < 0.0) return
        val a = charged8418.computeIfAbsent(lane.trim().uppercase()) { DoubleArray(2) }
        synchronized(a) {
            val n = minOf(a[0] + 1.0, 500.0)   // a running mean that keeps adapting (window ~500 labels)
            a[1] += (costPct - a[1]) / n; a[0] = n
        }
    }

    /** The mean round trip (%, chase excluded) this lane's labels were charged; null before any label. */
    fun chargedCostPct8418(lane: String): Double? =
        charged8418[lane.trim().uppercase()]?.let { a -> synchronized(a) { if (a[0] >= 1.0) a[1] else null } }

    fun laneStatFor7737(lane: String): CellStat? = executable8187(laneStat(lane), lane)  // V5.0.8187 — executable read

    /** V5.0.8438 — DiagnosisBrief8438: the forward record of every admitted / every refused decision. */
    fun aggregate8438(admitted: Boolean): CellStat? = cellStat(if (admitted) AGG_ADMITTED else AGG_REFUSED)

    private fun fmtStat(s: CellStat?): String =
        if (s == null) "n=0" else "n=${s.n60} net=${"%+.1f".format(s.meanNet60Pct)}% wr=${"%.0f".format(s.winRate60 * 100)}% run=${"%.0f".format(s.runnerRate60 * 100)}% lost=${s.lost}" +
            (if (s.outlierSuspect8185) " OUTLIER_SUSPECT_8185" else if (s.outlierFlag8322) " OUTLIER_FLAG_TAIL_8322" else "")

    private fun terminalStatus8401(): String {
        val oldest = terminalOutbox8395.entries.asSequence().filterNot { deliveredTerminals8395.contains(it.key) }
            .mapNotNull { try { org.json.JSONObject(it.value).optLong("terminalAt").takeIf { at -> at > 0L } } catch (_: Exception) { null } }.minOrNull()
        return "entryTarget8521=frozen ${rowStoreLine8409()} terminal8401[queued=${terminalOutbox8395.size} ack=${deliveredTerminals8395.size} oldestMs=${oldest?.let { System.currentTimeMillis() - it } ?: 0L} capacity=${terminalCapacityRefusals8401.get()} storageFailures=${terminalStorageFailures8401.get()}]"
    }

    fun statusLine(): String {
        val cellStats = cells.keys
            .filter { !it.startsWith("AGG|") && !it.startsWith("LANE|") && !it.startsWith("SRC|") && !it.startsWith("STAGE|") && !it.startsWith("KIND|") && !it.startsWith("NARR|") }
            .mapNotNull { cellStat(it) }
            .filter { it.n60 >= 30 }
        val best = cellStats.sortedByDescending { it.meanNet60Pct }.take(3)
        val worst = cellStats.sortedBy { it.meanNet60Pct }.take(3)
        // V5.0.8063 — the coin-kind books (lane x venue x cap, lane x narrative) at n >= 30.
        val kinds = cells.keys.filter { it.startsWith("KIND|") || it.startsWith("NARR|") }.mapNotNull { cellStat(it) }.filter { it.n60 >= 30 }
        val kindBest = kinds.sortedByDescending { it.meanNet60Pct }.take(4)
        val kindWorst = kinds.sortedBy { it.meanNet60Pct }.take(3)
        val lanes = cells.keys.filter { it.startsWith("LANE|") }.map { it.removePrefix("LANE|") }.sorted()
            .mapNotNull { l -> laneStat(l)?.let { "$l[${fmtStat(it)}]" } }
        return "read=5m(slot60) early=1m late=60m pending=${pending.size} restored7735=${restoredPending7735.get()} observed=${observed.get()} booked15=${booked15.get()} booked60=${booked60.get()} booked240=${booked240.get()} " +
            "lostMark=${lostMark.get()} vanished7944[lastMark=${vanishedLastMark7944.get()} dead=${vanishedDead7944.get()}] spikeCredited7945=${capturedLabels7945.get()} ${honestLine8366()} offWatch7737[priced=${offWatchPriced7737.get()} missed=${offWatchMissed7737.get()} limiterRetry8474=${offWatchLimiterRetry8474.get()} curve7753=${offWatchCurvePriced7753.get()} chain8540=${offWatchChainPriced8540.get()}/asked${chainRequested8540.get()}/reads${OnChainDepth8038.mintRequests8540()} deferred7809=${offWatchDeferred7809.get()} batches8508=${offWatchBatches8508.get()} avgMs8508=${offWatchBatches8508.get().let { n -> if (n > 0) offWatchBatchMsSum8508.get() / n else 0 }} maxMs8508=$offWatchBatchMsMax8508 stallReleased8508=${offWatchStallReleased8508.get()} running8508=${offWatchRunning8508.get()} markNoSolRate8508=${noSolRate8508.get()}] horizonMissed7809=${horizonMissed7809.get()} ${horizonPricerLine8583()} ${labelMarksLine8418()} hourTarget8571[lost=${hourTargetLost8571Count.get()} interimMissed=${interimMissed8571.get()}] basisSuspect7738=${basisSuspect7738.get()} entryBasis8571[chain=${chainEntry8571.get()} curveRebased=${curveRebasedEntry8571.get()}] curveWitness8165[entry=${curveRefusedEntry8165.get()} mark=${curveRefusedMark8165.get()} held8337=${curveHeldMark8337.get()}] hourSlot8337=${revisedToHour8337.get()} witnessedRunnerLabels8324=${witnessedLabels8324.get()} purged7738=${purgedCells7738.get()} curvePriced8062=${curvePriced8062.get()} rebased8076[up=${rebasedUp8076.get()} down=${rebasedDown8076.get()} flat8322=${rebasedFlat8322.get()} flatLostMarks8342=${flatMarks8322.get()} meanGap=${(rebasedUp8076.get() + rebasedDown8076.get() + rebasedFlat8322.get()).let { n -> if (n > 0) "%+.1f".format(rebaseGapSum8076 / n) else "-" }}% lateFirstPrice8153=${lateFirstCount8153.get()}] skipped[noPrice=${skippedNoPrice.get()} noState8572=${skippedNoState8572.get()} recent=${skippedRecent.get()} full=${skippedFull.get()}] cells=${cellStats.size}/${cells.size}\n" +
            "      admitted60[${fmtStat(cellStat(AGG_ADMITTED))}] refused60[${fmtStat(cellStat(AGG_REFUSED))}]\n" +
            "      best60: ${best.joinToString(" · ") { "${it.key}[${fmtStat(it)}]" }.ifBlank { "none at n>=30" }}\n" +
            "      tail8303: ${best.joinToString(" · ") { CellTail8303.line8303(it.key, it.meanNet60Pct, it.winRate60, it.runnerRate60) }.ifBlank { "-" }}\n" +  // V5.0.8303
            "      worst60: ${worst.joinToString(" · ") { "${it.key}[${fmtStat(it)}]" }.ifBlank { "none at n>=30" }}\n" +
            "      kinds8063 best: ${kindBest.joinToString(" · ") { "${it.key}[${fmtStat(it)}]" }.ifBlank { "none at n>=30" }} | worst: ${kindWorst.joinToString(" · ") { "${it.key}[${fmtStat(it)}]" }.ifBlank { "none" }}\n" +
            "      lanes60${com.lifecyclebot.engine.MetricTag8535.LANES60_8535.line()}: ${lanes.joinToString(" · ").ifBlank { "no labels yet" }}" +   // V5.0.8535 §P2-03
            " | ${terminalStatus8401()} robust8185[cap=${RobustLabelRead8185.CAP_LABEL_PCT_8185.toInt()}% capGT1M=${RobustLabelRead8185.CAP_LARGE_CAP_PCT_8185.toInt()}% suspectReads=${RobustLabelRead8185.suspectReads8185()} tailReadRaw8322=${tailReadRaw8322.get()}]"
    }

    /** Called when the service stops: the table survives the restart. */
    fun persistNow7731() {
        persist(force = true)
        try { FreshLaunchSelector7737.persistNow7737() } catch (_: Throwable) {}
        try { SpecialistMiner7972.saveNow7972() } catch (_: Throwable) {}   // V5.0.7972
        try { EdgeModel8537.saveNow() } catch (_: Throwable) {}             // V5.0.8537
        try {
            ForensicLogger.lifecycle(
                "FORWARD_LABELER_PERSISTED_7731",
                "cells=${cells.size} pending=${pending.size} booked60=${booked60.get()} lost=${lostMark.get()} " +
                    "admitted=[${fmtStat(cellStat(AGG_ADMITTED))}] refused=[${fmtStat(cellStat(AGG_REFUSED))}]",
            )
        } catch (_: Throwable) {}
    }
}

/** V5.0.8014 — Double to Int, rounded half up (counts scaled by a fresh-edge window). */

