# AATE Lifecycle Bot — Product Requirements Document

## Original Problem Statement
Upgrading a Native Kotlin Android Solana trading bot (AATE) to V5.7+.
Building a super smart SOL Perps/Leverage trading system that reuses
existing AI infrastructure, adding tokenized stocks, multi-asset trading,
an Insider wallet tracker, a live readiness gauge, and a continuous
auto-replay learning system. NO LOCAL COMPILER — all code changes must be
pushed via Git to trigger GitHub Actions CI.

## Session (07-08 Feb 2026) — Phase 2C Swarm Sentience + Live-Green Pivot

### V5.0.6193 — PHASE 2C SWARM SENTIENCE (SHIPPED GREEN)
- **BotPersonalityLayer.kt** — 8 deterministic personas per instanceId
  hash: Alpha aggressor, Beta guardian, Gamma contrarian, Delta momentum,
  Epsilon whale, Zeta chartist, Eta fundamental, Theta wildcard.
  Exposes riskAppetiteMult, entryPickinessDelta, holdConvictionMult,
  rugParanoiaDelta.
- **SwarmVariantABTuner.kt** — per-instance config perturbations
  (entryScore ±3, sl ±0.10, tp ±0.15, lab sizing ±0.05, cofire ±1).
  evolveTowardChampion drifts local config toward best-performing
  swarm winner.
- **InterBotLLMChat.kt** — OBSERVATION/CONFIRM/CONSENSUS message bus.
  ≥3 CONFIRMs within 90s triggers CONSENSUS event. Piggybacks on
  SwarmIntel's Turso channel.

### V5.0.6194 — HARD_BLOCK_FREEZE_AUTHORITY liquidity floor (SHIPPED)
Lowered fdgAuthorityUnknownRouteProof6186 liq floor from \$3,000 to
\$1,200. Operator report showed 148 blocks/minute on ANSEM-profile
launches (\$1.5-\$2.5k liq). Safety already softly allows UNKNOWN auth.

### V5.0.6195 — WINNER PRESS + ENTRY-PRICE HEAL (SHIPPED GREEN)
- RegimeDetector.laneAwareSizeMultiplier — new WINNER PRESS clause: any
  lane with n>=5 AND WR>=50% gets 1.10x during DUMP (not damped).
  Priority-lane floor raised 0.70 -> 0.85.
- OpenPnlSanity heal chain extended: pos.highestPrice fallback,
  currentPrice fallback. Unclogs ENTRY_PRICE_INVALID spam.

### V5.0.6196 — PIVOT-TO-WINNERS ROUTER (SHIPPED GREEN)
DumpRegimeWinnerRouter — FDG live-mode gate. During DUMP or CHOP, HARD
BLOCK live entries on lanes with n>=5, negative SOL PnL, EV<-2.5%,
WR<30%. Force capital into TREASURY (WR 50%, EV +205%), BLUECHIP
(WR 36.8%, EV +84%), Metals (WR 66.7%, EV +54%). Report showed 96
blocks/minute after activation — confirming the router IS firing.

### V5.0.6197 — UNCLOG CHICKEN-EGG (SHIPPED)
Report 2026-07-08 05:56 showed 6196 was over-blocking (FDG: 0/174
allow) because pump.fun-fed meme lanes got blocked as bleeders but
non-meme lanes have no live intake feed. Also PatternGoldenGoose was
vetoing every fresh meme intake (goose_catastrophic on name-pattern
memory that shouldn't apply to brand-new mints).
- BotService intake: bypass PatternGoldenGoose.isCatastrophic for
  fresh-launch sources (PUMP_FUN_NEW, RAYDIUM_NEW_POOL, PUMP_PORTAL_WS).
- FDG pivot router: bypass when FreshLaunchHunter matches ANSEM profile.
- OpenPnlSanity: synthetic 1e-9 basis when all other heals fail.
  Frees stuck RECOVERED_2xKQg4 slot.

### V5.0.6198 — MOONSHOT_HOLD + RPC_FAILOVER + CONFIG_STORE_FIX (SHIPPED GREEN)
- MoonshotHoldMode.shouldSuppressExit wired into Executor.requestSell
  so parabolic runners are actually held to \$3.4M target.
- WalletManager.reconnectViaFallbacks cycles 17 Solana RPC endpoints
  on Helius 429 rate-limit errors.
- ConfigStore.load(ctx) reference fix in the wallet-state bridge.

### V5.0.6199 — SOL-NETWORK-WIDE BLUECHIP INTAKE + PROJECT_SNIPER_LAUNCHPAD (SHIPPED GREEN)
Operator directive: "scan the entire sol network!!! its not a pumpfun bot".
Report 2026-07-08 19:27 showed intake=40 total with 0 SOLANA_BLUECHIP_WATCHLIST
emissions (raw=0 enq=0 durMs=2) because (a) isSeen() short-circuited after
cycle #1 and (b) DexScreener SR=24% storm starved dex.getBestPair() calls.
- scanSolanaBlueChipWatchlist: bypass isSeen() for blue-chip mints (persistent
  watchlist, not one-shot memes); skip only on hard-reject or open position.
- Primary path is now a batched Jupiter lite-api/price/v3 call
  (SR=100%, no key, one call for all 20 mints). DexScreener is now the
  fallback, not the primary.
- Category-aware conservative liquidity floors (SOL_WRAPPED \$5M, DEX_HUB
  \$1.5M, ESTABLISHED_MEME \$800K, LSD \$500K, INFRA \$400K, DeFi \$200K)
  so TokenMetricStageRouter's established-token override fires and routes
  into BLUECHIP/DIP_HUNTER/QUALITY.
- New TokenSource.PROJECT_SNIPER_LAUNCHPAD: scanFreshLaunches tags fresh
  launches passing legit-quality filter (mcap>=\$500K, liq>=\$50K, age>=30m)
  with this source. ScannerSourceBrain now learns launchpad-quality WR
  separately from pump.fun firehose.

## Priority Backlog

### P1 — Ship next
- CorrelationGuard — portfolio-level correlated-holding damper.
- DexScreener 5xx storm mitigation for scanFreshLaunches / scanDex*.
  Add Jupiter price fallback where feasible.
- Wire additional Solana-ecosystem feeders (Jito airdrop tokens,
  Backpack tokens, wSOL wrappers) into blue-chip watchlist as they mature.

### P2 — UI
- Show live persona label (BotPersonalityLayer.label()) on main UI.
- Pilot Log ticker consuming InterBotLLMChat.recent(20).
- Pivot-to-winners banner when router is active (fun to watch).

## Doctrine Rules
- **No local compiler** — push to GitHub Actions CI.
- **Brace parity mandatory** — count `{}` before every push.
- **Doctrine #86 — fail-open** — every gate returns ALLOW on error.
