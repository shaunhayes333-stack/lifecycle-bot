# AATE 8852 — Selective six-hour runner opportunity study

Owner priority, 2026-11-18: find coins the hunters and lane brains genuinely
identify, not 15,000 undifferentiated births. For a selected coin, ask:
**what would a $10 entry have been worth after six hours, and what would a
signal-driven exit have captured?** A later reawakening can be selected again.

## Code path

- Discovery remains broad and cheap. MovingTokenRecheck8847 already dispatches
  newly accelerating/verified-buyer coins for an expedited native re-evaluation.
- SpecialistCandidateBooks7803.markReady (all 12 meme specialists) and
  CryptoStrategyCandidateBooks7803.ready (the crypto-native strategies and desk
  overlays) nominate one deduplicated mint into RunnerStudy8852. Only **READY**,
  not DISCOVERED, WATCHING or generic QUALIFIED. The existing brains still
  decide READY. A nomination is not FDG admission or a buy.
- Up to **256 distinct active mints** are followed; other READY requests are
  counted as capacity refusals. The first nomination freezes the clock. Other
  READY lanes share that market path. Re-nomination after a completed episode
  requires another READY after a 30-minute cooldown.
- ForwardReturnLabeler7731.tick reads existing typed market marks for these
  mints, and uses up to four low-priority requests per tick through the
  *existing* off-watch batch only when the selected mint is stale. No new
  WebSocket, RPC source, scanner, buy or exit authority was created.
- A fresh market mark inside 90 seconds sets the observed (not quoted) entry
  basis; a fresh mark at entry + six hours, within five minutes afterward,
  matures the outcome. Missing entry/outcome remains UNAVAILABLE, never a
  fabricated -100% return. A 10x+ ratio requires chain-independent price
  witnesses under the existing LabelEvidence8386 policy.
- The study logs a **$10 hold MARK**, observed peak MARK (not banked profit),
  and a price-only benchmark for the runner 25/18/67% ladder, -35% stop and
  35%-from-peak giveback. The last benchmark is suppressed when the observed
  path has gaps longer than five minutes. Actual dynamic metrics, holder
  integrity and sell quotes are *not* represented by this price-only proxy.
- The cohort/restoration checkpoint is saved alongside existing forward
  observations; every completed or unavailable episode goes into a separate
  append-only on-device JSONL journal. **Save report** now includes
  aate_runner_study_8852_*.jsonl, allowing per-lane selection denominators,
  missingness, $10 marked outcomes and selected-coin replay audits.

## Non-negotiable interpretation

Hold marked $10 = $10 × (end price / observed entry price) - modelled entry-size
round-trip cost. Both entry and exit values are marks. A shown $8,000 mark is
**not** proof the coin allowed a $10 route in at that price or that its pool
could pay an $8,000 exit after price impact, slippage, fees, a rug or route
failure. Every study row explicitly records no buy/sell quote and no realized
wallet P&L.

These paper cells never award live proof, sizing, FDG approval or permission to
ignore a real hard-risk exit. The original price/cost/wallet ledgers and
live/paper separation remain authoritative.

## Next validation before any policy promotion

1. Run the 8852 build, full historical suite, runtime smoke and release gate.
   Inspect entry coverage, unavailable fraction and off-watch contention
   on-device. The current 8848 uploads cannot validate six-hour returns:
   their launch export ends at the first hour.
2. Add actual $10 **entry and full-quantity sell quotes** for selected mints
   where routable, without making quotes a dependency of the live decision loop.
   Attribute every quote to venue/pool/chain, source time and quote depth.
3. Mirror the **actual** metrics/structure/safety supervisor in paper research
   (the 8852 price-only ladder is just a baseline), replay missed-runner versus
   early-exit decisions and compare realized-equivalent proceeds. Include
   rejected-but-natively-READY, bought, and reawakening cohorts.
4. Walk-forward by coin and decision period, keep unavailable and duplicate
   mint observations out of falsely independent winner counts. Require
   meaningful coverage and out-of-sample evidence (including the owner's
   historic six-hour n≥8, mean+SE≥0 floor where applicable), not one lucky
   800x anecdote, before granting any live policy change.
