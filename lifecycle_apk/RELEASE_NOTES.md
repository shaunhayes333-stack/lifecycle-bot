# AATE Release Notes

**AATE — Autonomous Algorithmic Trading Engine**

## Version 5.0.7288 (24 Sep 2026)

This release rolls up builds 7274 through 7288, all shipped on 24 Sep 2026. The main themes are more reliable exits, an entry oracle that has to earn its authority, and paper trading that charges real venue costs.

Live execution is still **paper-first**. Every trader runs in paper mode. Live mode covers only the lanes you enable, with stocks and forex quarantined, and non-meme live execution is staged.

---

### Execution & exits

- **Sells no longer block pricing (7288).** Sells are now dispatched off the 1 Hz mark loop, so a slow sell can't delay price updates for your other open positions.
- **Stall detection by sequence (7288).** The mark loop now detects a stall by tick sequence number, which is more reliable than the previous timing check.
- **Self-healing mark loop (7283).** The open-position mark loop is supervised. If it stops, it restarts itself.
- **Profit lock follows the peak (7282).** The profit lock now slides up toward each position's peak instead of staying at a fixed level. Winners get room to run, with gains protected along the way.
- **Sizing fixes (7281).** A lamport-precision sizing fix, plus per-rung backoff on the RPC ladder so a single slow endpoint doesn't hold up submission.
- **Realistic sizer cap (7280).** Positions are capped to what the market can realistically absorb on exit. A launch-chase floor stops the engine from chasing new launches with dust-sized entries.
- **Exit-sized entries (7278).** Entries are sized to what can actually be exited, and pricing can now come from the live trade stream.
- **Paid Helius first, fee-aware floor (7277).** Your paid Helius endpoint is tried before public fallbacks. The minimum position size now accounts for fees, so tiny trades that can't beat their own costs are skipped.
- **Fill-time basis for alts (7275).** Crypto alt positions now record their cost basis at actual fill time, which makes P&L on those lanes more accurate.
- **Dead-token exits (7274).** When the engine checks whether a token is dead before exiting, it now consults the live feeds first.
- **Crypto exposure cap fix (7288).** The crypto lane exposure cap is now enforced correctly.

### Oracle & learning

- **Binary Predictive Entry Oracle (7287).** For every candidate, the oracle returns **ADMIT** or **REFUSE**. It forecasts expectancy and win probability from the full trade journal, reading up to 5,000 closes both per lane and across the whole book.
- **Probe trades removed (7287).** The engine no longer opens small "probe" positions to test an idea. It either takes the trade or it doesn't.
- **The proven oracle binds (7287).** Once the oracle passes its Edge Proof on real closes (≥20 ADMIT and ≥10 REFUSE closes, ADMIT mean ahead by ≥2pp, ADMIT win rate ≥ REFUSE, Brier ≤ 0.25), it decides admission. Until then it's advisory only, and it demotes itself if the edge fades.
- **Persistent proof (7287).** Edge Proof progress now survives app restarts.
- **Smart-money copy-trading (7277).** You can copy trades from mined smart-money wallets.

### Paper realism

- **Venue-priced paper fees (7287).** Paper fills now pay real venue costs:
  - pump.fun bonding curve: 1.25% per side
  - PumpSwap: 0.25% plus the creator fee tier (0.95% → 0.05% by market cap)
  - AMM pools: 0.25%
  - network: 0.000805 SOL per side
  - price impact: clip / (depth + clip), with curve depth ≥ 30 virtual SOL, capped at 15%
  - app fee: 0.5%
- A round trip now costs about **5–6% on the curve** and **2–3% on graduated pools**. Paper results made before 7287 aren't directly comparable with results from this release.

### Data

- **pump.fun create-event mint proof (7279).** New pump.fun mints are confirmed against the on-chain create event before they're treated as real launches.
- **Batched curve reads (7279).** Bonding-curve account reads are batched, so each refresh makes fewer RPC calls.
- **Trade-stream pricing (7278).** Prices can come directly from the PumpPortal keyed trade stream, which is faster than polling.

### LLM

- **Cerebras and Mistral join the council (7276).** Two more providers are available to the LLM council alongside Groq, Gemini, OpenRouter, OpenAI-compatible and keyless providers.
- **Reasoning-model token fix (7286).** Reasoning models no longer run out of output tokens before they return an answer.

### UI

- **Live decision log (7285).** A new live view shows what the engine is deciding and why as it happens.
- **PumpPortal key setting (7284).** You can enter your PumpPortal API key in Settings to enable the keyed trade stream.

---

### Measured on this build (PAPER)

This is one PAPER session on 5.0.7288, run on 24 Sep 2026: about 11.5 minutes, with 79 closed trades.

- Equity ≈10 → 31.34 SOL. Realized +20.52 SOL after 0.89 SOL in fees.
- Profit factor 7.59. Per-position win rate 52.6%.
- Crypto spot lane +7.85 SOL (avg +252% per trade). Project Sniper lane +4.86 SOL.
- Mark loop: 469 ticks in 687 s, 29 of 30 positions fresh, 0 stale resets.
- LLM (Groq): 100 of 100 calls successful.

This is one short paper session with a small sample. Paper is not live, so treat these numbers as a health check on the engine, not as a performance claim.

### What's next

Sustained paper profitability → a small live calibration run to compare real fills and fees with paper → live lanes expanded one at a time → the Oracle reaching PROVEN and guiding admission.

---

*Trading crypto is high risk. Paper results are not live results. AATE is software and does not provide financial advice.*
