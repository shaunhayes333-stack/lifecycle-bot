# Privacy Policy for AATE

**Last Updated: March 27, 2025**

## Overview

AATE ("the App") is a cryptocurrency trading application for Android devices. This Privacy Policy explains how we collect, use, and protect your information.

## Information We Collect

### Information You Provide
- **Wallet Private Keys**: Stored locally on your device using AES-256 encryption via Android's EncryptedSharedPreferences. Never transmitted to any server.
- **API Keys**: Jupiter, Helius, Birdeye, Groq, Gemini, Telegram - stored locally in encrypted storage.
- **Trading Configuration**: Your preferences, risk settings, and trading parameters.

### Collective Learning Data (Optional, Opt-In Only)
If you enable Collective Learning, the following **anonymized** data may be synced:
- Pattern outcomes (aggregated win/loss statistics, no individual trades)
- Token blacklist contributions (rug/honeypot reports)
- Mode performance by market condition

**NOT shared in Collective Learning:**
- Wallet addresses (only hashed identifiers)
- Trade sizes or amounts
- Personal information
- Individual trade history
- API keys

### Information We Do NOT Collect
- Personal identification information
- Location data
- Contacts
- Device identifiers for tracking
- Usage analytics or telemetry

## Data Storage

All data is stored locally on your device:
- **Primary**: Android EncryptedSharedPreferences (AES-256)
- **Backup**: Optional external storage at `/sdcard/AATE/` (user-controlled)
- **Remote**: Turso database (opt-in Collective Learning only)

## Data Security

- AES-256 encryption for all sensitive data
- Hardware-backed Android Keystore where available
- No cloud backup of wallet keys
- No server-side storage of personal data

## Third-Party Services

The App connects to the following services for functionality:
- **Jupiter API**: DEX aggregation for swaps
- **DexScreener**: Price data
- **Birdeye**: Chart data (optional)
- **Helius**: RPC services (optional)
- **RugCheck.xyz**: Token safety analysis
- **Turso**: Collective learning sync (optional)

These services may have their own privacy policies.

## Your Rights

You can:
- Delete all app data at any time
- Export your trade journal
- Disable Collective Learning
- View all stored data through the app

## Changes to This Policy

We may update this Privacy Policy. Changes will be noted with an updated "Last Updated" date.

## Contact

For privacy concerns, contact us through GitHub Issues.
