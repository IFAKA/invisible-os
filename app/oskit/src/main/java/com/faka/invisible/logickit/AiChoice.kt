package com.faka.invisible.logickit

/** Which AI does a job: the laptop's model, the model on the phone, or none. */
enum class AiSource { COMPUTER, PHONE, NONE }

/**
 * Pure: the ONE order for every AI job (notification digest, short version, reply ideas, tasks) and the ONE switch.
 * Switch off: none, anywhere. Switch on: the computer if one is set and not known to be down, else the phone model if
 * it is downloaded, else none. The caller falls back from the computer to the phone when a call fails.
 */
object AiChoice {
    fun pick(enabled: Boolean, computerSet: Boolean, computerDown: Boolean, phoneModel: Boolean): AiSource = when {
        !enabled -> AiSource.NONE
        computerSet && !computerDown -> AiSource.COMPUTER
        phoneModel -> AiSource.PHONE
        else -> AiSource.NONE
    }

    /** The subtitle of the AI row in settings: which AI is in use. */
    fun label(enabled: Boolean, computerSet: Boolean, phoneModel: Boolean): String = when {
        !enabled -> "Off"
        computerSet -> "Computer"
        phoneModel -> "Phone"
        else -> "Phone, model not downloaded"
    }
}
