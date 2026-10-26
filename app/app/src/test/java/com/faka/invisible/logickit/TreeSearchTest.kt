package com.faka.invisible.logickit

import com.faka.invisible.AppLeaf
import com.faka.invisible.TreeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TreeSearchTest {
    private fun leaf(name: String) = AppLeaf("hub:$name", name, "Tools/Sub", 0, 1.0)
    private fun app(name: String) = TreeNode(name, app = leaf(name))
    private val root = TreeNode("", listOf(
        TreeNode("Messages", listOf(app("Chat"))),
        TreeNode("Media", listOf(app("Music"), app("Volume")))))

    @Test fun pathOfAnApp() {
        val h = TreeSearch.search(root, null, "vol").single()
        assertEquals("Media (2nd) > Volume (2nd)", h.tree)
        assertNull(h.find)
    }

    @Test fun startsWithComesFirstAndEmptyQueryFindsNothing() {
        assertEquals(emptyList<Any>(), TreeSearch.search(root, null, "  "))
        assertEquals(listOf("Music"), TreeSearch.search(root, null, "MUS").map { it.app.label })
    }

    @Test fun findPathWalksRanges() {
        val plan = NameFinder.plan(listOf(leaf("Chat"), leaf("Music"), leaf("Volume")))
        assertEquals("Volume (3rd)", TreeSearch.search(root, plan, "volume").single().find)
    }

    @Test fun ordinals() { assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "22nd"), listOf(1, 2, 3, 4, 11, 12, 22).map(TreeSearch::ordinal)) }
}
