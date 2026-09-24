AATE — Autonomous Algorithmic Trading Engine · v5.0.7288

# AATE — Pitch Deck

> **Risk disclaimer.** Trading crypto is high risk and you can lose everything you put in. All performance figures in this deck are **PAPER** results from one short session and are not live results. Nothing here is financial advice or a promise of returns.

---

## Slide 1 — Title

**AATE — Autonomous Algorithmic Trading Engine**

An autonomous, Solana-first trading engine that runs entirely on an Android phone. It finds, vets, sizes, exits and learns without anyone clicking.

- Version 5.0.7288 (September 2026)
- Native Android (Kotlin), with the whole engine on-device
- Built by one developer in six months, on a phone, with no team and no million-dollar budget
- Raising a **$500K seed**

**Speaker notes:** Open with the phone in hand. "This is the whole engine. The scanners, the risk gates, the ledger and the learning loop all run on this device, and the keys never leave it." The goal we state is $50 → $1,000,000. It is a goal, not a result.

---

## Slide 2 — The problem

Crypto trading on Solana moves faster than a person can click.

- Tokens launch, graduate and rug within minutes.
- The popular tools are **click-driven**. A human still decides what to buy, how much and when to sell.
- Most tools don't account forensically for what they did or why. Fees, slippage and refusals are hard to see.
- Paper and "backtest" numbers usually leave out real venue costs, so they flatter the strategy.

**Speaker notes:** The pain is not a missing buy button. It's decision speed, discipline, and honesty about costs. People lose to rugs, to fees they never saw, and to their own exits.

---

## Slide 3 — The solution

**AATE is an autonomous engine, not a trading terminal.**

- **Decides.** Scanners and a WebSocket fast lane feed per-lane scoring AIs and a single final gate.
- **Sizes.** A realistic sizer only buys what it can exit, with a fee-aware floor.
- **Exits.** A 1 Hz supervised mark loop runs a profit lock that slides toward the peak, trailing stops and runner profiles.
- **Learns.** A per-lane learned exit policy, a Predictive Entry Oracle, an on-device learning model and collective learning.
- **Accounts.** One canonical ledger for positions and capital. Every refusal reason is counted.

**Speaker notes:** Photon, BullX, Trojan and the rest are good tools for humans who click. AATE removes the click.

---

## Slide 4 — How it decides

The pipeline from candidate to capital:

1. **Candidates.** Scanners plus the PumpPortal and Helius WebSocket fast lane.
2. **Safety.** HardRugPreFilter, TokenSafetyChecker (SAFE / CAUTION / HARD_BLOCK), RugCheck policy, a mint blacklist and refusal of serial-rugger creators.
3. **Scoring.** Per-lane AIs (ShitCoin, Moonshot, BlueChip, Quality, ProjectSniper, CashGeneration and others), then the FinalDecisionGate.
4. **Predictive Entry Oracle.** Forecasts expectancy and win probability from up to 5,000 journal closes. Its verdict is binary, **ADMIT / REFUSE**, with no probe trades.
5. **Oracle Edge Proof.** The oracle stays advisory until it proves itself on real closes: at least 20 ADMIT and 10 REFUSE closes, ADMIT mean at least 2pp better, ADMIT win rate at least equal, ADMIT mean positive, Brier ≤ 0.25. After that it decides admission, and it demotes itself if the edge fades.
6. **Executable Entry Authority.** The single gate before capital. It shrinks size after losses.
7. **LLM council** (Groq, Gemini, Cerebras, Mistral, OpenRouter and others). It runs async and cached, off the hot path. Its narrative and scam analysis can block live entries.

**Speaker notes:** The key idea is that the oracle has to earn authority. It can't just claim it. That proof state persists across restarts.

---

## Slide 5 — What it trades

**16 traders in one engine**, each with one canonical lane identity.

| Area | Lanes |
|---|---|
| Solana meme | Quality, BlueChip, ShitCoin, ShitCoin Express, Moonshot, Project Sniper, Dip Hunter, Manipulated, Cyclic, Treasury / CashGen |
| Crypto universe | CryptoAltTrader over Jupiter/SPL, Raydium, Meteora and bridged-wrapped routes |
| Markets | Tokenized stocks (xStocks / Backed Finance), commodities, metals, forex |
| Perps | SOL perps. They learn in paper, and live perps are not executing yet |
| Extras | Copy-trading from mined smart-money wallets. The LLM Lab lets an LLM invent strategies and paper-trade them on a 100 SOL synthetic bankroll, with an approval queue before any real money |

**Paper-first:** every trader runs in paper ("paper = learn everything"). In live, only an operator-enabled set runs. Stocks and forex are quarantined in live, and non-meme live execution is staged.

**Speaker notes:** Be explicit that breadth lives in paper today. Live is deliberately narrow.

---

## Slide 6 — Trust, safety and forensics

- **Keys:** EncryptedSharedPreferences (AES-256) and a biometric lock. Keys never leave the device. A multi-chain recovery vault covers ETH, BSC and BTC.
- **Execution:** Jupiter swap API, a PumpPortal fallback for pump.fun sells, and direct bonding-curve reads over an RPC ladder. Orders go out through Helius Sender with a tip envelope, use Jito bundles for MEV protection, and fall back to a public RPC ladder.
- **Circuit breakers:** a 0.1 SOL minimum wallet, and a 10% session drawdown halt.
- **Single sources of truth:** the Canonical Position Authority and Canonical Capital Authority (cash, reserved, open cost, unrealized, realized, fees).
- **Forensics:** ForensicLogger structured phase logs, plus a Pipeline Health screen with funnel counters, an ANR watchdog and a count for every refusal reason.

