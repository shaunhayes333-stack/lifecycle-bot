package com.lifecyclebot.engine.truth

/**
 * V5.0.7881 — the operator's Solana trader watchlist, and the infrastructure
 * addresses that must never be watched as "smart money".
 *
 * The previous seeds (InsiderWalletTracker, InsiderTrackerAI) streamed full
 * jsonParsed transactions for the Jupiter v6 program, the Raydium AMM
 * authority, Coinbase/Binance/Bybit hot wallets, Jump and Wintermute, and two
 * token mints. accountInclude on a program or mint is every swap through it:
 * billed by the byte, and parsed as a "smart money buy" — 5.0.7876 read
 * 18,052 detections in 68 minutes and a COPY signal of -2.9% over 104 labels.
 */
object OperatorWatchlist7881 {
    /** Operator, 08 Oct 2026: "these are probably the wallets we should be watching on solana". */
    val TRADERS: List<String> = listOf(
        "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9",
        "CyaE1VxvBrahnPWkqm5VsdCvyS2QmNht2UFrKJHga54o",
        "ardinRsN1mNYVeoJWTBsWeYeXvuR9UUDGMsCDKpb6AT",
        "Hw5UKBU5k3YudnGwaykj5E8cYUidNMPuEewRRar5Xoc7",
        "2fg5QD1eD7rzNNCsvnhmXFm5hqNgwTTG8p7kQ6f3rx6f",
        "498g1rVnFcnjBjpfw1xyqA1WvgQXUU8RWuELjxkjAayQ",
        "Bi4rd5FH5bYEN8scZ7wevxNZyNmKHdaBcvewdPFxYdLt",
        "CAPn1yH4oSywsxGU456jfgTrSSUidf9jgeAnHceNUJdw",
        "98T65wcMEjoNLDTJszBHGZEX75QRe8QaANXokv4yw3Mp",
        "4DdrfiDHpmx55i4SPssxVzS9ZaKLb8qr45NKY9Er9nNh",
        "5t9xBNuDdGTGpjaPTx6hKd7sdRJbvtKS8Mhq6qVbo8Qz",
        "8deJ9xeUvXSJwicYptA9mHsU2rN2pDx37KWzkDkEXhU6",
        "BQVz7fQ1WsQmSTMY3umdPEPPTm1sdcBcX9sP7o6kPRmB",
        "3BLjRcxWGtR7WRshJ3hL25U3RjWr5Ud98wMcczQqk4Ei",
        "CUHBzSPSaNS3tArEtM3maSV6pNdJhHJFYZpurPPK9P7H",
        "DYAn4XpAkN5mhiXkRB7dGq4Jadnx6XYgu8L5b3WGhbrt",
        "G2mgnzpr59vYjKpwU9q5zVfS9yQ9HezMwjuqF7LACvR4",
        "3LUfv2u5yzsDtUzPdsSJ7ygPBuqwfycMkjpNreRR2Yww",
        "GfXQesPe3Zuwg8JhAt6Cg8euJDTVx751enp9EQQmhzPH",
        "2T5NgDDidkvhJQg8AHDi74uCFwgp25pYFMRZXBaCUNBH",
        "B3wagQZiZU2hKa5pUCj6rrdhWsX3Q6WfTTnki9PjwzMh",
        "Dgehc8YMv6dHsiPJVoumvq4pSBkMVvrTgTUg7wdcYJPJ",
        "8MaVa9kdt3NW4Q5HyNAm1X5LbR8PQRVDc1W8NMVK88D5",
        "EeXvxkcGqMDZeTaVeawzxm9mbzZwqDUMmfG3bF7uzumH",
        "4BdKaxN8G6ka4GYtQQWk4G4dZRUTX2vQH9GcXdBREFUk",
    )

    private val TRADER_SET = TRADERS.toHashSet()

    /**
     * Programs, exchange hot wallets, market makers and token mints. A stream on
     * any of these is every swap through it, never a trader's decision.
     */
    private val INFRASTRUCTURE: Set<String> = hashSetOf(
        "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4", // Jupiter v6 program
        "5Q544fKrFoe6tsEbD7S8EmxGTJYAKtTVhAW5Q5pge4j1", // Raydium AMM authority
        "2AQdpHJ2JpcEgPiATUXjQxA8QmafFegfQwSLWSprPicm", // Coinbase hot wallet
        "9WzDXwBbmPdCBoccQ9W4TpFWZVxMPKE7j7jWGaK6eMmj", // Binance hot wallet
        "5tzFkiKscXHK5ZXCGbXZxdw7gTjjD1mBwuoFbhUvuAi9", // Bybit hot wallet
        "GThUX1Atko4tqhN2NaiTazWSeFWMuiUvfFnyJyUghFMJ", // Jump Trading
        "ASTyfSima4LLAdDgoFGkgqoKowG1LZFDr9fAQrg7iaJZ", // Wintermute
        "FUAfBo2jgks6gB4Z4LfZkqSZgzNucisEHqnNebaRxM1P", // MELANIA mint
        "rndrizKT3MK1iimdxRdWabcF7Zg7AR5T4nud4EkHBof",  // RENDER mint (was labelled "WIF Treasury")
        "TSLvdd1pWpHVjahSpsvCXUbgwsL3JAcvokwaKt1eokM",  // pump.fun deployer
    )

    fun isOperatorTrader(address: String): Boolean = address in TRADER_SET

    /**
     * Pure: stream/scan priority — operator traders first (in the operator's
     * order), then everything else as given, infrastructure excluded.
     */
    fun prioritise(addresses: List<String>): List<String> {
        val rest = addresses.filter { it !in TRADER_SET && it !in INFRASTRUCTURE }
        return (TRADERS + rest).distinct()
    }
}
