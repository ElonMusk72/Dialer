package com.example

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.data.VaultDatabase
import com.example.data.VaultFileEntity
import com.example.databinding.ActivityAudioPlayerBinding
import com.example.utils.SafeFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class AudioPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAudioPlayerBinding
    private var mediaPlayer: MediaPlayer? = null
    private var vaultFileId: Long = -1L
    private var vaultFile: VaultFileEntity? = null

    private var isPlaying = false
    private var isTrackingTouch = false

    private val handler = Handler(Looper.getMainLooper())
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { mp ->
                if (!isTrackingTouch && mp.isPlaying) {
                    val current = mp.currentPosition
                    val total = mp.duration
                    if (total > 0) {
                        val progress = (current * 1000L / total).toInt()
                        binding.audioSeekBar.progress = progress
                        binding.tvCurrentTime.text = formatTime(current.toLong())
                    }
                }
            }
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vaultFileId = intent.getLongExtra(EXTRA_FILE_ID, -1L)
        val directPath = intent.getStringExtra(EXTRA_FILE_PATH)
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME)

        binding.tvAudioTitle.text = fileName ?: "Audio Track"

        setupListeners()
        loadAudio(vaultFileId, directPath)
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnPlayPause.setOnClickListener {
            togglePlayPause()
        }

        binding.btnRewind10.setOnClickListener {
            mediaPlayer?.let { mp ->
                val target = (mp.currentPosition - 10000).coerceAtLeast(0)
                mp.seekTo(target)
                binding.tvCurrentTime.text = formatTime(target.toLong())
            }
        }

        binding.btnForward10.setOnClickListener {
            mediaPlayer?.let { mp ->
                val target = (mp.currentPosition + 10000).coerceAtMost(mp.duration)
                mp.seekTo(target)
                binding.tvCurrentTime.text = formatTime(target.toLong())
            }
        }

        binding.btnShare.setOnClickListener {
            vaultFile?.let { SafeFolderManager.shareVaultFile(this, it) }
        }

        binding.btnDelete.setOnClickListener {
            confirmDelete()
        }

        binding.audioSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.let { mp ->
                        val total = mp.duration
                        if (total > 0) {
                            val seekPos = (progress * total.toLong() / 1000L).toInt()
                            binding.tvCurrentTime.text = formatTime(seekPos.toLong())
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isTrackingTouch = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                mediaPlayer?.let { mp ->
                    val total = mp.duration
                    if (total > 0) {
                        val progress = seekBar?.progress ?: 0
                        val seekPos = (progress * total.toLong() / 1000L).toInt()
                        mp.seekTo(seekPos)
                        binding.tvCurrentTime.text = formatTime(seekPos.toLong())
                    }
                }
                isTrackingTouch = false
            }
        })
    }

    private fun loadAudio(fileId: Long, directPath: String?) {
        lifecycleScope.launch {
            val fileEntity = withContext(Dispatchers.IO) {
                if (fileId != -1L) {
                    VaultDatabase.getDatabase(this@AudioPlayerActivity).vaultFileDao().getAllFilesSync()
                        .firstOrNull { it.id == fileId }
                } else null
            }
            vaultFile = fileEntity

            val path = fileEntity?.savedPath ?: directPath
            if (path.isNullOrEmpty()) {
                Toast.makeText(this@AudioPlayerActivity, "Audio file not found.", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            val file = File(path)
            if (!file.exists()) {
                Toast.makeText(this@AudioPlayerActivity, "Audio not found in storage.", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            binding.tvAudioTitle.text = fileEntity?.fileName ?: file.name

            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(this@AudioPlayerActivity, Uri.fromFile(file))
                    prepare()
                    val totalDuration = duration
                    binding.tvTotalDuration.text = formatTime(totalDuration.toLong())
                    binding.tvCurrentTime.text = formatTime(0)

                    setOnCompletionListener {
                        this@AudioPlayerActivity.isPlaying = false
                        binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
                        binding.audioSeekBar.progress = 1000
                    }

                    start()
                    this@AudioPlayerActivity.isPlaying = true
                    binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
                }
                handler.post(updateProgressRunnable)
            } catch (e: Exception) {
                Toast.makeText(this@AudioPlayerActivity, "Failed to load audio format.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun togglePlayPause() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                mp.pause()
                isPlaying = false
                binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
            } else {
                mp.start()
                isPlaying = true
                binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
            }
        }
    }

    private fun formatTime(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(minutes)
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun confirmDelete() {
        val file = vaultFile ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete Audio")
            .setMessage("Are you sure you want to permanently delete \"${file.fileName}\" from your vault?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                    mediaPlayer = null
                    val deleted = SafeFolderManager.deleteVaultFile(this@AudioPlayerActivity, file)
                    if (deleted) {
                        Toast.makeText(this@AudioPlayerActivity, "Audio deleted.", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateProgressRunnable)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    companion object {
        const val EXTRA_FILE_ID = "extra_vault_file_id"
        const val EXTRA_FILE_PATH = "extra_vault_file_path"
        const val EXTRA_FILE_NAME = "extra_vault_file_name"
    }
}
