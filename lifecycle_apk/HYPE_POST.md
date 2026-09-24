# I built a 457,000-line trading engine on a phone

*A founder post about AATE — Autonomous Algorithmic Trading Engine, version 5.0.7288*

---

One developer. One phone. GitHub Actions doing the compiling. And a stubborn idea: a trading engine shouldn't need you to tap a button every time it trades.

Today AATE is ~457,000 lines of production Kotlin across 1,200 source files. It has 339 test files with 2,699 test cases, 22 screens, and 16 custom static-analysis validators that have to pass before any build ships. I wrote all of it on a phone, and GitHub Actions compiles it. The finished app runs on a phone too, and the whole engine lives on the device.

Here's what it is, what changed this week, and the numbers, including the ones that need caveats.

## Why build it at all

Photon, BullX, Trojan, GMGN, Banana Gun and BonkBot are good at what they do. They're fast execution surfaces, and the decision still belongs to you. You find the token, you click, and they fire.

I wanted the decision itself automated, and done honestly. AATE scans, filters, scores, admits or refuses, sizes, exits and then learns from the result. It's one autonomous engine rather than a faster button.

## 16 traders in one engine

The registry holds 16 traders: MEME, SHITCOIN, MOONSHOT, EXPRESS, QUALITY, TREASURY, CASHGEN, BLUECHIP, MANIPULATED, DIP_HUNTER, PROJECT_SNIPER, CYCLIC, CRYPTO_ALT, MARKETS_STOCKS, PERPS and SHADOW_PAPER.

They cover Solana meme lanes from pump.fun launches up to blue chips, crypto alts routed through Jupiter, Raydium and Meteora, tokenized stocks, commodities, metals and forex, and SOL perps, which currently learn in paper only. One canonical lane identity authority makes sure a trade can't wander into the wrong lane's books.

In paper mode, every trader runs, on the principle that paper should learn everything. Live mode is paper-first. It covers only the lanes the operator enables, keeps stocks and forex quarantined, and stages non-meme live execution.

## It had to earn the right to say yes

The biggest change in this release series is the **Predictive Entry Oracle**.

For every candidate, it reads the trade journal (up to 5,000 closes, per lane and across the whole book) and forecasts expectancy and win probability. Then it returns one of two verdicts: **ADMIT** or **REFUSE**.

I removed probe trades in 7287. The engine no longer opens a small position "just to see". It either takes the trade or it doesn't.

The part I'm proudest of is that the oracle doesn't start with any authority. It stays advisory until it passes an **Edge Proof** on real closes:

- at least 20 ADMIT closes and 10 REFUSE closes
- the ADMIT mean beats the REFUSE mean by at least 2 percentage points
- the ADMIT win rate is at or above the REFUSE win rate
- a Brier score of 0.25 or better

Once it passes, it becomes PROVEN and binds admission. If the edge fades, it demotes itself. The proof persists across app restarts, so it doesn't have to start over every time the phone kills the process.

## Exits: we don't cap wins

Entries get the attention, but exits decide the P&L, so a lot of this series went into exits:

- A **1 Hz open-position mark loop** that is supervised and self-heals (7283).
- A **profit lock that slides up toward the peak** rather than sitting at a fixed take-profit (7282).
- Trailing stops, runner exit profiles for moonshots, a learned exit policy for each lane and a universal stop-loss sweep.
- In 7288, **sells are dispatched off the mark loop** so a slow sell can't stall pricing, and stalls are detected by sequence number.

## Paper that doesn't lie to you

Paper results are usually the most flattering fiction in trading because the fills are free. In 7287 I made AATE's paper trading pay real venue costs:

- pump.fun bonding curve: 1.25% per side
- PumpSwap: 0.25% plus the creator fee tier (0.95% down to 0.05% depending on market cap)
- AMM pools: 0.25%
- network: 0.000805 SOL per side
- price impact: clip / (depth + clip), with curve depth of at least 30 virtual SOL, capped at 15%
- the 0.5% app fee

That comes to about **5–6% per round trip on the curve** and **2–3% on graduated pools**. Every paper trade has to clear that.

## The run

The latest measured session on 5.0.7288, on 24 Sep 2026:

> **PAPER. One session, ~11.5 minutes, 79 closed trades.**
> - Equity: ≈10 → **31.34 SOL**
> - Realized: **+20.52 SOL**, after **0.89 SOL** in fees
> - Profit factor: **7.59**
> - Per-position win rate: **52.6%**
> - Crypto spot lane: **+7.85 SOL** (avg +252% per trade)
> - Project Sniper lane: **+4.86 SOL**
> - Mark loop: 469 ticks in 687 s, 29 of 30 positions fresh, 0 stale resets
> - LLM (Groq): 100 of 100 calls successful

I'm excited about that run, and I also want to be straight about what it is. **It's one short paper session with a small sample. Paper is not live.** Real fills, real slippage and real latency will be different. It proves the machinery works end to end under realistic fee modelling. It doesn't prove the engine makes money, and it predicts nothing.

## Everything is counted

The rule I built the project around: **real data and forensic accounting. No imagined gains. No inferred values.**

That's why there's a single Canonical Position Authority (one idempotent ledger of positions and cash) and a single Canonical Capital Authority (cash, reserved, open cost, unrealized, realized and fees, all in one view). ForensicLogger writes structured phase logs. The Pipeline Health screen counts every stage of the funnel and every refusal reason, and runs an ANR watchdog. When the engine says no, it records the reason.

## The rest of the machine

- **Execution:** Jupiter swap API, a PumpPortal trade-local fallback for pump.fun sells, direct bonding-curve reads over an RPC ladder, Helius Sender with a tip envelope, Jito bundle MEV protection and a public RPC fallback.
- **Data:** 40+ sources, including Helius, PumpPortal, DexScreener, Birdeye, GeckoTerminal, CoinGecko, Jupiter Price, Pyth, Switchboard, DefiLlama, the major CEXs, RugCheck, Solscan, GMGN, market data providers, Fear & Greed and social feeds.
- **Safety:** a hard rug pre-filter, token safety tiers, RugCheck policy, a mint blacklist, serial-rugger creator refusal, a live circuit breaker, loss-streak limits, cooldowns and a daily loss cap.
- **LLM council:** Groq, Gemini, Cerebras, Mistral, OpenRouter, OpenAI-compatible and keyless providers. It runs scam and narrative checks that can block live entries, gives exit advice, and handles sentiment and parameter tuning. It's async and cached, off the hot path.
- **LLM Lab:** the model invents strategies and paper-trades them on a 100 SOL synthetic bankroll, and an approval queue sits between them and real money.
- **Learning:** an on-device TensorFlow Lite model plus an anonymized collective hive mind with a shared blacklist.
- **Keys:** AES-256 encrypted storage and a biometric lock. They never leave the device.

## What's next

Here's the roadmap without the hype:

1. Sustained paper profitability, meaning many sessions and not just one good one.
2. A small live calibration run to check real fills and fees against paper.
3. Live lanes opened one at a time.
4. The Oracle reaching PROVEN on real closes and guiding admission.
5. An iOS/web monitor later on.

The north star is **$50 → $1,000,000**. That's a goal I'm building toward, not a result I'm claiming.

I'm raising a **$500K seed** to take this from one developer on a phone to a proper team. If you want to see the forensics, I'm happy to walk anyone through them.

---

*Trading crypto is high risk, and you can lose everything you put in. The results above are PAPER results from one ~11.5 minute session with 79 closed trades. They are not live results and don't indicate future performance. This is not financial advice.*
