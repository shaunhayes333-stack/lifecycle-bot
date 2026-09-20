package com.lifecyclebot.v3.sizing

import com.lifecyclebot.v3.core.V3BotMode
import com.lifecyclebot.v3.core.DecisionBand
import com.lifecyclebot.v3.core.TradingConfigV3
import com.lifecyclebot.v3.scanner.CandidateSnapshot

/**
 * V3 Wallet Snapshot
 */
data class WalletSnapshot(
    val totalSol: Double,
    val tradeableSol: Double
)

/**
 * V3 Portfolio Risk State
 */
data class PortfolioRiskState(
    val recentDrawdownPct: Double = 0.0
)

/**
 * V3 Size Result
 */
data class SizeResult(
    val sizeSol: Double
)

/**
 * V3 Smart Sizer
 * Confidence-adjusted position sizing
 * 
 * V3 SELECTIVITY TUNING:
 * - Probe sizes reduced 0.4-0.6x for low-confidence EXECUTE_SMALL
 * - C-grade multiplier added for below-threshold setups
 * - AI degradation reduces size
 */
class SmartSizerV3(
    private val config: TradingConfigV3
) {
    private companion object {
        /**
         * V5.0.7127 — the live floor band. See the derivation at the use site.
         *
         * These are PRIVATE on purpose. The floor is a computed function of
         * balance and SOL price, not a number other files should copy; a second
         * reader holding its own idea of "the minimum live trade" is exactly the
         * drift this session has spent a dozen builds removing.
         */

        /** Smallest fill a DEX will actually route, expressed in dollars. From
         *  V5.0.6269's own finding: "pump.fun tokens simply have no executable
         *  route below ~$5". Converted at the live SOL price each call, so the
         *  floor tracks the market instead of freezing at one exchange rate. */
        const val LIVE_ROUTABLE_MIN_USD_7127 = 5.0

        /** Hard lower bound on the converted routable minimum. Guards against a
         *  bad or spiking SOL price producing a floor small enough to reinstate
         *  the ROUTE_FAILED dust that V5.0.6269 was built to stop. */
        const val LIVE_FLOOR_ABSOLUTE_MIN_SOL_7127 = 0.010

        /** The historical fixed floor, retained as the band's CEILING so a
         *  funded wallet sizes exactly as it did before this change. */
        const val LIVE_FLOOR_CEILING_SOL_7127 = 0.05

        /** Used only when the SOL price is unknown. Falls back to the old
         *  constant rather than guessing a cheaper floor we cannot justify. */
        const val LIVE_FLOOR_FALLBACK_SOL_7127 = 0.05

        /** The floor tracks this share of tradeable balance between the routable
         *  minimum and the ceiling. 10% keeps a floor-promoted trade in the same
         *  proportion the lane allocations already target. */
        const val LIVE_FLOOR_WALLET_PCT_7127 = 0.10

        /** A single floor-promoted trade may never exceed this share of tradeable
         *  balance. Without it, a small wallet would be forced to concentrate
         *  most of itself into one memecoin just to clear a routing minimum. */
        const val LIVE_FLOOR_MAX_WALLET_SHARE_7127 = 0.25
    }

    /**
     * Compute position size based on:
     * - Decision band
     * - Confidence level
     * - Liquidity
     * - Drawdown state
     * - Bot mode (paper/learning reduces size)
     * - C-grade penalty (low score = smaller size)
     */
    fun compute(
        band: DecisionBand,
        wallet: WalletSnapshot,
        confidence: Int,
        candidate: CandidateSnapshot,
        risk: PortfolioRiskState,
        mode: V3BotMode
    ): SizeResult {
        val tradeable = wallet.tradeableSol
        
        // V5.0.3921 — LIVE-MODE SIZE PROMOTION. Operator dump V5.0.3922
        // showed live trades landing at ~0.0095 SOL (~$1.50 — too small to
        // self-sustain after Solana fees + 0.5% fee skim). Root cause:
        // EXECUTE_SMALL basePct was capped at 3.0% of tradeable, compounded
        // with confMult≤0.55 + probeMult=0.50 + liqMult≤0.40 to deliver
        // <1% of wallet. Promote LIVE-mode EXECUTE_SMALL to 5% basePct and
        // drop the 0.50 probe shrink (probe rationale was for LEARNING /
        // PAPER, not real-money). PAPER / LEARNING modes are unchanged so
        // backtests remain conservative.
        val isLive = mode == V3BotMode.LIVE
        // Base percentage by band
        val basePct = when (band) {
            DecisionBand.EXECUTE_SMALL -> if (isLive) maxOf(config.maxSmallSizePct.coerceAtMost(0.05), 0.05)
                                          else config.maxSmallSizePct.coerceAtMost(0.03)
            DecisionBand.EXECUTE_STANDARD -> if (isLive) 0.08 else 0.06
            DecisionBand.EXECUTE_AGGRESSIVE -> if (isLive) 0.12 else 0.09
            else -> 0.0
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 SELECTIVITY: Confidence multiplier (tightened for low confidence)
        // 
        // Low confidence = much smaller size
        // - < 30: 0.40x (was 0.60)
        // - < 40: 0.55x (new tier)
        // - < 50: 0.75x (was 0.85)
        // ═══════════════════════════════════════════════════════════════════
        val confMult = when {
            confidence < 30 -> 0.40  // Very low confidence = tiny probe
            confidence < 40 -> 0.55  // Low confidence = reduced probe
            confidence < 50 -> 0.75  // Below average = smaller size
            confidence < 65 -> 1.00  // Normal
            else -> 1.10             // High confidence = slight boost
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 SELECTIVITY: EXECUTE_SMALL probe multiplier
        // 
        // EXECUTE_SMALL is already a "probe" tier, so reduce further:
        // - Apply 0.5x multiplier for probe trades
        // - This makes probes truly tiny (learning, not risking)
        // ═══════════════════════════════════════════════════════════════════
        val probeMult = if (band == DecisionBand.EXECUTE_SMALL) {
            0.50  // Probe trades are half the normal EXECUTE_SMALL size
        } else {
            1.00
        }
        
        // Liquidity multiplier (tightened for low liquidity)
        val liqMult = when {
            candidate.liquidityUsd < 3_000 -> 0.40   // Very low liquidity = tiny
            candidate.liquidityUsd < 7_000 -> 0.60   // Low liquidity = reduced
            candidate.liquidityUsd < 15_000 -> 0.80  // Below average
            candidate.liquidityUsd < 40_000 -> 1.00  // Normal
            else -> 1.05                              // High liquidity = slight boost
        }
        
        // Drawdown multiplier
        val ddMult = when {
            risk.recentDrawdownPct >= 20.0 -> 0.50
            risk.recentDrawdownPct >= 10.0 -> 0.70
            else -> 1.00
        }
        
        // Learning mode multiplier
        val learningMult = if (mode == V3BotMode.PAPER || mode == V3BotMode.LEARNING) {
            config.paperLearningSizeMult
        } else {
            1.00
        }
        
        // V3 Confidence Config size multiplier (user-adjustable)
        val v3ConfigMult = try {
            com.lifecyclebot.engine.V3ConfidenceConfig.getSizeMultiplier()
        } catch (e: Exception) {
            1.00
        }
        
        // Final size calculation (includes probe multiplier)
        val rawSize = tradeable * basePct * confMult * probeMult * liqMult * ddMult * learningMult * v3ConfigMult
        val cappedSize = rawSize.coerceAtLeast(0.0).coerceAtMost(tradeable * config.maxAggressiveSizePct)

        // V5.0.6269 — HARD NO-DUST FLOOR (operator directive:
        // "no stupid dust size trades ffs!"). Op-report V5.0.6268 showed
        // CHILLINU sized at 0.0062 SOL sent to Jupiter which returned
        // ROUTE_FAILED_NO_OPEN_COMMITTED — pump.fun tokens simply have no
        // executable route below ~$5 (~0.03 SOL). Every one of these attempts
        // burns compute, watchlist attention, and Jupiter quota for zero
        // return. Refuse the trade entirely when the stacked size would be
        // dust. This lets the bot wait for higher-conviction / better-liquidity
        // candidates that CAN actually round-trip instead of hemorrhaging
        // routing attempts on tiny probes. PAPER path still allowed to sub-cent
        // sizes because the mock engine can always fill (learning surface).
        // V5.0.6269 → V5.0.6271 evolution.
        //
        // V5.0.6269 rationale: block sub-0.05 SOL live trades outright because
        // Jupiter returned ROUTE_FAILED_NO_OPEN_COMMITTED on the CHILLINU
        // 0.0062 SOL attempt.
        //
        // V5.0.6271 correction after op-report showed only 2 trades in 30 min:
        // the block was TOO aggressive — with a 0.6 SOL wallet, the stacked-
        // multiplier math produces sub-0.05 SOL for every EXECUTE_SMALL
        // conviction band (score<60 / conf<70%), i.e. ~80% of the funnel got
        // hard-blocked and the bot sat idle. The real issue was DUST sent to
        // Jupiter — not the fact that low-conviction candidates were sized
        // conservatively. So promote instead of block: if V3 already said
        // EXECUTE (this candidate passed lane + safety + FDG + confidence
        // gates upstream), snap the size UP to the 0.05 SOL floor so Jupiter
        // gets a routable trade. Sub-dust (essentially zero) still blocks —
        // that only happens on a zero-liq or zero-tradeable input. Confidence
        // and score selectivity remain the operator's responsibility upstream.
        // V5.0.7127 — THE FLOOR IS NOW FLUID AND BALANCE-AWARE.
        //
        // Operator: "the live floor trade size is meant to be fluid and balance
        // aware!"
        //
        // The floor was the constant 0.05 SOL, and on a shrinking wallet it
        // failed in BOTH directions at once:
        //
        //   BLOCKED EVERYTHING. The operator's wallet reached 0.2922 SOL with
        //   per-lane targets of 0.0203-0.0275 SOL. Every candidate therefore
        //   sized below 0.05, needed promotion to 0.05, and the headroom test
        //   below refused it — SMART_SIZER_V3_DUST_BLOCK_NO_HEADROOM_6271 fired
        //   104 times in a 244-second session with EXEC=0. No round trips at all.
        //
        //   AND OVER-SIZED WHEN IT DID FIRE. Promoting to a fixed 0.05 on a
        //   0.29 SOL wallet is 17% of everything in one memecoin position. The
        //   constant was calibrated for a wallet several times larger, so as the
        //   balance fell it silently became a concentration risk right up until
        //   the moment it became a total block.
        //
        // A floor exists for ONE reason: routability. V5.0.6269 recorded it —
        // CHILLINU at 0.0062 SOL got ROUTE_FAILED_NO_OPEN_COMMITTED, and
        // "pump.fun tokens simply have no executable route below ~$5". That is a
        // statement about DOLLARS ON A DEX, not about a SOL constant, so it is
        // now expressed as dollars and converted at the live SOL price. The old
        // 0.05 is kept as the CEILING of the band, so a funded wallet behaves
        // exactly as it does today and nothing about this change loosens sizing
        // for an operator who has capital.
        //
        // Three bounds, each doing one job:
        //   routable minimum — below this a DEX will not fill; sending it burns
        //                      gas for a guaranteed failure
        //   wallet share     — one floor-promoted trade may not exceed a quarter
        //                      of tradeable balance, so a small wallet is never
        //                      concentrated into a single position to satisfy a
        //                      routing minimum
        //   old 0.05 ceiling — unchanged behaviour once the wallet is funded
        //
        // The hard block REMAINS, but now it only fires when the routable
        // minimum genuinely cannot be afforded at a safe concentration. That is
        // a real economic refusal rather than an artefact of a stale constant.
        val solUsd7127 = try {
            com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
        } catch (_: Throwable) { 0.0 }
        // The USD->SOL division goes through the single authority for it.
        // economic_units_scan rejected the first version of this line for doing
        // the divide inline, which was the right call: 7029 omitted this divisor
        // and 7057 inverted it, and a sizing floor is not the place to become the
        // sixth copy. usdToSol returns NaN when the price is unusable, which is
        // the signal to fall back rather than size on a bad number.
        val routableRawSol7127 = try {
            com.lifecyclebot.engine.truth.EconomicUnitInvariant7061
                .usdToSol(LIVE_ROUTABLE_MIN_USD_7127, solUsd7127)
        } catch (_: Throwable) { Double.NaN }
        val routableMinSol7127 = if (routableRawSol7127.isFinite() && routableRawSol7127 > 0.0) {
            routableRawSol7127.coerceIn(LIVE_FLOOR_ABSOLUTE_MIN_SOL_7127, LIVE_FLOOR_CEILING_SOL_7127)
        } else {
            // V5.0.7142 — A MISSING PRICE MUST NOT INFLATE THE FLOOR.
            //
            // 7127 fell back to LIVE_FLOOR_FALLBACK_SOL_7127 (0.05) here, "the
            // historical constant rather than guessing a cheaper floor we
            // cannot justify". That reasoning is backwards: 0.05 is the LARGEST
            // floor this function can produce, so an unknown SOL price
            // maximised the chance of the hard refusal below. On the operator's
            // 5.0.7140 device that refusal outran the promotion it exists to
            // enable — SMART_SIZER_V3_DUST_BLOCK_NO_HEADROOM_6271=281 against
            // SMART_SIZER_V3_DUST_PROMOTED_6271=139 — on a ~0.3 SOL wallet
            // where 0.05 exceeds a quarter of tradeable the moment open
            // positions take their share.
            //
            // The SOL price is not always loaded when sizing runs:
            // sol_price_refresh is a maintenance task measured at up to 5559ms
            // in the same snapshot, and SOL_PRICE_RESTORED_7042 fires once at
            // boot. So this branch is not an exotic edge; it is every sizing
            // call in the warm-up window.
            //
            // Not knowing the price is an ABSENCE. It justifies declining to
            // shrink the floor, which is what the absolute minimum already is —
            // it does not justify raising it to the ceiling. The absolute
            // minimum is the one value here chosen as the smallest trade that
            // can still route, so it is the honest answer to "how small may
            // this be" when the conversion is unavailable.
            LIVE_FLOOR_ABSOLUTE_MIN_SOL_7127
        }
        val liveNoDustFloor6269 = (tradeable * LIVE_FLOOR_WALLET_PCT_7127)
            .coerceIn(routableMinSol7127, LIVE_FLOOR_CEILING_SOL_7127)
        // V5.0.7142 — refuse on the ROUTABLE minimum, clamp on the percentage.
        //
        // 7127's own comment states the intent exactly: "The hard block REMAINS,
        // but now it only fires when the routable minimum genuinely cannot be
        // afforded at a safe concentration." The code did not do that. It tested
        // liveNoDustFloor6269, which is max(tradeable x 10%, routableMin) capped
        // at the ceiling — so a floor inflated by the PERCENTAGE arm, or by the
        // missing-price fallback above, produced the same hard zero as a genuine
        // economic refusal.
        //
        // The two cases are not alike. If the smallest trade that can actually
        // route exceeds a quarter of the wallet, refusing is right: the
        // alternatives are a route that cannot fill or one position holding most
        // of the balance. But if only the percentage arm is too large, the safe
        // answer is the safe share itself — still routable, still concentrated
        // no further than the guard allows, and a trade rather than a silence.
        val safeShareCap7142 = tradeable * LIVE_FLOOR_MAX_WALLET_SHARE_7127
        val effectiveSize = if (isLive && cappedSize > 0.0 && cappedSize < liveNoDustFloor6269) {
            if (routableMinSol7127 > safeShareCap7142) {
                // The smallest routable trade would be too large a share of this
                // wallet. Refusing is correct: the alternative is either a route
                // that cannot fill or a single position holding most of the
                // balance. Named so the operator sees WHICH bound refused.
                try {
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SMART_SIZER_V3_DUST_BLOCK_NO_HEADROOM_6271")
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LIVE_FLOOR_BLOCK_ROUTABLE_MIN_EXCEEDS_SHARE_7127")
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "SMART_SIZER_V3_DUST_BLOCK_NO_HEADROOM_6271",
                        "band=$band conf=$confidence tradeable=${"%.4f".format(tradeable)} floor=${"%.4f".format(liveNoDustFloor6269)} routableMin=${"%.4f".format(routableMinSol7127)} solUsd=${"%.2f".format(solUsd7127)} maxShare=$LIVE_FLOOR_MAX_WALLET_SHARE_7127 note=routable_minimum_exceeds_safe_wallet_share"
                    )
                } catch (_: Throwable) {}
                return SizeResult(sizeSol = 0.0)
            }
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SMART_SIZER_V3_DUST_PROMOTED_6271")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "SMART_SIZER_V3_DUST_PROMOTED_6271",
                    "band=$band conf=$confidence liq=${candidate.liquidityUsd.toInt()} raw=${"%.4f".format(cappedSize)} promotedTo=${"%.4f".format(liveNoDustFloor6269)} tradeable=${"%.4f".format(tradeable)} routableMin=${"%.4f".format(routableMinSol7127)} solUsd=${"%.2f".format(solUsd7127)} sharePct=${"%.1f".format(if (tradeable > 0.0) liveNoDustFloor6269 / tradeable * 100.0 else 0.0)} note=v3_execute_gate_passed_promote_to_balance_aware_floor_7127"
                )
            } catch (_: Throwable) {}
            // V5.0.7142 — the percentage arm may not exceed the safe share.
            // Promote to the floor, but never past the concentration guard;
            // the routable minimum has already been proven affordable above.
            val promoted7142 = liveNoDustFloor6269.coerceAtMost(
                maxOf(safeShareCap7142, routableMinSol7127),
            )
            if (promoted7142 < liveNoDustFloor6269) try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LIVE_FLOOR_CLAMPED_TO_SAFE_SHARE_7142")
            } catch (_: Throwable) {}
            promoted7142
        } else cappedSize

        return SizeResult(sizeSol = effectiveSize)
    }
}
