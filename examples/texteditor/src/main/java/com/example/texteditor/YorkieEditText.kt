package com.example.texteditor

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.doOnTextChanged

@SuppressLint("ClickableViewAccessibility")
class YorkieEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatEditText(context, attrs) {

    var textEventHandler: TextEventHandler? = null
    private var fromRemote = false
    private var cursorPos = 0

    init {
        fromRemote = false

        doOnTextChanged { _, start, _, _ ->
            if (!fromRemote) {
                cursorPos = start
            }
        }

        doAfterTextChanged {
            if (fromRemote) {
                fromRemote = false
                return@doAfterTextChanged
            }

            val text = it?.toString() ?: ""
            textEventHandler?.handleEditEvent(0, (text.length - 1).coerceAtLeast(0), text)
        }
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        fromRemote = true
        super.setText(text, type)
        setSelection(cursorPos.coerceAtMost(text?.length ?: 0))
    }

    interface TextEventHandler {

        fun handleEditEvent(from: Int, to: Int, content: String)
    }
}
