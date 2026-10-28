package com.faka.invisible

import com.faka.invisible.write.FieldKind
import com.faka.invisible.write.Fields
import com.faka.invisible.write.Frame
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import com.faka.invisible.voicekit.VoiceKit
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ButtonAccessibilityService : AccessibilityService() {
    companion object {
        var inst: ButtonAccessibilityService? = null
    }

    override fun onServiceConnected() {
        inst = this
        Actions.init(this)
        Actions.accessibilityConnected = true
        if (!EventLog.running) startForegroundService(Intent(this, ButtonService::class.java)) // self-start, no screen needed
        Log.d("Invisible", "A11Y connected")
        EventLog.add("accessibility connected")
        ButtonAccess.check(this)   // remembers that it is on
    }

    override fun onKeyEvent(e: KeyEvent): Boolean = VoiceKit.buttons.keyEvent("a11y", e)

    private val nameLike = Regex("title|name|contact|conversation|toolbar", RegexOption.IGNORE_CASE)

    /** The place that has the keyboard focus right now: which app, what kind of field, what is on the screen. Empty [Frame] if no other app's field has focus. */
    fun frame(): Frame {
        val root = rootInActiveWindow ?: return Frame()
        val app = root.packageName?.toString() ?: ""
        if (app == packageName) return Frame()
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: return Frame(app = app)
        val hint = f.hintText?.toString().orEmpty()
        val id = f.viewIdResourceName?.substringAfter('/').orEmpty()
        val kind = Fields.kind(f.inputType, f.isPassword, hint, id)
        val before = if (kind == FieldKind.PASSWORD || f.isShowingHintText) "" else f.text?.toString().orEmpty()
        val texts = ArrayList<String>(); var thread = ""
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || kind == FieldKind.PASSWORD) return
            val tx = n.text?.toString()?.trim().orEmpty()
            if (!n.isEditable && !n.isPassword && tx.isNotEmpty() && tx.length <= 300 && n.isVisibleToUser) {
                texts.add(tx); if (thread.isEmpty() && nameLike.containsMatchIn(n.viewIdResourceName.orEmpty())) thread = tx
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return Frame(app = app, kind = kind, field = id, hint = hint, thread = thread, around = texts.filter { it != thread }.distinct().takeLast(6), before = before, hasField = true)
    }

    /**
     * What the app in front lists as suggestions for the field (a search box shows them as a list under it): the visible, clickable, short texts of the
     * window that are not the field itself, in the order of the screen, at most 10. Empty for a password field or our own window.
     */
    fun screenSuggestions(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() == packageName) return emptyList()
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (field?.isPassword == true) return emptyList()
        val own = field?.text?.toString().orEmpty()
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int, inRow: Boolean) {
            if (n == null || depth > 40 || out.size >= 10) return
            val row = inRow || (n.isClickable && n.isVisibleToUser)
            val tx = n.text?.toString()?.trim().orEmpty()
            if (row && !n.isEditable && n.isVisibleToUser && tx.isNotEmpty() && tx.length <= 80 && tx != own) out.add(tx)
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, row)
        }
        walk(root, 0, false)
        return out.distinct()
    }

    /** Puts [text] into the focused field of the other app. */
    fun setFieldText(text: String): Boolean {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        Actions.accessibilityConnected = false
        Log.d("Invisible", "A11Y disconnected")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ ButtonAccess.check(applicationContext) }, 1500)   // off for good? then tell
        return super.onUnbind(intent)
    }
}
