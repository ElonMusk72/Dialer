package com.example

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.databinding.ActivityPinSetupBinding
import com.example.utils.VaultUtils

class PinSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPinSetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If PIN is already configured, proceed straight to the Dialer
        if (VaultUtils.isPinSet(this)) {
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
                    showError("PIN must be 4 to 6 digits long.")
                }
                !pin.all { it.isDigit() } -> {
                    showError("PIN must contain only numbers.")
                }
                pin != confirmPin -> {
                    showError("PINs do not match. Please re-enter.")
                }
                else -> {
                    binding.tvErrorMessage.visibility = View.GONE
                    VaultUtils.savePin(this, pin)
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
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }
}
