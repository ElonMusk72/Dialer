package com.example.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.text.format.DateUtils
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.content.ContextCompat
import com.example.data.ContactItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DialerUtils {

    private const val TAG = "DialerUtils"

    /**
     * Converts letters to T9 numeric digit strings.
     */
    fun nameToT9Digits(name: String): String {
        val sb = StringBuilder()
        for (char in name.uppercase(Locale.getDefault())) {
            when (char) {
                'A', 'B', 'C' -> sb.append('2')
                'D', 'E', 'F' -> sb.append('3')
                'G', 'H', 'I' -> sb.append('4')
                'J', 'K', 'L' -> sb.append('5')
                'M', 'N', 'O' -> sb.append('6')
                'P', 'Q', 'R', 'S' -> sb.append('7')
                'T', 'U', 'V' -> sb.append('8')
                'W', 'X', 'Y', 'Z' -> sb.append('9')
                else -> {
                    if (char.isDigit()) sb.append(char)
                }
            }
        }
        return sb.toString()
    }

    /**
     * Dynamically format phone numbers cleanly.
     */
    fun formatPhoneNumber(input: String): String {
        if (input.isBlank()) return ""
        val digits = input.replace(Regex("[^0-9+]"), "")
        if (digits.length <= 3) return digits
        if (digits.length <= 7 && !digits.startsWith("+")) {
            return "${digits.substring(0, 3)}-${digits.substring(3)}"
        }
        if (digits.length == 10 && !digits.startsWith("+")) {
            return "(${digits.substring(0, 3)}) ${digits.substring(3, 6)}-${digits.substring(6)}"
        }
        if (digits.length == 11 && digits.startsWith("1")) {
            return "+1 (${digits.substring(1, 4)}) ${digits.substring(4, 7)}-${digits.substring(7)}"
        }
        return try {
            PhoneNumberUtils.formatNumber(digits, Locale.getDefault().country) ?: digits
        } catch (e: Exception) {
            digits
        }
    }

    /**
     * Trigger haptic feedback for key presses.
     */
    fun performHapticFeedback(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val context = view.context
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(20)
                }
            }
        } catch (_: Exception) {
            // Fallback to view haptic if permission or vibrator service is unavailable
        }
    }

    /**
     * Format timestamp to human readable date/time string.
     */
    fun formatTimestamp(timestamp: Long): String {
        val now = System.currentTimeMillis()
        return if (DateUtils.isToday(timestamp)) {
            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
            "Today, " + sdf.format(Date(timestamp))
        } else if (DateUtils.isToday(timestamp + DateUtils.DAY_IN_MILLIS)) {
            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
            "Yesterday, " + sdf.format(Date(timestamp))
        } else {
            val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }

    /**
     * Format seconds into mm:ss format.
     */
    fun formatDuration(durationSeconds: Long): String {
        if (durationSeconds <= 0) return "0s"
        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        return if (minutes > 0) {
            "${minutes}m ${seconds}s"
        } else {
            "${seconds}s"
        }
    }

    /**
     * Permission checks
     */
    fun hasCallPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCallLogPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Read contacts from device or fallback to rich sample contacts.
     */
    fun loadContacts(context: Context): List<ContactItem> {
        LogRecorder.logInfo(TAG, "Loading contacts from device/content resolver")
        val contactsList = mutableListOf<ContactItem>()

        if (hasContactsPermission(context)) {
            try {
                val cursor = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
                        ContactsContract.CommonDataKinds.Phone.STARRED
                    ),
                    null,
                    null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )

                cursor?.use {
                    val idCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val nameCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val photoCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                    val starCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)

                    val seenNumbers = mutableSetOf<String>()

                    while (it.moveToNext()) {
                        val id = if (idCol >= 0) it.getString(idCol) else ""
                        val name = if (nameCol >= 0) it.getString(nameCol) ?: "Unknown" else "Unknown"
                        val number = if (numCol >= 0) it.getString(numCol) ?: "" else ""
                        val photoUri = if (photoCol >= 0) it.getString(photoCol) else null
                        val isStarred = if (starCol >= 0) it.getInt(starCol) == 1 else false

                        val cleanNumber = number.replace(Regex("[^0-9+]"), "")
                        if (cleanNumber.isNotEmpty() && seenNumbers.add(cleanNumber)) {
                            contactsList.add(
                                ContactItem(
                                    id = id,
                                    name = name,
                                    phoneNumber = number,
                                    photoUri = photoUri,
                                    isFavorite = isStarred,
                                    t9Digits = nameToT9Digits(name)
                                )
                            )
                        }
                    }
                }
                LogRecorder.logSuccess(TAG, "Loaded ${contactsList.size} contacts from ContentResolver")
            } catch (e: Exception) {
                LogRecorder.logError(TAG, "Failed to query contacts from ContentResolver", e)
            }
        } else {
            LogRecorder.logWarning(TAG, "READ_CONTACTS permission not granted")
        }

        if (contactsList.isEmpty()) {
            LogRecorder.logInfo(TAG, "Using fallback sample contacts")
            contactsList.addAll(getSampleContacts())
        }

        return contactsList
    }

    /**
     * Realistic sample contacts for default demonstration and offline fallback
     */
    fun getSampleContacts(): List<ContactItem> {
        val samples = listOf(
            ContactItem("1", "Alex Rivera", "(555) 234-5678", isFavorite = true),
            ContactItem("2", "Sarah Connor", "(555) 876-5432", isFavorite = true),
            ContactItem("3", "David Kim", "(555) 345-6789", isFavorite = false),
            ContactItem("4", "Emma Watson", "(555) 987-6543", isFavorite = true),
            ContactItem("5", "James Miller", "(555) 456-7890", isFavorite = false),
            ContactItem("6", "Sophia Martinez", "(555) 654-3210", isFavorite = false),
            ContactItem("7", "Tech Support", "1-800-555-0199", isFavorite = true),
            ContactItem("8", "Voicemail", "*86", isFavorite = false),
            ContactItem("9", "Mom", "(555) 111-2222", isFavorite = true),
            ContactItem("10", "Dad", "(555) 333-4444", isFavorite = false)
        )
        return samples.map { it.copy(t9Digits = nameToT9Digits(it.name)) }
    }
}
