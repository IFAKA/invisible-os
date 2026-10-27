package com.faka.invisible

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.faka.invisible.voicekit.Translations

/**
 * Xiaomi and Android can turn the accessibility service off after an app update. Without it the earbud button does nothing,
 * and a person who cannot see the screen would not know why. So the app remembers it was on, and when it finds it off it says so
 * (a notification that opens the accessibility settings, and one spoken sentence when the app is opened).
 */
object ButtonAccess {
    private const val CHANNEL = "inv_alert"
    private const val NOTE_ID = 2

    /** Pure rule: it was on before and is off now. */
    fun lost(wasOn: Boolean, nowOn: Boolean) = wasOn && !nowOn

    /** Is our service in the list of enabled accessibility services? (A cheap read; the service may still be starting.) */
    fun enabled(ctx: Context): Boolean {
        val me = ComponentName(ctx, ButtonAccessibilityService::class.java)
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("invisible", Context.MODE_PRIVATE)

    /**
     * Remember the state and show or remove the notification. Returns true when access was lost and has not been told to the person yet
     * (call [told] after speaking it, so it is said once per time it is lost).
     */
    fun check(ctx: Context): Boolean {
        val p = prefs(ctx)
        val now = enabled(ctx)
        if (now) { p.edit().putBoolean("a11y_was_on", true).putBoolean("a11y_lost_told", false).apply(); cancel(ctx); return false }
        if (!lost(p.getBoolean("a11y_was_on", false), false)) return false
        notify(ctx)
        return !p.getBoolean("a11y_lost_told", false)
    }

    fun told(ctx: Context) { prefs(ctx).edit().putBoolean("a11y_lost_told", true).apply() }

    private fun cancel(ctx: Context) { ctx.getSystemService(NotificationManager::class.java).cancel(NOTE_ID) }

    private fun notify(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Alerts", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 0, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(Translations.label("Invisible OS")).setContentText(Translations.label("Button access is off. Tap to turn it on."))
            .setContentIntent(open).setAutoCancel(true).build()
        try { nm.notify(NOTE_ID, n) } catch (e: SecurityException) { /* notifications not allowed: the spoken sentence still tells */ }
    }
}

/** After an update or a restart: check the button access (the service is not running yet, so nothing else would notice). */
class ButtonAccessReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Actions.init(c); ButtonAccess.check(c) }
}
