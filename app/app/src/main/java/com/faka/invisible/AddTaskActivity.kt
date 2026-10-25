package com.faka.invisible

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** "Share" target: share text from Keep (or any app) and it becomes a task. No screen of its own. */
class AddTaskActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Actions.init(applicationContext)
        val t = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        t?.let { try { java.io.File(filesDir, "last_share.txt").writeText(it) } catch (e: Exception) {} }   // to see the real format if splitting looks wrong
        val ctx = applicationContext
        Actions.addTasks(t ?: "") { n -> Toast.makeText(ctx, when (n) { 0 -> "No new open tasks"; 1 -> "1 task added"; else -> "$n tasks added" }, Toast.LENGTH_SHORT).show() }
        finish()
    }
}
