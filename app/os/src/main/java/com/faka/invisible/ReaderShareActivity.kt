package com.faka.invisible

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.faka.invisible.oskit.Trace
import com.faka.invisible.reader.ReaderStore
import com.faka.invisible.reader.ReaderVoice

/**
 * "Share" target of the Reader tool: share text (or a link with its title) from any app; the Reader tool reads it next time it
 * starts. A shared link is read as its title and text only (the app does not fetch pages). No screen of its own.
 */
class ReaderShareActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val i = intent?.takeIf { it.action == Intent.ACTION_SEND }
        val body = i?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val title = (i?.getStringExtra(Intent.EXTRA_SUBJECT) ?: i?.getStringExtra(Intent.EXTRA_TITLE)).orEmpty()
        val text = if (title.isNotBlank() && !body.startsWith(title)) "$title\n\n$body" else body
        if (text.isNotBlank()) {
            ReaderStore.share(getSharedPreferences("invisible", Context.MODE_PRIVATE), text)
            Trace.ev("program", "reader shared chars=${text.length}")
            Toast.makeText(applicationContext, ReaderVoice.SAVED.text, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
