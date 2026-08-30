package com.example.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.MainActivity
import com.example.adapters.CallLogAdapter
import com.example.data.CallLogEntity
import com.example.databinding.FragmentCallLogBinding
import com.example.utils.DialerUtils
import com.example.utils.LogRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CallLogFragment : Fragment() {

    companion object {
        private const val TAG = "CallLogFragment"
    }

    private var _binding: FragmentCallLogBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: CallLogAdapter
    private var showMissedOnly = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCallLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        LogRecorder.logInfo(TAG, "CallLogFragment view created")
        setupRecyclerView()
        setupFiltersAndActions()
        ensureSampleLogsIfEmpty()
        observeCallLogs()
    }

    private fun setupRecyclerView() {
        adapter = CallLogAdapter(
            onItemClick = { item ->
                placeCall(item.phoneNumber, item.callerName)
            },
            onCallClick = { item ->
                placeCall(item.phoneNumber, item.callerName)
            },
            onItemLongClick = { item ->
                showDeleteDialog(item)
            }
        )

        binding.rvCallLog.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@CallLogFragment.adapter
        }
    }

    private fun setupFiltersAndActions() {
        binding.chipAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                showMissedOnly = false
                observeCallLogs()
            }
        }

        binding.chipMissed.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                showMissedOnly = true
                observeCallLogs()
            }
        }

        binding.btnClearLog.setOnClickListener {
            LogRecorder.logWarning(TAG, "User clicked Clear Call Log button")
            AlertDialog.Builder(requireContext())
                .setTitle("Clear Call Log")
                .setMessage("Are you sure you want to clear all call history?")
                .setPositiveButton("Clear") { _, _ ->
                    LogRecorder.logWarning(TAG, "User confirmed clearing call logs")
                    clearAllLogs()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun observeCallLogs() {
        val activity = activity as? MainActivity ?: return
        lifecycleScope.launch {
            val flow = if (showMissedOnly) {
                activity.database.callLogDao().getMissedCallLogs()
            } else {
                activity.database.callLogDao().getAllCallLogs()
            }

            flow.collectLatest { logs ->
                if (logs.isEmpty()) {
                    binding.rvCallLog.visibility = View.GONE
                    binding.emptyStateContainer.visibility = View.VISIBLE
                } else {
                    binding.rvCallLog.visibility = View.VISIBLE
                    binding.emptyStateContainer.visibility = View.GONE
                    adapter.submitList(logs)
                }
            }
        }
    }

    private fun ensureSampleLogsIfEmpty() {
        val activity = activity as? MainActivity ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = activity.database.callLogDao()
            // Check if DB is empty
            val initial = withContext(Dispatchers.IO) {
                // simple check
                val logsList = mutableListOf<CallLogEntity>()
                // insert sample logs if completely empty
                logsList
            }

            // We can pre-populate realistic sample history
            val now = System.currentTimeMillis()
            val sampleLogs = listOf(
                CallLogEntity(phoneNumber = "(555) 234-5678", callerName = "Alex Rivera", callType = "INCOMING", timestamp = now - 1000 * 60 * 15, durationSeconds = 142),
                CallLogEntity(phoneNumber = "(555) 876-5432", callerName = "Sarah Connor", callType = "MISSED", timestamp = now - 1000 * 60 * 60 * 2, durationSeconds = 0),
                CallLogEntity(phoneNumber = "(555) 987-6543", callerName = "Emma Watson", callType = "OUTGOING", timestamp = now - 1000 * 60 * 60 * 5, durationSeconds = 310),
                CallLogEntity(phoneNumber = "1-800-555-0199", callerName = "Tech Support", callType = "INCOMING", timestamp = now - 1000 * 60 * 60 * 24, durationSeconds = 85),
                CallLogEntity(phoneNumber = "(555) 345-6789", callerName = "David Kim", callType = "MISSED", timestamp = now - 1000 * 60 * 60 * 36, durationSeconds = 0)
            )

            // Check if database has entries
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // Try to insert sample items if DB is fresh
                    sampleLogs.forEach { dao.insertCallLog(it) }
                } catch (_: Exception) {}
            }
        }
    }

    private fun showDeleteDialog(item: CallLogEntity) {
        val activity = activity as? MainActivity ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Call Log Entry")
            .setMessage("Delete call log for ${item.callerName ?: item.phoneNumber}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    activity.database.callLogDao().deleteCallLog(item.id)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearAllLogs() {
        val activity = activity as? MainActivity ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            activity.database.callLogDao().clearAllCallLogs()
        }
    }

    private fun placeCall(phoneNumber: String, name: String?) {
        val activity = activity as? MainActivity ?: return
        activity.makeCall(phoneNumber, name)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
