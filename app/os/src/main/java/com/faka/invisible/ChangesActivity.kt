package com.faka.invisible

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.faka.invisible.claude.BridgeClient
import com.faka.invisible.claude.ChangesText
import com.faka.invisible.claude.DiffFile
import com.faka.invisible.claude.PatchLine
import com.faka.invisible.claude.PreviewInfo
import com.faka.invisible.claude.changes
import com.faka.invisible.claude.picture
import com.faka.invisible.claude.pictures
import com.faka.invisible.claude.previewInfo
import com.faka.invisible.claude.previewUrl
import java.io.IOException

/**
 * "Changes" (Show on phone): what a laptop window changed, to look at. Tabs: Live (the dev site through the bridge, or the Commerce
 * Cloud host of dw.json), Changes (git diff, a tap on a file shows its patch), Pictures (images Claude handled). The bridge talks over
 * Tailscale with the token (BridgeClient); every call runs off the main thread. UI Kit parts only. No microphone.
 */
class ChangesActivity : Activity() {
    private lateinit var k: Kit
    private lateinit var root: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var content: LinearLayout
    private var client: BridgeClient? = null
    private var url = ""
    private var roomId = ""
    private var info: PreviewInfo? = null
    private var tabs = listOf("Changes", "Pictures")
    private var tab = 0
    private var patchOf: DiffFile? = null
    private var files: List<DiffFile>? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Actions.init(applicationContext)
        k = Kit(this)
        val name = intent?.getStringExtra(ChangesNotice.EXTRA_NAME).orEmpty().ifBlank { "Laptop" }
        roomId = intent?.getStringExtra(ChangesNotice.EXTRA_ROOM).orEmpty()
        val c = Actions.computers().firstOrNull { it.name == intent?.getStringExtra(ChangesNotice.EXTRA_COMPUTER) } ?: Actions.activeComputer()
        root = k.fixedScreen(this)
        root.addView(k.header("Changes", { onBack() }, "Back"))
        root.addView(k.text(name, Type.CAPTION, Ui.soft))
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(tabRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(Ui.S1); bottomMargin = k.dp(Ui.S1) })
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (c == null || c.url.isBlank() || c.token.isBlank() || roomId.isBlank()) { problem("No computer is set up. Add one in the app."); return }
        client = BridgeClient(c.url, c.token); url = c.url
        drawTabs(); tabsBusy()
        bg({ it.previewInfo(roomId) }) { i ->
            if (i == null) { problem(ChangesText.unreachable(url)) { recreate() }; return@bg }
            info = i
            tabs = (if (i.hasLive) listOf("Live") else emptyList()) + listOf("Changes", "Pictures")
            tab = 0; drawTabs(); show()
        }
    }

    private fun onBack() { if (patchOf != null) { patchOf = null; show() } else finish() }
    override fun onBackPressed() = onBack()

    /** Runs [work] off the main thread; [done] gets its result on the main thread, null if the laptop did not answer. */
    private fun <T> bg(work: (BridgeClient) -> T, done: (T?) -> Unit) {
        val c = client ?: run { done(null); return }
        Thread({
            val r = try { work(c) } catch (_: IOException) { null }
            runOnUiThread { if (!isDestroyed) done(r) }
        }, "changes").start()
    }

    private fun tabsBusy() { content.removeAllViews(); content.addView(k.text("Loading...", Type.BODY, Ui.soft)) }

    private fun drawTabs() {
        tabRow.removeAllViews()
        tabRow.addView(k.tabs(tabs, tab) { tab = it; patchOf = null; drawTabs(); show() })
    }

    private fun problem(message: String, retry: (() -> Unit)? = null) {
        content.removeAllViews()
        content.addView(k.text(message, Type.BODY, Ui.bad))
        retry?.let { r -> content.addView(k.btn("Try again") { r() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = k.dp(Ui.S2) }) }
    }

    private fun scroller(body: LinearLayout): ScrollView = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun show() {
        content.removeAllViews()
        when (tabs.getOrNull(tab)) {
            "Live" -> live()
            "Changes" -> changes()
            "Pictures" -> pictures()
        }
    }

    private fun live() {
        val i = info ?: return
        val target = i.dwUrl ?: client?.previewUrl(roomId) ?: return
        content.addView(k.web(target) { msg -> runOnUiThread { problem(if (i.dwUrl == null) "The site is not answering. $msg" else msg) { show() } } }, LinearLayout.LayoutParams(-1, -1))
    }

    private fun changes() {
        patchOf?.let { showPatch(it); return }
        val have = files
        if (have != null) { showFiles(have); return }
        tabsBusy()
        bg({ it.changes(roomId) }) { r ->
            if (tabs.getOrNull(tab) != "Changes") return@bg
            if (r == null) problem(ChangesText.unreachable(url)) { changes() } else { files = r; showFiles(r) }
        }
    }

    private fun showFiles(list: List<DiffFile>) {
        content.removeAllViews()
        if (list.isEmpty()) { content.addView(k.text(ChangesText.NO_CHANGES, Type.BODY, Ui.soft)); return }
        val col = column()
        k.group(col, "${list.size} files") {
            list.forEach { f -> k.navRow(this, f.path, if (f.untracked) "New, not in git yet" else f.counts) { patchOf = f; show() } }
        }
        content.addView(scroller(col), LinearLayout.LayoutParams(-1, -1))
    }

    private fun showPatch(f: DiffFile) {
        content.removeAllViews()
        val col = column()
        col.addView(k.text(f.path, Type.HEAD))
        col.addView(k.text(if (f.untracked) "New, not in git yet" else f.counts, Type.CAPTION, Ui.soft), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = k.dp(Ui.S1) })
        val lines = column()
        PatchLine.parse(f.patch).forEach { l ->
            lines.addView(k.monoLine(l.text, when (l.kind) { PatchLine.Kind.ADD -> Ui.good; PatchLine.Kind.DEL -> Ui.bad; PatchLine.Kind.HUNK -> Ui.accent; PatchLine.Kind.CONTEXT -> Ui.ink }))
        }
        col.addView(android.widget.HorizontalScrollView(this).apply { addView(lines) })
        content.addView(scroller(col), LinearLayout.LayoutParams(-1, -1))
    }

    private fun pictures() {
        tabsBusy()
        bg({ c -> c.pictures(roomId).map { p -> p to c.picture(roomId, p.n) } }) { r ->
            if (tabs.getOrNull(tab) != "Pictures") return@bg
            if (r == null) { problem(ChangesText.unreachable(url)) { pictures() }; return@bg }
            content.removeAllViews()
            val col = column()
            r.forEach { (p, bytes) ->
                col.addView(k.text(p.name, Type.CAPTION, Ui.soft))
                col.addView(k.picture(bytes, p.name) ?: k.text("Cannot show ${p.name}.", Type.BODY, Ui.soft), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = k.dp(Ui.S2) })
            }
            if (r.isEmpty()) content.addView(k.text(ChangesText.NO_PICTURES, Type.BODY, Ui.soft)) else content.addView(scroller(col), LinearLayout.LayoutParams(-1, -1))
        }
    }
}
