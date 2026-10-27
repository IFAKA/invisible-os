@file:OptIn(KitOnly::class)

package com.faka.invisible.claude

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of the Claude bridge tool. They live here, in the program, not in the Voice Kit. */
private fun f(text: String) = Phrase.fixed(text)

object ClaudeVoice {
    /** The menu label. */
    val TITLE = f("Claude")
    val NOT_SET = f("Claude is not set up. In the app, type the bridge address and token.")
    val LISTENING = f("Listening for Claude. Hold three seconds to leave.")
    val NOTHING = f("Nothing waiting.")
    val READ_AGAIN = f("Read again")
    val DOWN = f("Cannot reach the laptop.")
    val BACK_UP = f("The laptop is back.")
    val FAILED = f("Could not send. Try again.")
    val TOO_LATE = f("Too late. Answered on the laptop.")
    val SKIPPED = f("Skipped.")
    val WAITING_YOU = f("Waiting for you. Click to hear it again.")
    fun asks(project: String, summary: String) = f("$project asks: $summary")
    fun done(project: String, summary: String) = f("$project is done. $summary")
    fun needs(project: String, summary: String) = f("$project needs you. $summary")
    fun sent(choice: String) = f("Sent: $choice.")
}
