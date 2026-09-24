# Security Policy

Covers **AATE — Autonomous Algorithmic Trading Engine**.

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 5.0.7288 (current) | :white_check_mark: |
| Earlier builds     | :x: (update to the current build) |

## Security Features

### Encryption and Access
- **Wallet keys**: AES-256 through Android EncryptedSharedPreferences. They never leave the device.
- **API keys**: the same encrypted storage, with the hardware-backed Keystore where available.
- **At rest**: all sensitive data is encrypted on the device.
- **App lock**: a PIN / biometric unlock is required on entry.
- **Recovery vault**: a multi-chain recovery vault (ETH / BSC / BTC).

### Network Security
- No plaintext transmission of sensitive data
- Keys are never sent to any server. Collective Learning syncs only pattern records (hashed) and a shared blacklist to the operator's Turso database.

### Transaction Security
- Jito bundle MEV protection
- Helius Sender fast submission with a public RPC ladder fallback
- Fresh blockhash before signing
- Slippage protection

### Runtime Protection
- Live safety circuit breaker: minimum wallet of 0.1 SOL, and a halt at a 10% session drawdown
- Executable Entry Authority: shrinks size after losses (×0.65 after one, ×0.35 after two or during a 60 s cooldown)
- Rate limiting and backoff on API and RPC calls
- Wallet reserve protection

## Known Security Considerations

### User Responsibilities
1. **Secure your device**: the app is only as secure as your Android device.
2. **Protect your keys**: never share keys, and never share screenshots with keys visible.
3. **Use a burner wallet**: never your main wallet.
4. **Use paper mode first**: test thoroughly before live trading.
5. **Monitor regularly**: check positions, Pipeline Health and logs often.

### Accepted Risks
- **Smart contract risk**: interacting with DEXs carries inherent risk.
- **Network risk**: Solana congestion can affect trades.
- **Market risk**: cryptocurrency is volatile.

## Reporting a Vulnerability

We take security seriously. If you find a security vulnerability in AATE, please report it responsibly.

### How to Report
1. **DO NOT** open a public GitHub issue.
2. Send the details to the maintainer (contact via their GitHub profile).
3. Include:
   - A description of the vulnerability
   - Steps to reproduce it
   - Its potential impact
   - A suggested fix, if you have one

### What to Expect
- Acknowledgment within 48 hours
- A status update within 7 days
- Credit in the release notes, if you want it

### Scope
In scope:
- Application code vulnerabilities
- Encryption weaknesses
- Authentication bypasses
- Data exposure risks

Out of scope:
- Third-party service vulnerabilities
- Social engineering
- Physical device access attacks
- Known smart contract risks

## Security Checklist for Users

Before using AATE:
- [ ] Device screen lock is on
- [ ] Device is not rooted (unless you understand the risks)
- [ ] Latest Android security patches are installed
- [ ] APK came from the official GitHub Actions build
- [ ] AATE PIN is set
- [ ] Paper mode has been tested extensively
- [ ] Dedicated burner wallet, small amounts only at first

## Acknowledgments

Thank you for helping keep AATE secure.

---

© 2025–2026 AATE Project.
