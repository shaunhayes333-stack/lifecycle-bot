AATE — Autonomous Algorithmic Trading Engine · v5.0.7288

# AATE — One Pager

**An autonomous, Solana-first trading engine that runs entirely on an Android phone.** It decides, sizes, exits and learns, and keeps a forensic record of every step. Built by one developer, on a phone.

---

### Problem
Solana markets move faster than a person can click. The popular tools (Photon, BullX, Trojan, GMGN, Banana Gun, BonkBot) are **click-driven**, so a human still decides what to buy, how much and when to sell. Paper modes rarely charge real venue costs, so they flatter the strategy.

### Solution
AATE runs the whole pipeline on-device:
- **Candidates:** scanners plus a PumpPortal/Helius WebSocket fast lane.
- **Safety:** HardRugPreFilter, TokenSafetyChecker, RugCheck policy, a mint blacklist and refusal of serial-rugger creators.
- **Decision:** per-lane scoring AIs, then the FinalDecisionGate, then the **Predictive Entry Oracle** (binary ADMIT / REFUSE).
- **Oracle Edge Proof:** the oracle earns authority only after proving an edge on real closes, and it demotes itself if the edge fades.
- **Sizing:** sized to what can be exited, with a fee-aware floor, through a single Executable Entry Authority.
- **Exits:** a 1 Hz supervised mark loop, a profit lock that slides toward the peak, trailing stops, runner profiles and a learned exit policy.
- **Accounting:** a Canonical Position Authority and a Canonical Capital Authority. Every refusal reason is counted.

### What it trades
There are 16 traders in one engine:
- **Solana meme lanes:** Quality, BlueChip, ShitCoin, Express, Moonshot, Project Sniper, Dip Hunter, Manipulated, Cyclic, Treasury/CashGen.
- **Crypto alts:** Jupiter, Raydium and Meteora routes.
- **Tokenized markets:** stocks, commodities, metals and forex.
- **SOL perps:** paper only.

Every trader runs in **paper**. **Live** runs only an operator-enabled set, and non-meme live execution is staged.

### Trust
- Keys are AES-256 encrypted and biometric-locked, and never leave the device.
- Orders go through Helius Sender and Jito bundle MEV protection.
- Circuit breakers: a 0.1 SOL minimum wallet, a drawdown halt, a daily loss cap and loss-streak cooldowns.
- The app fee is 0.5% per spot side (1% leverage).

### Paper realism
Paper fills pay real venue costs: pump.fun curve 1.25% per side, PumpSwap plus creator tier, AMM 0.25%, network fees, modelled price impact and the 0.5% app fee. A round trip costs about **5–6% on the curve** and **2–3% on graduated pools**.

### Latest PAPER run (5.0.7288, 24 Sep 2026)
| Metric | PAPER value |
|---|---|
| Sample | 1 session, ~11.5 min, **79 closed trades** |
| Equity | ≈10 → 31.34 SOL |
| Realized | +20.52 SOL, after 0.89 SOL of fees |
| Profit factor / win rate | 7.59 / 52.6% |
| Mark loop | 469 ticks in 687 s, 0 stale resets |

*This is one short paper session and a small sample. Paper is not live.*

### Engineering
- ~457K lines of production Kotlin across 1,200 files.
- 2,699 `@Test` cases.
- 16 custom CI validators.
- An emulator smoke test.
- 22 screens.
- 40+ data sources.

### Roadmap
Sustained paper profitability → small live calibration run → live lanes expanded one at a time → Oracle PROVEN → iOS / web monitor.

### The ask
**Raising a $500K seed.** It funds live calibration, data and infrastructure, a security review and the first hires. The north star, **$50 → $1,000,000**, is a stated goal, not a result.

---

*Trading crypto is high risk. Paper results are not live results. Not financial advice.*
