package com.example.texteditor

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent.ACTION_UP
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.text.toSpannable
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.doOnTextChanged

@SuppressLint("ClickableViewAccessibility")
class YorkieEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatEditText(context, attrs) {
    var textEventHandler: TextEventHandler? = null

    private var applyingRemoteChange = false

    lateinit var ic: CustomInputConnection

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

        setOnTouchListener { _, event ->
            if (event.action == ACTION_UP && ::ic.isInitialized) {
                ic.maybeFinishComposing()
            }
            false
        }
    }

    fun withRemoteChange(action: (YorkieEditText) -> Unit) {
        applyingRemoteChange = true
        action.invoke(this)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        textEventHandler?.handleSelectEvent(selStart, selEnd)
        super.onSelectionChanged(selStart, selEnd)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val delegate = super.onCreateInputConnection(outAttrs) ?: return null
        return CustomInputConnection(this, delegate).also {
            ic = it
        }
    }

    interface TextEventHandler {

        fun handleEditEvent(from: Int, to: Int, content: CharSequence)

        fun handleSelectEvent(from: Int, to: Int)
    }
}
