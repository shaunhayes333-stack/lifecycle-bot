AATE — Autonomous Algorithmic Trading Engine · v5.0.7288

# Investor Email Templates

Keep these short. Only use numbers from the fact sheet. Label paper as paper, every time, and keep the disclaimer line in the email.

---

## 1. Cold outreach (short)

**Subject:** Autonomous Solana trading engine, built solo on a phone. Raising $500K seed

Hi [Name],

I'm [Founder Name]. I built AATE (Autonomous Algorithmic Trading Engine), a native Android app that runs a full autonomous trading engine on the phone. It finds candidates, vets them for rugs, sizes positions, exits and learns. No clicks are needed, and keys never leave the device.

A few facts:
- 16 traders in one engine: Solana meme lanes, crypto alts, tokenized markets, and perps in paper.
- ~457K lines of Kotlin, 2,699 tests, and 16 custom CI validators gating every build.
- The latest **PAPER** run (one ~11.5 min session, 79 closed trades) grew equity from ≈10 to 31.34 SOL after venue-priced fees. It's a small sample, and paper is not live.

I'm raising a $500K seed to move from paper edge to verified live behaviour. Would you have 20 minutes for a demo in the next couple of weeks?

[Founder Name]
[Link to deck] · [Contact]

*Trading crypto is high risk. Paper results are not live results. Not financial advice.*

---

## 2. Warm intro request (to a mutual contact)

**Subject:** Intro to [Investor Name] at [Fund]?

Hi [Mutual Contact],

Would you be comfortable introducing me to [Investor Name] at [Fund]? I'm raising a $500K seed for AATE, an autonomous Solana-first trading engine that runs entirely on Android.

Here's a forwardable blurb:

> [Founder Name] built AATE (Autonomous Algorithmic Trading Engine) solo, from a phone. It's an on-device engine that decides, sizes, exits and learns, with forensic accounting of every trade and refusal. Paper fills are charged real venue costs, and its entry oracle has to prove an edge on real closes before it gets authority. Raising a $500K seed.

Thanks either way,
[Founder Name]

---

## 3. Follow-up after demo

**Subject:** AATE follow-up: what you saw, and the caveats

Hi [Name],

Thanks for the time on [Date]. To recap what we covered:

- **The pipeline:** safety filters, per-lane scoring, the Predictive Entry Oracle (a binary ADMIT / REFUSE verdict) and a single Executable Entry Authority before capital.
- **Oracle Edge Proof:** the oracle stays advisory until it has ≥20 ADMIT and ≥10 REFUSE closes, an ADMIT mean at least 2pp better, and a Brier score ≤ 0.25. It demotes itself if the edge fades.
- **Paper realism:** a round trip costs about 5–6% on the pump.fun curve and 2–3% on graduated pools, charged to paper fills.
- **The PAPER run from 5.0.7288:** 79 closes in ~11.5 min, realized +20.52 SOL after 0.89 SOL of fees, profit factor 7.59, win rate 52.6%. That's one short session and a small sample, and it isn't live.

What the $500K seed funds next: sustained paper profitability, then a small live calibration run to check real fills and fees against paper, then live lanes one at a time.

Happy to share [deck / repo walkthrough / Pipeline Health screenshots]. Any questions for me?

[Founder Name]

*Trading crypto is high risk. Paper results are not live results. Not financial advice.*

---

## 4. Technical investor / angel (engineering-led)

**Subject:** 457K lines of Kotlin, 16 custom CI validators, one developer on a phone

Hi [Name],

Given your background in [area], you might like the engineering behind AATE, an autonomous trading engine that runs entirely on Android:

- **Single sources of truth:** a Canonical Position Authority (idempotent ledger) and a Canonical Capital Authority (cash, reserved, open cost, unrealized, realized, fees).
- **Execution:** Jupiter, a PumpPortal fallback, and direct pump.fun curve reads over an RPC ladder. Orders go through Helius Sender with a tip envelope, use Jito bundles for MEV protection, and fall back to a public RPC ladder.
- **Reliability:** a supervised, self-healing 1 Hz mark loop. The last paper session ran 469 ticks in 687 s with 0 stale resets.
- **CI:** 16 custom static-analysis validators, including golden tape, economic units, authority contradiction and lane identity, plus an emulator runtime smoke test.

I'm raising a $500K seed. Could I walk you through the code for 30 minutes?

[Founder Name]

*Paper figures are paper only. Trading crypto is high risk. Not financial advice.*

---

## 5. Short nudge (no reply after ~1 week)

**Subject:** Re: AATE, autonomous Solana trading engine

Hi [Name],

Just a quick bump on this. Since I last wrote, AATE has shipped [latest build / change, e.g. "venue-priced paper fees and a persistent oracle edge proof"]. I'm still raising a $500K seed, and I'm happy to send a 5-minute screen recording if that's easier than a call.

[Founder Name]

---

### Rules for editing these templates
- Never add valuations, projections, user counts or AUM.
- Always mark paper numbers as **PAPER**, with the sample size (79 closes) and the duration (~11.5 min).
- "$50 → $1,000,000" may appear only as the stated goal, never as a result.
- Use the exact product name: **AATE — Autonomous Algorithmic Trading Engine**.
