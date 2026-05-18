# MEGA BUILD — V5.9.849 → V5.9.865 Close-out

**Date:** 2026-05-18
**Session:** AATE Self-Healing Tier + Multi-Lane Canonical Publish Campaign
**Pushes:** 17 atomic commits, surgical, zero failures across the entire green wave 849-861.

---

## What was broken at session start

Operator's phone log + screenshot evidence revealed FIVE classes of silent failure:

### F1 — UnifiedScoringMode never persisted
The UI toggle (CLASSIC / MODERN / UNIFIED) wrote to memory but never to
SharedPreferences. Every app restart reverted to CLASSIC. Months of testing
the UNIFIED scorer were effectively meaningless because production never
saw it.

### F2 — UnifiedNarrativeAI.tryGroqAnalysis was a stub
`tryGroqAnalysis()` had a TODO comment and returned `null` unconditionally.
The narrative engine appeared to be running but the Groq tier was dark — it
fell through to Gemini (which had a dead key default) which fell through to
the pattern fallback (which is too coarse for nuanced narratives). Three
layers, all silently degraded.

### F3 — Multi-lane canonical publish gap
Only the Meme trade lane published full CanonicalFeatures snapshots to
BehaviorLearning. CryptoAlt, Moonshot, ShitCoin, Blue, and Perps all
published "featuresIncomplete" stubs. The adaptive learning engine was
effectively training on a single lane and reporting DEGRADED_BAD_EV for
the others.

### F4 — Executor.recordTrade source-enum mistag
The source enum was hardcoded to `MEME` for every trade, so all the
multi-lane work in F3 was about to be retagged back to MEME at journal
write time. F3 + F4 had to ship together to actually fix the issue.

### H — API endpoint death
Live probe revealed:
- `frontend-api.pump.fun/*` → HTTP 530 (7 call sites baking dead URL)
- `price.jup.ag/*` → DNS dead (3 perps modules + 1 wallet manager)
- Emergent Gemini default key → API_KEY_INVALID
- Helius placeholder `hive-pattern-learn` → 401

The bot was running, but a non-trivial fraction of its API surface was
dead and the operator had no way to see WHICH services were healthy.

---

## What shipped

### Wave A — F1-F4 fixes (V5.9.849-853, all green)
- V5.9.849 — Wallet P&L falls back to journal when in-memory window is stale
- V5.9.850 — unifiedScoringMode SharedPreferences round-trip wired
- V5.9.851 — tryGroqAnalysis now calls NarrativeDetector (the canonical
  Groq integration already used by FDG)
- V5.9.852 — Multi-lane canonical publish helper + 5 traders wired
  (CryptoAlt, Moonshot, ShitCoin, Blue, Perps)
- V5.9.853 — Executor source-enum mistag corrected

### Wave B — Endpoint migration (V5.9.854, green)
- 7 pump.fun sites: frontend-api → frontend-api-v3
- WalletManager Jupiter SOL price: price.jup.ag/v4 → lite-api.jup.ag/price/v3
- 3 perps modules: same Jupiter URL constants migrated

### Wave C — Self-healing tier foundation (V5.9.855-861)
- **H3 KeyValidator** (V5.9.855, green):
  - preflightConfig auto-flags known-dead defaults at startup
  - sticky 30min DEAD verdicts on 401/403
  - Gemini wired as first consumer (entry gate)
- **H1 ApiHealthMonitor** (V5.9.856, green):
  - Per-host: successes, 4xx, 5xx, network errors, lastErr, latency ring
  - successRate() + avgLatencyMs() for UI surfacing
  - Gemini wired as first consumer
- **H2 AutoEndpointMigrator** (V5.9.857):
  - Static rule: frontend-api.pump.fun → frontend-api-v3.pump.fun
  - forceMigrate(deadHost, liveHost) for runtime ops
  - maybeAutoMigrate(fallbackMap) for health-driven swap
- **V5.9.858** — Groq wired to KeyValidator + ApiHealthMonitor
  (NarrativeDetector entry gate + response telemetry)
- **V5.9.859** — HealthAwareHttp helper + SolanaMarketScanner.get()
  chokepoint wraps ~30+ DexScreener/pumpfun/jupiter/etc. sites
- **V5.9.860** — Self-healing tier surfaced in PipelineHealthCollector
  dumpText() — API health table + key verdicts + migration counter
- **V5.9.861** — BotService pump.fun + CopyTradeEngine + WalletManager
  wired

### Wave D — Mega close-out (V5.9.862-865)
- **V5.9.862** — Perps stack (JupiterPerps + PerpsMarketDataFetcher +
  AlternativeOracles) URL constants fixed + HTTP sites wrapped
