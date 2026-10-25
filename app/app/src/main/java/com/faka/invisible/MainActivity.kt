package com.faka.invisible

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Setup checklist, then the settings of the launcher (the menu you hear). Plain views, rebuilt on every change. */
class MainActivity : Activity() {
    private lateinit var body: LinearLayout
    private var s = LauncherSettings()

    private val ink = Color.parseColor("#1B1F23")
    private val soft = Color.parseColor("#5F6872")
    private val accent = Color.parseColor("#2F5BEA")
    private val good = Color.parseColor("#1E8E4E")
    private val bad = Color.parseColor("#C5392B")

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        Actions.init(applicationContext)
        s = Actions.mode.settings
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(40), dp(16), dp(32)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#F3F4F7")); addView(body) })
        if (!EventLog.running) startForegroundService(Intent(this, ButtonService::class.java))
        EventLog.onChange = { render() }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!EventLog.running) startForegroundService(Intent(this, ButtonService::class.java))
        render()
    }

    override fun onDestroy() { EventLog.onChange = null; super.onDestroy() }

    private fun change(n: LauncherSettings) { s = n; Actions.saveSettings(n); render() }

    // ---- small view helpers ----
    private fun text(t: String, size: Float = 16f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun card(title: String, hint: String? = null, fill: LinearLayout.() -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat() }
        }
        c.addView(text(title, 18f, bold = true))
        if (hint != null) c.addView(text(hint, 14f, soft).apply { setPadding(0, dp(2), 0, dp(6)) })
        c.fill()
        body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun btn(label: String, filled: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; minHeight = dp(48); minimumHeight = dp(48)
        if (filled) { setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(24).toFloat() } }
        setOnClickListener { onClick() }
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        v.forEach { addView(it) }
    }

    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0) }

    private fun grow(v: View) = v.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }

    private fun slider(label: String, valueText: String, max: Int, now: Int, apply: (Int) -> LauncherSettings): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0)
        addView(row(grow(text(label, 15f)), text(valueText, 15f, accent, true)))
        addView(SeekBar(this@MainActivity).apply {
            this.max = max; progress = now
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { change(apply(progress)) }
            })
        })
    }

    // ---- screen ----
    private fun render() {
        body.removeAllViews()
        val notif = (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)
        val assist = (Settings.Secure.getString(contentResolver, "assistant") ?: "").contains(packageName)
        val a11y = Actions.accessibilityConnected
        val missing = listOf(a11y, notif, assist).count { !it }

        body.addView(text("Invisible", 30f, bold = true))
        body.addView(text("Control your phone with one earbud button. No screen needed.", 15f, soft))

        card(if (missing == 0) "Ready" else "$missing step${if (missing > 1) "s" else ""} left to set up",
            "Turn each one on once. Then this app works with the screen off.") {
            fun step(name: String, why: String, ok: Boolean, open: String) {
                addView(row(
                    grow(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text((if (ok) "✓  " else "✗  ") + name, 16f, if (ok) good else bad, true))
                        addView(text(why, 13f, soft).apply { setPadding(dp(24), 0, 0, 0) })
                    }),
                    if (ok) spacer() else btn("Turn on", true) { startActivity(Intent(open)) }))
            }
            step("Button access", "Hears your earbud button.", a11y, Settings.ACTION_ACCESSIBILITY_SETTINGS)
            step("Notification access", "Reads your new messages.", notif, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            step("Assistant", "A long press works on the lock screen.", assist, Settings.ACTION_VOICE_INPUT_SETTINGS)
            addView(row(grow(text(if (EventLog.running) "Listening for the button" else "Stopped", 15f, soft)),
                btn(if (EventLog.running) "Stop" else "Start") {
                    val svc = Intent(this@MainActivity, ButtonService::class.java)
                    if (EventLog.running) stopService(svc) else startForegroundService(svc)
                }))
        }

        card("Your menu", "The phone says these one by one. Click when you hear the one you want. Turn off what you never use.") {
            val shown = s.hub
            val rest = LauncherSettings.HUB_ITEMS.keys.filter { it !in shown }
            (shown + rest).forEach { k ->
                val on = k in shown
                val locked = k == "more"
                addView(row(
                    Switch(this@MainActivity).apply { isChecked = on; isEnabled = !locked; setOnCheckedChangeListener { _, v ->
                        change(s.copy(hub = LauncherSettings.cleanHub(if (v) s.hub + k else s.hub - k)))
                    } },
                    grow(text("  " + LauncherSettings.HUB_ITEMS[k] + if (locked) "  (has Exit, Torch)" else "", 16f, if (on) ink else soft)),
                    if (on && shown.indexOf(k) > 0) btn("↑") { move(k, -1) }.apply { minWidth = dp(48) } else spacer(),
                    if (on && shown.indexOf(k) < shown.size - 1) btn("↓") { move(k, 1) }.apply { minWidth = dp(48) } else spacer()))
            }
            addView(text("You hear: " + shown.joinToString(", ") { LauncherSettings.HUB_ITEMS[it]!! }, 14f, accent).apply { setPadding(0, dp(8), 0, 0) })
            addView(row(grow(text("Move the options you pick most to the front (automatic)", 15f)),
                Switch(this@MainActivity).apply { isChecked = s.learnOrder; setOnCheckedChangeListener { _, v -> change(s.copy(learnOrder = v)) } }))
        }

        card("Timer", "Three lengths in minutes. \"Last used\" is always added.") {
            s.timers.forEachIndexed { i, m ->
                fun set(v: Int) = change(s.copy(timers = LauncherSettings.cleanTimers(s.timers.toMutableList().also { it[i] = v })))
                addView(row(grow(text(Spoken.minutes(m), 16f)),
                    btn("−") { set(if (m > 10) m - 5 else m - 1) }.apply { minWidth = dp(56) },
                    btn("+") { set(if (m >= 10) m + 5 else m + 1) }.apply { minWidth = dp(56) }))
            }
        }

        card("Speed", "Find what feels right. You can change it any time.") {
            addView(slider("Pause between options", "%.1f s".format(s.itemGapMs / 1000f), 34, ((s.itemGapMs - 600) / 100).toInt()) {
                s.copy(itemGapMs = 600L + it * 100) })
            addView(slider("Voice speed", "%.1f x".format(s.speechRate), 12, ((s.speechRate - 0.6f) * 10).toInt()) {
                s.copy(speechRate = 0.6f + it / 10f) })
            addView(slider("Time to cancel a send", "%.1f s".format(s.undoMs / 1000f), 13, ((s.undoMs - 1500) / 500).toInt()) {
                s.copy(undoMs = 1500L + it * 500) })
            addView(slider("Rounds before it goes quiet", "${s.silentLoops}", 4, s.silentLoops - 1) { s.copy(silentLoops = it + 1) })
        }

        card("How to use it") {
            addView(text("Music playing or phone idle: click = play/pause, 2 = next, 3 = previous.\n" +
                "4 clicks, or hold: wake the menu.\n" +
                "In the menu: click when you hear the option. 2 = start over, 3 = back, 4 = leave. Hold = exit.", 14f, soft))
        }

        card("Last gestures") {
            addView(text(EventLog.items.joinToString("\n").ifEmpty { "None yet. Press your button." }, 14f, soft))
        }

        body.addView(btn("Reset settings") { change(LauncherSettings()) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
    }

    private fun move(k: String, d: Int) {
        val l = s.hub.toMutableList(); val i = l.indexOf(k); val j = i + d
        if (i < 0 || j !in l.indices) return
        l[i] = l[j].also { l[j] = l[i] }
        change(s.copy(hub = l))
    }
}
