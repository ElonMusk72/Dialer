package com.example.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.CallLogEntity
import com.example.databinding.ItemCallLogBinding
import com.example.utils.DialerUtils

class CallLogAdapter(
    private val onItemClick: (CallLogEntity) -> Unit,
    private val onCallClick: (CallLogEntity) -> Unit,
    private val onItemLongClick: (CallLogEntity) -> Unit
) : ListAdapter<CallLogEntity, CallLogAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(val binding: ItemCallLogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCallLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context

        val displayName = item.callerName ?: DialerUtils.formatPhoneNumber(item.phoneNumber)
        holder.binding.tvCallerTitle.text = displayName
        holder.binding.tvAvatarText.text = displayName.take(1).uppercase()

        if (!item.callerName.isNull_or_blank()) {
            val formatted = DialerUtils.formatPhoneNumber(item.phoneNumber)
            val durationText = DialerUtils.formatDuration(item.durationSeconds)
            holder.binding.tvSubtitle.text = "$formatted • $durationText"
        } else {
            val durationText = DialerUtils.formatDuration(item.durationSeconds)
            holder.binding.tvSubtitle.text = "Duration: $durationText"
        }

        holder.binding.tvTimestamp.text = DialerUtils.formatTimestamp(item.timestamp)

        when (item.callType) {
            "INCOMING" -> {
                holder.binding.ivCallType.setImageResource(R.drawable.ic_incoming_call)
                holder.binding.ivCallType.setColorFilter(ContextCompat.getColor(context, R.color.call_incoming))
            }
            "OUTGOING" -> {
                holder.binding.ivCallType.setImageResource(R.drawable.ic_outgoing_call)
                holder.binding.ivCallType.setColorFilter(ContextCompat.getColor(context, R.color.call_outgoing))
            }
            else -> { // MISSED
                holder.binding.ivCallType.setImageResource(R.drawable.ic_missed_call)
                holder.binding.ivCallType.setColorFilter(ContextCompat.getColor(context, R.color.call_missed))
            }
        }

        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(item)
            true
        }
        holder.binding.btnCall.setOnClickListener { onCallClick(item) }
    }

    private fun String?.isNull_or_blank(): Boolean {
        return this == null || this.trim().isEmpty()
    }

    companion object DiffCallback : DiffUtil.ItemCallback<CallLogEntity>() {
        override fun areItemsTheSame(oldItem: CallLogEntity, newItem: CallLogEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: CallLogEntity, newItem: CallLogEntity): Boolean {
            return oldItem == newItem
        }
    }
}
