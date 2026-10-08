package com.example.moma

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 聊天消息适配器：user / assistant 两种气泡布局。 */
class ChatAdapter(
    private val messages: MutableList<Message>,
) : RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(android.R.id.text1)
    }

    override fun getItemViewType(position: Int): Int =
        if (messages[position].role == "user") 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == 0) R.layout.item_message_user else R.layout.item_message_ai
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = messages[position].content
    }

    override fun getItemCount(): Int = messages.size
}
