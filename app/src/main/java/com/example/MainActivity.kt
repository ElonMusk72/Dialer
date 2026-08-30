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
import com.example.utils.LogRecorder
import com.example.utils.StoragePermissionUtils
import com.example.utils.VaultUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    val database: CallLogDatabase by lazy { CallLogDatabase.getDatabase(this) }

    private var inCallDialog: AlertDialog? = null
    private var inCallDialogBinding: DialogInCallBinding? = null
    private var callTimerJob: Job? = null
    private var isMuted = false
    private var isSpeakerOn = false

    private var isWaitingForAllFilesAccess = false

    // Step 2 Launcher: Call Logs
    private val callLogPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        requestContactsPermission()
    }

    // Step 3 Launcher: Contacts
    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        requestNotificationsPermission()
    }

    // Step 4 Launcher: Notifications
    private val notificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        onPermissionsFlowCompleted()
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

        LogRecorder.logInfo(TAG, "MainActivity created")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPagerAndNavigation()
        setupCallServiceListener()
        startPermissionFlow()
    }

    override fun onResume() {
        super.onResume()
        if (isWaitingForAllFilesAccess) {
            if (StoragePermissionUtils.isAllFilesAccessGranted(this)) {
                isWaitingForAllFilesAccess = false
                requestCallLogPermission()
            }
        }
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

    /**
     * Permission Order:
     * 1. All Files Access (MANAGE_EXTERNAL_STORAGE)
     * 2. Call Logs (READ_CALL_LOG)
     * 3. Contacts (READ_CONTACTS)
     * 4. Notifications (POST_NOTIFICATIONS)
     * 5. PIN Setup Screen (if not configured)
     */
    private fun startPermissionFlow() {
        LogRecorder.logInfo(TAG, "Starting permission flow")
        if (!StoragePermissionUtils.isAllFilesAccessGranted(this)) {
            LogRecorder.logWarning(TAG, "All Files Access not granted, prompting user")
            isWaitingForAllFilesAccess = true
            StoragePermissionUtils.showAllFilesAccessDialog(
                activity = this,
                onGrantClicked = {
                    LogRecorder.logInfo(TAG, "User agreed to grant All Files Access")
                },
                onDismissed = {
                    LogRecorder.logWarning(TAG, "User dismissed All Files Access dialog")
                    isWaitingForAllFilesAccess = false
                    requestCallLogPermission()
                }
            )
        } else {
            LogRecorder.logInfo(TAG, "All Files Access is already granted")
            requestCallLogPermission()
        }
    }

    private fun requestCallLogPermission() {
        val permissions = mutableListOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.VIBRATE
        )

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            callLogPermissionLauncher.launch(needed.toTypedArray())
        } else {
            requestContactsPermission()
        }
    }

    private fun requestContactsPermission() {
        val permissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS
        )

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            contactsPermissionLauncher.launch(needed.toTypedArray())
        } else {
            requestNotificationsPermission()
        }
    }

    private fun requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onPermissionsFlowCompleted()
        }
    }

    private fun onPermissionsFlowCompleted() {
        LogRecorder.logInfo(TAG, "Permissions flow completed")
        if (!VaultUtils.isPinSet(this)) {
            LogRecorder.logInfo(TAG, "Vault PIN is not set, redirecting to PinSetupActivity")
            val intent = Intent(this, PinSetupActivity::class.java)
            startActivity(intent)
        } else {
            LogRecorder.logInfo(TAG, "Vault PIN is set, checking default dialer status")
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
            LogRecorder.logInfo(TAG, "App is not default dialer, prompting user to set as default")
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
        } else {
            LogRecorder.logInfo(TAG, "App is already the default dialer")
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
        LogRecorder.logDebug(TAG, "handleCallStateChange: state=$state")
        if (call == null || state == Call.STATE_DISCONNECTED) {
            LogRecorder.logInfo(TAG, "Call disconnected or null, dismissing in-call dialog")
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
        LogRecorder.logInfo(TAG, "makeCall initiated for number: $phoneNumber (callerName: $callerName)")
        val savedPin = VaultUtils.getPin(this)
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+*#]"), "")
        val digitsOnly = phoneNumber.replace(Regex("[^0-9]"), "")

        // Check if entered digits match the Secret Vault PIN
        if (savedPin != null && (phoneNumber == savedPin || cleanNumber == savedPin || digitsOnly == savedPin)) {
            LogRecorder.logInfo(TAG, "PIN match detected in makeCall! Opening VaultActivity.")
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
            LogRecorder.logDebug(TAG, "Logged outgoing call into database for: $phoneNumber")
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            LogRecorder.logWarning(TAG, "CALL_PHONE permission not granted, requesting permission")
            callLogPermissionLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
            return
        }

        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val uri = Uri.fromParts("tel", cleanNumber, null)

        if (isDefaultDialer() && telecomManager != null) {
            try {
                val extras = Bundle()
                telecomManager.placeCall(uri, extras)
                LogRecorder.logInfo(TAG, "Placed call via TelecomManager to: $cleanNumber")
                showInCallDialog(phoneNumber, callerName)
                return
            } catch (e: Exception) {
                LogRecorder.logError(TAG, "Failed to place call via TelecomManager", e)
            }
        }

        // Direct ACTION_CALL with custom In-Call overlay
        try {
            LogRecorder.logInfo(TAG, "Placing call via ACTION_CALL to: $cleanNumber")
            val intent = Intent(Intent.ACTION_CALL, uri)
            startActivity(intent)
            showInCallDialog(phoneNumber, callerName)
        } catch (e: Exception) {
            LogRecorder.logError(TAG, "Failed to launch ACTION_CALL intent", e)
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
        LogRecorder.logInfo(TAG, "Displaying in-call dialog for: $phoneNumber")
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
            LogRecorder.logInfo(TAG, "Toggled mute state: isMuted=$isMuted")
            dialogBinding.tvMuteLabel.text = if (isMuted) getString(R.string.unmute) else getString(R.string.mute)
            dialogBinding.btnMute.isSelected = isMuted
            CallService.inCallServiceInstance?.toggleMute(isMuted)
            Toast.makeText(this, if (isMuted) "Microphone muted" else "Microphone unmuted", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnSpeaker.setOnClickListener {
            isSpeakerOn = !isSpeakerOn
            LogRecorder.logInfo(TAG, "Toggled speaker state: isSpeakerOn=$isSpeakerOn")
            dialogBinding.tvSpeakerLabel.text = if (isSpeakerOn) getString(R.string.speaker_off) else getString(R.string.speaker)
            dialogBinding.btnSpeaker.isSelected = isSpeakerOn
            CallService.inCallServiceInstance?.toggleSpeaker(isSpeakerOn)
            Toast.makeText(this, if (isSpeakerOn) "Speakerphone ON" else "Speakerphone OFF", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnEndCall.setOnClickListener {
            LogRecorder.logInfo(TAG, "User clicked End Call button")
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

