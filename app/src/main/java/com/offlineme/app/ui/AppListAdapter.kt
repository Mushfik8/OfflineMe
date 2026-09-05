package com.offlineme.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.offlineme.app.databinding.ItemAppBinding

class AppListAdapter(
    private val onToggle: (packageName: String) -> Unit
) : ListAdapter<AppInfo, AppListAdapter.AppViewHolder>(AppDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AppViewHolder(
        private val binding: ItemAppBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppInfo) {
            binding.appIcon.setImageDrawable(app.icon)
            binding.appLabel.text = app.label
            binding.appPackage.text = app.packageName

            // Remove listener before setting checked state to avoid triggering callbacks
            binding.appSwitch.setOnCheckedChangeListener(null)
            binding.appSwitch.isChecked = app.isBlocked

            binding.appSwitch.setOnCheckedChangeListener { _, _ ->
                onToggle(app.packageName)
            }

            // Make the whole row tappable to toggle
            binding.root.setOnClickListener {
                binding.appSwitch.toggle()
            }
        }
    }

    private class AppDiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
            oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
            oldItem.isBlocked == newItem.isBlocked && oldItem.label == newItem.label
    }
}
