package com.faka.invisible.logickit

/**
 * Pure: how a click count on the earbud, while the OS is closed, becomes the normal headset action for the player that is
 * really playing (1 click = play/pause, 2 = next, 3 = previous), and which player that is. Never our own session.
 */
object MediaReplay {
    enum class Action { PLAY_PAUSE, NEXT, PREVIOUS }

    /** [state] = the `PlaybackState` int of its session. */
    data class Player(val pkg: String, val state: Int, val ours: Boolean)

    /** Android's key codes (KEYCODE_MEDIA_PLAY_PAUSE, _NEXT, _PREVIOUS, _PAUSE) so the pure code needs no Android class. */
    const val KEY_PLAY_PAUSE = 85
    const val KEY_NEXT = 87
    const val KEY_PREVIOUS = 88
    const val KEY_PAUSE = 127

    fun forClicks(n: Int): Action? = when (n) { 1 -> Action.PLAY_PAUSE; 2 -> Action.NEXT; 3 -> Action.PREVIOUS; else -> null }
    fun keyFor(a: Action) = when (a) { Action.PLAY_PAUSE -> KEY_PLAY_PAUSE; Action.NEXT -> KEY_NEXT; Action.PREVIOUS -> KEY_PREVIOUS }

    /** PlaybackState: PLAYING 3, FAST_FORWARDING 4, REWINDING 5, BUFFERING 6, CONNECTING 8 (a track about to sound). */
    fun isPlaying(state: Int) = state == 3 || state == 4 || state == 5 || state == 6 || state == 8

    /** [players] newest first (the system's order). The one to act on: a playing one that is not ours, else the most recent that is not ours. */
    fun choose(players: List<Player>): Player? {
        val others = players.filter { !it.ours }
        return others.firstOrNull { isPlaying(it.state) } ?: others.firstOrNull()
    }

    /** Every playing player that is not ours (the OS opens: all of them pause). */
    fun playing(players: List<Player>) = players.filter { !it.ours && isPlaying(it.state) }

    /** What to send to [p]: a play/pause click pauses a playing player and plays a stopped one. */
    fun command(a: Action, p: Player): String = when (a) {
        Action.PLAY_PAUSE -> if (isPlaying(p.state)) "pause" else "play"
        Action.NEXT -> "next"
        Action.PREVIOUS -> "previous"
    }
}
