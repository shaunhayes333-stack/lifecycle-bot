package com.lifecyclebot.engine

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.web3j.crypto.Credentials

/** V5.0.6546 — encrypted persistence for one multi-chain recovery wallet. */
object MultiChainWalletVault6546 {
    private const val PREFS = "aate_multichain_wallet_v6546"
    private const val MNEMONIC = "mnemonic"
    private const val SOLANA = "solana_address"
    private const val ETHEREUM = "ethereum_address"
    private const val BSC = "bsc_address"
    private const val BITCOIN = "bitcoin_address"
    private const val SOLANA_PRIVATE = "solana_private_key_b58"
    private const val BACKUP_CONFIRMED = "backup_confirmed"
    private const val ACTIVE_MAIN = "active_main"

    data class StoredWallet(
        val mnemonic: String,
        val solanaAddress: String,
        val ethereumAddress: String,
        val bscAddress: String,
        val bitcoinAddress: String,
        val solanaPrivateKeyB58: String,
        val backupConfirmed: Boolean,
        val activeMain: Boolean,
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
            .putBoolean(BACKUP_CONFIRMED, false)
            .putBoolean(ACTIVE_MAIN, false)
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
            backupConfirmed = p.getBoolean(BACKUP_CONFIRMED, false),
            activeMain = p.getBoolean(ACTIVE_MAIN, false),
        )
    }

    /** Activation is a separate durable step so generation can never silently
     * replace the funded/main signer. The UI must show the recovery phrase and
     * obtain an explicit backup acknowledgement first. */
    fun confirmBackupAndActivate(context: Context): StoredWallet {
        val staged = load(context) ?: error("MULTICHAIN_WALLET_NOT_STAGED")
        check(prefs(context).edit()
            .putBoolean(BACKUP_CONFIRMED, true)
            .putBoolean(ACTIVE_MAIN, true)
            .commit()) { "MULTICHAIN_ACTIVATION_WRITE_FAILED" }
        return staged.copy(backupConfirmed = true, activeMain = true)
    }

    fun executable(context: Context): StoredWallet? =
        load(context)?.takeIf { it.backupConfirmed && it.activeMain }

    fun evmCredentials6649(context: Context): Credentials? {
        val wallet = executable(context) ?: return null
        val credentials = MultiChainWalletGenerator6546.evmCredentialsFromMnemonic6649(wallet.mnemonic)
        check(credentials.address.equals(wallet.ethereumAddress, ignoreCase = true)) {
            "MULTICHAIN_EVM_SIGNER_MISMATCH"
        }
        return credentials
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }
}
