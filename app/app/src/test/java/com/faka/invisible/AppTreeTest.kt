package com.faka.invisible

import org.junit.Assert.*
import org.junit.Test

class AppTreeTest {
    private val pkgs = javaClass.classLoader!!.getResource("phone_apps.txt")!!.readText().lines().map { it.trim() }.filter { it.isNotEmpty() && it != "com.faka.invisible" }
    private val builtins = listOf("hub:messages", "hub:today", "hub:tasks", "hub:timer", "hub:music", "hub:phone", "hub:games")

    private fun leaf(p: String, i: Int, idle: Boolean = false, w: Double = 1.0 + (i * 7 % 3)) =
        AppLeaf(p, p.substringAfterLast('.'), AppCategories.categoryOf(p), if (idle) 0 else 1 + i % 3, w, native = idle)
    private fun leaves(idleEvery: Int = 0) = pkgs.mapIndexed { i, p -> leaf(p, i, idleEvery > 0 && i % idleEvery == 0) }

    private fun lists(n: TreeNode): List<TreeNode> = listOf(n) + n.children.filter { !it.isApp }.flatMap { lists(it) }
    private fun shape(n: TreeNode, d: Int = 1, out: MutableMap<Int, MutableList<Int>> = sortedMapOf()): Map<Int, List<Int>> {
        out.getOrPut(d) { mutableListOf() }.add(n.children.size)
        n.children.filter { !it.isApp }.forEach { shape(it, if (it.isMore) d else d + 1, out) }
        return out
    }
    private fun dump(n: TreeNode, ind: String = ""): String = n.children.joinToString("") { c ->
        ind + c.label + (if (c.isApp) "" else ", ${c.unread}") + "\n" + (if (c.isApp) "" else dump(c, "$ind  "))
    }

    @Test fun everyPhonePackageIsInATableGroup() {
        for (p in pkgs.filter { it != "com.faka.invisible" }) assertTrue("$p not in the table", p in AppCategories.EXACT)
        for (b in builtins) assertTrue(b, b in AppCategories.EXACT)
    }

    @Test fun knownApps() {
        fun g(p: String) = AppCategories.main(AppCategories.categoryOf(p))
        for (p in listOf("com.revolut.revolut", "com.bbva.bbvacontigo", "com.brubank", "com.mercadopago.wallet", "de.traderepublic.app", "com.transferwise.android", "com.westernunion.moneytransferr3app.es", "es.bancosantander.apps", "com.myinvestor.es")) assertEquals(p, "Money", g(p))
        for (p in listOf("com.whatsapp", "com.microsoft.teams", "com.tencent.mm", "com.discord", "com.google.android.apps.messaging", "com.linkedin.android", "com.zhiliaoapp.musically", "com.google.android.gm")) assertEquals(p, "Messages", g(p))
        for (p in listOf("com.google.android.calendar", "com.google.android.apps.docs", "com.google.android.keep", "com.atlassian.android.jira.core")) assertEquals(p, "Day", g(p))
        for (p in listOf("com.android.settings", "com.miui.securitycenter", "com.x8bit.bitwarden")) assertEquals(p, "Tools", g(p))
    }

    @Test fun unknownPackageUsesWordsThenHintThenDefault() {
        assertEquals("Money/Pay", AppCategories.categoryOf("com.some.newbank"))
        assertEquals("Media/Play", AppCategories.categoryOf("com.zzz.app", 0))
        assertEquals("Out/Move", AppCategories.categoryOf("com.zzz.app", 6))
        assertEquals("Tools/Dev", AppCategories.categoryOf("com.zzz.app", -1))
    }

    private fun all90(): List<AppLeaf> = leaves() + builtins.map { AppLeaf(it, it.removePrefix("hub:"), AppCategories.categoryOf(it), 0, 1.0, native = true) }

    @Test fun ninetyAppsAllActive() {
        for (l in listOf(leaves(), all90())) {
            val t = AppTree.build(l)
            println("SHAPE n=${l.size} (level -> list lengths): ${shape(t)}  depth=${AppTree.depth(t)} rawDepth=${AppTree.rawDepth(t)}")
            assertTrue(t.children.size <= AppTree.HOME)
            assertTrue(lists(t).drop(1).all { it.children.size <= AppTree.K })
            assertTrue("depth " + AppTree.depth(t), AppTree.depth(t) <= 3)
            val got = AppTree.apps(t).map { it.pkg }
            assertEquals(l.map { it.pkg }.sorted(), got.sorted())
        }
        println(dump(AppTree.build(all90())))
    }

