# Expert Crypto Trader Cheat Sheet — AATE Foundation

This is the compact, machine-facing summary of [`FIELD_MANUAL.md`](FIELD_MANUAL.md).
It is an expert prior for trade one, not claimed historical edge. The Field
Manual remains the complete source of the rules; the runtime implementation is
`FieldManual7715`.

## Candidate card

Before a candidate can justify risk, identify its exact mint/pair and venue,
classify its regime, name the setup, verify the trigger, state invalidation and
exit logic, and estimate round-trip costs at the intended size. A ticker,
market-cap print, last price, or route hint is not proof of identity, liquidity,
or an executable exit.

| Setup | Required evidence | Invalidation |
|---|---|---|
| Trend pullback | Higher low or reclaim in a supported trend | Pullback low / failed reclaim |
| Base breakout | Acceptance beyond a range, preferably a held retest | Return into range without reclaim |
| Range reversion | Entry near a defended range edge | Range boundary fails |
| Sweep and reclaim | Failed breakdown followed by a held reclaim | Sweep low / failed reclaim |
| Momentum continuation | Participation persists after a fresh continuation trigger | Continuation pivot fails |
| Launch / event | Identity, supply, sell behavior, depth and route are checked | Launch structure, liquidity, identity or route fails |
| Relative strength | Defined peer/benchmark outperformance and a valid trigger | Relative structure or benchmark thesis fails |
| Carry / arbitrage / derivatives | Owner supplies venue-specific costs, execution and liquidation rules | Spread, hedge, venue or margin premise fails |

## Decision discipline

- Missing identity, exitability, trigger, invalidation or cost evidence means
  `WAIT`/`PASS`; missing data never becomes a bullish forecast.
- Calculate net expected value after entry and exit fees, spread, impact,
  slippage, network/priority costs and expected route failure. Use the actual
  intended size. If cost consumes the plausible move, pass or reduce exposure.
- Size from the distance to invalidation and its all-in cost. Keep route,
  wallet, duplicate-position, accounting, and exit protections independent
  from learned performance opinions.
- Count one finalized, reconciled outcome per position. Exclude partial rows
  from terminal win/loss samples. Keep LIVE, PAPER, and SHADOW evidence
  separate; paper success does not authorize live risk.
- Treat the setup names and playbook rules as conditional hypotheses. Fade
  the foundation prior as relevant finalized evidence accumulates; do not
  report it as measured EV or proven edge.

## Runtime wiring

`FieldManual7715` builds the plan card and retains its live/paper decision.
The cold-start prior is passed from the strategy candidate into
`LearnedAdmissionInputs6909`, then joins the existing
`PredictiveEntryOracle6915` brain opinions and `SuperSsiFusion7632` consensus.
Its bounded contribution is shown by the `fieldManualFoundation(...)` label in
the oracle contribution list and `FIELD_MANUAL_FUSION_PRIOR_7715` counter.
The existing manual execution verdict and hard safety authorities still apply
independently.

The stack earns authority only when position-bound, mode-matched,
net-of-cost finalized outcomes show that a particular source, lane, setup,
regime, or tactic improves results out of sample.
