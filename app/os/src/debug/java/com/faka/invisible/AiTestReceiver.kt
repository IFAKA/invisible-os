package com.faka.invisible

import com.faka.invisible.write.MlKitTranslator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Dev tool: `adb shell am broadcast -n com.faka.invisible/.AiTestReceiver --es msg "Ana: ¿Vienes a cenar?" --es lang es` then read logcat tag INVISIBLE_AI. */
class AiTestReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Actions.init(c.applicationContext)   // a cold process (the system killed the app) has no OS yet; the hooks need it
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
        i.getStringExtra("fake")?.let { fake(c, it); return }   // dev (e2e): post a fake "call" / "chat" notification, or "clear" them
        if (i.hasExtra("reset")) { Actions.resetDev(i.getBooleanExtra("reset", true)); fake(c, "clear"); return }   // dev (e2e): known state; --ez reset false = practice not done yet
        i.getStringExtra("show")?.let { Actions.showDev(it); return }
        if (i.hasExtra("rate")) { Actions.rateDev(i.getFloatExtra("rate", 0.9f)); return }
        if (i.hasExtra("state")) { Actions.stateDev(); return }
        i.getStringExtra("press")?.let { Actions.pressDev(it); return }   // dev: raw presses "down,up,down,..." in ms, through the real button path
        i.getStringExtra("start")?.let { Actions.startDev(it); return }   // dev: start a program: echo, guard, duel, compass, write
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

    /** Fake notifications for the e2e test. Their buttons point at nobody, so nothing real is answered, rejected or sent. */
    private fun fake(c: Context, what: String) {
        val nm = c.getSystemService(android.app.NotificationManager::class.java)
        if (what == "clear") { nm.cancel(7001); nm.cancel(7002); return }
        nm.createNotificationChannel(android.app.NotificationChannel("e2e", "e2e", android.app.NotificationManager.IMPORTANCE_HIGH))
        fun pi(flag: Int) = android.app.PendingIntent.getBroadcast(c, 0, Intent("com.faka.invisible.E2E_NOOP").setPackage(c.packageName), flag or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val b = android.app.Notification.Builder(c, "e2e").setSmallIcon(android.R.drawable.sym_def_app_icon).setVisibility(android.app.Notification.VISIBILITY_PUBLIC).addExtras(android.os.Bundle().apply { putBoolean("e2e", true) })
        if (what == "call") {
            b.setContentTitle("E2E Caller").setContentText("Incoming call").setCategory(android.app.Notification.CATEGORY_CALL)
                .addAction(android.app.Notification.Action.Builder(null, "Answer", pi(android.app.PendingIntent.FLAG_IMMUTABLE)).build())
                .addAction(android.app.Notification.Action.Builder(null, "Decline", pi(android.app.PendingIntent.FLAG_IMMUTABLE)).build())
            nm.notify(7001, b.build())
        } else {
            val ri = android.app.RemoteInput.Builder("reply").setLabel("Reply").build()
            b.setContentTitle("E2E Friend").setContentText("Hello, are you coming to dinner tonight?").setCategory(android.app.Notification.CATEGORY_MESSAGE)
                .addAction(android.app.Notification.Action.Builder(null, "Reply", pi(android.app.PendingIntent.FLAG_MUTABLE)).addRemoteInput(ri).setSemanticAction(android.app.Notification.Action.SEMANTIC_ACTION_REPLY).build())
            nm.notify(7002, b.build())
        }
    }
}
