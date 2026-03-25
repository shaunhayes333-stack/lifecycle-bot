# Security Policy

## Security Architecture

AATE implements enterprise-grade security for protecting your trading credentials:

### Encryption

| Data Type | Encryption Method | Storage |
|-----------|-------------------|---------|
| Wallet Private Key | AES-256-GCM | EncryptedSharedPreferences |
| Groq API Key | AES-256-GCM | EncryptedSharedPreferences |
| Gemini API Key | AES-256-GCM | EncryptedSharedPreferences |
| Jupiter API Key | AES-256-GCM | EncryptedSharedPreferences |
| Helius API Key | AES-256-GCM | EncryptedSharedPreferences |
| Birdeye API Key | AES-256-GCM | EncryptedSharedPreferences |
| Telegram Bot Token | AES-256-GCM | EncryptedSharedPreferences |

### How It Works

1. **Android Keystore** — Master encryption key stored in hardware-backed secure enclave
2. **EncryptedSharedPreferences** — All sensitive data encrypted at rest with AES-256-GCM
3. **Key Encryption** — AES-256-SIV for preference keys, AES-256-GCM for values
4. **Zero Plain Text** — Credentials never stored or logged in plain text

### What This Means

- Even if someone gets your phone, they cannot extract your keys without your device PIN/biometric
- Root access cannot extract the encryption keys (hardware-backed)
- No API keys or secrets in the source code — all entered by user
- Credentials never transmitted to any server (except the intended API endpoints)

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

## Reporting a Vulnerability

We take security seriously. If you discover a security vulnerability in LifecycleBot, please report it responsibly.

### How to Report

1. **DO NOT** open a public GitHub issue for security vulnerabilities
2. Send details to the repository owner via private message
3. Include:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

### What to Expect

- Acknowledgment within 48 hours
- Status update within 7 days
- Credit in the changelog (if desired)

### Scope

Security issues we care about:

- **Private key exposure** - Any way keys could be leaked
- **Unauthorized transactions** - Bypassing approval flows
- **API key exposure** - Leaking user's API credentials
- **Remote code execution** - Malicious code injection
- **Data exfiltration** - Unauthorized data transmission

### Out of Scope

- Trading losses due to market conditions
- Issues with third-party APIs (Jupiter, Helius, etc.)
- Social engineering attacks
- Physical device access attacks

## Security Best Practices for Users

1. **Never share your seed phrase** - The app never asks for it
2. **Use a dedicated trading wallet** - Don't use your main wallet
3. **Start with small amounts** - Test with minimal funds first
4. **Keep API keys private** - Don't share Groq/Gemini keys
5. **Review transactions** - Check wallet activity regularly
6. **Update regularly** - Install security updates promptly

## Audit Status

- [x] **Security refactor complete** — All hardcoded keys removed (Dec 2025)
- [x] **EncryptedSharedPreferences** — Military-grade encryption implemented
- [x] Internal code review
- [x] Dependency vulnerability scanning
- [ ] Professional security audit (planned for v1.1)

---

Thank you for helping keep LifecycleBot secure! 🛡️
