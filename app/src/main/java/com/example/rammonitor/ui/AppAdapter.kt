
package com.example.rammonitor.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.rammonitor.R
import com.example.rammonitor.data.database.AppInfo

class AppAdapter(
    private val onClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

    private val items = mutableListOf<AppInfo>()

    fun submitList(newItems: List<AppInfo>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)

        return AppViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: AppViewHolder,
        position: Int
    ) {
        val item = items[position]
        val context = holder.itemView.context

        holder.label.text = item.app_label
        holder.packageName.text = item.package_name

        holder.type.text =
            if (item.is_system) "СИСТЕМНОЕ" else "ПОЛЬЗОВАТЕЛЬСКОЕ"

        holder.version.text =
            "Версия: ${item.version_name ?: item.version_code.takeIf { it > 0 } ?: "неизвестна"}"

        val icon = try {
            context.packageManager.getApplicationIcon(item.package_name)
        } catch (e: Exception) {
            context.packageManager.defaultActivityIcon
        }

        holder.icon.setImageDrawable(icon)
        holder.icon.contentDescription = "Иконка ${item.app_label}"

        holder.itemView.setOnClickListener {
            onClick(item)
        }
    }

    override fun getItemCount(): Int = items.size

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        val label: TextView = view.findViewById(R.id.tvAppLabel)
        val packageName: TextView = view.findViewById(R.id.tvPackageName)
        val type: TextView = view.findViewById(R.id.tvAppType)
        val icon: ImageView = view.findViewById(R.id.tvAppIcon)
        val version: TextView = view.findViewById(R.id.tvAppVersion)
    }
}
