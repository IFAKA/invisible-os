package com.faka.invisible

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * The system's headset long-press (MediaSessionService) starts ACTION_VOICE_SEARCH_HANDS_FREE.
 * We answer it with no UI: run the HOLD action and close.
 */
class HandsFreeActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Log.d("Invisible", "HANDSFREE launched by system")
        Actions.init(this)
        Actions.assistHold()
        finish()
    }
}
