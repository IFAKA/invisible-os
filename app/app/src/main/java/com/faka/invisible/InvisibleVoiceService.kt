package com.faka.invisible

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.util.Log

/** Registers us as a digital assistant so a long press (also on the lock screen) launches our code. */
class InvisibleVoiceService : VoiceInteractionService()

class InvisibleSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = InvisibleSession(this)
}

class InvisibleSession(private val ctx: android.content.Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, flags: Int) {
        super.onShow(args, flags)
        Log.d("Invisible", "ASSIST onShow flags=$flags")
        Actions.init(ctx)
        Actions.assistHold()
        hide()
        finish()
    }
}

/** Stub, only needed so the system accepts the assistant declaration. */
class InvisibleRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
