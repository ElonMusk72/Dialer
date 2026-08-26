package com.example

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.adapters.VaultFileAdapter
import com.example.data.VaultDatabase
import com.example.data.VaultFileEntity
import com.example.databinding.ActivityVaultBinding
import com.example.databinding.BottomSheetHideOptionsBinding
import com.example.databinding.DialogVaultSettingsBinding
import com.example.utils.SafeFolderManager
import com.example.utils.StoragePermissionUtils
import com.example.utils.VaultUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VaultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVaultBinding
    private lateinit var adapter: VaultFileAdapter

    // Tab categories: Videos, Photos, Documents, Audio
    private val tabTypes = listOf(
        SafeFolderManager.TYPE_VIDEO,
        SafeFolderManager.TYPE_PHOTO,
        SafeFolderManager.TYPE_DOCUMENT,
        SafeFolderManager.TYPE_AUDIO
    )

    private var currentTabType = SafeFolderManager.TYPE_VIDEO
    private var pendingFileTypeToHide: String? = null

    // Multiple file picker launcher
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data
            val targetType = pendingFileTypeToHide ?: currentTabType
            val urisToProcess = mutableListOf<Uri>()

            // Check clip data (multiple selection)
            val clipData = data?.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    urisToProcess.add(clipData.getItemAt(i).uri)
                }
            } else {
                data?.data?.let { urisToProcess.add(it) }
            }

            if (urisToProcess.isNotEmpty()) {
                processSelectedFiles(urisToProcess, targetType)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVaultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check if All Files Access is granted before showing the vault
        if (!StoragePermissionUtils.isAllFilesAccessGranted(this)) {
            StoragePermissionUtils.showAllFilesAccessDialog(this)
        }

        setupToolbar()
        setupTabs()
        setupRecyclerView()
        setupFab()
        observeVaultFiles()
    }

    override fun onResume() {
        super.onResume()
        if (!StoragePermissionUtils.isAllFilesAccessGranted(this)) {
            // Prompt if still not granted
        }
    }

    private fun setupToolbar() {
        binding.vaultToolbar.setNavigationOnClickListener {
            finish()
        }

        binding.btnSettings.setOnClickListener {
            showSettingsBottomSheet()
        }
    }

    private fun setupTabs() {
        // User requested 4 tabs: 1. Videos (🎬) 2. Photos (📷) 3. Documents (📄) 4. Audio (🎵)
        val tabTitles = listOf("🎬 Videos", "📷 Photos", "📄 Documents", "🎵 Audio")

        tabTitles.forEach { title ->
            binding.tabLayoutVault.addTab(binding.tabLayoutVault.newTab().setText(title))
        }

        binding.tabLayoutVault.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val position = tab?.position ?: 0
                if (position in tabTypes.indices) {
                    currentTabType = tabTypes[position]
                    updateLayoutManagerForTab(currentTabType)
                    observeVaultFiles()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        updateLayoutManagerForTab(currentTabType)
    }

    private fun updateLayoutManagerForTab(fileType: String) {
        // Use 2 columns for Photos & Videos, 1 column for Docs & Audio
        if (fileType == SafeFolderManager.TYPE_PHOTO || fileType == SafeFolderManager.TYPE_VIDEO) {
            binding.rvVaultFiles.layoutManager = GridLayoutManager(this, 1)
        } else {
            binding.rvVaultFiles.layoutManager = LinearLayoutManager(this)
        }
    }

    private fun setupRecyclerView() {
        adapter = VaultFileAdapter(
            scope = lifecycleScope,
            onItemClick = { file ->
                SafeFolderManager.openVaultFile(this, file)
            },
            onShareClick = { file ->
                SafeFolderManager.shareVaultFile(this, file)
            },
            onDeleteClick = { file ->
                confirmDeleteFile(file)
            }
        )
        binding.rvVaultFiles.adapter = adapter
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            showHideOptionsBottomSheet()
        }
    }

    private fun observeVaultFiles() {
        lifecycleScope.launch {
            VaultDatabase.getDatabase(this@VaultActivity)
                .vaultFileDao()
                .getFilesByType(currentTabType)
                .collectLatest { files ->
                    adapter.submitList(files)
                    updateEmptyState(files)
                }
        }
    }

    private fun updateEmptyState(files: List<VaultFileEntity>) {
        if (files.isEmpty()) {
            binding.emptyStateContainer.visibility = View.VISIBLE
            binding.rvVaultFiles.visibility = View.GONE

            when (currentTabType) {
                SafeFolderManager.TYPE_VIDEO -> {
                    binding.tvEmptyIcon.text = "🎬"
                    binding.tvEmptyTitle.text = "No hidden videos"
                    binding.tvEmptySubtitle.text = "Tap the + button below to securely hide videos in your vault."
                }
                SafeFolderManager.TYPE_PHOTO -> {
                    binding.tvEmptyIcon.text = "📷"
                    binding.tvEmptyTitle.text = "No hidden photos"
                    binding.tvEmptySubtitle.text = "Tap the + button below to securely hide photos in your vault."
                }
                SafeFolderManager.TYPE_DOCUMENT -> {
                    binding.tvEmptyIcon.text = "📄"
                    binding.tvEmptyTitle.text = "No hidden documents"
                    binding.tvEmptySubtitle.text = "Tap the + button below to securely hide documents and files."
                }
                SafeFolderManager.TYPE_AUDIO -> {
                    binding.tvEmptyIcon.text = "🎵"
                    binding.tvEmptyTitle.text = "No hidden audio"
                    binding.tvEmptySubtitle.text = "Tap the + button below to securely hide music and recordings."
                }
            }
        } else {
            binding.emptyStateContainer.visibility = View.GONE
            binding.rvVaultFiles.visibility = View.VISIBLE
        }
    }

    private fun showHideOptionsBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val sheetBinding = BottomSheetHideOptionsBinding.inflate(layoutInflater)
        bottomSheetDialog.setContentView(sheetBinding.root)

        sheetBinding.optionHidePhoto.setOnClickListener {
            bottomSheetDialog.dismiss()
            selectAndHideFiles(SafeFolderManager.TYPE_PHOTO)
        }

        sheetBinding.optionHideVideo.setOnClickListener {
            bottomSheetDialog.dismiss()
            selectAndHideFiles(SafeFolderManager.TYPE_VIDEO)
        }

        sheetBinding.optionHideDocument.setOnClickListener {
            bottomSheetDialog.dismiss()
            selectAndHideFiles(SafeFolderManager.TYPE_DOCUMENT)
        }

        sheetBinding.optionHideAudio.setOnClickListener {
            bottomSheetDialog.dismiss()
            selectAndHideFiles(SafeFolderManager.TYPE_AUDIO)
        }

        bottomSheetDialog.show()
    }

    private fun selectAndHideFiles(fileType: String) {
        pendingFileTypeToHide = fileType

        // Switch to the matching tab for user convenience
        val tabIndex = tabTypes.indexOf(fileType)
        if (tabIndex != -1 && binding.tabLayoutVault.selectedTabPosition != tabIndex) {
            binding.tabLayoutVault.getTabAt(tabIndex)?.select()
        }

        val mimeType = when (fileType) {
            SafeFolderManager.TYPE_PHOTO -> "image/*"
            SafeFolderManager.TYPE_VIDEO -> "video/*"
            SafeFolderManager.TYPE_DOCUMENT -> "*/*"
            SafeFolderManager.TYPE_AUDIO -> "audio/*"
            else -> "*/*"
        }

        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = mimeType
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
            if (fileType == SafeFolderManager.TYPE_DOCUMENT) {
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "application/pdf",
                        "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-powerpoint",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "text/plain",
                        "application/zip",
                        "application/x-rar-compressed"
                    )
                )
            }
        }

        try {
            filePickerLauncher.launch(Intent.createChooser(intent, "Select ${fileType.lowercase().replaceFirstChar { it.uppercase() }} to Hide"))
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open file picker.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processSelectedFiles(uris: List<Uri>, fileType: String) {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            var successCount = 0
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    val saved = SafeFolderManager.hideFile(this@VaultActivity, uri, fileType)
                    if (saved != null) {
                        successCount++
                    }
                }
            }

            binding.progressBar.visibility = View.GONE

            if (successCount > 0) {
                val message = if (successCount == 1) {
                    "File securely hidden in Vault 🔒"
                } else {
                    "$successCount files securely hidden in Vault 🔒"
                }
                Toast.makeText(this@VaultActivity, message, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@VaultActivity, "Failed to import selected file.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDeleteFile(file: VaultFileEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete from Vault")
            .setMessage("Are you sure you want to permanently delete \"${file.fileName}\"?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val deleted = SafeFolderManager.deleteVaultFile(this@VaultActivity, file)
                    if (deleted) {
                        Toast.makeText(this@VaultActivity, "File deleted.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val sheetBinding = DialogVaultSettingsBinding.inflate(layoutInflater)
        bottomSheetDialog.setContentView(sheetBinding.root)

        // Observe total files count and storage size
        lifecycleScope.launch {
            val db = VaultDatabase.getDatabase(this@VaultActivity).vaultFileDao()
            val allFiles = withContext(Dispatchers.IO) { db.getAllFilesSync() }
            val count = allFiles.size
            val totalBytes = allFiles.sumOf { it.fileSize }
            sheetBinding.tvStorageUsageSubtitle.text = "$count files • ${SafeFolderManager.formatFileSize(totalBytes)} used"
        }

        // Permission state
        val isGranted = StoragePermissionUtils.isAllFilesAccessGranted(this)
        sheetBinding.tvPermissionState.text = if (isGranted) "All Files Access: Granted ✅" else "All Files Access: Tap to configure"

        sheetBinding.optionChangePin.setOnClickListener {
            bottomSheetDialog.dismiss()
            AlertDialog.Builder(this)
                .setTitle("Change Vault PIN")
                .setMessage("Do you want to reset your vault PIN?")
                .setPositiveButton("Change") { _, _ ->
                    VaultUtils.clearPin(this)
                    val intent = Intent(this, PinSetupActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        sheetBinding.optionPermission.setOnClickListener {
            bottomSheetDialog.dismiss()
            StoragePermissionUtils.showAllFilesAccessDialog(this)
        }

        sheetBinding.optionClearVault.setOnClickListener {
            bottomSheetDialog.dismiss()
            confirmClearEntireVault()
        }

        bottomSheetDialog.show()
    }

    private fun confirmClearEntireVault() {
        AlertDialog.Builder(this)
            .setTitle("🗑️ Clear Entire Vault?")
            .setMessage("This will permanently delete all hidden photos, videos, documents, and audio files from your vault. This action cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                lifecycleScope.launch {
                    val cleared = SafeFolderManager.clearAllVaultFiles(this@VaultActivity)
                    if (cleared) {
                        Toast.makeText(this@VaultActivity, "Vault has been cleared.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@VaultActivity, "Error clearing vault.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
