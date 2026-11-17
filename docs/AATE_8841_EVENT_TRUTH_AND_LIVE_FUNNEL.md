# AATE 5.0.8841 — signed launch event integrity and live-funnel evidence

## Measured baseline: owner pipeline 2026-11-17 23:59, app 5.0.8840

- LIVE: wallet 0.3052 SOL, zero open positions, zero buy attempts; 1/9 wins in canonical closed trades (PF 0.05).
- Final decision gate: 0 allowed / 1,092 blocked. Most common groups: negative edge predictions 299,
  economics 269, missing data 281, safety 106, buying the high 50, structure 38.
- Scan/hunt and native lane qualification are active, but none reached a sealed live buy ticket.
- Fast launch prints feed many consumers before `TradeTape8100.note8285` deduplicates signed swaps.
- In `HeliusCurveTape8354.onTradeEvent8354`, a second provider's decoded trade previously updated
  `CurveReserves7951` before the witness/tape discarded it; it also triggered a scanner frame when
  `note8285` returned false. These are causal price/flow bugs, not an excuse to lower hard safety gates.

## Changes in this build

1. Claim one signed `(mint, transaction signature, side)` before launch-history, 1m bars, launch facts,
   manual reads, sniper, smart-money and paper exits. Identical duplicates reuse the witness verdict
   for the caller without re-publishing. A conflicting replay price gets `OUT_OF_BAND`.
2. Rejected first decodes never claim the identity; a valid independent provider can still rescue it.
   Unsigned reports remain distinguishable and cannot be guessed into a shared identity.
3. Preflight the reserves/executed size ratio and signed duplicate before writing curve reserves.
   Only successful canonical tape admission emits a Helius curve scanner frame.
4. `BuyerFlow8666` receives a signed event only after canonical tape signature dedup.
5. Bounded launch-identity LRU (30,000, trimmed under memory pressure); census, restart/reset and
   explicit duplicate/conflicting-decode telemetry in `tape8285`.

## Acceptance contract and limits

- Static and Kotlin regression suite must pass; installable APK produced by GitHub Actions from
  the exact branch commit. Green build does **not** establish profitable trades.
- On new device logs, look for `launchUnique8841`, `launchDup8841`, `launchConflict8841`, compare
  same-signed provider prints and verify price continuity and buyer-flow counts.
- Separately require nonzero LIVE `FDG_ALLOW`, `EXEC attempt`, `BUY ok` and eventually confirmed
  closes with positive after-cost expectancy across a meaningful independent sample.
- The negative learned-edge and execution-economics refusals in 8840 had predominantly negative
  labelled future returns; blindly removing them would turn zero trades into likely losses.
  Discovery ranking, missing-data repair, confirmation of runner opportunities and live selection
  need further measured evidence. No particular win rate or 600–1,000% trade can be guaranteed.
