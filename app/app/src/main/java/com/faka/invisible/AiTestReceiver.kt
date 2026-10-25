package com.faka.invisible

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Dev tool: `adb shell am broadcast -n com.faka.invisible/.AiTestReceiver --es msg "Ana: ¿Vienes a cenar?" --es lang es` then read logcat tag INVISIBLE_AI. */
class AiTestReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pr = goAsync(); val ctx = c.applicationContext
        Thread {
            try { val end = i.getStringExtra("ending"); if (end != null) LocalAi.continuations(ctx, end, i.getStringExtra("lang") ?: "es") else LocalAi.replies(ctx, i.getStringExtra("msg") ?: "Ana: ¿Vienes esta noche a cenar?", i.getStringExtra("lang") ?: "es") }
            catch (e: Throwable) { Log.e("INVISIBLE_AI", "failed", e) } finally { pr.finish() }
        }.start()
    }
}
