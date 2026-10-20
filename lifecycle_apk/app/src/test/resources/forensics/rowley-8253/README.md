# Rowley finalized settlement evidence

Captured from https://api.mainnet-beta.solana.com on 20 October 2026 using getTransaction, encoding jsonParsed, commitment finalized, maxSupportedTransactionVersion 0. The responses are retained verbatim. Signatures were discovered with getSignaturesForAddress for the full mint and matched to the diagnostic by time and exact raw quantities. No transactions were submitted.

The same owner bought 158329976511 raw units, sold 94997985906, then sold the remaining 63331990605. The final transaction post-balance is zero, at six decimals. The token account remains open with 1513840 lamports rent. A later wallet read is not inferred from these transaction snapshots.

Buy wallet debit excluding rent: 29891786 lamports (including network fee and sender tip). Net partial wallet credit: 17153559. Net final wallet credit: 11358235. Combined trading loss excluding refundable token-account rent: 1379992 lamports. Fees and tips already contained in these deltas must not be charged again. The diagnostic's rounded entry basis is not an exact substitute for these values.

The original application partial-exit trigger cannot be reconstructed from blockchain instructions. This fixture verifies the live transaction decoder and finality functions; it does not establish full journal, canonical-ledger or learning replay success.

- `buy.json` SHA-256 `b71608b61e6efc7f4b37e386985d4d3a01e1b1def8f7389c1a6a1224871d1f13`
- `close.json` SHA-256 `b82fecaf16167436bf687fbfc9c7c00aed67d160c4dfba06ee614ed17162f1b2`
- `partial.json` SHA-256 `6a07ccb63dc2c4d9ce3a899a9ae4fd12f85dedb16ca0085429314bae1f24d0a1`
