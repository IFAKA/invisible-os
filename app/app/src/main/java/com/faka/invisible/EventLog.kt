package com.faka.invisible

import android.os.Handler
import android.os.Looper

/** Simple in-memory list of the last 10 gestures, shared by service and activity. */
object EventLog {
    val items = ArrayList<String>()
    var onChange: (() -> Unit)? = null
    var running = false
    private val main = Handler(Looper.getMainLooper())

    fun add(text: String) {
        main.post {
            items.add(0, text)
            while (items.size > 10) items.removeAt(items.size - 1)
            onChange?.invoke()
        }
    }
}
