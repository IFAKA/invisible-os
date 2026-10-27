package com.faka.invisible

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.faka.invisible.voicekit.Translations

/**
 * UI Kit values: one calm system. Colours follow the system light or dark mode ([sync] reads it).
 * Spacing is an 8 dp grid. Type is the system font in four steps ([Type]).
 */
object Ui {
    @Volatile var dark = false
    /** Read the system mode from [c]; the Kit does this when it is made. */
    fun sync(c: Context) { dark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES }
    private fun pick(light: String, night: String) = Color.parseColor(if (dark) night else light)

    val ink get() = pick("#1C1C1E", "#FFFFFF")            // main text
    val soft get() = pick("#6C6C70", "#98989F")           // hints, captions
    val accent get() = pick("#0062CC", "#4DA3FF")         // the one accent for text: values, links, soft buttons (4.5:1 or more on page, card and tint, light and dark)
    val accentFill get() = pick("#0062CC", "#0A66C2")     // the accent as a button background: white text on it is 4.5:1 or more
    val good get() = pick("#17722F", "#32D74B")
    val bad get() = pick("#D70015", "#FF453A")
    val page get() = pick("#F2F2F7", "#000000")           // screen background
    val card get() = pick("#FFFFFF", "#1C1C1E")           // grouped surface
    val field get() = pick("#F2F2F7", "#2C2C2E")          // input box and task row, inside a card
    val tint get() = pick("#E5F0FF", "#0A2A4D")           // soft button
    val line get() = pick("#D9D9DE", "#38383A")           // hairline between rows
    val edge get() = pick("#C7C7CC", "#48484A")           // off switch, slider track
    val onAccent = Color.WHITE
    val clear = Color.TRANSPARENT

    // corner radius in dp
    const val RADIUS_CARD = 16
    const val RADIUS_ROW = 12
    const val RADIUS_CHIP = 12
    const val RADIUS_BUTTON = 14

    // 8 dp grid
    const val S1 = 8
    const val S2 = 16
    const val S3 = 24
    const val S4 = 32
    const val S6 = 48
}

/** The type scale: large title, title, headline, body, caption. System font. */
enum class Type(val size: Float, val face: String, val bold: Boolean = false) {
    LARGE(34f, "sans-serif", true), TITLE(22f, "sans-serif-medium"), HEAD(17f, "sans-serif-medium"),
    BODY(17f, "sans-serif"), SMALL(15f, "sans-serif"), CAPTION(13f, "sans-serif"), SECTION(13f, "sans-serif-medium"),
    MONO(15f, "monospace")
}

