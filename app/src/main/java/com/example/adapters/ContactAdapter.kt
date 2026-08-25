package com.example.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.ContactItem
import com.example.databinding.ItemContactBinding
import com.example.utils.DialerUtils

class ContactAdapter(
    private val onItemClick: (ContactItem) -> Unit,
    private val onCallClick: (ContactItem) -> Unit,
    private val onFavoriteClick: (ContactItem) -> Unit
) : ListAdapter<ContactItem, ContactAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(val binding: ItemContactBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContactBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context

        holder.binding.tvContactName.text = item.name
        holder.binding.tvPhoneNumber.text = DialerUtils.formatPhoneNumber(item.phoneNumber)
        holder.binding.tvAvatarText.text = item.name.take(1).uppercase()

        if (item.isFavorite) {
            holder.binding.btnFavorite.setImageResource(R.drawable.ic_star_filled)
            holder.binding.btnFavorite.setColorFilter(ContextCompat.getColor(context, R.color.gold_star))
        } else {
            holder.binding.btnFavorite.setImageResource(R.drawable.ic_star)
            holder.binding.btnFavorite.setColorFilter(ContextCompat.getColor(context, R.color.text_secondary))
        }

        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.binding.btnCall.setOnClickListener { onCallClick(item) }
        holder.binding.btnFavorite.setOnClickListener { onFavoriteClick(item) }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ContactItem>() {
        override fun areItemsTheSame(oldItem: ContactItem, newItem: ContactItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ContactItem, newItem: ContactItem): Boolean {
            return oldItem == newItem
        }
    }
}
