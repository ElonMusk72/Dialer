package com.example

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.TelecomManager
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.data.CallLogDatabase
import com.example.data.CallLogEntity
import com.example.databinding.ActivityMainBinding
import com.example.databinding.DialogInCallBinding
import com.example.service.CallService
import com.example.ui.CallLogFragment
import com.example.ui.ContactsFragment
import com.example.ui.DialerFragment
import com.example.ui.FavoritesFragment
import com.example.utils.DialerUtils
import com.example.utils.VaultUtils
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
    private var inCallDialogBinding: DialogInCallBinding? = null
    private var callTimerJob: Job? = null
    private var isMuted = false
    private var isSpeakerOn = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CALL_PHONE] == true) {
            checkAndPromptDefaultDialer()
        }
    }

    private val defaultDialerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (isDefaultDialer()) {
            Toast.makeText(this, "App set as default dialer! Calls will stay inside the app.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!VaultUtils.isPinSet(this)) {
            val intent = Intent(this, PinSetupActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPagerAndNavigation()
        requestPermissionsIfNeeded()
        setupCallServiceListener()
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
        } else {
            checkAndPromptDefaultDialer()
        }
    }

    fun isDefaultDialer(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(Context.ROLE_SERVICE) as? RoleManager
            roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        } else {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            packageName == telecomManager?.defaultDialerPackage
        }
    }

    fun checkAndPromptDefaultDialer() {
        if (!isDefaultDialer()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(Context.ROLE_SERVICE) as? RoleManager
                if (roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    defaultDialerLauncher.launch(intent)
                }
            } else {
                val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                    putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                }
                defaultDialerLauncher.launch(intent)
            }
        }
    }

    private fun setupCallServiceListener() {
        CallService.onCallStateChangedListener = { call, state ->
            runOnUiThread {
                handleCallStateChange(call, state)
            }
        }
    }

    private fun handleCallStateChange(call: Call?, state: Int) {
        if (call == null || state == Call.STATE_DISCONNECTED) {
            callTimerJob?.cancel()
            inCallDialog?.dismiss()
            inCallDialog = null
            inCallDialogBinding = null
            return
        }

        val handle = call.details?.handle?.schemeSpecificPart ?: ""
        val statusText = when (state) {
            Call.STATE_CONNECTING -> "Connecting..."
            Call.STATE_DIALING -> "Dialing..."
            Call.STATE_RINGING -> "Ringing..."
            Call.STATE_ACTIVE -> "In Call"
            Call.STATE_HOLDING -> "On Hold"
            else -> "Connecting..."
        }

        if (inCallDialog == null || inCallDialog?.isShowing == false) {
            showInCallDialog(handle, null)
        }

        inCallDialogBinding?.let { dialogBinding ->
            if (state != Call.STATE_ACTIVE) {
                dialogBinding.tvInCallStatus.text = statusText
            }
        }
    }

    fun makeCall(phoneNumber: String, callerName: String?) {
        val savedPin = VaultUtils.getPin(this)
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+*#]"), "")
        val digitsOnly = phoneNumber.replace(Regex("[^0-9]"), "")

        // Check if entered digits match the Secret Vault PIN
        if (savedPin != null && (phoneNumber == savedPin || cleanNumber == savedPin || digitsOnly == savedPin)) {
            val intent = Intent(this, VaultActivity::class.java)
            startActivity(intent)
            return
        }

        // Record call in Room database
        val callLog = CallLogEntity(
            phoneNumber = phoneNumber,
            callerName = callerName,
            callType = "OUTGOING",
            timestamp = System.currentTimeMillis(),
            durationSeconds = 0
        )
        lifecycleScope.launch(Dispatchers.IO) {
            database.callLogDao().insertCallLog(callLog)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
            return
        }

        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val uri = Uri.fromParts("tel", cleanNumber, null)

        if (isDefaultDialer() && telecomManager != null) {
            try {
                val extras = Bundle()
                telecomManager.placeCall(uri, extras)
                showInCallDialog(phoneNumber, callerName)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Direct ACTION_CALL with custom In-Call overlay
        try {
            val intent = Intent(Intent.ACTION_CALL, uri)
            startActivity(intent)
            showInCallDialog(phoneNumber, callerName)
        } catch (e: Exception) {
            showInCallDialog(phoneNumber, callerName)
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
        inCallDialogBinding = dialogBinding
        val displayName = callerName ?: DialerUtils.formatPhoneNumber(phoneNumber)

        dialogBinding.tvInCallName.text = displayName
        dialogBinding.tvInCallNumber.text = DialerUtils.formatPhoneNumber(phoneNumber)
        dialogBinding.tvInCallAvatar.text = displayName.take(1).uppercase()
        dialogBinding.tvInCallStatus.text = "Dialing..."

        isMuted = false
        isSpeakerOn = false

        dialogBinding.btnMute.setOnClickListener {
            isMuted = !isMuted
            dialogBinding.tvMuteLabel.text = if (isMuted) getString(R.string.unmute) else getString(R.string.mute)
            dialogBinding.btnMute.isSelected = isMuted
            CallService.inCallServiceInstance?.toggleMute(isMuted)
            Toast.makeText(this, if (isMuted) "Microphone muted" else "Microphone unmuted", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnSpeaker.setOnClickListener {
            isSpeakerOn = !isSpeakerOn
            dialogBinding.tvSpeakerLabel.text = if (isSpeakerOn) getString(R.string.speaker_off) else getString(R.string.speaker)
            dialogBinding.btnSpeaker.isSelected = isSpeakerOn
            CallService.inCallServiceInstance?.toggleSpeaker(isSpeakerOn)
            Toast.makeText(this, if (isSpeakerOn) "Speakerphone ON" else "Speakerphone OFF", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnEndCall.setOnClickListener {
            CallService.inCallServiceInstance?.endCurrentCall()
            callTimerJob?.cancel()
            inCallDialog?.dismiss()
            inCallDialog = null
            inCallDialogBinding = null
            Toast.makeText(this, "Call Ended", Toast.LENGTH_SHORT).show()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        inCallDialog = dialog

        // Start Call Duration Timer
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
        CallService.onCallStateChangedListener = null
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

