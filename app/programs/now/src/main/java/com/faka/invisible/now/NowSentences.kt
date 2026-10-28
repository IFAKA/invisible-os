@file:OptIn(KitOnly::class)

package com.faka.invisible.now

import com.faka.invisible.voicekit.KitOnly
import com.faka.invisible.voicekit.Phrase

/** Fixed sentences of Now and Quick switches. They live here, in the programs, not in the Voice Kit. */
private fun f(text: String) = Phrase.fixed(text)

object NowVoice {
    val TITLE get() = f("Now")
    val INTRO get() = f("Click: next. Three clicks: back.")
    val DONE get() = f("That is all.")
    val WAIT get() = f("One moment.")
}

object SwitchesVoice {
    val TITLE get() = f("Quick switches")
    val VOLUME_UP get() = f("Volume up")
    val VOLUME_DOWN get() = f("Volume down")
    val DND get() = f("Do not disturb")
    val TORCH get() = f("Flashlight")
    val RING get() = f("Ring my phone")
    val WIFI get() = f("Wi-Fi settings")
    val BLUETOOTH get() = f("Bluetooth settings")
    val DND_NEEDS get() = f("Needs access. Settings, Notifications, Do not disturb access.")
    val TORCH_FAILED get() = f("No flashlight.")
    val RINGING get() = f("Ringing. Press to stop.")
    val RING_STOPPED get() = f("Stopped.")
    val OPENING_WIFI get() = f("Opening Wi-Fi settings.")
    val OPENING_BLUETOOTH get() = f("Opening Bluetooth settings.")
    val OPEN_FAILED get() = f("Could not open it.")
    fun volume(percent: Int) = f("Volume $percent percent")
}
