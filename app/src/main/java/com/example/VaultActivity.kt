package com.example

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.databinding.ActivityVaultBinding
import com.example.utils.StoragePermissionUtils
import com.example.utils.VaultUtils

class VaultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVaultBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVaultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val isGranted = StoragePermissionUtils.isAllFilesAccessGranted(this)
        if (isGranted) {
            binding.tvPermissionIcon.text = "✅"
            binding.tvPermissionTitle.text = "All Files Access Granted"
            binding.tvPermissionSubtitle.text = "Ready to hide and protect your files"
            binding.btnGrantPermission.visibility = View.GONE
            binding.cardPermissionStatus.strokeColor = ContextCompat.getColor(this, R.color.accent_green)
            binding.cardPermissionStatus.setOnClickListener(null)
        } else {
            binding.tvPermissionIcon.text = "📁"
            binding.tvPermissionTitle.text = "All Files Access Needed"
            binding.tvPermissionSubtitle.text = "Required to hide & manage vault files • Tap to grant"
            binding.btnGrantPermission.visibility = View.VISIBLE
            binding.cardPermissionStatus.strokeColor = ContextCompat.getColor(this, R.color.divider_color)
            binding.cardPermissionStatus.setOnClickListener {
                StoragePermissionUtils.showAllFilesAccessDialog(this)
            }
        }
    }

    private fun setupListeners() {
        binding.btnCloseVault.setOnClickListener {
            finish()
        }

        binding.btnExitVault.setOnClickListener {
            finish()
        }

        binding.btnGrantPermission.setOnClickListener {
            StoragePermissionUtils.showAllFilesAccessDialog(this)
        }

        binding.btnHideFile.setOnClickListener {
            if (!StoragePermissionUtils.isAllFilesAccessGranted(this)) {
                StoragePermissionUtils.showAllFilesAccessDialog(this) {
                    // Triggered when user proceeds
                }
            } else {
                AlertDialog.Builder(this)
                    .setTitle("📁 Hide File")
                    .setMessage("All Files Access is active. Select photos, videos, or documents to securely encrypt and hide in your secret vault.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        binding.btnViewFiles.setOnClickListener {
            if (!StoragePermissionUtils.isAllFilesAccessGranted(this)) {
                StoragePermissionUtils.showAllFilesAccessDialog(this)
            } else {
                AlertDialog.Builder(this)
                    .setTitle("🔒 Vault Files")
                    .setMessage("No hidden files currently stored in your vault. Use 'Hide File' to add items.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        binding.btnChangePin.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Vault PIN")
                .setMessage("Do you want to reset your vault PIN? You will be guided to set a new PIN.")
                .setPositiveButton("Reset") { _, _ ->
                    VaultUtils.clearPin(this)
                    val intent = Intent(this, PinSetupActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
