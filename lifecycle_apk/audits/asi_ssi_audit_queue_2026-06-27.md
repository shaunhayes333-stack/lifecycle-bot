# AATE ASI / Super-Symbolic Intelligence Audit Queue — 2026-06-27

North star: realized live wallet growth toward the operator's daily 2x-5x compound target, while preserving catastrophic safety, live/paper parity, source balance, no hot-path API calls, and no learned zero-sizing.

## Bundle A — Symbolic safety + compound invariants

### A13 — SymbolicInvariantProver / Z3 guardrail layer
- Implement a local symbolic/proof layer, preferably Z3-backed or equivalent rule solver.
- Purpose: prove critical execution invariants before builds and/or during background health checks.
- Initial invariants:
  - learned strategy/state cannot hard-block candidate unless true hard safety whitelist applies
  - learned strategy cannot return zero size unless hard safety or explicit manual emergency state
  - routing reject must not become terminal reject
  - live/paper attribution must be event-local
  - proofState/mode/lane/source must survive journal/learning fanout
  - compound multiplier cannot dust-size below lane minimum without telemetry
  - cap/permit/authorizer release must happen on every BUY_NOT_OPENED/finality failure path
- Runtime: CI/background only. No scanner/executor hot-path dependency.
- Expected impact: fewer regressions, stronger live/paper parity, faster safe iteration.

### A14 — Compounding math sanity verifier
- Add tests around AutoCompoundEngine and lane consumers to verify realized wins actually increase buy-size authority across ShitCoin/Moonshot/Express/Sniper/Manipulated/Quality/BlueChip/Treasury.
- Verify drawdown reduction still protects catastrophic bleed.
- Expected impact: direct support for daily live wallet compound target.

## Bundle B — Free-tier async LLM strategy lab

### A15 — AsyncStrategyLab using free-tier providers
- Candidate free API providers:
  - Gemini Developer API free tier: best first choice for reasoning + embeddings/prototyping.
  - Groq free/developer tier: fast OpenAI-compatible inference for cheap background summaries/critics.
  - OpenRouter free models: optional fallback abstraction.
- Must be background-only. Never scanner/FDG/executor hot path.
- Inputs: closed trades, Golden Tape diffs, lane WR/PF, PnL outliers, rug/finality logs, source/lane balance.
- Outputs: testable hypotheses for StrategyHypothesisEngine, not direct trade commands.
- Gating: every hypothesis must include affected lane, expected metric, rollback condition, and symbolic invariant check.
- Expected impact: faster discovery of profitable rule changes without live-risk LLM calls.

### A16 — Multi-agent critic/judge stack
- Use multiple roles:
  - fast summarizer: compress newest logs/trades
  - strategist: proposes hypotheses
  - skeptic: finds leak/safety/mux regressions
  - symbolic judge: checks invariants before acceptance
- Store only accepted hypotheses into a persistent hypothesis bank.
- Expected impact: higher-quality strategy proposals, less yes-man logic.

## Bundle C — GEPA / reflective prompt-policy evolution

### A17 — GEPA-style reflective optimizer
- Implement DSPy/GEPA-inspired offline optimizer for prompts/policy text/rule weights.
- Use terminal trade outcomes as the objective, not cosmetic explanations.
- Candidate optimized artifacts:
  - StrategyHypothesisEngine prompts
  - symbolic reason labels
  - lane diagnostic/risk summaries
  - operator report summaries
  - AsyncStrategyLab scoring rubrics
- Must use held-out windows to avoid overfitting.
- Expected impact: self-improving symbolic reasoning quality.

## Bundle D — Semantic memory and counterfactual replay

### A18 — SemanticPatternGraph
- Use Gemini embeddings free tier or local embeddings to map setups/outcomes into similarity memory.
- Nodes: token setup, lane, source, liquidity state, narrative cluster, holder geometry, rug/safety overlay, exit path, realized outcome.
- Edges: similar_setup, same_deployer, similar_exit, same_source_bias, same_failure_mode, runner_family.
- Use at entry as cached readback only; update asynchronously after closes.
- Expected impact: pattern recognition beyond scalar counters.

### A19 — CounterfactualReplayEngine / MCTS exit planner
- Offline replay closed positions and simulate alternative exit decisions:
  - earlier take-win
  - later runner hold
  - wider/tighter dynamic stop
  - partial ladder timing
  - min-hold/settle bypass alternatives
- Use results to feed exit-policy hypotheses, not direct hot-path calls.
- Expected impact: better runner capture and avg_win/avg_loss curve.

## Bundle E — ResearchScout

### A20 — Free-API ResearchScout
- Periodically fetch AI/crypto infra developments and add candidate upgrades to the audit queue.
- Sources should be cached and summarized in background.
- Never use as a trading signal without passing through StrategyHypothesisEngine + symbolic checks.
- Expected impact: keeps AATE from freezing at current AI architecture while the field moves quickly.

## Patch ordering suggestion
1. 4234 — AutoCompound live-growth profile.
2. 4235 — Golden Tape/Symbolic invariant test harness skeleton for hard-block/zero-size/routing reject rules.
3. 4236 — StrategyHypothesisEngine async provider abstraction, no external key required yet.
4. 4237 — Gemini/Groq connector stub + background-only guard + secret/env plumbing.
5. 4238 — SemanticPatternGraph schema/cache, local-only first.
6. 4239 — CounterfactualReplayEngine offline replay for exit alternatives.
7. 4240 — AsyncStrategyLab first read-only reports into hypothesis bank.
