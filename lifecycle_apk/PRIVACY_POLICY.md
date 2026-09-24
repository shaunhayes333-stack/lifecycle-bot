# Privacy Policy for AATE

**Last Updated: September 24, 2026** (app version 5.0.7288)

## Overview

AATE, the Autonomous Algorithmic Trading Engine ("the App"), is a cryptocurrency trading application for Android devices. This Privacy Policy explains what information the App collects, how it is used and how it is protected.

## Information We Collect

### Information You Provide
- **Wallet Private Keys**: stored only on your device, encrypted with AES-256 through Android's EncryptedSharedPreferences, behind a PIN / biometric lock. They are never sent to any server.
- **API Keys**: Helius, PumpPortal, Birdeye, Groq, Gemini, ElevenLabs, Jupiter, Telegram bot token and any other LLM provider keys. They are stored only on your device in the same encrypted storage.
- **Trading Configuration**: your preferences, risk settings and trading parameters.

### Collective Learning Data
Collective Learning (the "hive mind") syncs **anonymized** data to a Turso/libSQL database run by the App's operator. It is enabled by default in the current version. The data is:
- Pattern outcomes (aggregated win/loss statistics, no individual trades)
- Token blacklist contributions (rug/honeypot reports)
- Mode performance by market condition

**NOT shared in Collective Learning:**
- Wallet addresses (only hashed identifiers)
- Trade sizes or amounts
- Personal information
- Individual trade history
- Private keys or API keys

### Information We Do NOT Collect
- Personal identification information
- Location data
- Contacts
- Device identifiers for tracking
- Usage analytics or telemetry

## Data Storage

- **Primary**: on your device, in Android EncryptedSharedPreferences (AES-256)
- **Backup**: optional external storage at `/sdcard/AATE/`, under your control
- **Remote**: the operator's Turso database, for anonymized Collective Learning data only

## Data Security

- AES-256 encryption for all sensitive data
- Hardware-backed Android Keystore where available
- PIN / biometric lock on app entry
- No cloud backup of wallet keys
- No server-side storage of personal data

## Third-Party Services

To work, the App sends requests directly from your device to third-party services. These include:
- **Solana infrastructure**: Helius (RPC, WebSocket, DAS, Sender), public Solana RPC endpoints, Jito
- **Trading and swaps**: Jupiter, PumpPortal, pump.fun, Raydium, Meteora
- **Market data**: DexScreener, Birdeye, GeckoTerminal, CoinGecko, Jupiter Price, Pyth Hermes, Switchboard, DefiLlama, Binance, Kraken, Coinbase, Solscan, GMGN, and market-data providers such as Yahoo, Stooq, Finnhub and Polygon
- **Token safety**: RugCheck
- **LLM providers**: Groq, Gemini, Cerebras, Mistral, OpenRouter and OpenAI-compatible endpoints. They receive token and market context for analysis, never your keys or wallet.
- **Voice (optional)**: ElevenLabs
- **Alerts (optional)**: Telegram, which receives trade alerts if you configure a bot token and chat ID
- **Collective Learning**: Turso

Each of these services has its own privacy policy.

## Your Rights

You can:
- Delete all app data at any time (clear app data or uninstall)
- Clear all stored API keys from Settings
- Export your data from Settings
- View your trade journal and logs in the App

## Changes to This Policy

We may update this Privacy Policy. When we do, the "Last Updated" date will change.

## Contact

For privacy concerns, contact us through GitHub Issues.

---

© 2025–2026 AATE Project.