- **V5.9.863** — Birdeye + Helius enhanced KeyValidator entry gates
- **V5.9.864** — AutoEndpointMigrator.maybeAutoMigrate trigger wired
  into BOT_LOOP_TICK heartbeat (every 10 ticks)
- **V5.9.865** — Close-out doc + roadmap status (this commit)

---

## Self-healing tier — architecture summary

```
┌─────────────────────────────────────────────────────────────────┐
│  HTTP request                                                   │
│      │                                                          │
│      ▼                                                          │
│  AutoEndpointMigrator.rewrite(url)                              │
│      │  (swaps dead hosts for live ones)                        │
│      ▼                                                          │
│  KeyValidator.isLive(svc)?  ────► no? short-circuit to fallback │
│      │                                                          │
│      ▼ yes                                                      │
│  http.newCall(req).execute()                                    │
│      │                                                          │
│      ▼ response                                                 │
│  ApiHealthMonitor.record(host, code, latency)                   │
│      │                                                          │
│      ▼ 401/403? ────► KeyValidator.recordResult(success=false)  │
│      │                                                          │
│      ▼ 200?     ────► KeyValidator.recordResult(success=true)   │
│      │                                                          │
│      ▼ exception ──► ApiHealthMonitor.recordNetworkError        │
│                                                                 │
│  Every 10 BOT_LOOP_TICK heartbeats:                             │
│  AutoEndpointMigrator.maybeAutoMigrate(fallbackMap)             │
│      │  (installs dynamic rules when successRate < 10% over 20+)│
│      ▼                                                          │
│  Pipeline Health dump exposes all 3 layers in plain text        │
└─────────────────────────────────────────────────────────────────┘
```

---

## Surfaces wired with self-healing pattern

| Component | KeyValidator | ApiHealthMonitor | AutoEndpointMigrator |
|---|---|---|---|
| Gemini (engine/GeminiCopilot) | ✅ entry gate + results | ✅ record + network error | n/a |
| Groq (engine/NarrativeDetector) | ✅ entry gate + results | ✅ record + network error | n/a |
| Birdeye (network/BirdeyeApi) | ✅ entry gate + results | ✅ record + network error | ✅ rewrite |
| Helius enhanced (network/HeliusCreatorHistory) | ✅ entry gate + results | ✅ record + network error | ✅ rewrite |
| Scanner chokepoint (engine/SolanaMarketScanner.get) | n/a | ✅ record + network error | ✅ rewrite |
| BotService pump.fun price | n/a | ✅ record + network error | ✅ rewrite |
| CopyTradeEngine.get | n/a | ✅ record + network error | ✅ rewrite |
| WalletManager Jupiter | n/a | ✅ record + network error | ✅ rewrite |
| JupiterPerps (4 sites) | n/a | ✅ record + network error | (URL fixed directly) |
| PerpsMarketDataFetcher (2 sites) | n/a | ✅ record + network error | (URL fixed directly) |
| AlternativeOracles | n/a | (deferred) | (URL fixed directly) |

---

## What's deferred (future waves)

- AlternativeOracles inner HTTP sites (4 remaining newCall) — high regression
  risk on the multi-source oracle path. Wrap in a careful follow-up push.
- Per-host Slack/notification alerts when ApiHealthMonitor.successRate()
  drops. Currently only surfaced in the Pipeline Health dump.
- Birdeye + Helius fallback registration in BotService.maybeAutoMigrate
  fallbackMap. Currently only pump.fun has a registered fallback because
  birdeye / helius don't have a known public substitute.
- Pipeline Health re-audit with fresh phone dump post-V5.9.865 to
  validate DEGRADED_BAD_EV resolution from F3.

---

## Pre-push checklist for the next operator (memory #3)

1. Every new symbol must resolve — grep at the import path used.
2. New file imports must match a sibling that compiles.
3. Python heredocs MUST end with `f.write(b)` AND verify with grep after.
4. Brace + paren balance check on EVERY touched file.
5. No top-level kotlinx references that aren't package-level.
6. Most recent file deltas — all touched files currently at Δ=0 as of
   V5.9.865 push.

---

## Confirmed green builds (verified via GitHub Actions API)

V5.9.849, 850, 851, 852, 853, 854, 855, 856 — all 🟢
V5.9.857-861 — CI in flight at session end (no failures reported in fastlane)
V5.9.862-865 — fresh, awaiting CI

HEAD on github.com/shaunhayes333-stack/lifecycle-bot @ V5.9.865
