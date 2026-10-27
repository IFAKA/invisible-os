@file:OptIn(KitOnly::class)

package com.faka.invisible.claude

import com.faka.invisible.logickit.squash
import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of the Claude bridge tool. They live here, in the program, not in the Voice Kit. */
private fun f(text: String) = Phrase.fixed(text)

object ClaudeVoice {
    /** The menu label. */
    val TITLE = f("Laptop")
    val NOT_SET = f("Claude is not set up. In the app, add a computer.")
    val LISTENING = f("Listening for Claude. Hold three seconds to leave.")
    val NOTHING = f("Nothing waiting.")
    val READ_AGAIN = f("Read again")
    val DOWN = f("Cannot reach the laptop.")
    val BACK_UP = f("The laptop is back.")
    val FAILED = f("Could not send. Try again.")
    val TOO_LATE = f("Too late. Answered on the laptop.")
    val SKIPPED = f("Skipped.")
    val WAITING_YOU = f("Waiting for you. Click to hear it again.")
    val NOT_REACHABLE = f("Laptop not reachable. Click to try again.")
    val NOT_REACHABLE_TAILSCALE = f("Laptop not reachable. Is Tailscale on? Click to try again.")
    val TRYING = f("Trying again")
    /** The not-reachable line for the address of the computer: Tailscale addresses (100.x) name the likely cause. */
    fun notReachable(url: String) = if (RoomState.isTailscale(url)) NOT_REACHABLE_TAILSCALE else NOT_REACHABLE
    val NO_ROOMS = f("No windows open.")
    val PROJECTS = f("New window")
    val SETTINGS = f("Laptop settings")
    val MUTE = f("Mute until morning")
    val TEST_DONE = f("Test done alert")
    val MUTED = f("Quiet until morning.")
    val NO_BACK = f("No, go back")
    val YES = f("Yes")
    val CONTINUE_CHAT = f("Continue last chat")
    val NEW_CHAT = f("New chat")
    val NEW_PROJECT = f("New project")
    val NO_AREAS = f("No places for a new project.")
    val WRITE_NAME = f("Write the project name.")
    val WRITE_ASK = f("Write the first ask, or Done.")
    fun started(name: String) = f("Started $name.")
    val NOTHING_TO_READ = f("Nothing to read.")
    fun room(name: String, state: String) = f("$name, ${RoomState.word(state)}")
    /** A window in the list: "project, what it is about, state". */
    fun window(r: Room) = f(RoomState.windowLine(r))
    fun switchTo(name: String) = f("Switch to $name")
    val SWITCH_COMPUTER = f("Switch computer")
    fun sentTo(name: String) = f("Sent to $name")
    fun opened(name: String) = f("Opened $name")
    fun sure(label: String) = f("$label?")
    fun label(a: RoomAction, go: String = ""): Phrase = f(RoomText.label(a, go))
    fun asks(project: String, summary: String) = f("$project asks: $summary")
    fun done(project: String, summary: String) = f("$project is done. $summary")
    fun needs(project: String, summary: String) = f("$project needs you. $summary")
    fun sent(choice: String) = f("Sent: $choice.")
}

/** The words of an action, for the ear and for the screen. */
object RoomText {
    private val fixed = mapOf(
        "go" to "Go ahead", "yes" to "Yes", "commit" to "Commit", "push" to "Push", "compact" to "Compact",
        "explain" to "Explain simpler", "recommend" to "What do you recommend", "research" to "Research first",
        "progress" to "Where are we", "hold" to "Hold", "stop" to "Stop", "clear" to "Clear",
        "hear" to "Hear it again", "readall" to "Read all", "write" to "Write", "again" to "Run again", "screen" to "Screen")
    /** [go] = what a plain "go" approves (from the story): "Go: commit the fix". Quoted options stay as they are. */
    fun label(a: RoomAction, go: String = ""): String {
        val g = go.squash()
        if (g.isNotEmpty() && a.id == "go") return "Go: $g"
        if (g.isNotEmpty() && a.id == "yes") return "Yes: $g"
        return a.option ?: a.makeTarget?.let { "Make $it" } ?: fixed[a.id] ?: a.id
    }
}
