package com.faka.invisible

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Dev tool: `adb shell am broadcast -n com.faka.invisible/.AiTestReceiver --es msg "Ana: ¿Vienes a cenar?" --es lang es` then read logcat tag INVISIBLE_AI. */
class AiTestReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        i.getStringExtra("dl")?.let { l -> Thread { MlKitTranslator.download(l); android.util.Log.d("Invisible", "translate models requested for en+$l") }.start(); return }
        i.getStringExtra("sayes")?.let { com.faka.invisible.voicekit.VoiceKit.narrator.say(com.faka.invisible.voicekit.Phrase.content(it, "es"), false, false, false); return }   // dev: speak a text with the Spanish voice
        if (i.hasExtra("pairtest")) { Actions.pairTest(); return }
        i.getStringExtra("neural")?.let { com.faka.invisible.voicekit.VoiceKit.neural.bench(it, i.getStringExtra("text") ?: "This is a test of the voice on this phone.", i.getIntExtra("rounds", 2), "neural_$it.wav", i.getFloatExtra("speed", 0f)); return }
        i.getStringExtra("ttsbench")?.let { com.faka.invisible.voicekit.TtsBench.run(c, it.ifBlank { null }, i.getStringExtra("lang") ?: "en", i.getStringExtra("text") ?: "This is a test of the voice on this phone."); return }
        if (i.hasExtra("swap")) { Actions.setSwapEars(i.getBooleanExtra("swap", true)); return }
        if (i.hasExtra("tree")) { val ctx0 = c.applicationContext; Thread { Log.d("TREEDUMP", "\n" + TreeLeaves.dump(ctx0)) }.start(); return }
        if (i.hasExtra("playmusic")) { Actions.playMusicDev(); return }   // dev: what the Music item does with no song
        if (i.hasExtra("browse")) { Actions.browseMusicDev(); return }
        i.getStringExtra("gesture")?.let { Actions.gestureDev(it); return }   // dev: a button gesture by name
        i.getStringExtra("ctl")?.let { Actions.ctlDev(it); return }
        if (i.hasExtra("wait")) { Actions.waitDev(i.getIntExtra("wait", 5000).toLong()); return }   // dev: the waiting heartbeat for this many ms
        i.getStringExtra("msearch")?.let { Actions.searchDev(it); return }   // dev: type a song search without the keyboard
        i.getStringExtra("toggle")?.let { Actions.toggleDev(it); return }
        if (i.hasExtra("radio")) { Actions.radioDev(); return }
        if (i.hasExtra("write")) { Actions.startWriteDev(); return }
        val pr = goAsync(); val ctx = c.applicationContext
        Thread {
            try { val nx = i.getStringExtra("next"); val end = i.getStringExtra("ending"); if (nx != null) LocalAi.nextWords(ctx, nx, i.getStringExtra("lang") ?: "es", i.getStringExtra("brief") ?: "") else if (end != null) LocalAi.continuations(ctx, end, i.getStringExtra("lang") ?: "es") else LocalAi.replies(ctx, i.getStringExtra("msg") ?: "Ana: ¿Vienes esta noche a cenar?", i.getStringExtra("lang") ?: "es") }
            catch (e: Throwable) { Log.e("INVISIBLE_AI", "failed", e) } finally { pr.finish() }
        }.start()
    }
}
