package com.faka.invisible

import android.os.Handler
import android.os.Looper

/** Simple in-memory list of the last 10 gestures, shared by service and activity. */
object EventLog {
    val items = ArrayList<String>()
    var onChange: (() -> Unit)? = null
    var running = false
    private val main = Handler(Looper.getMainLooper())

    /** Redraw the app screen now (a task changed by the voice menu). */
    fun refresh() { main.post { onChange?.invoke() } }

    /** The setup screen's "Test your button": called for every gesture that reaches the OS (from any thread). */
    @Volatile var lastGesture: String? = null
    fun pressed(g: com.faka.invisible.voicekit.Gesture) { lastGesture = g.name; com.faka.invisible.voicekit.ScreenMirror.pressed(com.faka.invisible.voicekit.ScreenMirror.gestureText(g.name)); refresh() }

    fun add(text: String) {
        main.post {
            items.add(0, text)
            while (items.size > 10) items.removeAt(items.size - 1)
            onChange?.invoke()
        }
    }
}
