package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Position
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6500 — QUANTITY INVARIANT AUTHORITY.
 *
 * OPERATOR MANDATE (verbatim, 6499 evidence):
 *
 *   "The token amounts vs spend don't line up at all. compassSOL:
 *    Entry $112.15, Size 0.05 SOL, Tokens 6.71K → 6710 × $112 = $752K
 *    on a $5 paper buy. Every open position violates
 *      qty × entryPrice ≈ sizeSol × solPrice
 *
 *    I need to stop shipping 'more authorities' that don't actually
 *    verify the invariants hold end-to-end. The fix must include an
 *    ASSERTIVE runtime check that if qty × entryPrice_usd disagrees
 *    with costSol × solPrice at any point, the position is
 *    quarantined immediately. Not just logged."
 *
 * INVARIANT
 * ─────────
 * A physically valid paper position must satisfy:
 *
 *   |qtyToken × entryPriceUsd  −  costSol × solPriceUsd|
 *   ─────────────────────────────────────────────────────  <  TOL
 *              (costSol × solPriceUsd)
 *
 * The two sides are the same notional expressed in two ways
 * (per-token accounting and per-SOL accounting). They MUST match
 * within a tight tolerance (default 10 %). Anything else is a
 * unit/scale bug or a stale-basis rebase and must not be allowed
 * to leak into openMarketValueSol / equity / analytics / learners.
 *
 * BEHAVIOUR
 * ─────────
 * • `validate(mint, pos)` → true iff invariant holds.
 * • `markInvariantBroken(mint, reason)` → routes to
 *   `HistoricalEconomicQuarantine6496` exactly once per mint.
 * • `isQuarantined(mint)` → true iff the mint has been marked.
 * • `sweepOpenPositions(status, forceClose)` → walks every open
 *   position and quarantines / optionally force-closes any that
 *   fail the invariant.
 */
object QuantityInvariantAuthority6500 {

    private const val TOLERANCE_RATIO = 0.10  // 10 %

    private val quarantined = ConcurrentHashMap<String, String>()
    private val validations = AtomicLong(0L)
    private val breaks = AtomicLong(0L)

    data class InvariantCheck(
        val ok: Boolean,
        val ratio: Double,
        val qtyNotionalUsd: Double,
        val costNotionalUsd: Double,
        val reason: String,
    )

