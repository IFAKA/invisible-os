package com.faka.invisible

import org.junit.Assert.*
import org.junit.Test

class TreeEditsTest {
    private val a = AppLeaf("a", "A", "Tools/Dev", 0, 1.0)
    private val b = AppLeaf("b", "B", "Tools/Dev", 0, 1.0)

    @Test fun hiddenLeavesMovedChangesGroupPinnedIsHeavy() {
        val s = LauncherSettings(hidden = setOf("a"), moved = mapOf("b" to "Money"), pinned = setOf("b"))
        val r = TreeLeaves.edit(listOf(a, b), s)
        assertEquals(listOf("b"), r.map { it.pkg })
        assertEquals("Money", r[0].category)
        assertTrue(r[0].weight > 100)
    }

    @Test fun pinnedAppIsOnHomeAndHiddenAppIsNowhere() {
        val many = (1..30).map { AppLeaf("p$it", "P$it", "Tools/Dev", 0, 5.0) } + AppLeaf("z", "Zed", "Tools/Dev", 0, 0.1)
        fun names(n: TreeNode): List<String> = n.children.flatMap { if (it.app != null) listOf(it.app.pkg) else names(it) }
        val pinned = AppTree.build(TreeLeaves.edit(many, LauncherSettings(pinned = setOf("z"))))
        assertTrue(pinned.children.any { it.app?.pkg == "z" })
        assertFalse("z" in names(AppTree.build(TreeLeaves.edit(many, LauncherSettings(hidden = setOf("z"))))))
    }
}
