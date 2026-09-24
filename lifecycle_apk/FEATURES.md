# AATE Features

This is the feature list for **AATE — Autonomous Algorithmic Trading Engine**, version **5.0.7288** (September 2026). AATE is a native Android app (Kotlin, minSdk 26, targetSdk 34) that runs the whole engine on the phone. It is Solana-first.

Everything below exists in the current code. Where a capability is paper-only or staged for live, it says so.

---

## Traders and lanes

One engine runs 16 registered traders: `MEME`, `SHITCOIN`, `MOONSHOT`, `EXPRESS`, `QUALITY`, `TREASURY`, `CASHGEN`, `BLUECHIP`, `MANIPULATED`, `DIP_HUNTER`, `PROJECT_SNIPER`, `CYCLIC`, `CRYPTO_ALT`, `MARKETS_STOCKS`, `PERPS`, `SHADOW_PAPER`.

- **Solana meme lanes.** Quality, BlueChip, ShitCoin, ShitCoin Express, Moonshot, Project Sniper, Dip Hunter, Manipulated, Cyclic and Treasury/CashGen. Each lane has its own scoring AI and exit profile.
- **Lane identity authority.** One canonical authority decides which lane owns each position, and a CI guard enforces it.
- **Crypto alts (CryptoAltTrader).** The wider crypto universe, reached through Jupiter/SPL, Raydium, Meteora and bridged/wrapped routes.
- **Markets.** Tokenized stocks (xStocks / Backed Finance), commodities, metals and forex, each with its own toggle in Settings › MARKETS SUB-TRADERS.
- **Perps.** SOL perps learn in paper. Live perps do not execute yet.
- **Copy-trading.** Follows smart-money wallets mined from on-chain activity.
- **LLM Lab.** An LLM invents strategies and paper-trades them on a 100 SOL synthetic bankroll. Nothing reaches real money until it passes an approval queue.
- **Paper and live scope.** In PAPER every trader runs ("paper = learn everything"). In LIVE only the traders the operator enables can run. Stocks and forex are quarantined in live, and live execution for non-meme lanes is being rolled out in stages.

## Execution (Solana)

- Swaps go through the Jupiter swap API.
- PumpPortal trade-local is the fallback for pump.fun sells.
- pump.fun bonding-curve accounts are read directly over an RPC ladder, with batched curve reads and create-event mint proof.
- Transactions are submitted fast through Helius Sender with a tip envelope.
- Jito bundles provide MEV protection.
- A public RPC ladder is the fallback, with per-rung backoff.
- Modes are **PAPER**, **LIVE** and **SHADOW**.
- A realistic sizer sizes each position to what can actually be exited and applies a fee-aware floor.
- The app fee is 0.5% per spot side (1% on leverage).

## Safety

- **HardRugPreFilter** removes obvious rugs before any scoring.
- **TokenSafetyChecker** returns a SAFE / CAUTION / HARD_BLOCK verdict.
- **RugCheck policy**, a **mint blacklist** (shared through the collective), and **serial-rugger creator refusal**.
- **Live safety circuit breaker:** a minimum wallet of 0.1 SOL and a halt on session drawdown.
- **Executable Entry Authority** is the single gate before capital. It enforces a loss-streak limit, cooldowns and a daily loss cap.
- **Canonical Position Authority** is one idempotent ledger of positions and cash.
- **Canonical Capital Authority** is one view of cash, reserved, open cost, unrealized, realized and fees.

## Intelligence and the oracle

- **Scanners and a WebSocket fast lane** (PumpPortal launches and migrations, the keyed PumpPortal trade stream, Helius enhanced WebSocket) produce candidates.
- **Per-lane scoring AIs** (ShitCoinTraderAI, MoonshotTraderAI, BlueChipTraderAI, QualityTraderAI, ProjectSniperAI, CashGenerationAI and others) feed a **FinalDecisionGate**.
- **Predictive Entry Oracle.** It forecasts expectancy and win probability for each candidate from the full trade journal: up to 5,000 closes, per lane and across the whole book. The verdict is binary, ADMIT or REFUSE, with no probe trades.
- **Oracle Edge Proof.** The oracle stays advisory until real closes prove it: at least 20 ADMIT and 10 REFUSE closes, an ADMIT mean at least 2 percentage points above the REFUSE mean, an ADMIT win rate at or above the REFUSE win rate, and a Brier score of 0.25 or lower. Once PROVEN it decides admission. It demotes itself if the edge fades. The proof persists across restarts.
- **Learned Admission Authority** sits between scoring and the Executable Entry Authority.
- **40+ data sources**, including Helius (RPC, enhanced WebSocket, DAS, Sender), PumpPortal, DexScreener, Birdeye, GeckoTerminal, CoinGecko, Jupiter Price, Pyth Hermes, Switchboard, DefiLlama, Binance/Kraken/Coinbase, RugCheck, Solscan, GMGN, Yahoo/Stooq/Finnhub/Polygon for markets, Fear & Greed, and social feeds (Telegram, X).

## Exits

