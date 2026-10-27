package com.faka.invisible

import com.faka.invisible.claude.BridgeClient
import com.faka.invisible.claude.ClaudeEvent
import com.faka.invisible.claude.Computer
import com.faka.invisible.claude.RoomEvent
import com.faka.invisible.claude.RoomState
import java.io.IOException

/**
 * Listens to the laptop bridges while the OS is idle: one background thread, for the active computer only (never the main thread). It reports questions
 * that need an answer ([onAsk]), rooms that need you ([onRoomNeeds]) and finished windows ([onRoomDone]);
 * all are called on the watcher's thread: the owner posts to the main thread. It does nothing when no computer is set.
 * The Claude tool, once started, polls from the start itself and so gets the same open questions again.
 */
class ClaudeWatch(private val active: () -> Computer?, private val onAsk: (ClaudeEvent) -> Unit,
                  private val onRoomNeeds: () -> Unit = {}, private val onRoomDone: (RoomEvent) -> Unit = {}) {
    @Volatile private var generation = 0

    /** (Re)starts for the active computer (the old thread stops); none stops it. */
    @Synchronized fun restart() {
        val g = ++generation
        val c = active() ?: return
        if (c.url.isBlank() || c.token.isBlank()) return
        Thread({ loop(g, BridgeClient(c.url, c.token)) }, "claude-watch-${c.name}").apply { isDaemon = true }.start()
    }

    private fun loop(g: Int, client: BridgeClient) {
        var since = 0
        val told = HashSet<String>()
        while (g == generation) {
            try {
                val r = client.poll(since, 25)
                since = r.seq
                for (e in r.events) if (e.needsAnswer && told.add(e.id) && g == generation) onAsk(e)
                for (e in r.rooms) if (g == generation) when (e.state) { RoomState.NEEDS -> onRoomNeeds(); RoomState.DONE -> onRoomDone(e) }
            } catch (e: IOException) {
                try { Thread.sleep(10_000) } catch (_: InterruptedException) { return }
            }
        }
    }
}
