package com.example

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.databinding.ActivityPinSetupBinding
import com.example.utils.LogRecorder
import com.example.utils.VaultUtils

class PinSetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PinSetupActivity"
    }

    private lateinit var binding: ActivityPinSetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LogRecorder.logInfo(TAG, "PinSetupActivity created")

        // If PIN is already configured, proceed straight to the Dialer
        if (VaultUtils.isPinSet(this)) {
            LogRecorder.logInfo(TAG, "PIN already set, navigating straight to Dialer")
            navigateToDialer()
            return
        }

        binding = ActivityPinSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    private fun setupListeners() {
        binding.btnCreatePin.setOnClickListener {
            val pin = binding.etPin.text?.toString()?.trim().orEmpty()
            val confirmPin = binding.etConfirmPin.text?.toString()?.trim().orEmpty()

            when {
                pin.length < 4 || pin.length > 6 -> {
                    val msg = "PIN must be 4 to 6 digits long."
                    LogRecorder.logWarning(TAG, "PIN setup validation failed: $msg")
                    showError(msg)
                }
                !pin.all { it.isDigit() } -> {
                    val msg = "PIN must contain only numbers."
                    LogRecorder.logWarning(TAG, "PIN setup validation failed: $msg")
                    showError(msg)
                }
                pin != confirmPin -> {
                    val msg = "PINs do not match. Please re-enter."
                    LogRecorder.logWarning(TAG, "PIN setup validation failed: $msg")
                    showError(msg)
                }
                else -> {
                    binding.tvErrorMessage.visibility = View.GONE
                    VaultUtils.savePin(this, pin)
                    LogRecorder.logSuccess(TAG, "Vault PIN saved successfully")
                    Toast.makeText(this, "Vault PIN created successfully!", Toast.LENGTH_SHORT).show()
                    navigateToDialer()
                }
            }
        }
    }

    private fun showError(message: String) {
        binding.tvErrorMessage.text = message
        binding.tvErrorMessage.visibility = View.VISIBLE
    }

    private fun navigateToDialer() {
        LogRecorder.logInfo(TAG, "Navigating to MainActivity (Dialer)")
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }
}
