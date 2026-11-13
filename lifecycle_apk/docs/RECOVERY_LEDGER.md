
## 8731 capture (14:33, uptime 13 min) — "no improvement actually dead"

What the run shows, from its own records:
- LIVE admission: ADMIT 0, REFUSE 287, DEFER_DATA 553. Live closes n11, win 27 %, PF 0.35.
- Forward labels, 60 min, every lane the bot watches: CASHGEN -6.1 %, CORE +1.1 %, Cortex bottom cohorts -12..-16 % in
  every meme lane; every crypto lane negative (CRYPTO_CORE -5.3 % n796, CRYPTO_ALT -3.5 % n355). Refusing most of this is
  what the evidence says, not a malfunction.
- 124 runners (4x+, 24h) seen, 121 missed: STRUCTURAL_RISK 22, liquidity below lane floor 19, EXECUTION_ECONOMICS 12,
  edge model 10. The economics refusals are coins already at their high after a large run; the 8537 dataset (42,178
  deduped rows) prices that state at mean -6..-14 % (runup 60-400 % at the high), with P(+100 %) rising to 6 % - a tail,
  not an edge after cost.
- The one cell that pays (PayingCell8723 OTHER_A1_L3: not pump.fun, under 5 min, pool $10k-50k, +24.6..+32.4 %): 32 LIVE
  coins reached admission in it; 29 were PENDING at the buy door (+36.3 % forward, 34 % runners), 2 PROVEN (-94 %), 0
  LP_BLOCKED. Which proof they waited on was not recorded.

8732: the record now names the pending proof family per coin (RugCheck / mint authority / holders / LP ownership by
sub-reason / pool depth / stale report) and the admission's own terminal for each paying-cell coin
("Paying-cell waits (§8732)"). Nothing about any gate changed. The next capture decides the fix: if the wait is a proof
the bot can read itself on-chain within the cell's 5-minute window, that read is built; if it is LP lock unknown, the
owner's rule stands (no unlocked/unknown LP bought).