- **1 Hz open-position mark loop.** It is supervised and self-healing, and it detects stalls by sequence number.
- **Sells are dispatched off the mark loop,** so a slow sell never stalls marking.
- **Profit lock** slides up toward the peak. Wins are not capped.
- **Trailing stops.**
- **Runner exit profiles** for moonshots.
- **A learned exit policy for each lane.**
- **A universal stop-loss sweep.**
- **Dead-token exit checks** consult the price feeds first.

## LLM council

- **Providers:** Groq (gpt-oss), Gemini, Cerebras, Mistral, OpenRouter, any OpenAI-compatible endpoint, and keyless providers.
- **Narrative and scam analysis** can block live entries.
- **Exit advice** can trigger exits.
- **Runs asynchronously and cached,** off the hot path, so the engine never waits on an LLM.
- **Also:** sentiment, parameter tuning, chat personas (Persona Studio) and voice (ElevenLabs).

## Learning and collective

- **Journal-driven learning.** Every close feeds lane scoring, the exit policy and the oracle.
- **On-device TensorFlow Lite model.**
- **Collective learning (hive mind).** Anonymized patterns and a shared blacklist sync through a Turso/libSQL database run by the operator.
- **Backtesting** on historical data.
- **Tuning screen:** per-lane expectancy and decision-quality signals.

## Forensics and pipeline health

- **ForensicLogger** writes structured logs for each phase.
- **Pipeline Health screen:** the intake → decision → execution funnel, loop, execution and journal counters, max frame time, an ANR watchdog, every refusal reason counted, a sectioned full report with copy-to-clipboard export, and a self-healing advisor.
- **Live decision log** and **live trade log.**
- **Error log screen.**
- **Universe Health screen:** one-glance ground truth covering runtime, scoring, learning and more.
- **Learning Counter screen:** canonical learning counters.
- The rule is that everything that happens is counted.

## UI screens

There are 22 screens (Activities) plus the settings bottom sheet:

1. **Splash:** launch
2. **Security:** PIN / biometric unlock
3. **Main:** dashboard, bot control, open positions
4. **Wallet:** connect wallet, balance, performance, treasury withdraw
5. **Journal:** trade journal
6. **Live Trade Log:** live execution log
7. **Alerts:** alert history and triage
8. **Error Log:** captured errors
9. **Pipeline Health:** funnel, counters, ANR watchdog
10. **Universe Health:** one-glance ground truth (runtime, scoring, learning and more)
11. **Learning Counter:** canonical learning counters and wallet-truth digest
12. **Behavior:** neural personality (instinct, plasticity, layer health)
13. **Tuning:** per-lane expectancy and decision-quality levers
14. **Collective Brain:** hive-mind status
15. **Multi-Asset:** markets (stocks, commodities, metals, forex, perps)
16. **Crypto Alt:** the crypto alts trader
17. **Insider Wallets:** smart money to follow or fade
18. **Watchlist:** watched mints
19. **Currency:** display currency
20. **Backtest:** backtesting
21. **Lab:** LLM Lab strategies and approval queue
22. **Persona Studio:** LLM chat personas and voice

**Settings sheet:** trading mode and trader toggles, PAPER/LIVE mode, auto trade, sizing and slippage, top-up strategy, API keys (Helius, PumpPortal data key, Birdeye, Groq, Gemini, ElevenLabs, Jupiter), RPC URL, treasury wallet, Telegram alerts, AI scoring mode, watchlist, export/import, and clear all API keys.

## Design system

- **Look:** a dark navy terminal with lit hairline strokes, raised blue surfaces and mono telemetry. Green means profit and red means loss.
- **Colors:** bg `#04060D`, surface `#121C3A`, stroke `#4E7CB8`, text `#E8EEFB`, purple `#8B5CF6`, blue `#4C8DFF`, cyan `#22D3EE`, green `#34D399`, amber `#FBBF24`, red `#FB5E6D`, pink `#F0409C`.
- **Fonts:** Space Grotesk for headings, IBM Plex Sans for body text, JetBrains Mono for numbers, labels and telemetry.
- **Logo:** a neural "bow-tie" of cyan filaments with the AATE wordmark.
- **Enforcement:** a CI palette-drift guard and a layout-id contract check.

## Security

- **Key storage.** Private and API keys are stored in EncryptedSharedPreferences (AES-256) and never leave the device.
- **PIN / biometric lock** on app entry.
- **Multi-chain recovery vault** (ETH / BSC / BTC).
- **Burner-wallet guidance** in the Wallet screen.
- **Clear All API Keys** in Settings.

## Engineering

- 1,200 Kotlin source files, about 457,000 lines of production Kotlin.
- 339 test files, about 47,000 test lines, 2,699 `@Test` cases.
- 16 custom static-analysis CI validators gate every build. GitHub Actions builds the APK, and an emulator runtime smoke test runs in a separate workflow.
- Built by one developer in six months, from a phone, with no team and no million-dollar budget; compiled by GitHub Actions.

---

Trading crypto is high risk. Paper results are not live results. This is not financial advice.
