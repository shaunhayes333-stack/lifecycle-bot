package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * CurrencyManager
 *
 * Fetches live exchange rates and converts SOL amounts to any major currency.
 * Uses exchangerate-api.com free tier — no key needed, 1500 req/month.
 * Falls back to CoinGecko for crypto rates (BTC, ETH).
 *
 * Supported currencies:
 *   Crypto:  SOL, BTC, ETH
 *   Fiat:    USD, GBP, EUR, AUD, JPY, CAD, CHF, CNY, HKD, SGD,
 *            NZD, SEK, NOK, DKK, INR, BRL, MXN, ZAR, KRW, TRY
 */
class CurrencyManager(private val ctx: Context) {

    companion object {
        // All supported display currencies
        val ALL_CURRENCIES = listOf(
            // Crypto first
            CurrencyInfo("SOL", "◎",  "Solana",          isCrypto = true),
            CurrencyInfo("USD", "$",   "US Dollar",       isCrypto = false),
            CurrencyInfo("GBP", "£",   "British Pound",   isCrypto = false),
            CurrencyInfo("EUR", "€",   "Euro",            isCrypto = false),
            CurrencyInfo("AUD", "A$",  "Australian Dollar",isCrypto = false),
            CurrencyInfo("JPY", "¥",   "Japanese Yen",    isCrypto = false),
            CurrencyInfo("CAD", "C$",  "Canadian Dollar", isCrypto = false),
            CurrencyInfo("CHF", "Fr",  "Swiss Franc",     isCrypto = false),
            CurrencyInfo("CNY", "¥",   "Chinese Yuan",    isCrypto = false),
            CurrencyInfo("HKD", "HK$", "Hong Kong Dollar",isCrypto = false),
            CurrencyInfo("SGD", "S$",  "Singapore Dollar",isCrypto = false),
            CurrencyInfo("NZD", "NZ$", "New Zealand Dollar",isCrypto = false),
            CurrencyInfo("SEK", "kr",  "Swedish Krona",   isCrypto = false),
            CurrencyInfo("NOK", "kr",  "Norwegian Krone", isCrypto = false),
            CurrencyInfo("DKK", "kr",  "Danish Krone",    isCrypto = false),
            CurrencyInfo("INR", "₹",   "Indian Rupee",    isCrypto = false),
            CurrencyInfo("BRL", "R$",  "Brazilian Real",  isCrypto = false),
            CurrencyInfo("MXN", "MX$", "Mexican Peso",    isCrypto = false),
            CurrencyInfo("ZAR", "R",   "South African Rand",isCrypto = false),
            CurrencyInfo("KRW", "₩",   "South Korean Won",isCrypto = false),
            CurrencyInfo("TRY", "₺",   "Turkish Lira",    isCrypto = false),
            CurrencyInfo("BTC", "₿",   "Bitcoin",         isCrypto = true),
            CurrencyInfo("ETH", "Ξ",   "Ethereum",        isCrypto = true),
        )

        private const val PREF_FILE     = "currency_prefs"
        private const val PREF_SELECTED = "selected_currency"
        private const val DEFAULT_CCY   = "USD"
        private const val RATE_TTL_MS   = 5 * 60_000L   // refresh every 5 min
    }

