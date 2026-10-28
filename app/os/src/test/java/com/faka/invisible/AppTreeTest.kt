package com.faka.invisible

import org.junit.Assert.*
import org.junit.Test

class AppTreeTest {
    private val pkgs = javaClass.classLoader!!.getResource("phone_apps.txt")!!.readText().lines().map { it.trim() }.filter { it.isNotEmpty() && it != "com.faka.invisible" }
    private val builtins = listOf("hub:messages", "hub:today", "hub:tasks", "hub:timer", "hub:music", "hub:phone", "hub:games")

    @Test fun everyPhonePackageIsInATableGroup() {
        for (p in pkgs.filter { it != "com.faka.invisible" }) assertTrue("$p not in the table", p in AppCategories.EXACT)
        for (b in builtins) assertTrue(b, b in AppCategories.EXACT)
    }

    @Test fun knownApps() {
        fun g(p: String) = AppCategories.main(AppCategories.categoryOf(p))
        for (p in listOf("com.revolut.revolut", "com.chase.sig.android", "com.nu.production", "com.paypal.android.p2pmobile", "de.number26.android", "com.transferwise.android", "com.venmo", "com.bankinter.launcher", "com.coinbase.android")) assertEquals(p, "Pay", g(p))
        for (p in listOf("com.whatsapp", "com.microsoft.teams", "org.telegram.messenger", "com.discord", "com.google.android.apps.messaging", "com.linkedin.android", "com.zhiliaoapp.musically", "com.google.android.gm")) assertEquals(p, "Talk", g(p))
        for (p in listOf("com.google.android.calendar", "com.google.android.keep")) assertEquals(p, "Plan", g(p))
        for (p in listOf("com.google.android.apps.docs", "com.atlassian.android.jira.core")) assertEquals(p, "Work", g(p))
        for (p in listOf("com.android.settings", "com.miui.securitycenter", "com.x8bit.bitwarden")) assertEquals(p, "Tools", g(p))
    }

    @Test fun unknownPackageUsesMoneyWordsThenHintThenWordsThenDefault() {
        assertEquals("Pay/Send", AppCategories.categoryOf("com.some.newbank"))
        assertEquals("Play/Games", AppCategories.categoryOf("com.zzz.app", 0))
        assertEquals("Pay/Send", AppCategories.categoryOf("com.some.newbank", 7))   // money words come before the system hint
        assertEquals("Watch and listen/Photos", AppCategories.categoryOf("com.zzz.app", 3))
        assertEquals("Talk/Mail", AppCategories.categoryOf("com.zzz.mailer"))   // no hint: the words
        assertEquals("Go/Move", AppCategories.categoryOf("com.zzz.app", 6))
        assertEquals("Tools/Other", AppCategories.categoryOf("com.zzz.app", -1))
    }

    // ---------- the optimized tree ----------
    private val usage: Map<String, Pair<Int, Int>> = javaClass.classLoader!!.getResource("app_usage.tsv")!!.readText().lines().drop(1).filter { it.isNotBlank() }
        .associate { l -> l.split('\t').let { it[0] to (it[1].toInt() to it[3].toInt()) } }

    private fun leaf(p: String, w: Double, unread: Int = 0) = AppLeaf(p, title(p), AppCategories.categoryOf(p), unread, w)

