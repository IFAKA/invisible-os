package com.faka.invisible

import com.faka.invisible.claude.BridgeClient
import com.faka.invisible.claude.ClaudeEvent
import com.faka.invisible.claude.RoomEvent
import com.faka.invisible.claude.RoomState
import java.io.IOException

/**
 * Listens to the laptop bridge while the OS is idle, on its own background thread (never the main thread). It only reports questions
 * that need an answer ([onAsk], called on this thread: the owner posts to the main thread). It does nothing when no address is set.
 * The Claude tool, once started, polls from the start itself and so gets the same open questions again.
 */
class ClaudeWatch(private val settings: () -> Pair<String, String>, private val onAsk: (ClaudeEvent) -> Unit, private val onRoomNeeds: (RoomEvent) -> Unit = {}) {
    @Volatile private var generation = 0

    /** (Re)starts with the saved address; a blank address stops it. */
    @Synchronized fun restart() {
        val g = ++generation
        val (url, token) = settings()
        if (url.isBlank() || token.isBlank()) return
        Thread({ loop(g, BridgeClient(url, token)) }, "claude-watch").apply { isDaemon = true }.start()
    }

    private fun loop(g: Int, client: BridgeClient) {
        var since = 0
        val told = HashSet<String>()
        while (g == generation) {
            try {
                val r = client.poll(since, 25)
                since = r.seq
                for (e in r.events) if (e.needsAnswer && told.add(e.id) && g == generation) onAsk(e)
                for (e in r.rooms) if (e.state == RoomState.NEEDS && g == generation) onRoomNeeds(e)
            } catch (e: IOException) {
                try { Thread.sleep(10_000) } catch (_: InterruptedException) { return }
            }
        }
    }
}
