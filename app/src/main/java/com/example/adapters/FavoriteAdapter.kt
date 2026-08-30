package com.example.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.data.ContactItem
import com.example.databinding.ItemFavoriteBinding
import com.example.utils.DialerUtils
import com.example.utils.LogRecorder

class FavoriteAdapter(
    private val onItemClick: (ContactItem) -> Unit,
    private val onCallClick: (ContactItem) -> Unit,
    private val onRemoveClick: (ContactItem) -> Unit
) : ListAdapter<ContactItem, FavoriteAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(val binding: ItemFavoriteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFavoriteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.tvContactName.text = item.name
        holder.binding.tvPhoneNumber.text = DialerUtils.formatPhoneNumber(item.phoneNumber)
        holder.binding.tvAvatarText.text = item.name.take(1).uppercase()

        holder.itemView.setOnClickListener {
            LogRecorder.logDebug(TAG, "Favorite item clicked: ${item.name}")
            onItemClick(item)
        }
        holder.binding.btnFavoriteCall.setOnClickListener {
            LogRecorder.logDebug(TAG, "Favorite call button clicked: ${item.name}")
            onCallClick(item)
        }
        holder.binding.btnRemoveFavorite.setOnClickListener {
            LogRecorder.logDebug(TAG, "Favorite remove button clicked: ${item.name}")
            onRemoveClick(item)
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ContactItem>() {
        private const val TAG = "FavoriteAdapter"

        override fun areItemsTheSame(oldItem: ContactItem, newItem: ContactItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ContactItem, newItem: ContactItem): Boolean {
            return oldItem == newItem
        }
    }
}
