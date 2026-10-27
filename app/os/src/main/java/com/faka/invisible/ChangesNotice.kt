package com.faka.invisible

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.faka.invisible.claude.ChangesText
import com.faka.invisible.claude.Computer
import com.faka.invisible.claude.Room
import com.faka.invisible.claude.RoomState

/** Show on phone: the notification "<window>: see changes". One buzz (default importance, no repeat); a tap opens [ChangesActivity] for that window. */
object ChangesNotice {
    const val CHANNEL = "changes"
    const val EXTRA_COMPUTER = "computer"
    const val EXTRA_ROOM = "room"
    const val EXTRA_NAME = "name"

    /** The intent a tap runs. Its data is unique per window, so each window keeps its own notice and its own extras. */
    fun intent(ctx: Context, computer: String, roomId: String, name: String): Intent =
        Intent(ctx, ChangesActivity::class.java).setData(android.net.Uri.parse("invisible://changes/" + android.net.Uri.encode(computer) + "/" + android.net.Uri.encode(roomId)))
            .putExtra(EXTRA_COMPUTER, computer).putExtra(EXTRA_ROOM, roomId).putExtra(EXTRA_NAME, name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun id(computer: String, roomId: String) = 4200 + ((computer + roomId).hashCode() and 0xFFF)

    fun post(ctx: Context, computer: Computer, room: Room) {
        val name = RoomState.nameOf(room)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Changes from the laptop", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(ctx, id(computer.name, room.id), intent(ctx, computer.name, room.id, name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(ChangesText.title(name)).setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true).build()
        try { nm.notify(id(computer.name, room.id), n) } catch (e: SecurityException) { /* notifications not allowed: the spoken "Sent to phone." still tells */ }
    }
}