> "Real data and forensic accounting. No imagined gains. No inferred values."

**Speaker notes:** This is the doctrine. If it happened, it's counted. If it wasn't measured, we don't claim it.

---

## Slide 7 — Paper realism

Paper fills are charged **real venue costs** (PaperVenueCost, since 5.0.7287):

| Cost | Charge |
|---|---|
| pump.fun bonding curve | 1.25% per side |
| PumpSwap | 0.25% plus creator fee tier (0.95% → 0.05% by market cap) |
| AMM | 0.25% |
| Network | 0.000805 SOL per side |
| Price impact | clip / (depth + clip); curve depth ≥ 30 virtual SOL, capped at 15% |
| App fee | 0.5% |

The round trip costs about **5–6% on the curve** and **2–3% on graduated pools**.

**Speaker notes:** Most paper modes are fantasy. Ours charges what the venue would charge, so a paper edge has to clear real friction.

---

## Slide 8 — Traction: latest PAPER run

**PAPER. One session of ~11.5 minutes, 79 closed trades, build 5.0.7288, 24 Sep 2026.**

| Metric | Value (paper) |
|---|---|
| Equity | ≈10 → 31.34 SOL |
| Realized P&L | +20.52 SOL, after 0.89 SOL of fees |
| Profit factor | 7.59 |
| Win rate (per position) | 52.6% |
| Crypto spot lane | +7.85 SOL (avg +252% per trade) |
| Project Sniper lane | +4.86 SOL |
| Mark loop health | 469 ticks in 687 s, 29/30 positions fresh, 0 stale resets |
| LLM (Groq) | 100/100 successful calls |

**Caveats:** this is one short paper session and a small sample. Paper is not live. It does not predict future results.

**Speaker notes:** Say the caveat out loud before the numbers. The mark-loop and LLM reliability numbers matter as much as P&L, because they show the machine runs clean. The next milestone is sustained paper profitability, then a small live calibration run.

---

## Slide 9 — Engineering scale

Measured from the code at 5.0.7288:

- **1,200** Kotlin source files, about **457,000** lines of production Kotlin
- **339** test files, about **47,000** test lines, **2,699** `@Test` cases
- **16 custom static-analysis CI validators** gate every build, including golden tape, economic units, authority contradiction, lane identity authority, palette drift and new dead code
- GitHub Actions APK build and an emulator runtime smoke test
- **22 screens** plus a settings sheet
- **40+ data sources**, including Helius, PumpPortal, DexScreener, Birdeye, GeckoTerminal, CoinGecko, Jupiter, Pyth, Switchboard, DefiLlama, major CEX feeds, RugCheck, Solscan, GMGN, and market data feeds
- 15 builds shipped on 24 Sep 2026 alone (7274 → 7288)

**Speaker notes:** One developer, working from a phone, compiling in CI. The validators are how a solo builder keeps a codebase this size honest.

---

## Slide 10 — Competition (qualitative)

| | Click-driven tools (Photon, BullX, Trojan, GMGN, Banana Gun, BonkBot) | Grid / DCA bots | **AATE** |
|---|---|---|---|
| Who decides | The user | Fixed rules | The engine, per lane |
| Exits | User or simple TP/SL | Rule-based | Sliding profit lock, trailing, runner profiles, learned policy |
| Learning | — | — | Oracle with edge proof, on-device learning model, collective learning |
| Where it runs | Telegram / web | Exchange / server | On the phone |

We don't publish competitor numbers. This is a comparison of product models, not a scorecard.

**Speaker notes:** These tools are great for manual traders, and they're our reference for execution speed. AATE's bet is autonomy plus honest accounting.

---

## Slide 11 — Roadmap (honest)

1. **Sustained paper profitability** across many sessions, not one.
2. **Small live calibration run** to verify real fills and fees against paper.
3. **Expand live lanes one at a time.**
4. **Oracle PROVEN** and guiding admission on real closes.
5. **iOS / web monitor** later.

**Speaker notes:** No dates are promised. Each step is gated on the previous one's evidence.

---

## Slide 12 — The ask

**Raising a $500K seed.**

Intended use (founder's plan):
- Live calibration and a careful live-lane rollout
- Data and infrastructure (paid RPC, data feeds, LLM providers)
- Security review of key handling and execution paths
- First hires around the founder

**Speaker notes:** No valuation is stated in this deck. That's a conversation. What the money buys is evidence: moving from a paper edge to verified live behaviour.

---

## Slide 13 — Team

**Solo founder. Built on a phone.**

- One developer designed, wrote and shipped the whole engine: ~457K lines of Kotlin, 2,699 tests and 16 custom CI validators.
- Six months of development from a phone, with builds compiled by GitHub Actions. No development team, no million-dollar budget.
- The operating doctrine is "Real data and forensic accounting. No imagined gains. No inferred values."

**Speaker notes:** The constraint is the story. If this much discipline came out of one person and one phone, imagine it with a small team.

---

> **Disclaimer.** Trading crypto is high risk. Paper results are not live results. The "$50 → $1,000,000" north star is a stated goal, not a result or a projection. This is not financial advice.
