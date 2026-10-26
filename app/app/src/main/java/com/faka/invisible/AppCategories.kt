package com.faka.invisible

/**
 * Pure: where an app lives in the spoken tree. A place is "Group" or "Group/Sub" (what the person wants to do: Messages, Day, Money, Media, Out, Tools).
 * Order of trust: the table below (one line per package; edit it here), then words in the package name, then the system hint
 * (ApplicationInfo.category), then Tools/Dev. The built-in programs of the OS are in the table too (keys "hub:...").
 */
object AppCategories {
    const val DEFAULT = "Tools/Dev"

    /** `place  package`, one app per line. */
    private const val TABLE = """
Messages            hub:messages
Messages/Write      hub:write
Messages/Chat       com.whatsapp
Messages/Chat       com.microsoft.teams
Messages/Chat       com.tencent.mm
Messages/Chat       com.discord
Messages/Mail       com.google.android.gm
Messages/Calls      com.google.android.dialer
Messages/Chat       com.google.android.apps.messaging
Messages/Calls      com.google.android.apps.tachyon
Messages/Calls      com.google.android.contacts
Messages/Social     com.linkedin.android
Messages/Social     com.zhiliaoapp.musically
Day                 hub:today
Day                 hub:tasks
Day                 hub:timer
Day/Notes           com.google.android.calendar
Day/Notes           com.google.android.keep
Day/Notes           com.miui.notes
Day/Notes           com.android.deskclock
Day/Work            com.atlassian.android.jira.core
Day/Work            com.google.android.apps.docs
Day/Work            com.google.android.apps.docs.editors.docs
Day/Work            com.anthropic.claude
Day/Work            com.google.android.apps.bard
Money/Banks         com.bbva.bbvacontigo
Money/Banks         com.brubank
Money/Banks         es.bancosantander.apps
Money/Banks         com.myinvestor.es
Money/Banks         de.traderepublic.app
Money/Pay           com.revolut.revolut
Money/Pay           com.transferwise.android
Money/Pay           com.mercadopago.wallet
Money/Pay           com.westernunion.moneytransferr3app.es
Money/Pay           com.google.android.apps.walletnfcrel
Media/Listen         hub:music
Media/Listen         com.google.android.apps.youtube.music
Media/Listen         app.morphe.android.apps.youtube.music
Media/Listen         org.oxycblt.auxio
Media/Listen         com.miui.player
Media/Watch         com.google.android.videos
Media/Watch         com.miui.videoplayer
Media/Watch         com.google.android.apps.photos
Media/Watch         com.miui.gallery
Media/Watch         com.android.camera
Media/Play          hub:games
Media/Play          com.mojang.minecraftpe
Media/Play          com.valvesoftware.android.steam.community
Media/Play          com.xiaomi.glgm
Media/Play          com.duolingo
Media/Listen         com.sonkins.tguitar
Out/Move            com.google.android.apps.maps
Out/Move            com.ubercab
Out/Move            com.crtm.integrated
Out/Move            hub:compass
Out/Move            com.miui.compass
Out/Trips           com.airbnb.android
Out/Shop            com.amazon.mShop.android.shopping
Out/Shop            com.alibaba.aliexpresshd
Out/Shop            com.xiaomi.mipicks
Out/Health          com.maxsnr.workout
Out/Health          digifit.android.virtuagym.pro.synergym
Tools/System        hub:phone
Tools/Utilities     hub:calculator
Money/Prices        hub:sum
Tools/Utilities     com.miui.calculator
Day                 hub:alarm
Tools/System        com.android.settings
Tools/System        com.miui.securitycenter
Tools/System        com.google.android.apps.adm
Tools/System        com.google.android.apps.safetyhub
Tools/Setup         com.android.thememanager
Tools/Setup         com.orange.update
Media/Audio         com.mi.earphone
Tools/Setup         com.microsoft.launcher
Tools/Setup         com.android.vending
Money/Pay           com.google.android.apps.subscriptions.red
Tools/Setup         com.miui.miservice
Tools/Setup         com.mi.globalminusscreen
Tools/Security      com.x8bit.bitwarden
Tools/Security      com.azure.authenticator
Tools/Security      com.google.android.apps.authenticator2
Tools/Security      com.salesforce.authenticator
Tools/Web           com.android.chrome
Tools/Web           com.mi.globalbrowser
Tools/Web           org.chromium.webapk.a7e73b90ab8fcb825_v2
Tools/Web           com.google.android.googlequicksearchbox
Tools/Utilities     com.xiaomi.scanner
Media/Audio         com.android.soundrecorder
Tools/Utilities     com.google.android.apps.nbu.files
Tools/Utilities     com.mi.android.globalFileexplorer
Tools/Utilities     com.android.providers.downloads.ui
Day/Notes           com.miui.weather2
Tools/Dev           com.termux
Tools/Dev           com.tailscale.ipn
Tools/Dev           com.crosspaste.android
Tools/Dev           com.k2fsa.sherpa.onnx.tts.engine
Tools/Dev           app.morphe.manager
Tools/Dev           app.revanced.android.gms
Day/Work            com.themis.clientmobile
Tools/System        dev.jason.com.google.android.inputmethod.latin
"""

    /** Package -> place. */
    val EXACT: Map<String, String> = TABLE.lines().filter { it.isNotBlank() }.associate { l ->
        val t = l.trim(); t.substringAfterLast(' ') to t.substringBeforeLast(' ').trim()
    }

    /** Words in a package name, in order: the first row that matches wins (money first). */
    private val WORDS: List<Pair<String, List<String>>> = listOf(
        "Money/Pay" to listOf("bank", "banco", "pay", "wallet", "invest", "revolut", "bizum", "trade", "crypto", "binance", "coinbase"),
        "Messages/Chat" to listOf("whatsapp", "telegram", "signal", "messenger", "discord", "slack", "teams", "wechat", "viber"),
        "Messages/Mail" to listOf("mail", "outlook"),
        "Messages/Social" to listOf("instagram", "facebook", "twitter", "tiktok", "linkedin", "reddit", "snapchat", "mastodon"),
        "Media/Listen" to listOf("music", "spotify", "podcast"),
        "Media/Watch" to listOf("youtube", "video", "netflix"),
        "Media/Watch" to listOf("photos", "gallery"),
        "Media/Play" to listOf("game", "minecraft", "steam"),
        "Out/Move" to listOf("maps", "uber", "taxi", "cabify", "transport"),
        "Out/Shop" to listOf("shop", "amazon", "ebay"),
        "Day/Notes" to listOf("calendar"),
        "Day/Work" to listOf("jira", "docs", "notion", "trello", "office"),
    )

    // ApplicationInfo.CATEGORY_* values, kept as numbers so this file has no Android types.
    private val HINTS: Map<Int, String> = mapOf(
        0 to "Media/Play", 1 to "Media/Listen", 2 to "Media/Watch", 3 to "Media/Watch",
        4 to "Messages/Social", 5 to "Media/Watch", 6 to "Out/Move", 7 to "Day/Work",
    )

    /** [hint] = ApplicationInfo.category, -1 = none. */
    fun categoryOf(pkg: String, hint: Int = -1): String {
        EXACT[pkg]?.let { return it }
        val p = pkg.lowercase()
        WORDS.firstOrNull { (_, ws) -> ws.any { it in p } }?.let { return it.first }
        return HINTS[hint] ?: DEFAULT
    }

    fun main(place: String) = place.substringBefore('/')
    fun sub(place: String): String? = place.substringAfter('/', "").ifEmpty { null }
}
