package com.example.texteditor

import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.util.Log
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.getSpans
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.texteditor.EditorViewModel.Selection
import com.example.texteditor.databinding.ActivityMainBinding
import dev.yorkie.core.Client
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.time.ActorID
import dev.yorkie.util.Logger
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: EditorViewModel by viewModels {
        viewModelFactory {
            initializer {
                val client = Client("https://api.yorkie.dev")
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
                viewModel.editOpInfos.collect { opInfo ->
                    opInfo.handleContentChange()
                }
            }

            launch {
                viewModel.removedPeers.collect {
                    binding.textEditor.text?.removePrevSpan(it)
                    viewModel.removeUnwatchedPeerSelectionInfo(it)
                }
            }

            launch {
                viewModel.selections.collect { selection ->
                    selection.handleSelectChange()
                }
            }
        }
    }

    private fun OperationInfo.EditOpInfo.handleContentChange() {
        binding.textEditor.withRemoteChange {
            if (from == to) {
                it.text.insert(from.coerceAtLeast(0), value.text)
            } else {
                it.text.replace(
                    from.coerceAtLeast(0),
                    to.coerceAtLeast(0),
                    value.text,
                )
            }
        }
    }

    private fun Selection.handleSelectChange() {
        val editable = binding.textEditor.text ?: return

        editable.removePrevSpan(clientID)
        editable.setSpan(
            BackgroundColorSpan(viewModel.getPeerSelectionColor(clientID)),
            from.coerceAtMost(to),
            to.coerceAtLeast(from),
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun Editable.removePrevSpan(actorID: ActorID): Boolean {
        val backgroundSpan = getSpans<BackgroundColorSpan>(0, length).firstOrNull {
            it.backgroundColor == viewModel.selectionColors[actorID]
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

        init {
            Logger.init(
                object : Logger {
                    override val minimumPriority: Int = Log.DEBUG

                    override fun d(
                        tag: String,
                        message: String?,
                        throwable: Throwable?,
                    ) {
                        Log.d(tag, message, throwable)
                    }

                    override fun e(
                        tag: String,
                        message: String?,
                        throwable: Throwable?,
                    ) {
                        Log.e(tag, message, throwable)
                    }
                },
            )
        }
    }
}
