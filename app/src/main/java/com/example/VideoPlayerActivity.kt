package com.example

import android.content.pm.ActivityInfo
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.data.VaultDatabase
import com.example.data.VaultFileEntity
import com.example.databinding.ActivityVideoPlayerBinding
import com.example.utils.LogRecorder
import com.example.utils.SafeFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var vaultFileId: Long = -1L
    private var vaultFile: VaultFileEntity? = null

    private var isPlaying = false
    private var isControlsVisible = true
    private var isFullscreen = false
    private var isTrackingTouch = false

    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (!isTrackingTouch && binding.videoView.isPlaying) {
                val current = binding.videoView.currentPosition
                val total = binding.videoView.duration
                if (total > 0) {
                    val progress = (current * 1000L / total).toInt()
                    binding.videoSeekBar.progress = progress
                    binding.tvCurrentTime.text = formatTime(current.toLong())
                }
            }
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vaultFileId = intent.getLongExtra(EXTRA_FILE_ID, -1L)
        val directPath = intent.getStringExtra(EXTRA_FILE_PATH)
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME)

        LogRecorder.logInfo(TAG, "VideoPlayerActivity created: fileId=$vaultFileId, fileName=$fileName, path=$directPath")

        binding.tvVideoTitle.text = fileName ?: "Video Player"

        setupListeners()
        loadVideo(vaultFileId, directPath)
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.touchOverlay.setOnClickListener {
            toggleControls()
        }

        binding.btnCenterPlay.setOnClickListener {
            togglePlayPause()
        }

        binding.btnBottomPlayPause.setOnClickListener {
            togglePlayPause()
        }

        binding.btnRewind10.setOnClickListener {
            seekRelative(-10000)
            resetHideControlsTimer()
        }

        binding.btnForward10.setOnClickListener {
            seekRelative(10000)
            resetHideControlsTimer()
        }

        binding.btnFullscreen.setOnClickListener {
            toggleFullscreen()
            resetHideControlsTimer()
        }

        binding.btnShare.setOnClickListener {
            vaultFile?.let { SafeFolderManager.shareVaultFile(this, it) }
        }

        binding.btnDelete.setOnClickListener {
            confirmDelete()
        }

        binding.videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val total = binding.videoView.duration
                    if (total > 0) {
                        val seekPos = (progress * total.toLong() / 1000L).toInt()
                        binding.tvCurrentTime.text = formatTime(seekPos.toLong())
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isTrackingTouch = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val total = binding.videoView.duration
                if (total > 0) {
                    val progress = seekBar?.progress ?: 0
                    val seekPos = (progress * total.toLong() / 1000L).toInt()
                    binding.videoView.seekTo(seekPos)
                    binding.tvCurrentTime.text = formatTime(seekPos.toLong())
                }
                isTrackingTouch = false
                resetHideControlsTimer()
            }
        })
    }

    private fun loadVideo(fileId: Long, directPath: String?) {
        binding.videoBufferingProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val fileEntity = withContext(Dispatchers.IO) {
                if (fileId != -1L) {
                    VaultDatabase.getDatabase(this@VideoPlayerActivity).vaultFileDao().getAllFilesSync()
                        .firstOrNull { it.id == fileId }
                } else null
            }
            vaultFile = fileEntity

            val path = fileEntity?.savedPath ?: directPath
            if (path.isNullOrEmpty()) {
                LogRecorder.logError(TAG, "Video file path is null or empty for fileId=$fileId")
                Toast.makeText(this@VideoPlayerActivity, "Video file not found.", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            val file = File(path)
            if (!file.exists()) {
                LogRecorder.logError(TAG, "Video file does not exist on disk: $path")
                Toast.makeText(this@VideoPlayerActivity, "Video not found in storage.", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            binding.tvVideoTitle.text = fileEntity?.fileName ?: file.name

            binding.videoView.setVideoURI(Uri.fromFile(file))

            binding.videoView.setOnPreparedListener { mp ->
                LogRecorder.logSuccess(TAG, "Video prepared successfully: ${file.name}")
                binding.videoBufferingProgress.visibility = View.GONE
                val duration = mp.duration
                binding.tvTotalDuration.text = formatTime(duration.toLong())
                binding.tvCurrentTime.text = formatTime(0)
                binding.videoSeekBar.progress = 0

                mp.setOnVideoSizeChangedListener { _, _, _ ->
                    // Adjust aspect ratio
                }

                // Start playback automatically
                binding.videoView.start()
                isPlaying = true
                updatePlayPauseIcons(true)
                handler.post(updateProgressRunnable)
                resetHideControlsTimer()
            }

            binding.videoView.setOnCompletionListener {
                LogRecorder.logInfo(TAG, "Video playback completed for: ${file.name}")
                isPlaying = false
                updatePlayPauseIcons(false)
                showControls()
                binding.videoSeekBar.progress = 1000
                binding.tvCurrentTime.text = binding.tvTotalDuration.text
            }

            binding.videoView.setOnErrorListener { _, what, extra ->
                LogRecorder.logError(TAG, "Video playback error: what=$what, extra=$extra for path=$path")
                binding.videoBufferingProgress.visibility = View.GONE
                Toast.makeText(this@VideoPlayerActivity, "Unable to play video format.", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    private fun togglePlayPause() {
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
            isPlaying = false
            updatePlayPauseIcons(false)
            showControls()
            handler.removeCallbacks(hideControlsRunnable)
        } else {
            binding.videoView.start()
            isPlaying = true
            updatePlayPauseIcons(true)
            resetHideControlsTimer()
        }
    }

    private fun updatePlayPauseIcons(playing: Boolean) {
        if (playing) {
            binding.btnBottomPlayPause.setImageResource(R.drawable.ic_pause)
            binding.btnCenterPlay.setImageResource(R.drawable.ic_pause)
            binding.btnCenterPlay.visibility = View.GONE
        } else {
            binding.btnBottomPlayPause.setImageResource(R.drawable.ic_play_arrow)
            binding.btnCenterPlay.setImageResource(R.drawable.ic_play_arrow)
            binding.btnCenterPlay.visibility = View.VISIBLE
        }
    }

    private fun seekRelative(offsetMs: Int) {
        val current = binding.videoView.currentPosition
        val duration = binding.videoView.duration
        val target = (current + offsetMs).coerceIn(0, duration)
        binding.videoView.seekTo(target)
        if (duration > 0) {
            binding.videoSeekBar.progress = (target * 1000L / duration).toInt()
            binding.tvCurrentTime.text = formatTime(target.toLong())
        }
    }

    private fun toggleControls() {
        if (isControlsVisible) {
            hideControls()
        } else {
            showControls()
            resetHideControlsTimer()
        }
    }

    private fun showControls() {
        isControlsVisible = true
        binding.topControls.visibility = View.VISIBLE
        binding.bottomControls.visibility = View.VISIBLE
        if (!binding.videoView.isPlaying) {
            binding.btnCenterPlay.visibility = View.VISIBLE
        }
    }

    private fun hideControls() {
        isControlsVisible = false
        binding.topControls.visibility = View.GONE
        binding.bottomControls.visibility = View.GONE
        binding.btnCenterPlay.visibility = View.GONE
    }

    private fun resetHideControlsTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        if (isPlaying) {
            handler.postDelayed(hideControlsRunnable, 3500)
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            hideSystemUi()
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            showSystemUi()
        }
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    private fun showSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
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
            .setTitle("Delete Video")
            .setMessage("Are you sure you want to permanently delete \"${file.fileName}\" from your vault?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    binding.videoView.stopPlayback()
                    val deleted = SafeFolderManager.deleteVaultFile(this@VideoPlayerActivity, file)
                    if (deleted) {
                        Toast.makeText(this@VideoPlayerActivity, "Video deleted.", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
            isPlaying = false
            updatePlayPauseIcons(false)
        }
        handler.removeCallbacks(updateProgressRunnable)
        handler.removeCallbacks(hideControlsRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.videoView.stopPlayback()
        handler.removeCallbacks(updateProgressRunnable)
        handler.removeCallbacks(hideControlsRunnable)
    }

    companion object {
        private const val TAG = "VideoPlayerActivity"
        const val EXTRA_FILE_ID = "extra_vault_file_id"
        const val EXTRA_FILE_PATH = "extra_vault_file_path"
        const val EXTRA_FILE_NAME = "extra_vault_file_name"
    }
}
