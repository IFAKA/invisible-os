package com.faka.invisible

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout

/** Dev only (debug builds): the design gallery. Start it with `AiTestReceiver --es show GALLERY`; see DESIGN.md. */
class GalleryActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val k = Kit(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        k.gallery(body)
        setContentView(k.screen(this, body))
    }
}
