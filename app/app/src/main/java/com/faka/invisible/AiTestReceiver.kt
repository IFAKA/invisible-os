package com.faka.invisible

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Dev tool: `adb shell am broadcast -n com.faka.invisible/.AiTestReceiver --es msg "Ana: ¿Vienes a cenar?" --es lang es` then read logcat tag INVISIBLE_AI. */
class AiTestReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        i.getStringExtra("dl")?.let { l -> Thread { MlKitTranslator.download(l); android.util.Log.d("Invisible", "translate models requested for en+$l") }.start(); return }
        if (i.hasExtra("swap")) { Actions.setSwapEars(i.getBooleanExtra("swap", true)); return }
        if (i.hasExtra("write")) { Actions.startWriteDev(); return }
        val pr = goAsync(); val ctx = c.applicationContext
        Thread {
            try { val nx = i.getStringExtra("next"); val end = i.getStringExtra("ending"); if (nx != null) LocalAi.nextWords(ctx, nx, i.getStringExtra("lang") ?: "es", i.getStringExtra("brief") ?: "") else if (end != null) LocalAi.continuations(ctx, end, i.getStringExtra("lang") ?: "es") else LocalAi.replies(ctx, i.getStringExtra("msg") ?: "Ana: ¿Vienes esta noche a cenar?", i.getStringExtra("lang") ?: "es") }
            catch (e: Throwable) { Log.e("INVISIBLE_AI", "failed", e) } finally { pr.finish() }
        }.start()
    }
}
