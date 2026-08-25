package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.data.CallLogDatabase
import com.example.databinding.ActivityMainBinding
import com.example.databinding.DialogInCallBinding
import com.example.ui.CallLogFragment
import com.example.ui.ContactsFragment
import com.example.ui.DialerFragment
import com.example.ui.FavoritesFragment
import com.example.utils.DialerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    val database: CallLogDatabase by lazy { CallLogDatabase.getDatabase(this) }

    private var inCallDialog: AlertDialog? = null
    private var callTimerJob: Job? = null
    private var isMuted = false
    private var isSpeakerOn = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions updated, refresh contacts or call log views if necessary
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPagerAndNavigation()
        requestPermissionsIfNeeded()
    }

    private fun setupViewPagerAndNavigation() {
        val adapter = MainPagerAdapter(this)
        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = 3

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                when (position) {
                    0 -> binding.bottomNavigation.selectedItemId = R.id.navigation_keypad
                    1 -> binding.bottomNavigation.selectedItemId = R.id.navigation_recents
                    2 -> binding.bottomNavigation.selectedItemId = R.id.navigation_contacts
                    3 -> binding.bottomNavigation.selectedItemId = R.id.navigation_favorites
                }
            }
        })

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_keypad -> binding.viewPager.setCurrentItem(0, true)
                R.id.navigation_recents -> binding.viewPager.setCurrentItem(1, true)
                R.id.navigation_contacts -> binding.viewPager.setCurrentItem(2, true)
                R.id.navigation_favorites -> binding.viewPager.setCurrentItem(3, true)
            }
            true
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.VIBRATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    fun notifyFavoritesUpdated() {
        supportFragmentManager.fragments.forEach { fragment ->
            if (fragment is FavoritesFragment) {
                fragment.loadFavorites()
            }
        }
    }

    fun showInCallDialog(phoneNumber: String, callerName: String?) {
        inCallDialog?.dismiss()
        callTimerJob?.cancel()

        val dialogBinding = DialogInCallBinding.inflate(LayoutInflater.from(this))
        val displayName = callerName ?: DialerUtils.formatPhoneNumber(phoneNumber)

        dialogBinding.tvInCallName.text = displayName
        dialogBinding.tvInCallNumber.text = DialerUtils.formatPhoneNumber(phoneNumber)
        dialogBinding.tvInCallAvatar.text = displayName.take(1).uppercase()
        dialogBinding.tvInCallStatus.text = "Calling..."

        isMuted = false
        isSpeakerOn = false

        dialogBinding.btnMute.setOnClickListener {
            isMuted = !isMuted
            dialogBinding.tvMuteLabel.text = if (isMuted) getString(R.string.unmute) else getString(R.string.mute)
            dialogBinding.btnMute.isSelected = isMuted
            Toast.makeText(this, if (isMuted) "Microphone muted" else "Microphone unmuted", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnSpeaker.setOnClickListener {
            isSpeakerOn = !isSpeakerOn
            dialogBinding.tvSpeakerLabel.text = if (isSpeakerOn) getString(R.string.speaker_off) else getString(R.string.speaker)
            dialogBinding.btnSpeaker.isSelected = isSpeakerOn
            Toast.makeText(this, if (isSpeakerOn) "Speakerphone ON" else "Speakerphone OFF", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnEndCall.setOnClickListener {
            callTimerJob?.cancel()
            inCallDialog?.dismiss()
            Toast.makeText(this, "Call Ended", Toast.LENGTH_SHORT).show()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        inCallDialog = dialog

        // Start Call Timer
        callTimerJob = CoroutineScope(Dispatchers.Main).launch {
            var seconds = 0
            while (isActive) {
                delay(1000)
                seconds++
                val mins = seconds / 60
                val secs = seconds % 60
                val timerFormatted = String.format("%02d:%02d", mins, secs)
                dialogBinding.tvInCallStatus.text = timerFormatted
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callTimerJob?.cancel()
        inCallDialog?.dismiss()
    }

    private class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 4

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> DialerFragment()
                1 -> CallLogFragment()
                2 -> ContactsFragment()
                3 -> FavoritesFragment()
                else -> DialerFragment()
            }
        }
    }
}
