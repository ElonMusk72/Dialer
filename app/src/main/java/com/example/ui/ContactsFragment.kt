package com.example.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.MainActivity
import com.example.adapters.ContactAdapter
import com.example.data.CallLogEntity
import com.example.data.ContactItem
import com.example.databinding.FragmentContactsBinding
import com.example.utils.DialerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ContactAdapter
    private var allContactsList: MutableList<ContactItem> = mutableListOf()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSearch()
        setupFab()
        loadContacts()
    }

    private fun setupRecyclerView() {
        adapter = ContactAdapter(
            onItemClick = { contact ->
                showContactOptionsDialog(contact)
            },
            onCallClick = { contact ->
                placeCall(contact.phoneNumber, contact.name)
            },
            onFavoriteClick = { contact ->
                toggleFavorite(contact)
            }
        )

        binding.rvContacts.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ContactsFragment.adapter
        }
    }

    private fun setupSearch() {
        binding.etSearchContacts.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterContacts(s?.toString() ?: "")
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupFab() {
        binding.fabAddContact.setOnClickListener {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                type = ContactsContract.Contacts.CONTENT_TYPE
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Unable to open contacts app", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadContacts() {
        lifecycleScope.launch(Dispatchers.IO) {
            val contacts = DialerUtils.loadContacts(requireContext()).toMutableList()
            withContext(Dispatchers.Main) {
                allContactsList = contacts
                filterContacts(binding.etSearchContacts.text?.toString() ?: "")
            }
        }
    }

    private fun filterContacts(query: String) {
        val filtered = if (query.isBlank()) {
            allContactsList
        } else {
            val q = query.lowercase().trim()
            allContactsList.filter {
                it.name.lowercase().contains(q) ||
                it.phoneNumber.replace(Regex("[^0-9+]"), "").contains(q) ||
                it.t9Digits.contains(q)
            }
        }

        if (filtered.isEmpty()) {
            binding.rvContacts.visibility = View.GONE
            binding.emptyStateContainer.visibility = View.VISIBLE
        } else {
            binding.rvContacts.visibility = View.VISIBLE
            binding.emptyStateContainer.visibility = View.GONE
            adapter.submitList(filtered)
        }
    }

    private fun toggleFavorite(contact: ContactItem) {
        contact.isFavorite = !contact.isFavorite
        adapter.notifyDataSetChanged()

        val msg = if (contact.isFavorite) {
            "${contact.name} added to Speed Dial"
        } else {
            "${contact.name} removed from Speed Dial"
        }
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

        val activity = activity as? MainActivity
        activity?.notifyFavoritesUpdated()
    }

    private fun showContactOptionsDialog(contact: ContactItem) {
        val favLabel = if (contact.isFavorite) "Remove from Speed Dial" else "Add to Speed Dial"
        val options = arrayOf("Call ${contact.phoneNumber}", "Send Message", favLabel)

        AlertDialog.Builder(requireContext())
            .setTitle(contact.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> placeCall(contact.phoneNumber, contact.name)
                    1 -> {
                        val smsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("sms:${contact.phoneNumber}"))
                        try {
                            startActivity(smsIntent)
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "Unable to send SMS", Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> toggleFavorite(contact)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun placeCall(phoneNumber: String, name: String?) {
        val activity = activity as? MainActivity ?: return

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

        if (DialerUtils.hasCallPermission(requireContext())) {
            try {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))
                startActivity(intent)
            } catch (e: Exception) {
                activity.showInCallDialog(phoneNumber, name)
            }
        } else {
            activity.showInCallDialog(phoneNumber, name)
        }
    }

    fun getAllContacts(): List<ContactItem> = allContactsList

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
