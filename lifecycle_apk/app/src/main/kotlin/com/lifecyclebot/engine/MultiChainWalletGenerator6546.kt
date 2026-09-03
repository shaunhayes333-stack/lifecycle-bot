package com.lifecyclebot.engine

import com.iwebpp.crypto.TweetNaclFast
import io.github.novacrypto.base58.Base58
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.crypto.HDKeyDerivation
import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.crypto.Credentials
import org.web3j.crypto.MnemonicUtils
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * V5.0.6546 — one recovery wallet, chain-specific addresses.
 *
 * A single BIP-39 recovery phrase derives independent keys on each chain:
 * Solana uses SLIP-0010 ed25519, EVM uses BIP-44 secp256k1, and Bitcoin uses
 * BIP-44 secp256k1. Addresses are never copied between chains.
 *
 * This class does not persist secrets and does not enable live execution by
 * itself. Callers must put the phrase in the app's encrypted vault and only
 * expose public addresses to deposit UI.
 */
object MultiChainWalletGenerator6546 {
    private const val HARDENED = 0x80000000.toInt()
    private const val SOLANA_PATH_ACCOUNT = 0
    private const val EVM_PATH_ACCOUNT = 0
    private const val BITCOIN_PATH_ACCOUNT = 0

    data class GeneratedWallet(
        val mnemonic: String,
        val solanaAddress: String,
        val ethereumAddress: String,
        val bscAddress: String,
        val bitcoinAddress: String,
        /** Base58 Solana secret required by the existing SolanaWallet. */
        val solanaPrivateKeyB58: String,
    )

    fun generate(entropy: ByteArray? = null, passphrase: String = ""): GeneratedWallet {
        val entropyBytes = entropy?.copyOf() ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
        require(entropyBytes.size == 16 || entropyBytes.size == 20 || entropyBytes.size == 24 || entropyBytes.size == 28 || entropyBytes.size == 32) {
            "BIP39 entropy must be 128/160/192/224/256 bits"
        }
        val mnemonic = MnemonicUtils.generateMnemonic(entropyBytes)
        return fromMnemonic(mnemonic, passphrase)
    }

    fun fromMnemonic(mnemonic: String, passphrase: String = ""): GeneratedWallet {
        require(MnemonicUtils.validateMnemonic(mnemonic)) { "INVALID_BIP39_MNEMONIC" }
        val seed = MnemonicUtils.generateSeed(mnemonic, passphrase)
        try {
            val evmMaster = Bip32ECKeyPair.generateKeyPair(seed)
            val evmPath = intArrayOf(
                44 or HARDENED, 60 or HARDENED, EVM_PATH_ACCOUNT or HARDENED, 0, 0
            )
            val evm = Credentials.create(Bip32ECKeyPair.deriveKeyPair(evmMaster, evmPath)).address

            val btcMaster = HDKeyDerivation.createMasterPrivateKey(seed)
            val btcKey = derive(btcMaster, intArrayOf(
                44 or HARDENED, 0 or HARDENED, BITCOIN_PATH_ACCOUNT or HARDENED, 0, 0
            ))
            val btc = LegacyAddress.fromKey(MainNetParams.get(), btcKey).toString()

            val solSeed = slip10Ed25519Seed(seed)
            val solKeyPair = TweetNaclFast.Signature.keyPair_fromSeed(solSeed)
            val solSecret = solKeyPair.secretKey.copyOf()
            val solPublic = Base58.base58Encode(solKeyPair.publicKey)
            val solPrivate = Base58.base58Encode(solSecret)
            solSecret.fill(0)
            solSeed.fill(0)

            return GeneratedWallet(
                mnemonic = mnemonic.trim().replace(Regex("\\s+"), " "),
                solanaAddress = solPublic,
                ethereumAddress = evm,
                bscAddress = evm,
                bitcoinAddress = btc,
                solanaPrivateKeyB58 = solPrivate,
            )
        } finally {
            seed.fill(0)
        }
    }

    /** Derive the EVM signer on demand from the encrypted recovery phrase.
     * No additional private-key projection is persisted. */
    fun evmCredentialsFromMnemonic6649(mnemonic: String, passphrase: String = ""): Credentials {
        require(MnemonicUtils.validateMnemonic(mnemonic)) { "INVALID_BIP39_MNEMONIC" }
        val seed = MnemonicUtils.generateSeed(mnemonic, passphrase)
        return try {
            val master = Bip32ECKeyPair.generateKeyPair(seed)
            Credentials.create(Bip32ECKeyPair.deriveKeyPair(master, intArrayOf(
                44 or HARDENED, 60 or HARDENED, EVM_PATH_ACCOUNT or HARDENED, 0, 0,
            )))
        } finally {
            seed.fill(0)
        }
    }

    private fun derive(master: org.bitcoinj.crypto.DeterministicKey, path: IntArray): org.bitcoinj.crypto.DeterministicKey {
        var current = master
        for (index in path) current = HDKeyDerivation.deriveChildKey(current, index)
        return current
    }

    /** SLIP-0010 ed25519 derivation for m/44'/501'/0'/0'. */
    private fun slip10Ed25519Seed(bip39Seed: ByteArray): ByteArray {
        val masterMac = Mac.getInstance("HmacSHA512")
        masterMac.init(SecretKeySpec("ed25519 seed".toByteArray(StandardCharsets.UTF_8), "HmacSHA512"))
        var material = masterMac.doFinal(bip39Seed)
        var privateKey = material.copyOfRange(0, 32)
        var chainCode = material.copyOfRange(32, 64)
        val path = intArrayOf(44 or HARDENED, 501 or HARDENED, 0 or HARDENED, 0 or HARDENED)
        for (index in path) {
            val input = ByteArray(37)
            input[0] = 0
            privateKey.copyInto(input, 1)
            index.toByteArrayBigEndian().copyInto(input, 33)
            val childMac = Mac.getInstance("HmacSHA512")
            childMac.init(SecretKeySpec(chainCode, "HmacSHA512"))
            material = childMac.doFinal(input)
            privateKey.fill(0)
            chainCode.fill(0)
            privateKey = material.copyOfRange(0, 32)
            chainCode = material.copyOfRange(32, 64)
        }
        chainCode.fill(0)
        material.fill(0)
        return privateKey
    }

    private fun Int.toByteArrayBigEndian(): ByteArray = byteArrayOf(
        (this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), this.toByte()
    )
}
