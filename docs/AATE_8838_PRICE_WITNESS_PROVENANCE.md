# AATE 5.0.8838 — Price-witness provenance closure

Base: exact 5.0.8837 main tree. This change is an additional root-cause repair found while tracing the full price/learning path; 8837's model-lane isolation and mark unification remain unchanged. It does not claim improved profitability without new real fills.

## Defects found in source

1. **Later witness authenticated an earlier unverified mark.** `PrintWitness8750.witnessedPrint8750(mint, atMs)` checked `lastWitnessed.timestamp >= atMs`. `TradeTape8100.note8285` retains UNWITNESSED (but not out-of-band) tape prints for limited observational purposes. The newest acceptable price arriving later could therefore retroactively validate a different earlier price through `lastPrint8360` / `ForwardReturnLabeler7731` / shadow and crypto mark consumers. Replaced the monotonic timestamp check with equality and changed **all production tape-mark readers** to bind both `(observedAtMs, priceSol)` to the latest actual witnessed observation. Unknown/older/mismatched marks abstain.
2. **Anonymous quotes could promote a new market consensus.** `shiftIndependent8801` rejected duplicated *nonblank* transaction signatures but allowed three changing prices whose signatures and traders were all blank. The code's stated criterion of independent on-chain transactions was not enforced. A 3-print repricing now requires a distinct nonblank transaction signature per print; named wallets must include at least two distinct identities. A genuine distinct-trade crash remains eligible.
3. **Future prints participated in past medians.** `median8800` checked only `atMs - printTime <= 3min`, which includes negative differences. A late out-of-order print could be graded against observations newer than itself. It now accepts only observations with age in `[0, 3min]` and finite positive prices.
4. **The 8776 source regression pinned code retired by 8837.** It still required `ShadowMark8360`'s removed divergent tick-first/fallback implementation. Updated its assertions to require the canonical `observedTimed8372` path, the surviving intake-seed guard and witnessed price+time. The 8750 and 8800 tests now require price-bound witness reads and signed independent re-pricing respectively.

## Tests

`Aate8838WitnessProvenanceTest` reproduces the timestamp/price authentication issue, anonymous repricing, forward-time median contamination, and audits all callers of the witnessed tape mark. Existing 8750, 8776 and 8800 regressions were updated to enforce the new contract. Source checks run locally; the authoritative Kotlin/Android compile and full test suite run in GitHub Actions from the pushed commit.

## Boundaries

- No wallet, risk, signer, trading authority, buy thresholds or sell protections are bypassed.
- A label from a now-unwitnessed historical mark cannot borrow confidence from a later transaction; no backfill fabrication is introduced.
- Requiring exact matching may produce more **missing marks**, which must be measured. The app's pool/curve/Jupiter fresh-mark fallback still operates, and an unknown observation is preferable to a false profit or phantom rug.
- The 8833 sample predates these changes and PumpPortal funding. After release, compare by-mint witnessed vs refused marks, labelled coverage, buy/exit quote parity and real after-cost returns before claiming the lost 600–1100% trading capability is restored.
