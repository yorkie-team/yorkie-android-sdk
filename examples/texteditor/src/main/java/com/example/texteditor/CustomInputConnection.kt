package com.example.texteditor

import android.provider.Settings
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.content.getSystemService

class CustomInputConnection(
    private val editText: EditText,
    private val delegate: InputConnection,
) : InputConnection by delegate {
    var isApplyingRemoteChange = false
    var hasUnfinishedBatchEdit = false

    val isGboard: Boolean
        get() = Settings.Secure.getString(
            editText.context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        ).startsWith("com.google.android.inputmethod")

    fun withRemoteChange(action: InputConnection.() -> Unit) {
        isApplyingRemoteChange = true
        hasUnfinishedBatchEdit = true
        action.invoke(this)
        isApplyingRemoteChange = false
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (!isApplyingRemoteChange && hasUnfinishedBatchEdit) {
            hasUnfinishedBatchEdit = false
            endBatchEdit()
        }
        return delegate.commitText(text, newCursorPosition)
    }

    override fun beginBatchEdit(): Boolean {
        if (!isApplyingRemoteChange && hasUnfinishedBatchEdit) {
            hasUnfinishedBatchEdit = false
            delegate.endBatchEdit()
        }
        return delegate.beginBatchEdit()
    }

    override fun endBatchEdit(): Boolean {
        if (!isApplyingRemoteChange && hasUnfinishedBatchEdit) {
            hasUnfinishedBatchEdit = false
            delegate.endBatchEdit()
        }
        return delegate.endBatchEdit()
    }

    fun maybeFinishComposing() {
        if (isGboard && hasUnfinishedBatchEdit) {
            editText.context.getSystemService<InputMethodManager>()?.restartInput(editText)
        }
    }
}
