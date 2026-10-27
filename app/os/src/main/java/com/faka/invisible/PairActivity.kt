package com.faka.invisible

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.faka.invisible.claude.Computers

/**
 * Opened by the phone's camera app when it scans the QR code that `bridge/setup.sh` prints (link `invisible-pair://...`).
 * It adds that computer at once (no screen, no questions) and says so; the phone then listens to that computer.
 */
class PairActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Actions.init(applicationContext)
        val c = Computers.parsePair(intent?.dataString.orEmpty())
        val said = if (c == null) "That is not a pair code." else {
            val r = Computers.addPaired(Actions.computers(), c)
            if (!r.already) Actions.setComputers(r.list)
            Actions.setActive(r.name)
            if (!r.already) Actions.suggestProfile(r.name)
            Computers.addedSentence(r)
        }
        Toast.makeText(applicationContext, said, Toast.LENGTH_LONG).show()
        finish()
    }
}
