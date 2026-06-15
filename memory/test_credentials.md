# AATE Test Credentials

This project is a Native Kotlin Android trading bot. There are no web-app
auth credentials — login flows for the testing agent are N/A. The bot
authenticates to Solana via a user-imported keypair stored encrypted on
device (see `LiveKeyVault`); no test wallet is bundled.

## CI

GitHub Actions workflows in `.github/workflows/` run on push to `main`.
No secrets are required by the agent; CI keys are scoped via repository
settings (`GITHUB_TOKEN`, plus optional release token).
