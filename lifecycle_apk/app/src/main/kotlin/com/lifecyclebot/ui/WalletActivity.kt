package com.lifecyclebot.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.*
import android.widget.*
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.lifecyclebot.R
import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.engine.WalletConnectionState
import kotlinx.coroutines.launch

class WalletActivity : AppCompatActivity() {

    private lateinit var vm: BotViewModel
    private lateinit var currency: com.lifecyclebot.engine.CurrencyManager

    // connection section
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvPublicKey: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var etPrivKeyInput: EditText
    private lateinit var btnShowHideKey: Button
    private lateinit var btnChangeKey: Button
    private lateinit var layoutConnected: View
    private lateinit var layoutDisconnected: View

    // balance section
    private lateinit var tvSolBalance: TextView
    private lateinit var tvUsdBalance: TextView
    private lateinit var tvSolPrice: TextView
    private lateinit var btnRefreshBalance: Button
    private lateinit var tvLastRefreshed: TextView

    // P&L section
    private lateinit var tvTotalPnl: TextView
    private lateinit var tvTotalPnlPct: TextView
    private lateinit var tvTotalTrades: TextView
    private lateinit var tvWinRate: TextView
    private lateinit var tvBestTrade: TextView
    private lateinit var tvWorstTrade: TextView
    private lateinit var pnlChart: PnlChartView

    private var keyVisible = false
    private val accentColor = 0xFF00E5A0.toInt()
    private val mutedColor  = 0xFF4A5E70.toInt()
    private val dangerColor = 0xFFFF3D5A.toInt()
    private val warnColor   = 0xFFFFB700.toInt()

