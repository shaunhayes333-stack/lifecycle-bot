package com.lifecyclebot.ui

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lifecyclebot.R
import com.lifecyclebot.engine.BotService
import com.lifecyclebot.engine.CurrencyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CurrencyActivity : AppCompatActivity() {

    private lateinit var currency: CurrencyManager
    private lateinit var llCurrencies: LinearLayout
    private lateinit var tvSolUsdRate: TextView
    private lateinit var tvRateStatus: TextView

    private val white   = 0xFFFFFFFF.toInt()
    private val muted   = 0xFF6B7280.toInt()
    private val purple  = 0xFF9945FF.toInt()
    private val green   = 0xFF10B981.toInt()
    private val surface = 0xFF111118.toInt()
    private val divider = 0xFF1F2937.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_currency)
        supportActionBar?.hide()

        currency = try {
            BotService.instance?.currencyManager
                ?: CurrencyManager(applicationContext)
        } catch (_: Exception) {
            CurrencyManager(applicationContext)
        }

        llCurrencies = findViewById(R.id.llCurrencies)
        tvSolUsdRate = findViewById(R.id.tvSolUsdRate)
        tvRateStatus = findViewById(R.id.tvRateStatus)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        buildList()
        refreshRates()
    }

    private fun buildList() {
        llCurrencies.removeAllViews()
        val selected = currency.selectedCurrency

        // Group: Crypto
        addGroupHeader("CRYPTO")
        CurrencyManager.ALL_CURRENCIES.filter { it.isCrypto }.forEach { info ->
            addCurrencyRow(info, info.code == selected)
        }

        // Group: Major Fiat
        addGroupHeader("FIAT — MAJOR")
        val major = listOf("USD","GBP","EUR","AUD","JPY","CAD","CHF","CNY","HKD","SGD")
        CurrencyManager.ALL_CURRENCIES.filter { it.code in major }.forEach { info ->
            addCurrencyRow(info, info.code == selected)
        }

        // Group: Other Fiat
        addGroupHeader("FIAT — OTHER")
        CurrencyManager.ALL_CURRENCIES.filter { !it.isCrypto && it.code !in major }.forEach { info ->
            addCurrencyRow(info, info.code == selected)
        }
    }

    private fun addGroupHeader(label: String) {
        val tv = TextView(this).apply {
            text = label
            textSize = 10f
            setTextColor(muted)
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.12f
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        llCurrencies.addView(tv)
    }

    private fun addCurrencyRow(info: CurrencyManager.CurrencyInfo, isSelected: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(surface)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                currency.selectedCurrency = info.code
                buildList()   // redraw with new selection
                updateRateDisplay()
            }
        }

        // Symbol badge
        val badge = TextView(this).apply {
            text = info.symbol
            textSize = 18f
            setTextColor(if (isSelected) purple else muted)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            minWidth = dp(48)
            gravity = android.view.Gravity.CENTER
        }

        // Name + code
        val info_layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info_layout.addView(TextView(this).apply {
            text = info.name
            textSize = 15f
            setTextColor(if (isSelected) white else 0xFFD1D5DB.toInt())
            typeface = if (isSelected) android.graphics.Typeface.DEFAULT_BOLD
                       else android.graphics.Typeface.DEFAULT
        })
        info_layout.addView(TextView(this).apply {
            text = info.code
            textSize = 11f
            setTextColor(muted)
            typeface = android.graphics.Typeface.MONOSPACE
        })

        // Rate vs SOL
        val rateView = TextView(this).apply {
            text = getRateText(info)
            textSize = 12f
            setTextColor(muted)
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = android.view.Gravity.END
        }

        // Selection indicator
        val check = TextView(this).apply {
            text = if (isSelected) " ✓" else ""
            textSize = 16f
            setTextColor(purple)
        }

        row.addView(badge)
        row.addView(info_layout)
        row.addView(rateView)
        row.addView(check)

        val div = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(divider)
        }

        llCurrencies.addView(row)
        llCurrencies.addView(div)
    }

    private fun getRateText(info: CurrencyManager.CurrencyInfo): String {
        val sol = currency.getSolUsd()
        if (sol <= 0) return "—"
        return when (info.code) {
            "SOL" -> "1 SOL"
            "BTC" -> "—"   // fetched separately
            "ETH" -> "—"
            "USD" -> "$%.2f".format(sol)
            else  -> currency.format(1.0)
        }
    }

    private fun updateRateDisplay() {
        val sol = currency.getSolUsd()
        tvSolUsdRate.text = if (sol > 0) "$${"%,.2f".format(sol)}" else "$—"
    }

    private fun refreshRates() {
        tvRateStatus.text = "Updating…"
        lifecycleScope.launch(Dispatchers.IO) {
            currency.refresh()
            withContext(Dispatchers.Main) {
                tvRateStatus.text = "Live rates"
                tvRateStatus.setTextColor(green)
                updateRateDisplay()
                buildList()
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
