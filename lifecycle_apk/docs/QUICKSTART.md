# AATE Quickstart

This guide covers **AATE — Autonomous Algorithmic Trading Engine**, version **5.0.7288**, on Android 8.0 or later (minSdk 26).

It takes you through installing the app, setting it up, running it in PAPER, reading Pipeline Health, and going live carefully.

---

## 1. Install the APK

1. Open the [Build AATE APK workflow](https://github.com/shaunhayes333-stack/lifecycle-bot/actions/workflows/build.yml) on GitHub Actions, pick the latest green run on `main`, and download the `AATE_v5.0.7288…` artifact.
   You can also build it yourself: `cd lifecycle_apk && ./gradlew assembleDebug` (JDK 17 + Android SDK). The APK lands in `app/build/outputs/apk/debug/`.
2. Copy the APK to your phone and open it. Allow "install unknown apps" for your file manager or browser when Android asks.
3. Launch **AATE**. On first launch the Security screen asks you to set a 4–6 digit PIN. After that you can unlock with the PIN or with biometrics.

## 2. First run

- The **Main** screen is the dashboard. It shows bot status, open positions and the start/stop control.
- Open **Settings** (the bottom sheet from the Main screen). All the configuration below lives there, except the wallet.
- The **Wallet** screen is where you connect a wallet. Only do that once you are ready for live trading (step 6).

## 3. Settings to fill in

Open Settings › **API KEYS**. The labels below are exactly what you will see.

| Setting | Where to get it | Why it matters |
|---|---|---|
| **HELIUS KEY** | helius.dev | RPC, enhanced WebSocket, DAS and the Helius Sender fast path. Strongly recommended. |
| **PUMPPORTAL DATA KEY** | pumpportal.fun | Unlocks the keyed PumpPortal **trade stream**. The key must be **funded (about 0.02 SOL)**. If you leave it blank you only get launch events. |
| **BIRDEYE KEY** | birdeye.so | Extra price and market data. |
| **GROQ KEY** | console.groq.com | LLM council (gpt-oss): narrative/scam checks, exit advice, sentiment. |
| **GEMINI KEY** | aistudio.google.com | A second LLM council provider. |
| **ELEVENLABS KEY** | elevenlabs.io | Optional voice for personas. |
| **JUPITER KEY** | portal.jup.ag | Jupiter swap and price API. |

Also in Settings:

- **RPC URL**: leave the default or paste your own Solana RPC endpoint.
- **TREASURY WALLET ADDRESS**: optional. This is a public SOL address for treasury splits.
- **TELEGRAM ALERTS** › **BOT TOKEN** / **CHAT ID**: optional trade alerts sent to Telegram.
- **TRADING MODE** and the trader toggles (Meme Trader, Markets Trader with its sub-traders, Crypto Alts Trader) choose which traders run.
- **AI SCORING MODE**: keep **CLASSIC** (the production default).

Tap **Save Settings**.

## 4. Start in PAPER

1. In Settings › **TRADING**, set **MODE** to **PAPER** and turn on **AUTO TRADE**.
2. Save, then start the bot from the Main screen.

In PAPER every trader runs and learns. Paper fills pay realistic venue costs: pump.fun curve 1.25% per side, PumpSwap 0.25% plus the creator-fee tier, AMM pools 0.25%, a 0.000805 SOL network fee per side, modelled price impact, and the 0.5% app fee. A round trip costs about 5–6% on the curve and 2–3% on graduated pools, so paper P&L is not free money on paper.

Let it run. The **Predictive Entry Oracle** stays advisory until it has proved an edge on real closes (at least 20 ADMIT and 10 REFUSE closes with a measurable gap). It only decides admission after that.

## 5. Read Pipeline Health

Open **Pipeline Health** (intake · decision · execution):

- **LOOP / EXEC / JRNL** show bot-loop ticks, executions and journal records. All three should keep climbing while the bot runs.
- **MAX FRAME / ANR** shows UI responsiveness. ANR should stay at 0.
- **PIPELINE FUNNEL** shows how many candidates entered, passed safety, were evaluated by a lane, were admitted and were executed. Every refusal reason is counted, so you can see exactly why trades did not happen.
- Use ◀ / ▶ to page through sections. **Copy to Clipboard** exports the full dump, and **Ask Self-Healing Advisor** asks the LLM to diagnose it.

Also useful: **Journal** (closed trades), **Live Trade Log**, **Error Log**, **Universe Health** and **Learning Counter**.

If the funnel stays empty, check the Helius key, the PumpPortal data key (it must be funded for the trade stream) and your network connection.

## 6. Going live: checklist

Only go live once you understand what the bot does in paper. Paper results are not live results.

- [ ] You have run in PAPER long enough to see closes across several lanes, and Pipeline Health looks healthy.
- [ ] You use a **dedicated burner wallet**, never your main wallet. Connect it in **Wallet** › **PRIVATE KEY (base58)** › **CONNECT WALLET**.
- [ ] The wallet holds **at least 0.1 SOL**. The live safety circuit breaker will not trade below that, and it halts on session drawdown.
- [ ] You start with a **small calibration run**, an amount you can lose completely. The goal is to check that real fills and fees match paper.
- [ ] Sizing (**SMALL BUY / LARGE BUY (SOL)**, **SLIPPAGE BPS**, **MAX SOL**) is set conservatively.
- [ ] Only the traders you want live are enabled. Stocks and forex are quarantined in live, live perps are not executing yet, and non-meme live execution is being rolled out in stages.
- [ ] You know the Executable Entry Authority limits: loss-streak limit, cooldowns and daily loss cap.

Then set Settings › TRADING › **MODE** to **LIVE**, save and start. Watch the first trades in the **Live Trade Log** and **Pipeline Health**.

## 7. Security notes

- **Never share your keys.** Don't screenshot or paste private keys or API keys anywhere, and never send them to anyone, including people claiming to be support.
- Keys **stay encrypted on the device** (EncryptedSharedPreferences, AES-256) behind a PIN / biometric lock. They are never uploaded.
- Use a burner wallet with only what you are prepared to lose.
- **Clear All API Keys** in Settings wipes the stored API keys.
- Keep your phone's screen lock on and your security patches up to date.

---

Trading crypto is high risk. You can lose some or all of your capital. Paper results are not live results. This is not financial advice. See [LEGAL.md](../LEGAL.md) and [SECURITY.md](../SECURITY.md).
