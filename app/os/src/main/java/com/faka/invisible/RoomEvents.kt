package com.faka.invisible

import android.os.Handler
import com.faka.invisible.claude.BridgeClient
import com.faka.invisible.claude.Computer
import com.faka.invisible.claude.DoneBatch
import com.faka.invisible.voicekit.Phrase

/**
 * What the OS does with the laptop's room events while it is idle (the logic that used to sit in [Actions], so a test runs the same code):
 * a room that needs you makes the Claude sound, a finished window is said by name (several in 3 s are one line), and the count of
 * waiting windows is renewed in a background thread for home. Nothing is said while a program runs or the rooms are muted.
 */
class RoomEvents(
    private val handler: Handler,
    private val active: () -> Computer?,
    private val programRuns: () -> Boolean,
    private val muted: () -> Boolean,
    private val beepNeeds: () -> Unit,
    private val say: (Phrase) -> Unit,
    onAsk: (com.faka.invisible.claude.ClaudeEvent) -> Unit = {},
) {
    private val watch = ClaudeWatch(active, onAsk, { waitingNow(); handler.post { needs() } }, { e -> waitingNow(); handler.post { done(e.name) } })
    @Volatile private var waitingRooms = 0
    private val waitingBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val batch = DoneBatch()

    /** Starts watching the active computer (the old watcher stops). */
    fun restart() = watch.restart()
    /** Windows of the active computer that wait for you: the last answer now, renewed in a background thread (a change shows one wake later). */
    fun waitingNow(): Int {
        val c = active() ?: return 0
        if (c.url.isBlank() || c.token.isBlank()) return 0
        if (waitingBusy.compareAndSet(false, true)) Thread({
            waitingRooms = try { BridgeClient(c.url, c.token).rooms().count { it.state == "needs" } } catch (e: Exception) { 0 }
            waitingBusy.set(false)
        }, "rooms-waiting").apply { isDaemon = true }.start()
        return waitingRooms
    }

    private fun needs() { if (programRuns() || muted()) return; beepNeeds() }
    private fun done(name: String) {
        if (programRuns() || muted()) return
        if (batch.add(name)) handler.postDelayed({ batch.take()?.let { say(it) } }, 3000)
    }
}
