package com.faka.invisible

import com.faka.invisible.claude.BridgeClient
import com.faka.invisible.claude.ClaudeEvent
import com.faka.invisible.claude.Computer
import com.faka.invisible.claude.RoomEvent
import com.faka.invisible.claude.RoomState
import java.io.IOException

/**
 * Listens to the laptop bridges while the OS is idle: one background thread per computer (never the main thread). It reports questions
 * that need an answer ([onAsk]), rooms that need you ([onRoomNeeds]) and finished windows ([onRoomDone]), each with the computer's name;
 * all are called on the watcher's thread: the owner posts to the main thread. It does nothing when no computer is set.
 * The Claude tool, once started, polls from the start itself and so gets the same open questions again.
 */
class ClaudeWatch(private val computers: () -> List<Computer>, private val onAsk: (String, ClaudeEvent) -> Unit,
                  private val onRoomNeeds: (String) -> Unit = {}, private val onRoomDone: (String, RoomEvent) -> Unit = { _, _ -> }) {
    @Volatile private var generation = 0

    /** (Re)starts with the saved computers; none stops it. */
    @Synchronized fun restart() {
        val g = ++generation
        for (c in computers()) {
            if (c.url.isBlank() || c.token.isBlank()) continue
            Thread({ loop(g, c.name, BridgeClient(c.url, c.token)) }, "claude-watch-${c.name}").apply { isDaemon = true }.start()
        }
    }

    private fun loop(g: Int, name: String, client: BridgeClient) {
        var since = 0
        val told = HashSet<String>()
        while (g == generation) {
            try {
                val r = client.poll(since, 25)
                since = r.seq
                for (e in r.events) if (e.needsAnswer && told.add(e.id) && g == generation) onAsk(name, e)
                for (e in r.rooms) if (g == generation) when (e.state) { RoomState.NEEDS -> onRoomNeeds(name); RoomState.DONE -> onRoomDone(name, e) }
            } catch (e: IOException) {
                try { Thread.sleep(10_000) } catch (_: InterruptedException) { return }
            }
        }
    }
}
