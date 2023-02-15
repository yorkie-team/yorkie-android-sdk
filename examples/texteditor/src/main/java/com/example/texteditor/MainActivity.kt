package com.example.texteditor

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.texteditor.databinding.ActivityMainBinding
import dev.yorkie.core.Client

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
        binding = ActivityMainBinding.inflate(layoutInflater).apply {
            viewModel = this@MainActivity.viewModel
        }
        binding.lifecycleOwner = this
        setContentView(binding.root)
    }
}