/** UI Kit parts (composable). Screens build only from these and the [Ui] values; a missing part is added here first. */
class Kit(private val c: Context) {
    init { Ui.sync(c) }
    fun dp(n: Int) = (n * c.resources.displayMetrics.density).toInt()
    private fun fill(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }
    private fun lp(w: Int = -1, h: Int = -2, top: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    fun text(t: String, type: Type = Type.BODY, color: Int = Ui.ink) = TextView(c).apply {
        text = t; textSize = type.size; setTextColor(color)
        typeface = Typeface.create(type.face, if (type.bold) Typeface.BOLD else Typeface.NORMAL)
        if (type == Type.LARGE) letterSpacing = -0.01f
        if (type == Type.LARGE || type == Type.TITLE || type == Type.SECTION) isAccessibilityHeading = true   // TalkBack can jump from title to title
        if (type == Type.BODY || type == Type.SMALL) setLineSpacing(0f, 1.12f)
    }

    /** A block of fixed-width text (a terminal screen): soft surface, wraps long lines, text can be selected. */
    fun mono(t: String) = text(t, Type.MONO).apply {
        setTextIsSelectable(true); setPadding(dp(Ui.S2), dp(Ui.S2), dp(Ui.S2), dp(Ui.S2)); surface(this)
    }

    /** The system bars take the page colour; the content stays clear of them. Call once from onCreate. */
    fun screen(a: Activity, body: View): ScrollView {
        a.window.statusBarColor = Ui.page; a.window.navigationBarColor = Ui.page
        @Suppress("DEPRECATION")
        a.window.decorView.systemUiVisibility = if (Ui.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        return ScrollView(c).apply {
            setBackgroundColor(Ui.page); isVerticalScrollBarEnabled = false; clipToPadding = false
            addView(body)
            setOnApplyWindowInsetsListener { v, i ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val b = i.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    v.setPadding(b.left, b.top, b.right, b.bottom); android.view.WindowInsets.CONSUMED
                } else {
                    @Suppress("DEPRECATION") v.setPadding(i.systemWindowInsetLeft, i.systemWindowInsetTop, i.systemWindowInsetRight, i.systemWindowInsetBottom); i
                }
            }
        }
    }

    /** A page comes in softly: fade and a small rise. */
    fun enter(v: View) {
        if (android.provider.Settings.Global.getFloat(c.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) return   // Reduce Motion
        v.alpha = 0f; v.translationY = dp(8).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    /** Filled button: the one main action of a page. */
    fun primary(label: String, onClick: () -> Unit) = Button(c).apply {
        text = label; isAllCaps = false; textSize = Type.HEAD.size; typeface = Typeface.create(Type.HEAD.face, Typeface.NORMAL)
        minHeight = dp(52); minimumHeight = dp(52); stateListAnimator = null; elevation = 0f
        setTextColor(Ui.onAccent); background = fill(Ui.accentFill, Ui.RADIUS_BUTTON)
        setOnClickListener { onClick() }
    }

    /** Quiet text button: Skip, Not now. No box. */
    fun quiet(label: String, color: Int = Ui.accent, onClick: () -> Unit) = Button(c).apply {
        text = label; isAllCaps = false; textSize = Type.BODY.size; minHeight = dp(48); minimumHeight = dp(48)
        stateListAnimator = null; elevation = 0f; setTextColor(color); background = null
        setOnClickListener { onClick() }
    }

    /** Soft button: [filled] is the main one, otherwise a tinted one. */
    fun btn(label: String, filled: Boolean = false, onClick: () -> Unit): Button =
        if (filled) primary(label, onClick) else Button(c).apply {
            text = label; isAllCaps = false; textSize = Type.SMALL.size; minHeight = dp(44); minimumHeight = dp(44)
            stateListAnimator = null; elevation = 0f
            setTextColor(Ui.accent); background = fill(Ui.tint, Ui.RADIUS_BUTTON)
            setOnClickListener { onClick() }
        }

    fun row(vararg v: View) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        v.forEach { addView(it) }
    }

    fun grow(v: View) = v.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }

    /** Text box on a soft field. No outline. */
    fun input(hintText: String) = android.widget.EditText(c).apply {
        hint = hintText; setHintTextColor(Ui.soft); setTextColor(Ui.ink); textSize = Type.BODY.size
        minLines = 2; maxLines = 5; gravity = Gravity.TOP or Gravity.START
        setPadding(dp(Ui.S2), dp(12), dp(Ui.S2), dp(12))
        background = fill(Ui.field, Ui.RADIUS_ROW)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }

    /** The grip of a draggable row; [visible] false keeps the space but hides it. */
    fun handle(widthDp: Int, visible: Boolean = true) = TextView(c).apply {
        text = "\u2261"; textSize = 24f; setTextColor(if (visible) Ui.soft else Ui.clear); gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(maxOf(widthDp, 48)), -1)   // a touch target is at least 48 dp
        if (visible) contentDescription = "Reorder"
    }

