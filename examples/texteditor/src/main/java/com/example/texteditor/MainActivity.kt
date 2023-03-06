package com.example.texteditor

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import androidx.activity.viewModels
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.getSpans
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.texteditor.databinding.ActivityMainBinding
import dev.yorkie.core.Client
import dev.yorkie.document.crdt.TextChange
import dev.yorkie.document.crdt.TextChangeType
import dev.yorkie.document.time.ActorID
import kotlinx.coroutines.launch
import java.util.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: EditorViewModel by viewModels {
        viewModelFactory {
            initializer {
                val client = Client(this@MainActivity, "api.yorkie.dev", 443)
                EditorViewModel(client)
            }
        }
    }
    private val peerSelectionInfos = mutableMapOf<ActorID, PeerSelectionInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.textEditor.textEventHandler = viewModel
        viewModel.configurationChanged = savedInstanceState != null

        lifecycleScope.launch {
            launch {
                viewModel.content.collect { content ->
                    binding.textEditor.withRemoteChange {
                        it.setText(content)
                    }
                }
            }

            launch {
                viewModel.textChanges.collect {
                    when (it.type) {
                        TextChangeType.Content -> it.handleContentChange()
                        TextChangeType.Selection -> it.handleSelectChange()
                        else -> return@collect
                    }
                }
            }
        }
    }

    private fun TextChange.handleContentChange() {
        binding.textEditor.withRemoteChange {
            if (from == to) {
                it.text.insert(from.coerceAtLeast(0), content.orEmpty())
            } else {
                it.text.replace(
                    from.coerceAtLeast(0),
                    to.coerceAtLeast(0),
                    content.orEmpty(),
                )
            }
        }
    }

    private fun TextChange.handleSelectChange() {
        val editable = binding.textEditor.text ?: return

        if (editable.removePrevSpan(actor) && from == to) {
            val peerSelectionInfo = peerSelectionInfos[actor] ?: return
            peerSelectionInfos[actor] = peerSelectionInfo.copy(prevSelection = null)
        } else if (from < to) {
            editable.setSpan(
                BackgroundColorSpan(getPeerSelectionColor(actor)),
                from,
                to,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            val peerSelectionInfo = peerSelectionInfos[actor] ?: return
            peerSelectionInfos[actor] = peerSelectionInfo.copy(prevSelection = from to to)
        }
    }

    private fun Editable.removePrevSpan(actorID: ActorID): Boolean {
        val (start, end) = peerSelectionInfos[actorID]?.prevSelection ?: return false
        val backgroundSpan = getSpans<BackgroundColorSpan>(start, end).firstOrNull {
            it.backgroundColor == peerSelectionInfos[actorID]?.color
        }
        backgroundSpan?.let(::removeSpan)
        return true
    }

    @ColorInt
    private fun getPeerSelectionColor(actorID: ActorID): Int {
        return peerSelectionInfos[actorID]?.color ?: run {
            with(Random()) {
                val newColor = Color.argb(51, nextInt(256), nextInt(256), nextInt(256))
                peerSelectionInfos[actorID] = PeerSelectionInfo(newColor)
                newColor
            }
        }
    }
}
