package com.example.texteditor

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.widget.EditText
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

    private var isComposing = false

    private var selectionFromTextChange = false

    private val hangulConsonants = '\u3131'..'\u314E'
    private val hangulVowels = '\u314F'..'\u3163'
    private val hangulSyllables = '\uAC00'..'\uD7AF'

    init {
        setOnTouchListener { _, _ ->
            selectionFromTextChange = false
            false
        }

        doOnTextChanged { text, start, before, count ->
            if (applyingRemoteChange) {
                return@doOnTextChanged
            }
            val spannable = text?.subSequence(
                start,
                (start + count),
            )?.toSpannable() ?: return@doOnTextChanged

            handleHangulComposition(spannable)

            textEventHandler?.handleEditEvent(
                start,
                if (before == 0) start else start + before,
                spannable,
            )
        }

        doAfterTextChanged {
            applyingRemoteChange = false
            selectionFromTextChange = true
        }
    }

    private fun handleHangulComposition(content: CharSequence) {
        if (!isComposing && content.isHangulComposing()) {
            isComposing = true
            textEventHandler?.handleHangulCompositionStart()
        } else if (isComposing && !content.isHangulComposing()) {
            isComposing = false
            textEventHandler?.handleHangulCompositionEnd()
        }
    }

    private fun CharSequence.isHangulComposing(): Boolean {
        return lastOrNull() !in hangulVowels && lastOrNull() in hangulConsonants + hangulSyllables
    }

    fun withRemoteChange(action: (EditText) -> Unit) {
        applyingRemoteChange = true
        action.invoke(this)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        if (selectionFromTextChange) {
            selectionFromTextChange = false
            return
        }
        textEventHandler?.handleSelectEvent(selStart, selEnd)
        super.onSelectionChanged(selStart, selEnd)
    }

    interface TextEventHandler {

        fun handleEditEvent(
            from: Int,
            to: Int,
            content: CharSequence,
        )

        fun handleSelectEvent(from: Int, to: Int)

        fun handleHangulCompositionStart()

        fun handleHangulCompositionEnd()
    }
}
