package com.example.texteditor

import android.content.Context
import android.util.AttributeSet
import android.widget.EditText
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.text.toSpannable
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.doOnTextChanged

class YorkieEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatEditText(context, attrs) {
    var textEventHandler: TextEventHandler? = null

    private var applyingRemoteChange = false

    init {
        doOnTextChanged { text, start, before, count ->
            if (applyingRemoteChange) {
                return@doOnTextChanged
            }
            val spannable = text?.subSequence(
                start,
                (start + count),
            )?.toSpannable() ?: return@doOnTextChanged

            textEventHandler?.handleEditEvent(
                start,
                if (before == 0) start else start + before,
                spannable,
            )
        }

        doAfterTextChanged {
            applyingRemoteChange = false
        }
    }

    fun withRemoteChange(action: (EditText) -> Unit) {
        applyingRemoteChange = true
        action.invoke(this)
    }

    interface TextEventHandler {

        fun handleEditEvent(from: Int, to: Int, content: CharSequence)
    }
}
