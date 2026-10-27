package com.faka.invisible

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.faka.invisible.oskit.Trace

private val last = HashMap<String, String>()

/** One trace line for a notification that arrived or went away. Text of secret notifications and of bank or password apps is left out. */
fun traceNotif(verb: String, sbn: StatusBarNotification) {
    val n = sbn.notification; val x = n.extras
    val hide = sbn.packageName.let { NotifPrivacy.sensitivePackage(it) } || n.visibility == NotifPrivacy.VISIBILITY_SECRET
    val title = if (hide) "-" else x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val text = if (hide) "-" else (x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
    val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
    val line = "$verb pkg=${sbn.packageName} cat=${n.category} ongoing=$ongoing title=$title text=${text.take(80)}"
    synchronized(last) { if (last[sbn.key] == line) return; last[sbn.key] = line; if (last.size > 200) last.clear() }   // a media notification re-posts every second or so: one line per change
    Trace.ev("notif", line)
}
