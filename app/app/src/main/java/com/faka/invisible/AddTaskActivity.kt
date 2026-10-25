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
        val task = t?.let { Actions.addTask(it) }
        Toast.makeText(this, if (task != null) "Task added" else "Nothing to add", Toast.LENGTH_SHORT).show()
        finish()
    }
}
