package com.example.moma

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 聊天消息适配器：user / assistant 两种气泡布局。
 * 长按消息弹出菜单：复制 / 重新生成（AI）/ 重发（用户）。
 */
class ChatAdapter(
    private val messages: MutableList<Message>,
    private val onCopy: (Message) -> Unit,
    private val onRegenerate: (Int) -> Unit,
    private val onResend: (Int) -> Unit,
) : RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
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
        val msg = messages[position]
        holder.text.text = msg.content
        holder.itemView.setOnLongClickListener { v ->
            showMenu(v, position)
            true
        }
    }

    private fun showMenu(anchor: View, position: Int) {
        val popup = PopupMenu(anchor.context, anchor)
        popup.menu.add(0, 1, 0, "复制")
        if (messages[position].role == "assistant") {
            popup.menu.add(0, 2, 1, "重新生成")
        } else {
            popup.menu.add(0, 3, 1, "重新发送")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> onCopy(messages[position])
                2 -> onRegenerate(position)
                3 -> onResend(position)
            }
            true
        }
        popup.show()
    }

    override fun getItemCount(): Int = messages.size
}
