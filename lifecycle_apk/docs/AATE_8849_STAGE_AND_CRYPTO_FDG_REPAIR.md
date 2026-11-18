# AATE 5.0.8849 — entry-stage continuity and crypto FDG evidence

## What the 8848 evidence actually says

The attached 8848 live report (about 14 minutes) recorded 1 live win / 11 losses,
profit factor 0.04, and negative realised expectancy. The crypto producer elected
164 candidates and submitted all 164 to its canonical FDG, but there were zero
approved intents or executed crypto opens. The pipeline does not expose a
per-cause breakdown of those 164 blocks. **Neither approval rate nor realised
profitability can be inferred from the paper book.**

The 8848 edge export is a set of forward *candidate* labels, not confirmed
fills. Many meme entry cohorts have negative net forward results, especially
strong runners bought at the top or after a failed pullback. Winning prices on
a short-lived quote are also not equivalent to a sellable, fee-adjusted fill.

## Source repairs, not a permission override

1. **Current phase binds before an old positive cell.** `TradePlan7739` now
   checks fresh observed chart dangers before both the chart-reader fast path
   and the historical-evidence early-launch fast path. Sell-dominant tape,
   confirmed continuing declines, unreclaimed/deep pullbacks, and extension
   into the high return WAIT. Unknown/short bars are not falsely classified as
   a downtrend; a later new-buyer/price recheck can retry. Hard safety and
   the single FDG remain unchanged. This specifically repairs the bypass in
   `optionalChartForAdmit(specific=true)`.
2. **Predict crypto with the desk that chose it.** The cross-asset oracle and
   forward fallback read the same `CRYPTO_<elected desk>` lane whose outcomes
   are labelled by the Cortex, rather than a generic `CRYPTO` with no matching
   learning history. The desk's actual setup is forwarded as a phase when
   recorded. Unknown phase stays unknown, and a score does not invent a setup
   quality grade. The sealed CRYPTO execution owner, route, mode and venue
   remain exactly as before.
3. **Real confidence, not full-size fiction.** Crypto's canonical shaping
   receives its 0–100 confidence converted to 0–1, instead of the previous
   fixed 1.0. Exposure/size floors are still enforced downstream.
4. **Expose the crypto FDG choke.** The next cross-asset funnel includes the
   ten most common named crypto canonical refusal categories and counts. This
   distinguishes genuine route, SOL-wallet, structural, expectancy, risk and
   sizing failures. Logging never changes a verdict.
5. **Keep the initial swap opportunity observable.** The early reclaim family
   now evaluates actual executed, same-venue prints even when the slower
   aggregate 2/5-minute flow read is not ready. It separately requires at
   least four executed buys, net buyer SOL and >=58% SOL buy share in the
   trailing 120 seconds, in addition to its already-required actual impulse,
   dip, held higher low and fresh reclaim. Price-only curve snapshots cannot
   supply the confirmation. This is an earlier **paper and forward-label
   discovery** opportunity, not an automatic live-entry bypass.
6. **Identify missing early-buyer proof.** The signed 60-second demand signal's
   diagnostics now break down unknown birth, age outside launch, missing
   sustained demand, missing buyer counts/share and absent acceleration. The
   8848 capture had 421 fresh signed-flow reads and zero family signals, with
   only 42/768 birth proofs resolved; the next capture will show exactly how
   those failures intersect instead of guessing that unknown birth is young.
7. **One entry-stage snapshot per candidate.** The live entry's bar read and
   its stage check now consume the same bounded snapshot. This removes the
   second chart construction introduced in the first 8849 fix; it prevents
   self-contradictory decisions on a changing quote and reduces buy-path work.

## Acceptance: real trading, not paper ROI

- **Selection:** first-minute independent prints and dated buyer flow reach
  the evaluating lane; out-of-sample *executable, cost-adjusted* forward
  results must be positive for the selected stage, with enough resolved marks
  to rule out survivor/outlier bias.
- **Timing:** sampled executed buys are not stamped at unretraced highs,
  descending pullbacks or sell-dominated phases. Rejected WAIT candidates
  are re-examined after fresh positive evidence; no permanent watchlist loss.
- **FDG-to-fill:** for live-routable, viable crypto candidates, count each
  stage `candidate → submit → reasoned FDG verdict → size → immutable intent →
  dispatch → settled buy`. Zero allows are acceptable only if reasons show the
  candidate set did not clear economics or safety; artificially admitting
  bad entries is not throughput.
- **Speed:** measure p50/p95 time from signal to buy broadcast and stop trigger
  to protective sell broadcast and landed finality. 8848's observed buy
  start-to-broadcast mean was about 3.1 seconds; its protective stop end-to-end
  mean was about 10.7 seconds. Neither is a verified new-version improvement.
- **Profit:** use a materially sized sample of *live, settled, round-trip*
  positions; win rate, profit factor above 1, positive realised SOL after all
  network/venue/app/impact costs and stable out-of-sample selection. A green
  APK or synthetic replay alone is not this acceptance.

The new independent early-swap detector increases the **opportunities to
measure a real reclaim**, not permission to spend capital on an unproven
setup. Profitability is not implied by a signal count. In particular, the
8848 60-minute forward results were negative for the broad admitted meme
cohorts; rescuing throughput by relaxing the FDG would buy more losers.

No profitability claim is made before a new installed 8849 run supplies those
independent receipts. No live orders can be dispatched by CI.
