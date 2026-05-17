package com.lifecyclebot.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lifecyclebot.R
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.engine.ErrorLogger

/**
 * V5.2: Settings Bottom Sheet Dialog
 * Replaces the inline settings card in MainActivity for cleaner UI.
 */
class SettingsBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "SettingsSheet"
        
        fun newInstance(): SettingsBottomSheet {
            return SettingsBottomSheet()
        }
    }
    
    // Callback for when settings are saved
    var onSettingsSaved: ((BotConfig) -> Unit)? = null
    var onExportRequested: (() -> Unit)? = null
    var onImportRequested: (() -> Unit)? = null
    var onClearApiKeys: (() -> Unit)? = null
    var onTestToast: (() -> Unit)? = null
    
    // Current config to populate
    private var currentConfig: BotConfig? = null
    
    // Views
    // V5.9.663 — etActiveToken removed from dialog_settings.xml per
    // operator: 'we don't need it'. The same id still exists on
    // activity_main.xml; MainActivity owns the read/write of
    // cfg.activeToken from there. Do NOT re-add a findViewById here
    // or it will NullPointerException on the Settings sheet.
    private lateinit var spMode: Spinner
    private lateinit var spAutoTrade: Spinner
    private lateinit var etStopLoss: EditText
    private lateinit var etExitScore: EditText
    private lateinit var etSmallBuy: EditText
    private lateinit var etLargeBuy: EditText
    private lateinit var etSlippage: EditText
    private lateinit var etPoll: EditText
    private lateinit var switchTopUp: SwitchCompat
    private lateinit var etTopUpMinGain: EditText
    private lateinit var etTopUpGainStep: EditText
    private lateinit var etTopUpMaxCount: EditText
    private lateinit var etTopUpMaxSol: EditText
    private lateinit var etRpc: EditText
    private lateinit var etHeliusKey: EditText
    private lateinit var etBirdeyeKey: EditText
    private lateinit var etGroqKey: EditText
    private lateinit var etGeminiKey: EditText
    // V5.9.361 — ElevenLabs TTS key for per-persona character voices.
    private lateinit var etElevenLabsKey: EditText
    private lateinit var etJupiterKey: EditText
    private lateinit var etTgBotToken: EditText
    private lateinit var etTgChatId: EditText
    private lateinit var switchNotifications: SwitchCompat
    private lateinit var switchSounds: SwitchCompat
    private lateinit var switchDarkMode: SwitchCompat
    // V5.9.814 — AI scoring mode selector (3-way: CLASSIC / MODERN / UNIFIED)
    private lateinit var spScoringMode: Spinner
    private lateinit var tvScoringModeHint: TextView
    private lateinit var etWatchlist: EditText
    
    // V5.7.6: Trading Mode
    private lateinit var spTradingMode: Spinner
    private lateinit var switchMemeTrader: SwitchCompat
    private lateinit var switchMarketsTrader: SwitchCompat
    private lateinit var switchCryptoAltsTrader: SwitchCompat
    // V5.7.7: Individual Markets sub-trader switches
    private lateinit var switchPerpsEnabled: SwitchCompat
    private lateinit var switchStocksEnabled: SwitchCompat
    private lateinit var switchCommoditiesEnabled: SwitchCompat
    private lateinit var switchMetalsEnabled: SwitchCompat
    private lateinit var switchForexEnabled: SwitchCompat
    
    fun setConfig(config: BotConfig) {
        currentConfig = config
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_settings, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Make bottom sheet expanded by default
        dialog?.setOnShowListener {
            val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                // Set max height to 90% of screen
                it.layoutParams.height = (resources.displayMetrics.heightPixels * 0.9).toInt()
            }
        }
        
        bindViews(view)
        setupSpinners()
        setupClickListeners(view)
        setupApiKeyLinks(view)
        populateFromConfig()
    }
    
    private fun bindViews(view: View) {
        // V5.9.663 — etActiveToken binding removed (id no longer in layout).
        spMode = view.findViewById(R.id.spMode)
        spAutoTrade = view.findViewById(R.id.spAutoTrade)
        etStopLoss = view.findViewById(R.id.etStopLoss)
        etExitScore = view.findViewById(R.id.etExitScore)
        etSmallBuy = view.findViewById(R.id.etSmallBuy)
        etLargeBuy = view.findViewById(R.id.etLargeBuy)
        etSlippage = view.findViewById(R.id.etSlippage)
        etPoll = view.findViewById(R.id.etPoll)
        switchTopUp = view.findViewById(R.id.switchTopUp)
        etTopUpMinGain = view.findViewById(R.id.etTopUpMinGain)
        etTopUpGainStep = view.findViewById(R.id.etTopUpGainStep)
        etTopUpMaxCount = view.findViewById(R.id.etTopUpMaxCount)
        etTopUpMaxSol = view.findViewById(R.id.etTopUpMaxSol)
        etRpc = view.findViewById(R.id.etRpc)
        etHeliusKey = view.findViewById(R.id.etHeliusKey)
        etBirdeyeKey = view.findViewById(R.id.etBirdeyeKey)
        etGroqKey = view.findViewById(R.id.etGroqKey)
        etGeminiKey = view.findViewById(R.id.etGeminiKey)
        etElevenLabsKey = view.findViewById(R.id.etElevenLabsKey)
        etJupiterKey = view.findViewById(R.id.etJupiterKey)
        etTgBotToken = view.findViewById(R.id.etTgBotToken)
        etTgChatId = view.findViewById(R.id.etTgChatId)
        switchNotifications = view.findViewById(R.id.switchNotifications)
        switchSounds = view.findViewById(R.id.switchSounds)
        switchDarkMode = view.findViewById(R.id.switchDarkMode)
        // V5.9.814 — AI scoring mode (CLASSIC / MODERN / UNIFIED 44-layer)
        spScoringMode     = view.findViewById(R.id.spScoringMode)
        tvScoringModeHint = view.findViewById(R.id.tvScoringModeHint)
        run {
            val opts = arrayOf("CLASSIC (20-layer build ~1920)", "MODERN (V5.9.325 outer-ring)", "UNIFIED (44-layer V5.9.813)")
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, opts)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spScoringMode.adapter = adapter
            spScoringMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    tvScoringModeHint.text = when (position) {
                        0 -> "CLASSIC — 20 inner layers, symmetric accuracy weighting, polarity self-heal. Production default."
                        1 -> "MODERN — V5.9.325 outer-ring + TrustNet + MuteBoost. Historical over-penalising; not recommended."
                        else -> "UNIFIED — 44 layers, CLASSIC fixes + V5.9.325 outer ring with softened caps. Operator opt-in (V5.9.813)."
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        etWatchlist = view.findViewById(R.id.etWatchlist)
        
        // V5.7.6: Trading Mode
        spTradingMode = view.findViewById(R.id.spTradingMode)
        switchMemeTrader = view.findViewById(R.id.switchMemeTrader)
        switchMarketsTrader = view.findViewById(R.id.switchMarketsTrader)
        switchCryptoAltsTrader = view.findViewById(R.id.switchCryptoAltsTrader)
        // V5.7.7: Individual Markets sub-trader switches
        switchPerpsEnabled = view.findViewById(R.id.switchPerpsEnabled)
        switchStocksEnabled = view.findViewById(R.id.switchStocksEnabled)
        switchCommoditiesEnabled = view.findViewById(R.id.switchCommoditiesEnabled)
        switchMetalsEnabled = view.findViewById(R.id.switchMetalsEnabled)
        switchForexEnabled = view.findViewById(R.id.switchForexEnabled)
    }
    
    private fun setupSpinners() {
        val ctx = requireContext()
        
        // Mode spinner
        val modes = arrayOf("PAPER", "LIVE")
        spMode.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, modes)
        
        // Auto trade spinner - Full auto is the default and only real option
        // Bot is designed to be fully autonomous - buy AND sell
        val autoTrades = arrayOf("OFF", "FULL_AUTO")
        spAutoTrade.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, autoTrades)
        
        // V5.7.6: Trading Mode spinner
        val tradingModes = arrayOf("🎰 MEME ONLY", "📊 MARKETS ONLY", "🚀 BOTH (Recommended)")
        spTradingMode.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, tradingModes)
        
        // Sync trading mode spinner with individual switches
        spTradingMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    0 -> { // MEME ONLY
                        switchMemeTrader.isChecked = true
                        switchMarketsTrader.isChecked = false
                    }
                    1 -> { // MARKETS ONLY
                        switchMemeTrader.isChecked = false
                        switchMarketsTrader.isChecked = true
                    }
                    2 -> { // BOTH
                        switchMemeTrader.isChecked = true
                        switchMarketsTrader.isChecked = true
                    }
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        
        // Also sync switches back to spinner
        switchMemeTrader.setOnCheckedChangeListener { _, _ -> syncTradingModeSpinner() }
        switchMarketsTrader.setOnCheckedChangeListener { _, isChecked ->
            syncTradingModeSpinner()
            // Show/hide sub-trader panel based on Markets master switch
            val subPanel = view?.findViewById<android.view.View>(R.id.layoutMarketsSubTraders)
            subPanel?.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
        }
        switchCryptoAltsTrader.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!com.lifecyclebot.perps.CryptoAltTrader.isRunning()) {
                    com.lifecyclebot.perps.CryptoAltTrader.init(requireContext())
                    com.lifecyclebot.perps.CryptoAltTrader.start()
                }
            } else { com.lifecyclebot.perps.CryptoAltTrader.stop() }
        }
        // V5.7.7: Sub-trader toggles — apply immediately to live traders
        switchPerpsEnabled.setOnCheckedChangeListener { _, isChecked ->
            com.lifecyclebot.perps.PerpsTraderAI.setEnabled(isChecked)
        }
        switchStocksEnabled.setOnCheckedChangeListener { _, isChecked ->
            com.lifecyclebot.perps.TokenizedStockTrader.setEnabled(isChecked)
        }
        switchCommoditiesEnabled.setOnCheckedChangeListener { _, isChecked ->
            com.lifecyclebot.perps.CommoditiesTrader.setEnabled(isChecked)
        }
        switchMetalsEnabled.setOnCheckedChangeListener { _, isChecked ->
            com.lifecyclebot.perps.MetalsTrader.setEnabled(isChecked)
        }
        switchForexEnabled.setOnCheckedChangeListener { _, isChecked ->
            com.lifecyclebot.perps.ForexTrader.setEnabled(isChecked)
        }
    }
    
    private fun syncTradingModeSpinner() {
        val meme = switchMemeTrader.isChecked
        val markets = switchMarketsTrader.isChecked
        val position = when {
            meme && markets -> 2  // BOTH
            meme -> 0            // MEME ONLY
            markets -> 1         // MARKETS ONLY
            else -> 2            // Default to BOTH if neither selected
        }
        if (spTradingMode.selectedItemPosition != position) {
            spTradingMode.setSelection(position)
        }
    }
    
    private fun setupClickListeners(view: View) {
        // Close button
        view.findViewById<ImageButton>(R.id.btnCloseSettings)?.setOnClickListener {
            dismiss()
        }
        
        // Save button
        view.findViewById<Button>(R.id.btnSave)?.setOnClickListener {
            saveSettings()
        }
        
        // Export/Import
        view.findViewById<Button>(R.id.btnExportData)?.setOnClickListener {
            onExportRequested?.invoke()
            dismiss()
        }
        
        view.findViewById<Button>(R.id.btnImportData)?.setOnClickListener {
            onImportRequested?.invoke()
            dismiss()
        }
        
        // Clear API Keys
        view.findViewById<Button>(R.id.btnClearSettings)?.setOnClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Clear API Keys")
                .setMessage("This will remove all your API keys. Are you sure?")
                .setPositiveButton("Clear") { _, _ ->
                    onClearApiKeys?.invoke()
                    dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        
        // Test Toast
        view.findViewById<Button>(R.id.btnTestToast)?.setOnClickListener {
            onTestToast?.invoke()
        }
    }
    
    private fun setupApiKeyLinks(view: View) {
        // Make API key labels clickable to open signup pages
        view.findViewById<TextView>(R.id.tvHeliusHelp)?.setOnClickListener {
            openUrl("https://helius.dev/signup")
        }
        view.findViewById<TextView>(R.id.tvBirdeyeHelp)?.setOnClickListener {
            openUrl("https://birdeye.so")
        }
        view.findViewById<TextView>(R.id.tvGroqHelp)?.setOnClickListener {
            openUrl("https://console.groq.com")
        }
        view.findViewById<TextView>(R.id.tvGeminiHelp)?.setOnClickListener {
            openUrl("https://aistudio.google.com")
        }
        view.findViewById<TextView>(R.id.tvJupiterHelp)?.setOnClickListener {
            openUrl("https://portal.jup.ag")
        }
    }
    
    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to open URL: $url")
        }
    }
    
    private fun populateFromConfig() {
        val cfg = currentConfig ?: return
        
        // V5.9.663 — no etActiveToken on this sheet anymore.
        
        // Mode spinner (0=PAPER, 1=LIVE)
        spMode.setSelection(if (cfg.paperMode) 0 else 1)
        
        // Auto trade spinner (0=OFF, 1=FULL_AUTO)
        spAutoTrade.setSelection(if (cfg.autoTrade) 1 else 0)
        
        // V5.7.6: Trading Mode
        spTradingMode.setSelection(cfg.tradingMode.coerceIn(0, 2))
        switchMemeTrader.isChecked = cfg.memeTraderEnabled
        switchMarketsTrader.isChecked = cfg.marketsTraderEnabled
        switchCryptoAltsTrader.isChecked = cfg.cryptoAltsEnabled
        // V5.7.7: Sub-trader switches
        switchPerpsEnabled.isChecked = cfg.perpsEnabled
        switchStocksEnabled.isChecked = cfg.stocksEnabled
        switchCommoditiesEnabled.isChecked = cfg.commoditiesEnabled
        switchMetalsEnabled.isChecked = cfg.metalsEnabled
        switchForexEnabled.isChecked = cfg.forexEnabled
        // Show/hide sub-trader panel
        view?.findViewById<android.view.View>(R.id.layoutMarketsSubTraders)
            ?.visibility = if (cfg.marketsTraderEnabled) android.view.View.VISIBLE else android.view.View.GONE
        
        etStopLoss.setText(cfg.stopLossPct.toString())
        etExitScore.setText(cfg.exitScoreThreshold.toString())
        etSmallBuy.setText(cfg.smallBuySol.toString())
        etLargeBuy.setText(cfg.largeBuySol.toString())
        etSlippage.setText(cfg.slippageBps.toString())
        etPoll.setText(cfg.pollSeconds.toString())
        
        // Top-up
        switchTopUp.isChecked = cfg.topUpEnabled
        etTopUpMinGain.setText(cfg.topUpMinGainPct.toString())
        etTopUpGainStep.setText(cfg.topUpGainStepPct.toString())
        etTopUpMaxCount.setText(cfg.topUpMaxCount.toString())
        etTopUpMaxSol.setText(cfg.topUpMaxTotalSol.toString())
        
        // API Keys
        etRpc.setText(cfg.rpcUrl)
        etHeliusKey.setText(cfg.heliusApiKey)
        etBirdeyeKey.setText(cfg.birdeyeApiKey)
        etGroqKey.setText(cfg.groqApiKey)
        etGeminiKey.setText(cfg.geminiApiKey)
        // V5.9.361 — load ElevenLabs key directly from VoiceManager (lives in
        // VoiceManager prefs, not main Config, so it doesn't pollute the
        // bot config schema).
        try {
            etElevenLabsKey.setText(com.lifecyclebot.engine.VoiceManager.getElevenLabsKey(requireContext()))
        } catch (_: Exception) {}
        etJupiterKey.setText(cfg.jupiterApiKey)
        
        // Telegram
        etTgBotToken.setText(cfg.telegramBotToken)
        etTgChatId.setText(cfg.telegramChatId)
        
        // Toggles
        switchNotifications.isChecked = cfg.notificationsEnabled
        switchSounds.isChecked = cfg.soundEnabled
        switchDarkMode.isChecked = cfg.darkModeEnabled
        // V5.9.814 — scoring mode: 0=CLASSIC, 1=MODERN, 2=UNIFIED (precedence: unified > classic > modern)
        spScoringMode.setSelection(when {
            cfg.unifiedScoringMode -> 2
            cfg.classicScoringMode -> 0
            else                   -> 1
        })
        
        // Watchlist
        etWatchlist.setText(cfg.watchlist.joinToString(", "))
    }
    
    private fun saveSettings() {
        val cfg = currentConfig ?: return
        
        val newConfig = cfg.copy(
            // V5.9.663 — activeToken preserved as-is from existing config;
            // edits happen via the etActiveToken field on activity_main.xml.
            activeToken = cfg.activeToken,
            paperMode = spMode.selectedItemPosition == 0,  // 0=PAPER, 1=LIVE
            autoTrade = spAutoTrade.selectedItemPosition == 1,  // 0=OFF, 1=ON
            // V5.7.6: Trading Mode
            tradingMode = spTradingMode.selectedItemPosition,
            memeTraderEnabled = switchMemeTrader.isChecked,
            marketsTraderEnabled = switchMarketsTrader.isChecked,
            cryptoAltsEnabled = switchCryptoAltsTrader.isChecked,
            // V5.7.7: Individual Markets sub-trader toggles
            perpsEnabled = switchPerpsEnabled.isChecked,
            stocksEnabled = switchStocksEnabled.isChecked,
            commoditiesEnabled = switchCommoditiesEnabled.isChecked,
            metalsEnabled = switchMetalsEnabled.isChecked,
            forexEnabled = switchForexEnabled.isChecked,
            stopLossPct = etStopLoss.text.toString().toDoubleOrNull() ?: cfg.stopLossPct,
            exitScoreThreshold = etExitScore.text.toString().toDoubleOrNull() ?: cfg.exitScoreThreshold,
            smallBuySol = etSmallBuy.text.toString().toDoubleOrNull() ?: cfg.smallBuySol,
            largeBuySol = etLargeBuy.text.toString().toDoubleOrNull() ?: cfg.largeBuySol,
            slippageBps = etSlippage.text.toString().toIntOrNull() ?: cfg.slippageBps,
            pollSeconds = etPoll.text.toString().toIntOrNull() ?: cfg.pollSeconds,
            topUpEnabled = switchTopUp.isChecked,
            topUpMinGainPct = etTopUpMinGain.text.toString().toDoubleOrNull() ?: cfg.topUpMinGainPct,
            topUpGainStepPct = etTopUpGainStep.text.toString().toDoubleOrNull() ?: cfg.topUpGainStepPct,
            topUpMaxCount = etTopUpMaxCount.text.toString().toIntOrNull() ?: cfg.topUpMaxCount,
            topUpMaxTotalSol = etTopUpMaxSol.text.toString().toDoubleOrNull() ?: cfg.topUpMaxTotalSol,
            rpcUrl = etRpc.text.toString().trim().ifBlank { "https://api.mainnet-beta.solana.com" },
            heliusApiKey = etHeliusKey.text.toString().trim(),
            birdeyeApiKey = etBirdeyeKey.text.toString().trim(),
            groqApiKey = etGroqKey.text.toString().trim(),
            geminiApiKey = etGeminiKey.text.toString().trim(),
            jupiterApiKey = etJupiterKey.text.toString().trim(),
            telegramBotToken = etTgBotToken.text.toString().trim(),
            telegramChatId = etTgChatId.text.toString().trim(),
            notificationsEnabled = switchNotifications.isChecked,
            soundEnabled = switchSounds.isChecked,
            darkModeEnabled = switchDarkMode.isChecked,
            // V5.9.814 — scoring mode from 3-way spinner. UNIFIED takes precedence in score() router
            // so when UNIFIED selected, set unifiedScoringMode=true and classicScoringMode irrelevant.
            // When CLASSIC selected, classicScoringMode=true + unifiedScoringMode=false.
            // When MODERN selected, both false.
            classicScoringMode = (spScoringMode.selectedItemPosition == 0),
            unifiedScoringMode = (spScoringMode.selectedItemPosition == 2),
            watchlist = etWatchlist.text.toString()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        )
        
        onSettingsSaved?.invoke(newConfig)
        // V5.9.361 — persist ElevenLabs key (lives in VoiceManager prefs).
        try {
            com.lifecyclebot.engine.VoiceManager.setElevenLabsKey(
                requireContext(),
                etElevenLabsKey.text.toString().trim(),
            )
        } catch (_: Exception) {}
        Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show()
        ErrorLogger.info(TAG, "Settings saved via bottom sheet")
        dismiss()
    }
}

