# Security Policy

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

- [ ] Professional security audit (planned for v1.1)
- [x] Internal code review
- [x] Dependency vulnerability scanning

---

Thank you for helping keep LifecycleBot secure! 🛡️
