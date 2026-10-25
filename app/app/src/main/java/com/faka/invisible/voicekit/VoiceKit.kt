@file:OptIn(KitOnly::class)
package com.faka.invisible.voicekit

import android.content.ComponentName
import android.content.Context
import android.os.Handler

/**
 * FACADE. The one door to the Voice Kit. The OS calls [init] once; screens and programs use [narrator] and [buttons];
 * services that receive the button ask for [mediaKeys].
 */
object VoiceKit {
    lateinit var narrator: Narrator; private set
    lateinit var buttons: ButtonInput; private set
    private lateinit var app: Context
    private lateinit var handler: Handler
    private lateinit var watcher: ComponentName
    val ready get() = ::buttons.isInitialized

    /** [rate] = normal speech rate now; [watcher] = the notification listener component (lets the kit see other media sessions). */
    fun init(ctx: Context, handler: Handler, rate: () -> Float, listener: SpeechListener, target: ButtonTarget, watcher: ComponentName) {
        if (ready) return
        app = ctx.applicationContext; this.handler = handler; this.watcher = watcher
        val tones = TonePlayer(app, handler)
        val speech = TtsSpeech(app, handler, tones, rate, listener)
        narrator = Narrator(speech, tones, handler)
        buttons = ButtonInput(handler, narrator, target)
        speech.start()
    }

    /** The earbud button from the system media session. The caller starts it and stops it. */
    fun mediaKeys() = MediaKeyIntake(app, handler, buttons, watcher)
}
