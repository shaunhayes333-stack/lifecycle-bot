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
    private lateinit var etRpcUrl: EditText
    private lateinit var btnShowHideKey: Button
    private lateinit var btnChangeKey: Button
    private lateinit var layoutConnected: View
    private lateinit var layoutDisconnected: View
    private lateinit var btnBackToBot: Button

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
    private val accentColor = AateUi.GREEN
    private val mutedColor  = AateUi.TEXT_MUTED
    private val dangerColor = AateUi.RED
    private val warnColor   = AateUi.AMBER

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
        // Apply theme BEFORE setContentView.
        // V5.9.1546 — NO-OP GUARD (mirrors MainActivity.applyTheme V5.9.1447).
        // setDefaultNightMode with a CHANGED value forces an Activity-stack
        // recreate (onCreate re-runs, re-inflating heavy layer drawables — a
        // multi-second main-thread stall that starves the bot loop). Only call
        // it when the desired mode actually differs from the current effective
        // mode, so opening Wallet can never trigger a redundant recreate storm.
        val cfg = ConfigStore.load(this)
        val desiredNightMode = if (cfg.darkModeEnabled)
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        else
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() != desiredNightMode) {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(desiredNightMode)
        }
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallet)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Wallet"
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
                if (cfg.darkModeEnabled) 0xFF0C1018.toInt() else 0xFFF5F5F5.toInt()
            ))
        }
        
        // Apply theme colors to root container
        applyThemeColors(cfg.darkModeEnabled)

        vm       = ViewModelProvider(this)[BotViewModel::class.java]
        currency = try {
            com.lifecyclebot.engine.BotService.instance?.currencyManager
                ?: com.lifecyclebot.engine.CurrencyManager(applicationContext)
        } catch (_: Exception) {
            com.lifecyclebot.engine.CurrencyManager(applicationContext)
        }
        bindViews()
        setupListeners()
        // V5.9.495z26 — inject Treasury Wallet card into the connected layout.
        injectTreasuryWalletCard()
        injectDeployableCapitalCard6986()
        injectCrossChainBridgeCard6987()
        injectMainMultiChainWalletCard6645()

        lifecycleScope.launch {
            vm.ui.collect { state -> updateUi(state) }
        }
    }

    /** Clean-install main-wallet path: one recovery phrase owns Solana, every
     * EVM chain, and Bitcoin. Generation is staged; the derived Solana signer
     * becomes ConfigStore's main wallet only after explicit backup confirmation. */
    private fun injectMainMultiChainWalletCard6645() {
        try {
            val container = layoutDisconnected as? android.view.ViewGroup ?: return
            val pad = (14 * resources.displayMetrics.density).toInt()
            val card = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(android.graphics.Color.parseColor("#101827"))
            }
            card.addView(android.widget.TextView(this).apply {
                text = "🌐 Create Main Multi-Chain Wallet"
                textSize = 16f
                setTextColor(android.graphics.Color.parseColor("#00E5A0"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(android.widget.TextView(this).apply {
                text = "One recovery phrase derives Solana, EVM/BSC and Bitcoin addresses. The wallet is not activated until you confirm the phrase is backed up."
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#B8C2D1"))
            })
            card.addView(android.widget.Button(this).apply {
                text = "Generate Main Wallet"
                setOnClickListener {
                    val cfgNow = com.lifecyclebot.data.ConfigStore.load(this@WalletActivity)
                    if (cfgNow.privateKeyB58.isNotBlank()) {
                        Toast.makeText(this@WalletActivity, "A main wallet already exists; use the migration flow.", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    val generated = com.lifecyclebot.engine.WalletManager
                        .getInstance(applicationContext).generateMultiChainWallet()
                    android.app.AlertDialog.Builder(this@WalletActivity)
                        .setTitle("Back up this recovery phrase")
                        .setMessage(
                            generated.mnemonic + "\n\n" +
                                "Solana: ${generated.solanaAddress}\n" +
                                "EVM: ${generated.ethereumAddress}\n" +
                                "Bitcoin: ${generated.bitcoinAddress}\n\n" +
                                "Write the phrase down offline. It will control real funds on every derived chain."
                        )
                        .setCancelable(false)
                        .setNegativeButton("Not backed up", null)
                        .setPositiveButton("I BACKED IT UP") { _, _ ->
                            val requestedRpc6645 = etRpcUrl.text.toString().trim()
                            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val ok = try {
                                    com.lifecyclebot.engine.WalletManager.getInstance(applicationContext)
                                        .activateStagedMultiChainAsMain(requestedRpc6645)
                                } catch (t: Throwable) {
                                    com.lifecyclebot.engine.ErrorLogger.error("WalletActivity", "multichain activation failed: ${t.message}", t)
                                    false
                                }
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    Toast.makeText(this@WalletActivity,
                                        if (ok) "Main multi-chain wallet activated" else "Activation failed—wallet remains staged",
                                        Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        .show()
                }
            })
            container.addView(card, 0)
        } catch (t: Throwable) {
            com.lifecyclebot.engine.ErrorLogger.warn("WalletActivity", "multichain card failed: ${t.message}")
        }
    }

    /**
     * V5.9.495z26 — programmatically add a "Treasury Wallet" card to the
     * wallet screen so the operator can see the second wallet's pubkey,
     * balance, and manage backup/regenerate without an XML layout change.
     * Sits at the top of the layoutConnected LinearLayout so it's visible
     * regardless of whether the trading wallet is connected.
     */
    /**
     * V5.0.6986 — DEPOSITED CAPITAL, NOT JUST SOL.
     *
     * The operator asked to be able to "just straight deposit USDC to the
     * wallet". That already works as capital and always has:
     * UniversalBridgeEngine.scanWalletCapacity values every SPL balance in the
     * wallet, and prepareCapital explicitly prefers USDC over SOL when picking
     * a funding source. Nothing needed building for the bot to USE it.
     *
     * What did not exist was any way to SEE it. This screen rendered
     * `ws.solBalance` and `ws.balanceUsd` and nothing else, so USDC sent to the
     * trading wallet was invisible — no confirmation it arrived, no sign it was
     * counted as deployable, and the USD figure did not move. From the
     * operator's side a working deposit and a lost deposit look identical.
     *
     * So this card renders what the bridge engine already computes: total
     * deployable USD across every token, the USDC balance specifically, the
     * source the engine would fund the next trade from, and the address to
     * send to. Read-only — it calls the same scanWalletCapacity the executor
     * uses, so it cannot disagree with what the bot will actually spend.
     */
    private fun injectDeployableCapitalCard6986() {
        try {
            val container = layoutConnected as? android.view.ViewGroup ?: return
            val card = AateUi.card(this, AateUi.CYAN)
            val body = AateUi.cardBody(card)
            body.addView(AateUi.headerRow(this, "DEPLOYABLE CAPITAL", "SOL + SPL", AateUi.CYAN))
            body.addView(AateUi.gap(this, 10))

            val tvTotal = AateUi.valueText(this, "—", AateUi.TEXT, sizeSp = 24f)
            body.addView(tvTotal)
            body.addView(AateUi.labelText(this, "TOTAL ACROSS ALL WALLET TOKENS"))
            body.addView(AateUi.divider(this))

            val tileUsdc = AateUi.kpiTile(this, "USDC", "—", AateUi.GREEN)
            val tileSol = AateUi.kpiTile(this, "SOL", "—", AateUi.PURPLE_BRIGHT)
            val tileSrc = AateUi.kpiTile(this, "NEXT SOURCE", "—", AateUi.TEXT_SECONDARY)
            body.addView(AateUi.kpiRow(this, listOf(tileUsdc, tileSol, tileSrc)))
            body.addView(AateUi.gap(this, 10))

            body.addView(AateUi.bodyText(
                this,
                "Send SOL or USDC (SPL) to the wallet address above. USDC is used " +
                    "as funding capital directly — the router prefers it over SOL and " +
                    "swaps it to the target token on entry.",
                AateUi.TEXT_MUTED,
                sizeSp = 10.5f,
            ))

            container.addView(card, 0)

            fun tileValue(tile: android.widget.LinearLayout): TextView? =
                tile.getChildAt(0) as? TextView

            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val wallet = com.lifecyclebot.engine.WalletManager.getWallet()
                        ?: return@launch
                    val cap = com.lifecyclebot.engine.UniversalBridgeEngine.scanWalletCapacity(wallet)
                    val usdcUi = cap.allBalances[com.lifecyclebot.engine.UniversalBridgeEngine.USDC_MINT] ?: 0.0
                    val solUi = cap.allBalances[com.lifecyclebot.engine.UniversalBridgeEngine.SOL_MINT]
                        ?: try { wallet.getSolBalance() } catch (_: Throwable) { 0.0 }
                    val srcLabel = try {
                        com.lifecyclebot.engine.UniversalBridgeEngine.mintLabel(cap.bestSourceMint)
                    } catch (_: Throwable) { "—" }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        tvTotal.text = "$" + "%.2f".format(cap.totalUsdValue)
                        tileValue(tileUsdc)?.text = "%.2f".format(usdcUi)
                        tileValue(tileSol)?.text = "%.4f".format(solUi)
                        tileValue(tileSrc)?.text = srcLabel
                    }
                } catch (_: Throwable) { /* read-only card; never break the screen */ }
            }
        } catch (_: Throwable) { }
    }

    /**
     * V5.0.6987 — CROSS-CHAIN BRIDGE, MADE VISIBLE AND TESTABLE.
     *
     * The Solana->EVM bridge (CryptoBridgeAdapter over deBridge DLN) is fully
     * coded and fully wired: CryptoUniverseExecutor calls buySolToEvm,
     * MarketsLiveExecutor calls sellEvmToSol on close, CryptoUniverseRouteResolver
     * gates on supportsRoundTrip. Nine of its ten readiness capabilities are
     * marked implemented. It is held shut by two flags, and the last one is
     * waiting on "the real chain matrix exercised and attested".
     *
     * None of that was visible anywhere in the app. No screen referenced the
     * adapter or the multi-chain vault, so the operator could not see that the
     * bridge existed, could not see what was blocking it, and — critically —
     * could not reach confirmBackupAndActivate, which is the one step the
     * design explicitly reserves for a human. The EVM signer could never be
     * created, so the route could never be tested even in principle.
     *
     * This card surfaces the real state and makes the human-gated steps
     * reachable. It does NOT enable the bridge: FULL_ROUND_TRIP_IMPLEMENTED
     * stays false, and the dry run never broadcasts a transaction.
     */
    private fun injectCrossChainBridgeCard6987() {
        try {
            val ctx = this
            val container = layoutConnected as? android.view.ViewGroup ?: return
            val card = AateUi.card(ctx, AateUi.AMBER)
            val body = AateUi.cardBody(card)

            val enabled = try {
                com.lifecyclebot.perps.crypto.CryptoBridgeAdapter.isConfigured()
            } catch (_: Throwable) { false }
            body.addView(AateUi.headerRow(
                ctx, "CROSS-CHAIN BRIDGE",
                if (enabled) "LIVE" else "DISABLED",
                if (enabled) AateUi.GREEN else AateUi.AMBER,
            ))
            body.addView(AateUi.gap(ctx, 8))

            val chains = try {
                com.lifecyclebot.perps.crypto.CryptoBridgeAdapter.configuredChains6987()
            } catch (_: Throwable) { emptyMap() }
            val distinctChains = chains.values.map { it.id }.distinct().size
            val blocking = try {
                com.lifecyclebot.perps.crypto.CryptoBridgeAdapter
                    .readiness6647("base")?.missing().orEmpty()
            } catch (_: Throwable) { emptyList() }

            body.addView(AateUi.bodyText(
                ctx,
                "Route: Solana → USDC → target chain token, and back. " +
                    "$distinctChains chains configured (${chains.size} keys incl. aliases).",
                AateUi.TEXT_SECONDARY, sizeSp = 11f,
            ))
            body.addView(AateUi.gap(ctx, 6))
            body.addView(AateUi.bodyText(
                ctx,
                if (blocking.isEmpty()) "No readiness capability outstanding."
                else "Blocking: ${blocking.joinToString(", ")}",
                if (blocking.isEmpty()) AateUi.GREEN else AateUi.AMBER,
                sizeSp = 11f,
            ))

            body.addView(AateUi.divider(ctx))

            // ── multi-chain signer state ───────────────────────────────────
            val stored = try { com.lifecyclebot.engine.MultiChainWalletVault6546.load(ctx) } catch (_: Throwable) { null }
            val signerState = when {
                stored == null -> "NOT CREATED"
                !stored.backupConfirmed -> "CREATED · BACKUP NOT CONFIRMED"
                !stored.activeMain -> "BACKED UP · NOT ACTIVATED"
                else -> "ACTIVE"
            }
            val tvSigner = AateUi.bodyText(ctx, "EVM signer: $signerState", AateUi.TEXT, sizeSp = 11.5f)
            body.addView(tvSigner)
            if (stored != null) {
                body.addView(AateUi.gap(ctx, 4))
                body.addView(AateUi.bodyText(ctx, stored.ethereumAddress, AateUi.TEXT_MUTED, sizeSp = 10f).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                })
            }
            body.addView(AateUi.gap(ctx, 10))

            val tvOut = AateUi.bodyText(ctx, "", AateUi.TEXT_SECONDARY, sizeSp = 10.5f)

            fun btn(label: String, accent: Int, onTap: () -> Unit) = Button(ctx).apply {
                text = label
                textSize = 12f
                setTextColor(accent)
                background = AateUi.pillBackground(ctx, accent)
                setPadding(AateUi.dp(ctx, 12), AateUi.dp(ctx, 6), AateUi.dp(ctx, 12), AateUi.dp(ctx, 6))
                setOnClickListener { onTap() }
            }

            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

            if (stored == null) {
                row.addView(btn("Create EVM signer", AateUi.CYAN) {
                    try {
                        val w = com.lifecyclebot.engine.MultiChainWalletGenerator6546.generate()
                        com.lifecyclebot.engine.MultiChainWalletVault6546.save(ctx, w)
                        // The vault's own contract: the UI must show the phrase
                        // and take an explicit acknowledgement before activating.
                        // Shown here only, never logged, never sent anywhere.
                        AlertDialog.Builder(ctx)
                            .setTitle("Recovery phrase — write it down now")
                            .setMessage(
                                w.mnemonic + "\n\n" +
                                    "ETH/EVM: ${w.ethereumAddress}\n" +
                                    "This phrase is shown once here. It is stored encrypted on this " +
                                    "device only. Anyone with it controls these funds.",
                            )
                            .setPositiveButton("I have written it down", null)
                            .show()
                        recreate()
                    } catch (e: Throwable) {
                        tvOut.text = "Create failed: ${e.message}"
                    }
                })
            } else if (!stored.backupConfirmed || !stored.activeMain) {
                row.addView(btn("Confirm backup + activate", AateUi.AMBER) {
                    AlertDialog.Builder(ctx)
                        .setTitle("Activate multi-chain signer?")
                        .setMessage(
                            "This marks the recovery phrase as backed up and makes this " +
                                "wallet the active multi-chain signer. Only do this if you " +
                                "have written the phrase down. It does not enable bridge " +
                                "execution on its own.",
                        )
                        .setPositiveButton("Activate") { _, _ ->
                            try {
                                com.lifecyclebot.engine.MultiChainWalletVault6546.confirmBackupAndActivate(ctx)
                                recreate()
                            } catch (e: Throwable) {
                                tvOut.text = "Activate failed: ${e.message}"
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                })
            }

            row.addView(btn("Run chain dry run", AateUi.PURPLE_BRIGHT) {
                tvOut.text = "Probing ${distinctChains} chains…"
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val probes = try {
                        com.lifecyclebot.perps.crypto.CryptoBridgeAdapter.dryRunAll6987(ctx)
                    } catch (e: Throwable) {
                        emptyList()
                    }
                    val text = if (probes.isEmpty()) "Dry run produced no results."
                    else buildString {
                        appendLine(
                            try { com.lifecyclebot.perps.crypto.CryptoBridgeAdapter.dryRunSummary6987(probes) }
                            catch (_: Throwable) { "" }
                        )
                        probes.forEach { p ->
                            val mark = if (p.unfundedGreen) "OK " else "-- "
                            append(mark).append(p.chainKey).append(" (").append(p.chainId).append(")  ")
                            if (p.unfundedGreen) {
                                append("gas=").append(p.gasPriceGwei).append("gwei nonce=").append(p.nonce)
                            } else {
                                append(p.blockingList().joinToString(","))
                                if (p.error.isNotBlank()) append("  ").append(p.error)
                            }
                            appendLine()
                        }
                        appendLine()
                        append(
                            "Submission and finality are NOT proven here — they need a funded " +
                                "transaction on a real chain. Nothing was broadcast.",
                        )
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        tvOut.text = text
                    }
                }
            })

            body.addView(row)
            body.addView(AateUi.gap(ctx, 8))
            body.addView(tvOut)

            container.addView(card, 0)
        } catch (_: Throwable) { }
    }

    private fun injectTreasuryWalletCard() {
        try {
            val ctx = this
            // V5.9.495z26 — layoutConnected is declared as View; we need its
            // ViewGroup capability to addView() the card. The XML root for
            // layoutConnected is a LinearLayout so the cast is safe.
            val container = layoutConnected as? android.view.ViewGroup ?: return
            val card = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(android.graphics.Color.parseColor("#0D1320"))
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                val mPx = (12 * resources.displayMetrics.density).toInt()
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(mPx, mPx, mPx, mPx) }
                layoutParams = lp
            }
            val title = android.widget.TextView(ctx).apply {
                text = "🏦 Treasury Wallet"
                setTextColor(AateUi.AMBER)
                textSize = 16f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
            val tvPk = android.widget.TextView(ctx).apply {
                text = com.lifecyclebot.engine.TreasuryWalletManager.publicKey().ifBlank { "(initialising…)" }
                setTextColor(AateUi.TEXT)
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            val tvBal = android.widget.TextView(ctx).apply {
                text = "Balance: ${"%.4f".format(com.lifecyclebot.engine.TreasuryWalletManager.getBalance())} SOL"
                setTextColor(AateUi.TEXT_SECONDARY)
                textSize = 13f
                setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
            }
            val btnRow = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            fun mkBtn(label: String, color: String, action: () -> Unit) =
                android.widget.Button(ctx).apply {
                    text = label
                    setBackgroundColor(android.graphics.Color.parseColor(color))
                    setTextColor(AateUi.TEXT)
                    textSize = 11f
                    val mPx = (4 * resources.displayMetrics.density).toInt()
                    val lp = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { setMargins(mPx, mPx, mPx, mPx) }
                    layoutParams = lp
                    setOnClickListener { action() }
                }
            btnRow.addView(mkBtn("Copy", "#101E33") {
                val pk = com.lifecyclebot.engine.TreasuryWalletManager.publicKey()
                if (pk.isNotBlank()) {
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("treasury_pk", pk))
                    Toast.makeText(ctx, "Treasury address copied", Toast.LENGTH_SHORT).show()
                }
            })
            btnRow.addView(mkBtn("Refresh", "#101E33") {
                lifecycleScope.launch {
                    val bal = com.lifecyclebot.engine.TreasuryWalletManager.refreshBalance()
                    tvBal.text = "Balance: ${"%.4f".format(bal)} SOL"
                }
            })
            btnRow.addView(mkBtn("Reveal Key", "#7F1D1D") {
                android.app.AlertDialog.Builder(ctx)
                    .setTitle("⚠ Reveal Treasury Private Key")
                    .setMessage("Anyone with this key controls treasury funds. Continue?")
                    .setPositiveButton("Show") { _, _ ->
                        val key = com.lifecyclebot.engine.TreasuryWalletManager.exportPrivateKey(ctx)
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle("Treasury Private Key")
                            .setMessage(key.ifBlank { "(none — wallet not initialised)" })
                            .setPositiveButton("Copy") { _, _ ->
                                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("treasury_key", key))
                                Toast.makeText(ctx, "Copied — store securely!", Toast.LENGTH_LONG).show()
                            }
                            .setNegativeButton("Close", null)
                            .show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
            btnRow.addView(mkBtn("Regenerate", "#7F1D1D") {
                android.app.AlertDialog.Builder(ctx)
                    .setTitle("⚠ Regenerate Treasury Wallet")
                    .setMessage("This destroys the current treasury keypair and creates a new one. Any SOL in the old treasury wallet will be UNREACHABLE unless you've backed up the private key. Continue?")
                    .setPositiveButton("Regenerate") { _, _ ->
                        val newPk = com.lifecyclebot.engine.TreasuryWalletManager.regenerate(ctx)
                        tvPk.text = newPk.ifBlank { "(failed — see logs)" }
                        tvBal.text = "Balance: 0.0000 SOL"
                        Toast.makeText(ctx, "New treasury wallet generated", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
            card.addView(title)
            card.addView(tvPk)
            card.addView(tvBal)
            card.addView(btnRow)
            // Insert at top of layoutConnected.
            container.addView(card, 0)
        } catch (e: Exception) {
            com.lifecyclebot.engine.ErrorLogger.warn("WalletActivity",
                "treasury card injection failed: ${e.message}")
        }
    }
    
    // Handle back button press - just close activity, don't disconnect wallet
    @android.annotation.SuppressLint("MissingSuperCall")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Just finish the activity - wallet stays connected
        finish()
    }

    private fun bindViews() {
        tvConnectionStatus  = findViewById(R.id.tvConnectionStatus)
        tvPublicKey         = findViewById(R.id.tvPublicKey)
        btnConnect          = findViewById(R.id.btnConnect)
        btnDisconnect       = findViewById(R.id.btnDisconnect)
        etPrivKeyInput      = findViewById(R.id.etPrivKeyInput)
        etRpcUrl            = findViewById(R.id.etRpcUrl)
        btnChangeKey        = findViewById(R.id.btnChangeKey)
        btnShowHideKey      = findViewById(R.id.btnShowHideKey)
        layoutConnected     = findViewById(R.id.layoutConnected)
        layoutDisconnected  = findViewById(R.id.layoutDisconnected)
        btnBackToBot        = findViewById(R.id.btnBackToBot)
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
        // Back to bot button - just finish activity, wallet stays connected
        btnBackToBot.setOnClickListener {
            finish()
        }
        
        btnConnect.setOnClickListener {
            val key = etPrivKeyInput.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(this, "Enter your private key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // Get RPC URL from input field (can be empty - will use fallbacks)
            val userRpcUrl = etRpcUrl.text.toString().trim()
            
            // Save key first
            val cfg = ConfigStore.load(this)
            val updatedCfg = cfg.copy(privateKeyB58 = key, rpcUrl = userRpcUrl)
            vm.saveConfig(updatedCfg)
            
            // Determine RPC URL to use:
            // 1. User's input (if provided)
            // 2. Helius key from settings (if available)
            // 3. Empty string (WalletManager will use fallbacks)
            val rpcUrl = when {
                userRpcUrl.isNotBlank() -> userRpcUrl
                updatedCfg.heliusApiKey.isNotBlank() -> "https://mainnet.helius-rpc.com/?api-key=${updatedCfg.heliusApiKey}"
                else -> ""  // Empty = use auto fallbacks
            }
            
            Toast.makeText(this, "Connecting wallet...", Toast.LENGTH_SHORT).show()
            vm.connectWallet(key, rpcUrl)
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
                    com.lifecyclebot.engine.ErrorLogger.info("WalletActivity", "Manual balance refresh requested")
                    com.lifecyclebot.engine.WalletManager.getInstance(applicationContext).refreshBalance()
                } catch (e: Exception) {
                    com.lifecyclebot.engine.ErrorLogger.error("WalletActivity", "Balance refresh failed: ${e.message}", e)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(this@WalletActivity, "Refresh failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Toast.makeText(this, "Refreshing…", Toast.LENGTH_SHORT).show()
        }

        // V5.9.495g — withdrawal uses the LIVE-capped treasury so users
        // can't request to withdraw SOL the wallet doesn't actually hold.
        fun currentTreasury(): Double {
            val cfg = com.lifecyclebot.data.ConfigStore.load(this)
            val walletSolNow = try {
                com.lifecyclebot.engine.BotService.status.walletSol.takeIf { it > 0.0 } ?: 0.0
            } catch (_: Exception) { 0.0 }
            return com.lifecyclebot.engine.TreasuryManager
                .effectiveLockedSol(walletSolNow, cfg.paperMode)
        }

        // ── Withdraw listeners ──────────────────────────────────────
        fun applyWithdrawPct(pct: Int) {
            withdrawPct = pct.coerceIn(0, 100)
            seekWithdrawPct.progress = withdrawPct
            tvWithdrawPct.text = "$withdrawPct%"
            val treasury = currentTreasury()
            val amount   = treasury * withdrawPct / 100.0
            val solPrice = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            tvWithdrawSolAmt.text = "≈ ${"%.4f".format(amount)}◎ (${"$%.2f".format(amount * solPrice)})"
            // Warning for full exit
            tvWithdrawWarning.visibility =
                if (withdrawPct >= 100) android.view.View.VISIBLE else android.view.View.GONE
            // Highlight 100% button red, others reset
            btnWith100.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (withdrawPct == 100) 0xFF3D1010.toInt() else AateUi.SURFACE)
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
            val treasury = currentTreasury()
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
                    tvWithdrawStatus.setTextColor(AateUi.AMBER)
                    btnWithdrawConfirm.isEnabled = false
                    vm.withdrawFromTreasury(pct, dest) { result ->
                        btnWithdrawConfirm.isEnabled = true
                        val ok = result.startsWith("OK") || result.startsWith("PAPER")
                        tvWithdrawStatus.text  = result
                        tvWithdrawStatus.setTextColor(
                            if (ok) AateUi.GREEN else AateUi.RED)
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

        // Balance - Fixed: SOL shows SOL, USD shows USD
        tvSolBalance.text = "◎ %.4f".format(ws.solBalance)  // SOL balance with SOL symbol
        tvUsdBalance.text = if (ws.balanceUsd > 0) "\$%.2f".format(ws.balanceUsd) else "—"  // USD value
        tvSolPrice.text   = if (ws.solPriceUsd > 0) "SOL = \$%.2f".format(ws.solPriceUsd) else "—"
        if (ws.lastRefreshed > 0) {
            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            tvLastRefreshed.text = "Updated ${sdf.format(java.util.Date(ws.lastRefreshed))}"
        }

        // Withdraw card — always updated regardless of connection state.
        // V5.9.495g — show LIVE-capped treasury so user sees what's actually
        // claimable (paper-mode untouched). Reuses `cfg` already in scope
        // (V5.9.495g.1 fix: removed duplicate val cfg declaration).
        val treasury  = com.lifecyclebot.engine.TreasuryManager
            .effectiveLockedSol(ws.solBalance, cfg.paperMode)
        val solPx     = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
        val tradeable = (ws.solBalance - 0.05 - treasury).coerceAtLeast(0.0)
        tvWithdrawTreasuryBal.text = "${"%.4f".format(treasury)}◎"
        tvWithdrawTradeable.text   = "${"%.4f".format(tradeable)}◎"

        // V5.0.7022 — the same three numbers, as the split they are.
        //
        // treasury / tradeable / gas reserve is already computed right here and
        // two of the three were printed as bare figures on a different card.
        // "How much of this can I actually trade with" is the question a wallet
        // is opened to answer, and it is a proportion, so it gets a ring.
        //
        // The 0.05 reserve is not a rounding allowance — it is the gas floor
        // subtracted on the line above, so it is shown rather than absorbed
        // into one of the other two.
        try {
            findViewById<DonutView7020>(R.id.walletAllocDonut)?.let { d ->
                val reserve = kotlin.math.min(0.05, ws.solBalance).coerceAtLeast(0.0)
                val total = tradeable + treasury + reserve
                if (total > 0.0) {
                    d.centreText = if (ws.solBalance > 0.0) {
                        "${(tradeable / ws.solBalance * 100.0).toInt()}"
                    } else "0"
                    d.centreCaption = "% FREE"
                    d.setSegments(
                        floatArrayOf(tradeable.toFloat(), treasury.toFloat(), reserve.toFloat()),
                        intArrayOf(AateUi.GREEN, AateUi.PURPLE, AateUi.TEXT_MUTED),
                    )
                }
            }
        } catch (_: Throwable) {}
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
    
    private fun applyThemeColors(isDarkMode: Boolean) {
        val scrollView = findViewById<ScrollView>(R.id.walletScrollView)
        val container = findViewById<LinearLayout>(R.id.walletContainer)
        
        if (isDarkMode) {
            scrollView.setBackgroundColor(0xFF06080C.toInt())
            container?.setBackgroundColor(0xFF06080C.toInt())
            window.statusBarColor = 0xFF06080C.toInt()
            window.navigationBarColor = 0xFF06080C.toInt()
            
            // Apply dark card backgrounds to all cards
            applyDarkCardBackgrounds(container)
        } else {
            scrollView.setBackgroundColor(0xFFF8F9FA.toInt())
            container?.setBackgroundColor(0xFFF8F9FA.toInt())
            window.statusBarColor = 0xFFF5F5F5.toInt()
            window.navigationBarColor = 0xFFF5F5F5.toInt()
            // Update text colors for light mode
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }
    
    private fun applyDarkCardBackgrounds(container: LinearLayout?) {
        container ?: return
        // Find all LinearLayouts that are direct children (the cards)
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is LinearLayout) {
                // Apply dark card background drawable
                child.setBackgroundResource(R.drawable.card_bg_dark)
            }
        }
    }
}
