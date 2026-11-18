# AATE 8853 — Selected runners: timestamped exit evidence and two-sided route quotes

Owner question: *If a hunter/brain picked this coin and I placed a $10 entry,
what was the value at six hours, and would the actual metrics have warned me
to exit first? Could an exit have sold the entire amount?*

## Cohort and evidence boundaries

- **Same selective READY enrolment as 8852**, from every native meme-specialist
  and crypto-strategy READY hook. Deduplicate by mint across lanes. A native
  reawakening can start a new six-hour episode after cooldown. No generic
  discovered, merely qualified, or 15,000-coin scanner stream is enrolled.
- The 8852 price-mark cohort stays capped at 256. No new market subscription
  or off-watch fetcher. Price marks remain the source of its six-hour outcomes.
- `RunnerMetrics8853.capture()` observes only that selected mint, only when a
  **new fresh independent price mark** is available. It checks source age:
  buy pressure and 5m movement <=60s, safety assessment <=120s, source-owned
  same-pool liquidity <=120s, and chart structure with a <=120s tape entry.
  Evidence rows retain the observed metric values, source times, price ratio,
  lane, selection clock and any first hypothetical exit candidate.
- Four deliberately conservative *candidate* signals: a witnessed hard safety
  block; verified same-pool liquidity at or below half the entry observation;
  a structure break with buyer share below 45% after a >=1.2x move; or price
  declining >=10% over five minutes plus buyer pressure below 35% after >=1.2x.
  These are **research candidates**, not reconstructed decisions from the live
  supervisor, and they cannot trigger a trade.
- A metrics-only six-hour comparison requires dense source-time-proven flow,
  price-change and safety snapshots throughout, no >5min mark/metric gaps,
  and endpoint coverage. If absent, its result is `METRIC_PATH_INCOMPLETE`,
  even if an isolated metric signal was observed. The $10 figure still uses
  observed market **marks**, not executable proceeds.

## Route witnesses, where technically applicable

- `RunnerRouteQuotes8853` nominates at most 24 of the same native-READY Solana
  mints, no route calls for cross-asset tickers or EVM addresses. A single
  daemon worker, bounded queue, >=3s between enqueues and <=2 attempts per
  side limit contention with execution. It is optional and never on any
  live buy, live sell, safety, scanner or strategy decision thread.
- It attempts a $10-equivalent SOL-to-token Jupiter **non-binding indicative
  quote** within 90s of native READY, then a token-to-SOL quote for exactly
  the quoted full buy quantity near six hours after the **received buy quote**.
  Quote journal rows include request/receipt times, actual raw amount,
  returned raw amount, router, first pool key, quoted price impact, SOL/USD,
  attempt counts and missing-data reasons. They intentionally do not claim
  the quoted price was filled or still available seconds later.
- A completed two-quote pair has `grossSellQuotedUsd`, but **no binding order,
  fill, fee-complete net, wallet proceeds, or realized P&L**. Gross buy and
  gross quoted sell values are **indicative**. A price-mark 800x and a pair of
  route quotes cannot establish that a $10-to-$8,000 opportunity was
  executable. Quote failures are `UNAVAILABLE`, not zero-return losers.
- Quote state is checkpointed independently as `runner_routes_8853` in the
  forward-labeler preference transaction. Quote results and metric snapshots
  append to the `runner_study_8852.jsonl` evidence file, included in the same
  Save report. The small checkpoint need not retain the full six-hour tape.

## Validation and limits

New unit coverage tests: invalid chain identity, source-freshness / partial
metric refusal, same-pool liquidity, hard safety, price-and-flow candidates,
six-hour quote quantity/timing, quote missingness, restart-preserved clocks,
dense metric signal-versus-hold replay, and incomplete metric refusal.

The route evidence is **Solana only**, and not all supported Solana venues are
guaranteed to route through Jupiter. The cross-chain/native crypto cohort
still receives the 8852 selected market-mark study and metric witness when
its token state contains fresh data. It is not labelled route-infeasible just
because Jupiter has no route. For a proper net executable comparison, next
add a taker-bound order / venue-specific sellability witness and full cost
accounting; then mirror and walk-forward-validate the **actual** exit engine
against the observational baseline across all lanes. No live-policy change
is authorized by this phase.
