# AATE — Autonomous Algorithmic Trading Engine

[![Build AATE APK](https://github.com/shaunhayes333-stack/lifecycle-bot/actions/workflows/build.yml/badge.svg)](https://github.com/shaunhayes333-stack/lifecycle-bot/actions/workflows/build.yml)
![Version](https://img.shields.io/badge/version-5.0.7288-8B5CF6)
![Platform](https://img.shields.io/badge/platform-Android-34D399)
![Kotlin](https://img.shields.io/badge/language-Kotlin-4C8DFF)

AATE is a native Android app that runs a complete autonomous trading engine on the phone. It finds candidates, runs safety checks, scores them, decides, sizes, executes, exits, and learns from the result without anyone tapping a button. It is Solana-first. Sixteen traders share one engine, one position ledger and one capital view. It was built by one developer, on a phone, and GitHub Actions compiles it. The house rule is *real data and forensic accounting: no imagined gains, no inferred values.* The stated goal is **$50 → $1,000,000**. That is a goal, not a result.

> Current version: **5.0.7288** (September 2026). Repo and package name: `lifecycle-bot` / `com.lifecyclebot.aate`. The product name is AATE.

---

## What it trades

One registry holds 16 traders: `MEME`, `SHITCOIN`, `MOONSHOT`, `EXPRESS`, `QUALITY`, `TREASURY`, `CASHGEN`, `BLUECHIP`, `MANIPULATED`, `DIP_HUNTER`, `PROJECT_SNIPER`, `CYCLIC`, `CRYPTO_ALT`, `MARKETS_STOCKS`, `PERPS`, `SHADOW_PAPER`.

- **Solana meme lanes:** Quality, BlueChip, ShitCoin, ShitCoin Express, Moonshot, Project Sniper, Dip Hunter, Manipulated, Cyclic, Treasury/CashGen. One canonical lane-identity authority decides which lane owns a position.
- **Crypto alts:** CryptoAltTrader covers the wider crypto universe through Jupiter/SPL, Raydium, Meteora and bridged/wrapped routes.
- **Markets:** tokenized stocks (xStocks / Backed Finance), commodities, metals and forex.
- **Perps:** SOL perps learn in paper. Live perps do not execute yet.
- **Copy-trading** from mined smart-money wallets.
- **LLM Lab:** an LLM invents strategies and paper-trades them on a 100 SOL synthetic bankroll. An approval queue sits between the Lab and real money.

**Paper first.** In PAPER mode every trader runs ("paper = learn everything"). In LIVE mode only the traders the operator enables can run. Stocks and forex are quarantined in live, and live execution for non-meme lanes is being rolled out in stages.

## How it decides

```
 Scanners + PumpPortal / Helius WebSocket fast lane
                    |
                    v
 Safety: HardRugPreFilter, TokenSafetyChecker (SAFE / CAUTION / HARD_BLOCK),
         RugCheck policy, mint blacklist, serial-rugger creator refusal
                    |
                    v
 Per-lane scoring AIs  ->  FinalDecisionGate
                    |
                    v
 Predictive Entry Oracle: ADMIT / REFUSE   (reads up to 5,000 journal closes)
   advisory until Oracle Edge Proof = PROVEN, then it decides admission
                    |
                    v
 Learned Admission Authority -> Executable Entry Authority (single gate before capital)
                    |
                    v
 Realistic sizer (sized to what can be exited, fee-aware floor)
                    |
                    v
 Execution: Jupiter / PumpPortal / pump.fun curve reads, Helius Sender, Jito bundles
                    |
                    v
 Canonical Position Authority + Canonical Capital Authority (one ledger, one capital view)
                    |
                    v
 Exits: 1 Hz mark loop, sliding profit lock, trailing stops, runner profiles,
        learned exit policy, universal stop-loss sweep
                    |
                    v
 Journal -> learning, on-device TFLite model, collective hive mind, Oracle proof
```

- **The oracle gives a binary verdict.** It either ADMITs or REFUSEs. There are no "probe" trades.
- **The oracle has to earn authority.** It stays advisory until real closes show an edge: at least 20 ADMIT and 10 REFUSE closes, an ADMIT mean at least 2 percentage points above the REFUSE mean, an ADMIT win rate at or above the REFUSE win rate, and a Brier score of 0.25 or lower. Once PROVEN, it decides admission. If the edge fades, it demotes itself. The proof persists across restarts.
- **The LLM council** can use Groq (gpt-oss), Gemini, Cerebras, Mistral, OpenRouter, any OpenAI-compatible endpoint, and keyless providers. Its narrative/scam analysis can block live entries and its exit advice can trigger exits. It runs asynchronously and cached, off the hot path.
- **Wins are not capped.** The profit lock slides up toward the peak instead of taking a fixed target.

## Execution and safety

- Swaps go through the Jupiter swap API. PumpPortal trade-local is the fallback for pump.fun sells, and pump.fun bonding-curve accounts are read directly over an RPC ladder.
- Transactions are submitted fast through Helius Sender with a tip envelope, protected from MEV by Jito bundles, and fall back to a public RPC ladder.
- Modes are **PAPER**, **LIVE** and **SHADOW**. The live safety circuit breaker needs a minimum wallet of 0.1 SOL and halts on session drawdown. The Executable Entry Authority enforces a loss-streak limit, cooldowns and a daily loss cap.
- Keys are stored in EncryptedSharedPreferences (AES-256) behind a PIN / biometric lock and never leave the device. A multi-chain recovery vault covers ETH, BSC and BTC.
- The app fee is 0.5% per spot side (1% on leverage).
- There are 40+ data sources, including Helius (RPC, enhanced WebSocket, DAS, Sender), PumpPortal, DexScreener, Birdeye, GeckoTerminal, CoinGecko, Jupiter Price, Pyth Hermes, Switchboard, DefiLlama, Binance/Kraken/Coinbase, RugCheck, Solscan, GMGN, market-data feeds (Yahoo, Stooq, Finnhub, Polygon), Fear & Greed, and social feeds.

## Paper realism

Paper fills pay real venue costs (`PaperVenueCost`, since 5.0.7287):

| Cost | Charged in paper |
|---|---|
| pump.fun bonding curve | 1.25% per side |
| PumpSwap | 0.25% + creator-fee tier (0.95% → 0.05% by market cap) |
| AMM pools | 0.25% |
| Network | 0.000805 SOL per side |
| Price impact | clip / (depth + clip); curve depth ≥ 30 virtual SOL; capped at 15% |
| App fee | 0.5% |

A round trip costs about 5–6% on the curve and 2–3% on graduated pools.

## Forensics

Everything that happens is counted. `ForensicLogger` writes structured logs for each phase. The **Pipeline Health** screen shows the intake → decision → execution funnel, loop, execution and journal counters, an ANR watchdog, and a count for every refusal reason. The full dump can be copied for offline analysis.

## Latest measured run (PAPER)

**PAPER mode, build 5.0.7288, 24 Sep 2026: one session of about 11.5 minutes with 79 closed trades.**

| Metric | Value |
|---|---|
| Equity | ≈10 → 31.34 SOL |
| Realized P&L | +20.52 SOL, after 0.89 SOL in fees |
| Profit factor | 7.59 |
| Per-position win rate | 52.6% |
| Crypto spot lane | +7.85 SOL (avg +252% per trade) |
| Project Sniper lane | +4.86 SOL |
| Mark loop | 469 ticks in 687 s, 29/30 positions fresh, 0 stale resets |
| LLM (Groq) | 100/100 calls successful |

**Caveats:** this is one short paper session with a small sample. Paper is not live. These are not live returns, and they do not predict future results.

## Scale (measured from the 5.0.7288 source)

- Built by one developer in six months, from a phone, with no team and no million-dollar budget
- 1,200 Kotlin source files, about 457,000 lines of production Kotlin
- 339 test files, about 47,000 test lines, 2,699 `@Test` cases
- 22 screens (Activities) plus the settings sheet
- 16 custom static-analysis CI validators, plus an APK build and an emulator runtime smoke test

## Build

GitHub Actions builds the APK on every push and pull request to `main`/`master` ([build.yml](https://github.com/shaunhayes333-stack/lifecycle-bot/actions/workflows/build.yml)). The APK is uploaded as a workflow artifact.

To build locally (JDK 17 and the Android SDK):

```bash
cd lifecycle_apk
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/
```

### CI validators

Every build has to pass these gates (scripts in `ci/`):

1. Kotlin block-comment balance: `comment_balance.py`
2. Golden-tape literal scan: `golden_tape_literal_scan.py`
3. Authority contradiction scan: `authority_contradiction_scan.py`
4. Patch-rot scan: `patch_rot_scan.py`
5. Economic units / cash authority: `economic_units_scan.py`
6. Android resource pre-flight: `res_validate.py`
7. Layout id contract: `layout_contract.py`
8. Palette drift guard: `palette_drift.py`
9. Static-call receiver guard: `static_call_check.py`
10. Return-telemetry honesty guard: `return_telemetry_check.py`
11. New dead-code guard: `new_dead_code.py`
12. Qualified-reference package guard: `qualified_reference_check.py`
13. Kotlin expression-body return guard: `kotlin_expression_body_return.py`
14. Lane identity authority guard: `lane_identity_authority_scan.py`
15. Local-function modifier guard: `kotlin_local_function_modifiers.py`
16. Val reassignment guard: `kotlin_val_assignment.py`

After the gates, CI builds the APK and runs the unit regression suite. A separate workflow (`runtime-test.yml`) runs the emulator smoke test.

## Repository layout

```
lifecycle_apk/
  app/src/main/kotlin/com/lifecyclebot/
    backtest/     backtesting harness
    collective/   collective learning (Turso / libSQL hive mind)
    data/         config, data sources, persistence
    engine/       bot service, scanners, authorities, execution, exits
    learning/     journal-driven learning
    ml/           on-device TensorFlow Lite model
    network/      HTTP / RPC / WebSocket clients
    perps/        perpetuals trading
    ui/           the 22 screens and settings sheet
    util/         shared utilities
    v3/           V3 scoring and decision layers
    v4/           V4 meta / cross-asset intelligence
  app/src/main/res/  layouts, colors, fonts, drawables
  ci/                static-analysis validators
  docs/              documentation (docs/archive = superseded material)
```

## Documentation

- [docs/README.md](docs/README.md): index of all docs
- [docs/QUICKSTART.md](docs/QUICKSTART.md): install, configure, paper, going live
- [FEATURES.md](FEATURES.md): the full feature list
- [ARCHITECTURE.md](ARCHITECTURE.md): system architecture
- [TECHNICAL_DESCRIPTION.md](TECHNICAL_DESCRIPTION.md): technical description
- [PITCH_DECK.md](PITCH_DECK.md): the pitch
- [CHANGELOG.md](CHANGELOG.md): build history

## Disclaimer

Trading crypto is high risk. You can lose some or all of your capital. AATE does not guarantee profits. Paper results are not live results. Nothing in this repository is financial advice. Start in PAPER, then go live with small amounts you can afford to lose. See [LEGAL.md](LEGAL.md).

## License

See [LICENSE](LICENSE). The LICENSE file in this repo is a proprietary, all-rights-reserved license. AATE™ is a trademark.
