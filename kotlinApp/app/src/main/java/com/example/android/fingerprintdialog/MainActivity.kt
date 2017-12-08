/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.example.android.fingerprintdialog

import android.app.KeyguardManager
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.fingerprint.FingerprintManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProperties.BLOCK_MODE_CBC
import android.security.keystore.KeyProperties.ENCRYPTION_PADDING_PKCS7
import android.security.keystore.KeyProperties.KEY_ALGORITHM_AES
import android.support.v7.app.AppCompatActivity
import android.util.Base64
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.UnrecoverableKeyException
import java.security.cert.CertificateException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.NoSuchPaddingException
import javax.crypto.SecretKey

/**
 * Main entry point for the sample, showing a backpack and "Purchase" button.
 */
class MainActivity : AppCompatActivity(),
    FingerprintAuthenticationDialogFragment.Callback {

    private lateinit var keyStore: KeyStore
    private lateinit var keyGenerator: KeyGenerator
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        setupKeyStoreAndKeyGenerator()

        val (defaultCipher: Cipher, cipherNotInvalidated: Cipher) = setupCiphers()
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        setUpPurchaseButtons(cipherNotInvalidated, defaultCipher)
    }

    /**
     * Enables or disables purchase buttons and sets the appropriate click listeners.
     *
     * @param cipherNotInvalidated cipher for the not invalidated purchase button
     * @param defaultCipher the default cipher, used for the purchase button
     */
    private fun setUpPurchaseButtons(cipherNotInvalidated: Cipher, defaultCipher: Cipher) {
        val purchaseButton = findViewById<Button>(R.id.purchase_button)
        val purchaseButtonNotInvalidated =
                findViewById<Button>(R.id.purchase_button_not_invalidated)

        purchaseButtonNotInvalidated.run {
            isEnabled = true
            setOnClickListener(PurchaseButtonClickListener(
                    cipherNotInvalidated, KEY_NAME_NOT_INVALIDATED))
        }

        val keyguardManager = getSystemService(KeyguardManager::class.java)
        if (!keyguardManager.isKeyguardSecure) {
            // Show a message that the user hasn't set up a fingerprint or lock screen.
            showToast(getString(R.string.setup_lock_screen))
            purchaseButton.isEnabled = false
            purchaseButtonNotInvalidated.isEnabled = false
            return
        }

        val fingerprintManager = getSystemService(FingerprintManager::class.java)
        if (!fingerprintManager.hasEnrolledFingerprints()) {
            purchaseButton.isEnabled = false
            // This happens when no fingerprints are registered.
            showToast(getString(R.string.register_fingerprint))
            return
        }

        createKey(DEFAULT_KEY_NAME)
        createKey(KEY_NAME_NOT_INVALIDATED, false)
        purchaseButton.run {
            isEnabled = true
            setOnClickListener(PurchaseButtonClickListener(defaultCipher, DEFAULT_KEY_NAME))
        }
    }

    /**
     * Sets up KeyStore and KeyGenerator
     */
    private fun setupKeyStoreAndKeyGenerator() {
        try {
            keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
        } catch (e: KeyStoreException) {
            throw RuntimeException("Failed to get an instance of KeyStore", e)
        }

        try {
            keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        } catch (e: Exception) {
            when (e) {
                is NoSuchAlgorithmException,
                is NoSuchProviderException ->
                    throw RuntimeException("Failed to get an instance of KeyGenerator", e)
                else -> throw e
            }
        }
    }

    /**
     * Sets up default cipher and a non-invalidated cipher
     */
    private fun setupCiphers(): Pair<Cipher, Cipher> {
        val defaultCipher: Cipher
        val cipherNotInvalidated: Cipher
        try {
            val cipherString = "$KEY_ALGORITHM_AES/$BLOCK_MODE_CBC/$ENCRYPTION_PADDING_PKCS7"
            defaultCipher = Cipher.getInstance(cipherString)
            cipherNotInvalidated = Cipher.getInstance(cipherString)
        } catch (e: Exception) {
            when (e) {
                is NoSuchAlgorithmException,
                is NoSuchPaddingException ->
                    throw RuntimeException("Failed to get an instance of Cipher", e)
                else -> throw e
            }
        }
        return Pair(defaultCipher, cipherNotInvalidated)
    }

    /**
     * Initialize the [Cipher] instance with the created key in the [createKey] method.
     *
     * @param keyName the key name to init the cipher
     * @return `true` if initialization succeeded, `false` if the lock screen has been disabled or
     * reset after key generation, or if a fingerprint was enrolled after key generation.
     */
    private fun initCipher(cipher: Cipher, keyName: String): Boolean {
        try {
            keyStore.load(null)
            cipher.init(Cipher.ENCRYPT_MODE, keyStore.getKey(keyName, null) as SecretKey)
            return true
        } catch (e: Exception) {
            when (e) {
                is KeyPermanentlyInvalidatedException -> return false
                is KeyStoreException,
                is CertificateException,
                is UnrecoverableKeyException,
                is IOException,
                is NoSuchAlgorithmException,
                is InvalidKeyException -> throw RuntimeException("Failed to init Cipher", e)
                else -> throw e
            }
        }
    }

    /**
     * Proceed with the purchase operation
     *
     * @param withFingerprint `true` if the purchase was made by using a fingerprint
     * @param crypto the Crypto object
     */
    override fun onPurchased(withFingerprint: Boolean, crypto: FingerprintManager.CryptoObject?) {
        if (withFingerprint) {
            // If the user authenticated with fingerprint, verify using cryptography and then show
            // the confirmation message.
            if (crypto != null) {
                tryEncrypt(crypto.cipher)
            }
        } else {
            // Authentication happened with backup password. Just show the confirmation message.
            showConfirmation()
        }
    }

    // Show confirmation message. Also show crypto information if fingerprint was used.
    private fun showConfirmation(encrypted: ByteArray? = null) {
        findViewById<View>(R.id.confirmation_message).visibility = View.VISIBLE
        if (encrypted != null) {
            findViewById<TextView>(R.id.encrypted_message).run {
                visibility = View.VISIBLE
                text = Base64.encodeToString(encrypted, 0 /* flags */)
            }
        }
    }

    /**
     * Tries to encrypt some data with the generated key from [createKey]. This only works if the
     * user just authenticated via fingerprint.
     */
    private fun tryEncrypt(cipher: Cipher) {
        try {
            showConfirmation(cipher.doFinal(SECRET_MESSAGE.toByteArray()))
        } catch (e: Exception) {
            when (e) {
                is BadPaddingException,
                is IllegalBlockSizeException -> {
                    Toast.makeText(this, "Failed to encrypt the data with the generated key. "
                            + "Retry the purchase", Toast.LENGTH_LONG).show()
                    Log.e(TAG, "Failed to encrypt the data with the generated key. ${e.message}")
                }
                else -> throw e
            }
        }
    }

    /**
     * Creates a symmetric key in the Android Key Store which can only be used after the user has
     * authenticated with a fingerprint.
     *
     * @param keyName the name of the key to be created
     * @param invalidatedByBiometricEnrollment if `false` is passed, the created key will not be
     * invalidated even if a new fingerprint is enrolled. The default value is `true` - the key will
     * be invalidated if a new fingerprint is enrolled.
     */
    override fun createKey(keyName: String, invalidatedByBiometricEnrollment: Boolean) {
        // The enrolling flow for fingerprint. This is where you ask the user to set up fingerprint
        // for your flow. Use of keys is necessary if you need to know if the set of enrolled
        // fingerprints has changed.
        try {
            keyStore.load(null)

            val keyProperties = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            val builder = KeyGenParameterSpec.Builder(keyName, keyProperties)
                    .setBlockModes(BLOCK_MODE_CBC)
                    .setUserAuthenticationRequired(true)
                    .setEncryptionPaddings(ENCRYPTION_PADDING_PKCS7)
                    .setInvalidatedByBiometricEnrollment(invalidatedByBiometricEnrollment)

            keyGenerator.run {
                init(builder.build())
                generateKey()
            }
        } catch (e: Exception) {
            when (e) {
                is NoSuchAlgorithmException,
                is InvalidAlgorithmParameterException,
                is CertificateException,
                is IOException -> throw RuntimeException(e)
                else -> throw e
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private inner class PurchaseButtonClickListener internal constructor(
            internal var cipher: Cipher,
            internal var keyName: String
    ) : View.OnClickListener {

        override fun onClick(view: View) {
            findViewById<View>(R.id.confirmation_message).visibility = View.GONE
            findViewById<View>(R.id.encrypted_message).visibility = View.GONE

            val fragment = FingerprintAuthenticationDialogFragment()
            fragment.setCryptoObject(FingerprintManager.CryptoObject(cipher))
            fragment.setCallback(this@MainActivity)

            // Set up the crypto object for later, which will be authenticated by fingerprint usage.
            if (initCipher(cipher, keyName)) {

                // Show the fingerprint dialog. The user has the option to use the fingerprint with
                // crypto, or can fall back to using a server-side verified password.
                val useFingerprintPreference = sharedPreferences
                        .getBoolean(getString(R.string.use_fingerprint_to_authenticate_key), true)
                if (useFingerprintPreference) {
                    fragment.setStage(Stage.FINGERPRINT)
                } else {
                    fragment.setStage(Stage.PASSWORD)
                }
            } else {
                // This happens if the lock screen has been disabled or or a fingerprint was
                // enrolled. Thus, show the dialog to authenticate with their password first and ask
                // the user if they want to authenticate with a fingerprint in the future.
                fragment.setStage(Stage.NEW_FINGERPRINT_ENROLLED)
            }
            fragment.show(fragmentManager, DIALOG_FRAGMENT_TAG)
        }
    }

    companion object {
        private val ANDROID_KEY_STORE = "AndroidKeyStore"
        private val DIALOG_FRAGMENT_TAG = "myFragment"
        private val KEY_NAME_NOT_INVALIDATED = "key_not_invalidated"
        private val SECRET_MESSAGE = "Very secret message"
        private val TAG = MainActivity::class.java.simpleName
    }
}
