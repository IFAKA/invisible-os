package com.faka.invisible.oskit

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.RawButton

/**
 * A program of the OS (a game or a tool). It owns its own rules and sounds and gets the raw button while it runs ([RawButton]).
 * It never plays an OS sound and never touches the menu: when it is over it calls [ProgramHost.ended] and the OS plays the close sound.
 * A program module depends only on :oskit; everything it needs from the phone comes through [ProgramHost] (or a small port
 * the program module declares itself and the OS implements).
 */
interface Program : RawButton {
    /** Main thread. Starts the program; it keeps [host] until it calls [ProgramHost.ended]. */
    fun start(host: ProgramHost)
    /** Main thread. Stops the program now (the exit gesture or a new program). It must end with [ProgramHost.ended]. */
    fun stop()
    /** The phone finished speaking. Programs that wait for the voice use it. */
    fun speechDone() {}
    /** The person tapped row [row] of the picture the program put on the app screen ([ProgramHost.screen]): do what the button would do for it. */
    fun tap(row: Int) {}
}

/** What the OS gives a running program. */
interface ProgramHost {
    val context: Context
    val handler: Handler
    /** Runs [r] on the main thread after [delayMs]. Prefer this to `handler.postDelayed`: a test host (FakeProgramHost) can then move time by hand. */
    fun later(r: Runnable, delayMs: Long) { handler.postDelayed(r, delayMs) }
    /** Cancels a [later] that has not run yet. */
    fun cancel(r: Runnable) { handler.removeCallbacks(r) }
    /** The app's saved settings; a program uses its own key names (for example `echo_best`). */
    val prefs: SharedPreferences
    /** Starts the OS wait sound (a soft heartbeat while sounds are built); call the result when ready. */
    fun waiting(): () -> Unit
    fun speak(text: Phrase, slow: Boolean = false)
    /** The picture of the program on the app screen: [rows] top to bottom, [mark] the row being read. Without it the screen shows the last lines spoken. */
    fun screen(rows: List<String>, mark: Int) {}
    fun stopSpeech()
    fun pauseMusic()
    fun resumeMusic()
    fun log(line: String)
    /** Which sound output is in use (earbuds, wired, phone speaker). */
    fun outputDevice(): String
    /** The program is over. The OS plays its close sound and takes the button back. Call it once. */
    fun ended()
}

/**
 * One line of the OS menu that starts a program. [id] is the menu id, `games:<name>` (in the Games list) or `hub:<name>`
 * (a tool of the home hub, placed by its tag in AppCategories). [title] is what the phone says. [warm] runs once when the OS
 * starts, off the main thread's critical path, for programs whose sounds take seconds to build on a phone. [create] makes a fresh program.
 */
class ProgramEntry(val id: String, val title: Phrase, val warm: () -> Unit = {}, val create: () -> Program)
