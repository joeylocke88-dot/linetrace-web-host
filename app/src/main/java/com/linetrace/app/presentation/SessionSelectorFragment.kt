package com.linetrace.app.presentation

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.linetrace.app.feature.sync.ImuNetworkBridge
import com.linetrace.app.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionSelectorFragment(
    private val sessions: List<File>,
    private val networkBridge: ImuNetworkBridge? = null,
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
            textSize = 16f
            setPadding(64, 64, 64, 16)
            typeface = android.graphics.Typeface.MONOSPACE
            paintFlags = paintFlags or android.graphics.Paint.FAKE_BOLD_TEXT_FLAG
            
            // Live Status Poller
            val updateStatus = object : Runnable {
                override fun run() {
                    val connected = networkBridge?.isConnected == true
                    val status = if (connected) " [ONLINE]" else " [OFFLINE]"
                    text = "> MISSION LOGS_V2.0$status"
                    setTextColor(if (connected) 
                        ContextCompat.getColor(context, R.color.tactical_cyan) else Color.RED)
                    
                    if (isAdded) {
                        handler.postDelayed(this, 1000)
                    }
                }
            }
            handler.post(updateStatus)
        }
        root.addView(title)

        // Tactical separator
        root.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(64, 0, 64, 16)
            }
            setBackgroundColor(ContextCompat.getColor(context, R.color.tactical_cyan))
            alpha = 0.3f
        })

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
            val indicator: View = view.findViewById(R.id.sessionStatusIndicator)
            val duration: TextView = view.findViewById(R.id.sessionDuration)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.session_list_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = items[position]
            // Clean up name for tactical display
            holder.name.text = file.nameWithoutExtension.uppercase()
            
            val date = Date(file.lastModified())
            val size = file.length() / 1024
            
            // Format: [DATE] | [SIZE]
            holder.details.text = "[${dateFormat.format(date).uppercase()}] | ${size}KB"
            
            // Heuristic for duration based on file size (since it's binary surfel data)
            // Assuming ~64 bytes per surfel, and maybe 30 surfels per second on average
            val estimatedSeconds = size * 1024 / (64 * 30)
            val mins = estimatedSeconds / 60
            val secs = estimatedSeconds % 60
            holder.duration.text = String.format("%02d:%02d", mins, secs)

            // Visual indicator: Cyan for recent (last hour), dimmed for older
            val ageMs = System.currentTimeMillis() - file.lastModified()
            if (ageMs < 3600000) {
                holder.indicator.setBackgroundColor(Color.parseColor("#00FFFF"))
                holder.indicator.alpha = 1.0f
            } else {
                holder.indicator.setBackgroundColor(Color.parseColor("#008888"))
                holder.indicator.alpha = 0.5f
            }

            holder.itemView.setOnClickListener {
                holder.itemView.alpha = 0.5f
                onClick(file)
                // Dismiss on main thread to avoid window leak/sync issues
                Handler(Looper.getMainLooper()).post { dismiss() }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
