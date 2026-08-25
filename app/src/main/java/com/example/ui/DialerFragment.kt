package com.example.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.MainActivity
import com.example.adapters.SuggestionAdapter
import com.example.data.CallLogEntity
import com.example.data.ContactItem
import com.example.databinding.FragmentDialerBinding
import com.example.utils.DialerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DialerFragment : Fragment() {

    private var _binding: FragmentDialerBinding? = null
    private val binding get() = _binding!!

    private val dialedDigits = StringBuilder()
    private var allContacts: List<ContactItem> = emptyList()
    private lateinit var suggestionAdapter: SuggestionAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDialerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSuggestionsRecyclerView()
        setupKeypadClickListeners()
        setupActionButtons()
        loadContactsData()
        updateDialDisplay()
    }

    private fun setupSuggestionsRecyclerView() {
        suggestionAdapter = SuggestionAdapter(
            onItemClick = { contact ->
                dialedDigits.clear()
                dialedDigits.append(contact.phoneNumber.replace(Regex("[^0-9+]"), ""))
                updateDialDisplay()
            },
            onQuickCallClick = { contact ->
                placeCall(contact.phoneNumber, contact.name)
            }
        )

        binding.rvSuggestions.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = suggestionAdapter
        }
    }

    private fun setupKeypadClickListeners() {
        val keys = listOf(
            binding.key0 to "0",
            binding.key1 to "1",
            binding.key2 to "2",
            binding.key3 to "3",
            binding.key4 to "4",
            binding.key5 to "5",
            binding.key6 to "6",
            binding.key7 to "7",
            binding.key8 to "8",
            binding.key9 to "9",
            binding.keyStar to "*",
            binding.keyHash to "#"
        )

        for ((view, digit) in keys) {
            view.setOnClickListener {
                DialerUtils.performHapticFeedback(it)
                dialedDigits.append(digit)
                updateDialDisplay()
            }
        }

        // Long press 0 for '+'
        binding.key0.setOnLongClickListener {
            DialerUtils.performHapticFeedback(it)
            dialedDigits.append("+")
            updateDialDisplay()
            true
        }
    }

    private fun setupActionButtons() {
        binding.btnBackspace.setOnClickListener {
            DialerUtils.performHapticFeedback(it)
            if (dialedDigits.isNotEmpty()) {
                dialedDigits.deleteCharAt(dialedDigits.length - 1)
                updateDialDisplay()
            }
        }

        binding.btnBackspace.setOnLongClickListener {
            DialerUtils.performHapticFeedback(it)
            if (dialedDigits.isNotEmpty()) {
                dialedDigits.clear()
                updateDialDisplay()
            }
            true
        }

        binding.btnDisplayBackspace.setOnClickListener {
            DialerUtils.performHapticFeedback(it)
            if (dialedDigits.isNotEmpty()) {
                dialedDigits.deleteCharAt(dialedDigits.length - 1)
                updateDialDisplay()
            }
        }

        binding.btnCall.setOnClickListener {
            DialerUtils.performHapticFeedback(it)
            val number = dialedDigits.toString()
            if (number.isNotBlank()) {
                val matchedName = findMatchedContactName(number)
                placeCall(number, matchedName)
            } else {
                Toast.makeText(requireContext(), "Please enter a phone number", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAddContact.setOnClickListener {
            DialerUtils.performHapticFeedback(it)
            val number = dialedDigits.toString()
            val intent = Intent(Intent.ACTION_INSERT).apply {
                type = ContactsContract.Contacts.CONTENT_TYPE
                if (number.isNotBlank()) {
                    putExtra(ContactsContract.Intents.Insert.PHONE, number)
                }
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Unable to open contacts app", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadContactsData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val contacts = DialerUtils.loadContacts(requireContext())
            withContext(Dispatchers.Main) {
                allContacts = contacts
                filterSuggestions()
            }
        }
    }

    private fun updateDialDisplay() {
        val number = dialedDigits.toString()
        val formatted = DialerUtils.formatPhoneNumber(number)
        binding.tvDialedNumber.text = formatted

        val hasInput = number.isNotEmpty()
        binding.btnBackspace.visibility = if (hasInput) View.VISIBLE else View.INVISIBLE
        binding.btnDisplayBackspace.visibility = if (hasInput) View.VISIBLE else View.GONE

        val matchedName = findMatchedContactName(number)
        if (hasInput && matchedName != null) {
            binding.tvMatchedContactName.text = matchedName
            binding.tvMatchedContactName.visibility = View.VISIBLE
        } else {
            binding.tvMatchedContactName.visibility = View.GONE
        }

        filterSuggestions()
    }

    private fun filterSuggestions() {
        val query = dialedDigits.toString().trim()
        if (query.isEmpty()) {
            suggestionAdapter.submitList(emptyList())
            return
        }

        val filtered = allContacts.filter { contact ->
            val cleanPhone = contact.phoneNumber.replace(Regex("[^0-9+]"), "")
            cleanPhone.contains(query) || contact.t9Digits.contains(query) || contact.name.lowercase().contains(query.lowercase())
        }.take(5)

        suggestionAdapter.submitList(filtered)
    }

    private fun findMatchedContactName(number: String): String? {
        val cleanInput = number.replace(Regex("[^0-9+]"), "")
        if (cleanInput.isEmpty()) return null

        return allContacts.firstOrNull {
            val cleanPhone = it.phoneNumber.replace(Regex("[^0-9+]"), "")
            cleanPhone == cleanInput || (cleanInput.length >= 7 && cleanPhone.endsWith(cleanInput))
        }?.name
    }

    private fun placeCall(phoneNumber: String, name: String?) {
        val activity = activity as? MainActivity ?: return

        // Save call log in Room database
        val callLog = CallLogEntity(
            phoneNumber = phoneNumber,
            callerName = name,
            callType = "OUTGOING",
            timestamp = System.currentTimeMillis(),
            durationSeconds = (5..120).random().toLong()
        )

        lifecycleScope.launch(Dispatchers.IO) {
            activity.database.callLogDao().insertCallLog(callLog)
        }

        // Trigger real action call intent if permission granted, or simulate in-call dialog
        if (DialerUtils.hasCallPermission(requireContext())) {
            try {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))
                startActivity(intent)
            } catch (e: Exception) {
                activity.showInCallDialog(phoneNumber, name)
            }
        } else {
            // Offer direct simulated call experience or request permission
            activity.showInCallDialog(phoneNumber, name)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