    /**
     * V5.0.6635f §CANONICAL_ECONOMIC_INVARIANT — runs the economic
     * notional check on the CANONICAL position fields directly, no
     * runtime `data.Position` projection required.  The strict
     * `openPositions()` filter uses this to catch canonical-side
     * invariant breaks even when no runtime `check(mint, pos)` call
     * has been made yet.  On failure, atomically quarantines the mint
     * so subsequent `isQuarantined(mint)` reads return true.
     *
     * OPERATOR SCREENSHOT (Feb 2026, second dump): 8+ positions
     * (HzmASEo..., GPRO, GASSPAS, CHOMP, DONK, Ape-1, NICKGPRO,
     * catfish) rendered `Entry: INVARIANT_BROKEN_6500` / `qty INVALID`
     * after V5.0.6634's isQuarantined-only strict filter — because
     * the RUNTIME check(mint, pos) failed BUT the mint was never yet
     * quarantined by a prior call.  This method closes that loophole
     * at the canonical layer.
     */
    fun checkCanonical6635(canonical: CanonicalPositionAuthority6441.Position): InvariantCheck {
        val scale = canonical.quantityScale
        if (scale !in 0..18 || canonical.remainingQtyRaw <= java.math.BigInteger.ZERO) {
            return InvariantCheck(false, Double.POSITIVE_INFINITY, 0.0, canonical.entryCostSol,
                "canonical_qty_or_scale_invalid")
        }
        val qtyToken = try { canonical.remainingQtyRaw.toBigDecimal().movePointLeft(scale).toDouble() } catch (_: Throwable) { 0.0 }
        val entry = canonical.entryPriceUsd
        val cost = canonical.entryCostSol.coerceAtLeast(0.0)
        // Only run the economic invariant when all three fields carry
        // real values.  A partially-populated canonical row (e.g.
        // freshly opened, entry not yet stamped) is allowed to pass
        // — the strict filter's entryPriceUsd > 0 gate catches those.
        if (!qtyToken.isFinite() || qtyToken <= 0.0) return InvariantCheck(false, 0.0, 0.0, cost, "qty_nonpositive")
        if (!entry.isFinite() || entry <= 0.0) return InvariantCheck(true, 0.0, 0.0, cost, "entry_price_not_set")
        if (!cost.isFinite() || cost <= 0.0) return InvariantCheck(true, 0.0, 0.0, cost, "cost_not_set")
        // Same economic-notional math as economicNotionalCheck6537:
        //   qty_notional_usd  = qty × entryPriceUsd
        //   impliedSolPriceUsd = qty_notional_usd / costSol
        //   must fall in [MIN_PLAUSIBLE_SOL_USD .. MAX_PLAUSIBLE_SOL_USD]
        val src = canonical.entryPriceSource.uppercase()
        // Skip synthetic bases (same doctrine as economicNotionalCheck6537).
        if (src.contains("SYNTH") || src.contains("PUMP_FUN_BC")) {
            return InvariantCheck(true, 0.0, 0.0, cost, "synthetic_basis_skipped")
        }
        // DERIVED_CARRY_COST_QTY_6631 & DURABLE_CARRY sources produce
        // entry prices in SOL/token space, not USD/token, so the
        // economic notional check would collapse to ratio~1 and
        // false-positive quarantine every legitimate carry-replay
        // position.  Skip them.
        //
        // Also skip OPEN_POSITION_DERIVED_FROM_COST_QTY_6631 — the
        // 6631d openPosition() fallback derives entry from
        // entryCostSol/qtyToken when the caller passed 0. The result
        // is in SOL/token, not USD/token, and would trip the same
        // impliedSolPriceUsd~=1 false-positive.
        if (src.contains("DERIVED_CARRY_COST_QTY_6631") || src.contains("DURABLE_CARRY_COST_QTY_REPAIR_6519") ||
            src.contains("REPLAY_CARRY") || src.contains("RECOVERED_CARRY") ||
            src.contains("OPEN_POSITION_DERIVED_FROM_COST_QTY_6631") ||
            src.contains("DERIVED_FROM_COST")) {
            return InvariantCheck(true, 0.0, 0.0, cost, "carry_or_derived_basis_skipped")
        }
        val qtyNotionalUsd = qtyToken * entry
        if (!qtyNotionalUsd.isFinite() || qtyNotionalUsd <= 0.0) return InvariantCheck(false, 0.0, 0.0, cost, "qty_notional_nonpositive")
        val impliedSolPriceUsd = qtyNotionalUsd / cost.coerceAtLeast(1e-18)
        val ok = impliedSolPriceUsd.isFinite() &&
            impliedSolPriceUsd in MIN_PLAUSIBLE_SOL_USD..MAX_PLAUSIBLE_SOL_USD
        val reason = if (ok) "canonical_econ_ok_impliedSolUsd=$impliedSolPriceUsd"
            else "canonical_econ_notional_mismatch impliedSolUsd=$impliedSolPriceUsd " +
                "band=[$MIN_PLAUSIBLE_SOL_USD,$MAX_PLAUSIBLE_SOL_USD] " +
                "qtyNotional=$qtyNotionalUsd costSol=$cost"
        if (!ok && canonical.mint.isNotBlank()) {
            markInvariantBroken(canonical.mint, reason)
            try { PipelineHealthCollector.labelInc("QUANTITY_INVARIANT_CANONICAL_BROKEN_6635") } catch (_: Throwable) {}
        }
        return InvariantCheck(ok, impliedSolPriceUsd, qtyNotionalUsd, cost, reason)
    }

