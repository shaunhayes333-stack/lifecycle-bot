AATE — Autonomous Algorithmic Trading Engine · v5.0.7288

# Competitive Analysis (qualitative)

> This comparison is qualitative and covers product models. It includes no competitor statistics, user counts or revenue figures. Individual tools change often, so check a specific competitor feature before citing it externally.

---

## 1. The landscape in one line each

- **Photon, BullX, Trojan, GMGN, Banana Gun, BonkBot.** Fast, popular Telegram and web trading tools. They are **driven by the user's clicks**. The human picks the token, the size and the exit, and the tool executes quickly and conveniently.
- **Generic grid / DCA bots.** Rule-driven automation, such as buying on a schedule or placing orders in a price grid. They are autonomous in a narrow sense, but the rules are fixed and set by the user.
- **AATE.** An **autonomous engine** that decides, sizes, exits and learns **on-device** (Android). It has one canonical ledger and counts every decision and refusal forensically.

## 2. Different jobs

| Question | Click-driven tools | Grid / DCA bots | AATE |
|---|---|---|---|
| Who picks the trade? | The user | A fixed rule | Per-lane scoring AIs plus the FinalDecisionGate and Predictive Entry Oracle |
| Who sizes it? | The user | A fixed rule | A realistic sizer: sized to what can be exited, with a fee-aware floor |
| Who exits? | The user, or simple TP/SL | A fixed rule | 1 Hz mark loop, sliding profit lock, trailing stops, runner profiles, learned exit policy |
| Does it learn? | Not the tool's job | No | Oracle with edge proof, on-device TFLite, collective learning |
| Where does it run? | Telegram / web | Exchange or server | On the user's phone |

The click-driven tools are strong at what they do. AATE isn't a faster buy button. It removes the human from the decision loop.

## 3. AATE capability checklist

The legend is from AATE's own point of view:
- **✓** means shipped in 5.0.7288.
- **partial** means it exists but is staged or paper-only.
- **✗** means not available.

Competitor columns are left out on purpose, because we don't publish unverified competitor feature claims.

| Capability | AATE status |
|---|---|
| Autonomous entry decisions (no user click) | ✓ |
| Autonomous sizing (realistic, exit-sized, fee-aware) | ✓ |
| Autonomous exits (sliding profit lock, trailing, runner profiles) | ✓ |
| Pre-trade rug/safety filters (HardRugPreFilter, TokenSafetyChecker, RugCheck, creator refusal) | ✓ |
| Predictive Entry Oracle with binary ADMIT / REFUSE verdict | ✓ |
| Oracle Edge Proof (earns authority on real closes, self-demotes) | ✓ |
| LLM council for narrative and scam checks, off the hot path | ✓ |
| On-device ML (TensorFlow Lite) | ✓ |
| Collective learning (anonymized patterns, shared blacklist) | ✓ |
| Copy-trading from mined smart-money wallets | ✓ |
| LLM Lab (LLM-invented strategies, paper-traded, approval queue) | ✓ |
| Paper mode charged real venue costs (PaperVenueCost) | ✓ |
| Canonical single ledger for positions and capital | ✓ |
| Forensic logs plus Pipeline Health (every refusal counted) | ✓ |
| MEV protection (Jito bundles) plus fast submission (Helius Sender) | ✓ |
| Keys on device only (AES-256, biometric lock) | ✓ |
| Live circuit breakers (min wallet, drawdown halt, loss-streak, daily cap) | ✓ |
| Live execution on Solana meme lanes | ✓ (operator-enabled set) |
| Live execution on crypto alts / markets lanes | partial (staged, paper-first) |
| Live tokenized stocks / forex | partial (quarantined in live, paper only) |
| Live perps | partial (SOL perps learn in paper, live not executing) |
| iOS app | ✗ |
| Web monitor | ✗ (roadmap) |

## 4. Where AATE is ahead (by design)

1. **Autonomy end to end.** Candidate → safety → scoring → oracle → single entry authority → sizing → exit → learning, with no human in the loop.
2. **Authority has to be earned.** The oracle stays advisory until it beats its own REFUSE bucket on real closes (≥20 ADMIT, ≥10 REFUSE, ≥2pp mean edge, Brier ≤ 0.25). It demotes itself when the edge fades.
3. **Honest paper.** Paper fills pay pump.fun curve fees, PumpSwap and creator tiers, AMM fees, network fees, modelled price impact and the app fee. A round trip costs about 5–6% on the curve and 2–3% on graduated pools.
4. **Forensic accounting.** One ledger and one capital view. Every refusal reason is counted.
5. **Breadth in one engine.** 16 traders (meme lanes, crypto alts, tokenized markets, perps) share one learning loop.
6. **Self-custody on the device.** Keys never leave the phone.

## 5. Where AATE is behind (honestly)

- **Live execution for non-meme lanes is staged.** Crypto alts, markets and perps are paper-first. Stocks and forex are quarantined in live, and live perps are not executing yet.
- **The live track record is small.** The headline numbers are from one **PAPER** session: ~11.5 min, 79 closed trades, 5.0.7288. That's a small sample, and paper is not live. A live calibration run to verify real fills and fees against paper comes next on the roadmap.
- **Android only.** There's no iOS app or web monitor yet. Click-driven tools reach users through Telegram and the browser on any device.
- **Solo builder.** One developer means a concentrated bus factor, even with 2,699 tests and 16 CI validators.
- **Distribution and brand.** The established tools already have communities and name recognition. AATE is early.
- **Execution speed is not the pitch.** AATE uses Helius Sender and Jito, but it doesn't claim to be faster than dedicated sniping tools.

## 6. Positioning statement

> For traders who want a disciplined machine rather than a faster button, AATE is an autonomous trading engine on their own phone. It decides, sizes, exits and learns, and it accounts forensically for every step. "Real data and forensic accounting. No imagined gains. No inferred values."

---

*Trading crypto is high risk. Paper results are not live results. Not financial advice.*
