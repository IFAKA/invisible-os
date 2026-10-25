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
        val n = t?.let { Actions.addTasks(it) } ?: 0
        Toast.makeText(this, when (n) { 0 -> "No open tasks found"; 1 -> "1 task added"; else -> "$n tasks added" }, Toast.LENGTH_SHORT).show()
        finish()
    }
}