    @Test fun deterministicAndUnreadBeforeIdle() {
        val l = leaves(idleEvery = 4)
        val a = AppTree.build(l); val b = AppTree.build(l.reversed())
        assertEquals(dump(a), dump(b))
        for (x in lists(a)) {
            val flags = x.children.map { it.unread > 0 }
            assertEquals(flags.sortedByDescending { it }, flags)
        }
        assertTrue(AppTree.apps(a).all { it.unread > 0 || it.native })
    }

    @Test fun appsWithoutNotificationsAreHidden() {
        val t = AppTree.build(listOf(AppLeaf("a", "A", "Tools/Dev", 0, 1.0), AppLeaf("b", "B", "Tools/Dev", 2, 1.0)))
        assertEquals(listOf("b"), AppTree.apps(t).map { it.pkg })
    }

    @Test fun treeIsCheaperThanFlatList() {
        val l = all90()
        val tree = AppTree.expectedCost(AppTree.build(l))
        val flat = AppTree.expectedCost(TreeNode("", l.filter { it.unread > 0 || it.native }.sortedWith(compareByDescending<AppLeaf> { it.unread > 0 }.thenByDescending { it.weight }.thenBy { it.label })
            .map { TreeNode(it.label, app = it, unread = it.unread, weight = it.weight) }))
        println("cost tree=$tree flat=$flat")
        assertTrue(tree < flat)
    }

    @Test fun threeAppsStayAFlatList() {
        val t = AppTree.build(listOf(AppLeaf("a", "A", "Money/Banks", 1, 1.0), AppLeaf("b", "B", "Money/Banks", 1, 1.0), AppLeaf("c", "C", "Tools/Dev", 1, 1.0)))
        assertEquals(3, t.children.size); assertTrue(t.children.all { it.isApp }); assertEquals(1, AppTree.depth(t))
    }

    @Test fun oneAppGroupIsMergedIntoTheParentList() {
        val l = listOf("a", "b", "c", "d").map { AppLeaf(it, it.uppercase(), "Money/Banks", 1, 1.0) } +
            AppLeaf("x", "X", "Tools/Dev", 1, 1.0) + AppLeaf("y", "Y", "Media/Play", 1, 1.0)
        val t = AppTree.build(l)
        assertTrue(t.children.any { it.isApp && it.label == "X" }); assertTrue(t.children.any { it.isApp && it.label == "Y" })
        assertEquals(listOf("Money"), t.children.filter { !it.isApp }.map { it.label })
    }

    @Test fun longSubGroupKeepsBestAndPutsRestInMore() {
        val l = (1..8).map { AppLeaf("p$it", "App$it", "Tools/Dev", 1, it.toDouble() / 10) } + AppLeaf("z", "Z", "Media/Play", 1, 1.0) + AppLeaf("q", "Q", "Money/Pay", 1, 1.0)
        val t = AppTree.build(l, home = 3)
        val dev = t.children.first { it.label == "Tools" || it.label == "Dev" }
        assertEquals(5, dev.children.size)
        assertEquals(listOf("App8", "App7", "App6", "App5"), dev.children.take(4).map { it.label })
        assertTrue(dev.children.last().isMore); assertEquals(4, dev.children.last().children.size)
    }

    @Test fun usedLeavesAreLiftedToHomeWhenThereIsRoom() {
        val l = listOf(leaf("com.whatsapp", 0, w = 1.0), leaf("com.brubank", 1, w = 1.0), leaf("com.google.android.gm", 2, w = 1.0), leaf("com.revolut.revolut", 3, w = 1.0),
            leaf("com.android.settings", 4, w = 1.0), leaf("com.discord", 5, w = 9.0), leaf("com.termux", 6, w = 1.0))
        val t = AppTree.build(l)
        assertTrue(t.children.first().isApp && t.children.first().label == "discord")
        assertTrue(t.children.size <= AppTree.HOME)
    }
}
