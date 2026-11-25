package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.9020 §THE_OWNERS_NAMED_TRADERS — owner, 25 Nov 22:05: "if the top holders are all kols or good traders thats ok!",
 * then the list ("top 50 below") and 22:19: "these should also be wallets we follow and copy trade".
 *
 * The list: the 50 most profitable pump.fun / PumpSwap wallets of the trailing 15 days (dEdge on-chain leaderboard,
 * snapshot 25 Nov 2026, as the owner supplied it; +32 to +2,259 SOL each), in rank order. Every address decodes to a
 * 32-byte key; none could be checked against the leaderboard from the build machine. A wrong address never matches a
 * holder and never trades, so it changes nothing.
 *
 * What the phone showed before this build (5.0.9016, 21:53): topTraders8713=0/50 (pump.fun developer key missing, GMGN
 * benched), the smart-money stream subscribed to 3 wallets (whaleAccounts=3 of 26 asked: its width is scaled by the mined
 * copy book's record, PAPER_ONLY n=3175 mean-SE -2.8 %), buysSeen=0. The bot was following almost nobody.
 *
 *  FOLLOW   Each named wallet joins the copy list once (CopyTradeEngine.seedNamed9020, tier TOP) and is on the
 *           smart-money stream whole: the list's slots are its own, outside the width the mined book's record sets
 *           ([streamList9020]). Their swaps extend their expert histories (ExpertWallets7962: entry charts, cells, exits).
 *  COPY     A named wallet's buy is a copy signal like any tracked wallet's — candidate, V3, FDG, sizing and every safety
 *           proof unchanged — stamped as its own source (SignalSourceProof7291.Source.NAMED) so the list is graded on its
 *           own closes and forward labels, not on the mined wallets' record. A tracked wallet selling half or more of a
 *           coin the bot holds is already an exit signal (BotService.smartMoneySellExit7745).
 *  HOLDERS  A named wallet is not the unknown whale the holder limits judge: the single-wallet limit reads the largest
 *           wallet NOT on the list, and the top-10 limit is read net of what named wallets hold inside the ten largest
 *           (LaunchFacts8840.Facts.top1UnnamedPct / namedTop10Pct; PreTradeHardGate.holderBlockFor9020).
 *
 * Not measured yet — the owner's rule, built so it is: the decision dataset carries `top1UnnamedPct`, `namedTop10Pct` and
 * `namedBuyers30m`, and the NAMED source prints its own record.
 */
object NamedTraders9020 {
    const val LIST_ID_9020 = "dedge-pump-15d-2026-11-25"
    const val LABEL_PREFIX_9020 = "TOP_NAMED_"
    const val BUYER_WINDOW_MS_9020 = 30L * 60_000L
    /** Stream legs kept per named wallet (ExpertWallets7962 keeps 1,200 for the others; fifty busy wallets, a 512 MB heap). */
    const val MAX_LEGS_9020 = 400
    private const val MAX_MINTS = 1_500

    /** Rank order (1 = most profitable). */
    val WALLETS_9020: List<String> = listOf(
        "GLSCB2DeQAKcSD79nSSXm4F4qAheWDuJswiT8xXUZ6TN", "9s4ji7MRh6xx9PVx7D4by8YjpHMfPVtHfYuMUy8VaTTp",
        "DADDY96nGXz3unXJhBzWRFgmB5kwGkEi6e7VEodneZoy", "4UYeHzyvi9gr3vjRSjgWmBrAgpS15Th2SpJcFfRrdAdv",
        "DY7V1LzRv3nRPpYTMbLW2hEQtzLMdKwfnbQAv3nQZYsG", "AXwCFSn9B3VGbUQsZN32ULCzxEUc481Lz52zojJfKZKE",
        "BNnN2MqfWLvgThYBsv6v8JQaYZXYKYahC5YCy27Ct1cX", "4g73es2nXR9r8RzAxC119M44sBcLsr2ZAgtEx5dsAG8r",
        "6pQY6GNAfveuiiZSLd62JdQmtCXAGe4vJ6WV4xEdLTad", "DCgZR7U5ti6Y3UB2supWyy6msfmSNmT7pZsE9tkkJ6F8",
        "HUcYnQCHCojCmWTEHWtNZkMUYoY1yekWJ7HU1Xu6rAdY", "8P2vQR4MNhDp1fzKyV9zw6QSAUkHaAsSZdYxkwBmr5nG",
        "HzKH3s8Kf7f7pgvJZx2V6TzhkdHbZDurG53rXj61BVLR", "2NMRJ33rhBSZLgyo8Td9K795d8GF7oaLuQbtRHHKT3YK",
        "E69vrqyfrPJEb7gCH4ocfkVoKineucutR4rD4sgYkh6t", "Gtw5QKLnYazyApkzmCXo9GvXa5sCjwvx2bdRLaid8FHu",
        "AtBKj9vkoANANAX1qdoc5xhUdya8rJdNwu1WAFN8KzoM", "FLb8koi2N8Suh6RnBS7Ch5Qj99r8Ws8QDz24JR7VJ7wp",
        "D3xbpdLXyEkY2cbhkhngNQ6nGKfPQBW72DJNbzb2U56L", "Gmjvdkz2C5oMnYVEQFefnnFavEy9Ae7TrLyR5nq3LXyK",
        "Gskao2sjtA1jzBRMEEnVbD7ERB5rz88yaDKwGjqN6Yy4", "3kxcF8wHKm4sEtnxjXXeUvNGeDjTmo47LgtSRg71YfG5",
        "xo7cLDvCgFYAYj94iz4Vw173jK3zKz8xw1dYmg9PLik", "5HM96vknB75wfoZZuHeUArTXooH9mRNgB88kHHcgigL",
        "43E8Uor73ZyU4UvTC5RpdAV4omfnmoNhurBxx9jURcs7", "64PmA4TDPDhdErr3hG5iS7YgFRTgthfKtEKbk98MDDFh",
        "7mZzd7cC4YEz4dsTMAmAsx5LuJ3giN4ywxytxN1b1t8o", "6ppZ7YeGTDn5HseZuEddcJxjWMb4oXX4Wi9mSpGJbV4J",
        "8GyUZbGNagt8FhKqxeveGYpYvdv8NY6JspXxdS2BYkS9", "Fq56oFYuviTKvd78CjAdBw9ojYPtWtxKqWgcCLZ7ddfg",
        "Bup46FaXBi8zr39c3sVh89hSqiyewJr85o7cbUtX7xtD", "8H8i3cfx1LtoVxFZE4z8pav5JeoBKyHcxoeNCuhbC9vt",
        "BvkazozQxwPvgBbaTHBZFJsrGqn6cRWQgQU6V1GtvKgr", "E9dZhfLwzTyvuEjcjvia3auKF2P1ni6mw7feeTA9BbsT",
        "6UHxVYGvBqigvQ8uKXBHTkkGEhw25XnKBMdSHMkuR5gq", "7Q8rvHKi1brA49epwVyinrqX6rMKZxZqW1FoUgpXTpTs",
        "7PaVZkSbXAvT5wd5rn6cDaiKpyyYSAGN9X79izVP6SGb", "J1PzRcDWcFCvbWhhGJ2kMT7pcG9EW67bLveEpjFL5Jar",
        "3ir3cihQtsD7MXXEKzuJEnTMycp6yGwqK6LXkuS8UFAX", "7GPgfyK7YR1AJWL76Wi3snaVnUFc57vJ2xpRWoLh8czn",
        "BvtZeURZMcvvQVBr5k9jDT53E2xuFq2Da6d6RTDP7gM4", "Gy3DYzDrowYjpdR2B3ajKRQpQuhL9qy4fY2CUCvmSc8Z",
        "75atx2W4poAzyULnLz1MQPN3CHBLmuv4Zs281BxbA52G", "9ZhrKqanCTAQQaW9KAbvQxo2e1LRg5TP6uHF3DxZccsw",
        "5RhdUJYFCke1LRnJuAJp4PvyeDA67tY39CRGqUG3qYF2", "Bv2BAw5UmKxv5SBMWYKqpsh6eXKNGM2RKxJGpGPk5vmb",
        "29Quu8u1TEeAXHJsP8c4ueADnwqJNQqJpsobw6Gxdzzv", "51XcBjxnhAPUrDPZkdef1kUK3TJwAwCsCPRkCVJhTUbV",
        "7dVjbXSGhgnceedKX4RiHsQM8LHA8v8mXMNk8HV3zAwW", "5AMmFMegYasAEKZzh5xeAZf4DodThqcA34SrKRZto21y",
    )

    private val addrs: Set<String> by lazy { WALLETS_9020.toHashSet() }
    private val sortedKeys: LongArray by lazy { WALLETS_9020.map { LaunchFacts8840.walletKey8853(it) }.toLongArray().also { it.sort() } }

    fun isNamed9020(wallet: String): Boolean = wallet in addrs

    /** [walletKey] = LaunchFacts8840.walletKey8853 of a wallet. No allocation: the launch fold asks this per wallet. */
    fun isNamedKey9020(walletKey: Long): Boolean = java.util.Arrays.binarySearch(sortedKeys, walletKey) >= 0

    fun label9020(wallet: String): String = LABEL_PREFIX_9020 + wallet.take(4) + "_#" + (WALLETS_9020.indexOf(wallet) + 1)

    /** The source a tracked wallet's copy signal is graded under. */
    fun sourceFor9020(wallet: String): SignalSourceProof7291.Source =
        if (isNamed9020(wallet)) SignalSourceProof7291.Source.NAMED else SignalSourceProof7291.Source.COPY

    /**
     * Pure: the stream's watch list from the asked list in priority order. Named wallets are all watched; the rest keep the
     * prefix [width] allows for their number.
     */
    fun streamList9020(prioritised: List<String>, width: (Int) -> Int): List<String> {
        val named = prioritised.filter { isNamed9020(it) }
        if (named.isEmpty()) return prioritised.take(width(prioritised.size))
        val rest = prioritised.filter { !isNamed9020(it) }
        return (rest.take(width(rest.size)) + named).distinct()
    }

    /**
     * Pure: what seeding does on a copy list holding [tracked] (address to label) when the stored list id is [seededId] —
     * (wallets to add, tracked wallets to relabel), or null when this list was already seeded (a wallet the owner removed
     * since stays removed).
     */
    fun seedPlan9020(tracked: Map<String, String>, seededId: String?): Pair<List<String>, List<String>>? {
        if (seededId == LIST_ID_9020) return null
        return WALLETS_9020.filter { it !in tracked } to WALLETS_9020.filter { a -> tracked[a]?.let { !it.startsWith(LABEL_PREFIX_9020) } == true }
    }

    // ── what the named wallets are doing, per coin ──
    private val buyers = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val buysSeen = AtomicLong(); private val sellsSeen = AtomicLong(); private val exemptions = AtomicLong()
    @Volatile private var seeded = -1; @Volatile private var lastBuyMs = 0L

    /** CopyTradeEngine: a tracked wallet bought [mint]. Kept only for named wallets. */
    fun noteBuy9020(mint: String, wallet: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.length < 30 || !isNamed9020(wallet)) return
        buysSeen.incrementAndGet(); lastBuyMs = nowMs
        buyers.getOrPut(mint) { ConcurrentHashMap() }[wallet] = nowMs
        if (buyers.size > MAX_MINTS) buyers.entries.removeIf { e -> e.value.values.all { nowMs - it > BUYER_WINDOW_MS_9020 } }
    }

    /** A named wallet sold [mint]: it no longer counts among the coin's named buyers. */
    fun noteSell9020(mint: String, wallet: String) {
        if (!isNamed9020(wallet)) return
        sellsSeen.incrementAndGet()
        buyers[mint]?.remove(wallet)
    }

    /** Named wallets that bought [mint] in the last 30 minutes and have not sold since. */
    fun buyers9020(mint: String, nowMs: Long = System.currentTimeMillis()): Int =
        buyers[mint]?.values?.count { nowMs - it in 0L..BUYER_WINDOW_MS_9020 } ?: 0

    /** The holder rule read a coin whose largest wallet is on the list as under the single-wallet limit. */
    fun noteExemption9020() { exemptions.incrementAndGet() }
    fun noteSeeded9020(added: Int) { seeded = added }

    fun statusLine9020(nowMs: Long = System.currentTimeMillis()): String {
        val held = buyers.count { e -> e.value.values.any { nowMs - it in 0L..BUYER_WINDOW_MS_9020 } }
        val two = buyers.count { e -> e.value.values.count { nowMs - it in 0L..BUYER_WINDOW_MS_9020 } >= 2 }
        val last = if (lastBuyMs > 0L) "${(nowMs - lastBuyMs) / 60_000L}m" else "never"
        return "namedTraders9020[list=$LIST_ID_9020 wallets=${WALLETS_9020.size} seededThisRun=${if (seeded < 0) "no(already on the copy list)" else "+$seeded"} " +
            "buysSeen=${buysSeen.get()} sellsSeen=${sellsSeen.get()} lastBuy=$last coinsBought30m=$held coins2plus30m=$two " +
            "holderReadsExempted=${exemptions.get()} record=the NAMED row of the signal sources]"
    }

    internal fun resetForTest9020() { buyers.clear(); buysSeen.set(0); sellsSeen.set(0); exemptions.set(0); seeded = -1; lastBuyMs = 0L }
}
