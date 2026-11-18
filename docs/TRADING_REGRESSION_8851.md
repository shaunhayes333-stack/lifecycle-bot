# 8851 — historical trading-regression repair and acceptance

Mainline benchmark: APK 8096 (`cddf86c17`), APK 8114 (`379ad2fa0`). The earlier 1942 APK has not
been located in the available repository/release history; do not impute its implementation or PnL.

## What the recorded bot evidence establishes

| Read | Older 8096/8114 | Observed 8848 sample | Engineering implication |
| --- | --- | --- | --- |
| Canonical realised wallet | 8096 PF 2.15, ~2.64x balance; 8114 32W/47L, PF 1.54, 0.1952→0.4339 SOL | 1W/11L, PF ~0.04, ~−0.0036 SOL per trade | CI is not a profit test; compare settled trades and wallet deltas |
| Runner capture | WAITIE observed +1459%, banked +721% in 8096 sample | 111 of 112 observed 4x runners missed in 8848 window (varied causes) | preserve real early setups and their runner exit ladders; do not bypass rug/exit-safe floors |
| CORE | independently electable ensemble in 8096 | native candidate ~261 / qualified ~193 / ownerSelected ~1 / opened 0 in 8848 | align fresh native CORE ensemble with cycle election; keep fresh FDG/safety/size |
| CASHGEN | designed short cash-generation lane | 5 live closes averaged ~−4.7%; forward admitted cohorts also negative | buying wrong phase is a bigger issue than extending CASHGEN losers' holds |
| Labels | older 1m/5m/60m books | runner Cortex target 15m, but several deadlines and feeds still used old 60m; 5m interim was staged as first outcome | grade each timestamp separately and keep the true target horizon consistent |

Forward/paper mark returns are not real fills. These windows are short, highly volatile and not causal
proof of a profitable strategy. Historic high balances likewise do not guarantee a repeat.

## Changes

1. The runner's five-minute read is **interim only**, not prematurely staged as the Cortex's
   immutable terminal entry value. All due checks, expiry/recovery windows, horizon-pricer ranking
   and counts now use the actual 15m runner target (or persisted 60m target) rather than incorrectly
   waiting 60 minutes.
2. `TrajectoryGrades8851` grades the **same frozen setup/stage decision at 1m/5m/15m**, with
   separately checkpointed receipts, live market price proof and entry-fill/basis checks. A small
   horizon-concordant adjustment can order *already-fired* setups; it cannot grant an entry, alter
   position size or masquerade as realised wallet PnL. No later 15m price backfills a missed 1m.
3. Qualified survivors additionally receive real **1h/4h/24h** reads. The independent, priority
   horizon pricer now requests these checkpoints; the book separates them from the unconditional
   first 15 minutes to avoid survivorship bias. A previously corroborated runner remains eligible
   for follow-up despite giving back a portion of its peak if liquidity/mark/safety stay sound.
   Missing checkpoints are counted, not imputed from a later quote.
4. Only **accepted** DexScreener WS price updates reach the local candle binner (which feeds
   both `TokenState.history` and the forming `MarkBars7948`/chart), and the realtime opportunity
   sweep. The old path fed rejected lower-authority WS quotes to those stage inputs and even
   overwrote `tokenMap.priceUsd` while canonical price stayed chain-held. An untrusted quote
   can no longer invent an apparent peak, breakdown or continuation.
5. A fresh authoritative, named native CORE ensemble may be elected as the primary; the independent
   FDG, live-read, risk, executable-size and route checks are unchanged. This repairs the shadow-only
   mismatch without introducing CORE as an unrestricted rescue lane.
6. An already-sealed runner with independently verified +25% peak, fresh +15%+ price and actual
   confirmed continuation can temporarily hold through a *routine* scalp exit before +100%.
   Price reversals, missing continuation, stale evidence, tier-1/structural stops and existing
   loser/giveback/green-floor releases remain binding. Broad from-entry -40% holds for losing
   positions are **not** restored.

## Verification and acceptance

- CI: compile, new `Aate8851MultiHorizonAndWsDataTest`, existing cohort and exit/entry tests,
  signed APK assembly and runtime smoke. Check the exact GitHub main SHA, not the version string.
- On-device: label stats must show increasing, independently priced 1m/5m/15m and *qualified*
  1h/4h/24h cohorts. `FORWARD_HOUR_INTERIM_NOT_TARGET_8851`, `ENTRY_LIVE_WS_BAR_8851`,
  `CORE_NATIVE_CONSENSUS_OWNER_8851` and `LIFECYCLE_FIRMING_RUNNER_HELD_8851` prove the
  intended paths are actually used; check the refusal/expiry and per-lane funnel too.
- Live trading: verify scanner→hunter→native→FDG→ticket→broadcast→settled open→broadcast→settled
  close against on-chain fills and wallet balances. Compare stage at **decision, quote and fill**,
  entry latency, missed-runner reasons, CORE/CASHGEN opens, hold and stop classifications, net
  SOL/trade, win rate, profit factor, drawdown and high-multiple captures versus 8096/8114.
- Do not call the requested trading behavior restored on passing CI or forward paper labels.
  Only repeatable, on-chain settled profitability and real runner captures meet the operator's
  acceptance criterion. No live funds are moved by these code/test changes.
