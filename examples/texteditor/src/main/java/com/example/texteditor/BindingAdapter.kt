package com.example.texteditor

import androidx.databinding.BindingAdapter

object BindingAdapter {

    @JvmStatic
    @BindingAdapter("onTextEvent")
    fun setOnTextEventHandler(editText: YorkieEditText, handler: YorkieEditText.TextEventHandler?) {
        editText.textEventHandler = handler
    }
}