    // Withdraw views
    private lateinit var tvWithdrawTreasuryBal: TextView
    private lateinit var tvWithdrawTradeable:   TextView
    private lateinit var tvWithdrawPct:         TextView
    private lateinit var tvWithdrawSolAmt:      TextView
    private lateinit var seekWithdrawPct:       SeekBar
    private lateinit var etWithdrawDest:        EditText
    private lateinit var tvWithdrawWarning:     TextView
    private lateinit var btnWithdrawConfirm:    Button
    private lateinit var tvWithdrawStatus:      TextView
    private lateinit var btnWith25:             Button
    private lateinit var btnWith50:             Button
    private lateinit var btnWith75:             Button
    private lateinit var btnWith100:            Button
    private var withdrawPct: Int = 50   // 0–100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallet)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Wallet"
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF0C1018.toInt()))
        }

        vm       = ViewModelProvider(this)[BotViewModel::class.java]
        currency = try {
            com.lifecyclebot.engine.BotService.instance?.currencyManager
                ?: com.lifecyclebot.engine.CurrencyManager(applicationContext)
        } catch (_: Exception) {
            com.lifecyclebot.engine.CurrencyManager(applicationContext)
        }
        bindViews()
        setupListeners()

        lifecycleScope.launch {
            vm.ui.collect { state -> updateUi(state) }
        }
    }

    private fun bindViews() {
        tvConnectionStatus  = findViewById(R.id.tvConnectionStatus)
        tvPublicKey         = findViewById(R.id.tvPublicKey)
        btnConnect          = findViewById(R.id.btnConnect)
        btnDisconnect       = findViewById(R.id.btnDisconnect)
        etPrivKeyInput      = findViewById(R.id.etPrivKeyInput)
        btnChangeKey        = findViewById(R.id.btnChangeKey)
        btnShowHideKey      = findViewById(R.id.btnShowHideKey)
        layoutConnected     = findViewById(R.id.layoutConnected)
        layoutDisconnected  = findViewById(R.id.layoutDisconnected)
        tvSolBalance        = findViewById(R.id.tvSolBalance)
        tvUsdBalance        = findViewById(R.id.tvUsdBalance)
        tvSolPrice          = findViewById(R.id.tvSolPrice)
        btnRefreshBalance   = findViewById(R.id.btnRefreshBalance)
        tvLastRefreshed     = findViewById(R.id.tvLastRefreshed)
        tvTotalPnl          = findViewById(R.id.tvTotalPnl)
        tvTotalPnlPct       = findViewById(R.id.tvTotalPnlPct)
        tvTotalTrades       = findViewById(R.id.tvTotalTrades)
        tvWinRate           = findViewById(R.id.tvWinRate)
        tvBestTrade         = findViewById(R.id.tvBestTrade)
        tvWorstTrade        = findViewById(R.id.tvWorstTrade)
        pnlChart            = findViewById(R.id.pnlChart)
        // Withdraw
        tvWithdrawTreasuryBal = findViewById(R.id.tvWithdrawTreasuryBal)
        tvWithdrawTradeable   = findViewById(R.id.tvWithdrawTradeable)
        tvWithdrawPct         = findViewById(R.id.tvWithdrawPct)
        tvWithdrawSolAmt      = findViewById(R.id.tvWithdrawSolAmt)
        seekWithdrawPct       = findViewById(R.id.seekWithdrawPct)
        etWithdrawDest        = findViewById(R.id.etWithdrawDest)
        tvWithdrawWarning     = findViewById(R.id.tvWithdrawWarning)
        btnWithdrawConfirm    = findViewById(R.id.btnWithdrawConfirm)
        tvWithdrawStatus      = findViewById(R.id.tvWithdrawStatus)
        btnWith25             = findViewById(R.id.btnWith25)
        btnWith50             = findViewById(R.id.btnWith50)
        btnWith75             = findViewById(R.id.btnWith75)
        btnWith100            = findViewById(R.id.btnWith100)
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener {
            val key = etPrivKeyInput.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(this, "Enter your private key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cfg = ConfigStore.load(this)
            // Save key and connect
            vm.saveConfig(cfg.copy(privateKeyB58 = key))
            vm.connectWallet(key, cfg.rpcUrl)
        }

        btnDisconnect.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Disconnect Wallet")
                .setMessage("This will remove your private key from the app. Are you sure?")
                .setPositiveButton("Disconnect") { dialog: android.content.DialogInterface, _: Int ->
                    vm.disconnectWallet()
                    etPrivKeyInput.setText("")
                    Toast.makeText(this, "Wallet disconnected", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnShowHideKey.setOnClickListener {
            keyVisible = !keyVisible
            etPrivKeyInput.inputType = if (keyVisible)
                android.text.InputType.TYPE_CLASS_TEXT
            else
                android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            btnShowHideKey.text = if (keyVisible) "Hide" else "Show"
            etPrivKeyInput.setSelection(etPrivKeyInput.text.length)
        }

        btnChangeKey.setOnClickListener {
            // Clear saved key — force user to re-paste it
            val cfg2 = ConfigStore.load(this)
            vm.saveConfig(cfg2.copy(privateKeyB58 = ""))
            vm.disconnectWallet()
            etPrivKeyInput.setText("")
            etPrivKeyInput.hint = "Paste your base58 private key"
            btnConnect.text     = "Connect"
            btnChangeKey.visibility = View.GONE
            Toast.makeText(this, "Key cleared — paste new key", Toast.LENGTH_SHORT).show()
        }

        btnRefreshBalance.setOnClickListener {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    com.lifecyclebot.engine.BotService.walletManager.refreshBalance()
                } catch (_: Exception) {}
            }
            Toast.makeText(this, "Refreshing…", Toast.LENGTH_SHORT).show()
        }

        // ── Withdraw listeners ──────────────────────────────────────
        fun applyWithdrawPct(pct: Int) {
            withdrawPct = pct.coerceIn(0, 100)
            seekWithdrawPct.progress = withdrawPct
            tvWithdrawPct.text = "$withdrawPct%"
            val treasury = com.lifecyclebot.engine.TreasuryManager.treasurySol
            val amount   = treasury * withdrawPct / 100.0
            val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            tvWithdrawSolAmt.text = "≈ ${"%.4f".format(amount)}◎ (${"$%.2f".format(amount * solPrice)})"
            // Warning for full exit
            tvWithdrawWarning.visibility =
                if (withdrawPct >= 100) android.view.View.VISIBLE else android.view.View.GONE
            // Highlight 100% button red, others reset
            btnWith100.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (withdrawPct == 100) 0xFF3D1010.toInt() else 0xFF111720.toInt())
        }

        seekWithdrawPct.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) { applyWithdrawPct(p) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        btnWith25.setOnClickListener  { applyWithdrawPct(25) }
        btnWith50.setOnClickListener  { applyWithdrawPct(50) }
        btnWith75.setOnClickListener  { applyWithdrawPct(75) }
        btnWith100.setOnClickListener { applyWithdrawPct(100) }

        btnWithdrawConfirm.setOnClickListener {
            val treasury = com.lifecyclebot.engine.TreasuryManager.treasurySol
            if (treasury < 0.001) {
                Toast.makeText(this, "Treasury is empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val pct  = withdrawPct / 100.0
            val amt  = treasury * pct
            val dest = etWithdrawDest.text.toString().trim()
            val destLabel = if (dest.isBlank()) "your bot wallet (self)" else "${dest.take(8)}…${dest.takeLast(4)}"

            val confirmMsg: String = if (withdrawPct >= 100)
                "⚠ FULL EXIT\n\nWithdraw ALL ${"%,.4f".format(amt)}◎ from treasury to $destLabel?\n\nThe treasury will be empty after this."
            else
                "Withdraw $withdrawPct% (${"%,.4f".format(amt)}◎) from treasury to $destLabel?"

            AlertDialog.Builder(this)
                .setTitle("Confirm Withdrawal")
                .setMessage(confirmMsg)
                .setPositiveButton(if (withdrawPct >= 100) "WITHDRAW ALL" else "Withdraw") { dialog: android.content.DialogInterface, _: Int ->
                    tvWithdrawStatus.text = "Processing…"
                    tvWithdrawStatus.setTextColor(0xFFFFB700.toInt())
                    btnWithdrawConfirm.isEnabled = false
                    vm.withdrawFromTreasury(pct, dest) { result ->
                        btnWithdrawConfirm.isEnabled = true
                        val ok = result.startsWith("OK") || result.startsWith("PAPER")
                        tvWithdrawStatus.text  = result
                        tvWithdrawStatus.setTextColor(
                            if (ok) 0xFF00E5A0.toInt() else 0xFFFF3D5A.toInt())
                        if (ok) {
                            Toast.makeText(this, "Withdrawal complete", Toast.LENGTH_LONG).show()
                            applyWithdrawPct(50)  // reset to 50% after success
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateUi(state: UiState) {
        val ws  = state.walletState
        val cfg = state.config

        when (ws.connectionState) {
            WalletConnectionState.DISCONNECTED -> {
                tvConnectionStatus.text      = "● DISCONNECTED"
                tvConnectionStatus.setTextColor(mutedColor)
                layoutConnected.visibility   = View.GONE
                layoutDisconnected.visibility = View.VISIBLE
                // Security: never pre-fill the raw private key into the EditText.
                // Show a "Key saved" indicator instead — user taps "Change" to re-enter.
                if (cfg.privateKeyB58.isNotBlank()) {
                    etPrivKeyInput.setText("")
                    etPrivKeyInput.hint = "Key already saved — tap Change Key to update"
                    btnConnect.text     = "Reconnect"
                    btnChangeKey.visibility = View.VISIBLE
                } else {
                    etPrivKeyInput.hint = "Paste your base58 private key"
                    btnConnect.text     = "Connect"
                    btnChangeKey.visibility = View.GONE
                }
            }
            WalletConnectionState.CONNECTING -> {
                tvConnectionStatus.text      = "◌ CONNECTING…"
                tvConnectionStatus.setTextColor(warnColor)
            }
            WalletConnectionState.CONNECTED -> {
                tvConnectionStatus.text      = "● CONNECTED"
                tvConnectionStatus.setTextColor(accentColor)
                layoutConnected.visibility   = View.VISIBLE
                layoutDisconnected.visibility = View.GONE
                tvPublicKey.text             = ws.publicKey
            }
            WalletConnectionState.ERROR -> {
                tvConnectionStatus.text      = "✕ ERROR: ${ws.errorMessage}"
                tvConnectionStatus.setTextColor(dangerColor)
                layoutConnected.visibility   = View.GONE
                layoutDisconnected.visibility = View.VISIBLE
            }
        }

        // Balance
        tvSolBalance.text = currency.format(ws.solBalance)
        tvUsdBalance.text = if (ws.solBalance > 0 && currency.selectedCurrency != "SOL")
            "◎ %.4f".format(ws.solBalance) else if (ws.balanceUsd > 0) "\$%.2f".format(ws.balanceUsd) else "—"
        tvSolPrice.text   = if (ws.solPriceUsd > 0) "SOL = $%.2f".format(ws.solPriceUsd) else "—"
        if (ws.lastRefreshed > 0) {
            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            tvLastRefreshed.text = "Updated ${sdf.format(java.util.Date(ws.lastRefreshed))}"
        }

        // Withdraw card — always updated regardless of connection state
        val treasury  = com.lifecyclebot.engine.TreasuryManager.treasurySol
        val solPx     = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
        val tradeable = (ws.solBalance - 0.05 - treasury).coerceAtLeast(0.0)
        tvWithdrawTreasuryBal.text = "${"%.4f".format(treasury)}◎"
        tvWithdrawTradeable.text   = "${"%.4f".format(tradeable)}◎"
        // Refresh SOL amount label when balance updates
        val wdAmt = treasury * withdrawPct / 100.0
        tvWithdrawSolAmt.text = "≈ ${"%.4f".format(wdAmt)}◎ (${"$%.2f".format(wdAmt * solPx)})"

        // P&L stats
        val pnl     = ws.totalPnlSol
        val pnlPct  = ws.totalPnlPct
        val pnlCol  = if (pnl >= 0) accentColor else dangerColor

        tvTotalPnl.text = currency.format(pnl, showPlus = true)
        tvTotalPnl.setTextColor(pnlCol)
        tvTotalPnlPct.text = "%+.1f%%".format(pnlPct)
        tvTotalPnlPct.setTextColor(pnlCol)
        tvTotalTrades.text = "${ws.totalTrades} trades"
        tvWinRate.text     = "${ws.winRate}% win rate  (${ws.winningTrades}W / ${ws.losingTrades}L)"
        tvBestTrade.text   = "Best:  %+.4f SOL".format(ws.bestTradePnl)
        tvWorstTrade.text  = "Worst: %+.4f SOL".format(ws.worstTradePnl)

        // P&L chart
        pnlChart.points = ws.pnlHistory
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
