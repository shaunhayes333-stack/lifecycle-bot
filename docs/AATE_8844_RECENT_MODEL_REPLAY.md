# AATE 5.0.8844 — A model reboot must learn the latest runner examples

## Evidence from the owner's 17 November edge export

- `aate_edge_dataset_8537_1794923941923.jsonl.txt` has **61,087** physical rows.
- The prior `EdgeModel8537.replayDataset8642` stopped at **the first 60,000** with `if (seen++ >= DATASET_MAX_LINES) return@forEachLine`.
- The **1,087 omitted newest rows** have 372 `fill=true` labels (all after the clean-learning epoch); four of those newest rows were labelled with net returns of at least +500%. These are data labels, **not** evidence that a live executable trade would have realised +500%.
- `appendDataset` can exceed 60,000 physical lines because trimming is triggered by every 5,000 *learned fillable* outcomes, while unfillable exported rows also grow the file.

## Source-level repair

- `EdgeModel8537.streamLatestDatasetRows8844`: count physical lines, then replay **the newest 60,000 in original order** with a second streaming pass and bounded memory. It never replays earliest rows at the expense of the newest ones; it does not load the JSONL into an Android heap array.
- Periodic trimming now also runs every 5,000 physical appends, independently of how many observations are fillable, preventing unfillable-heavy exports from growing without bound while the model is cold.
- Keep the existing verified-venue, epoch, horizon, fillability, unchanged-price and implausible-return checks. A spectacular but phantom forward label does not become model truth merely by being recent.
- `Aate8844LatestModelReplayTest` covers over-cap and exact/below-cap files and asserts the production replay calls the new bounded-tail mechanism.

## Acceptance

1. CI release build, required regressions, complete unit suite, signed APK and emulator smoke on one exact `main` SHA.
2. On the installed build, inspect `edge8537[... replay8642=...]`, model/stacker selection, FDG allow/execute and wallet-confirmed fills/exits.
3. A green build confirms engineering and test gates. Live positive net returns and 600–1,100% holds require separately observed canonical evidence; none are implied by these tests.