    data class CurrencyInfo(
        val code: String,
        val symbol: String,
        val name: String,
        val isCrypto: Boolean,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Live rates: currency code → SOL per 1 unit of that currency
    // (i.e. rateVsSol["USD"] = 0.007 means 1 USD = 0.007 SOL)
    // We store solPerUnit so: solAmount / solPerUnit = displayAmount
    @Volatile private var solUsd    = 0.0
    @Volatile private var rates     = mapOf<String, Double>()   // fiat rates vs USD
    @Volatile private var btcUsd    = 0.0
    @Volatile private var ethUsd    = 0.0
    @Volatile private var lastFetch = 0L

    private val prefs: SharedPreferences =
        ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    // ── Selected currency ─────────────────────────────────────────────

    var selectedCurrency: String
        get() = prefs.getString(PREF_SELECTED, DEFAULT_CCY) ?: DEFAULT_CCY
        set(value) = prefs.edit().putString(PREF_SELECTED, value).apply()

    val selectedInfo: CurrencyInfo
        get() = ALL_CURRENCIES.find { it.code == selectedCurrency }
                ?: ALL_CURRENCIES[1]

    // ── Conversion ────────────────────────────────────────────────────

    /**
     * Convert a SOL amount to the currently selected display currency.
     * Returns the converted amount as a Double.
     */
    fun solToDisplay(sol: Double): Double {
        if (sol == 0.0) return 0.0
        refreshIfStale()
        return when (selectedCurrency) {
            "SOL" -> sol
            "BTC" -> if (btcUsd > 0 && solUsd > 0) sol * solUsd / btcUsd else 0.0
            "ETH" -> if (ethUsd > 0 && solUsd > 0) sol * solUsd / ethUsd else 0.0
            else  -> {
                val fiatPerUsd = rates[selectedCurrency] ?: 1.0
                sol * solUsd * fiatPerUsd
            }
        }
    }

    /**
     * Format a SOL amount as a display string in the selected currency.
     * Examples:
     *   SOL:  "◎ 0.0500"
     *   USD:  "$ 8.25"
     *   JPY:  "¥ 1,243"
     *   BTC:  "₿ 0.000082"
     */
    fun format(sol: Double, showPlus: Boolean = false): String {
        val info   = selectedInfo
        val amount = solToDisplay(sol)
        val prefix = if (showPlus && amount > 0) "+" else ""

        return when (selectedCurrency) {
            "SOL" -> "${prefix}${info.symbol} ${formatSol(amount)}"
            "JPY", "KRW", "IDR" -> "${prefix}${info.symbol}${formatInt(amount)}"
            "BTC" -> "${prefix}${info.symbol} ${formatBtc(amount)}"
            "ETH" -> "${prefix}${info.symbol} ${formatEth(amount)}"
            else  -> "${prefix}${info.symbol} ${formatFiat(amount)}"
        }
    }

    /**
     * Format a price (very small number) in display currency.
     * Used for token prices like $0.00000189
     */
    fun formatPrice(priceUsd: Double): String {
        val info = selectedInfo
        val converted = when (selectedCurrency) {
            "SOL" -> if (solUsd > 0) priceUsd / solUsd else 0.0
            "BTC" -> if (btcUsd > 0) priceUsd / btcUsd else 0.0
            "ETH" -> if (ethUsd > 0) priceUsd / ethUsd else 0.0
            else  -> {
                val fiatPerUsd = rates[selectedCurrency] ?: 1.0
                priceUsd * fiatPerUsd
            }
        }
        return "${info.symbol}${formatSmallPrice(converted)}"
    }

    /** Get SOL/USD price for display */
    fun getSolUsd(): Double = solUsd

    // ── Rate fetching ─────────────────────────────────────────────────

    fun refreshIfStale() {
        if (System.currentTimeMillis() - lastFetch < RATE_TTL_MS) return
        refresh()
    }

    fun refresh() {
        try {
            fetchCryptoRates()
            fetchFiatRates()
            lastFetch = System.currentTimeMillis()
        } catch (_: Exception) {}
    }

    private fun fetchCryptoRates() {
        val url  = "https://api.coingecko.com/api/v3/simple/price" +
                   "?ids=solana,bitcoin,ethereum&vs_currencies=usd"
        val body = get(url) ?: return
        val json = JSONObject(body)
        solUsd = json.optJSONObject("solana")?.optDouble("usd", 0.0) ?: 0.0
        btcUsd = json.optJSONObject("bitcoin")?.optDouble("usd", 0.0) ?: 0.0
        ethUsd = json.optJSONObject("ethereum")?.optDouble("usd", 0.0) ?: 0.0
    }

    private fun fetchFiatRates() {
        // Free tier — no key, returns USD base rates
        val url  = "https://open.er-api.com/v6/latest/USD"
        val body = get(url) ?: return
        val json = JSONObject(body)
        val r    = json.optJSONObject("rates") ?: return
        val map  = mutableMapOf<String, Double>()
        ALL_CURRENCIES.filter { !it.isCrypto }.forEach { ccy ->
            val rate = r.optDouble(ccy.code, 0.0)
            if (rate > 0) map[ccy.code] = rate
        }
        rates = map
    }

    private fun get(url: String): String? = try {
        val req  = Request.Builder().url(url)
            .header("Accept", "application/json").build()
        val resp = http.newCall(req).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (_: Exception) { null }

    // ── Formatters ────────────────────────────────────────────────────

    private fun formatSol(v: Double)  = "%.4f".format(v)
    private fun formatBtc(v: Double)  = "%.6f".format(v)
    private fun formatEth(v: Double)  = "%.5f".format(v)
    private fun formatFiat(v: Double) = "%,.2f".format(v)
    private fun formatInt(v: Double)  = "%,.0f".format(v)

    private fun formatSmallPrice(v: Double): String = when {
        v >= 1.0    -> "%.4f".format(v)
        v >= 0.01   -> "%.6f".format(v)
        v >= 0.0001 -> "%.8f".format(v)
        else        -> "%.10f".format(v)
    }
}
