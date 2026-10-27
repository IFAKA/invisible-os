package com.faka.invisible

/**
 * Pure: where an app lives in the spoken tree. A place is "Group" or "Group/Sub" (what the person wants to do: Talk, Plan, Pay, Work, Watch and listen, Play, Go, Tools).
 * Order of trust: the table below (one line per package; edit it here), then words in the package name, then the system hint
 * (ApplicationInfo.category), then Tools/Setup. The built-in programs of the OS are in the table too (keys "hub:...").
 */
object AppCategories {
    const val DEFAULT = "Tools/Setup"

    /** `place  package`, one app per line. */
    private const val TABLE = """
Talk                hub:messages
Talk                hub:write
Talk/Chat           com.whatsapp
Work/Projects       com.slack
Talk/Chat           com.microsoft.teams
Talk/Chat           com.tencent.mm
Talk/Chat           com.discord
Talk/Chat           org.telegram.messenger
Talk/Chat           org.thoughtcrime.securesms
Talk/Chat           com.facebook.orca
Talk/Chat           com.Slack
Talk/Text           com.samsung.android.messaging
Talk/Text           com.android.mms
Talk/Social         com.instagram.android
Talk/Social         com.facebook.katana
Talk/Social         com.reddit.frontpage
Talk/Social         com.pinterest
Talk/Text           com.google.android.apps.messaging
Talk/Mail           com.google.android.gm
Talk/Mail           com.microsoft.office.outlook
Talk/Call           com.google.android.dialer
Talk/Call           com.zoom.videomeetings
Talk/Call           com.google.android.apps.tachyon
Talk/Call           com.google.android.contacts
Talk/Social         com.linkedin.android
Talk/Social         com.zhiliaoapp.musically
Plan                hub:today
Plan                hub:tasks
Plan/Time           hub:timer
Plan/Time           hub:alarm
Plan/Time           com.android.deskclock
Plan/Time           com.google.android.calendar
Plan/Time           com.miui.weather2
Plan/Notes          com.google.android.keep
Plan/Notes          com.google.android.apps.tasks
Plan/Notes          com.miui.notes
Pay/Banks Spain     com.bbva.bbvacontigo
Pay/Banks Latin America com.brubank
Pay/Banks Spain     es.bancosantander.apps
Pay/Banks Spain     com.myinvestor.es
Pay/Invest          de.traderepublic.app
Pay/Banks US        com.chase.sig.android
Pay/Banks US        com.infonow.bofa
Pay/Banks US        com.wf.wellsfargomobile
Pay/Banks US        com.konylabs.capitalone
Pay/Banks US        com.americanexpress.android.acctsvcs.us
Pay/Banks US        com.usaa.mobile.android.usaa
Pay/Banks UK and EU co.uk.getmondo
Pay/Banks UK and EU com.starlingbank.android
Pay/Banks UK and EU com.barclays.android.barclaysmobilebanking
Pay/Banks UK and EU uk.co.hsbc.hsbcukmobilebanking
Pay/Banks Latin America com.nu.production
Pay/Banks Latin America ar.com.santander.rio.mbanking
Pay/Banks Spain     com.bankinter.launcher
Pay/Banks Spain     es.lacaixa.mobile.android.newwapicon
Pay/Banks Spain     es.lacaixa.mobile.imaginemobile
Pay/Banks Spain     es.caixabank.caixabankmobile
Pay/Banks UK and EU de.number26.android
Pay/Send            com.revolut.revolut
Pay/Send            com.transferwise.android
Pay/Send            com.mercadopago.wallet
Pay/Send            com.westernunion.moneytransferr3app.es
Pay/Send            com.paypal.android.p2pmobile
Pay/Send            com.venmo
Pay/Send            com.squareup.cash
Pay/Invest          com.coinbase.android
Pay/Invest          com.binance.dev
Pay/Send            com.google.android.apps.walletnfcrel
Pay                 hub:sum
Work/AI             com.anthropic.claude
Work/AI             com.google.android.apps.bard
Work/Documents      com.google.android.apps.docs
Work/Documents      com.dropbox.android
Work/Documents      com.microsoft.office.word
Work/Documents      com.google.android.apps.docs.editors.docs
Work/Projects       com.atlassian.android.jira.core
Work/Projects       com.themis.clientmobile
Watch and listen/Music     hub:music
Watch and listen/Music com.spotify.music
Watch and listen/Music     com.google.android.apps.youtube.music
Watch and listen/Music     app.morphe.android.apps.youtube.music
Watch and listen/Music     org.oxycblt.auxio
Watch and listen/Music     com.miui.player
Watch and listen/Music     com.mi.earphone
Watch and listen/Video     com.google.android.videos
Watch and listen/Video com.google.android.youtube
Watch and listen/Video com.netflix.mediaclient
Watch and listen/Video     com.miui.videoplayer
Watch and listen/Camera    com.android.camera
Watch and listen/Camera    com.miui.gallery
Watch and listen/Camera    com.google.android.apps.photos
Watch and listen/Camera    com.android.soundrecorder
Play/Games          hub:games
Play/Games          com.mojang.minecraftpe
Play/Games          com.valvesoftware.android.steam.community
Play/Games          com.xiaomi.glgm
Play/Learn          com.duolingo
Play/Learn          com.sonkins.tguitar
Play/Fitness        com.maxsnr.workout
Play/Fitness        com.google.android.apps.fitness
Play/Fitness        digifit.android.virtuagym.pro.synergym
Play/Fitness        com.strava
Play/Fitness        com.myfitnesspal.android
Play/Fitness        com.fitbit.FitbitMobile
Play/Fitness        com.garmin.android.apps.connectmobile
Play/Fitness        com.freeletics.lite
Go/Move             com.google.android.apps.maps
Go/Taxi             com.ubercab
Go/Transit          com.crtm.integrated
Go/Transit          com.citymapper.app.release
Go/Taxi             com.cabify.rider
Go/Transit          com.tranzmate
Go/Taxi             me.lyft.android
Go/Taxi             ee.mtakso.client
Go/Taxi             taxi.android.client
Go/Taxi             com.comuto
Go/Move             hub:compass
Tools/Setup         hub:reset
Go/Move             hub:whereami
Go/Move             com.miui.compass
Go/Shop             com.amazon.mShop.android.shopping
Go/Shop             com.ebay.mobile
Go/Shop             com.alibaba.aliexpresshd
Go/Shop             com.xiaomi.mipicks
Go                  com.airbnb.android
Go                  com.booking
Tools/Web           com.android.chrome
Tools/Web           com.mi.globalbrowser
Tools/Web           org.chromium.webapk.a7e73b90ab8fcb825_v2
Tools/Web           com.google.android.googlequicksearchbox
Tools/Utilities     hub:calculator
Tools/Utilities     com.google.android.apps.translate
Work/Dev            hub:claude
Tools/Utilities     com.miui.calculator
Tools/Utilities     com.xiaomi.scanner
Tools/Utilities     com.google.android.apps.nbu.files
Tools/Utilities     com.mi.android.globalFileexplorer
Tools/Utilities     com.android.providers.downloads.ui
Tools/Safety        com.x8bit.bitwarden
Tools/Safety        com.azure.authenticator
Tools/Safety        com.google.android.apps.authenticator2
Tools/Safety        com.salesforce.authenticator
Tools/Safety        com.google.android.apps.safetyhub
Tools/Settings      hub:phone
Tools/Settings      com.android.settings
Tools/Settings      com.android.vending
Tools/Settings      com.android.thememanager
Tools/Settings      com.google.android.apps.adm
Tools/Settings      com.google.android.apps.subscriptions.red
Tools/Setup         com.miui.securitycenter
Tools/Setup         com.google.android.inputmethod.latin
Tools/Setup         com.orange.update
Tools/Setup         com.microsoft.launcher
Tools/Setup         com.miui.miservice
Tools/Setup         com.mi.globalminusscreen
Tools/Setup         dev.jason.com.google.android.inputmethod.latin
Tools/Developer     com.termux
Tools/Developer     com.tailscale.ipn
Tools/Developer     com.crosspaste.android
Tools/Developer     com.k2fsa.sherpa.onnx.tts.engine
Tools/Developer     app.morphe.manager
Tools/Developer     app.revanced.android.gms
"""

