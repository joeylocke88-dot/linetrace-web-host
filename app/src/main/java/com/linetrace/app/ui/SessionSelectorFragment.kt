package com.linetrace.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.linetrace.app.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionSelectorFragment(
    private val sessions: List<File>,
    private val onSessionSelected: (File) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundResource(R.color.tactical_bg)
        }

        val title = TextView(requireContext()).apply {
            text = "MISSION LOGS"
            setTextColor(context.getColor(R.color.tactical_cyan))
            textSize = 18f
            setPadding(64, 64, 64, 32)
            typeface = android.graphics.Typeface.MONOSPACE
            paintFlags = paintFlags or android.graphics.Paint.FAKE_BOLD_TEXT_FLAG
        }
        root.addView(title)

        val recyclerView = RecyclerView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutManager = LinearLayoutManager(context)
            adapter = SessionAdapter(sessions) {
                onSessionSelected(it)
                // Dismiss on main thread to avoid window leak/sync issues
                Handler(Looper.getMainLooper()).post { dismiss() }
            }
        }
        root.addView(recyclerView)
        return root
    }

    inner class SessionAdapter(
        private val items: List<File>,
        private val onClick: (File) -> Unit
    ) : RecyclerView.Adapter<SessionAdapter.ViewHolder>() {

        private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.sessionName)
            val details: TextView = view.findViewById(R.id.sessionDetails)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.session_list_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = items[position]
            holder.name.text = file.name
            val date = Date(file.lastModified())
            val size = file.length() / 1024
            holder.details.text = "${dateFormat.format(date)} • ${size}KB"
            holder.itemView.setOnClickListener { onClick(file) }
        }

        override fun getItemCount(): Int = items.size
    }
}
