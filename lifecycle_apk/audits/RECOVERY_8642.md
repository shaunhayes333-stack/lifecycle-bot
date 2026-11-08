# AATE recovery 8642 — learning integrity, real runners, one profit owner

Runtime evidence: `aate_pipeline_20261109_003238_745.txt` (5.0.8632),
`AATE_Live_Trades_LIVE_20261109_0032.csv`,
`aate_edge_dataset_8537_1794148360031.jsonl.txt`, and
`aate_launch_hour_1794148362792.jsonl.txt`.
History: `audits/audit_8629/audit_1983.md` and `audit_8096.md`.

## Diagnosed, repaired at source

1. **A fabricated historical learning authority.** `EdgeModel8537.kt` read a `DIM`-only binary containing training statistics
   and walk-forward verdicts accumulated before false-price and seed-basis checks were introduced. On 8632, CORE reported a
   +375% mean selected outcome and was labelled `AUTHORITY`, even though the attached dataset contains impossible returns
   of +1,406,280,000% on a $2.96m-cap token (and +518,638% at $112k). A process counter of zero *new* implausible labels
   cannot validate the old binary. **8642** versions the binary, rebuilds ridge evidence once from *filled* rows with the
   correct strategy horizon and plausible return, preserves the original JSONL dataset, discards the old tainted OOS verdicts,
   and ignores still-pending pre-8642 prediction scores for OOS authority. Outcomes still train if valid. Cortex stays disabled
   in live authority; the edge model must now earn OOS authority on new graded decisions.

2. **A single-feature veto inverts a breakout requirement.** `LaneAuthority8577.memeBuysTheHigh8582` refused any meme buy
   whenever buy pressure was >= 60%, irrespective of peak location. The `MemeStrategies8546.BASE_BREAKOUT_FLOW` trigger
   requires at least 60% executed buy-side share; the live gate vetoed precisely that healthy flow condition.
   The November 9 *filled, 15-minute* cohort did not establish buy pressure as a standalone discriminator.
   **8642** keeps the actual high/peak rule (at least 5% run, price at 97%+ of peak) while removing the buy-pressure-only
   veto. It is *not* a new automatic buy signal: the lane's setup, execution economics and safety still apply.

3. **Wrong units enter launch-hour peak evidence.** Both `TradeTape8100` and `HeliusCurveTape8354` previously passed `sol`
   and `tokenQty` to `LaunchHour8628.trade` without checking `sol/tokenQty` against the independently decoded curve price.
   A raw-token quantity can yield an impossible launch-hour peak. **8642** requires a positive, finite SOL/token print
   within 8x of the matching curve/pool price, rejects inconsistent units and exports a rejection count. This does not
   manufacture missing hour prices; feed coverage is still a separate production issue.

4. **Two profits owners race on the same runner.** Despite 8630 making `RunnerDoctrine8584` responsible for the 2x/5x/10x
   ladder and its trail, the 500ms `BotService` monitor could independently submit `PEAK_GIVEBACK_SLIP_6394`,
   `PEAK_CAPTURE_TRAIL_6394`, the legacy rapid drawdown and the 4301 peak lock. The live trade CSV contains
   `PEAK_GIVEBACK_SLIP_6394_peak12105_now-3` at a loss, highly suspicious as a phantom stored peak.
   **8642** stops those *profit* paths pre-empting the doctrine on its own meme lanes. Actual distribution, stops,
   catastrophe, manual, rug, liquidity and other safety exits remain. Other non-meme lanes are unchanged.

5. **The dataset compactor could exhaust the heap.** `EdgeModel8537.appendDataset` loaded the entire ~24MB JSONL file
   with `readLines()` for trimming every time the learned counter was a multiple of 5,000. **8642** checks once per
   milestone and copies only the retained tail by streaming, not by holding all rows in RAM.

## Tests and honesty checks

- `Aate8582BuyThePullbackTest`: high-peak refusal survives, buy pressure cannot independently veto breakout flow.
- `LaunchHour8628Test`: 10x *real* runner accepted, million-fold unit mistake rejected.
- `Aate8537EdgeModelTest`, `Aate8571LabelLossTest`: historic ghost, wrong horizon and unfilled observations excluded;
  pre-upgrade prediction lacks the new authority epoch.
- `Aate8584RunnerDoctrineTest`: static sell-path regression guard for the rival legacy peak exits.
- The Android build, all unit tests and emulator smoke must pass on GitHub Actions before a verified APK can be called ready.

## Not solved by this commit; required before asserting profitability

- First-hour labels: 96,539 of 98,421 export rows had zero hour prints and no row carried a complete priced hour outcome.
  Restore independent signed-trade/price sources and measure hourly coverage, especially missing PumpPortal stream access.
- Event families: all five `MemeStrategies8546` signal counters were zero on 8632; trace actual event flow, chart-bar age,
  candidates and per-family reject causes. Never backfill fake flow to force a positive setup.
- Held exits: 365 of 394 executable quote requests had no route; investigate `JUP_EXCEPTION_NO_FRESH_RESERVE`, independent
  pool reserve availability, hot holdings and retry/confirmation latency before claiming tighter real-world stops.
- Admission/strategy convergence: the 8629 audit still lists multiple downstream buy validators and dozens of old exit
  surfaces. This repair eliminates confirmed conflicts, **not** the entire architecture's duplicate decision graph.
- Profitability: 8632 had 19 recent live closes, 31.6% WR, PF 0.87, net losing. No static patch is proof of a profitable
  live strategy. Verify one continuous live-forward cohort including realised SOL after fees, meaningful 2x/5x captures,
  candidate selection age/source, confidence calibration, confirmed executable exits and a reproducible baseline.

This pass is a code repair, **not** a rollback to 1983 or 8096, a licence to ignore safety, or a claim that live profits are restored.