    /** Package -> place. */
    val EXACT: Map<String, String> = TABLE.lines().filter { it.isNotBlank() }.associate { l ->
        val t = l.trim(); t.substringAfterLast(' ') to t.substringBeforeLast(' ').trim()
    }

    /** Words in a package name, in order: the first row that matches wins (money first). */
    private val WORDS: List<Pair<String, List<String>>> = listOf(
        "Pay/Send" to listOf("bank", "banco", "pay", "wallet", "invest", "revolut", "bizum", "trade", "crypto", "binance", "coinbase"),
        "Talk/Chat" to listOf("whatsapp", "telegram", "signal", "messenger", "discord", "slack", "teams", "wechat", "viber"),
        "Talk/Mail" to listOf("mail", "outlook"),
        "Talk/Social" to listOf("instagram", "facebook", "twitter", "tiktok", "linkedin", "reddit", "snapchat", "mastodon"),
        "Watch and listen/Music" to listOf("music", "spotify", "podcast"),
        "Watch and listen/Video" to listOf("youtube", "video", "netflix"),
        "Watch and listen/Camera" to listOf("photos", "gallery"),
        "Play/Games" to listOf("game", "minecraft", "steam"),
        "Go/Move" to listOf("maps", "uber", "taxi", "cabify", "transport"),
        "Go/Shop" to listOf("shop", "amazon", "ebay"),
        "Plan/Time" to listOf("calendar"),
        "Work/Documents" to listOf("jira", "docs", "notion", "trello", "office"),
    )

    // ApplicationInfo.CATEGORY_* values, kept as numbers so this file has no Android types.
    private val HINTS: Map<Int, String> = mapOf(
        0 to "Play/Games", 1 to "Watch and listen/Music", 2 to "Watch and listen/Video", 3 to "Watch and listen/Video",
        4 to "Talk/Social", 5 to "Watch and listen/Video", 6 to "Go/Move", 7 to "Work/Documents",
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
