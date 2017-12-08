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

import android.app.DialogFragment
import android.content.Context
import android.content.SharedPreferences
import android.hardware.fingerprint.FingerprintManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView

/**
 * A dialog which uses fingerprint APIs to authenticate the user, and falls back to password
 * authentication if fingerprint is not available.
 */
class FingerprintAuthenticationDialogFragment : DialogFragment(),
        TextView.OnEditorActionListener,
        FingerprintUiHelper.Callback {

    private lateinit var backupContent: View
    private lateinit var cancelButton: Button
    private lateinit var fingerprintContainer: View
    private lateinit var fingerprintEnrolledTextView: TextView
    private lateinit var passwordDescriptionTextView: TextView
    private lateinit var passwordEditText: EditText
    private lateinit var secondDialogButton: Button
    private lateinit var useFingerprintFutureCheckBox: CheckBox

    private lateinit var callback: Callback
    private lateinit var cryptoObject: FingerprintManager.CryptoObject
    private lateinit var fingerprintUiHelper: FingerprintUiHelper
    private lateinit var inputMethodManager: InputMethodManager
    private lateinit var sharedPreferences: SharedPreferences

    private var stage = Stage.FINGERPRINT

    private val showKeyboardRunnable = Runnable {
        inputMethodManager.showSoftInput(passwordEditText, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Do not create a new Fragment when the Activity is re-created such as orientation changes.
        retainInstance = true
        setStyle(DialogFragment.STYLE_NORMAL, android.R.style.Theme_Material_Light_Dialog)
    }

    override fun onCreateView(inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        dialog.setTitle(getString(R.string.sign_in))
        return inflater.inflate(R.layout.fingerprint_dialog_container, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        backupContent = view.findViewById(R.id.backup_container)
        cancelButton = view.findViewById(R.id.cancel_button)
        fingerprintContainer = view.findViewById(R.id.fingerprint_container)
        fingerprintEnrolledTextView = view.findViewById(R.id.new_fingerprint_enrolled_description)
        passwordDescriptionTextView = view.findViewById(R.id.password_description)
        passwordEditText = view.findViewById(R.id.password)
        secondDialogButton = view.findViewById(R.id.second_dialog_button)
        useFingerprintFutureCheckBox = view.findViewById(R.id.use_fingerprint_in_future_check)

        cancelButton.setOnClickListener { dismiss() }
        passwordEditText.setOnEditorActionListener(this)
        secondDialogButton.setOnClickListener {
            if (stage == Stage.FINGERPRINT) goToBackup() else verifyPassword()
        }

        fingerprintUiHelper = FingerprintUiHelper(
                activity.getSystemService(FingerprintManager::class.java),
                view.findViewById(R.id.fingerprint_icon),
                view.findViewById(R.id.fingerprint_status),
                this
        )
        updateStage()

        // If fingerprint authentication is not available, switch immediately to the backup
        // (password) screen.
        if (!fingerprintUiHelper.isFingerprintAuthAvailable) {
            goToBackup()
        }
    }
    override fun onResume() {
        super.onResume()
        if (stage == Stage.FINGERPRINT) {
            fingerprintUiHelper.startListening(cryptoObject)
        }
    }

    override fun onPause() {
        super.onPause()
        fingerprintUiHelper.stopListening()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        inputMethodManager = context.getSystemService(InputMethodManager::class.java)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    }

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    fun setCryptoObject(cryptoObject: FingerprintManager.CryptoObject) {
        this.cryptoObject = cryptoObject
    }

    fun setStage(stage: Stage) {
        this.stage = stage
    }

    /**
     * Switches to backup (password) screen. This either can happen when fingerprint is not
     * available or the user chooses to use the password authentication method by pressing the
     * button. This can also happen when the user has too many invalid fingerprint attempts.
     */
    private fun goToBackup() {
        stage = Stage.PASSWORD
        updateStage()
        passwordEditText.run {
            requestFocus()

            // Show the keyboard.
            postDelayed(showKeyboardRunnable, 500)
        }

        // Fingerprint is not used anymore. Stop listening for it.
        fingerprintUiHelper.stopListening()
    }

    /**
     * Checks whether the current entered password is correct, and dismisses the dialog and
     * informs the activity about the result.
     */
    private fun verifyPassword() {
        if (!checkPassword(passwordEditText.text.toString())) {
            return
        }
        if (stage == Stage.NEW_FINGERPRINT_ENROLLED) {
            sharedPreferences.edit()
                    .putBoolean(getString(R.string.use_fingerprint_to_authenticate_key),
                            useFingerprintFutureCheckBox.isChecked)
                    .apply()

            if (useFingerprintFutureCheckBox.isChecked) {
                // Re-create the key so that fingerprints including new ones are validated.
                callback.createKey(DEFAULT_KEY_NAME)
                stage = Stage.FINGERPRINT
            }
        }
        passwordEditText.setText("")
        callback.onPurchased(withFingerprint = false)
        dismiss()
    }

    /**
     * Checks if the given password is valid. Assume that the password is always correct.
     * In a real world situation, the password needs to be verified via the server.
     *
     * @param password The password String
     *
     * @return true if `password` is correct, false otherwise
     */
    private fun checkPassword(password: String) = password.isNotEmpty()

    private fun updateStage() {
        when (stage) {
            Stage.FINGERPRINT -> {
                cancelButton.setText(R.string.cancel)
                secondDialogButton.setText(R.string.use_password)
                fingerprintContainer.visibility = View.VISIBLE
                backupContent.visibility = View.GONE
            }
            Stage.NEW_FINGERPRINT_ENROLLED, // Intentional fall through
            Stage.PASSWORD -> {
                cancelButton.setText(R.string.cancel)
                secondDialogButton.setText(R.string.ok)
                fingerprintContainer.visibility = View.GONE
                backupContent.visibility = View.VISIBLE
                if (stage == Stage.NEW_FINGERPRINT_ENROLLED) {
                    passwordDescriptionTextView.visibility = View.GONE
                    fingerprintEnrolledTextView.visibility = View.VISIBLE
                    useFingerprintFutureCheckBox.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onEditorAction(v: TextView, actionId: Int, event: KeyEvent?): Boolean {
        return if (actionId == EditorInfo.IME_ACTION_GO) { verifyPassword(); true } else false
    }

    override fun onAuthenticated() {
        // Callback from FingerprintUiHelper. Let the activity know that authentication succeeded.
        callback.onPurchased(withFingerprint = true, crypto = cryptoObject)
        dismiss()
    }

    override fun onError() {
        goToBackup()
    }

    interface Callback {
        fun onPurchased(withFingerprint: Boolean, crypto: FingerprintManager.CryptoObject? = null)
        fun createKey(keyName: String, invalidatedByBiometricEnrollment: Boolean = true)
    }
}
