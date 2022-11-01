package dev.yorkie.examples

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import dev.yorkie.core.Client
import dev.yorkie.examples.databinding.ActivityKanbanBinding
import dev.yorkie.examples.databinding.KanbanItemBinding
import kotlinx.coroutines.launch

class KanbanActivity : AppCompatActivity() {
    private lateinit var binding: ActivityKanbanBinding
    private val viewModel: KanbanViewModel by lazy {
        ViewModelProvider(this)[KanbanViewModel::class.java]
    }
    private val kanbanCardAdapter =
        KanbanCardAdapter(::itemsListener, ::addItemToCard, ::deleteCard)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKanbanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initViews()
    }

    private fun initViews() {
        viewModel.initialSetUp(this@KanbanActivity)
        binding.kanbanList.adapter = kanbanCardAdapter
        binding.btnAddKanbanCard.setOnClickListener { addCard(binding.etAddTitle.text.toString()) }
        lifecycleScope.launch {
            viewModel.client.collect {
                if (it is Client.Event.DocumentSynced) {
                    viewModel.updateDocument(it.value.document.getRoot()[DOCUMENT_LIST_KEY])
                }
            }
        }
        lifecycleScope.launch {
            viewModel.list.collect {
                kanbanCardAdapter.setKanbans(it)
                kanbanCardAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun itemsListener(view: ViewGroup, card: Pair<String, List<String>>) {
        view.removeAllViews()
        card.second.forEach {
            KanbanItemBinding.inflate(layoutInflater).apply {
                tvContent.text = it
            }.also {
                view.addView(it.root)
            }
        }
    }

    private fun addItemToCard(view: ViewGroup, title: String, item: String) {
        if (item.isEmpty()) return
        viewModel.addItem(title, item)
    }

    private fun addCard(title: String) {
        binding.etAddTitle.setText("")
        viewModel.addCard(title)
    }

    private fun deleteCard(title: String) {
        viewModel.deleteCard(title)
    }
}