    /** A readable name from a package (the phone gives real labels): the last part that is not a filler word, capitalised. */
    private val labels = mapOf("com.android.vending" to "Play Store", "com.android.thememanager" to "Themes", "com.google.android.apps.adm" to "Find My Device", "com.google.android.apps.subscriptions.red" to "Google One", "com.android.providers.downloads.ui" to "Downloads", "com.google.android.apps.nbu.files" to "Files by Google", "com.miui.calculator" to "Calculator", "com.mi.android.globalFileexplorer" to "File Manager", "com.xiaomi.scanner" to "Scanner", "com.google.android.apps.translate" to "Translate", "com.mi.globalminusscreen" to "App Vault", "com.miui.miservice" to "Services and feedback", "com.google.android.inputmethod.latin" to "Gboard", "com.microsoft.launcher" to "Microsoft Launcher", "com.miui.securitycenter" to "Security", "com.mi.globalbrowser" to "Mi Browser", "com.google.android.googlequicksearchbox" to "Google", "com.x8bit.bitwarden" to "Bitwarden", "com.google.android.apps.safetyhub" to "Personal Safety", "com.google.android.apps.authenticator2" to "Google Authenticator", "com.azure.authenticator" to "Microsoft Authenticator", "com.reddit.frontpage" to "Reddit", "com.zhiliaoapp.musically" to "TikTok", "com.facebook.katana" to "Facebook", "org.telegram.messenger" to "Telegram", "com.microsoft.teams" to "Teams", "com.zoom.videomeetings" to "Zoom", "com.google.android.contacts" to "Contacts", "com.google.android.apps.tachyon" to "Google Meet", "com.google.android.dialer" to "Phone dialer", "com.google.android.apps.messaging" to "Messages by Google", "com.google.android.gm" to "Gmail", "com.microsoft.office.outlook" to "Outlook", "com.google.android.youtube" to "YouTube", "com.miui.videoplayer" to "Mi Video", "com.netflix.mediaclient" to "Netflix", "com.google.android.videos" to "Google TV", "com.spotify.music" to "Spotify", "com.mi.earphone" to "Mi Earphones", "com.miui.player" to "Mi Music", "org.oxycblt.auxio" to "Auxio", "com.google.android.apps.youtube.music" to "YouTube Music", "com.miui.gallery" to "Gallery", "com.android.soundrecorder" to "Sound recorder", "com.google.android.apps.photos" to "Google Photos", "com.android.camera" to "Camera", "com.google.android.apps.maps" to "Google Maps", "com.miui.compass" to "Compass app", "com.amazon.mShop.android.shopping" to "Amazon", "com.alibaba.aliexpresshd" to "AliExpress", "com.ebay.mobile" to "eBay", "com.xiaomi.mipicks" to "GetApps", "com.citymapper.app.release" to "Citymapper", "com.ubercab" to "Uber", "com.miui.weather2" to "Weather", "com.android.deskclock" to "Clock", "com.google.android.apps.tasks" to "Google Tasks", "com.google.android.keep" to "Google Keep", "com.miui.notes" to "Notes", "com.google.android.apps.walletnfcrel" to "Google Wallet", "com.paypal.android.p2pmobile" to "PayPal", "com.nu.production" to "Nubank", "com.chase.sig.android" to "Chase", "com.microsoft.office.word" to "Word", "com.google.android.apps.docs.editors.docs" to "Google Docs", "com.google.android.apps.docs" to "Google Drive", "com.google.android.apps.bard" to "Gemini", "com.xiaomi.glgm" to "Game Center", "com.google.android.apps.fitness" to "Google Fit", "com.whatsapp" to "WhatsApp", "com.transferwise.android" to "Wise", "com.crtm.integrated" to "Metro Madrid", "com.tranzmate" to "Moovit", "com.cabify.rider" to "Cabify", "com.bbva.bbvacontigo" to "BBVA", "com.myfitnesspal.android" to "MyFitnessPal", "com.mercadopago.wallet" to "Mercado Pago", "com.coinbase.android" to "Coinbase", "com.binance.dev" to "Binance")   // the names a person sees on the phone

    private fun title(p: String): String {
        labels[p]?.let { return it }
        val skip = setOf("android", "app", "apps", "es", "core", "ui", "mobile", "red", "wallet", "client", "integrated", "community", "wallet", "latin", "manager", "m", "main", "mshop", "v2")
        val parts = p.removePrefix("hub:").split('.')
        val w = parts.reversed().firstOrNull { it !in skip && it.length > 2 } ?: parts.last()
        return w.replaceFirstChar { it.uppercase() }
    }

    /** All apps of the owner's phone (not this app) with weights from real launches, plus the OS programs. */
    private fun phone(): List<AppLeaf> = usage.filterKeys { it != AppWeights.SELF }.map { (p, u) -> leaf(p, AppWeights.weight(p, u.first, u.second, 0)) } +
        builtins.map { leaf(it, AppWeights.weight(it, 0, 0, 0)) }

    private fun lists(n: TreeNode): List<TreeNode> = listOf(n) + n.children.filter { !it.isApp }.flatMap { lists(it) }
    private fun dump(n: TreeNode, ind: String = ""): String = n.children.joinToString("") { c ->
        ind + c.label + (if (c.isApp) " [%.0f]".format(c.weight) else " (${c.children.size}) [%.0f]".format(c.weight)) + "\n" + (if (c.isApp) "" else dump(c, "$ind  "))
    }
    private fun flat(l: List<AppLeaf>) = TreeNode("", l.sortedWith(compareByDescending<AppLeaf> { it.weight }.thenBy { it.pkg }).map { TreeNode(it.label, app = it, weight = it.weight) })

