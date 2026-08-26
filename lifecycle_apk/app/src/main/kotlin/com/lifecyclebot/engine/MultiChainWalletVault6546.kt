package com.lifecyclebot.engine

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** V5.0.6546 — encrypted persistence for one multi-chain recovery wallet. */
object MultiChainWalletVault6546 {
    private const val PREFS = "aate_multichain_wallet_v6546"
    private const val MNEMONIC = "mnemonic"
    private const val SOLANA = "solana_address"
    private const val ETHEREUM = "ethereum_address"
    private const val BSC = "bsc_address"
    private const val BITCOIN = "bitcoin_address"
    private const val SOLANA_PRIVATE = "solana_private_key_b58"

    data class StoredWallet(
        val mnemonic: String,
        val solanaAddress: String,
        val ethereumAddress: String,
        val bscAddress: String,
        val bitcoinAddress: String,
        val solanaPrivateKeyB58: String,
    )

    private fun prefs(context: Context): android.content.SharedPreferences {
        val master = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS,
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun save(context: Context, wallet: MultiChainWalletGenerator6546.GeneratedWallet) {
        require(wallet.mnemonic.isNotBlank()) { "MULTICHAIN_MNEMONIC_EMPTY" }
        require(wallet.solanaAddress.isNotBlank() && wallet.ethereumAddress.isNotBlank() &&
            wallet.bscAddress.isNotBlank() && wallet.bitcoinAddress.isNotBlank()) {
            "MULTICHAIN_PUBLIC_ADDRESS_EMPTY"
        }
        prefs(context).edit()
            .putString(MNEMONIC, wallet.mnemonic)
            .putString(SOLANA, wallet.solanaAddress)
            .putString(ETHEREUM, wallet.ethereumAddress)
            .putString(BSC, wallet.bscAddress)
            .putString(BITCOIN, wallet.bitcoinAddress)
            .putString(SOLANA_PRIVATE, wallet.solanaPrivateKeyB58)
            .commit()
            .also { check(it) { "MULTICHAIN_VAULT_WRITE_FAILED" } }
    }

    fun load(context: Context): StoredWallet? {
        val p = prefs(context)
        val values = listOf(
            p.getString(MNEMONIC, null), p.getString(SOLANA, null),
            p.getString(ETHEREUM, null), p.getString(BSC, null),
            p.getString(BITCOIN, null), p.getString(SOLANA_PRIVATE, null),
        )
        if (values.any { it.isNullOrBlank() }) return null
        return StoredWallet(
            mnemonic = values[0]!!,
            solanaAddress = values[1]!!,
            ethereumAddress = values[2]!!,
            bscAddress = values[3]!!,
            bitcoinAddress = values[4]!!,
            solanaPrivateKeyB58 = values[5]!!,
        )
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }
}
