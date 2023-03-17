package com.example.texteditor

import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.view.inputmethod.BaseInputConnection.getComposingSpanEnd
import android.view.inputmethod.BaseInputConnection.getComposingSpanStart
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.textEditor.textEventHandler = viewModel

        lifecycleScope.launch {
            viewModel.syncText()
            launch {
                viewModel.content.collect { content ->
                    binding.textEditor.withRemoteChange {
                        it.setText(content)
                    }
                    savedInstanceState?.let {
                        binding.textEditor.setSelection(it.getInt(SELECTION_END))
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

            launch {
                viewModel.removedPeers.collect { peers ->
                    peers.forEach {
                        binding.textEditor.text?.removePrevSpan(it)
                        viewModel.removeDetachedPeerSelectionInfo(it)
                    }
                }
            }
        }
    }

    private fun TextChange.handleContentChange() {
        binding.textEditor.withRemoteChange {
            val editable = it.text ?: return@withRemoteChange
            val prevComposing = getComposingSpanStart(editable)..getComposingSpanEnd(editable)
            if (it.ic.isGboard && to <= prevComposing.max() && prevComposing.min() >= 0) {
                val content = content.orEmpty()
                val prevComposingText = editable.subSequence(from, prevComposing.max())
                val composingText = SpannableStringBuilder(prevComposingText)
                if (from == to) {
                    composingText.insert(0, content)
                } else {
                    composingText.replace(0, to - from, content)
                }
                val lengthDiff = composingText.length - prevComposingText.length
                it.ic.withRemoteChange {
                    beginBatchEdit()
                    setComposingRegion(from, prevComposing.max())
                    if (from in prevComposing || to in prevComposing) {
                        commitText(composingText, 1)
                        setComposingRegion(
                            prevComposing.max() + lengthDiff,
                            prevComposing.max() + lengthDiff,
                        )
                        getSystemService<InputMethodManager>()?.restartInput(it)
                    } else {
                        setComposingText(composingText, 1)
                        setComposingRegion(
                            prevComposing.min() + lengthDiff,
                            prevComposing.max() + lengthDiff,
                        )
                    }
                }
            } else if (from == to) {
                editable.insert(from.coerceAtLeast(0), content.orEmpty())
            } else {
                editable.replace(
                    from.coerceAtLeast(0),
                    to.coerceAtLeast(from),
                    content.orEmpty(),
                )
            }
        }
    }

    private fun TextChange.handleSelectChange() {
        val editable = binding.textEditor.text ?: return

        if (editable.removePrevSpan(actor) && from == to) {
            viewModel.updatePeerPrevSelection(actor, null)
        } else if (from < to) {
            editable.setSpan(
                BackgroundColorSpan(viewModel.getPeerSelectionColor(actor)),
                from,
                to,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            viewModel.updatePeerPrevSelection(actor, from to to)
        }
    }

    private fun Editable.removePrevSpan(actorID: ActorID): Boolean {
        val (start, end) = viewModel.peerSelectionInfos[actorID]?.prevSelection ?: return false
        val backgroundSpan = getSpans<BackgroundColorSpan>(start, end).firstOrNull {
            it.backgroundColor == viewModel.peerSelectionInfos[actorID]?.color
        }
        backgroundSpan?.let(::removeSpan)
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(SELECTION_END, binding.textEditor.selectionEnd)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val SELECTION_END = "selection end"
    }
}
