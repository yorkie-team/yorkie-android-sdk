package com.example.texteditor

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.texteditor.databinding.ActivityMainBinding
import dev.yorkie.core.Client
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
            launch {
                viewModel.content.collect { content ->
                    binding.textEditor.withRemoteChange {
                        it.setText(content)
                    }
                }
            }

            launch {
                viewModel.textChanges.collect {
                    val editable = binding.textEditor.text ?: return@collect
                    binding.textEditor.withRemoteChange { _ ->
                        if (it.from == it.to) {
                            editable.insert(it.from.coerceAtLeast(0), it.content.orEmpty())
                        } else {
                            editable.replace(
                                it.from.coerceAtLeast(0),
                                it.to.coerceAtLeast(0),
                                it.content.orEmpty(),
                            )
                        }
                    }
                }
            }
        }
    }
}
