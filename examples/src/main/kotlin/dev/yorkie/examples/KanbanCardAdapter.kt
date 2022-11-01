package dev.yorkie.examples

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.yorkie.examples.databinding.KanbanCardBinding

class KanbanCardAdapter(
    private val itemsListener: (ViewGroup, Pair<String, List<String>>) -> Unit,
    private val itemAddListener: (ViewGroup, String, String) -> Unit,
    private val cardDeleteListener: (String) -> Unit,
) : RecyclerView.Adapter<KanbanCardAdapter.KanbanCardViewHolder>() {
    private var kanbanList = mapOf<String, List<String>>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): KanbanCardViewHolder {
        return KanbanCardViewHolder(
            KanbanCardBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            ),
            itemsListener,
        )
    }

    override fun onBindViewHolder(holder: KanbanCardViewHolder, position: Int) {
        holder.bind(kanbanList.toList()[position])
        with(holder.binding) {
            btnAddItem.setOnClickListener {
                val item = etContent.text.toString()
                if (item.isEmpty()) return@setOnClickListener
                itemAddListener.invoke(contentList, tvTitle.text.toString(), item)
                etContent.setText("")
            }
            btnDeleteCard.setOnClickListener {
                cardDeleteListener.invoke(tvTitle.text.toString())
            }
        }
    }

    override fun getItemCount(): Int {
        return kanbanList.size
    }

    fun setKanbans(kanbanList: Map<String, List<String>>) {
        this.kanbanList = kanbanList
    }

    class KanbanCardViewHolder(
        val binding: KanbanCardBinding,
        private val itemsListener: (ViewGroup, Pair<String, List<String>>) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(card: Pair<String, List<String>>) {
            binding.tvTitle.text = card.first
            itemsListener.invoke(binding.contentList, card)
        }
    }
}
