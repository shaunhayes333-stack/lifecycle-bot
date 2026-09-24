# AATE — Marketing Copy (5.0.7288)

Product name (use exactly): **AATE — Autonomous Algorithmic Trading Engine**
Package: `com.lifecyclebot.aate` (LifecycleBot is the repo/package name, not the product name).

Copy rules: every number here comes from the 5.0.7288 fact sheet. Paper results are always labelled PAPER, with sample size and duration. Never promise returns. Every public surface carries the disclaimer at the bottom of this file.

Brand cues: dark navy terminal (#04060D), lit hairline strokes (#4E7CB8), cyan/purple accents (#22D3EE / #8B5CF6), green = profit (#34D399), red = loss (#FB5E6D). Headings in Space Grotesk, body in IBM Plex Sans, numbers and telemetry in JetBrains Mono.

---

## Taglines

1. The trading engine that lives in your pocket.
2. It decides. It sizes. It exits. It learns.
3. No imagined gains. Every fill counted.
4. Built on a phone. Runs on a phone.
5. Trade or don't. No probes.
6. An oracle that has to earn its vote.
7. Autonomous on-device. Your keys never leave.
8. Paper that pays real fees.

---

## Hero options

**Option A**
- Headline: **An autonomous trading engine that runs entirely on your phone.**
- Subhead: AATE scans Solana markets, filters rugs, decides entries, manages exits and learns from every close. It all happens on-device, and your keys never leave it.

**Option B**
- Headline: **Stop clicking. Start auditing.**
- Subhead: Telegram bots wait for your tap. AATE decides for itself and logs every refusal, fill and fee so you can see exactly why.

**Option C**
- Headline: **457,000 lines of Kotlin. One phone. Zero imagined gains.**
- Subhead: A full algorithmic trading stack (16 traders, 40+ data sources, forensic accounting) built by one developer in six months, from a phone.

---

## Website sections

### Features

**16 traders, one engine**
Solana meme lanes (Quality, BlueChip, ShitCoin, ShitCoin Express, Moonshot, Project Sniper, Dip Hunter, Manipulated, Cyclic, Treasury/CashGen), crypto alts across Jupiter, Raydium and Meteora, tokenized stocks, commodities, metals and forex, and perps. One canonical lane identity authority keeps each trade in its proper lane.

**Predictive Entry Oracle**
Forecasts expectancy and win probability for each candidate from the trade journal, using up to 5,000 closes per lane and across the whole book. The verdict is binary: ADMIT or REFUSE. There are no half-size "probe" trades.

**Exits that let winners run**
A supervised, self-healing 1 Hz mark loop. The profit lock slides up toward the peak, so wins aren't capped. Trailing stops, runner exit profiles for moonshots, a learned exit policy for each lane and a universal stop-loss sweep.

**Fast, protected execution**
Jupiter swap API, PumpPortal fallback for pump.fun sells, direct bonding-curve reads, Helius Sender fast submission, Jito bundle MEV protection and a public RPC ladder fallback.

**An LLM council**
Groq, Gemini, Cerebras, Mistral, OpenRouter, OpenAI-compatible and keyless providers. They run narrative and scam analysis that can block live entries, give live exit advice, and handle sentiment, parameter tuning and chat. Calls are async and cached, off the hot path.

**LLM Lab and copy-trading**
The LLM invents strategies and paper-trades them on a 100 SOL synthetic bankroll. An approval queue sits between them and real money. Copy-trading follows mined smart-money wallets.

**Keys stay on the device**
AES-256 EncryptedSharedPreferences, a biometric lock and a multi-chain recovery vault (ETH/BSC/BTC).

### How it works

1. **Find.** Scanners and a WebSocket fast lane (Helius, PumpPortal) surface candidates from 40+ data sources.
2. **Filter.** Hard rug pre-filter, token safety tiers (SAFE / CAUTION / HARD_BLOCK), RugCheck policy, mint blacklist and serial-rugger creator refusal.
3. **Score.** Scoring AIs for each lane feed a final decision gate.
4. **Admit.** The Predictive Entry Oracle says ADMIT or REFUSE. A single Executable Entry Authority shrinks size after losses before any capital moves.
5. **Size.** The realistic sizer sizes each trade to what can actually be exited, with a fee-aware floor.
6. **Manage.** The 1 Hz mark loop, sliding profit lock, trailing stops and runner profiles take it from there.
7. **Learn.** Every close feeds the journal, the per-lane exit policy, the on-device learning model and the collective hive mind (hashed pattern records synced to the operator's own Turso database).

### Trust and forensics

**Real data and forensic accounting. No imagined gains. No inferred values.**

- **One ledger.** The Canonical Position Authority is a single idempotent ledger of positions and cash. The Canonical Capital Authority gives one view of cash, reserved, open cost, unrealized, realized and fees.
- **Everything counted.** ForensicLogger writes structured phase logs. The Pipeline Health screen shows funnel counters, an ANR watchdog and a count of every refusal reason.
- **An oracle that earns authority.** It stays advisory until it proves an edge on real closes: at least 20 ADMIT and 10 REFUSE closes, ADMIT mean ahead of REFUSE by at least 2pp, ADMIT win rate at or above REFUSE, a positive ADMIT mean, and a Brier score of 0.25 or better. It demotes itself if the edge fades. The proof persists across restarts.
- **Engineering discipline.** 2,699 test cases, 16 custom static-analysis validators on every build, and an emulator runtime smoke test in CI.
- **Safety rails.** A live circuit breaker (0.1 SOL minimum wallet, 10% session drawdown halt), and an entry gate that shrinks size after losses.

### Paper realism

Paper trading that flatters you is worse than none. Since 5.0.7287, AATE charges every paper fill real venue costs:

| Venue / cost | Charge |
|---|---|
| pump.fun bonding curve | 1.25% per side |
| PumpSwap | 0.25% + creator fee tier (0.95% → 0.05% by market cap) |
| AMM pools | 0.25% |
| Network | 0.000805 SOL per side |
| Price impact | clip / (depth + clip); curve depth ≥ 30 virtual SOL, capped at 15% |
| App fee | 0.5% |

A round trip costs about **5–6% on the curve** and **2–3% on graduated pools**. If a strategy doesn't beat that in paper, it doesn't go live.

**Latest PAPER session (5.0.7288, 24 Sep 2026, ~11.5 min, 79 closed trades):** equity ≈10 → 31.34 SOL, +20.52 SOL realized after 0.89 SOL fees, profit factor 7.59, per-position win rate 52.6%. *That's one short paper session and a small sample. Paper is not live.*

### Built on a phone

~457,000 lines of production Kotlin across 1,200 source files. 22 screens. One developer, six months, working from a phone, with builds compiled by GitHub Actions. No team, no million-dollar budget.

### Roadmap

Sustained paper profitability → a small live calibration run to verify real fills and fees against paper → live lanes expanded one at a time → Oracle PROVEN and guiding → an iOS/web monitor later.

---

## App store

**Short description (≤80 chars)**
Autonomous on-device trading engine for Solana. Paper-first. Keys stay local.

**Long description**

AATE — Autonomous Algorithmic Trading Engine runs a complete trading engine on your Android phone.

It finds candidates across Solana and beyond, filters out rugs, decides whether to enter, sizes each position to what can realistically be exited, manages the exit and learns from every closed trade. You don't have to tap to fire each trade.

WHAT'S INSIDE
• 16 traders in one engine: Solana meme lanes, crypto alts, tokenized stocks and forex, and perps
• Predictive Entry Oracle: an ADMIT / REFUSE verdict that only gains authority after it proves an edge on real closes
• 1 Hz self-healing mark loop with a profit lock that follows price toward the peak
• Jupiter, PumpPortal, Helius Sender and Jito bundle execution
• An LLM council for scam and narrative checks, exit advice and chat
• LLM Lab, where AI-invented strategies paper-trade before an approval queue
• Copy-trading from mined smart-money wallets
• Pipeline Health screen with every refusal reason counted

PAPER FIRST
Every trader runs in paper mode, and paper fills are charged real venue fees and price impact. Live execution is limited to the lanes you enable and is being expanded carefully, one lane at a time.

YOUR KEYS, YOUR DEVICE
Keys are stored with AES-256 encryption behind a biometric lock and never leave your phone.

FEES
0.5% app fee per spot side (1% on leverage).

Trading crypto is high risk. Paper results are not live results. AATE does not provide financial advice and does not guarantee any return.

---

## Feature bullets (short form)

- Fully autonomous, fully on-device
- 16 traders, 40+ data sources
- Binary entry oracle with an edge proof
- A profit lock that slides to the peak
- Paper fills at real venue cost
- Jito MEV protection and Helius Sender
- AES-256 keys and a biometric lock
- Every refusal counted

---

## CTA lines

- Start in paper. See every fill, fee and refusal.
- Run it in paper first. Judge it by the ledger.
- Download AATE and watch it decide.
- Read the forensics, then decide.
- Follow the build, one release at a time.

---

## Disclaimer (required on every public surface)

Trading cryptocurrency and other digital assets is high risk, and you can lose all of the capital you commit. Paper-trading results are simulated. They come from limited samples and are not live results or an indication of future performance. The goal "$50 → $1,000,000" is an aspiration, not a result or a promise. AATE — Autonomous Algorithmic Trading Engine is software, not financial advice.
