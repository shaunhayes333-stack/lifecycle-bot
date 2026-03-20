# Lifecycle Bot v7.1 — Solana Trading Bot APK

## What's New in v7.1
### Bug Fixes
- Fixed duplicate variable declarations in LifecycleStrategy.kt
- Fixed duplicate mcapUsd assignment in Executor.kt
- Fixed OkHttp extension syntax in StartupReconciler.kt

### New Features
- **🔔 Telegram Alerts** — Trade notifications, P&L updates, remote control via commands
- **📊 Performance Analytics** — Win rate by phase/time/score, drawdown analysis, actionable insights
- **🛡️ Anti-Rug Engine** — Liquidity lock verification, dev wallet tracking, top holder analysis
- **⚡ Jito MEV Protection** — Bundle transactions to avoid sandwich attacks
- **🔄 Auto-Compound** — Reinvest profits intelligently with streak bonuses
- **🚦 Rate Limiter** — Centralized API rate limiting prevents getting banned
- **🛑 Remote Kill Switch** — Emergency remote control via hosted JSON file

## Features Overview

### Core Trading
- 3-layer self-learning (statistical + Groq LLM + regime detection)
- EMA fan detection with multi-timeframe confirmation
- Smart position sizing based on wallet balance
- Treasury management with milestone profit locking

### Risk Management  
- Circuit breakers (consecutive loss protection)
- Trailing stops with gain-based tightening
- Partial sells at profit milestones
- Whale/bonding curve detection

### Telegram Commands
Send these to your bot:
- `/status` — Current positions, P&L, regime
- `/pause` — Pause auto-trading
- `/resume` — Resume auto-trading
- `/kill` — Emergency stop
- `/pnl` — Today's P&L summary
- `/positions` — List open positions
- `/treasury` — Treasury status

## How to get your APK (step by step)

### Step 1 — Upload this code to GitHub

On your phone or PC, go to your repository:
**https://github.com/shaunhayes333-stack/lifecycle-bot**

You need to upload all these files. The easiest way is:

1. Open **github.com/shaunhayes333-stack/lifecycle-bot**
2. Click **uploading an existing file** (shown on the empty repo page)
3. Drag and drop the ZIP file you downloaded from this chat
4. Click **Commit changes**

### Step 2 — Watch it build

1. Go to your repo on GitHub
2. Click the **Actions** tab at the top
3. You'll see a workflow called **Build APK** running (yellow circle = building)
4. Wait about **5-8 minutes**
5. When it turns green ✅ — it's done

### Step 3 — Download your APK

1. Click on the completed workflow run
2. Scroll down to **Artifacts**
3. Click **lifecycle-bot-apk** — it downloads a ZIP
4. Unzip it — inside is **app-debug.apk**

### Step 4 — Install on your phone

1. Transfer **app-debug.apk** to your phone (email it to yourself, or use Google Drive)
2. Open it on your phone
3. If prompted "Install unknown apps" — tap **Allow**
4. Install and open **Lifecycle Bot**

---

## First time setup in the app

1. Tap **CONFIG** tab
2. Set your **Active Token Mint** — the Solana token you want to trade
3. Set your **Watchlist** — comma separated mint addresses
4. Leave **Paper mode** ON until you've tested it
5. Set **Auto Trade** to Manual first
6. Tap **SAVE SETTINGS**
7. Go back to **DASH** tab
8. Tap **▶ START**

The bot will start polling Dexscreener and showing live signals.

## Going live (real trades)

1. Create a **burner wallet** — a separate Solana wallet with only the SOL you're willing to risk
2. In CONFIG, paste your **private key (base58)** — it's stored encrypted on your device
3. Set mode to **Live**
4. Set Auto Trade to **Fully automatic** if you want hands-free trading
5. Use a paid RPC URL (Helius.dev free tier is good)

⚠️ **Never use your main wallet. Only use a burner with funds you can afford to lose.**

---

## What the scores mean

| Score | Meaning |
|-------|---------|
| **ENTRY 0-100** | How confident the bot is about entering. Above 45 = will buy |
| **EXIT 0-100** | How urgently the bot wants to exit. Above 62 = will sell |
| **VOLUME** | Is volume expanding (bullish) or contracting (bearish) |
| **PRESSURE** | Are buyers or sellers in control right now |

## Phases

| Phase | Meaning |
|-------|---------|
| launch | Token pumping hard — bot waits for pullback |
| range | Consolidating — bot buys low, rides to exit score |
| distribution | Smart money exiting — bot sells |
| breakdown | Price collapsed — bot exits immediately |
| reclaim_attempt | Possible recovery forming |

## Exit logic (no fixed take-profit)

The bot holds until the **EXIT score hits 62** (default). That score builds from:
- Volume rolling over = +35 pts
- Buy pressure dropping = up to +25 pts  
- Phase turning bad = +25-50 pts
- Momentum reversing = up to +20 pts
- Big unrealised profit = bonus urgency

Lower the threshold to exit sooner. Raise it to ride longer.