    @Test fun liveAppsTakePromotedSlotsAndAreNeverPromoted() {
        val base = phone()
        val top = base.filter { it.pkg != "hub:music" }.sortedByDescending { it.weight }.take(3).map { it.pkg }   // the three heaviest would be promoted without live
        for (n in 0..3) {
            val liveSet = top.take(n).toSet()
            val root = AppTree.build(base.map { if (it.pkg in liveSet) it.copy(live = true) else it })
            val promoted = root.children.filter { it.isApp }.map { it.app!!.pkg }
            assertTrue("no live app on home: $promoted", promoted.none { it in liveSet })
            assertTrue("promoted ${promoted.size} with $n live", promoted.size <= 2 - n || promoted.isEmpty())
            assertTrue(root.children.size <= 8 + maxOf(0, 2 - n))
            assertTrue(liveSet.all { p -> AppTree.apps(root).any { it.pkg == p } })   // still in its group
        }
    }

    @Test fun weightUsesLaunchesNotMinutes() {
        assertEquals(1.0 + 3 * Math.log(17.0) + 5 * 2, AppWeights.weight("com.x", 10, 3, 2), 1e-9)
        assertEquals(1.0, AppWeights.weight("com.microsoft.launcher", 155, 122, 0), 1e-9)
        assertEquals(3.0, AppWeights.weight("hub:tasks", 0, 0, 0), 1e-9)
        assertEquals(1.0, AppWeights.weight("com.unseen", 0, 0, 0), 1e-9)
    }

    @Test fun report() {
        val l = phone(); assertEquals(93, l.size)
        val t0 = System.nanoTime()
        val opt = AppTree.build(l)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val fl = flat(l)
        val cOpt = AppTree.expectedCost(opt); val cFlat = AppTree.expectedCost(fl)
        println("REPORT build ${ms} ms, ${l.size} leaves")
        println("REPORT expected cost per open (items heard + 0.5 per level): flat=%.2f optimized=%.2f".format(cFlat, cOpt))
        println("REPORT expected items heard per open: flat=%.2f optimized=%.2f".format(AppTree.expectedHeard(fl), AppTree.expectedHeard(opt)))
        println("REPORT items heard to reach the top 5 apps (sum): flat=%.0f optimized=%.0f".format(AppTree.heardToTop(fl, 5), AppTree.heardToTop(opt, 5)))
        println("REPORT list lengths: " + lists(opt).map { (it.label.ifEmpty { "HOME" }) + "=" + it.children.size })
        println("REPORT depth=${AppTree.depth(opt)} maxList=${AppTree.maxListLength(opt)}")
        println("REPORT tree (label (length) [weight]):\n" + dump(opt))
        assertTrue("$cOpt <= $cFlat", cOpt <= cFlat + 1e-9)
        assertTrue(lists(opt).drop(1).all { it.children.size <= AppTree.MAX_LIST }); assertTrue(opt.children.size <= AppTree.HOME_MAX)
        val groups = TreeLeaves.GROUPS.toSet()
        assertEquals(groups, opt.children.map { it.label }.filter { it in groups }.toSet())   // every group is a direct entry of home
        assertTrue(opt.children.count { it.isApp } <= AppTree.PROMOTED)
        assertTrue("the tags must split every place so the letter fallback is never needed: " + lists(opt).map { it.label }.filter { " to " in it }, lists(opt).none { " to " in it.label })
        assertEquals(opt.children.size, opt.children.map { it.label }.distinct().size)
        println("REPORT top-5 vs flat: optimized=%.0f flat=%.0f".format(AppTree.heardToTop(opt, 5), AppTree.heardToTop(fl, 5)))
        assertTrue(AppTree.depth(opt) <= 4)
        assertEquals(l.map { it.pkg }.sorted(), AppTree.apps(opt).map { it.pkg }.sorted())
        assertTrue("build took $ms ms", ms < 1000)
    }

    @Test fun deterministic() {
        val l = phone()
        assertEquals(dump(AppTree.build(l)), dump(AppTree.build(l.reversed())))
    }

    @Test fun unreadNeverChangesOrder() {
        val l = phone()
        val noisy = l.map { if (it.pkg == "com.termux" || it.pkg == "org.telegram.messenger") it.copy(unread = 9) else it }
        assertEquals(dump(AppTree.build(l)), dump(AppTree.build(noisy)))
    }

    @Test fun heaviestFirstInEveryList() {
        val opt = AppTree.build(phone())
        for (x in lists(opt)) {
            val w = x.children.map { it.weight }
            assertEquals(w, w.sortedDescending())
        }
    }

