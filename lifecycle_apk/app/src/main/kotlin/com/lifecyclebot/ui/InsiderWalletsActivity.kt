package com.lifecyclebot.ui

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lifecyclebot.R
import com.lifecyclebot.perps.InsiderWalletTracker
import com.lifecyclebot.perps.InsiderWalletTracker.InsiderCategory
import com.lifecyclebot.perps.InsiderWalletTracker.InsiderWallet
import com.lifecyclebot.perps.InsiderWalletTracker.TokenHolding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class InsiderWalletsActivity : AppCompatActivity() {

    private lateinit var llWalletList: LinearLayout
    private lateinit var tvWalletCount: TextView
    private lateinit var progressLoading: ProgressBar
    private lateinit var tabAll: TextView
    private lateinit var tabPolitical: TextView
    private lateinit var tabSmartMoney: TextView
    private lateinit var tabCustom: TextView

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var currentFilter: InsiderCategory? = null // null = ALL

    private val white   = 0xFFFFFFFF.toInt()
    private val muted   = 0xFF6B7280.toInt()
    private val green   = 0xFF14F195.toInt()
    private val red     = 0xFFEF4444.toInt()
    private val amber   = 0xFFF59E0B.toInt()
    private val purple  = 0xFF9945FF.toInt()
    private val surface = 0xFF111118.toInt()
    private val dark    = 0xFF0D0D14.toInt()
    private val divider = 0xFF1F2937.toInt()
    private val sdf     = SimpleDateFormat("MMM dd HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_insider_wallets)
        supportActionBar?.hide()

        llWalletList = findViewById(R.id.llWalletList)
        tvWalletCount = findViewById(R.id.tvWalletCount)
        progressLoading = findViewById(R.id.progressLoading)
        tabAll = findViewById(R.id.tabAll)
        tabPolitical = findViewById(R.id.tabPolitical)
        tabSmartMoney = findViewById(R.id.tabSmartMoney)
        tabCustom = findViewById(R.id.tabCustom)

        // Init tracker if not already
        InsiderWalletTracker.init(applicationContext)

        // Back button
        findViewById<View>(R.id.btnInsiderBack).setOnClickListener { finish() }

        // Add wallet button
        findViewById<View>(R.id.btnAddWallet).setOnClickListener { showAddWalletDialog() }

        // Refresh button
        findViewById<View>(R.id.btnRefreshAll).setOnClickListener { refreshAllWallets() }

        // Tab listeners
        tabAll.setOnClickListener { selectTab(null) }
        tabPolitical.setOnClickListener { selectTab(InsiderCategory.POLITICAL) }
        tabSmartMoney.setOnClickListener { selectTab(InsiderCategory.SMART_MONEY) }
        tabCustom.setOnClickListener { selectTab(InsiderCategory.CUSTOM) }

        buildWalletList()
    }

    private fun selectTab(category: InsiderCategory?) {
        currentFilter = category

        val tabs = listOf(tabAll, tabPolitical, tabSmartMoney, tabCustom)
        val cats: List<InsiderCategory?> = listOf(null, InsiderCategory.POLITICAL, InsiderCategory.SMART_MONEY, InsiderCategory.CUSTOM)

        tabs.forEachIndexed { index, tab ->
            if (cats[index] == category) {
                tab.setTextColor(purple)
                tab.setBackgroundColor(0xFF1A1A2E.toInt())
            } else {
                tab.setTextColor(muted)
                tab.setBackgroundColor(0x00000000)
            }
        }

        buildWalletList()
    }

    private fun buildWalletList() {
        llWalletList.removeAllViews()

        val wallets = if (currentFilter == null) {
            InsiderWalletTracker.getTrackedWallets()
        } else {
            InsiderWalletTracker.getWalletsByCategory(currentFilter!!)
        }

        tvWalletCount.text = "${wallets.size} wallets tracked"

        if (wallets.isEmpty()) {
            llWalletList.addView(TextView(this).apply {
                text = "No insider wallets tracked"
                textSize = 14f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, 0)
            })
            return
        }

        wallets.sortedBy { it.category.ordinal }.forEach { wallet ->
            addWalletCard(wallet)
        }
    }

    private fun addWalletCard(wallet: InsiderWallet) {
        // Card container
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(surface)
        }

        // Row 1: Category badge + Label + Active toggle
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Category badge
        val badgeColor = when (wallet.category) {
            InsiderCategory.POLITICAL -> red
            InsiderCategory.SMART_MONEY -> green
            InsiderCategory.CUSTOM -> amber
        }
        val badgeText = when (wallet.category) {
            InsiderCategory.POLITICAL -> "POL"
            InsiderCategory.SMART_MONEY -> "WHALE"
            InsiderCategory.CUSTOM -> "CUSTOM"
        }
        headerRow.addView(TextView(this).apply {
            text = badgeText
            textSize = 9f
            setTextColor(0xFF000000.toInt())
            setBackgroundColor(badgeColor)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })

        // Spacer
        headerRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), 0)
        })

        // Label
        headerRow.addView(TextView(this).apply {
            text = wallet.label
            textSize = 15f
            setTextColor(white)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        // Active indicator
        headerRow.addView(TextView(this).apply {
            text = if (wallet.isActive) "ACTIVE" else "PAUSED"
            textSize = 10f
            setTextColor(if (wallet.isActive) green else muted)
            setOnClickListener {
                InsiderWalletTracker.toggleWallet(wallet.address)
                buildWalletList()
            }
        })

        card.addView(headerRow)

        // Row 2: Address (truncated)
        card.addView(TextView(this).apply {
            text = wallet.address.take(6) + "..." + wallet.address.takeLast(6)
            textSize = 11f
            setTextColor(purple)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
        })

        // Row 3: Description
        if (wallet.description.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = wallet.description
                textSize = 11f
                setTextColor(muted)
                setPadding(0, dp(2), 0, 0)
            })
        }

        // Row 4: Holdings container (loaded async)
        val holdingsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            tag = wallet.address
        }
        card.addView(holdingsContainer)

        // Row 5: Action buttons
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        actionsRow.addView(TextView(this).apply {
            text = "View Holdings"
            textSize = 12f
            setTextColor(green)
            setPadding(0, dp(4), dp(16), dp(4))
            setOnClickListener { loadHoldings(wallet, holdingsContainer) }
        })

        actionsRow.addView(TextView(this).apply {
            text = "Recent Txns"
            textSize = 12f
            setTextColor(purple)
            setPadding(0, dp(4), dp(16), dp(4))
            setOnClickListener { loadTransactions(wallet, holdingsContainer) }
        })

        if (wallet.category == InsiderCategory.CUSTOM) {
            actionsRow.addView(TextView(this).apply {
                text = "Remove"
                textSize = 12f
                setTextColor(red)
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener {
                    AlertDialog.Builder(this@InsiderWalletsActivity)
                        .setTitle("Remove Wallet")
                        .setMessage("Remove ${wallet.label}?")
                        .setPositiveButton("Remove") { _, _ ->
                            InsiderWalletTracker.removeWallet(wallet.address)
                            buildWalletList()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
        }

        card.addView(actionsRow)

        llWalletList.addView(card)

        // Divider
        llWalletList.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(divider)
        })
    }

    // ===============================================================================
    // LOAD HOLDINGS
    // ===============================================================================

    private fun loadHoldings(wallet: InsiderWallet, container: LinearLayout) {
        container.removeAllViews()
        container.addView(TextView(this).apply {
            text = "Loading holdings..."
            textSize = 11f
            setTextColor(muted)
        })
        progressLoading.visibility = View.VISIBLE

        scope.launch {
            val holdings = InsiderWalletTracker.fetchHoldings(wallet.address)

            withContext(Dispatchers.Main) {
                progressLoading.visibility = View.GONE
                container.removeAllViews()

                if (holdings == null || holdings.holdings.isEmpty()) {
                    container.addView(TextView(this@InsiderWalletsActivity).apply {
                        text = "No token holdings found"
                        textSize = 11f
                        setTextColor(muted)
                    })
                    return@withContext
                }

                // Total value header
                container.addView(TextView(this@InsiderWalletsActivity).apply {
                    text = "Portfolio: \$${"%,.2f".format(holdings.totalUsdValue)}"
                    textSize = 13f
                    setTextColor(green)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, dp(4))
                })

                // Top 10 holdings
                holdings.holdings.take(10).forEach { holding ->
                    addHoldingRow(container, holding)
                }

                if (holdings.holdings.size > 10) {
                    container.addView(TextView(this@InsiderWalletsActivity).apply {
                        text = "+ ${holdings.holdings.size - 10} more tokens"
                        textSize = 10f
                        setTextColor(muted)
                        setPadding(0, dp(4), 0, 0)
                    })
                }
            }
        }
    }

    private fun addHoldingRow(container: LinearLayout, holding: TokenHolding) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }

        // Symbol
        row.addView(TextView(this).apply {
            text = holding.symbol
            textSize = 12f
            setTextColor(white)
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT)
        })

        // Amount
        row.addView(TextView(this).apply {
            text = formatAmount(holding.amount)
            textSize = 11f
            setTextColor(muted)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        // USD value
        row.addView(TextView(this).apply {
            text = "\$${"%,.2f".format(holding.usdValue)}"
            textSize = 12f
            setTextColor(if (holding.usdValue > 10_000) green else white)
            gravity = Gravity.END
        })

        container.addView(row)
    }

    // ===============================================================================
    // LOAD TRANSACTIONS
    // ===============================================================================

    private fun loadTransactions(wallet: InsiderWallet, container: LinearLayout) {
        container.removeAllViews()
        container.addView(TextView(this).apply {
            text = "Loading transactions..."
            textSize = 11f
            setTextColor(muted)
        })
        progressLoading.visibility = View.VISIBLE

        scope.launch {
            val txData = InsiderWalletTracker.fetchRecentTransactions(wallet.address)

            withContext(Dispatchers.Main) {
                progressLoading.visibility = View.GONE
                container.removeAllViews()

                if (txData == null || txData.transactions.isEmpty()) {
                    container.addView(TextView(this@InsiderWalletsActivity).apply {
                        text = "No recent transactions"
                        textSize = 11f
                        setTextColor(muted)
                    })
                    return@withContext
                }

                container.addView(TextView(this@InsiderWalletsActivity).apply {
                    text = "Recent Transactions (${txData.transactions.size})"
                    textSize = 13f
                    setTextColor(purple)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, dp(4))
                })

                txData.transactions.take(15).forEach { tx ->
                    val row = LinearLayout(this@InsiderWalletsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(3), 0, dp(3))
                    }

                    // Status dot
                    row.addView(View(this@InsiderWalletsActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).also {
                            it.marginEnd = dp(8)
                        }
                        setBackgroundColor(if (tx.success) green else red)
                    })

                    // Signature (truncated)
                    row.addView(TextView(this@InsiderWalletsActivity).apply {
                        text = tx.signature.take(8) + "..." + tx.signature.takeLast(8)
                        textSize = 10f
                        setTextColor(white)
                        typeface = android.graphics.Typeface.MONOSPACE
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })

                    // Time
                    if (tx.blockTime > 0) {
                        row.addView(TextView(this@InsiderWalletsActivity).apply {
                            text = sdf.format(Date(tx.blockTime * 1000))
                            textSize = 10f
                            setTextColor(muted)
                            gravity = Gravity.END
                        })
                    }

                    container.addView(row)
                }
            }
        }
    }

    // ===============================================================================
    // ADD WALLET DIALOG
    // ===============================================================================

    private fun showAddWalletDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val labelInput = EditText(this).apply {
            hint = "Label (e.g., 'Pelosi Whale')"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val addressInput = EditText(this).apply {
            hint = "Solana Wallet Address"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val descInput = EditText(this).apply {
            hint = "Description (optional)"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
        }

        layout.addView(labelInput)
        layout.addView(addressInput)
        layout.addView(descInput)

        AlertDialog.Builder(this)
            .setTitle("Track New Wallet")
            .setView(layout)
            .setPositiveButton("Track") { _, _ ->
                val label = labelInput.text.toString().trim()
                val address = addressInput.text.toString().trim()
                val desc = descInput.text.toString().trim()

                if (label.isNotEmpty() && address.length >= 32) {
                    InsiderWalletTracker.addCustomWallet(address, label, desc)
                    buildWalletList()
                    Toast.makeText(this, "Wallet added: $label", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid label or address", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ===============================================================================
    // REFRESH ALL
    // ===============================================================================

    private fun refreshAllWallets() {
        InsiderWalletTracker.clearCache()
        progressLoading.visibility = View.VISIBLE
        Toast.makeText(this, "Refreshing all wallets...", Toast.LENGTH_SHORT).show()

        scope.launch {
            InsiderWalletTracker.getActiveWallets().forEach { wallet ->
                InsiderWalletTracker.fetchHoldings(wallet.address)
            }

            withContext(Dispatchers.Main) {
                progressLoading.visibility = View.GONE
                buildWalletList()
                Toast.makeText(this@InsiderWalletsActivity, "Refresh complete", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===============================================================================
    // HELPERS
    // ===============================================================================

    private fun formatAmount(amount: Double): String {
        return when {
            amount >= 1_000_000 -> "${"%.2f".format(amount / 1_000_000)}M"
            amount >= 1_000 -> "${"%.2f".format(amount / 1_000)}K"
            amount >= 1 -> "%.2f".format(amount)
            amount >= 0.001 -> "%.6f".format(amount)
            else -> "%.9f".format(amount)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
