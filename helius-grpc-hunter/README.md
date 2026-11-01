# AATE validator ingestion

This dedicated Node process subscribes to Pump.fun, PumpSwap, Raydium AMM and Raydium CPMM using the official Yellowstone client. It serves the existing AATE Android client over authenticated TLS gRPC. Each phone opens a processed discovery stream and a confirmed evidence stream.

Processed observations warm subscriptions and mint-account requests. Confirmed swaps feed the app's existing tape, chart, wallet-flow, candidate, price-mark and learning-input paths. V3/V4 retains entry authority under the paired Cortex proof contract. Transactions do not directly authorize a trade.

## Run

Use Node 20.18 or later.

```sh
npm ci
npm run check
npm test

export HELIUS_GRPC_ENDPOINT=https://laserstream-mainnet-tyo.helius-rpc.com
export HELIUS_API_KEY='your-existing-subscription-key'
export AATE_RELAY_TOKEN='a-separate-long-random-token'
export TLS_KEY_FILE='/path/to/server-private-key.pem'
export TLS_CERT_FILE='/path/to/fullchain.pem'
export LISTEN_ADDRESS='0.0.0.0:9443'
npm start
```

Choose the Helius region nearest the relay. The API key must have LaserStream access. The relay requires a certificate valid for its hostname.

In AATE's Settings sheet, enter the relay's `https://hostname:9443` address and its separate token in the Yellowstone fields. Save and restart trading to rewire the streams.

Leaving the endpoint blank selects direct Helius mode through Tokyo, using the configured Helius API key. Direct mode uses bounded launch and tracked-mint filters. The Node relay handles the broader program feed on the server. Set an explicit Helius endpoint and token to select a different region.

## Routing and resource limits

The Android requests preserve launch observations and the held/near-entry tape. These pass immediately. The server also examines the broader program feed for a previously unseen mint bought by three distinct observed wallets in four seconds. It forwards the retained transactions and follows that mint for 30 seconds. Native lane rules evaluate the resulting flow.

The relay keeps at most 256 pending mints with four raw transactions each, each below 32 KiB, plus bounded signature and active-mint books. Downstream writes apply stream backpressure. Reconnects use capped exponential delay, and disconnects cancel upstream subscriptions. Four downstream streams allow an old device connection pair to close while its replacement connects.

A program mention alone does not identify a token, buyer or executable opportunity. The actual Pump.fun program is `6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P`. Transaction messages are under `update.transaction.transaction.transaction.message`. Loaded writable and read-only addresses follow the static keys.

The tests exercise real local gRPC connections, credential rejection, filter construction, raw protobuf compatibility, first-print delivery, duplicate suppression and stale or failed observations. Provider-to-phone latency and live profitability require measurement on the configured deployment.