    @Test fun anUntaggedPileIsCutByLetterNotBehindMore() {
        // 12 apps in one place: no "More"; two sorted sub-lists by first letter
        val l = (0 until 12).map { AppLeaf("a$it", "App${'A' + it}", "Tools/Other", 0, (100 - it).toDouble()) }
        val t = AppTree.build(l)
        assertTrue(lists(t).none { it.label == "More" })
        assertTrue(lists(t).drop(1).all { it.children.size <= AppTree.MAX_LIST })
        assertEquals(l.map { it.pkg }.sorted(), AppTree.apps(t).map { it.pkg }.sorted())
        assertTrue(lists(t).any { it.label.startsWith("Other ") })
    }

    @Test fun aHeavyLeafIsPromotedAndALightGroupStaysDown() {
        val l = (1..8).map { AppLeaf("m$it", "M$it", "Pay/Banks", 0, 1.0) } + AppLeaf("star", "Star", "Tools/Web", 0, 500.0) + (1..8).map { AppLeaf("d$it", "D$it", "Tools/Developer", 0, 1.0) }
        val t = AppTree.build(l)
        assertTrue(t.children.any { it.label == "Star" })
        assertTrue(t.children.size <= AppTree.HOME_MAX)
    }

    @Test fun emptyAndSingle() {
        assertTrue(AppTree.build(emptyList()).children.isEmpty())
        val one = AppTree.build(listOf(leaf("com.x", 1.0)))
        assertEquals(1, one.children.size)
    }


    // ---------- information scent: a group says the apps inside it ----------
    @Test fun groupScentNamesTheTopAppsAndCountsTheRest() {
        val l = listOf(AppLeaf("a", "Alpha", "Talk/Chat", 0, 50.0), AppLeaf("b", "Beta", "Talk/Chat", 0, 40.0), AppLeaf("c", "Gamma", "Talk/Mail", 0, 30.0),
            AppLeaf("d", "Delta", "Talk/Mail", 0, 20.0), AppLeaf("e", "Eps", "Talk/Social", 0, 10.0), AppLeaf("g1", "Maps", "Go/Move", 0, 5.0), AppLeaf("g2", "Uber", "Go/Taxi", 0, 4.0), AppLeaf("g3", "Cabify", "Go/Taxi", 0, 3.0), AppLeaf("w1", "Chrome", "Tools/Web", 0, 900.0), AppLeaf("w2", "Files", "Tools/Utilities", 0, 800.0))
        val t = AppTree.build(l)
        val talk = t.children.first { it.label == "Talk" }
        fun top(n: TreeNode): String = if (n.isApp) n.label else top(n.children.first())
        assertEquals(talk.children.take(2).map { top(it) }, talk.hint)   // the top app of the first two entries, a sub-group gives its top app
        assertEquals(AppTree.apps(talk).size - 2, talk.more)
        val go = t.children.first { it.label == "Go" }
        assertEquals(setOf("Maps", "Uber", "Cabify"), go.hint.toSet())   // 3 apps or fewer: all of them
        assertEquals(0, go.more)
        talk.children.filter { !it.isApp }.forEach { assertEquals(AppTree.apps(it).size, it.hint.size + it.more) }
    }

    @Test fun everyGroupHasAHintAndNoAppDoes() {
        fun check(n: TreeNode) { for (c in n.children) { if (c.isApp) assertTrue(c.hint.isEmpty()) else { assertTrue(c.label, c.hint.isNotEmpty()); assertEquals(AppTree.apps(c).size, c.hint.size + c.more); check(c) } } }
        check(AppTree.build(phone()))
    }

    /** For tools/tree_test.py: the tree as it is spoken, in both variants (short label, and group with the names inside), to app/build/tree_dump.json. */
    @Test fun writeTreeDump() {
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun names(n: TreeNode) = if (n.isApp) n.label else n.label + ": " + n.hint.joinToString(", ") + (if (n.more > 0) ", and ${n.more} more" else "")
        fun js(n: TreeNode): String = "{\"short\":${q(n.label)},\"names\":${q(names(n))},\"weight\":${n.weight},\"pkg\":${q(n.app?.pkg ?: "")},\"children\":[" + n.children.joinToString(",") { js(it) } + "]}"
        val f = java.io.File("../build/tree_dump.json"); f.parentFile.mkdirs()
        f.writeText(js(AppTree.build(phone())))
        assertTrue(f.length() > 1000)
    }
}
