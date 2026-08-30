package com.example.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.MainActivity
import com.example.adapters.FavoriteAdapter
import com.example.data.CallLogEntity
import com.example.data.ContactItem
import com.example.databinding.FragmentFavoritesBinding
import com.example.utils.DialerUtils
import com.example.utils.LogRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FavoritesFragment : Fragment() {

    companion object {
        private const val TAG = "FavoritesFragment"
    }

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: FavoriteAdapter
    private var favoriteContactsList: MutableList<ContactItem> = mutableListOf()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        LogRecorder.logInfo(TAG, "FavoritesFragment view created")
        setupRecyclerView()
        loadFavorites()
    }

    private fun setupRecyclerView() {
        adapter = FavoriteAdapter(
            onItemClick = { contact ->
                placeCall(contact.phoneNumber, contact.name)
            },
            onCallClick = { contact ->
                placeCall(contact.phoneNumber, contact.name)
            },
            onRemoveClick = { contact ->
                removeFavorite(contact)
            }
        )

        binding.rvFavorites.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = this@FavoritesFragment.adapter
        }
    }

    fun loadFavorites() {
        lifecycleScope.launch(Dispatchers.IO) {
            val allContacts = DialerUtils.loadContacts(requireContext())
            val favorites = allContacts.filter { it.isFavorite }.toMutableList()

            withContext(Dispatchers.Main) {
                favoriteContactsList = favorites
                if (favoriteContactsList.isEmpty()) {
                    binding.rvFavorites.visibility = View.GONE
                    binding.emptyStateContainer.visibility = View.VISIBLE
                } else {
                    binding.rvFavorites.visibility = View.VISIBLE
                    binding.emptyStateContainer.visibility = View.GONE
                    adapter.submitList(favoriteContactsList.toList())
                }
            }
        }
    }

    private fun removeFavorite(contact: ContactItem) {
        contact.isFavorite = false
        favoriteContactsList.remove(contact)

        LogRecorder.logInfo(TAG, "Removed contact from favorites: ${contact.name}")

        if (favoriteContactsList.isEmpty()) {
            binding.rvFavorites.visibility = View.GONE
            binding.emptyStateContainer.visibility = View.VISIBLE
        } else {
            adapter.submitList(favoriteContactsList.toList())
        }

        Toast.makeText(requireContext(), "${contact.name} removed from Speed Dial", Toast.LENGTH_SHORT).show()

        val activity = activity as? MainActivity
        activity?.notifyFavoritesUpdated()
    }

    private fun placeCall(phoneNumber: String, name: String?) {
        val activity = activity as? MainActivity ?: return
        activity.makeCall(phoneNumber, name)
    }

    override fun onResume() {
        super.onResume()
        loadFavorites()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