    /** V5.0.6521 — validate mutable runtime projection against canonical raw lot truth. */
    fun check(mint: String, pos: Position): InvariantCheck {
        validations.incrementAndGet()
        if (mint.isBlank() || !pos.isOpen || !pos.qtyToken.isFinite() || pos.qtyToken <= 0.0 ||
            !pos.entryPrice.isFinite() || pos.entryPrice <= 0.0 ||
            !pos.costSol.isFinite() || pos.costSol <= 0.0
        ) {
            return InvariantCheck(false, Double.POSITIVE_INFINITY, 0.0, 0.0,
                "runtime_projection_structurally_invalid")
        }
        // V5.0.6537 §ECONOMIC_INVARIANT — the operator's original 6499 mandate
        // was: "qty × entryPrice_usd disagrees with costSol × solPrice → quarantine
        // immediately". Pre-6537 the check only compared runtime pos to the
        // canonical raw record, so bugs that minted BOTH sides with the same
        // bad math (e.g. paper qty derived without solPriceUsd, or entryPrice
        // written in SOL/token instead of USD/token) passed the invariant
        // silently. The operator's TNOS/Morty/SPACES/SRM/POPCAT/MOBILE/Buddy/
        // Pistacia rows (18.78 tokens for 0.0008 SOL @ $0.00003998 "USD", etc.)
        // all satisfy the canonical vs runtime check but violate the ECONOMIC
        // notional identity by ~200x. Enforce the economic side FIRST so a
        // canonical row that co-agrees with a broken runtime row is still
        // caught.
        val econ6537 = economicNotionalCheck6537(pos)
        if (econ6537 != null && !econ6537.ok) {
            if (quarantined.putIfAbsent(mint, econ6537.reason) == null) {
                breaks.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "QUANTITY_ECONOMIC_INVARIANT_BROKEN_6537",
                        "mint=${mint.take(10)} qtyNotionalUsd=${econ6537.qtyNotionalUsd} " +
                            "costNotionalUsd=${econ6537.costNotionalUsd} ratio=${econ6537.ratio} " +
                            "expected=|qty*entry-cost*solPrice|/cost*solPrice<=${TOLERANCE_RATIO} " +
                            "observed=${econ6537.reason}",
                    )
                    PipelineHealthCollector.labelInc("QUANTITY_ECONOMIC_INVARIANT_BROKEN_6537")
                } catch (_: Throwable) {}
            }
            return econ6537
        }
        val expectedMode = if (pos.isPaperPosition) "paper" else "live"
        // V5.0.6636 — bind by positionId first. The previous mint-only lookup
        // could compare a re-entry against the previous generation and, worse,
        // returned ok=true when canonical openPositions() had already filtered
        // the broken row. Missing canonical truth is now fail-closed.
        val canonical = if (pos.positionId.isNotBlank()) {
            CanonicalPositionAuthority6441.getPosition(pos.positionId)
                ?.takeIf { it.mint == mint && it.mode == expectedMode }
        } else {
            // Compatibility for pre-6512 persisted projections. New commits
            // always carry positionId; this fallback still reads the strict
            // CanonicalPositionAuthority6441.openPositions() inventory.
            CanonicalPositionAuthority6441.openPositions()
                .filter { it.mint == mint && it.mode == expectedMode }
                .maxByOrNull { it.lastMutationMs }
        } ?: return InvariantCheck(false, Double.POSITIVE_INFINITY, 0.0, 0.0,
            "canonical_open_missing_or_identity_mismatch")
        if (canonical.lifecycle !in setOf(
                CanonicalPositionAuthority6441.Lifecycle.OPEN,
                CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED,
            ) || canonical.quarantineReason.isNotBlank()
        ) {
            return InvariantCheck(false, Double.POSITIVE_INFINITY, 0.0, canonical.entryCostSol,
                "canonical_lifecycle_or_quarantine_invalid")
        }
        val canonicalSource = canonical.entryPriceSource.uppercase()
        if (canonicalSource.contains("INVARIANT_BROKEN") ||
            canonicalSource.contains("QUARANTINED") ||
            canonicalSource.contains("LEGACY_REPLAY_QUARANTINED")
        ) {
            return InvariantCheck(false, Double.POSITIVE_INFINITY, 0.0, canonical.entryCostSol,
                "canonical_entry_source_invalid")
        }
        val canonicalEconomic = checkCanonical6635(canonical)
        if (!canonicalEconomic.ok || !canonical.entryPriceUsd.isFinite() || canonical.entryPriceUsd <= 0.0) {
            return InvariantCheck(false, canonicalEconomic.ratio, canonicalEconomic.qtyNotionalUsd,
                canonicalEconomic.costNotionalUsd, "canonical_economic_or_entry_invalid: ${canonicalEconomic.reason}")
        }
        val scale = canonical.quantityScale
        if (scale !in 0..18 || canonical.remainingQtyRaw <= java.math.BigInteger.ZERO) {
            return InvariantCheck(false, Double.POSITIVE_INFINITY, 0.0, canonical.entryCostSol, "canonical_raw_or_scale_invalid")
        }
        val canonicalQty = canonical.remainingQtyRaw.toBigDecimal().movePointLeft(scale).toDouble()
        val canonicalCost = (canonical.entryCostSol - canonical.soldCostBasisSol).coerceAtLeast(0.0)
        val qtyRatio = kotlin.math.abs(pos.qtyToken - canonicalQty) / canonicalQty.coerceAtLeast(1e-18)
        val costRatio = kotlin.math.abs(pos.costSol - canonicalCost) / canonicalCost.coerceAtLeast(1e-18)
        val canonicalEntry = canonical.entryPriceUsd.takeIf { it.isFinite() && it > 0.0 }
        val priceRatio = canonicalEntry?.let { kotlin.math.abs(pos.entryPrice - it) / it.coerceAtLeast(1e-18) } ?: 0.0
        val ratio = maxOf(qtyRatio, costRatio, priceRatio)
        val ok = canonicalQty.isFinite() && canonicalQty > 0.0 && canonicalCost.isFinite() && canonicalCost > 0.0 && ratio <= TOLERANCE_RATIO
        val priorQuarantine = quarantined[mint]
        // Only a projection mismatch may self-release after reconstruction.
        // A route/basis/economic quarantine must remain sticky until its owning
        // authority explicitly proves and releases it.
        if (ok && priorQuarantine?.startsWith("runtime_projection_vs_canonical_raw") == true &&
            quarantined.remove(mint, priorQuarantine)
        ) {
            try {
                ForensicLogger.lifecycle("QUANTITY_INVARIANT_REPAIRED_RELEASED_6521", "mint=${mint.take(10)} qtyRatio=$qtyRatio costRatio=$costRatio priceRatio=$priceRatio")
                PipelineHealthCollector.labelInc("QUANTITY_INVARIANT_REPAIRED_RELEASED_6521")
            } catch (_: Throwable) {}
        }
        // V5.0.6635f — projection-vs-canonical failures MUST quarantine
        //   the mint atomically so the strict openPositions() filter
        //   (which reads isQuarantined) catches every subsequent
        //   render before the UI paints INVARIANT_BROKEN_6500. Prior
        //   to 6635f, only the ECONOMIC branch quarantined; a
        //   projection-only failure returned ok=false without
        //   quarantining, which is exactly the loophole the operator's
        //   HzmASEo / GPRO / GASSPAS / CHOMP / DONK / Ape-1 /
        //   NICKGPRO / catfish screenshot exposed.
        if (!ok && mint.isNotBlank()) {
            val reason6635f = "runtime_projection_vs_canonical_raw qtyRatio=$qtyRatio costRatio=$costRatio priceRatio=$priceRatio"
            if (quarantined.putIfAbsent(mint, reason6635f) == null) {
                breaks.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "QUANTITY_PROJECTION_INVARIANT_BROKEN_6635F",
                        "mint=${mint.take(10)} reason=$reason6635f",
                    )
                    PipelineHealthCollector.labelInc("QUANTITY_PROJECTION_INVARIANT_BROKEN_6635F")
                } catch (_: Throwable) {}
            }
        }
        return InvariantCheck(ok, ratio, canonicalQty * (canonicalEntry ?: pos.entryPrice), canonicalCost,
            if (ok) "ok_canonical_raw" else "runtime_projection_vs_canonical_raw qtyRatio=$qtyRatio costRatio=$costRatio priceRatio=$priceRatio")
    }

    /**
     * V5.0.6636 — the single admission gate for every runtime OPEN consumer
     * (UI list, hero totals, exposure, and ViewModel snapshots).
     *
     * A row is not open merely because qtyToken > 1. It must be linked to a
     * canonical OPEN lot, match its raw quantity/cost/entry projection, pass
     * the economic invariant, and (for current-schema rows) have the immutable
     * BUY snapshot that downstream readers depend on.
     */
    fun isRuntimeOpenEligible6636(mint: String, pos: Position): Boolean {
        val check = check(mint, pos)
        if (!check.ok || isQuarantined(mint)) {
            try { PipelineHealthCollector.labelInc("RUNTIME_OPEN_REJECTED_INVARIANT_6636") } catch (_: Throwable) {}
            return false
        }
        if (pos.positionId.isNotBlank() && LockedEntryMetrics6634.read6634(pos.positionId) == null) {
            markInvariantBroken(mint, "LOCKED_ENTRY_MISSING_6636 positionId=${pos.positionId.take(24)}")
            try { PipelineHealthCollector.labelInc("RUNTIME_OPEN_REJECTED_LOCK_MISSING_6636") } catch (_: Throwable) {}
            return false
        }
        return true
    }

    /** Legacy non-authorizing structural check; callers with a mint must use check(mint, pos). */
    fun check(pos: Position): InvariantCheck {
        val valid = pos.qtyToken.isFinite() && pos.qtyToken > 0.0 && pos.entryPrice.isFinite() && pos.entryPrice > 0.0 && pos.costSol.isFinite() && pos.costSol > 0.0
        return InvariantCheck(valid, if (valid) 0.0 else Double.POSITIVE_INFINITY, 0.0, 0.0,
            if (valid) "legacy_structural_only" else "invalid_runtime_projection")
    }

    /**
     * V5.0.6537 — ECONOMIC NOTIONAL INVARIANT (operator's original 6499 mandate).
     *
     * Two independent expressions of the same open notional:
     *   qty_notional_usd  = pos.qtyToken × pos.entryPrice  (per-token accounting)
     *   cost_notional_usd = pos.costSol × solPriceUsd     (per-SOL accounting)
     *
     * These MUST match — implying:
     *   impliedSolPriceUsd = (qty × entry) / cost_sol
     * must fall inside a physically sane band [MIN_PLAUSIBLE_SOL_USD .. MAX_PLAUSIBLE_SOL_USD].
     *
     * V5.0.6521 doctrine forbids reading any mutable runtime SOL price
     * (WalletManager last-known-Sol-USD field) inside the invariant
     * (mutable runtime scalar → false quarantine risk), so we derive the
     * implied SOL price purely from the position's own immutable fields and only reject when the implied price is
     * physically impossible (e.g. < $5 or > $10,000 → obvious units bug).
     *
     * Returns null when the check cannot run (malformed fields, or a
     * synthetic price basis where entry is intentionally non-USD).
     */
    fun economicNotionalCheck6537(pos: Position): InvariantCheck? {
        if (!pos.qtyToken.isFinite() || pos.qtyToken <= 0.0) return null
        if (!pos.entryPrice.isFinite() || pos.entryPrice <= 0.0) return null
        if (!pos.costSol.isFinite() || pos.costSol <= 0.0) return null
        // Skip synthetic bases where entryPrice is not directly comparable to
        // USD notional. The rebase authority handles those separately.
        val src = pos.entryPriceSource.uppercase()
        if (src.contains("SYNTH") || src.contains("PUMP_FUN_BC")) return null
        // These legacy/carry sources are explicitly SOL/token or otherwise
        // lack a proven USD/token unit. They may preserve inventory, but this
        // USD-notional invariant cannot classify them.
        if (src.contains("DERIVED_CARRY_COST_QTY_6631") ||
            src.contains("DURABLE_CARRY_COST_QTY_REPAIR_6519") ||
            src.contains("REPLAY_CARRY") || src.contains("RECOVERED_CARRY") ||
            src.contains("OPEN_POSITION_DERIVED_FROM_COST_QTY_6631") ||
            src.contains("DERIVED_FROM_COST")
        ) return null
        val qtyNotionalUsd = pos.qtyToken * pos.entryPrice
        if (!qtyNotionalUsd.isFinite() || qtyNotionalUsd <= 0.0) return null
        // impliedSolPriceUsd = notional_usd / cost_sol. If entry is genuinely
        // USD/token this equals the SOL price at buy — physically bounded.
        // If entry was mistakenly stored as SOL/token, the ratio collapses
        // to ~1 (or 1×slippage), well below MIN_PLAUSIBLE_SOL_USD.
        val impliedSolPriceUsd = qtyNotionalUsd / pos.costSol.coerceAtLeast(1e-18)
        val ok = impliedSolPriceUsd.isFinite() &&
            impliedSolPriceUsd in MIN_PLAUSIBLE_SOL_USD..MAX_PLAUSIBLE_SOL_USD
        val reason = if (ok) "economic_notional_ok_impliedSolUsd=$impliedSolPriceUsd"
            else "economic_notional_mismatch impliedSolUsd=$impliedSolPriceUsd band=[$MIN_PLAUSIBLE_SOL_USD,$MAX_PLAUSIBLE_SOL_USD] qtyNotional=$qtyNotionalUsd costSol=${pos.costSol}"
        return InvariantCheck(
            ok = ok,
            ratio = impliedSolPriceUsd,
            qtyNotionalUsd = qtyNotionalUsd,
            costNotionalUsd = 0.0, // not comparable without runtime SOL price
            reason = reason,
        )
    }

    private const val MIN_PLAUSIBLE_SOL_USD = 5.0
    private const val MAX_PLAUSIBLE_SOL_USD = 10_000.0

    fun reconstructFromCanonical(mint: String, pos: Position): Position? {
        val canonical = CanonicalPositionAuthority6441.openPositions()
            .filter { it.mint == mint && it.mode == if (pos.isPaperPosition) "paper" else "live" }
            .maxByOrNull { it.lastMutationMs } ?: return null
        if (canonical.quantityScale !in 0..18 || canonical.remainingQtyRaw <= java.math.BigInteger.ZERO) return null
        val qty = canonical.remainingQtyRaw.toBigDecimal().movePointLeft(canonical.quantityScale).toDouble()
        val cost = (canonical.entryCostSol - canonical.soldCostBasisSol).coerceAtLeast(0.0)
        if (!qty.isFinite() || qty <= 0.0 || !cost.isFinite() || cost <= 0.0) return null
        val reconstructed6539 = pos.copy(
            qtyToken = qty,
            costSol = cost,
            entryPrice = canonical.entryPriceUsd.takeIf { it.isFinite() && it > 0.0 } ?: pos.entryPrice,
            entryPriceSource = canonical.entryPriceSource.ifBlank { pos.entryPriceSource },
            entryPoolAddress = canonical.entryPoolAddress.ifBlank { pos.entryPoolAddress },
        )
        // V5.0.6539 §CORRUPTED_CANONICAL_REFUSE_RECONSTRUCT — operator
        // mandate: "DO NOT reconstruct these from their current runtime
        // qty/entryPrice. They contain SOL/token contamination." Even
        // reading from the canonical row is unsafe when the canonical row
        // itself was minted with the same wrong math (pre-6509 paperBuy).
        // Verify the reconstructed row satisfies the economic invariant;
        // if it does not, refuse reconstruction so the row stays
        // quarantined instead of being silently "repaired" back into the
        // ledger.
        val econ6539 = try { economicNotionalCheck6537(reconstructed6539) } catch (_: Throwable) { null }
        if (econ6539 != null && !econ6539.ok) {
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_RECONSTRUCT_REFUSED_ECONOMIC_INVARIANT_6539",
                    "mint=${mint.take(10)} reason=${econ6539.reason} " +
                        "action=keep_quarantined_not_repaired",
                )
                PipelineHealthCollector.labelInc("CANONICAL_RECONSTRUCT_REFUSED_6539")
            } catch (_: Throwable) {}
            return null
        }
        return reconstructed6539
    }

    /**
     * Convenience: validate and, on failure, atomically route the
     * mint to permanent quarantine (learning + equity exclusion).
     * Returns true iff the position passes.
     */
    fun validateAndQuarantine(mint: String, pos: Position): Boolean {
        val c = check(mint, pos)
        if (!c.ok) markInvariantBroken(mint, c.reason)
        return c.ok
    }

    fun markInvariantBroken(mint: String, reason: String) {
        if (mint.isBlank()) return
        if (quarantined.putIfAbsent(mint, reason) != null) return
        breaks.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "QUANTITY_INVARIANT_BROKEN_6500",
                "mint=${mint.take(10)} reason=$reason",
            )
            PipelineHealthCollector.labelInc("QUANTITY_INVARIANT_BROKEN_6500")
        } catch (_: Throwable) {}
        // V5.0.6521: quantity projection mismatch is repairable and is NOT an orphan lot.
        // Learning remains protected by isQuarantined until canonical reconstruction succeeds.
    }

    fun isQuarantined(mint: String): Boolean {
        if (mint.isBlank()) return false
        return quarantined.containsKey(mint)
    }

    fun statusLine(): String =
        "validations=${validations.get()} breaks=${breaks.get()} quarantined=${quarantined.size}"

    fun release(mint: String) {
        if (mint.isBlank()) return
        quarantined.remove(mint)
    }

    internal fun resetForTest() {
        quarantined.clear()
        validations.set(0L); breaks.set(0L)
    }
}
