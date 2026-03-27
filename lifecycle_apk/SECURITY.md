# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.2.x   | :white_check_mark: |
| 1.1.x   | :white_check_mark: |
| 1.0.x   | :x:                |

## Security Features

### Encryption
- **Wallet Keys**: AES-256 via Android EncryptedSharedPreferences
- **API Keys**: Hardware-backed Keystore where available
- **At Rest**: All sensitive data encrypted on device

### Network Security
- DNS-over-HTTPS for Jupiter API calls
- Certificate pinning (configurable)
- No plaintext transmission of sensitive data

### Transaction Security
- Jito MEV bundle protection
- Fresh blockhash before signing
- Transaction simulation before execution
- Slippage protection

### Runtime Protection
- Circuit breakers for loss limits
- Kill switch for emergency stops
- Rate limiting on API calls
- Wallet reserve protection

## Known Security Considerations

### User Responsibilities
1. **Secure your device**: The app is only as secure as your Android device
2. **Protect your keys**: Never share screenshots with keys visible
3. **Use paper mode first**: Test thoroughly before live trading
4. **Monitor regularly**: Check positions and logs frequently

### Accepted Risks
- **Smart contract risk**: Interacting with DEXs carries inherent risk
- **Network risk**: Solana network congestion can affect trades
- **Market risk**: Cryptocurrency is volatile

## Reporting a Vulnerability

We take security seriously. If you discover a security vulnerability in AATE, please report it responsibly.

### How to Report
1. **DO NOT** create a public GitHub issue
2. Email details to the maintainer (via GitHub profile)
3. Include:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

### What to Expect
- Acknowledgment within 48 hours
- Status update within 7 days
- Credit in release notes (if desired)

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
- [ ] Device has screen lock enabled
- [ ] Device is not rooted (unless you understand the risks)
- [ ] Latest Android security patches installed
- [ ] App downloaded from official source
- [ ] Paper mode tested extensively
- [ ] Small amounts only initially

## Acknowledgments

Thank you for helping keep AATE secure! 🛡️
