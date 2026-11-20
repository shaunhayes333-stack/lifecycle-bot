package com.lifecyclebot.engine

/**
 * V5.0.8894 §THE_PHONE_OR_THE_GATES — owner, 20 Nov 18:08 on 5.0.8893: "its kinda dead hey. guessing fdg is blocking pretty
 * much everything". The report's own host table said otherwise: in those 10 minutes jupiter answered 5 % of its calls (150
 * transport failures), jupiter_price_keyed 10 % (112), dexscreener 67 % (58), raydium 68 % (55), helius 70 % (44), each at
 * 2-9x its 12:48 latency; held-quantity quotes 0 of 260 (5.0.8882: 517 of 911); the wallet read failed long enough for 69
 * WALLET_BLIND_8074 refusals. At 12:48 on 5.0.8889 every one of those hosts was at 100 %. Calls that never get an answer from
 * several unrelated providers at once are the device's network, not a provider and not a gate — and every gate downstream of
 * a price, a wallet read or a quote then defers or refuses. The FDG admitted 0 of 554 there; 5.0.8882 admitted 1 of 1942.
 *
 * This names it on the brief's first section: a provider is failing on transport when at least [SHARE_8894] of its recent
 * calls (ApiHealthMonitor's decayed window, at least [MIN_CALLS_8894]) got no answer at all (timeout, reset, DNS); [PROVIDERS_8894]
 * or more distinct providers failing together is DEGRADED. Read-only: no gate, size or exit reads it.
 */
object NetworkVerdict8894 {
    internal const val MIN_CALLS_8894 = 20
    internal const val SHARE_8894 = 0.25
    internal const val PROVIDERS_8894 = 3
    /**
     * V5.0.8898 — the share of the providers in use that must fail together before the phone's network is blamed. 20 Nov 20:53
     * (5.0.8894): helius-enhanced (55 % no answer, 4.8 s average), defillama (37 %) and the public Solana RPC (28 %) timed out
     * while dexscreener, geckoterminal, groq, helius, helius_curve, the Meteora APIs and Jupiter's keyed price answered 90-100 %:
     * three providers out of more than ten, each its own provider's timeouts, read as DEGRADED and raised a FAULT. At 17:57
     * (5.0.8893) every provider in use failed (4 of 4).
     */
    internal const val ACTIVE_SHARE_8898 = 0.5

    /** One host's recent counters as ApiHealthMonitor holds them. */
    internal data class Host8894(val host: String, val ok: Int, val c4xx: Int, val c5xx: Int, val net: Int) {
        val calls: Int get() = ok + c4xx + c5xx + net
        val netShare: Double get() = if (calls > 0) net.toDouble() / calls else 0.0
    }

    /** Pure: the provider a host belongs to ("jupiter_price_keyed" and "jupiter" are one provider, "helius-enhanced" is helius). */
    internal fun provider8894(host: String): String =
        host.trim().lowercase().substringBefore('_').substringBefore('-').substringBefore('.')

    /** Pure: the hosts failing on transport, most failures first. */
    internal fun failing8894(hosts: List<Host8894>): List<Host8894> =
        hosts.filter { it.calls >= MIN_CALLS_8894 && it.netShare >= SHARE_8894 }.sortedByDescending { it.net }

    /** Pure (V5.0.8898): the providers in use — any of their hosts made at least [MIN_CALLS_8894] recent calls. */
    internal fun active8898(hosts: List<Host8894>): Int =
        hosts.filter { it.calls >= MIN_CALLS_8894 }.map { provider8894(it.host) }.distinct().size

    /**
     * Pure: several unrelated providers failing on transport together. V5.0.8898 — and at least [ACTIVE_SHARE_8898] of the
     * providers in use: a few providers timing out while the rest answer are those providers, not the phone.
     */
    internal fun degraded8894(hosts: List<Host8894>): Boolean {
        val failing = failing8894(hosts).map { provider8894(it.host) }.distinct().size
        return failing >= PROVIDERS_8894 && failing >= ACTIVE_SHARE_8898 * active8898(hosts)
    }

    /** Pure: the brief's line for [hosts]. */
    internal fun line8894(hosts: List<Host8894>): String {
        val bad = failing8894(hosts)
        val providers = bad.map { provider8894(it.host) }.distinct().size
        val verdict = if (degraded8894(hosts)) "DEGRADED" else if (bad.isEmpty()) "OK" else "PROVIDER"
        val list = bad.take(6).joinToString(" · ") { "${it.host} net${it.net}/${it.calls} ${"%.0f".format(it.netShare * 100.0)}%" }.ifBlank { "-" }
        return "network8894[$verdict failingProviders=$providers $list active8898=${active8898(hosts)} rule=a provider fails on transport when >=${(SHARE_8894 * 100).toInt()}% of " +
            "its last >=$MIN_CALLS_8894 calls got no answer; $PROVIDERS_8894+ providers and >=${(ACTIVE_SHARE_8898 * 100).toInt()}% of those in use at once = the device's network, not a provider or a gate]"
    }

    /** ApiHealthMonitor's hosts now (recent, decayed counters). */
    internal fun hosts8894(): List<Host8894> = try {
        ApiHealthMonitor.snapshot().map { (h, s) ->
            Host8894(h, s.successes.get(), s.failures4xx.get(), s.failures5xx.get(), s.networkErrors.get())
        }
    } catch (_: Throwable) { emptyList() }
}