    /** An on/off switch in the app colour, 48 dp high, named for screen readers. */
    fun toggle(label: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) = Switch(c).apply {
        isChecked = on; isEnabled = enabled; minimumHeight = dp(48); contentDescription = label
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Ui.onAccent, Ui.onAccent))
        trackTintList = ColorStateList(states, intArrayOf(Ui.good, Ui.edge))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    /** A row with a label (and a quiet line under it) and a switch on the right. */
    fun toggleRow(parent: LinearLayout, label: String, note: String?, on: Boolean, onChange: (Boolean) -> Unit) {
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(label))
            if (note != null) addView(text(note, Type.CAPTION, Ui.soft).apply { setPadding(0, dp(2), 0, 0) })
        }
        val r = row(grow(box), toggle(label, on, true, onChange))
        r.minimumHeight = dp(56); r.setPadding(dp(Ui.S2), dp(Ui.S1), dp(Ui.S2), dp(Ui.S1))
        parent.addView(r, lp())
    }

    /** Ask before something that cannot be undone. */
    fun confirm(title: String, yes: String, onYes: () -> Unit) {
        AlertDialog.Builder(c).setTitle(title).setNegativeButton("Cancel", null).setPositiveButton(yes) { _, _ -> onYes() }.show()
    }

    /** Soft rounded background of a list row inside a card. */
    fun surface(v: View) { v.background = fill(Ui.field, Ui.RADIUS_ROW) }

    /** Top of a sub-page: a quiet back link, then the large title. */
    fun header(title: String, onBack: () -> Unit, backLabel: String = "Home"): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(quiet("\u2039 " + Translations.label(backLabel), onClick = onBack).apply { contentDescription = Translations.label("Back") + ", " + Translations.label(backLabel); minWidth = dp(64); setPadding(0, 0, dp(Ui.S2), 0); gravity = Gravity.START or Gravity.CENTER_VERTICAL },
            LinearLayout.LayoutParams(-2, dp(48)))
        addView(text(title, Type.LARGE), lp(top = Ui.S1))
    }

    /** One grouped section like iOS Settings: a small [header] above, a rounded surface, a quiet [footer] below. Rows are separated by hairlines. */
    fun group(parent: LinearLayout, header: String? = null, footer: String? = null, fill: LinearLayout.() -> Unit) {
        if (header != null) parent.addView(text(header, Type.SECTION, Ui.soft).apply { setPadding(dp(Ui.S2), 0, dp(Ui.S2), dp(6)) }, lp(top = Ui.S3))
        val g = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; background = fill(Ui.card, Ui.RADIUS_CARD) }
        g.fill()
        val kids = (0 until g.childCount).map { g.getChildAt(it) }
        g.removeAllViews()
        kids.forEachIndexed { i, v ->
            if (i > 0) g.addView(View(c).apply { setBackgroundColor(Ui.line) }, LinearLayout.LayoutParams(-1, maxOf(1, dp(1) / 2)).apply { marginStart = dp(Ui.S2) })
            g.addView(v)
        }
        parent.addView(g, lp(top = if (header == null) Ui.S3 else 0))
        if (footer != null) parent.addView(text(footer, Type.CAPTION, Ui.soft).apply { setPadding(dp(Ui.S2), dp(6), dp(Ui.S2), 0) }, lp())
    }

    /** A card with a header, optional hint under it, and free content with 16 dp inside. */
    fun card(parent: LinearLayout, title: String, hint: String? = null, pad: Boolean = true, fill: LinearLayout.() -> Unit) {
        group(parent, title, hint) {
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                if (pad) setPadding(dp(Ui.S2), dp(Ui.S2), dp(Ui.S2), dp(Ui.S2))
                fill()
            })
        }
    }

    /** A row of a group that opens a page: title, one quiet line for its state, a chevron. */
    fun navRow(parent: LinearLayout, title: String, summary: String, onClick: () -> Unit) {
        val r = row(grow(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title))
            addView(text(summary, Type.CAPTION, Ui.soft).apply { setPadding(0, dp(2), 0, 0) })
        }), text("\u203A", Type.TITLE, Ui.edge).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        r.contentDescription = "$title. $summary"
        r.minimumHeight = dp(64); r.setPadding(dp(Ui.S2), dp(Ui.S1), dp(Ui.S2), dp(Ui.S1))
        r.isClickable = true; r.setOnClickListener { onClick() }
        parent.addView(r, lp())
    }

    /** A navRow that starts with a coloured dot (green = reachable, grey = not, or not known yet). */
    fun dotRow(parent: LinearLayout, dot: Int, title: String, summary: String, onClick: () -> Unit) {
        val d = View(c).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dot) } }
        val r = row(d.apply { layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) } }, grow(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title))
            addView(text(summary, Type.CAPTION, Ui.soft).apply { setPadding(0, dp(2), 0, 0) })
        }), text("\u203A", Type.TITLE, Ui.edge).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        r.contentDescription = "$title. $summary"
        r.minimumHeight = dp(64); r.setPadding(dp(Ui.S2), dp(Ui.S1), dp(Ui.S2), dp(Ui.S1))
        r.isClickable = true; r.setOnClickListener { onClick() }
        parent.addView(r, lp())
    }

    /** A row of a group where exactly one choice is on: the title and a check mark. TalkBack hears "selected". */
    fun choiceRow(parent: LinearLayout, title: String, selected: Boolean, onClick: () -> Unit) {
        val r = row(grow(text(title)), text(if (selected) "\u2713" else "", Type.TITLE, Ui.accent).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        r.contentDescription = if (selected) title + ", " + Translations.label("selected") else title
        r.minimumHeight = dp(52); r.setPadding(dp(Ui.S2), dp(Ui.S1), dp(Ui.S2), dp(Ui.S1))
        r.isClickable = true; r.setOnClickListener { onClick() }
        parent.addView(r, lp())
    }

    /** Tabs: a row of equal soft buttons, the [selected] one filled. [onSelect] gets the index. */
    fun tabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        labels.forEachIndexed { i, l ->
            val b = Button(c).apply {
                text = l; isAllCaps = false; textSize = Type.SMALL.size; minHeight = dp(44); minimumHeight = dp(44)
                stateListAnimator = null; elevation = 0f; isSelected = i == selected
                setTextColor(if (i == selected) Ui.onAccent else Ui.accent); background = fill(if (i == selected) Ui.accentFill else Ui.tint, Ui.RADIUS_BUTTON)
                contentDescription = if (i == selected) l + ", " + Translations.label("selected") else l
                setOnClickListener { onSelect(i) }
            }
            addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(Ui.S1) })
        }
    }

    /** One line of fixed-width text in [color] (a line of a patch); long lines scroll sideways with the others. */
    fun monoLine(t: String, color: Int = Ui.ink) = TextView(c).apply {
        text = t; textSize = 13f; setTextColor(color); typeface = Typeface.MONOSPACE; setHorizontallyScrolling(true); setPadding(dp(Ui.S1), 0, dp(Ui.S1), 0)
    }

    /** A picture from its bytes (png, jpeg, webp, gif); fits the width. Null when the bytes are not an image. */
    fun picture(bytes: ByteArray, description: String): View? {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return android.widget.ImageView(c).apply { setImageBitmap(bmp); adjustViewBounds = true; scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; contentDescription = description }
    }

    /**
     * A web page. Cookies are on (the bridge sets one on the first load). A pull down from the top reloads it. [onProblem] gets a
     * plain sentence when the main page fails or the server answers with an error status.
     */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    fun web(url: String, onProblem: (String) -> Unit): View {
        val wv = android.webkit.WebView(c)
        wv.settings.javaScriptEnabled = true; wv.settings.domStorageEnabled = true
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        wv.setBackgroundColor(Ui.page)
        wv.webViewClient = object : android.webkit.WebViewClient() {
            override fun onReceivedError(v: android.webkit.WebView, r: android.webkit.WebResourceRequest, e: android.webkit.WebResourceError) { if (r.isForMainFrame) onProblem(e.description.toString()) }
            override fun onReceivedHttpError(v: android.webkit.WebView, r: android.webkit.WebResourceRequest, e: android.webkit.WebResourceResponse) { if (r.isForMainFrame && e.statusCode >= 500) onProblem(e.reasonPhrase ?: "Error ${e.statusCode}") }
        }
        wv.loadUrl(url)
        return object : android.widget.FrameLayout(c) {
            private var startY = -1f
            private var fired = false
            init { addView(wv, LayoutParams(-1, -1)) }
            override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startY = if (wv.scrollY == 0) e.y else -1f; fired = false }
                    MotionEvent.ACTION_MOVE -> if (startY >= 0f && !fired && e.y - startY > dp(96)) { fired = true; wv.reload() }
                }
                return false
            }
        }
    }

    /** A page that never scrolls itself: it fills the window and keeps the system bars clear. Put a [mirrorScreen] in it. */
    private var barsSeen: IntArray? = null   // the system bars the last screen got: a new screen starts with them, so its first frame is already in place (it used to start too high and drop)
    fun fixedScreen(a: Activity): LinearLayout {
        a.window.statusBarColor = Ui.page; a.window.navigationBarColor = Ui.page
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.page)
            val b0 = barsSeen ?: intArrayOf(0, 0, 0, 0)
            setPadding(b0[0] + dp(Ui.S2), b0[1] + dp(Ui.S2), b0[2] + dp(Ui.S2), b0[3] + dp(Ui.S2))
            setOnApplyWindowInsetsListener { v, i ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val b = i.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.ime())
                    barsSeen = intArrayOf(b.left, b.top, b.right, b.bottom)
                    v.setPadding(b.left + dp(Ui.S2), b.top + dp(Ui.S2), b.right + dp(Ui.S2), b.bottom + dp(Ui.S2)); android.view.WindowInsets.CONSUMED
                } else i
            }
            post { requestApplyInsets() }
        }
    }

    /** The mirror on screen: [update] moves the mark and the last press without rebuilding, so nothing jumps. */
    inner class MirrorView(private val rows: List<TextView>, private val scroller: ScrollView, private val press: TextView) {
        private var shown = -1
        fun update(current: Int, lastPress: String?) {
            rows.forEachIndexed { i, r ->
                val on = i == current
                r.setTextColor(if (on) Ui.accent else Ui.ink)
                r.background = if (on) fill(Ui.field, Ui.RADIUS_ROW) else null
                r.contentDescription = if (on) r.text.toString() + ", " + Translations.label("selected") else null
            }
            press.text = lastPress?.let { Translations.label("Last press") + ": " + Translations.label(it) } ?: ""
            if (current != shown) {
                val first = shown < 0; shown = current
                rows.getOrNull(current)?.let { r ->
                    fun centre() = (r.top - (scroller.height - r.height) / 2).coerceAtLeast(0)
                    if (first) scroller.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {   // before the first frame: the new list never shows at the top for a moment
                        override fun onPreDraw(): Boolean { if (scroller.height == 0) return true; scroller.viewTreeObserver.removeOnPreDrawListener(this); scroller.scrollTo(0, centre()); return true }
                    })
                    else scroller.post { scroller.smoothScrollTo(0, centre()) }   // the current item sits in the middle
                }
            }
        }
    }

    /**
     * The picture of the OS: where you are (title), the list being read with the current item marked (a tap chooses it, as a click would),
     * the last press, and three buttons that do what the button does. Fits the window; only the list scrolls.
     * Every row has the same size, so moving the mark never moves the layout.
     */
    fun mirrorScreen(parent: LinearLayout, title: String, items: List<String>, current: Int, lastPress: String?,
                     onPick: (Int, String) -> Unit, backLabel: String, onBack: () -> Unit, closeLabel: String, onClose: () -> Unit, moreLabel: String, onMore: () -> Unit): MirrorView {
        status(parent, title, Translations.label("Invisible OS is open."), Ui.good, Ui.S1)   // the same header as the idle home (dot, word, line): going between them moves nothing
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val rows = items.mapIndexed { i, t ->
            text(t, Type.BODY).apply {
                setPadding(dp(Ui.S2), dp(Ui.S1), dp(Ui.S2), dp(Ui.S1)); minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { onPick(i, t) }
                box.addView(this, lp(top = 2))
            }
        }
        if (items.isEmpty()) box.addView(text(Translations.label("Playing. Use your earbud button."), Type.BODY, Ui.soft).apply { setPadding(dp(Ui.S2), dp(Ui.S1), dp(Ui.S2), dp(Ui.S1)) })
        val scroller = ScrollView(c).apply { isVerticalScrollBarEnabled = false; addView(box) }
        parent.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(Ui.S2) })
        val press = text("", Type.CAPTION, Ui.soft).apply { minHeight = dp(20) }   // always there, so the list never changes height
        parent.addView(press, lp(top = Ui.S1))
        val bar = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, act) in listOf(backLabel to onBack, closeLabel to onClose, moreLabel to onMore))
            bar.addView(btn(label) { act() }.apply { minHeight = dp(52); minimumHeight = dp(52); setTextColor(Ui.ink); textSize = Type.BODY.size; background = fill(Ui.field, Ui.RADIUS_BUTTON) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        parent.addView(bar, lp(top = Ui.S2))
        return MirrorView(rows, scroller, press).also { it.update(current, lastPress) }
    }

    /** Home status: a coloured dot, a large word, one quiet line. */
    fun status(parent: LinearLayout, headline: String, line: String, color: Int, top: Int = Ui.S3, align: Boolean = false, fill: LinearLayout.() -> Unit = {}) {
        if (align) parent.addView(View(c), LinearLayout.LayoutParams(1, dp(48)))   // the room of a page's "‹ Home" line: the title then sits at the same height as on every page
        val dot = View(c).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) } }
        parent.addView(row(dot.apply { layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) } }, text(headline, Type.LARGE)), lp(top = top))
        parent.addView(text(line, Type.SMALL, Ui.soft), lp(top = Ui.S1))
        val extra = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        extra.fill()
        if (extra.childCount > 0) parent.addView(extra, lp(top = Ui.S2))
    }

    /** One onboarding step: a thin progress bar, a quiet step label, a large title, a plain line, one main button, one quiet one. */
    fun onboarding(parent: LinearLayout, progress: Float, stepLabel: String, title: String, line: String,
                   primaryLabel: String?, onPrimary: () -> Unit, secondaryLabel: String? = null, onSecondary: () -> Unit = {}, focusFirst: Boolean = false) {
        val track = FrameBar(progress).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        parent.addView(track, lp(h = dp(4), top = Ui.S3))
        val label = text(stepLabel, Type.SECTION, Ui.accent).apply { contentDescription = "$stepLabel. $title" }   // first thing TalkBack reads: where you are and what it is
        parent.addView(label, lp(top = Ui.S6))
        if (focusFirst) label.post {   // the step appears: TalkBack moves to it and reads it
            val am = c.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            if (am.isTouchExplorationEnabled) label.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
        parent.addView(text(title, Type.LARGE), lp(top = Ui.S1))
        parent.addView(text(line, Type.BODY, Ui.soft), lp(top = Ui.S2))
        if (primaryLabel != null) parent.addView(primary(primaryLabel, onPrimary), lp(top = Ui.S6))
        if (secondaryLabel != null) parent.addView(quiet(secondaryLabel, Ui.soft, onSecondary), lp(top = Ui.S1))
    }

    private inner class FrameBar(private val frac: Float) : View(c) {
        private val track = fill(Ui.line, 2); private val bar = fill(Ui.accent, 2)
        override fun onDraw(canvas: android.graphics.Canvas) {
            track.setBounds(0, 0, width, height); track.draw(canvas)
            bar.setBounds(0, 0, (width * frac.coerceIn(0.04f, 1f)).toInt(), height); bar.draw(canvas)
        }
    }

    /** The finished and coming steps, small and quiet. */
    fun stepList(parent: LinearLayout, items: List<Pair<String, String>>) {
        parent.addView(text(items.joinToString("\n") { (mark, name) -> "$mark  $name" }, Type.CAPTION, Ui.soft).apply {
            setLineSpacing(0f, 1.3f)
            contentDescription = items.joinToString(". ") { (mark, name) -> Translations.label(if (mark == "\u2713") "done" else "skipped") + " " + name }
        }, lp(top = Ui.S6))
    }

    /** A tinted button for one value (a timer length). */
    fun chip(label: String, onClick: () -> Unit) = Button(c).apply {
        text = label; isAllCaps = false; textSize = Type.TITLE.size; setTextColor(Ui.accent); stateListAnimator = null; elevation = 0f
        background = fill(Ui.tint, Ui.RADIUS_CHIP)
        setOnClickListener { onClick() }
    }

    fun slider(label: String, valueText: String, max: Int, now: Int, onCommit: (Int) -> Unit): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0)
        addView(row(grow(text(label)), text(valueText, Type.BODY, Ui.accent)))
        addView(SeekBar(c).apply {
            this.max = max; progress = now; minimumHeight = dp(48); contentDescription = "$label, $valueText"
            progressTintList = ColorStateList.valueOf(Ui.accent); thumbTintList = ColorStateList.valueOf(Ui.accent)
            progressBackgroundTintList = ColorStateList.valueOf(Ui.edge)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { onCommit(progress) }
            })
        })
    }

    /** Native-style wheels for hours and minutes. */
    fun pickTime(now: Int, set: (Int) -> Unit) {
        fun wheel(max: Int, v: Int) = NumberPicker(c).apply { minValue = 0; maxValue = max; value = v; wrapSelectorWheel = true; setFormatter { "%02d".format(it) } }
        val h = wheel(3, now / 60); val m = wheel(59, now % 60)
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(16), dp(16), dp(16), dp(8))
            addView(h); addView(text("h", Type.BODY, Ui.soft).apply { setPadding(dp(8), 0, dp(24), 0) })
            addView(m); addView(text("min", Type.BODY, Ui.soft).apply { setPadding(dp(8), 0, 0, 0) })
        }
        AlertDialog.Builder(c).setTitle("Timer length").setView(box)
            .setPositiveButton("Set") { _, _ -> set((h.value * 60 + m.value).coerceIn(1, 180)) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Drag [handle] to move its row inside [rows] (equal heights [rowH], draggable ones are 0..[last]). The others slide aside; [onMove] gets the positions when you let go. */
    fun makeDraggable(handle: View, rows: List<View>, last: Int, rowH: Int, onMove: (from: Int, to: Int) -> Unit) {
        val r = handle.parent as View
        var startY = 0f
        handle.setOnTouchListener { v, e ->
            val orig = rows.indexOf(r)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; v.parent.requestDisallowInterceptTouchEvent(true); r.elevation = dp(8).toFloat() }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - startY).coerceIn(-orig * rowH.toFloat(), (last - orig) * rowH.toFloat())
                    r.translationY = dy
                    val target = (orig + Math.round(dy / rowH)).coerceIn(0, last)
                    rows.forEachIndexed { i, o -> if (o !== r && i <= last) o.translationY = when {
                        orig < target && i in (orig + 1)..target -> -rowH.toFloat()
                        orig > target && i in target until orig -> rowH.toFloat()
                        else -> 0f } }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val target = (orig + Math.round(r.translationY / rowH)).coerceIn(0, last)
                    rows.forEach { it.translationY = 0f }; r.elevation = 0f
                    if (e.actionMasked == MotionEvent.ACTION_UP && target != orig) onMove(orig, target)
                }
            }
            true
        }
    }

    /**
     * Compact reorder list. [keys] = the ON items in order; off items follow. Drag the \u2261 handle: the other rows slide
     * aside while you move, and [onOrder] gets the new ON order when you let go. [locked] items cannot be switched off.
     */
    fun dragList(on: List<String>, names: Map<String, String>, locked: Set<String>, onOrder: (List<String>) -> Unit): View {
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val rowH = dp(52)
        val rows = ArrayList<View>()
        val all = on + names.keys.filter { it !in on }
        all.forEach { k ->
            val isOn = k in on
            val handle = handle(48, isOn).apply { layoutParams = LinearLayout.LayoutParams(dp(48), rowH) }
            val r = row(handle, grow(text(names[k]!!, Type.BODY, if (isOn) Ui.ink else Ui.soft)),
                toggle(names[k]!!, isOn, k !in locked) { v -> onOrder(if (v) on + k else on - k) })
            r.tag = k; r.minimumHeight = rowH
            box.addView(r, LinearLayout.LayoutParams(-1, rowH))
            rows.add(r)
            if (isOn) makeDraggable(handle, rows, on.size - 1, rowH) { from, to -> onOrder(on.toMutableList().also { it.add(to, it.removeAt(from)) }) }
        }
        return box
    }
}
