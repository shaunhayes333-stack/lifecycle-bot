# LIFECYCLE BOT V5.2
## Quick Start Guide

---

# Welcome!

You're about to set up the most advanced AI-powered Solana trading bot available on mobile. This guide will get you trading (on paper first!) in under 10 minutes.

---

# Step 1: Install the App

## Download
1. Go to the Releases page
2. Download `lifecycle-bot-v5.2-release.apk`
3. Transfer to your Android device (or download directly)

## Install
1. Open the APK file
2. If prompted, enable "Install from Unknown Sources"
3. Complete installation
4. Launch the app

---

# Step 2: Initial Setup

## Wallet Configuration

You'll need a Solana wallet private key. **Use a dedicated trading wallet, not your main wallet!**

### Creating a New Wallet (Recommended)
1. Use Phantom or Solflare to create a new wallet
2. Export the private key (Base58 format)
3. Fund with SOL for trading

### Entering Your Key
1. Open Lifecycle Bot
2. Go to Settings > Wallet
3. Paste your private key
4. The key is encrypted and stored locally

**SECURITY NOTE**: Your private key never leaves your device. No cloud servers are involved.

---

# Step 3: API Keys

## Required APIs (Free Tiers Available)

### Helius (Required)
1. Go to [helius.dev](https://helius.dev)
2. Create free account
3. Copy your API key
4. Paste in Settings > API Keys > Helius

### Birdeye (Required)
1. Go to [birdeye.so](https://birdeye.so)
2. Create free account
3. Copy your API key
4. Paste in Settings > API Keys > Birdeye

### Optional APIs
- **Groq**: For AI chat features
- **Gemini**: For advanced analysis

---

# Step 4: Paper Trading Mode

**IMPORTANT**: Always start with paper trading!

## Why Paper Trade?
- Zero risk while learning
- Bot calibrates to market conditions
- You learn the bot's behavior
- Build confidence before real money

## Enable Paper Mode
1. Go to Settings
2. Toggle "Paper Mode" ON (should be default)
3. Paper balance starts at 5 SOL

## Recommended Paper Trades
- Minimum: 50 trades
- Recommended: 100+ trades
- Watch learning progress reach 20%+

---

# Step 5: Start the Bot

## Launch Trading
1. Return to main dashboard
2. Tap the START button
3. Bot begins scanning and trading

## What You'll See
- Scanner activity in logs
- Token discoveries
- Trade executions (paper)
- P&L updates

## Dashboard Tiles
- **🎯 V3 Core**: Main quality trades
- **💰 Treasury**: Scalping mode
- **🔵 BlueChip**: Large cap trades
- **💩 ShitCoin**: Degen plays
- **🚀 Moonshot**: 10x-1000x hunting

Tap any tile for detailed stats!

---

# Step 6: Monitor & Learn

## Key Metrics to Watch

### Win Rate
- Paper mode: Expect 40-60%
- Varies by layer and market conditions

### Learning Progress
- Shows as percentage
- Higher = more calibrated
- Affects confidence thresholds

### Layer Performance
- Each layer has its own stats
- Some layers suit different markets

## Log Analysis

Watch the log feed for:
- `ENTRY`: Trade opened
- `EXIT`: Trade closed
- `SHADOW`: Trade tracked but not executed
- `REJECT`: Trade filtered out

---

# Step 7: Going Live

## Pre-Live Checklist

- [ ] 100+ paper trades completed
- [ ] Learning progress > 20%
- [ ] Win rate acceptable (40%+)
- [ ] Understand each layer's behavior
- [ ] Funded wallet ready
- [ ] Risk parameters reviewed

## Switch to Live

1. Go to Settings
2. Toggle "Paper Mode" OFF
3. Confirm the warning
4. Bot now trades with real SOL

## Live Trading Tips

- Start with small positions
- Monitor first few live trades closely
- Keep daily loss limits enabled
- Don't override the bot's decisions

---

# Quick Reference

## Layer Summary

| Layer | Risk | Target | Best For |
|-------|------|--------|----------|
| V3 Core | Medium | 15-35% | Balanced trading |
| Treasury | Low | 4-15% | Consistent gains |
| BlueChip | Low | 10-40% | Established tokens |
| ShitCoin | High | 25-100% | Degen plays |
| Moonshot | High | 100-1000% | Big wins |

## Common Settings

| Setting | Conservative | Balanced | Aggressive |
|---------|-------------|----------|------------|
| Position Size | 50% | 100% | 150% |
| Take Profit | Default | Default | +20% |
| Stop Loss | Tighter | Default | Wider |
| Max Concurrent | Lower | Default | Higher |

---

# Troubleshooting

## Bot Not Starting
- Check API keys are valid
- Ensure wallet has SOL
- Check internet connection

## No Trades Happening
- Market may be quiet
- Confidence thresholds may need calibrating
- Check layer enables in settings

## High Loss Rate
- Reduce position sizes
- Enable more conservative layers only
- Check market regime (bear market?)

## App Crashing
- Clear app cache
- Reinstall latest version
- Export backup first!

---

# Getting Help

## Resources
- **Docs**: Full documentation in /docs folder
- **Telegram**: Community chat
- **Twitter**: @LifecycleBot
- **GitHub**: Issues and discussions

## Exporting Logs

If reporting issues:
1. Go to Settings > Export
2. Export error logs
3. Include in bug report

---

# Safety Reminders

1. **Only trade what you can afford to lose**
2. **Use a dedicated trading wallet**
3. **Start with paper trading**
4. **Keep position sizes small initially**
5. **Enable daily loss limits**
6. **Monitor the bot regularly**

---

*Happy Trading!*

*Remember: Trade smarter, not harder.*
