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

    // Callbacks
    var onSettingsSaved: ((BotConfig) -> Unit)? = null
    var onExportRequested: (() -> Unit)? = null
    var onImportRequested: (() -> Unit)? = null
    var onClearApiKeys: (() -> Unit)? = null
    var onTestToast: (() -> Unit)? = null

    // Current config
    private var currentConfig: BotConfig? = null

    // Views — only those that actually exist in dialog_settings.xml
    private lateinit var etActiveToken: EditText
    private lateinit var spMode: Spinner
    private lateinit var spStrategy: Spinner        // autoTrade ON/OFF
    private lateinit var etBuySol: EditText         // smallBuySol / largeBuySol
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
    private lateinit var etJupiterKey: EditText
    private lateinit var etTgBotToken: EditText
    private lateinit var etTgChatId: EditText
    private lateinit var switchNotifications: SwitchCompat
    private lateinit var switchVibration: SwitchCompat
    private lateinit var switchSounds: SwitchCompat
    private lateinit var switchDarkMode: SwitchCompat
    private lateinit var etWatchlist: EditText
    private lateinit var spTradingMode: Spinner
    private lateinit var switchMemeTrader: SwitchCompat
    private lateinit var switchMarketsTrader: SwitchCompat

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
        try {
            // Make bottom sheet expanded by default
            dialog?.setOnShowListener {
                val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                bottomSheet?.let {
                    val behavior = BottomSheetBehavior.from(it)
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                    behavior.skipCollapsed = true
                    it.layoutParams.height = (resources.displayMetrics.heightPixels * 0.9).toInt()
                }
            }

            bindViews(view)
            setupSpinners()
            setupClickListeners(view)
            setupApiKeyLinks(view)
            populateFromConfig()
        } catch (e: Exception) {
            com.lifecyclebot.engine.ErrorLogger.crash("SettingsBottomSheet", "onViewCreated CRASH: ${e.javaClass.simpleName}: ${e.message}", e)
            android.widget.Toast.makeText(requireContext(), "Settings failed to load: ${e.message?.take(80)}", android.widget.Toast.LENGTH_LONG).show()
            dismissAllowingStateLoss()
        }
    }

    private fun bindViews(view: View) {
        etActiveToken       = view.findViewById(R.id.etActiveToken)
        spMode              = view.findViewById(R.id.spMode)
        spStrategy          = view.findViewById(R.id.spStrategy)
        etBuySol            = view.findViewById(R.id.etBuySol)
        etSlippage          = view.findViewById(R.id.etSlippage)
        etPoll              = view.findViewById(R.id.etPoll)
        switchTopUp         = view.findViewById(R.id.switchTopUp)
        etTopUpMinGain      = view.findViewById(R.id.etTopUpMinGain)
        etTopUpGainStep     = view.findViewById(R.id.etTopUpGainStep)
        etTopUpMaxCount     = view.findViewById(R.id.etTopUpMaxCount)
        etTopUpMaxSol       = view.findViewById(R.id.etTopUpMaxSol)
        etRpc               = view.findViewById(R.id.etRpc)
        etHeliusKey         = view.findViewById(R.id.etHeliusKey)
        etBirdeyeKey        = view.findViewById(R.id.etBirdeyeKey)
        etGroqKey           = view.findViewById(R.id.etGroqKey)
        etGeminiKey         = view.findViewById(R.id.etGeminiKey)
        etJupiterKey        = view.findViewById(R.id.etJupiterKey)
        etTgBotToken        = view.findViewById(R.id.etTgBotToken)
        etTgChatId          = view.findViewById(R.id.etTgChatId)
        switchNotifications = view.findViewById(R.id.switchNotifications)
        switchVibration     = view.findViewById(R.id.switchVibration)
        switchSounds        = view.findViewById(R.id.switchSounds)
        switchDarkMode      = view.findViewById(R.id.switchDarkMode)
        etWatchlist         = view.findViewById(R.id.etWatchlist)
        spTradingMode       = view.findViewById(R.id.spTradingMode)
        switchMemeTrader    = view.findViewById(R.id.switchMemeTrader)
        switchMarketsTrader = view.findViewById(R.id.switchMarketsTrader)
    }

    private fun setupSpinners() {
        val ctx = requireContext()

        // Mode spinner (0=PAPER, 1=LIVE)
        val modes = arrayOf("PAPER", "LIVE")
        spMode.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, modes)

        // Strategy/Auto-trade spinner (0=OFF, 1=FULL_AUTO)
        val strategies = arrayOf("OFF", "FULL_AUTO")
        spStrategy.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, strategies)

        // Trading Mode spinner
        val tradingModes = arrayOf("🎰 MEME ONLY", "📊 MARKETS ONLY", "🚀 BOTH (Recommended)")
        spTradingMode.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, tradingModes)

        // Sync trading mode spinner with individual switches
        spTradingMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    0 -> { switchMemeTrader.isChecked = true;  switchMarketsTrader.isChecked = false }
                    1 -> { switchMemeTrader.isChecked = false; switchMarketsTrader.isChecked = true  }
                    2 -> { switchMemeTrader.isChecked = true;  switchMarketsTrader.isChecked = true  }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Also sync switches back to spinner
        switchMemeTrader.setOnCheckedChangeListener { _, _ -> syncTradingModeSpinner() }
        switchMarketsTrader.setOnCheckedChangeListener { _, _ -> syncTradingModeSpinner() }
    }

    private fun syncTradingModeSpinner() {
        val meme    = switchMemeTrader.isChecked
        val markets = switchMarketsTrader.isChecked
        val position = when {
            meme && markets -> 2
            meme            -> 0
            markets         -> 1
            else            -> 2
        }
        if (spTradingMode.selectedItemPosition != position) {
            spTradingMode.setSelection(position)
        }
    }

    private fun setupClickListeners(view: View) {
        view.findViewById<View>(R.id.btnCloseSettings)?.setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btnSave)?.setOnClickListener { saveSettings() }
        view.findViewById<View>(R.id.btnExportData)?.setOnClickListener {
            onExportRequested?.invoke(); dismiss()
        }
        view.findViewById<View>(R.id.btnImportData)?.setOnClickListener {
            onImportRequested?.invoke(); dismiss()
        }
        view.findViewById<View>(R.id.btnClearSettings)?.setOnClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Clear API Keys")
                .setMessage("This will remove all your API keys. Are you sure?")
                .setPositiveButton("Clear") { _, _ -> onClearApiKeys?.invoke(); dismiss() }
                .setNegativeButton("Cancel", null)
                .show()
        }
        view.findViewById<View>(R.id.btnTestToast)?.setOnClickListener {
            onTestToast?.invoke()
        }
    }

    private fun setupApiKeyLinks(view: View) {
        view.findViewById<TextView>(R.id.tvHeliusHelp)?.setOnClickListener  { openUrl("https://helius.dev/signup")        }
        view.findViewById<TextView>(R.id.tvBirdeyeHelp)?.setOnClickListener { openUrl("https://birdeye.so")               }
        view.findViewById<TextView>(R.id.tvGroqHelp)?.setOnClickListener    { openUrl("https://console.groq.com")         }
        view.findViewById<TextView>(R.id.tvGeminiHelp)?.setOnClickListener  { openUrl("https://aistudio.google.com")      }
        view.findViewById<TextView>(R.id.tvJupiterHelp)?.setOnClickListener { openUrl("https://portal.jup.ag")            }
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

        etActiveToken.setText(cfg.activeToken)

        // Mode spinner (0=PAPER, 1=LIVE)
        spMode.setSelection(if (cfg.paperMode) 0 else 1)

        // Strategy spinner (0=OFF, 1=FULL_AUTO)
        spStrategy.setSelection(if (cfg.autoTrade) 1 else 0)

        // V5.7.6: Trading Mode
        spTradingMode.setSelection(cfg.tradingMode.coerceIn(0, 2))
        switchMemeTrader.isChecked    = cfg.memeTraderEnabled
        switchMarketsTrader.isChecked = cfg.marketsTraderEnabled

        // Buy size — use smallBuySol as the representative value
        etBuySol.setText(cfg.smallBuySol.toString())

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
        etJupiterKey.setText(cfg.jupiterApiKey)

        // Telegram
        etTgBotToken.setText(cfg.telegramBotToken)
        etTgChatId.setText(cfg.telegramChatId)

        // Toggles
        switchNotifications.isChecked = cfg.notificationsEnabled
        switchVibration.isChecked     = cfg.vibrationEnabled
        switchSounds.isChecked        = cfg.soundEnabled
        switchDarkMode.isChecked      = cfg.darkModeEnabled

        // Watchlist
        etWatchlist.setText(cfg.watchlist.joinToString(", "))
    }

    private fun saveSettings() {
        val cfg = currentConfig ?: return

        val buySol = etBuySol.text.toString().toDoubleOrNull() ?: cfg.smallBuySol

        val newConfig = cfg.copy(
            activeToken           = etActiveToken.text.toString().trim(),
            paperMode             = spMode.selectedItemPosition == 0,
            autoTrade             = spStrategy.selectedItemPosition == 1,
            tradingMode           = spTradingMode.selectedItemPosition,
            memeTraderEnabled     = switchMemeTrader.isChecked,
            marketsTraderEnabled  = switchMarketsTrader.isChecked,
            smallBuySol           = buySol,
            largeBuySol           = (buySol * 2.0),   // auto-scale large buy to 2x small
            slippageBps           = etSlippage.text.toString().toIntOrNull()    ?: cfg.slippageBps,
            pollSeconds           = etPoll.text.toString().toIntOrNull()         ?: cfg.pollSeconds,
            topUpEnabled          = switchTopUp.isChecked,
            topUpMinGainPct       = etTopUpMinGain.text.toString().toDoubleOrNull()  ?: cfg.topUpMinGainPct,
            topUpGainStepPct      = etTopUpGainStep.text.toString().toDoubleOrNull() ?: cfg.topUpGainStepPct,
            topUpMaxCount         = etTopUpMaxCount.text.toString().toIntOrNull()    ?: cfg.topUpMaxCount,
            topUpMaxTotalSol      = etTopUpMaxSol.text.toString().toDoubleOrNull()   ?: cfg.topUpMaxTotalSol,
            rpcUrl                = etRpc.text.toString().trim().ifBlank { "https://api.mainnet-beta.solana.com" },
            heliusApiKey          = etHeliusKey.text.toString().trim(),
            birdeyeApiKey         = etBirdeyeKey.text.toString().trim(),
            groqApiKey            = etGroqKey.text.toString().trim(),
            geminiApiKey          = etGeminiKey.text.toString().trim(),
            jupiterApiKey         = etJupiterKey.text.toString().trim(),
            telegramBotToken      = etTgBotToken.text.toString().trim(),
            telegramChatId        = etTgChatId.text.toString().trim(),
            notificationsEnabled  = switchNotifications.isChecked,
            vibrationEnabled      = switchVibration.isChecked,
            soundEnabled          = switchSounds.isChecked,
            darkModeEnabled       = switchDarkMode.isChecked,
            watchlist             = etWatchlist.text.toString()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        )

        onSettingsSaved?.invoke(newConfig)
        Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show()
        ErrorLogger.info(TAG, "Settings saved via bottom sheet")
        dismiss()
    }
}
